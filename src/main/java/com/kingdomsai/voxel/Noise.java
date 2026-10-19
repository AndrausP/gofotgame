package com.kingdomsai.voxel;

/** Ruído de valor determinístico (sem estado): mesma semente, mesmo mundo, em qualquer máquina. */
public final class Noise {
    private Noise() {}

    public static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Hash de uma célula inteira → [0,1). */
    public static double hash(long seed, int x, int y, int z) {
        long h = mix(seed ^ mix(x * 0x9E3779B97F4A7C15L) ^ mix(y * 0xC2B2AE3D27D4EB4FL + 17) ^ mix(z * 0x165667B19E3779F9L + 91));
        return (h >>> 11) * (1.0 / (1L << 53));
    }

    public static double value(long seed, double x, double z) {
        int x0 = (int) Math.floor(x), z0 = (int) Math.floor(z);
        double fx = x - x0, fz = z - z0;
        fx = fx * fx * (3 - 2 * fx);
        fz = fz * fz * (3 - 2 * fz);
        double a = hash(seed, x0, 0, z0), b = hash(seed, x0 + 1, 0, z0);
        double c = hash(seed, x0, 0, z0 + 1), d = hash(seed, x0 + 1, 0, z0 + 1);
        return (a + (b - a) * fx) + ((c + (d - c) * fx) - (a + (b - a) * fx)) * fz;
    }

    /** Soma de oitavas, normalizada em [0,1]. */
    public static double fbm(long seed, double x, double z, int octaves) {
        double sum = 0, amp = 1, norm = 0, f = 1;
        for (int i = 0; i < octaves; i++) {
            sum += value(seed + i * 1013L, x * f, z * f) * amp;
            norm += amp;
            amp *= 0.5;
            f *= 2;
        }
        return sum / norm;
    }
}
