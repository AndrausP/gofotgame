package com.kingdomsai.core.skill;

import com.kingdomsai.core.common.Text;

import java.util.*;
import java.util.function.Predicate;

/**
 * "picareta de ferro" → minecraft:iron_pickaxe. O servidor não tem tradução em português, então o
 * Core mantém um vocabulário dos itens úteis. Ids em inglês ("iron_pickaxe", "minecraft:torch") também valem.
 * Grupos (#logs, #planks, #ores, #all) servem para "pegue madeira", "guarde tudo".
 */
public final class ItemNames {
    private ItemNames() {}

    private static final Map<String, String> MATERIAL = new LinkedHashMap<>();
    private static final Map<String, String> TOOL = new LinkedHashMap<>();
    private static final Map<String, String> ARMOR = new LinkedHashMap<>();
    private static final Map<String, String> SIMPLE = new LinkedHashMap<>();
    private static final Map<String, String> PT = new HashMap<>();

    static {
        MATERIAL.put("madeira", "wooden");
        MATERIAL.put("pedra", "stone");
        MATERIAL.put("ferro", "iron");
        MATERIAL.put("ouro", "golden");
        MATERIAL.put("diamante", "diamond");
        MATERIAL.put("couro", "leather");
        TOOL.put("picareta", "pickaxe");
        TOOL.put("picaretas", "pickaxe");
        TOOL.put("machado", "axe");
        TOOL.put("machados", "axe");
        TOOL.put("pa", "shovel");
        TOOL.put("pas", "shovel");
        TOOL.put("espada", "sword");
        TOOL.put("espadas", "sword");
        TOOL.put("enxada", "hoe");
        TOOL.put("enxadas", "hoe");
        ARMOR.put("capacete", "helmet");
        ARMOR.put("elmo", "helmet");
        ARMOR.put("peitoral", "chestplate");
        ARMOR.put("armadura", "chestplate");
        ARMOR.put("calca", "leggings");
        ARMOR.put("calcas", "leggings");
        ARMOR.put("perneira", "leggings");
        ARMOR.put("bota", "boots");
        ARMOR.put("botas", "boots");
        String[][] s = {
                {"tocha", "torch"}, {"tochas", "torch"}, {"bau", "chest"}, {"baus", "chest"}, {"barril", "barrel"},
                {"bancada", "crafting_table"}, {"bancada de trabalho", "crafting_table"}, {"mesa de trabalho", "crafting_table"},
                {"fornalha", "furnace"}, {"forno", "furnace"}, {"graveto", "stick"}, {"gravetos", "stick"}, {"varetas", "stick"},
                {"tabua", "oak_planks"}, {"tabuas", "oak_planks"}, {"escada de mao", "ladder"}, {"escadas de mao", "ladder"},
                {"escada", "oak_stairs"}, {"escadas", "oak_stairs"}, {"porta", "oak_door"}, {"portas", "oak_door"},
                {"cerca", "oak_fence"}, {"cercas", "oak_fence"}, {"portao", "oak_fence_gate"}, {"cama", "white_bed"},
                {"camas", "white_bed"}, {"pao", "bread"}, {"paes", "bread"}, {"balde", "bucket"}, {"baldes", "bucket"},
                {"tesoura", "shears"}, {"arco", "bow"}, {"flecha", "arrow"}, {"flechas", "arrow"}, {"escudo", "shield"},
                {"lanterna", "lantern"}, {"livro", "book"}, {"livros", "book"}, {"papel", "paper"}, {"estante", "bookshelf"},
                {"bigorna", "anvil"}, {"trilho", "rail"}, {"trilhos", "rail"}, {"carvao", "coal"}, {"carvao vegetal", "charcoal"},
                {"lingote de ferro", "iron_ingot"}, {"lingotes de ferro", "iron_ingot"}, {"barra de ferro", "iron_ingot"},
                {"barras de ferro", "iron_ingot"}, {"ferro", "iron_ingot"}, {"lingote de ouro", "gold_ingot"}, {"barra de ouro", "gold_ingot"},
                {"barras de ouro", "gold_ingot"}, {"ouro", "gold_ingot"}, {"diamante", "diamond"}, {"diamantes", "diamond"},
                {"ferro bruto", "raw_iron"}, {"minerio de ferro", "iron_ore"}, {"pedregulho", "cobblestone"}, {"pedregulhos", "cobblestone"},
                {"pedra", "cobblestone"}, {"pedras", "cobblestone"}, {"vidro", "glass"}, {"areia", "sand"}, {"trigo", "wheat"},
                {"maca", "apple"}, {"macas", "apple"}, {"muda", "oak_sapling"}, {"mudas", "oak_sapling"}, {"tora", "oak_log"},
                {"toras", "oak_log"}, {"tronco", "oak_log"}, {"troncos", "oak_log"}, {"placa", "oak_sign"}, {"alavanca", "lever"},
                {"vara de pescar", "fishing_rod"}, {"pederneira", "flint_and_steel"}, {"isqueiro", "flint_and_steel"},
                {"corda", "string"}, {"linha", "string"}, {"la", "white_wool"}, {"tijolo", "brick"}, {"tijolos", "bricks"},
                {"terra", "dirt"}, {"semente", "wheat_seeds"}, {"sementes", "wheat_seeds"}, {"bolo", "cake"}, {"tigela", "bowl"},
                {"sopa de cogumelo", "mushroom_stew"}, {"escotilha", "oak_trapdoor"}, {"alcapao", "oak_trapdoor"},
                {"fogueira", "campfire"}, {"caldeirao", "cauldron"}, {"pedra lisa", "stone"}};
        for (String[] e : s) SIMPLE.put(e[0], e[1]);
    }

    /** Resolve um nome em português/inglês para id do Minecraft (ou grupo #...). null se não reconhecer. */
    public static String resolve(String text) {
        if (text == null || text.isBlank()) return null;
        String raw = text.trim();
        if (raw.startsWith("#")) return raw.toLowerCase(Locale.ROOT);
        if (raw.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+")) return raw;
        String n = Text.norm(raw).replaceAll("[^a-z0-9 _]", " ").replaceAll("\\s+", " ").trim();
        n = n.replaceAll("^(um|uma|uns|umas|o|a|os|as|\\d+)\\s+", "").trim();
        if (n.matches("[a-z0-9_]+") && n.contains("_")) return "minecraft:" + n;
        switch (n) {
            case "madeira", "madeiras", "lenha" -> {
                return "#logs";
            }
            case "minerio", "minerios" -> {
                return "#ores";
            }
            case "tudo", "todos", "todas", "tudo que tiver", "tudo o que tiver" -> {
                return "#all";
            }
            default -> {
            }
        }
        // ferramenta/armadura + material ("picareta de ferro", "espada de diamante")
        String[] w = n.split(" ");
        String kind = TOOL.get(w[0]);
        String armor = ARMOR.get(w[0]);
        if (kind != null || armor != null) {
            String mat = null;
            for (int i = 1; i < w.length; i++) if (MATERIAL.containsKey(w[i])) mat = MATERIAL.get(w[i]);
            if (armor != null) {
                if (mat == null) mat = "iron";
                if (mat.equals("wooden") || mat.equals("stone")) return null; // não existe armadura de madeira/pedra
                return "minecraft:" + mat + "_" + armor;
            }
            if (mat == null) mat = "stone";
            if (mat.equals("leather")) return null;
            return "minecraft:" + mat + "_" + kind;
        }
        // frase mais longa primeiro ("barra de ferro" antes de "ferro")
        String best = null;
        for (String key : SIMPLE.keySet()) {
            boolean match = n.equals(key) || n.startsWith(key + " ") || n.endsWith(" " + key);
            if (match && (best == null || key.length() > best.length())) best = key;
        }
        if (best != null) return "minecraft:" + SIMPLE.get(best);
        if (n.matches("[a-z_]+")) return "minecraft:" + n;
        return null;
    }

    /** Nome curto para mensagens ("minecraft:iron_pickaxe" → "picareta de ferro"). */
    public static String display(String id) {
        if (id == null) return "?";
        if (id.equals("#logs")) return "madeira";
        if (id.equals("#ores")) return "minérios";
        if (id.equals("#all")) return "tudo";
        if (id.equals("#planks")) return "tábuas";
        String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        if (PT.isEmpty()) synchronized (PT) {
            if (PT.isEmpty()) {
                for (var e : SIMPLE.entrySet()) PT.putIfAbsent(e.getValue(), e.getKey());
                for (var m : MATERIAL.entrySet()) {
                    for (var t : TOOL.entrySet()) if (!t.getKey().endsWith("s")) PT.put(m.getValue() + "_" + t.getValue(), t.getKey() + " de " + m.getKey());
                    for (var a : ARMOR.entrySet()) if (!a.getKey().endsWith("s") && !a.getKey().equals("elmo") && !a.getKey().equals("armadura"))
                        PT.put(m.getValue() + "_" + a.getValue(), a.getKey() + " de " + m.getKey());
                }
                PT.put("iron_ingot", "barra de ferro");
                PT.put("gold_ingot", "barra de ouro");
                PT.put("cobblestone", "pedregulho");
                PT.put("oak_planks", "tábua de carvalho");
                PT.put("oak_log", "tora de carvalho");
                PT.put("stick", "graveto");
                PT.put("crafting_table", "bancada");
            }
        }
        String pt = PT.get(path);
        return pt != null ? pt : path.replace('_', ' ');
    }

    /** O item (id) pertence ao pedido (id exato ou grupo)? */
    public static Predicate<String> matcher(String spec) {
        if (spec == null) return id -> false;
        return switch (spec) {
            case "#all" -> id -> true;
            case "#logs" -> id -> id.endsWith("_log") || id.endsWith("_stem") || id.endsWith("_planks");
            case "#planks" -> id -> id.endsWith("_planks");
            case "#ores" -> id -> id.endsWith("_ore") || id.startsWith("minecraft:raw_") || id.equals("minecraft:coal") || id.equals("minecraft:diamond");
            default -> id -> id.equals(spec);
        };
    }

    /** Ferramentas, armas e armaduras de metal: coisa de ferreiro. */
    public static boolean smithing(String id) {
        String p = id.substring(id.indexOf(':') + 1);
        boolean metal = p.startsWith("iron_") || p.startsWith("golden_") || p.startsWith("diamond_") || p.startsWith("netherite_") || p.startsWith("chainmail_");
        boolean gear = p.matches(".*_(pickaxe|axe|shovel|sword|hoe|helmet|chestplate|leggings|boots)") || p.equals("anvil") || p.equals("shield")
                || p.equals("bucket") || p.equals("shears") || p.equals("rail") || p.equals("cauldron") || p.equals("flint_and_steel");
        return gear && (metal || !p.matches(".*_(pickaxe|axe|shovel|sword|hoe)"));
    }
}
