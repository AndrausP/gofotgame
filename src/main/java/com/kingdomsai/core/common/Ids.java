package com.kingdomsai.core.common;

import java.util.Random;
import java.util.UUID;

/**
 * Fábrica de UUIDs do Core. Normalmente aleatórios; com -Dkingdoms.seed=N saem de uma sequência fixa,
 * o que torna uma execução inteira reproduzível (testes e relatos de bug).
 */
public final class Ids {
    private static final Random SEEDED = seeded();

    private Ids() {}

    private static Random seeded() {
        Long seed = Long.getLong("kingdoms.seed");
        return seed == null ? null : new Random(seed ^ 0x1D5C0DEL);
    }

    public static UUID next() {
        if (SEEDED == null) return UUID.randomUUID();
        synchronized (SEEDED) {
            return new UUID(SEEDED.nextLong(), SEEDED.nextLong());
        }
    }
}
