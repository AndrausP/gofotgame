package com.kingdomsai.core.event;

import java.util.Map;
import java.util.UUID;

/**
 * Tudo que é importante gera um evento. Eventos alimentam outros sistemas (Event Bus),
 * o HUD do Manager, as notificações do rei e o replay/debug.
 */
public record GameEvent(
        long seq,
        long tick,
        EventType type,
        Severity severity,
        UUID kingdomId,
        UUID actorId,
        String message,
        Map<String, String> data
) {
    public enum Severity { INFO, GOOD, WARN, DANGER }

    public String data(String key) {
        return data == null ? null : data.get(key);
    }

    public String icon() {
        return switch (severity) {
            case GOOD -> "✓";
            case WARN -> "⚠";
            case DANGER -> "‼";
            default -> "•";
        };
    }
}
