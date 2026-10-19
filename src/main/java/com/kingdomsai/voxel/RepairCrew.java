package com.kingdomsai.voxel;

import com.kingdomsai.core.KingdomsCore;
import com.kingdomsai.core.common.Pos;
import com.kingdomsai.core.construction.Building;
import com.kingdomsai.core.event.EventType;
import com.kingdomsai.core.event.GameEvent;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.kingdom.ResourceType;
import com.kingdomsai.core.npc.Npc;
import com.kingdomsai.core.npc.NpcActivity;
import com.kingdomsai.core.npc.Profession;
import com.kingdomsai.core.port.PhysicalPort;

import java.util.*;

/**
 * Reparo é trabalho de gente: construtores (ou quem estiver livre) vão até a obra danificada, gastam material do
 * reino e recolocam bloco a bloco o que a planta pede. Sem material ou sem braços, a obra continua se arruinando.
 */
public final class RepairCrew {
    private static final double BLOCKS_PER_SECOND = 2.0;

    private final KingdomsCore core;
    private final VoxelWorld world;
    private final LoadArea area;
    private final NpcMover mover;
    private final VoxelPhysicalPort port;
    private final WorldSim sim;
    private final Map<UUID, List<UUID>> crews = new HashMap<>();
    private final Map<UUID, Double> acc = new HashMap<>();
    private final Map<UUID, Long> lastShortage = new HashMap<>();

    public RepairCrew(KingdomsCore core, VoxelWorld world, LoadArea area, NpcMover mover, VoxelPhysicalPort port, WorldSim sim) {
        this.core = core;
        this.world = world;
        this.area = area;
        this.mover = mover;
        this.port = port;
        this.sim = sim;
    }

    public List<UUID> crew(UUID building) {
        return crews.getOrDefault(building, List.of());
    }

    public void tick() {
        long t = core.tick();
        if (t % 40 == 0) assign();
        work();
    }

    private void assign() {
        for (UUID bid : List.copyOf(sim.damage().keySet())) {
            Building b = core.state().buildings.get(bid);
            Set<Pos> set = sim.damage().get(bid);
            if (b == null || b.status != Building.Status.COMPLETE || set == null || set.isEmpty()) {
                release(bid);
                if (set != null && set.isEmpty()) sim.damage().remove(bid);
                continue;
            }
            Pos c = b.centerPos();
            if (!area.loaded(c.x(), c.z())) continue;
            List<UUID> crew = crews.computeIfAbsent(bid, k -> new ArrayList<>());
            crew.removeIf(id -> {
                Npc n = core.npc(id);
                return n == null || !n.alive || !n.materialized || !mover.onErrand(id);
            });
            int want = Math.min(3, 1 + set.size() / 25);
            if (crew.size() >= want) continue;
            Set<UUID> busy = new HashSet<>();
            for (List<UUID> l : crews.values()) busy.addAll(l);
            List<Npc> pool = new ArrayList<>();
            for (Npc n : core.citizens(b.kingdomId)) {
                if (!n.alive || !n.materialized || n.pos == null || busy.contains(n.id) || n.jobId != null) continue;
                if (n.profession.isMilitary() || n.activity == NpcActivity.SLEEP || n.activity == NpcActivity.SUMMONED || n.hunger < 25) continue;
                pool.add(n);
            }
            pool.sort(Comparator.<Npc>comparingInt(n -> n.profession == Profession.BUILDER ? 0 : 1).thenComparingDouble(n -> n.pos.distXZ(c)));
            for (Npc n : pool) {
                if (crew.size() >= want) break;
                crew.add(n.id);
                mover.setErrand(n.id, standCell(b));
            }
        }
    }

    private Pos standCell(Building b) {
        Pos e = b.entrance();
        int x = e != null ? e.x() : b.centerPos().x(), z = e != null ? e.z() : b.centerPos().z();
        return new Pos(x, world.groundY(x, z), z);
    }

    private void release(UUID bid) {
        List<UUID> crew = crews.remove(bid);
        if (crew != null) for (UUID id : crew) mover.clearErrand(id);
        acc.remove(bid);
    }

    private void work() {
        for (UUID bid : List.copyOf(crews.keySet())) {
            Building b = core.state().buildings.get(bid);
            Set<Pos> set = sim.damage().get(bid);
            if (b == null || set == null) {
                release(bid);
                continue;
            }
            Kingdom k = core.kingdom(b.kingdomId);
            Pos c = b.centerPos();
            double reach = Math.max(b.blueprint().sizeX(), b.blueprint().sizeZ()) / 2.0 + 6;
            List<Npc> here = new ArrayList<>();
            for (UUID id : crews.get(bid)) {
                Npc n = core.npc(id);
                if (n != null && n.pos != null && n.pos.distXZ(c) <= reach) here.add(n);
            }
            if (here.isEmpty() || k == null) continue;
            double a = acc.getOrDefault(bid, 0.0) + BLOCKS_PER_SECOND * here.size() / 20.0;
            int guard = 0;
            while (a >= 1 && !set.isEmpty() && guard++ < 8) {
                Pos target = pick(set, here.get(0).pos);
                BlockType exp = sim.expected(b, target);
                BlockType cur = world.get(target);
                if (exp == null || cur == exp && world.isBuilt(target) && !worn(cur)) {
                    set.remove(target);
                    continue;
                }
                boolean replaceable = cur.air() || !cur.solid() && !cur.fluid() || cur == Blocks.RUBBLE || cur == Blocks.MOSS || cur == Blocks.FIRE
                        || worn(cur) || cur == exp;
                if (!replaceable) {
                    set.remove(target);
                    continue;
                }
                ResourceType rt = costType(exp);
                double cost = rt == ResourceType.WOOD ? 0.25 : 0.2;
                if (k.get(rt) < cost) {
                    long last = lastShortage.getOrDefault(bid, -100000L);
                    if (core.tick() - last > 2400) {
                        lastShortage.put(bid, core.tick());
                        core.bus().publish(core.tick(), EventType.BUILDING_DAMAGED, GameEvent.Severity.WARN, b.kingdomId, null,
                                "Faltam " + rt.display.toLowerCase() + " para consertar " + b.blueprint().displayName().toLowerCase() + ".");
                    }
                    break;
                }
                k.stock.merge(rt, -cost, Double::sum);
                world.set(target, exp);
                world.markBuilt(target);
                set.remove(target);
                a -= 1;
                port.animate(here.get(guard % here.size()).id, target, PhysicalPort.Anim.SWING, 0);
            }
            acc.put(bid, Math.min(a, 3));
            if (set.isEmpty()) {
                sim.repaired(b);
                release(bid);
            }
        }
    }

    private static boolean worn(BlockType t) {
        return t == Blocks.WEATHERED_PLANKS || t == Blocks.MOSSY_COBBLE || t == Blocks.CRACKED_STONE_BRICKS || t == Blocks.CRACKED_BRICKS;
    }

    private static ResourceType costType(BlockType b) {
        String id = b.id();
        if (id.contains("plank") || id.contains("log") || id.contains("fence") || id.contains("door") || id.contains("bed") || b == Blocks.CRAFTING
                || b == Blocks.CHEST || b == Blocks.BARREL || b == Blocks.BOOKSHELF || b == Blocks.LADDER) return ResourceType.WOOD;
        return ResourceType.STONE;
    }

    /** De baixo para cima, o mais perto do trabalhador primeiro. */
    private static Pos pick(Set<Pos> set, Pos from) {
        Pos best = null;
        for (Pos p : set) {
            if (best == null || p.y() < best.y() || p.y() == best.y() && p.distSq(from) < best.distSq(from)) best = p;
        }
        return best;
    }
}
