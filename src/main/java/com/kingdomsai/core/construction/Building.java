package com.kingdomsai.core.construction;

import com.kingdomsai.core.common.Pos;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Uma construção registrada no reino.
 *
 * progress = quantos passos do blueprint já foram concluídos (simulação, mesmo longe do jogador).
 * placed   = quantos passos já existem fisicamente no mundo. Quando a área carrega e placed < progress,
 *            o adaptador "materializa" o que falta — LOD aplicado a construções.
 */
public final class Building {
    public enum Status { PLANNED, UNDER_CONSTRUCTION, COMPLETE, ABANDONED }

    public UUID id;
    public UUID kingdomId;
    public String blueprintId;
    public Pos origin;
    public int variant;
    public Status status = Status.PLANNED;
    public int progress;
    public int placed;
    public long startedTick;
    public long completedTick;
    /** Construtor principal (compatibilidade com saves antigos). */
    public UUID builderId;
    /** Todos os construtores designados (principal + ajudantes puxados por prazo). */
    public List<UUID> builderIds = new ArrayList<>();
    /** Prazo dado pelo rei (tick absoluto); 0 = sem prazo. */
    public long deadlineTick;
    public boolean overdueWarned;
    public boolean atRiskWarned;
    public List<UUID> residents = new ArrayList<>();
    /** Para quem a construção foi feita (ex.: "casa para o ferreiro"). */
    public UUID dedicatedTo;
    /** Baú do prédio (forja, armazém, fazenda...) usado pelas cadeias de trabalho. */
    public java.util.Map<com.kingdomsai.core.work.Item, Integer> inventory = new java.util.EnumMap<>(com.kingdomsai.core.work.Item.class);
    /** Fazenda: tick em que foi plantada (0 = vazia). */
    public long cropPlantedTick;
    /** Fazenda: já recebeu o saco de sementes inicial. */
    public boolean seeded;

    public Building() {}

    public Blueprint blueprint() {
        return BlueprintLibrary.get(blueprintId);
    }

    public boolean isComplete() {
        return status == Status.COMPLETE;
    }

    public Pos entrance() {
        Blueprint b = blueprint();
        return origin.offset(b.sizeX() / 2, 0, -1);
    }

    public Pos centerPos() {
        Blueprint b = blueprint();
        return origin.offset(b.sizeX() / 2, 0, b.sizeZ() / 2);
    }

    public boolean overlaps(Pos otherOrigin, Blueprint other, int margin) {
        Blueprint b = blueprint();
        if (b == null) return false;
        int ax0 = origin.x() - margin, ax1 = origin.x() + b.sizeX() + margin;
        int az0 = origin.z() - margin, az1 = origin.z() + b.sizeZ() + margin;
        int bx0 = otherOrigin.x(), bx1 = otherOrigin.x() + other.sizeX();
        int bz0 = otherOrigin.z(), bz1 = otherOrigin.z() + other.sizeZ();
        if (!(ax0 < bx1 && bx0 < ax1 && az0 < bz1 && bz0 < az1)) return false;
        // Muralha é um anel: o que fica inteiro do lado de dentro não colide com ela.
        boolean thisRing = VillageWall.isWall(b), otherRing = VillageWall.isWall(other);
        if (thisRing && !otherRing) return !inside(origin, b, bx0, bz0, bx1, bz1, margin);
        if (otherRing && !thisRing)
            return !inside(otherOrigin, other, origin.x(), origin.z(), origin.x() + b.sizeX(), origin.z() + b.sizeZ(), margin);
        return true;
    }

    /** O retângulo [x0,x1)×[z0,z1) cabe no miolo do anel (com folga)? */
    private static boolean inside(Pos ringOrigin, Blueprint ring, int x0, int z0, int x1, int z1, int margin) {
        int ix0 = ringOrigin.x() + 1 + margin, iz0 = ringOrigin.z() + 1 + margin;
        int ix1 = ringOrigin.x() + ring.sizeX() - 1 - margin, iz1 = ringOrigin.z() + ring.sizeZ() - 1 - margin;
        return x0 >= ix0 && z0 >= iz0 && x1 <= ix1 && z1 <= iz1;
    }

    public boolean hasBuilder(UUID npc) {
        return npc != null && (npc.equals(builderId) || builderIds.contains(npc));
    }

    public double percent() {
        Blueprint b = blueprint();
        if (b == null) return 0;
        int total = b.solidTotal();
        return total == 0 ? 100.0 * progress / Math.max(1, b.blockCount()) : 100.0 * b.solidBefore(progress) / total;
    }
}
