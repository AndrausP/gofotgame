package com.kingdomsai.voxel;

import com.kingdomsai.core.port.PhysicalPort.Recipe;
import com.kingdomsai.core.port.PhysicalPort.Station;

import java.util.*;

/** Itens e receitas do mundo voxel (o suficiente para a economia dos súditos: ferramentas, comida, armazém). */
public final class Items {
    private Items() {}

    private static final String[] WOODS = {"oak", "spruce", "birch", "dark_oak", "jungle", "acacia"};
    private static final Map<String, List<Recipe>> RECIPES = new HashMap<>();
    private static final Set<String> ITEMS = new HashSet<>();

    private static List<String> planks() {
        List<String> l = new ArrayList<>();
        for (String w : WOODS) l.add("minecraft:" + w + "_planks");
        return l;
    }

    private static void add(Recipe r) {
        RECIPES.computeIfAbsent(r.output(), k -> new ArrayList<>()).add(r);
        ITEMS.add(r.output());
        for (List<String> slot : r.ingredients()) ITEMS.addAll(slot);
    }

    private static List<String> one(String id) {
        return List.of(id);
    }

    private static List<List<String>> n(List<String> slot, int times) {
        return Collections.nCopies(times, slot);
    }

    static {
        List<String> planks = planks();
        for (String w : WOODS) {
            add(new Recipe("minecraft:" + w + "_planks", 4, List.of(List.of("minecraft:" + w + "_log", "minecraft:" + w + "_wood")), Station.NONE));
        }
        add(new Recipe("minecraft:stick", 4, List.of(planks, planks), Station.NONE));
        add(new Recipe("minecraft:crafting_table", 1, n(planks, 4), Station.NONE));
        add(new Recipe("minecraft:chest", 1, n(planks, 8), Station.CRAFTING_TABLE));
        add(new Recipe("minecraft:barrel", 1, n(planks, 8), Station.CRAFTING_TABLE));
        add(new Recipe("minecraft:furnace", 1, n(one("minecraft:cobblestone"), 8), Station.CRAFTING_TABLE));
        add(new Recipe("minecraft:torch", 4, List.of(List.of("minecraft:coal", "minecraft:charcoal"), one("minecraft:stick")), Station.NONE));
        add(new Recipe("minecraft:oak_door", 3, n(planks, 6), Station.CRAFTING_TABLE));
        add(new Recipe("minecraft:bread", 1, n(one("minecraft:wheat"), 3), Station.CRAFTING_TABLE));
        add(new Recipe("minecraft:bucket", 1, n(one("minecraft:iron_ingot"), 3), Station.CRAFTING_TABLE));
        tools("wooden", planks);
        tools("stone", List.of("minecraft:cobblestone"));
        tools("iron", List.of("minecraft:iron_ingot"));
        add(new Recipe("minecraft:iron_ingot", 1, List.of(one("minecraft:raw_iron")), Station.FURNACE));
        add(new Recipe("minecraft:gold_ingot", 1, List.of(one("minecraft:raw_gold")), Station.FURNACE));
        add(new Recipe("minecraft:charcoal", 1, List.of(List.of("minecraft:oak_log", "minecraft:spruce_log", "minecraft:birch_log")), Station.FURNACE));
        add(new Recipe("minecraft:glass", 1, List.of(one("minecraft:sand")), Station.FURNACE));
        add(new Recipe("minecraft:stone", 1, List.of(one("minecraft:cobblestone")), Station.FURNACE));
        for (String misc : new String[]{"minecraft:diamond", "minecraft:raw_iron", "minecraft:raw_gold", "minecraft:coal", "minecraft:cobblestone", "minecraft:dirt",
                "minecraft:sand", "minecraft:gravel", "minecraft:wheat", "minecraft:wheat_seeds", "minecraft:carrot", "minecraft:potato", "minecraft:apple",
                "minecraft:oak_sapling", "minecraft:oak_log", "minecraft:spruce_log", "minecraft:birch_log", "minecraft:oak_wood", "minecraft:cooked_beef"})
            ITEMS.add(misc);
        for (BlockType b : Blocks.all()) if (!b.air() && !b.fluid()) ITEMS.add(b.id());
    }

    private static void tools(String tier, List<String> mat) {
        String p = "minecraft:" + tier + "_";
        add(new Recipe(p + "pickaxe", 1, join(n(mat, 3), n(one("minecraft:stick"), 2)), Station.CRAFTING_TABLE));
        add(new Recipe(p + "axe", 1, join(n(mat, 3), n(one("minecraft:stick"), 2)), Station.CRAFTING_TABLE));
        add(new Recipe(p + "shovel", 1, join(n(mat, 1), n(one("minecraft:stick"), 2)), Station.CRAFTING_TABLE));
        add(new Recipe(p + "hoe", 1, join(n(mat, 2), n(one("minecraft:stick"), 2)), Station.CRAFTING_TABLE));
        add(new Recipe(p + "sword", 1, join(n(mat, 2), n(one("minecraft:stick"), 1)), Station.CRAFTING_TABLE));
    }

    private static List<List<String>> join(List<List<String>> a, List<List<String>> b) {
        List<List<String>> l = new ArrayList<>(a);
        l.addAll(b);
        return l;
    }

    public static List<Recipe> recipes(String item) {
        return RECIPES.getOrDefault(item, List.of());
    }

    public static boolean exists(String item) {
        return ITEMS.contains(item);
    }

    /** "minecraft:stone_pickaxe" → "pickaxe"; "pickaxe" → "pickaxe"; sem ferramenta → null. */
    public static String toolType(String item) {
        if (item == null || item.isEmpty()) return null;
        String name = item.substring(item.indexOf(':') + 1);
        int u = name.lastIndexOf('_');
        String t = u >= 0 ? name.substring(u + 1) : name;
        return switch (t) {
            case "pickaxe", "axe", "shovel", "hoe" -> t;
            default -> null;
        };
    }
}
