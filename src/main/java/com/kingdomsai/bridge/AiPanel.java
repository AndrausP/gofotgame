package com.kingdomsai.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.kingdomsai.core.KingdomsCore;
import com.kingdomsai.core.llm.ClaudeCodeProvider;
import com.kingdomsai.core.llm.LlmConfig;
import com.kingdomsai.core.llm.LlmLedger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Painel de IA da ponte: lê o estado do LlmGateway, reconfigura em tempo de execução (só valores de listas fechadas),
 * testa o provider e abre o login do Claude. A configuração escolhida vai para {@code ia.json} ao lado do save —
 * nunca guarda chave de API.
 */
public final class AiPanel {
    public static final String FILE = "ia.json";
    /** Providers que o painel oferece (id, rótulo). A chave de API chega na fase F7. */
    private static final List<String[]> PROVIDERS = List.of(
            new String[]{"mock", "Regras"},
            new String[]{"claude_code", "Claude"},
            new String[]{"anthropic_api", "Chave de API"},
            new String[]{"ollama", "Ollama"});
    private static final Pattern LOCAL_MODEL = Pattern.compile("[A-Za-z0-9._:\\-/]{1,64}");
    private static final Pattern ENDPOINT = Pattern.compile("https?://[A-Za-z0-9.\\-]{1,100}(:\\d{1,5})?(/[A-Za-z0-9._\\-/]{0,100})?");

    /** O que o arquivo guarda. */
    private record Saved(String provider, String npcModel, String councilModel, double budgetUsd, int callsPerMinute, String endpoint, String effort) {}

    private final KingdomsCore core;
    private final Gson gson = new Gson();
    private volatile Path saveDir;
    private volatile String lastTest = "";
    /** Abre a janela de login (trocado nos testes). */
    private volatile Function<LlmConfig, String> loginOpener = cfg ->
            ClaudeCodeProvider.openLoginTerminal(cfg, Path.of(System.getProperty("java.io.tmpdir"), "kingdomsai-claude"));

    public AiPanel(KingdomsCore core) {
        this.core = core;
    }

    public void setSaveDir(Path dir) {
        this.saveDir = dir;
    }

    public void setLoginOpener(Function<LlmConfig, String> opener) {
        this.loginOpener = opener;
    }

    // ------------------------------------------------------------------ status

    public Map<String, Object> status() {
        LlmConfig cfg = core.llm().config();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("op", "ai_status");
        m.put("provider", cfg.enabled ? cfg.provider.toLowerCase() : "mock");
        List<Object> provs = new ArrayList<>();
        for (String[] p : PROVIDERS) provs.add(Map.of("id", p[0], "label", p[1]));
        m.put("providers", provs);
        m.put("models", ClaudeCodeProvider.MODELS);
        m.put("npc_model", cfg.isClaudeCode() ? cfg.claudeNpcModel : cfg.npcModel);
        m.put("council_model", cfg.isClaudeCode() ? cfg.claudeCouncilModel : cfg.model);
        m.put("effort", cfg.claudeEffort);
        m.put("endpoint", cfg.endpoint);
        m.put("calls_per_minute", cfg.maxCallsPerMinute);
        m.put("budget_usd", cfg.budgetUsd);
        m.put("live", core.llm().live());
        m.put("text", core.llm().status());
        m.put("last_error", core.llm().lastError());
        m.put("last_test", lastTest);
        m.put("ledger", ledger(core.llm().ledger()));
        return m;
    }

    private static Map<String, Object> ledger(LlmLedger l) {
        Map<String, Object> out = new LinkedHashMap<>();
        double cost = 0;
        int calls = 0, fallbacks = 0, errors = 0;
        Map<String, Object> by = new LinkedHashMap<>();
        for (var e : l.snapshot().entrySet()) {
            LlmLedger.Entry v = e.getValue();
            cost += v.costUsd;
            calls += v.calls;
            fallbacks += v.fallbacks;
            errors += v.errors;
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("calls", v.calls);
            o.put("cost_usd", v.costUsd);
            o.put("avg_latency_ms", Math.round(v.avgLatencyMs()));
            o.put("fallbacks", v.fallbacks);
            o.put("errors", v.errors);
            by.put(e.getKey(), o);
        }
        out.put("calls", calls);
        out.put("cost_usd", cost);
        out.put("fallbacks", fallbacks);
        out.put("errors", errors);
        out.put("by_purpose", by);
        return out;
    }

    // ------------------------------------------------------------------ configurar

    /** Aplica a configuração pedida. Devolve o status novo, com {@code ok=false} e {@code error} se algo foi recusado (nada muda). */
    public Map<String, Object> configure(JsonObject req) {
        LlmConfig cfg = core.llm().config().copy();
        try {
            if (req.has("provider")) {
                String p = str(req, "provider");
                if (PROVIDERS.stream().noneMatch(x -> x[0].equals(p))) throw new IllegalArgumentException("Provider desconhecido: " + p);
                cfg.provider = p;
                cfg.enabled = !p.equals("mock");
                if (p.equals("claude_code") && cfg.budgetUsd <= 0 && !req.has("budget_usd")) cfg.budgetUsd = BridgeMain.DEFAULT_CLAUDE_BUDGET_USD;
            }
            boolean claude = cfg.isClaudeCode();
            if (req.has("npc_model")) {
                String v = str(req, "npc_model");
                if (claude) cfg.claudeNpcModel = claudeModel(v);
                else cfg.npcModel = localModel(v);
            }
            if (req.has("council_model")) {
                String v = str(req, "council_model");
                if (claude) cfg.claudeCouncilModel = claudeModel(v);
                else cfg.model = localModel(v);
            }
            if (req.has("effort")) {
                String v = str(req, "effort");
                if (!LlmConfig.EFFORTS.contains(v)) throw new IllegalArgumentException("Esforço inválido: " + v);
                cfg.claudeEffort = v;
            }
            if (req.has("budget_usd")) {
                double v = req.get("budget_usd").getAsDouble();
                if (Double.isNaN(v) || v < 0 || v > 100) throw new IllegalArgumentException("Teto de gasto fora de 0–100 US$.");
                cfg.budgetUsd = v;
            }
            if (req.has("calls_per_minute")) {
                int v = req.get("calls_per_minute").getAsInt();
                if (v < 1 || v > 120) throw new IllegalArgumentException("Chamadas por minuto fora de 1–120.");
                cfg.maxCallsPerMinute = v;
            }
            if (req.has("endpoint")) {
                String v = str(req, "endpoint");
                if (!ENDPOINT.matcher(v).matches()) throw new IllegalArgumentException("Endereço inválido: " + v);
                cfg.endpoint = v;
            }
            if (cfg.isClaudeCode()) {
                String bad = ClaudeCodeProvider.validate(cfg);
                if (bad != null) throw new IllegalArgumentException(bad);
            }
        } catch (RuntimeException e) {
            Map<String, Object> m = status();
            m.put("ok", false);
            m.put("error", e instanceof IllegalArgumentException ? e.getMessage() : "Pedido inválido: " + e.getMessage());
            return m;
        }
        core.llm().configure(cfg);
        lastTest = "";
        String saveError = save(cfg);
        Map<String, Object> m = status();
        m.put("ok", true);
        if (saveError != null) m.put("warning", saveError);
        return m;
    }

    private static String str(JsonObject o, String key) {
        return o.get(key).getAsString().trim();
    }

    private static String claudeModel(String v) {
        if (!ClaudeCodeProvider.MODELS.contains(v))
            throw new IllegalArgumentException("Modelo do Claude inválido: " + v + " (use " + String.join(", ", ClaudeCodeProvider.MODELS) + ").");
        return v;
    }

    private static String localModel(String v) {
        if (!v.isEmpty() && !LOCAL_MODEL.matcher(v).matches()) throw new IllegalArgumentException("Nome de modelo inválido: " + v);
        return v;
    }

    // ------------------------------------------------------------------ testar e entrar

    /** Roda o teste de ponta a ponta do provider atual; devolve o texto do resultado quando terminar. */
    public CompletableFuture<String> test() {
        return core.llm().test().thenApply(r -> {
            lastTest = r;
            return r;
        });
    }

    /** Abre o login do Claude no terminal do próprio PC. Só para quem conversa pela interface local. */
    public String login(boolean fromLoopback) {
        if (!fromLoopback) return "✗ O login só abre a partir do próprio PC.";
        LlmConfig cfg = core.llm().config().copy();
        cfg.provider = "claude_code";
        return loginOpener.apply(cfg);
    }

    // ------------------------------------------------------------------ arquivo ia.json

    private String save(LlmConfig cfg) {
        Path dir = saveDir;
        if (dir == null) return null;
        try {
            Files.createDirectories(dir);
            boolean claude = cfg.isClaudeCode();
            Saved s = new Saved(cfg.enabled ? cfg.provider : "mock", claude ? cfg.claudeNpcModel : cfg.npcModel, claude ? cfg.claudeCouncilModel : cfg.model,
                    cfg.budgetUsd, cfg.maxCallsPerMinute, cfg.endpoint, cfg.claudeEffort);
            Files.writeString(dir.resolve(FILE), gson.toJson(s), StandardCharsets.UTF_8);
            return null;
        } catch (IOException e) {
            return "Não consegui salvar a configuração da IA: " + e.getMessage();
        }
    }

    /** Carrega {@code ia.json} por cima da configuração dada (valores inválidos são ignorados). Retorna true se aplicou. */
    public static boolean loadInto(Path dir, LlmConfig cfg) {
        Path f = dir.resolve(FILE);
        if (!Files.isRegularFile(f)) return false;
        try {
            Saved s = new Gson().fromJson(Files.readString(f, StandardCharsets.UTF_8), Saved.class);
            if (s == null || s.provider() == null || PROVIDERS.stream().noneMatch(x -> x[0].equals(s.provider()))) return false;
            cfg.provider = s.provider();
            cfg.enabled = !s.provider().equals("mock");
            boolean claude = cfg.isClaudeCode();
            if (s.npcModel() != null) {
                if (claude && ClaudeCodeProvider.MODELS.contains(s.npcModel())) cfg.claudeNpcModel = s.npcModel();
                else if (!claude && (s.npcModel().isEmpty() || LOCAL_MODEL.matcher(s.npcModel()).matches())) cfg.npcModel = s.npcModel();
            }
            if (s.councilModel() != null) {
                if (claude && ClaudeCodeProvider.MODELS.contains(s.councilModel())) cfg.claudeCouncilModel = s.councilModel();
                else if (!claude && LOCAL_MODEL.matcher(s.councilModel()).matches()) cfg.model = s.councilModel();
            }
            if (s.budgetUsd() >= 0 && s.budgetUsd() <= 100) cfg.budgetUsd = s.budgetUsd();
            if (s.callsPerMinute() >= 1 && s.callsPerMinute() <= 120) cfg.maxCallsPerMinute = s.callsPerMinute();
            if (s.endpoint() != null && ENDPOINT.matcher(s.endpoint()).matches()) cfg.endpoint = s.endpoint();
            if (s.effort() != null && LlmConfig.EFFORTS.contains(s.effort())) cfg.claudeEffort = s.effort();
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
