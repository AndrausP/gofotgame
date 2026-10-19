package com.kingdomsai.core.kingdom;

import com.kingdomsai.core.common.Text;

public enum ResourceType {
    FOOD("Comida", "comida", "food"),
    WOOD("Madeira", "madeira", "wood"),
    STONE("Pedra", "pedra", "stone"),
    IRON("Ferro", "ferro", "iron"),
    GOLD("Tesouro", "ouro", "gold"),
    WEAPONS("Armas", "armas", "weapons");

    public final String display;
    private final String[] aliases;

    ResourceType(String display, String... aliases) {
        this.display = display;
        this.aliases = aliases;
    }

    public static ResourceType parse(String s) {
        String n = Text.norm(s);
        for (ResourceType r : values()) {
            if (r.name().equalsIgnoreCase(n) || Text.norm(r.display).equals(n)) return r;
            for (String a : r.aliases) if (a.equals(n)) return r;
        }
        if (n.startsWith("tesour") || n.startsWith("dinheir")) return GOLD;
        if (n.startsWith("espad") || n.startsWith("arma")) return WEAPONS;
        return null;
    }
}
