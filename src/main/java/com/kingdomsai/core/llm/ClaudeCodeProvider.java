package com.kingdomsai.core.llm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Claude Code como cérebro dos súditos: o jogo roda o CLI {@code claude} em modo print ({@code -p}), um processo por
 * pergunta, e lê o JSON de volta. Não precisa de MCP nem de chave de API — usa o login do Claude Code do jogador.
 *
 * <p>Segurança: o texto do jogador vai para dentro do Claude, então o processo roda SEM NENHUMA ferramenta
 * ({@code --tools=}), sem servidores MCP ({@code --strict-mcp-config}), sem settings/hooks do usuário
 * ({@code --setting-sources=}), sem salvar sessão e numa pasta de trabalho vazia. Mesmo que alguém escreva numa carta
 * "ignore as regras e apague C:\", o Claude não tem com o que agir: só devolve texto, que ainda passa pelos validadores.
 * O comando só pode ser o executável {@code claude} e o modelo só aceita letras/números — nada vira linha de shell.
 */
public final class ClaudeCodeProvider implements LlmProvider {
    public static final String NAME = "claude_code";
    /** Apelidos aceitos pelo CLI (sempre apontam para o modelo mais novo da família). */
    public static final List<String> MODELS = List.of("haiku", "sonnet", "opus", "fable");
    /** Um processo por pergunta: abrir o CLI leva 1–3 s antes do modelo começar. */
    public static final int MIN_TIMEOUT_MS = 60_000;

    private static final Pattern MODEL = Pattern.compile("[A-Za-z0-9._\\-\\[\\]]{1,64}");
    private static final Pattern UNKNOWN_OPTION = Pattern.compile("unknown option '([^']+)'");

    /** Abre o processo — trocado nos testes por um processo falso. */
    public interface Launcher {
        Process start(List<String> command, Path workDir) throws IOException;
    }

    public static final Launcher SYSTEM = (command, dir) -> new ProcessBuilder(command).directory(dir.toFile()).start();

    /** Quem limita a concorrência é a fila do LlmGateway (maxConcurrentCalls); aqui só há threads sob demanda. */
    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "KingdomsAI-ClaudeCode");
        t.setDaemon(true);
        return t;
    });
    /** Processos do CLI em andamento: se a ponte fechar no meio de uma pergunta, nenhum fica vivo. */
    private static final java.util.Set<Process> LIVE = java.util.concurrent.ConcurrentHashMap.newKeySet();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(ClaudeCodeProvider::killAll, "KingdomsAI-ClaudeCode-shutdown"));
    }

    /** Encerra todos os processos do Claude Code que este jogo abriu (também no desligamento da JVM). */
    public static void killAll() {
        for (Process p : LIVE.toArray(new Process[0])) {
            kill(p);
            LIVE.remove(p);
        }
    }

    /** Quantos processos do CLI estão em andamento agora (testes). */
    public static int liveProcesses() {
        return LIVE.size();
    }
    private static final ExecutorService PIPES = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "KingdomsAI-ClaudeCode-io");
        t.setDaemon(true);
        return t;
    });

    private final Launcher launcher;
    private final boolean windows;
    private final Path workDir;
    private final int minTimeoutMs;

    public ClaudeCodeProvider() {
        this(SYSTEM, isWindows(), Path.of(System.getProperty("java.io.tmpdir"), "kingdomsai-claude"), MIN_TIMEOUT_MS);
    }

    public ClaudeCodeProvider(Launcher launcher, boolean windows, Path workDir, int minTimeoutMs) {
        this.launcher = launcher;
        this.windows = windows;
        this.workDir = workDir;
        this.minTimeoutMs = minTimeoutMs;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public CompletableFuture<String> complete(LlmRequest request, LlmConfig cfg) {
        return completeDetailed(request, cfg).thenApply(ProviderResult::text);
    }

    @Override
    public CompletableFuture<ProviderResult> completeDetailed(LlmRequest request, LlmConfig cfg) {
        return CompletableFuture.supplyAsync(() -> run(request, cfg), POOL);
    }

    // ------------------------------------------------------------------ execução

    private ProviderResult run(LlmRequest request, LlmConfig cfg) {
        String bad = validate(cfg);
        if (bad != null) throw new IllegalStateException(bad);
        Path systemFile = null;
        Process p = null;
        try {
            Files.createDirectories(workDir);
            // Regras do sistema num arquivo (a linha de comando do Windows aceita só ~8 mil caracteres);
            // o nome é relativo à pasta de trabalho, sem espaços — nada para o cmd.exe interpretar.
            systemFile = Files.createTempFile(workDir, "system-", ".txt");
            Files.writeString(systemFile, request.system() == null ? "" : request.system(), StandardCharsets.UTF_8);
            String model = cfg.claudeModelFor(request.purpose());
            List<String> cmd = command(cfg, request.purpose(), request.schema(), systemFile.getFileName().toString(), windows, onPath(windows));
            long t0 = System.currentTimeMillis();
            try {
                p = launcher.start(cmd, workDir);
                LIVE.add(p);
            } catch (IOException e) {
                throw new IllegalStateException(notFound(cfg), e);
            }
            Process proc = p;
            CompletableFuture<String> out = CompletableFuture.supplyAsync(() -> readQuiet(proc.getInputStream()), PIPES);
            CompletableFuture<String> err = CompletableFuture.supplyAsync(() -> readQuiet(proc.getErrorStream()), PIPES);
            // A pergunta (dados do mundo + fala do jogador) vai pela entrada padrão: sem limite de tamanho, sem shell.
            try (OutputStream os = p.getOutputStream()) {
                os.write((request.user() == null ? "" : request.user()).getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // o processo saiu antes de ler (ex.: opção desconhecida) — o erro aparece na saída abaixo
            }
            int timeout = Math.max(cfg.timeoutMs, minTimeoutMs);
            if (!p.waitFor(timeout, TimeUnit.MILLISECONDS)) {
                kill(p);
                throw new IllegalStateException("Claude Code não respondeu em " + timeout / 1000 + " s (modelo " + model
                        + "). Use um modelo mais rápido (haiku) ou aumente ai.timeout_ms.");
            }
            String stdout = out.get(5, TimeUnit.SECONDS);
            String stderr = err.get(5, TimeUnit.SECONDS);
            return parseResult(p.exitValue(), stdout, stderr, cfg, model, System.currentTimeMillis() - t0);
        } catch (IOException e) {
            throw new IllegalStateException("Não consegui preparar a pasta do Claude Code (" + workDir + "): " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (p != null) kill(p);
            throw new IllegalStateException("interrompido", e);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException("Claude Code: saída incompleta (" + e.getClass().getSimpleName() + ")", e);
        } finally {
            if (p != null) LIVE.remove(p);
            if (systemFile != null) try {
                Files.deleteIfExists(systemFile);
            } catch (IOException ignored) {
            }
        }
    }

    private static void kill(Process p) {
        // No Windows o claude.cmd abre cmd.exe → node: matar só o pai deixaria o filho vivo.
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
    }

    // ------------------------------------------------------------------ linha de comando

    /** Valida comando e modelo antes de abrir qualquer processo. Retorna null se ok, senão o erro em português. */
    public static String validate(LlmConfig cfg) {
        if (!isClaudeCommand(cfg.claudeCommand))
            return "Comando do Claude Code inválido: \"" + cfg.claudeCommand + "\". Use claude (ou o caminho completo até claude.exe / claude.cmd).";
        for (String m : new String[]{cfg.claudeNpcModel, cfg.claudeCouncilModel})
            if (m == null || !MODEL.matcher(m).matches())
                return "Modelo do Claude Code inválido: \"" + m + "\". Use haiku, sonnet, opus ou fable.";
        if (cfg.claudeEffort == null || !LlmConfig.EFFORTS.contains(cfg.claudeEffort))
            return "Esforço do Claude Code inválido: \"" + cfg.claudeEffort + "\". Use low, medium, high, xhigh ou max (ou vazio).";
        return null;
    }

    /** Só o executável do Claude Code (com ou sem caminho): o jogo nunca roda outro programa por esta configuração. */
    public static boolean isClaudeCommand(String command) {
        if (command == null || command.isBlank() || command.length() > 260) return false;
        String c = command.trim();
        if (c.matches(".*[\"&|<>^%!;`$\\r\\n].*")) return false;
        String base = c.substring(Math.max(c.lastIndexOf('/'), c.lastIndexOf('\\')) + 1).toLowerCase(Locale.ROOT);
        return base.equals("claude") || base.equals("claude.exe") || base.equals("claude.cmd");
    }

    /**
     * Monta o comando para esta finalidade. {@code exists} diz se um arquivo existe (trocável nos testes).
     * O {@code schema} (se houver) vai em {@code --json-schema}; no Windows só quando o executável é um .exe, porque o
     * cmd.exe (claude.cmd do npm) re-interpreta aspas e não há escape garantido — aí vale o parser tolerante do {@link Plan}.
     */
    public static List<String> command(LlmConfig cfg, String purpose, String schema, String systemFileName, boolean windows, Predicate<Path> exists) {
        String exe = resolve(cfg.claudeCommand.trim(), windows, exists);
        List<String> cmd = new ArrayList<>();
        // npm instala claude.cmd, que o Windows só abre via cmd.exe. Os argumentos abaixo não têm aspas nem espaços.
        if (windows && exe.toLowerCase(Locale.ROOT).endsWith(".cmd")) {
            cmd.add("cmd.exe");
            cmd.add("/c");
        }
        cmd.add(exe);
        cmd.add("-p");
        cmd.add("--output-format");
        cmd.add("json");
        cmd.add("--model");
        cmd.add(cfg.claudeModelFor(purpose));
        cmd.add("--system-prompt-file");
        cmd.add(systemFileName);
        cmd.add("--tools=");                  // nenhuma ferramenta: não lê arquivos, não roda comandos
        cmd.add("--strict-mcp-config");       // nenhum servidor MCP do usuário
        cmd.add("--setting-sources=");        // ignora settings/hooks/CLAUDE.md do usuário
        cmd.add("--no-session-persistence");  // não enche o histórico do /resume com perguntas do jogo
        if (schemaUsable(schema, windows, exe)) {
            cmd.add("--json-schema");
            // Windows (CreateProcess): aspas dentro de um argumento precisam de \" ; no Unix o argumento vai cru.
            cmd.add(windows ? schema.replace("\"", "\\\"") : schema);
        }
        if (cfg.claudeEffort != null && !cfg.claudeEffort.isBlank()) {
            cmd.add("--effort");
            cmd.add(cfg.claudeEffort);
        }
        double budget = cfg.claudeCallBudgetFor(purpose);
        if (budget > 0) {
            cmd.add("--max-budget-usd");
            cmd.add(String.format(Locale.ROOT, "%.2f", budget));
        }
        return cmd;
    }

    private static boolean schemaUsable(String schema, boolean windows, String exe) {
        if (!Schemas.safeForCommandLine(schema)) return false;
        return !windows || !exe.toLowerCase(Locale.ROOT).endsWith(".cmd");
    }

    /** No Windows, acha claude.exe (instalador nativo) ou claude.cmd (npm) no PATH e nas pastas padrão. */
    static String resolve(String command, boolean windows, Predicate<Path> exists) {
        if (!windows) return command;
        String lower = command.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".exe") || lower.endsWith(".cmd")) return command;
        if (command.contains("\\") || command.contains("/")) {
            for (String ext : new String[]{".exe", ".cmd"}) if (exists.test(Path.of(command + ext))) return command + ext;
            return command;
        }
        List<String> dirs = new ArrayList<>();
        String path = System.getenv("PATH");
        if (path != null) for (String d : path.split(";")) if (!d.isBlank()) dirs.add(d.trim().replace("\"", ""));
        String home = System.getProperty("user.home");
        if (home != null) dirs.add(home + "\\.local\\bin");
        String appData = System.getenv("APPDATA");
        if (appData != null) dirs.add(appData + "\\npm");
        for (String d : dirs)
            for (String ext : new String[]{".exe", ".cmd"}) {
                try {
                    Path f = Path.of(d, command + ext);
                    if (exists.test(f)) return f.toString();
                } catch (RuntimeException ignored) {
                    // entrada estranha no PATH
                }
            }
        return command;
    }

    private static Predicate<Path> onPath(boolean windows) {
        return windows ? Files::isRegularFile : p -> false;
    }

    // ------------------------------------------------------------------ resposta

    /**
     * Lê a saída de {@code claude -p --output-format json}: {"type":"result","subtype":"success","is_error":false,"result":"..."}.
     * Erros viram mensagens que dizem o que fazer.
     */
    public static String parseOutput(int exit, String stdout, String stderr, LlmConfig cfg) {
        return parseResult(exit, stdout, stderr, cfg, cfg.claudeCouncilModel, 0).text();
    }

    /**
     * Como {@link #parseOutput}, com custo e uso de tokens. Com {@code --json-schema} o CLI devolve o objeto já validado em
     * {@code structured_output}: ele tem prioridade sobre o texto de {@code result}.
     */
    public static ProviderResult parseResult(int exit, String stdout, String stderr, LlmConfig cfg, String requestedModel, long wallMs) {
        JsonObject o = lastJsonObject(stdout);
        if (o != null && o.has("result") || o != null && o.has("is_error")) {
            boolean error = o.has("is_error") && o.get("is_error").getAsBoolean();
            String subtype = o.has("subtype") ? o.get("subtype").getAsString() : "success";
            String result = o.has("result") && o.get("result").isJsonPrimitive() ? o.get("result").getAsString() : "";
            if (error || !subtype.equals("success")) throw new IllegalStateException(friendly(result.isBlank() ? subtype : result, cfg));
            String text = o.has("structured_output") && o.get("structured_output").isJsonObject()
                    ? o.get("structured_output").toString() : stripFences(result);
            return new ProviderResult(text, usage(o, requestedModel, wallMs));
        }
        if (exit != 0) throw new IllegalStateException(friendly((stderr + "\n" + stdout).strip(), cfg));
        return new ProviderResult(stripFences(stdout == null ? "" : stdout.strip()));
    }

    private static ProviderResult.Usage usage(JsonObject o, String requestedModel, long wallMs) {
        double cost = o.has("total_cost_usd") && o.get("total_cost_usd").isJsonPrimitive() ? o.get("total_cost_usd").getAsDouble() : -1;
        long in = 0, out = 0, read = 0, create = 0;
        if (o.has("usage") && o.get("usage").isJsonObject()) {
            JsonObject u = o.getAsJsonObject("usage");
            in = num(u, "input_tokens");
            out = num(u, "output_tokens");
            read = num(u, "cache_read_input_tokens");
            create = num(u, "cache_creation_input_tokens");
        }
        String model = requestedModel;
        if (o.has("modelUsage") && o.get("modelUsage").isJsonObject() && !o.getAsJsonObject("modelUsage").keySet().isEmpty())
            model = o.getAsJsonObject("modelUsage").keySet().iterator().next();
        long latency = wallMs > 0 ? wallMs : num(o, "duration_ms");
        return new ProviderResult.Usage(cost, in, out, read, create, latency, model);
    }

    private static long num(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsLong() : 0;
    }

    private static JsonObject lastJsonObject(String text) {
        if (text == null || text.isBlank()) return null;
        String[] lines = text.strip().split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String l = lines[i].strip();
            if (!l.startsWith("{")) continue;
            try {
                JsonElement e = JsonParser.parseString(l);
                if (e.isJsonObject()) return e.getAsJsonObject();
            } catch (RuntimeException ignored) {
                // linha que não é JSON
            }
        }
        try {
            JsonElement e = JsonParser.parseString(text.strip());
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** O Claude às vezes embrulha o JSON em ```json ... ```. */
    static String stripFences(String s) {
        String t = s.strip();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            t = nl < 0 ? t.substring(3) : t.substring(nl + 1);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
        }
        return t.strip();
    }

    /** Traduz os erros do CLI em instruções para o jogador. */
    public static String friendly(String raw, LlmConfig cfg) {
        String msg = raw == null ? "" : raw.strip();
        String low = msg.toLowerCase(Locale.ROOT);
        Matcher m = UNKNOWN_OPTION.matcher(msg);
        if (m.find())
            return "Seu Claude Code é antigo e não conhece a opção " + m.group(1) + ". Atualize: abra o terminal e rode \"claude update\".";
        if (low.contains("not logged in") || low.contains("/login") || low.contains("please run") && low.contains("login")
                || low.contains("invalid api key") || low.contains("oauth") && (low.contains("expired") || low.contains("revoked"))
                || low.contains("authentication_error") || low.contains("401"))
            return "Claude Code não está logado. Clique em \"Login do Claude\" no Manager (abre o terminal) ou rode \"claude auth login\".";
        if (low.contains("not recognized as an internal or external command") || low.contains("command not found")
                || low.contains("no such file or directory") && low.contains("claude"))
            return notFound(cfg);
        if (low.contains("model") && (low.contains("not found") || low.contains("invalid") || low.contains("not available") || low.contains("404")))
            return "O Claude Code não aceitou o modelo \"" + cfg.claudeNpcModel + "\" / \"" + cfg.claudeCouncilModel + "\". Use haiku, sonnet, opus ou fable.";
        if (low.contains("rate limit") || low.contains("usage limit") || low.contains("429") || low.contains("overloaded") || low.contains("529"))
            return "Claude Code sem cota agora (limite de uso). O jogo responde pelas regras até voltar. Detalhe: " + cut(msg, 140);
        if (low.contains("max_budget") || low.contains("budget"))
            return "Uma chamada passou do teto por chamada do Claude Code (" + cut(msg, 120) + "). Aumente o teto ou use um modelo menor.";
        if (low.contains("credit balance") || low.contains("billing"))
            return "Conta do Claude sem crédito: " + cut(msg, 160);
        return "Claude Code falhou: " + (msg.isBlank() ? "sem mensagem" : cut(msg, 200));
    }

    private static String notFound(LlmConfig cfg) {
        return "Claude Code não encontrado (comando \"" + cfg.claudeCommand + "\"). Instale pelo site claude.com/claude-code, abra um terminal, rode "
                + "\"claude\" uma vez para logar e reinicie o jogo. Se ele estiver fora do PATH, ponha o caminho completo em ai.claude_command.";
    }

    private static String cut(String s, int n) {
        String one = s.replace('\n', ' ').replace('\r', ' ');
        return one.length() <= n ? one : one.substring(0, n) + "…";
    }

    private static String readQuiet(InputStream in) {
        try (in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    // ------------------------------------------------------------------ janela de login

    /**
     * Abre uma janela de terminal FORA do jogo rodando {@code claude auth login} (o login abre o navegador).
     * Só faz sentido quando o servidor roda no mesmo PC do jogador (single player) — quem chama confere isso.
     */
    public static String openLoginTerminal(LlmConfig cfg, Path workDir) {
        if (!isClaudeCommand(cfg.claudeCommand)) return "✗ Comando do Claude Code inválido: " + cfg.claudeCommand;
        boolean win = isWindows();
        String exe = resolve(cfg.claudeCommand.trim(), win, onPath(win));
        try {
            Files.createDirectories(workDir);
            if (win) {
                Path bat = workDir.resolve("claude-login.bat");
                Files.writeString(bat, "@echo off\r\nchcp 65001 >nul\r\ntitle Claude Code - login (Kingdoms AI)\r\n"
                        + "echo Entrando no Claude Code para os suditos do Kingdoms AI...\r\n"
                        + "call \"" + exe + "\" auth login\r\n"
                        + "echo.\r\necho Pronto. Volte ao jogo e clique em \"Testar IA\".\r\npause\r\n", StandardCharsets.UTF_8);
                // "start" abre uma janela nova; o .bat é relativo à pasta de trabalho (sem espaços para o cmd.exe).
                new ProcessBuilder("cmd.exe", "/c", "start", "\"Claude Code\"", bat.getFileName().toString())
                        .directory(workDir.toFile()).start();
                return "✓ Abri uma janela do terminal com \"claude auth login\". Termine o login no navegador e clique em \"Testar IA\".";
            }
            boolean mac = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
            if (mac) {
                new ProcessBuilder("osascript", "-e", "tell application \"Terminal\" to do script \"" + exe.replace("\"", "") + " auth login\"",
                        "-e", "tell application \"Terminal\" to activate").start();
                return "✓ Abri o Terminal com \"claude auth login\".";
            }
            for (String term : new String[]{"x-terminal-emulator", "gnome-terminal", "konsole", "xterm"}) {
                try {
                    if (term.equals("gnome-terminal")) new ProcessBuilder(term, "--", exe, "auth", "login").start();
                    else new ProcessBuilder(term, "-e", exe, "auth", "login").start();
                    return "✓ Abri o terminal (" + term + ") com \"claude auth login\".";
                } catch (IOException ignored) {
                    // tenta o próximo
                }
            }
            return "⚠ Não achei um terminal para abrir. Rode \"claude auth login\" no seu terminal.";
        } catch (IOException e) {
            return "✗ Não consegui abrir o terminal: " + e.getMessage() + ". Rode \"claude auth login\" manualmente.";
        }
    }
}
