package com.kingdomsai.core.npc;

import java.util.Set;
import java.util.UUID;

/** Uma lembrança. Só as memórias relevantes vão para a LLM — nunca o histórico inteiro. */
public record Memory(UUID id, long tick, String text, int importance, Set<String> tags, UUID about) {
}
