package com.kingdomsai.core.construction.program;

import com.kingdomsai.core.common.Text;
import com.kingdomsai.core.construction.Material;

import java.util.EnumMap;
import java.util.Map;

/**
 * Estilo = paleta de materiais + proporções. Tabelas de dados, não código por edifício: o mesmo compilador gera
 * uma catedral gótica, uma capela românica ou uma fortaleza só trocando o estilo.
 */
public enum Style {
    ROMANESQUE("românico", 3, 6, "vault", "cone", false,
            "minecraft:stone_bricks", "minecraft:cobblestone", "minecraft:stone_bricks", "minecraft:bricks", "minecraft:glass"),
    GOTHIC("gótico", 3, 4, "vault", "cone", false,
            "minecraft:stone_bricks", "minecraft:cobblestone", "minecraft:stone_bricks", "minecraft:dark_oak_planks", "minecraft:glass"),
    RUSTIC("rústico", 4, 6, "gable", "cone", false,
            "minecraft:oak_planks", "minecraft:cobblestone", "minecraft:oak_log", "minecraft:spruce_planks", "minecraft:glass"),
    FORTRESS("fortaleza", 4, 8, "flat", "flat", true,
            "minecraft:stone_bricks", "minecraft:cobblestone", "minecraft:stone_bricks", "minecraft:stone_bricks", "minecraft:glass");

    public final String display;
    /** Espaço entre janelas (blocos) e entre contrafortes, por padrão. */
    public final int windowEvery, buttressEvery;
    /** Telhado padrão da nave e da torre. */
    public final String hallRoof, towerRoof;
    /** Ameias no topo das torres e muralhas. */
    public final boolean crenellated;
    private final Map<Material, String> palette = new EnumMap<>(Material.class);

    Style(String display, int windowEvery, int buttressEvery, String hallRoof, String towerRoof, boolean crenellated,
          String wall, String foundation, String pillar, String roof, String window) {
        this.display = display;
        this.windowEvery = windowEvery;
        this.buttressEvery = buttressEvery;
        this.hallRoof = hallRoof;
        this.towerRoof = towerRoof;
        this.crenellated = crenellated;
        palette.put(Material.WALL, wall);
        palette.put(Material.FOUNDATION, foundation);
        palette.put(Material.PILLAR, pillar);
        palette.put(Material.FLOOR, "minecraft:stone_bricks".equals(wall) ? "minecraft:cobblestone" : "minecraft:spruce_planks");
        palette.put(Material.ROOF, roof);
        palette.put(Material.ROOF_EDGE, roof);
        palette.put(Material.ROOF_STAIR, roof);
        palette.put(Material.WINDOW, window);
    }

    /** Material abstrato → bloco (vai para {@code Blueprint.materials}). */
    public Map<Material, String> palette() {
        return new EnumMap<>(palette);
    }

    public static Style parse(String s) {
        if (s == null) return null;
        String n = Text.norm(s);
        for (Style st : values()) if (st.name().equalsIgnoreCase(n) || Text.norm(st.display).equals(n)) return st;
        return switch (n) {
            case "romanico", "romanesco", "romanesque" -> ROMANESQUE;
            case "gotico", "gothic" -> GOTHIC;
            case "rustico", "rustica", "rustic" -> RUSTIC;
            case "fortaleza", "fortress", "militar" -> FORTRESS;
            default -> null;
        };
    }
}
