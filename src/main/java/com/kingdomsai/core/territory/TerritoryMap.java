package com.kingdomsai.core.territory;

import com.kingdomsai.core.common.Pos;

import java.util.*;

/** O mundo é dividido em células administrativas (cellSize x cellSize blocos). */
public final class TerritoryMap {
    public int cellSize = 32;
    public Map<Long, Cell> cells = new HashMap<>();

    public static final class Cell {
        public int cx, cz;
        public UUID owner;
        /** Força da reivindicação 0..100. */
        public double claim;
        /** Controle militar 0..100. */
        public double control;
        public long claimedTick;

        public Cell() {}
    }

    public TerritoryMap() {}

    public static long key(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xffffffffL);
    }

    public Cell cellAt(int cx, int cz) {
        return cells.get(key(cx, cz));
    }

    public Cell cellAt(Pos p) {
        return cellAt(p.cellX(cellSize), p.cellZ(cellSize));
    }

    public UUID ownerAt(Pos p) {
        Cell c = cellAt(p);
        return c == null ? null : c.owner;
    }

    public Cell claim(int cx, int cz, UUID owner, double strength, long tick) {
        Cell c = cells.computeIfAbsent(key(cx, cz), k -> {
            Cell n = new Cell();
            n.cx = cx;
            n.cz = cz;
            return n;
        });
        c.owner = owner;
        c.claim = strength;
        c.control = strength;
        c.claimedTick = tick;
        return c;
    }

    /** Reivindica um círculo de células ao redor de um ponto, sem roubar células de outros donos. */
    public int claimRadius(Pos center, int radius, UUID owner, long tick) {
        int ccx = center.cellX(cellSize), ccz = center.cellZ(cellSize);
        int n = 0;
        for (int dx = -radius; dx <= radius; dx++)
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius + 1) continue;
                Cell c = cellAt(ccx + dx, ccz + dz);
                if (c != null && c.owner != null && !c.owner.equals(owner)) continue;
                double strength = 100 - 12 * Math.sqrt(dx * dx + dz * dz);
                claim(ccx + dx, ccz + dz, owner, Math.max(30, strength), tick);
                n++;
            }
        return n;
    }

    public int countOwned(UUID owner) {
        int n = 0;
        for (Cell c : cells.values()) if (owner.equals(c.owner)) n++;
        return n;
    }

    public double areaKm2(UUID owner) {
        return countOwned(owner) * (double) cellSize * cellSize / 1_000_000.0;
    }

    /** Células livres adjacentes ao território do dono, ordenadas pela distância ao centro. */
    public List<int[]> claimableFrontier(UUID owner, Pos center) {
        Set<Long> seen = new HashSet<>();
        List<int[]> out = new ArrayList<>();
        for (Cell c : cells.values()) {
            if (!owner.equals(c.owner)) continue;
            int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int[] d : dirs) {
                int nx = c.cx + d[0], nz = c.cz + d[1];
                long k = key(nx, nz);
                if (!seen.add(k)) continue;
                Cell o = cells.get(k);
                if (o == null || o.owner == null) out.add(new int[]{nx, nz});
            }
        }
        int ccx = center.cellX(cellSize), ccz = center.cellZ(cellSize);
        out.sort(Comparator.comparingInt(a -> (a[0] - ccx) * (a[0] - ccx) + (a[1] - ccz) * (a[1] - ccz)));
        return out;
    }

    /** Há células de 'a' encostando em células de 'b'? */
    public boolean bordersTouch(UUID a, UUID b) {
        for (Cell c : cells.values()) {
            if (!a.equals(c.owner)) continue;
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                Cell o = cellAt(c.cx + d[0], c.cz + d[1]);
                if (o != null && b.equals(o.owner)) return true;
            }
        }
        return false;
    }

    /** Distância (em blocos) da posição até a borda do território do dono. */
    public double distanceToBorder(UUID owner, Pos p) {
        double best = Double.MAX_VALUE;
        for (Cell c : cells.values()) {
            if (!owner.equals(c.owner)) continue;
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                Cell o = cellAt(c.cx + d[0], c.cz + d[1]);
                if (o == null || !owner.equals(o.owner)) {
                    double bx = (c.cx + 0.5 + d[0] * 0.5) * cellSize, bz = (c.cz + 0.5 + d[1] * 0.5) * cellSize;
                    double dist = Math.hypot(bx - p.x(), bz - p.z());
                    if (dist < best) best = dist;
                }
            }
        }
        return best == Double.MAX_VALUE ? 0 : best;
    }
}
