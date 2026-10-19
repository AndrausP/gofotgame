package com.kingdomsai.core.llm;

import java.util.concurrent.CompletableFuture;

/** ILLMProvider da arquitetura. O jogo não depende de um modelo específico. */
public interface LlmProvider {
    String name();

    /** Retorna o texto bruto produzido pelo modelo (esperado: JSON). Nunca bloqueia a thread do jogo. */
    CompletableFuture<String> complete(LlmRequest request, LlmConfig config);
}
