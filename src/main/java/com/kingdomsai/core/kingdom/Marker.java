package com.kingdomsai.core.kingdom;

import com.kingdomsai.core.common.Text;

/**
 * Pontos que o rei marca com a Bandeira do Reino. Os súditos passam a usá-los:
 * SPAWN — onde chegam os novos moradores e onde o rei renasce; GATHER — praça do fim de tarde;
 * MINE / FOREST — onde mineradores e lenhadores trabalham (rotina e cadeias).
 */
public enum Marker {
    SPAWN("Spawn do reino", "spawn", "inicio", "chegada", "nascer", "renascer"),
    GATHER("Praça", "praca", "encontro", "reuniao", "gather", "plaza"),
    MINE("Mina", "mina", "mine", "pedreira"),
    FOREST("Bosque", "bosque", "floresta", "forest", "mata");

    public final String display;
    private final String[] aliases;

    Marker(String display, String... aliases) {
        this.display = display;
        this.aliases = aliases;
    }

    public Marker next() {
        return values()[(ordinal() + 1) % values().length];
    }

    public static Marker parse(String s) {
        if (s == null) return null;
        String n = Text.norm(s);
        for (Marker m : values()) {
            if (m.name().equalsIgnoreCase(n) || Text.norm(m.display).equals(n)) return m;
            for (String a : m.aliases) if (a.equals(n)) return m;
        }
        return null;
    }
}
