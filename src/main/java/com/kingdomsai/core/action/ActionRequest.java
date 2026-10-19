package com.kingdomsai.core.action;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Pedido de ação. Venha da CLI, do Manager, da LLM ou do AI Director, passa pelo mesmo pipeline.
 *
 * @param actorId UUID do jogador (actorKind = PLAYER) ou do NPC (NPC); null para DIRECTOR.
 */
public record ActionRequest(UUID kingdomId, UUID actorId, ActorKind actorKind, ActionType type,
                            Map<String, String> params, Source source) {

    public enum ActorKind { PLAYER, NPC, DIRECTOR }

    public enum Source { CLI, MANAGER, LLM, DIRECTOR, TEST }

    public String param(String key) {
        return params == null ? null : params.get(key);
    }

    public int intParam(String key, int def) {
        String v = param(key);
        if (v == null || v.isBlank()) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static ActionRequest of(java.util.UUID kingdomId, UUID actorId, ActorKind kind, ActionType type, Source source, String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return new ActionRequest(kingdomId, actorId, kind, type, m, source);
    }
}
