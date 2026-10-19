package com.kingdomsai.core.law;

import java.util.List;
import java.util.Map;

/**
 * Propriedades de um objeto do mundo, lidas do id ("minecraft:red_bed" → cor vermelho, tag bed, material cloth).
 * É o vocabulário das leis: o Core nunca olha o mundo de outro jeito, então a regra vale em qualquer adaptador.
 *
 * @param color    cor em inglês ("red", "light_blue") ou null
 * @param tag      o que é ("bed", "door", "light", "container", "planks", "log", "glass", "textile", "workbench"...)
 * @param material do que é feito ("wood", "stone", "gold", "iron", "glass", "cloth", "earth", "straw") ou null
 * @param base     o id sem namespace e sem cor ("bed", "oak_planks")
 */
public record Props(String color, String tag, String material, String base) {
    private static final List<String> COLORS = List.of("light_blue", "light_gray", "white", "orange", "magenta", "yellow", "lime", "pink", "gray",
            "cyan", "purple", "blue", "brown", "green", "red", "black");

    /** Nomes em português (e variações) → cor em inglês. */
    private static final Map<String, String> PT = Map.ofEntries(
            Map.entry("vermelho", "red"), Map.entry("vermelha", "red"), Map.entry("azul", "blue"), Map.entry("verde", "green"),
            Map.entry("amarelo", "yellow"), Map.entry("amarela", "yellow"), Map.entry("branco", "white"), Map.entry("branca", "white"),
            Map.entry("preto", "black"), Map.entry("preta", "black"), Map.entry("rosa", "pink"), Map.entry("roxo", "purple"), Map.entry("roxa", "purple"),
            Map.entry("laranja", "orange"), Map.entry("marrom", "brown"), Map.entry("cinza", "gray"), Map.entry("ciano", "cyan"),
            Map.entry("azul-claro", "light_blue"), Map.entry("azul claro", "light_blue"), Map.entry("lima", "lime"), Map.entry("magenta", "magenta"));

    public static String colorOf(String word) {
        if (word == null) return null;
        String w = word.trim().toLowerCase().replace('-', '_').replace(' ', '_');
        if (COLORS.contains(w)) return w;
        String pt = PT.get(word.trim().toLowerCase());
        if (pt != null) return pt;
        return PT.get(word.trim().toLowerCase().replace('_', ' '));
    }

    public static Props of(String blockId) {
        if (blockId == null) return new Props(null, null, null, "");
        String id = blockId.substring(blockId.indexOf(':') + 1);
        int br = id.indexOf('[');
        if (br >= 0) id = id.substring(0, br);
        String color = null;
        String base = id;
        for (String c : COLORS) {
            if (id.startsWith(c + "_")) {
                color = c;
                base = id.substring(c.length() + 1);
                break;
            }
        }
        String tag = tagOf(base);
        return new Props(color, tag, materialOf(base, tag), base);
    }

    private static String tagOf(String b) {
        if (b.equals("bed") || b.endsWith("_bed")) return "bed";
        if (b.endsWith("door")) return "door";
        if (b.endsWith("torch") || b.endsWith("lantern")) return "light";
        if (b.equals("chest") || b.equals("barrel") || b.endsWith("_chest")) return "container";
        if (b.endsWith("planks")) return "planks";
        if (b.endsWith("_log") || b.endsWith("_wood") || b.endsWith("stem")) return "log";
        if (b.contains("glass")) return "glass";
        if (b.equals("wool") || b.endsWith("carpet")) return "textile";
        if (b.endsWith("banner")) return "banner";
        if (b.equals("concrete") || b.endsWith("concrete")) return "concrete";
        if (b.equals("crafting_table")) return "workbench";
        if (b.contains("fence")) return "fence";
        if (b.equals("furnace")) return "furnace";
        if (b.equals("bookshelf") || b.equals("lectern")) return "books";
        if (b.contains("ore")) return "ore";
        return b;
    }

    private static String materialOf(String b, String tag) {
        if (tag.equals("planks") || tag.equals("log") || tag.equals("door") || tag.equals("fence") || tag.equals("workbench") || tag.equals("container")
                || tag.equals("books") || b.equals("ladder")) return "wood";
        if (b.startsWith("gold") || b.contains("raw_gold") || b.equals("gold_ore")) return "gold";
        if (b.startsWith("iron") || b.contains("raw_iron")) return "iron";
        if (b.contains("diamond")) return "diamond";
        if (b.contains("glass")) return "glass";
        if (tag.equals("bed") || tag.equals("textile") || tag.equals("banner")) return "cloth";
        if (b.contains("stone") || b.contains("cobble") || b.contains("brick") || b.contains("andesite") || b.contains("granite") || b.contains("diorite")
                || b.contains("deepslate") || tag.equals("furnace") || tag.equals("ore") || tag.equals("concrete")) return "stone";
        if (b.contains("dirt") || b.contains("grass") || b.contains("farmland") || b.equals("sand") || b.equals("gravel")) return "earth";
        if (b.contains("hay")) return "straw";
        return null;
    }
}
