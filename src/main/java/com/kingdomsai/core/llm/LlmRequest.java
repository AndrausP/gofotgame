package com.kingdomsai.core.llm;

import java.util.UUID;

/**
 * Pedido para a LLM. system/user já vêm montados pelo ContextBuilder, com as seções separadas.
 * Os campos "raw*" permitem que o MockProvider (regras) trabalhe sem parsear o prompt.
 */
public record LlmRequest(String purpose, String system, String user, UUID kingdomId, UUID npcId, String rawPlayerText) {
}
