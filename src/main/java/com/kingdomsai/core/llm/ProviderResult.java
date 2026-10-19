package com.kingdomsai.core.llm;

/**
 * Resposta de um provider: o texto do modelo (esperado: JSON) e o que a chamada custou.
 * Providers que não medem custo devolvem {@link Usage#UNKNOWN}.
 */
public record ProviderResult(String text, Usage usage) {
    /** costUsd < 0 = desconhecido (não conta para o teto). */
    public record Usage(double costUsd, long inputTokens, long outputTokens, long cacheReadTokens, long cacheCreationTokens, long latencyMs, String model) {
        public static final Usage UNKNOWN = new Usage(-1, 0, 0, 0, 0, 0, "");

        public boolean known() {
            return costUsd >= 0;
        }
    }

    public ProviderResult(String text) {
        this(text, Usage.UNKNOWN);
    }
}
