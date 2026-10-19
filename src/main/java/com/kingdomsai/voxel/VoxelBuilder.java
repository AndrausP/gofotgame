package com.kingdomsai.voxel;

import com.kingdomsai.core.KingdomsCore;
import com.kingdomsai.core.common.Pos;
import com.kingdomsai.core.construction.Blueprint;
import com.kingdomsai.core.construction.Building;
import com.kingdomsai.core.construction.ConstructionSystem;
import com.kingdomsai.core.construction.Material;
import com.kingdomsai.core.npc.Npc;
import com.kingdomsai.core.port.PhysicalPort;

import java.util.*;

/**
 * Obras de verdade: o construtor presente na obra coloca bloco a bloco; o que a simulação abstrata já avançou
 * aparece quando a área está carregada (LOD). É o ConstructionExecutor do mod, sobre o mundo voxel.
 */
public final class VoxelBuilder {
    public static final int MAX_BLOCKS_PER_TICK = 100;
    public static final double BUILDER_BLOCKS_PER_SECOND = 2.0;

    private final KingdomsCore core;
    private final VoxelWorld world;
    private final LoadArea area;
    private final VoxelPhysicalPort port;
    private final Map<UUID, Double> accumulator = new HashMap<>();
    private final Random rng;

    public VoxelBuilder(KingdomsCore core, VoxelWorld world, LoadArea area, VoxelPhysicalPort port) {
        this.core = core;
        this.world = world;
        this.area = area;
        this.port = port;
        this.rng = new Random(world.gen().seed() ^ 0xB011D);
    }

    public void tick() {
        int budget = MAX_BLOCKS_PER_TICK;
        for (Building b : List.copyOf(core.state().buildings.values())) {
            if (budget <= 0) break;
            if (b.status == Building.Status.ABANDONED) continue;
            Blueprint bp = b.blueprint();
            if (bp == null) continue;
            boolean active = b.status == Building.Status.UNDER_CONSTRUCTION;
            if (!active && b.placed >= bp.blockCount()) continue;
            Pos o = b.origin;
            if (!area.loaded(o.x() - 1, o.z() - 1) || !area.loaded(o.x() + bp.sizeX(), o.z() + bp.sizeZ())) continue;
            if (o.y() == ConstructionSystem.UNKNOWN_Y) {
                var check = core.world().checkSite(o.x(), o.z(), bp);
                int y = check.groundY() != Integer.MIN_VALUE ? check.groundY() : world.groundY(o.x() + bp.sizeX() / 2, o.z() + bp.sizeZ() / 2);
                core.construction().resolveGround(b, y);
            }
            // 1) materializa o que a simulação já construiu
            while (b.placed < b.progress && budget > 0) {
                place(b, bp.placements().get(b.placed));
                b.placed++;
                budget--;
            }
            if (!active || b.placed < b.progress) continue;
            // 2) construção ao vivo: cada construtor presente na obra soma velocidade
            List<Npc> workers = new ArrayList<>();
            Pos c = b.centerPos();
            double reach = Math.max(bp.sizeX(), bp.sizeZ()) / 2.0 + 7;
            for (Npc n : core.construction().activeBuilders(b)) {
                if (!n.materialized || n.pos == null) continue;
                double dx = n.pos.x() - c.x(), dz = n.pos.z() - c.z();
                if (dx * dx + dz * dz <= reach * reach) workers.add(n);
            }
            if (workers.isEmpty()) continue;
            double acc = accumulator.getOrDefault(b.id, 0.0) + BUILDER_BLOCKS_PER_SECOND * workers.size() / 20.0;
            int guard = 0, turn = 0;
            while (acc >= 1 && budget > 0 && b.status == Building.Status.UNDER_CONSTRUCTION && guard++ < 96) {
                Blueprint.Placement p = bp.placements().get(b.progress);
                boolean changed = place(b, p);
                core.construction().stepPhysical(b);
                b.placed = b.progress;
                budget--;
                if (changed && p.material() != Material.AIR) {
                    acc -= 1;
                    Npc w = workers.get(turn++ % workers.size());
                    port.animate(w.id, new Pos(b.origin.x() + p.x(), b.origin.y() + p.y(), b.origin.z() + p.z()), PhysicalPort.Anim.SWING, 0);
                } else if (changed) acc -= 0.35; // limpar terreno é mais rápido
            }
            accumulator.put(b.id, Math.min(acc, 3));
        }
    }

    /** @return true se algum bloco mudou. */
    private boolean place(Building b, Blueprint.Placement p) {
        int x = b.origin.x() + p.x(), y = b.origin.y() + p.y(), z = b.origin.z() + p.z();
        BlockType current = world.get(x, y, z);
        if (p.material() == Material.AIR) {
            if (current.air() || !clearable(current)) return false;
            return world.set(x, y, z, Blocks.AIR);
        }
        BlockType target = Palette.block(p, b.blueprint(), b.variant);
        if (current.container() && !target.container()) return false; // não destrói coisas do jogador
        if (target.air()) return false; // bloco proibido/inválido: pula
        if (current == target && p.material() != Material.CROP) return false;
        boolean changed = world.set(x, y, z, target);
        world.markBuilt(new Pos(x, y, z));
        if (target == Blocks.WHEAT) world.setGrowth(new Pos(x, y, z), Palette.initialGrowth(rng));
        if (p.y() == -1) fillBelow(x, y - 1, z, Palette.support(p.material(), b.variant));
        return changed;
    }

    private void fillBelow(int x, int y, int z, BlockType support) {
        for (int i = 0; i < 6 && y > 0; i++, y--) {
            BlockType s = world.get(x, y, z);
            if (!(s.air() || !s.solid() || s.fluid())) break;
            world.set(x, y, z, support);
            world.markBuilt(new Pos(x, y, z));
        }
    }

    /** Só limpa blocos naturais — nunca algo que o jogador construiu. */
    private static boolean clearable(BlockType s) {
        if (s.container()) return false;
        return !s.solid() && !s.fluid() || s.leaves() || s.log() || s.natural();
    }
}
