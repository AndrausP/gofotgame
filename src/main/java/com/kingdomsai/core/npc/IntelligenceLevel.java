package com.kingdomsai.core.npc;

/**
 * NPCs não são todos LLM.
 * BOT: rotina pura. CONTEXTUAL: memória curta e relações. IMPORTANT: memória persistente e agenda.
 * LLM: o nível em que a LLM é ativada quando necessário (conversa direta com o rei, decisões especiais).
 */
public enum IntelligenceLevel {
    BOT, CONTEXTUAL, IMPORTANT, LLM;

    public IntelligenceLevel atLeast(IntelligenceLevel other) {
        return this.ordinal() >= other.ordinal() ? this : other;
    }
}
