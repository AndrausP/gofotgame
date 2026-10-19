package com.kingdomsai.core.construction;

import com.kingdomsai.core.kingdom.ResourceType;

import java.util.List;
import java.util.Map;

/**
 * Planta de construção. A LLM nunca coloca blocos: ela escolhe uma planta (ou parâmetros de uma planta
 * paramétrica) e o Construction System faz o resto.
 *
 * Coordenadas locais: y = 0 é o nível do chão; y = -1 é a fundação. A porta fica em z = 0.
 *
 * @param source    builtin | param | saved | nbt
 * @param materials sobrescreve o bloco de um material abstrato (ex.: WALL → "minecraft:stone_bricks").
 *                  O Core trata como texto opaco; quem resolve é o adaptador.
 */
public record Blueprint(
        String id,
        String displayName,
        Category category,
        int sizeX, int sizeY, int sizeZ,
        Map<ResourceType, Integer> cost,
        int housing,
        List<Placement> placements,
        String source,
        Map<Material, String> materials
) {
    public enum Category { CIVIC, HOUSING, FARM, STORAGE, MILITARY, INDUSTRY }

    /**
     * @param block estado de bloco explícito ("minecraft:oak_stairs[facing=north]") quando material == RAW.
     */
    public record Placement(int x, int y, int z, Material material, Facing facing, String block) {
        public Placement(int x, int y, int z, Material material, Facing facing) {
            this(x, y, z, material, facing, null);
        }
    }

    /** Direção abstrata (para portas, camas, escadas). */
    public enum Facing { NORTH, SOUTH, EAST, WEST, NONE }

    public Blueprint(String id, String displayName, Category category, int sizeX, int sizeY, int sizeZ,
                     Map<ResourceType, Integer> cost, int housing, List<Placement> placements) {
        this(id, displayName, category, sizeX, sizeY, sizeZ, cost, housing, placements, "builtin", Map.of());
    }

    public int blockCount() {
        return placements.size();
    }

    /** Quantos blocos "de verdade" (não-AIR) existem do passo 0 até o passo index (exclusivo). */
    public int solidBefore(int index) {
        int[] p = BlueprintLibrary.solidPrefix(this);
        return p[Math.max(0, Math.min(index, p.length - 1))];
    }

    public int solidTotal() {
        int[] p = BlueprintLibrary.solidPrefix(this);
        return p[p.length - 1];
    }
}
