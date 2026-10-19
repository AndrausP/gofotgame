package com.kingdomsai.voxel;

import com.kingdomsai.core.common.Pos;

import java.util.*;

/**
 * Quais partes do mundo estão "carregadas" (simuladas em detalhe): o entorno de quem olha (rei/câmera) e os chunks
 * forçados pelo Core para ordens longe da vista. Fora disso o Core simula de forma abstrata, como no mod.
 */
public final class LoadArea {
    private final VoxelWorld world;
    private final Map<UUID, Pos> viewers = new HashMap<>();
    private final Set<Long> forced = new HashSet<>();
    /** Raio de detalhe, em chunks. */
    public int radiusChunks = 6;

    public LoadArea(VoxelWorld world) {
        this.world = world;
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }

    public void setViewer(UUID id, Pos p) {
        viewers.put(id, p);
    }

    public void removeViewer(UUID id) {
        viewers.remove(id);
    }

    public Collection<Pos> viewers() {
        return viewers.values();
    }

    public boolean loaded(int x, int z) {
        if (!world.inBounds(x, z)) return false;
        int cx = Math.floorDiv(x, Chunk.SIZE), cz = Math.floorDiv(z, Chunk.SIZE);
        if (forced.contains(key(cx, cz))) return true;
        for (Pos v : viewers.values()) {
            int vx = Math.floorDiv(v.x(), Chunk.SIZE), vz = Math.floorDiv(v.z(), Chunk.SIZE);
            if (Math.abs(cx - vx) <= radiusChunks && Math.abs(cz - vz) <= radiusChunks) return true;
        }
        return false;
    }

    public boolean isForced(int cx, int cz) {
        return forced.contains(key(cx, cz));
    }

    public void force(int cx, int cz, boolean on) {
        if (on) forced.add(key(cx, cz));
        else forced.remove(key(cx, cz));
    }

    /** Distância (em blocos, no plano) até o espectador mais próximo; infinito sem espectadores. */
    public double distanceToViewer(int x, int z) {
        double best = Double.MAX_VALUE;
        for (Pos v : viewers.values()) best = Math.min(best, Math.hypot(v.x() - x, v.z() - z));
        return best;
    }
}
