package com.kingdomsai.core.construction;

/**
 * Materiais abstratos. O Core sabe que "precisamos de uma parede";
 * só o adaptador do Minecraft sabe que parede = tábuas de carvalho.
 */
public enum Material {
    AIR,
    FOUNDATION,
    FLOOR,
    WALL,
    PILLAR,
    ROOF,
    ROOF_EDGE,
    WINDOW,
    DOOR_LOWER,
    DOOR_UPPER,
    LIGHT,
    BED_FOOT,
    BED_HEAD,
    CHEST,
    BARREL,
    CRAFTING,
    FURNACE,
    ANVIL,
    FARMLAND,
    CROP,
    WATER,
    FENCE,
    PATH,
    BELL,
    HAY,
    BANNER,
    /** Escada de mão (precisa de facing = parede onde encosta). */
    LADDER,
    /** Escada de telhado (facing = lado para onde sobe). */
    ROOF_STAIR,
    CHIMNEY,
    BOOKSHELF,
    /** Atril (facing = para onde o leitor olha). */
    LECTERN,
    /** Bloco explícito (Placement.block) — plantas salvas do mundo ou importadas de .nbt. */
    RAW
}
