package com.kingdomsai.core.npc;

import com.kingdomsai.core.common.Text;

public enum Profession {
    PEASANT("Camponês", "campones", "peasant"),
    FARMER("Fazendeiro", "fazendeiro", "agricultor", "farmer"),
    LUMBERJACK("Lenhador", "lenhador", "lumberjack"),
    MINER("Minerador", "minerador", "mineiro", "miner"),
    BLACKSMITH("Ferreiro", "ferreiro", "blacksmith", "smith"),
    BUILDER("Construtor", "construtor", "pedreiro", "builder"),
    MERCHANT("Mercador", "mercador", "comerciante", "merchant"),
    GUARD("Guarda", "guarda", "guard"),
    SOLDIER("Soldado", "soldado", "soldier"),
    PRIEST("Sacerdote", "sacerdote", "padre", "priest"),
    SCHOLAR("Estudioso", "estudioso", "escriba", "scholar");

    public final String display;
    private final String[] aliases;

    Profession(String display, String... aliases) {
        this.display = display;
        this.aliases = aliases;
    }

    public boolean isMilitary() {
        return this == SOLDIER || this == GUARD;
    }

    public static Profession parse(String s) {
        String n = Text.norm(s);
        if (n.endsWith("s") && n.length() > 3) {
            Profession p = parseExact(n.substring(0, n.length() - 1));
            if (p != null) return p;
            if (n.endsWith("es")) {
                p = parseExact(n.substring(0, n.length() - 2));
                if (p != null) return p;
            }
        }
        return parseExact(n);
    }

    private static Profession parseExact(String n) {
        for (Profession p : values()) {
            if (p.name().equalsIgnoreCase(n) || Text.norm(p.display).equals(n)) return p;
            for (String a : p.aliases) if (a.equals(n)) return p;
        }
        return null;
    }
}
