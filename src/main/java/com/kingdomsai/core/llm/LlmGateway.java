package com.kingdomsai.core.llm;

import com.kingdomsai.core.KingdomsCore;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Gateway: limita chamadas, aplica timeout, refaz se o JSON vier inválido e cai para as regras
 * quando o modelo está indisponível. A LLM é uma camada adicional — o jogo nunca congela.
 */
public final class LlmGateway {
    public record Result(Plan plan, String provider, boolean fallback, String note) {}

    private final KingdomsCore core;
    private final MockProvider mock;
    private LlmConfig config = new LlmConfig();
    private LlmProvider provider;
    private LlmProvider claudeCode = new ClaudeCodeProvider();
    private LlmProvider api = new AnthropicApiProvider();
    private final ArrayDeque<Long> calls = new ArrayDeque<>();
    private volatile long downUntil;
    private volatile String lastError = "";
    private volatile String lastPrompt = "";
    private volatile String lastResponse = "";
    private final LlmLedger ledger = new LlmLedger();
    private volatile boolean budgetNoticeSent;

    // ---- fila única com prioridade (F3.4)
    private final Object lock = new Object();
    private final PriorityQueue<Job> queue = new PriorityQueue<>();
    private final Map<String, Job> byKey = new HashMap<>();
    private int inFlight;
    private long seq;
    private volatile int fused, staleDropped;

    /** Resposta descartada porque o mundo andou enquanto o pedido esperava na fila. */
    static final class StaleRequestException extends RuntimeException {
        StaleRequestException() {
            super("pedido vencido", null, false, false);
        }
    }

    private static final class Job implements Comparable<Job> {
        final int priority;
        final long seq, createdTick;
        final LlmRequest req;
        final LlmConfig cfg;
        final String key;
        final CompletableFuture<ProviderResult> future = new CompletableFuture<>();

        Job(int priority, long seq, long createdTick, LlmRequest req, LlmConfig cfg, String key) {
            this.priority = priority;
            this.seq = seq;
            this.createdTick = createdTick;
            this.req = req;
            this.cfg = cfg;
            this.key = key;
        }

        @Override
        public int compareTo(Job o) {
            return priority != o.priority ? Integer.compare(priority, o.priority) : Long.compare(seq, o.seq);
        }
    }

    /** Prioridade (menor = primeiro): fala do rei > conselho/rival > arquiteto > agenda > conversa entre súditos. */
    public static int priorityOf(String purpose) {
        if (purpose == null) return 2;
        return switch (purpose) {
            case "npc_dialogue", "test" -> 0;
            case "council", "rival_king", "military", "repair" -> 1;
            case "architect" -> 2;
            case "agenda" -> 3;
            case "conversa" -> 4;
            default -> 2;
        };
    }

    /** Quantos ticks um pedido de fundo pode esperar na fila antes de perder o sentido (0 = nunca vence). */
    public static long maxAgeTicks(String purpose) {
        if (purpose == null) return 0;
        return switch (purpose) {
            case "agenda" -> 20L * 90;
            case "conversa" -> 20L * 12;
            default -> 0;
        };
    }

    public int fusedRequests() {
        return fused;
    }

    public int staleDropped() {
        return staleDropped;
    }

    /**
     * Entra na fila única. Pedidos de fundo com a mesma chave (finalidade + NPC) ainda pendentes ou em andamento são fundidos
     * (todos recebem a mesma resposta); pedidos que esperam além do tempo de vida da finalidade são descartados sem chamar o provider.
     */
    private CompletableFuture<ProviderResult> dispatch(LlmRequest req, boolean dedupe) {
        String key = dedupe && req.npcId() != null ? req.purpose() + ":" + req.npcId() : null;
        CompletableFuture<ProviderResult> result;
        synchronized (lock) {
            if (key != null) {
                Job existing = byKey.get(key);
                if (existing != null) {
                    fused++;
                    return existing.future;
                }
            }
            Job job = new Job(priorityOf(req.purpose()), seq++, core.tick(), req, config, key);
            queue.add(job);
            if (key != null) byKey.put(key, job);
            result = job.future;
        }
        pump();
        return result;
    }

    /** Dispara o que cabe na concorrência configurada; descarta o que venceu na fila. */
    private void pump() {
        java.util.List<Job> start = new java.util.ArrayList<>();
        java.util.List<Job> stale = new java.util.ArrayList<>();
        synchronized (lock) {
            // vencidos saem da fila já (não esperam a vez para descobrir que perderam o sentido)
            for (java.util.Iterator<Job> it = queue.iterator(); it.hasNext(); ) {
                Job j = it.next();
                long maxAge = maxAgeTicks(j.req.purpose());
                if (maxAge > 0 && core.tick() - j.createdTick > maxAge) {
                    it.remove();
                    if (j.key != null) byKey.remove(j.key);
                    staleDropped++;
                    ledger.recordStale(j.req.purpose());
                    stale.add(j);
                }
            }
            while (inFlight < Math.max(1, config.maxConcurrentCalls) && !queue.isEmpty()) {
                inFlight++;
                start.add(queue.poll());
            }
        }
        for (Job j : stale) j.future.completeExceptionally(new StaleRequestException());
        for (Job j : start) run(j);
    }

    private void run(Job j) {
        long t0 = System.currentTimeMillis();
        CompletableFuture<ProviderResult> call;
        try {
            call = provider.completeDetailed(j.req, j.cfg).orTimeout(j.cfg.effectiveTimeoutMs() + 500L, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            call = CompletableFuture.failedFuture(e);
        }
        call.whenComplete((res, err) -> {
            synchronized (lock) {
                inFlight--;
                if (j.key != null) byKey.remove(j.key);
            }
            long ms = System.currentTimeMillis() - t0;
            if (err != null) {
                ledger.recordError(j.req.purpose(), ms);
                j.future.completeExceptionally(err);
            } else {
                ProviderResult.Usage u = res.usage();
                ProviderResult timed = u.latencyMs() > 0 ? res : new ProviderResult(res.text(), new ProviderResult.Usage(u.costUsd(), u.inputTokens(),
                        u.outputTokens(), u.cacheReadTokens(), u.cacheCreationTokens(), ms, u.model()));
                record(j.req.purpose(), timed); // uma vez por chamada real, mesmo com pedidos fundidos
                j.future.complete(timed);
            }
            pump();
        });
    }

    public LlmGateway(KingdomsCore core) {
        this.core = core;
        this.mock = new MockProvider(core);
        configure(config);
    }

    public LlmLedger ledger() {
        return ledger;
    }

    /** O gasto estimado da sessão passou do teto configurado (0 = sem teto)? */
    public boolean overBudget() {
        return config.budgetUsd > 0 && ledger.totalCostUsd() >= config.budgetUsd;
    }

    public void configure(LlmConfig cfg) {
        this.config = cfg;
        budgetNoticeSent = false;
        this.provider = switch (cfg.provider.toLowerCase()) {
            case "ollama" -> new HttpProviders.Ollama();
            case "openai", "lmstudio", "custom" -> new HttpProviders.OpenAiCompatible();
            case ClaudeCodeProvider.NAME, "claude", "claudecode" -> claudeCode;
            case AnthropicApiProvider.NAME -> api;
            default -> mock;
        };
        downUntil = 0;
    }

    public LlmConfig config() {
        return config;
    }

    /** Troca o provider de API (testes usam um servidor falso). */
    public void setApiProvider(LlmProvider p) {
        this.api = p;
        configure(config);
    }

    /** Troca o executor do Claude Code (testes usam um processo falso). */
    public void setClaudeCodeProvider(LlmProvider p) {
        this.claudeCode = p;
        configure(config);
    }

    public String status() {
        if (!config.enabled) return "LLM desligada (modo regras)";
        if (provider == mock) return "Provider: regras (mock)";
        long now = System.currentTimeMillis();
        String s = provider == claudeCode || provider == api
                ? "IA: Claude · modelos " + config.claudeNpcModel + " (súditos) / " + config.claudeCouncilModel + " (conselho) · comando " + config.claudeCommand
                : "Provider: " + provider.name() + " · modelo " + config.model + " · " + config.endpoint;
        if (overBudget()) s += String.format(java.util.Locale.ROOT, " · orçamento de IA atingido (US$ %.2f); usando regras", ledger.totalCostUsd());
        else if (now < downUntil) s += " · indisponível (tentando de novo em " + (downUntil - now) / 1000 + "s; usando regras)";
        if (!lastError.isEmpty()) s += " · último erro: " + lastError;
        return s;
    }

    /** Lista modelos do servidor configurado (não bloqueia). */
    public CompletableFuture<java.util.List<String>> listModels() {
        if (provider == mock) return CompletableFuture.completedFuture(java.util.List.of("(modo regras: sem modelos)"));
        if (provider == claudeCode) return CompletableFuture.completedFuture(ClaudeCodeProvider.MODELS);
        return HttpProviders.listModels(config);
    }

    /** Teste de ponta a ponta: manda um prompt mínimo e mede a latência. */
    public CompletableFuture<String> test() {
        if (!config.enabled || provider == mock) return CompletableFuture.completedFuture("LLM desligada/mock: o jogo usa as regras.");
        long t0 = System.currentTimeMillis();
        LlmRequest req = new LlmRequest("test", "Responda apenas com JSON.", "Responda exatamente: {\"reply\":\"ok\",\"actions\":[]}",
                null, null, "teste");
        return dispatch(req, false).handle((res, err) -> {
            long ms = System.currentTimeMillis() - t0;
            String raw = res == null ? "" : res.text();
            if (err != null) {
                Throwable c = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
                return "✗ Falhou em " + ms + " ms: " + (c instanceof TimeoutException ? "timeout" : c.getMessage());
            }
            try {
                Plan.parse(raw);
                downUntil = 0;
                return "✓ " + (provider == claudeCode ? "IA: Claude" : provider.name()) + " / " + config.activeModel() + " respondeu JSON válido em " + ms + " ms.";
            } catch (RuntimeException e) {
                return "⚠ Respondeu em " + ms + " ms, mas não em JSON válido: " + raw.substring(0, Math.min(120, raw.length()));
            }
        });
    }

    public String lastError() {
        return lastError;
    }

    public String lastPrompt() {
        return lastPrompt;
    }

    public String lastResponse() {
        return lastResponse;
    }

    /** Há um modelo de verdade respondendo agora (não as regras)? */
    public boolean live() {
        if (!config.enabled || provider == mock || System.currentTimeMillis() < downUntil) return false;
        if (overBudget()) {
            announceBudget();
            return false;
        }
        return true;
    }

    /**
     * Chamada de fundo (vida dos súditos: agenda, conversa entre eles). Não cai para as regras do rei e respeita o limite
     * global deixando folga para as falas do rei. Devolve null se não deu (quem chama usa as próprias regras).
     */
    public CompletableFuture<Plan> background(LlmRequest req) {
        return backgroundRaw(req).thenApply(raw -> {
            if (raw == null) return null;
            try {
                return Plan.parse(raw);
            } catch (RuntimeException e) {
                return null;
            }
        });
    }

    /** Como {@link #background}, devolvendo o texto bruto do modelo (null se não deu). */
    public CompletableFuture<String> backgroundRaw(LlmRequest req) {
        if (!live()) return CompletableFuture.completedFuture(null);
        long now = System.currentTimeMillis();
        while (!calls.isEmpty() && now - calls.peekFirst() > 60_000) calls.pollFirst();
        if (calls.size() >= Math.max(1, config.maxCallsPerMinute - 5)) return CompletableFuture.completedFuture(null);
        calls.addLast(now);
        return dispatch(req, true).handle((res, err) -> {
            return err != null ? null : res.text(); // falha ou pedido vencido: quem chamou segue pelas regras
        });
    }

    public CompletableFuture<Result> submit(LlmRequest req) {
        if (!config.enabled || provider == mock) return CompletableFuture.completedFuture(new Result(mock.interpret(req), mock.name(), false, ""));
        long now = System.currentTimeMillis();
        if (now < downUntil) return fallback(req, "modelo indisponível");
        if (overBudget()) {
            announceBudget();
            return fallback(req, "orçamento de IA da sessão atingido");
        }
        while (!calls.isEmpty() && now - calls.peekFirst() > 60_000) calls.pollFirst();
        if (calls.size() >= config.maxCallsPerMinute) return fallback(req, "limite de chamadas por minuto");
        calls.addLast(now);
        lastPrompt = "SYSTEM:\n" + req.system() + "\n\nUSER:\n" + req.user();
        return attempt(req, 0);
    }

    private CompletableFuture<Result> attempt(LlmRequest req, int tryNo) {
        LlmRequest effective = tryNo == 0 ? req
                : req.withUser(req.user() + "\n\nATENÇÃO: sua resposta anterior não era JSON válido. Responda SOMENTE o objeto JSON.");
        return dispatch(effective, false)
                .handle((res, err) -> {
                    String raw = res == null ? null : res.text();
                    if (err != null) {
                        Throwable cause = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
                        lastError = cause instanceof TimeoutException ? "timeout"
                                : cause instanceof IllegalStateException ? cause.getMessage() : cause.getClass().getSimpleName() + ": " + cause.getMessage();
                        downUntil = System.currentTimeMillis() + 60_000;
                        return fallback(req, lastError);
                    }
                    lastResponse = raw;
                    try {
                        Plan p = Plan.parse(raw);
                        lastError = "";
                        return CompletableFuture.completedFuture(new Result(p, provider.name(), false, ""));
                    } catch (RuntimeException parseError) {
                        lastError = "JSON inválido";
                        if (tryNo < config.maxRetries) return attempt(req, tryNo + 1);
                        return fallback(req, "JSON inválido após " + (tryNo + 1) + " tentativa(s)");
                    }
                }).thenCompose(f -> f);
    }

    private void record(String purpose, ProviderResult res) {
        ledger.record(purpose, res.usage());
        ledger.recordOption(config.optionId(), res.usage());
        core.state().aiSpentUsd = ledger.totalCostUsd();
        core.state().aiCalls = ledger.totalCalls();
    }

    /** Avisa uma única vez que o teto de gasto foi atingido e que o jogo segue pelas regras. */
    private void announceBudget() {
        if (budgetNoticeSent) return;
        budgetNoticeSent = true;
        String msg = String.format(java.util.Locale.ROOT, "Orçamento de IA da sessão atingido: US$ %.2f. Usando regras.", ledger.totalCostUsd());
        lastError = msg;
        core.mainThread().execute(() -> core.bus().publish(core.tick(), com.kingdomsai.core.event.EventType.AI_DECISION,
                com.kingdomsai.core.event.GameEvent.Severity.WARN, null, null, msg));
    }

    private CompletableFuture<Result> fallback(LlmRequest req, String why) {
        ledger.recordFallback(req.purpose());
        if (!config.fallbackToRules)
            return CompletableFuture.completedFuture(new Result(new Plan("(Sem resposta: " + why + ")", java.util.List.of()), "nenhum", true, why));
        // O interpretador lê o estado do jogo: precisa rodar na thread principal.
        CompletableFuture<Result> f = new CompletableFuture<>();
        core.mainThread().execute(() -> {
            try {
                f.complete(new Result(mock.interpret(req), mock.name(), true, why));
            } catch (RuntimeException e) {
                f.completeExceptionally(e);
            }
        });
        return f;
    }
}
