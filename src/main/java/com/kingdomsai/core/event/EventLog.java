package com.kingdomsai.core.event;

import java.util.*;
import java.util.function.Predicate;

/** Histórico limitado de eventos — base do replay ("por que Aldren traiu o rei?") e do HUD. */
public final class EventLog {
    private final int capacity;
    private final ArrayDeque<GameEvent> events = new ArrayDeque<>();

    public EventLog(int capacity) {
        this.capacity = capacity;
    }

    public void add(GameEvent e) {
        events.addLast(e);
        while (events.size() > capacity) events.removeFirst();
    }

    public List<GameEvent> recent(int n, Predicate<GameEvent> filter) {
        List<GameEvent> out = new ArrayList<>();
        Iterator<GameEvent> it = events.descendingIterator();
        while (it.hasNext() && out.size() < n) {
            GameEvent e = it.next();
            if (filter.test(e)) out.add(e);
        }
        Collections.reverse(out);
        return out;
    }

    public List<GameEvent> all() {
        return new ArrayList<>(events);
    }

    public void restore(Collection<GameEvent> saved) {
        events.clear();
        for (GameEvent e : saved) add(e);
    }
}
