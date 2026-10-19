package com.kingdomsai.voxel;

/**
 * Geração do mundo por ruído em camadas: relevo, rios, praias, minérios, florestas. Determinística pela semente.
 * Perto da origem o terreno é suave e plano, onde nasce o primeiro reino.
 */
public final class WorldGen {
    public static final int SEA = 62;
    private static final int TREE_CELL = 6;

    private final long seed;

    public WorldGen(long seed) {
        this.seed = seed;
    }

    public long seed() {
        return seed;
    }

    /** Altura (y do bloco de cima do chão natural) na coluna. */
    public int height(int x, int z) {
        double continent = Noise.fbm(seed + 1, x / 260.0, z / 260.0, 4);
        double hills = Noise.fbm(seed + 2, x / 70.0, z / 70.0, 3);
        double mountain = Math.max(0, Noise.fbm(seed + 3, x / 180.0, z / 180.0, 3) - 0.55) * 2.4;
        double detail = Noise.fbm(seed + 10, x / 14.0, z / 14.0, 2);
        double h = 56 + continent * 22 + hills * 13 + (detail - 0.5) * 3.2 + mountain * 48;
        double r = Math.sqrt((double) x * x + (double) z * z);
        double flat = Math.max(0, Math.min(1, 1 - (r - 32) / 48.0));
        h = h + (70 + hills * 3 - h) * flat * flat * (3 - 2 * flat);
        if (r > 45) {
            double river = Math.abs(Noise.fbm(seed + 4, x / 150.0, z / 150.0, 2) - 0.5);
            if (river < 0.014 && h > 58) h = 59 - (0.014 - river) * 40;
        }
        return Math.max(2, Math.min(Chunk.HEIGHT - 20, (int) Math.round(h)));
    }

    private double forest(int x, int z) {
        return Noise.fbm(seed + 5, x / 85.0, z / 85.0, 3);
    }

    /** Estrutura de uma árvore: base (x, z), altura do tronco e tipo (0 carvalho, 1 bétula, 2 pinheiro); ou null. */
    private int[] tree(int cellX, int cellZ) {
        int ox = (int) (Noise.hash(seed + 6, cellX, 1, cellZ) * TREE_CELL);
        int oz = (int) (Noise.hash(seed + 6, cellX, 2, cellZ) * TREE_CELL);
        int x = cellX * TREE_CELL + ox, z = cellZ * TREE_CELL + oz;
        double f = forest(x, z);
        double density = f > 0.52 ? 0.75 : f > 0.45 ? 0.22 : 0.03;
        if (Noise.hash(seed + 7, cellX, 3, cellZ) > density) return null;
        if (Math.sqrt((double) x * x + (double) z * z) < 20) return null; // praça central livre
        int h = height(x, z);
        if (h <= SEA + 1 || h > 92) return null;
        int type = h > 82 ? 2 : Noise.fbm(seed + 8, x / 60.0, z / 60.0, 2) > 0.6 ? 1 : 0;
        int trunk = 4 + (int) (Noise.hash(seed + 9, cellX, 4, cellZ) * 3);
        return new int[]{x, z, h, trunk, type};
    }

    public void generate(Chunk c) {
        int bx = c.cx * Chunk.SIZE, bz = c.cz * Chunk.SIZE;
        int[] heights = new int[Chunk.SIZE * Chunk.SIZE];
        for (int lz = 0; lz < Chunk.SIZE; lz++)
            for (int lx = 0; lx < Chunk.SIZE; lx++) {
                int x = bx + lx, z = bz + lz;
                int h = height(x, z);
                heights[lz * Chunk.SIZE + lx] = h;
                boolean beach = h <= SEA + 1;
                for (int y = 0; y <= Math.max(h, SEA); y++) {
                    BlockType t;
                    if (y == 0) t = Blocks.BEDROCK;
                    else if (y > h) t = Blocks.WATER;
                    else if (y == h) t = h > 94 ? Blocks.STONE : beach ? Blocks.SAND : Blocks.GRASS;
                    else if (y >= h - 3) t = beach ? Blocks.SAND : h > 94 ? Blocks.STONE : Blocks.DIRT;
                    else t = ore(x, y, z);
                    c.set(lx, y, lz, (short) t.num());
                }
            }
        // capim e flores onde há grama e céu aberto
        for (int lz = 0; lz < Chunk.SIZE; lz++)
            for (int lx = 0; lx < Chunk.SIZE; lx++) {
                int h = heights[lz * Chunk.SIZE + lx];
                if (h <= SEA + 1 || h >= Chunk.HEIGHT - 2 || c.get(lx, h, lz) != Blocks.GRASS.num()) continue;
                int x = bx + lx, z = bz + lz;
                double roll = Noise.hash(seed + 30, x, 0, z);
                BlockType p = roll < 0.012 ? Blocks.DANDELION : roll < 0.020 ? Blocks.POPPY : roll < 0.026 ? Blocks.CORNFLOWER
                        : roll < 0.20 + 0.18 * forest(x, z) ? Blocks.TALL_GRASS : null;
                if (p != null && Math.sqrt((double) x * x + (double) z * z) > 14) c.set(lx, h + 1, lz, (short) p.num());
            }
        // árvores cujas copas tocam este chunk
        int c0x = Math.floorDiv(bx - 3, TREE_CELL), c1x = Math.floorDiv(bx + Chunk.SIZE + 2, TREE_CELL);
        int c0z = Math.floorDiv(bz - 3, TREE_CELL), c1z = Math.floorDiv(bz + Chunk.SIZE + 2, TREE_CELL);
        for (int cellZ = c0z; cellZ <= c1z; cellZ++)
            for (int cellX = c0x; cellX <= c1x; cellX++) {
                int[] t = tree(cellX, cellZ);
                if (t != null) stamp(c, t);
            }
    }

    private BlockType ore(int x, int y, int z) {
        if (y > 90) return Blocks.STONE;
        int cx = Math.floorDiv(x, 4), cy = Math.floorDiv(y, 4), cz = Math.floorDiv(z, 4);
        double v = Noise.hash(seed + 20, cx, cy, cz);
        double fill = Noise.hash(seed + 21, x, y, z);
        if (y < 16 && v < 0.006 && fill < 0.7) return Blocks.DIAMOND_ORE;
        if (y < 32 && v > 0.006 && v < 0.016 && fill < 0.7) return Blocks.GOLD_ORE;
        if (y < 60 && v > 0.016 && v < 0.040 && fill < 0.7) return Blocks.IRON_ORE;
        if (y < 82 && v > 0.040 && v < 0.080 && fill < 0.65) return Blocks.COAL_ORE;
        if (y < 55 && Noise.hash(seed + 22, cx, cy, cz) > 0.985 && fill < 0.8) return Blocks.GRAVEL;
        return Blocks.STONE;
    }

    private void stamp(Chunk c, int[] t) {
        int tx = t[0], tz = t[1], ground = t[2], trunk = t[3], type = t[4];
        BlockType log = type == 1 ? Blocks.BIRCH_LOG : type == 2 ? Blocks.SPRUCE_LOG : Blocks.OAK_LOG;
        BlockType leaf = type == 1 ? Blocks.BIRCH_LEAVES : type == 2 ? Blocks.SPRUCE_LEAVES : Blocks.OAK_LEAVES;
        int top = ground + trunk;
        for (int y = ground + 1; y <= top; y++) put(c, tx, y, tz, log, true);
        for (int y = top - 2; y <= top + 1; y++) {
            int rad = type == 2 ? Math.max(0, (top + 1 - y)) / 2 + (y == top + 1 ? 0 : 1) : (y >= top ? 1 : 2);
            if (type == 2 && y < top - 1) rad = 2;
            for (int dz = -rad; dz <= rad; dz++)
                for (int dx = -rad; dx <= rad; dx++) {
                    if (rad >= 2 && Math.abs(dx) == rad && Math.abs(dz) == rad) continue; // quina arredondada
                    put(c, tx + dx, y, tz + dz, leaf, false);
                }
        }
    }

    private static void put(Chunk c, int x, int y, int z, BlockType t, boolean overwriteLeaves) {
        int lx = x - c.cx * Chunk.SIZE, lz = z - c.cz * Chunk.SIZE;
        if (lx < 0 || lx >= Chunk.SIZE || lz < 0 || lz >= Chunk.SIZE || y < 0 || y >= Chunk.HEIGHT) return;
        BlockType cur = Blocks.byNum(c.get(lx, y, lz));
        if (cur.air() || (!cur.solid() && !cur.fluid()) || (cur.leaves() && overwriteLeaves)) c.set(lx, y, lz, (short) t.num());
    }
}
