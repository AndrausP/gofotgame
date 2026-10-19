package com.kingdomsai.voxel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registro global de blocos. Ids desconhecidos (blocos explícitos de plantas .nbt, por exemplo) nascem na hora,
 * com cor derivada do nome, para o mundo nunca recusar uma planta.
 */
public final class Blocks {
    private Blocks() {}

    private static final Map<String, BlockType> BY_ID = new ConcurrentHashMap<>();
    private static final List<BlockType> BY_NUM = new ArrayList<>();

    public static final BlockType AIR = def("minecraft:air", false, false, 0, null, false, null, 0xFFFFFF, true, false, false, false, false);
    public static final BlockType STONE = ground("minecraft:stone", 1.5, "pickaxe", true, "minecraft:cobblestone", 0x7D7D7D);
    public static final BlockType DIRT = ground("minecraft:dirt", 0.5, "shovel", false, "minecraft:dirt", 0x8B5E3C);
    public static final BlockType GRASS = ground("minecraft:grass_block", 0.6, "shovel", false, "minecraft:dirt", 0x5FA83A);
    public static final BlockType SAND = ground("minecraft:sand", 0.5, "shovel", false, "minecraft:sand", 0xE0D49A);
    public static final BlockType GRAVEL = ground("minecraft:gravel", 0.6, "shovel", false, "minecraft:gravel", 0x8A8580);
    public static final BlockType WATER = def("minecraft:water", false, true, -1, null, false, null, 0x3A6FD8, true, false, false, false, false);
    public static final BlockType BEDROCK = ground("minecraft:bedrock", -1, null, false, null, 0x333333);
    public static final BlockType COBBLE = ground("minecraft:cobblestone", 2.0, "pickaxe", true, "minecraft:cobblestone", 0x6E6E6E);
    public static final BlockType COAL_ORE = ground("minecraft:coal_ore", 3.0, "pickaxe", true, "minecraft:coal", 0x4A4A4A);
    public static final BlockType IRON_ORE = ground("minecraft:iron_ore", 3.0, "pickaxe", true, "minecraft:raw_iron", 0xB08D72);
    public static final BlockType GOLD_ORE = ground("minecraft:gold_ore", 3.0, "pickaxe", true, "minecraft:raw_gold", 0xE8C84A);
    public static final BlockType DIAMOND_ORE = ground("minecraft:diamond_ore", 3.0, "pickaxe", true, "minecraft:diamond", 0x4DE0D8);
    public static final BlockType OAK_LOG = log("minecraft:oak_log", 0x6B4A2A);
    public static final BlockType BIRCH_LOG = log("minecraft:birch_log", 0xD8D4C4);
    public static final BlockType SPRUCE_LOG = log("minecraft:spruce_log", 0x4A3320);
    public static final BlockType OAK_LEAVES = leaves("minecraft:oak_leaves", 0x3F8A2B);
    public static final BlockType BIRCH_LEAVES = leaves("minecraft:birch_leaves", 0x7BA84A);
    public static final BlockType SPRUCE_LEAVES = leaves("minecraft:spruce_leaves", 0x2E5C3A);
    public static final BlockType OAK_PLANKS = wood("minecraft:oak_planks", 0xB88A52);
    public static final BlockType SPRUCE_PLANKS = wood("minecraft:spruce_planks", 0x7A5A36);
    public static final BlockType BIRCH_PLANKS = wood("minecraft:birch_planks", 0xD6C58E);
    public static final BlockType DARK_OAK_PLANKS = wood("minecraft:dark_oak_planks", 0x4A3018);
    public static final BlockType BRICKS = solid("minecraft:bricks", 2.0, "pickaxe", 0x9A4A3C);
    public static final BlockType STONE_BRICKS = solid("minecraft:stone_bricks", 1.5, "pickaxe", 0x808080);
    public static final BlockType MOSSY_COBBLE = solid("minecraft:mossy_cobblestone", 2.0, "pickaxe", 0x5E7A55);
    public static final BlockType GLASS = def("minecraft:glass", true, false, 0.3, null, false, null, 0xB8E4F0, true, false, false, false, false);
    public static final BlockType FARMLAND = ground("minecraft:farmland", 0.6, "shovel", false, "minecraft:dirt", 0x6B4426);
    public static final BlockType DIRT_PATH = ground("minecraft:dirt_path", 0.65, "shovel", false, "minecraft:dirt", 0xA8854F);
    public static final BlockType WHEAT = plant("minecraft:wheat", 0xC8B040);
    public static final BlockType TORCH = plant("minecraft:torch", 0xFFC040);
    public static final BlockType OAK_SAPLING = plant("minecraft:oak_sapling", 0x4FA03A);
    public static final BlockType CHEST = container("minecraft:chest", 2.5, "axe", 0x9A6A2A);
    public static final BlockType BARREL = container("minecraft:barrel", 2.5, "axe", 0x7A5530);
    public static final BlockType FURNACE = container("minecraft:furnace", 3.5, "pickaxe", 0x707070);
    public static final BlockType CRAFTING = solid("minecraft:crafting_table", 2.5, "axe", 0x9C6B3A);
    public static final BlockType ANVIL = solid("minecraft:anvil", 5.0, "pickaxe", 0x3A3A44);
    public static final BlockType HAY = solid("minecraft:hay_block", 0.5, "hoe", 0xD0B030);
    public static final BlockType BOOKSHELF = solid("minecraft:bookshelf", 1.5, "axe", 0x8A5A30);
    public static final BlockType LECTERN = solid("minecraft:lectern", 2.5, "axe", 0xA07840);
    public static final BlockType BELL = solid("minecraft:bell", 5.0, "pickaxe", 0xE0B020);
    public static final BlockType FENCE = solid("minecraft:oak_fence", 2.0, "axe", 0xA67C45);
    public static final BlockType OAK_DOOR = def("minecraft:oak_door", false, false, 3.0, "axe", false, "minecraft:oak_door", 0x9A7035, false, false, false, false, false);
    public static final BlockType RED_BED = def("minecraft:red_bed", false, false, 0.2, null, false, "minecraft:red_bed", 0xC03030, false, false, false, false, false);
    public static final BlockType LADDER = def("minecraft:ladder", false, false, 0.4, "axe", false, "minecraft:ladder", 0x8A6A3A, false, false, false, false, false);
    public static final BlockType BANNER = def("minecraft:white_banner", false, false, 1.0, "axe", false, "minecraft:white_banner", 0xEEEEEE, false, false, false, false, false);
    public static final BlockType RUBBLE = ground("minecraft:rubble", 1.0, "pickaxe", false, "minecraft:cobblestone", 0x6A665F);
    public static final BlockType MOSS = ground("minecraft:moss_block", 0.1, "hoe", false, "minecraft:moss_block", 0x4C7A3A);
    public static final BlockType WEATHERED_PLANKS = solid("minecraft:weathered_planks", 2.0, "axe", 0x7C6A52);
    public static final BlockType CRACKED_STONE_BRICKS = solid("minecraft:cracked_stone_bricks", 1.5, "pickaxe", 0x6E6E6A);
    public static final BlockType CRACKED_BRICKS = solid("minecraft:cracked_bricks", 2.0, "pickaxe", 0x7E4A40);
    public static final BlockType FIRE = def("minecraft:fire", false, false, 0.0, null, false, null, 0xFF7A18, true, false, false, false, false);
    public static final BlockType BUSH = plant("minecraft:fern", 0x3F7A2C);
    public static final BlockType TALL_GRASS = plant("minecraft:short_grass", 0x62A840);
    public static final BlockType DANDELION = plant("minecraft:dandelion", 0xF2D230);
    public static final BlockType POPPY = plant("minecraft:poppy", 0xD83A2E);
    public static final BlockType CORNFLOWER = plant("minecraft:cornflower", 0x5876E0);
    public static final BlockType ASH = def("minecraft:ash", false, false, 0.0, null, false, null, 0x3A3836, true, false, false, false, false);

    private static synchronized BlockType def(String id, boolean solid, boolean fluid, double hardness, String tool, boolean needsTool,
                                              String drop, int color, boolean translucent, boolean log, boolean leaves, boolean container,
                                              boolean natural) {
        BlockType t = new BlockType(BY_NUM.size(), id, solid, fluid, hardness, tool, needsTool, drop, color, translucent, log, leaves, container, natural);
        BY_NUM.add(t);
        BY_ID.put(id, t);
        return t;
    }

    private static BlockType ground(String id, double h, String tool, boolean needs, String drop, int color) {
        return def(id, true, false, h, tool, needs, drop, color, false, false, false, false, true);
    }

    private static BlockType solid(String id, double h, String tool, int color) {
        return def(id, true, false, h, tool, false, id, color, false, false, false, false, false);
    }

    private static BlockType wood(String id, int color) {
        return solid(id, 2.0, "axe", color);
    }

    private static BlockType log(String id, int color) {
        return def(id, true, false, 2.0, "axe", false, id, color, false, true, false, false, false);
    }

    private static BlockType leaves(String id, int color) {
        return def(id, true, false, 0.2, null, false, null, color, true, false, true, false, false);
    }

    private static BlockType plant(String id, int color) {
        return def(id, false, false, 0.0, null, false, id, color, true, false, false, false, false);
    }

    private static BlockType container(String id, double h, String tool, int color) {
        return def(id, true, false, h, tool, false, id, color, false, false, false, true, false);
    }

    /** "minecraft:oak_stairs[facing=north]" → bloco (propriedades são ignoradas; desconhecido nasce na hora). */
    public static BlockType of(String id) {
        if (id == null || id.isBlank()) return AIR;
        int br = id.indexOf('[');
        String key = br >= 0 ? id.substring(0, br) : id;
        if (!key.contains(":")) key = "minecraft:" + key;
        BlockType t = BY_ID.get(key);
        if (t != null) return t;
        synchronized (Blocks.class) {
            t = BY_ID.get(key);
            if (t != null) return t;
            boolean thin = key.endsWith("_door") || key.endsWith("_bed") || key.endsWith("_carpet") || key.endsWith("_banner") || key.endsWith("_sign")
                    || key.endsWith("torch") || key.endsWith("_button") || key.endsWith("_pressure_plate") || key.endsWith("flower") || key.endsWith("_sapling");
            int color = colorFromName(key);
            return def(key, !thin, false, 1.5, key.contains("log") || key.contains("plank") || key.contains("wood") ? "axe" : "pickaxe", false, key,
                    color, false, false, false, false, false);
        }
    }

    private static final String[][] NAMED_COLORS = {{"light_blue", "6AB0E0"}, {"light_gray", "9D9D97"}, {"white", "EAEAEA"}, {"orange", "E07A1F"},
            {"magenta", "BD44B3"}, {"yellow", "E8C32C"}, {"lime", "70B919"}, {"pink", "ED8DAC"}, {"gray", "474F52"}, {"cyan", "169C9C"},
            {"purple", "8932B8"}, {"blue", "3C44AA"}, {"brown", "835432"}, {"green", "5E7C16"}, {"red", "B02E26"}, {"black", "1D1D21"}};

    /** Cor do cubo: a do nome se ele começa por uma cor ("blue_bed"), senão um cinza derivado do texto. */
    private static int colorFromName(String key) {
        String n = key.substring(key.indexOf(':') + 1);
        for (String[] c : NAMED_COLORS) if (n.startsWith(c[0] + "_")) return Integer.parseInt(c[1], 16);
        return 0x606060 | (key.hashCode() & 0x9F9F9F);
    }

    public static BlockType byNum(int num) {
        synchronized (Blocks.class) {
            return num >= 0 && num < BY_NUM.size() ? BY_NUM.get(num) : AIR;
        }
    }

    public static List<BlockType> all() {
        synchronized (Blocks.class) {
            return List.copyOf(BY_NUM);
        }
    }
}
