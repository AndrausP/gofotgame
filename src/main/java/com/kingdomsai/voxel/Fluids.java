package com.kingdomsai.voxel;

import com.kingdomsai.core.KingdomsCore;
import com.kingdomsai.core.common.Pos;
import com.kingdomsai.core.event.EventType;
import com.kingdomsai.core.event.GameEvent;

import java.util.*;

/**
 * Água que escorre: fontes (a água do mundo e a colocada) enchem o que está aberto ao lado e embaixo, com nível que
 * cai 1 a cada bloco de distância; água sem fonte recua. Quebrar um dique, uma explosão ou uma cratera alagam sozinhos.
 * Só células ativas custam algo: o oceano parado não gasta tempo.
 */
public final class Fluids {
    public static final int SOURCE = 8;
    public static final int MAX_FLOWING = 30000;
    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private final VoxelWorld world;
    private final KingdomsCore core;
    /** Nível 1..7 das células que são água corrente (ausente = fonte, nível 8). */
    private final Map<Pos, Integer> level = new HashMap<>();
    private final LinkedHashSet<Pos> active = new LinkedHashSet<>();
    private boolean floodReported;
    private long lastFlood;

    public Fluids(KingdomsCore core, VoxelWorld world) {
        this.core = core;
        this.world = world;
        world.changeListener = this::onChange;
    }

    public int flowing() {
        return level.size();
    }

    public int activeCells() {
        return active.size();
    }

    public int levelAt(Pos p) {
        if (!world.get(p).fluid()) return 0;
        return level.getOrDefault(p, SOURCE);
    }

    /** Um bloco mudou: quem estava ao redor precisa olhar de novo. */
    private void onChange(Pos p) {
        boolean near = world.get(p).fluid();
        if (!near)
            for (int[] d : new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}}) {
                if (world.get(p.x() + d[0], p.y() + d[1], p.z() + d[2]).fluid()) {
                    near = true;
                    break;
                }
            }
        if (!world.get(p).fluid()) level.remove(p);
        if (near) {
            active.add(p);
            for (int[] d : new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}}) active.add(p.offset(d[0], d[1], d[2]));
        }
    }

    /** Põe uma fonte (um balde, um rompimento, uma ferramenta do rei). */
    public void source(Pos p) {
        world.set(p, Blocks.WATER);
        level.remove(p);
        active.add(p);
    }

    public void tick() {
        if (active.isEmpty()) return;
        int budget = 600;
        Iterator<Pos> it = active.iterator();
        List<Pos> batch = new ArrayList<>();
        while (it.hasNext() && budget-- > 0) {
            batch.add(it.next());
            it.remove();
        }
        for (Pos p : batch) update(p);
        long t = core.tick();
        if (level.size() > 250 && !floodReported && t - lastFlood > 20 * 60) {
            floodReported = true;
            lastFlood = t;
            core.bus().publish(t, EventType.FLOOD_STARTED, GameEvent.Severity.WARN, null, null,
                    "Uma inundação se espalha (" + level.size() + " blocos de água corrente).", Map.of("cells", String.valueOf(level.size())));
        }
        if (level.size() < 60) floodReported = false;
    }

    private void update(Pos p) {
        BlockType here = world.get(p);
        if (here.fluid()) {
            int l = level.getOrDefault(p, SOURCE);
            if (l < SOURCE && !supported(p, l)) {
                world.set(p, Blocks.AIR);
                level.remove(p);
                reactivateAround(p);
                return;
            }
            Pos below = p.offset(0, -1, 0);
            if (p.y() > 1 && flowable(world.get(below))) {
                fill(below, SOURCE - 1);
            } else if (l > 1 && (p.y() <= 1 || !flowable(world.get(below)))) {
                int nl = l - 1;
                for (int[] s : SIDES) {
                    Pos n = new Pos(p.x() + s[0], p.y(), p.z() + s[1]);
                    if (flowable(world.get(n))) fill(n, nl);
                    else if (world.get(n).fluid() && level.containsKey(n) && level.get(n) < nl) {
                        level.put(n, nl);
                        active.add(n);
                    }
                }
            }
        } else if (flowable(here)) {
            // célula vazia ao lado de água: o vizinho dela já a enche no próprio update; aqui só garante o alcance
            for (int[] s : SIDES) {
                Pos n = new Pos(p.x() + s[0], p.y(), p.z() + s[1]);
                if (world.get(n).fluid()) active.add(n);
            }
            Pos above = p.offset(0, 1, 0);
            if (world.get(above).fluid()) active.add(above);
        }
    }

    private boolean flowable(BlockType t) {
        return t.air() || !t.solid() && !t.fluid();
    }

    private void fill(Pos p, int l) {
        if (level.size() >= MAX_FLOWING) return;
        world.keepIntegrity = true;
        boolean ok = world.set(p, Blocks.WATER);
        world.keepIntegrity = false;
        if (ok) {
            level.put(p, Math.max(1, l));
            active.add(p);
        }
    }

    /** Água corrente se mantém com água acima, ou ao lado com nível maior. */
    private boolean supported(Pos p, int l) {
        if (world.get(p.offset(0, 1, 0)).fluid()) return true;
        for (int[] s : SIDES) {
            Pos n = new Pos(p.x() + s[0], p.y(), p.z() + s[1]);
            if (world.get(n).fluid() && level.getOrDefault(n, SOURCE) > l) return true;
        }
        return false;
    }

    private void reactivateAround(Pos p) {
        for (int[] d : new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}}) active.add(p.offset(d[0], d[1], d[2]));
    }
}
