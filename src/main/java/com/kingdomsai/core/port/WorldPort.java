package com.kingdomsai.core.port;

import com.kingdomsai.core.common.Pos;
import com.kingdomsai.core.construction.Blueprint;

/**
 * Porta de saída do Core para o mundo físico. O Core não sabe que o Minecraft existe:
 * o adaptador implementa esta interface. Nos testes, uma implementação falsa basta.
 */
public interface WorldPort {

    /** Resultado da avaliação de um terreno. */
    record SiteCheck(Kind kind, int groundY, double score) {
        public enum Kind { OK, BAD, UNLOADED }

        public static SiteCheck unloaded() {
            return new SiteCheck(Kind.UNLOADED, Integer.MIN_VALUE, 0);
        }

        public static SiteCheck bad() {
            return new SiteCheck(Kind.BAD, Integer.MIN_VALUE, 0);
        }
    }

    /** Avalia se o blueprint cabe com a quina mínima em (x, z). */
    SiteCheck checkSite(int x, int z, Blueprint blueprint);

    /** Hora do dia 0..23999 (0 = amanhecer). */
    long dayTime();

    boolean isLoaded(int x, int z);

    /** Altura do chão ou Integer.MIN_VALUE se não carregado. */
    int surfaceY(int x, int z);

    /**
     * Um ponto firme e plano para nascer um reino perto de (x, z), ou null se este mundo não sabe dizer (o chamador usa o ponto bruto).
     * Mundos gerados (voxel) respondem pela própria geração; o Minecraft deixa como está.
     */
    default com.kingdomsai.core.common.Pos habitableNear(int x, int z, int radius) {
        return null;
    }

    /** Implementação nula — usada quando não há mundo (testes, CLI headless). */
    WorldPort NONE = new WorldPort() {
        @Override
        public SiteCheck checkSite(int x, int z, Blueprint blueprint) {
            return SiteCheck.unloaded();
        }

        @Override
        public long dayTime() {
            return 6000;
        }

        @Override
        public boolean isLoaded(int x, int z) {
            return false;
        }

        @Override
        public int surfaceY(int x, int z) {
            return Integer.MIN_VALUE;
        }
    };
}
