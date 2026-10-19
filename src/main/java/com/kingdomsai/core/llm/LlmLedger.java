package com.kingdomsai.core.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Livro-caixa da IA: chamadas, custo, tokens e latência por finalidade, na sessão. Thread-safe (as respostas chegam
 * nas threads do provider). Com login de assinatura o custo é a estimativa do próprio CLI; com chave de API é cobrança real.
 */
public final class LlmLedger {
    /** Totais de uma finalidade. */
    public static final class Entry {
        public int calls, fallbacks, errors;
        public double costUsd;
        public long inputTokens, outputTokens, cacheReadTokens, cacheCreationTokens, latencyMs;
        /** Chamadas cujo custo o provider informou (a média de custo usa só estas). */
        public int pricedCalls;

        public double avgLatencyMs() {
            int ok = calls - errors;
            return ok <= 0 ? 0 : (double) latencyMs / ok;
        }

        public double fallbackRate() {
            return calls + fallbacks == 0 ? 0 : (double) fallbacks / (calls + fallbacks);
        }

        Entry copy() {
            Entry e = new Entry();
            e.calls = calls;
            e.fallbacks = fallbacks;
            e.errors = errors;
            e.costUsd = costUsd;
            e.inputTokens = inputTokens;
            e.outputTokens = outputTokens;
            e.cacheReadTokens = cacheReadTokens;
            e.cacheCreationTokens = cacheCreationTokens;
            e.latencyMs = latencyMs;
            e.pricedCalls = pricedCalls;
            return e;
        }
    }

    private final Map<String, Entry> byPurpose = new TreeMap<>();

    private Entry of(String purpose) {
        return byPurpose.computeIfAbsent(purpose == null || purpose.isBlank() ? "outro" : purpose, k -> new Entry());
    }

    /** Uma chamada ao provider que terminou (com ou sem custo conhecido). */
    public synchronized void record(String purpose, ProviderResult.Usage u) {
        Entry e = of(purpose);
        e.calls++;
        e.inputTokens += u.inputTokens();
        e.outputTokens += u.outputTokens();
        e.cacheReadTokens += u.cacheReadTokens();
        e.cacheCreationTokens += u.cacheCreationTokens();
        e.latencyMs += u.latencyMs();
        if (u.known()) {
            e.costUsd += u.costUsd();
            e.pricedCalls++;
        }
    }

    /** Chamada que falhou (timeout, CLI quebrado, JSON inválido). */
    public synchronized void recordError(String purpose, long latencyMs) {
        Entry e = of(purpose);
        e.calls++;
        e.errors++;
        e.latencyMs += latencyMs;
    }

    /** O pedido caiu para as regras. */
    public synchronized void recordFallback(String purpose) {
        of(purpose).fallbacks++;
    }

    public synchronized double totalCostUsd() {
        double s = 0;
        for (Entry e : byPurpose.values()) s += e.costUsd;
        return s;
    }

    public synchronized int totalCalls() {
        int s = 0;
        for (Entry e : byPurpose.values()) s += e.calls;
        return s;
    }

    public synchronized Map<String, Entry> snapshot() {
        Map<String, Entry> out = new TreeMap<>();
        byPurpose.forEach((k, v) -> out.put(k, v.copy()));
        return out;
    }

    public synchronized void reset() {
        byPurpose.clear();
    }

    /** Texto do comando "ai custo". */
    public List<String> describe(double budgetUsd) {
        Map<String, Entry> snap = snapshot();
        List<String> out = new ArrayList<>();
        double total = 0;
        int calls = 0, fallbacks = 0;
        for (Entry e : snap.values()) {
            total += e.costUsd;
            calls += e.calls;
            fallbacks += e.fallbacks;
        }
        out.add(String.format(Locale.ROOT, "IA nesta sessão: %d chamada(s), US$ %.4f%s, %d vez(es) pelas regras.", calls, total,
                budgetUsd > 0 ? String.format(Locale.ROOT, " de US$ %.2f", budgetUsd) : " (sem teto)", fallbacks));
        for (var en : snap.entrySet()) {
            Entry e = en.getValue();
            out.add(String.format(Locale.ROOT, "  %-13s %3d chamadas · US$ %.4f · %.1f s médios · %d falha(s) · %d via regras · tokens %d/%d (cache lido %d)",
                    en.getKey(), e.calls, e.costUsd, e.avgLatencyMs() / 1000, e.errors, e.fallbacks, e.inputTokens, e.outputTokens, e.cacheReadTokens));
        }
        out.add("Com login de assinatura o valor é a estimativa do cliente; o que limita de fato é a cota do plano. Com chave de API é cobrança real.");
        return out;
    }
}
