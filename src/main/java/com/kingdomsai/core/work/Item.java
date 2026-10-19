package com.kingdomsai.core.work;

import com.kingdomsai.core.common.Text;
import com.kingdomsai.core.kingdom.ResourceType;

/**
 * Itens físicos das cadeias de trabalho: o que um NPC carrega na mão ou o que fica no baú de um prédio.
 * Ao ser guardado no armazém (STORE), o item vira estoque do reino ({@link #resource}).
 */
public enum Item {
    SEEDS("Sementes", null, 0, "seeds", "semente", "sementes"),
    WHEAT("Trigo", ResourceType.FOOD, 1, "wheat", "trigo", "colheita"),
    LOG("Tora", ResourceType.WOOD, 1, "log", "tora", "toras", "lenha", "madeira", "wood"),
    STONE("Pedra", ResourceType.STONE, 1, "stone", "pedra", "pedras", "cobblestone"),
    RAW_IRON("Ferro bruto", null, 0, "raw_iron", "ferro_bruto", "minerio", "minerio_de_ferro", "ferro bruto"),
    COAL("Carvão", null, 0, "coal", "carvao"),
    IRON_INGOT("Barra de ferro", ResourceType.IRON, 1, "iron_ingot", "barra", "barras", "lingote", "ferro", "iron"),
    SWORD("Espada", ResourceType.WEAPONS, 1, "sword", "espada", "espadas", "arma", "armas", "weapons"),
    LETTER("Carta", null, 0, "letter", "carta", "cartas");

    public final String display;
    /** Recurso do reino que este item vira ao ser guardado; null = não é estoque (fica no baú). */
    public final ResourceType resource;
    /** Quanto de {@link #resource} cada unidade vale. */
    public final double resourcePerUnit;
    private final String[] aliases;

    Item(String display, ResourceType resource, double resourcePerUnit, String... aliases) {
        this.display = display;
        this.resource = resource;
        this.resourcePerUnit = resourcePerUnit;
        this.aliases = aliases;
    }

    public static Item parse(String s) {
        if (s == null) return null;
        String n = Text.norm(s).replace(' ', '_');
        for (Item i : values()) {
            if (i.name().equalsIgnoreCase(n) || Text.norm(i.display).replace(' ', '_').equals(n)) return i;
            for (String a : i.aliases) if (a.replace(' ', '_').equals(n)) return i;
        }
        return null;
    }
}
