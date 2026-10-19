package com.kingdomsai.voxel;

/**
 * Um tipo de bloco do mundo voxel. Os ids de texto são os do Minecraft ("minecraft:stone") porque o Core fala assim;
 * o número é o que viaja pela rede e vive nos chunks.
 *
 * @param tool      ferramenta certa (pickaxe|axe|shovel|hoe) ou null
 * @param needsTool sem a ferramenta certa não dá nada
 * @param drop      o que cai ao quebrar com a ferramenta certa (null = nada)
 * @param color     cor do cubo no cliente (0xRRGGBB)
 */
public record BlockType(int num, String id, boolean solid, boolean fluid, double hardness, String tool, boolean needsTool,
                        String drop, int color, boolean translucent, boolean log, boolean leaves, boolean container,
                        boolean natural) {

    public boolean air() {
        return num == 0;
    }

    public boolean breakable() {
        return !air() && !fluid && hardness >= 0;
    }
}
