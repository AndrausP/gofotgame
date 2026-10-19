package com.kingdomsai.core.event;

import java.util.*;
import java.util.function.Consumer;

/**
 * Barramento de eventos do Core. Sistemas não chamam uns aos outros diretamente:
 * publicam eventos e reagem a eventos (evita dependência circular).
 *
 * Eventos publicados durante o despacho entram na fila e são processados na mesma
 * chamada de {@link #dispatch()}, com limite para evitar loops infinitos.
 */
public final class EventBus {
    private static final int MAX_DISPATCH_PER_CALL = 500;

    private final Map<EventType, List<Consumer<GameEvent>>> listeners = new EnumMap<>(EventType.class);
    private final List<Consumer<GameEvent>> globalListeners = new ArrayList<>();
    private final ArrayDeque<GameEvent> queue = new ArrayDeque<>();
    private final EventLog log;
    private long seq;

    public EventBus(EventLog log, long startSeq) {
        this.log = log;
        this.seq = startSeq;
    }

    public void subscribe(EventType type, Consumer<GameEvent> listener) {
        listeners.computeIfAbsent(type, t -> new ArrayList<>()).add(listener);
    }

    public void subscribeAll(Consumer<GameEvent> listener) {
        globalListeners.add(listener);
    }

    public GameEvent publish(long tick, EventType type, GameEvent.Severity severity, UUID kingdomId, UUID actorId,
                             String message, Map<String, String> data) {
        GameEvent e = new GameEvent(++seq, tick, type, severity, kingdomId, actorId, message,
                data == null ? Map.of() : Map.copyOf(data));
        queue.add(e);
        log.add(e);
        return e;
    }

    public GameEvent publish(long tick, EventType type, GameEvent.Severity severity, UUID kingdomId, UUID actorId, String message) {
        return publish(tick, type, severity, kingdomId, actorId, message, null);
    }

    /** Processa a fila. Chamado pelo Core uma vez por tick lógico. */
    public void dispatch() {
        int n = 0;
        while (!queue.isEmpty() && n++ < MAX_DISPATCH_PER_CALL) {
            GameEvent e = queue.poll();
            List<Consumer<GameEvent>> ls = listeners.get(e.type());
            if (ls != null) for (Consumer<GameEvent> l : List.copyOf(ls)) safe(l, e);
            for (Consumer<GameEvent> l : List.copyOf(globalListeners)) safe(l, e);
        }
    }

    private static void safe(Consumer<GameEvent> l, GameEvent e) {
        try {
            l.accept(e);
        } catch (RuntimeException ex) {
            // Um listener com bug nunca deve derrubar a simulação.
            System.err.println("[KingdomsAI] listener failed for " + e.type() + ": " + ex);
        }
    }

    public long currentSeq() {
        return seq;
    }

    public EventLog log() {
        return log;
    }
}
