package com.kingdomsai.core.common;

/** Posição de bloco independente do Minecraft. O Core nunca importa classes do jogo. */
public record Pos(int x, int y, int z) {

    public static final Pos ORIGIN = new Pos(0, 0, 0);

    public Pos offset(int dx, int dy, int dz) {
        return new Pos(x + dx, y + dy, z + dz);
    }

    public double distSq(Pos o) {
        double dx = x - o.x, dy = y - o.y, dz = z - o.z;
        return dx * dx + dy * dy + dz * dz;
    }

    public double distXZ(Pos o) {
        double dx = x - o.x, dz = z - o.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    public int cellX(int cellSize) {
        return Math.floorDiv(x, cellSize);
    }

    public int cellZ(int cellSize) {
        return Math.floorDiv(z, cellSize);
    }

    @Override
    public String toString() {
        return x + " " + y + " " + z;
    }
}
