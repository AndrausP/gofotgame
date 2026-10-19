package com.kingdomsai.voxel;

import com.kingdomsai.core.construction.Blueprint;
import com.kingdomsai.core.port.WorldPort;

import java.util.Arrays;
import java.util.function.LongSupplier;

/** Pergunta do Core sobre terreno: onde cabe uma obra, que horas são, quão alto é o chão. */
public final class VoxelWorldPort implements WorldPort {
    private final VoxelWorld world;
    private final LoadArea area;
    private final LongSupplier ticks;

    public VoxelWorldPort(VoxelWorld world, LoadArea area, LongSupplier ticks) {
        this.world = world;
        this.area = area;
        this.ticks = ticks;
    }

    @Override
    public long dayTime() {
        return (ticks.getAsLong() + 6000) % 24000L;
    }

    @Override
    public boolean isLoaded(int x, int z) {
        return area.loaded(x, z);
    }

    @Override
    public int surfaceY(int x, int z) {
        if (!isLoaded(x, z)) return Integer.MIN_VALUE;
        return world.groundY(x, z);
    }

    /** Procura em espiral um ponto de terra firme, plano e baixo (sem água, sem serra) — usa só a geração, sem carregar chunks. */
    @Override
    public com.kingdomsai.core.common.Pos habitableNear(int x, int z, int radius) {
        WorldGen g = world.gen();
        for (int r = 0; r <= radius; r += 6) {
            int steps = Math.max(1, r / 3 * 2);
            for (int i = 0; i < steps; i++) {
                double a = i * Math.PI * 2 / steps;
                int px = x + (int) Math.round(Math.cos(a) * r), pz = z + (int) Math.round(Math.sin(a) * r);
                if (!world.inBounds(px, pz) || !suitable(g, px, pz)) continue;
                return new com.kingdomsai.core.common.Pos(px, g.height(px, pz) + 1, pz);
            }
        }
        return null;
    }

    private static boolean suitable(WorldGen g, int x, int z) {
        int h = g.height(x, z);
        if (h <= WorldGen.SEA + 2 || h > 84) return false;
        int lo = h, hi = h;
        for (int dx = -14; dx <= 14; dx += 7)
            for (int dz = -14; dz <= 14; dz += 7) {
                int e = g.height(x + dx, z + dz);
                if (e <= WorldGen.SEA + 1) return false;
                lo = Math.min(lo, e);
                hi = Math.max(hi, e);
            }
        return hi - lo <= 6;
    }

    @Override
    public SiteCheck checkSite(int x, int z, Blueprint bp) {
        int w = bp.sizeX(), d = bp.sizeZ();
        if (!isLoaded(x - 1, z - 1) || !isLoaded(x + w, z - 1) || !isLoaded(x - 1, z + d) || !isLoaded(x + w, z + d))
            return SiteCheck.unloaded();
        int[] heights = new int[w * d];
        int i = 0;
        for (int dx = 0; dx < w; dx++)
            for (int dz = 0; dz < d; dz++) {
                int gy = world.groundY(x + dx, z + dz);
                BlockType ground = world.get(x + dx, gy - 1, z + dz);
                if (ground.fluid() || !ground.natural()) return SiteCheck.bad();
                heights[i++] = gy;
            }
        int[] sorted = heights.clone();
        Arrays.sort(sorted);
        int range = sorted[sorted.length - 1] - sorted[0];
        if (range > 4) return SiteCheck.bad();
        return new SiteCheck(SiteCheck.Kind.OK, sorted[sorted.length / 2], 1.0 / (1 + range));
    }
}
