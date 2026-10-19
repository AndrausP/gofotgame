package com.kingdomsai.core.llm;

import java.util.UUID;

/**
 * Pedido para a LLM. system/user já vêm montados pelo ContextBuilder, com as seções separadas.
 * Os campos "raw*" permitem que o MockProvider (regras) trabalhe sem parsear o prompt.
 * {@code schema} é o JSON Schema (constante do código, nunca texto do jogador) que o provider pode usar para
 * pedir saída estruturada; null = sem schema.
 */
public record LlmRequest(String purpose, String system, String user, UUID kingdomId, UUID npcId, String rawPlayerText, String schema) {
    public LlmRequest(String purpose, String system, String user, UUID kingdomId, UUID npcId, String rawPlayerText) {
        this(purpose, system, user, kingdomId, npcId, rawPlayerText, Schemas.forPurpose(purpose, true));
    }

    public LlmRequest withUser(String newUser) {
        return new LlmRequest(purpose, system, newUser, kingdomId, npcId, rawPlayerText, schema);
    }
}
