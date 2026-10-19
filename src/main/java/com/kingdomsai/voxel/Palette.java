package com.kingdomsai.voxel;

import com.kingdomsai.core.construction.Blueprint;
import com.kingdomsai.core.construction.Material;

import java.util.Random;
import java.util.Set;

/** "Uma casa significa esses blocos": material abstrato do Core → bloco do mundo voxel. */
public final class Palette {
    private Palette() {}

    private static final BlockType[] WALLS = {Blocks.OAK_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.BIRCH_PLANKS};
    private static final BlockType[] PILLARS = {Blocks.OAK_LOG, Blocks.SPRUCE_LOG, Blocks.of("minecraft:stripped_dark_oak_log")};
    private static final BlockType[] ROOFS = {Blocks.DARK_OAK_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.BRICKS};
    private static final BlockType[] FOUNDATIONS = {Blocks.COBBLE, Blocks.STONE_BRICKS, Blocks.MOSSY_COBBLE};

    /** Blocos que uma planta nunca pode colocar (mesmo vinda de .nbt ou salva do mundo). */
    private static final Set<String> FORBIDDEN = Set.of("command_block", "chain_command_block", "repeating_command_block", "structure_block",
            "jigsaw", "barrier", "bedrock", "tnt", "spawner", "trial_spawner", "end_portal", "end_portal_frame", "nether_portal",
            "end_gateway", "light", "reinforced_deepslate", "lava", "fire", "soul_fire", "respawn_anchor");

    public static boolean forbidden(String id) {
        String n = id.substring(id.indexOf(':') + 1);
        int br = n.indexOf('[');
        if (br >= 0) n = n.substring(0, br);
        return FORBIDDEN.contains(n);
    }

    public static BlockType block(Blueprint.Placement p, Blueprint bp, int variant) {
        Material m = p.material();
        if (m == Material.RAW) return p.block() == null || forbidden(p.block()) ? Blocks.AIR : Blocks.of(p.block());
        String override = bp.materials() == null ? null : bp.materials().get(m);
        if (override != null && !override.isBlank() && !forbidden(override)) {
            BlockType t = Blocks.of(override);
            if (!t.air()) return t;
        }
        int v = Math.floorMod(variant, 3);
        return switch (m) {
            case AIR, RAW -> Blocks.AIR;
            case FOUNDATION -> FOUNDATIONS[v];
            case FLOOR -> WALLS[(v + 1) % 3];
            case WALL -> WALLS[v];
            case PILLAR -> PILLARS[v];
            case ROOF, ROOF_EDGE, ROOF_STAIR -> ROOFS[v];
            case WINDOW -> Blocks.GLASS;
            case DOOR_LOWER, DOOR_UPPER -> Blocks.OAK_DOOR;
            case LIGHT -> Blocks.TORCH;
            case BED_FOOT, BED_HEAD -> Blocks.RED_BED;
            case CHEST -> Blocks.CHEST;
            case BARREL -> Blocks.BARREL;
            case CRAFTING -> Blocks.CRAFTING;
            case FURNACE -> Blocks.FURNACE;
            case ANVIL -> Blocks.ANVIL;
            case FARMLAND -> Blocks.FARMLAND;
            case CROP -> Blocks.WHEAT;
            case WATER -> Blocks.WATER;
            case FENCE -> Blocks.FENCE;
            case PATH -> Blocks.DIRT_PATH;
            case BELL -> Blocks.BELL;
            case HAY -> Blocks.HAY;
            case BANNER -> Blocks.BANNER;
            case LADDER -> Blocks.LADDER;
            case CHIMNEY -> Blocks.BRICKS;
            case BOOKSHELF -> Blocks.BOOKSHELF;
            case LECTERN -> Blocks.LECTERN;
        };
    }

    /** Bloco para preencher buracos sob a fundação. */
    public static BlockType support(Material m, int variant) {
        return m == Material.FOUNDATION ? FOUNDATIONS[Math.floorMod(variant, 3)] : Blocks.DIRT;
    }

    public static int initialGrowth(Random rng) {
        return rng.nextInt(100);
    }
}
