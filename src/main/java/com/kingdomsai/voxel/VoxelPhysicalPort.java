package com.kingdomsai.voxel;

import com.kingdomsai.core.common.Pos;
import com.kingdomsai.core.port.PhysicalPort;

import java.util.*;
import java.util.function.Consumer;

/** As mãos dos NPCs sobre o mundo voxel: o equivalente headless do McPhysicalPort. */
public final class VoxelPhysicalPort implements PhysicalPort {
    /** Efeito para o cliente desenhar (braço, rachadura, baú...). */
    public record Fx(UUID npc, Pos at, Anim anim, int stage) {}

    private final VoxelWorld world;
    private final LoadArea area;
    private final Map<String, Integer> given = new TreeMap<>();
    private final Map<Pos, Map<String, Integer>> dropped = new HashMap<>();
    private Consumer<Fx> fx = f -> {};
    private final List<Sighting> threats = new ArrayList<>();

    public VoxelPhysicalPort(VoxelWorld world, LoadArea area) {
        this.world = world;
        this.area = area;
    }

    public void onFx(Consumer<Fx> c) {
        fx = c;
    }

    /** O que o rei recebeu em mãos (entregas dos súditos). */
    public Map<String, Integer> given() {
        return given;
    }

    public Map<Pos, Map<String, Integer>> dropped() {
        return dropped;
    }

    /** Monstros que os súditos veem (a fauna hostil do mundo alimenta isto). */
    public List<Sighting> threats() {
        return threats;
    }

    @Override
    public boolean isLoaded(Pos p) {
        return p.y() >= 0 && p.y() < Chunk.HEIGHT && area.loaded(p.x(), p.z());
    }

    @Override
    public BlockInfo block(Pos p) {
        if (!isLoaded(p)) return BlockInfo.UNKNOWN;
        BlockType t = world.get(p);
        boolean near = false;
        if (!t.fluid()) {
            for (int[] d : new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}})
                if (world.get(p.x() + d[0], p.y() + d[1], p.z() + d[2]).fluid()) near = true;
        }
        boolean needs = t.needsTool();
        String drop = t.leaves() ? null : t.drop();
        return new BlockInfo(t.id(), t.air(), t.breakable(), t.hardness(), t.container(), near, t.fluid(), t.log(), t.leaves(),
                t.tool(), needs, drop);
    }

    @Override
    public Map<String, Integer> breakBlock(UUID npc, Pos p, String tool) {
        BlockType t = world.get(p);
        if (!t.breakable()) return Map.of();
        Map<String, Integer> out = new TreeMap<>();
        if (t.container()) {
            Map<String, Integer> inside = world.containers().remove(p);
            if (inside != null) inside.forEach((k, v) -> out.merge(k, v, Integer::sum));
        }
        int growth = world.growth(p);
        world.set(p, Blocks.AIR);
        if (t.leaves()) {
            if (Noise.hash(world.gen().seed() + 33, p.x(), p.y(), p.z()) < 0.25) out.merge("minecraft:oak_sapling", 1, Integer::sum);
            return out;
        }
        if (t == Blocks.WHEAT) {
            out.merge("minecraft:wheat_seeds", 1, Integer::sum);
            if (growth >= 100) out.merge("minecraft:wheat", 1, Integer::sum);
            return out;
        }
        String type = Items.toolType(tool);
        boolean right = t.tool() != null && t.tool().equals(type);
        if (t.needsTool() && !right) return out; // sem a ferramenta certa o bloco não dá nada (mas some)
        if (t.drop() != null) out.merge(t.drop(), 1, Integer::sum);
        return out;
    }

    @Override
    public boolean place(UUID npc, Pos p, String blockId) {
        BlockType cur = world.get(p);
        if (!(cur.air() || (!cur.solid() && !cur.fluid() && cur != Blocks.AIR))) return false;
        BlockType t = Blocks.of(blockId);
        if (t.air()) return false;
        BlockType below = world.get(p.offset(0, -1, 0));
        if (t == Blocks.WHEAT && below != Blocks.FARMLAND) return false;
        if (blockId.endsWith("_sapling") && below != Blocks.DIRT && below != Blocks.GRASS) return false;
        if (blockId.endsWith("torch") && !below.solid()) return false;
        if (!world.set(p, t)) return false;
        world.markBuilt(p);
        if (t == Blocks.WHEAT) world.setGrowth(p, 0);
        return true;
    }

    @Override
    public int growth(Pos p) {
        return world.growth(p);
    }

    @Override
    public boolean till(UUID npc, Pos p) {
        BlockType t = world.get(p);
        if (t != Blocks.DIRT && t != Blocks.GRASS && t != Blocks.DIRT_PATH) return false;
        if (!world.get(p.offset(0, 1, 0)).air()) return false;
        boolean ok = world.set(p, Blocks.FARMLAND);
        if (ok) world.markBuilt(p);
        return ok;
    }

    @Override
    public Map<String, Integer> container(Pos p) {
        return world.container(p);
    }

    @Override
    public int take(UUID npc, Pos chest, String item, int n) {
        Map<String, Integer> c = world.container(chest);
        if (c == null) return 0;
        int have = c.getOrDefault(item, 0), t = Math.min(have, n);
        if (have - t <= 0) c.remove(item);
        else c.put(item, have - t);
        return t;
    }

    @Override
    public Map<String, Integer> put(UUID npc, Pos chest, Map<String, Integer> items) {
        Map<String, Integer> c = world.container(chest);
        if (c == null) return items;
        items.forEach((k, v) -> c.merge(k, v, Integer::sum));
        return Map.of();
    }

    @Override
    public List<Recipe> recipes(String item) {
        return Items.recipes(item);
    }

    @Override
    public boolean itemExists(String item) {
        return Items.exists(item);
    }

    @Override
    public Pos findNear(Pos p, String blockId, int radius) {
        BlockType want = Blocks.of(blockId);
        boolean deep = blockId.endsWith("_ore");
        int y0 = deep ? 1 : Math.max(1, p.y() - 12), y1 = deep ? Chunk.HEIGHT - 1 : Math.min(Chunk.HEIGHT - 1, p.y() + 12);
        Pos best = null;
        double bestD = Double.MAX_VALUE;
        for (int x = p.x() - radius; x <= p.x() + radius; x++)
            for (int z = p.z() - radius; z <= p.z() + radius; z++) {
                if (!area.loaded(x, z)) continue;
                for (int y = y0; y <= y1; y++) {
                    if (world.get(x, y, z) != want) continue;
                    double d = (x - p.x()) * (double) (x - p.x()) + (y - p.y()) * (double) (y - p.y()) + (z - p.z()) * (double) (z - p.z());
                    if (d < bestD) {
                        bestD = d;
                        best = new Pos(x, y, z);
                    }
                }
            }
        return best;
    }

    @Override
    public boolean give(UUID npc, UUID player, Map<String, Integer> items) {
        items.forEach((k, v) -> given.merge(k, v, Integer::sum));
        return true;
    }

    @Override
    public void animate(UUID npc, Pos at, Anim anim, int stage) {
        fx.accept(new Fx(npc, at, anim, stage));
    }

    @Override
    public boolean mayBreak(UUID player, Pos p) {
        return world.inBounds(p.x(), p.z()) && p.y() > 0;
    }

    @Override
    public void drop(Pos p, Map<String, Integer> items) {
        Map<String, Integer> at = dropped.computeIfAbsent(p, k -> new TreeMap<>());
        items.forEach((k, v) -> at.merge(k, v, Integer::sum));
    }

    @Override
    public boolean chunkForced(int chunkX, int chunkZ) {
        return area.isForced(chunkX, chunkZ);
    }

    @Override
    public void forceChunk(int chunkX, int chunkZ, boolean on) {
        area.force(chunkX, chunkZ, on);
    }

    @Override
    public List<Sighting> threatsNear(Pos p, int radius) {
        List<Sighting> out = new ArrayList<>();
        for (Sighting s : threats) if (s.pos().distXZ(p) <= radius) out.add(s);
        return out;
    }
}
