package com.kingdomsai.voxel;

import com.kingdomsai.core.CoreConfig;
import com.kingdomsai.core.KingdomsCore;
import com.kingdomsai.core.WorldState;
import com.kingdomsai.core.common.Pos;
import com.kingdomsai.core.llm.LlmConfig;

import java.util.Map;

/**
 * Tudo o que o Minecraft dava ao Core, em Java puro: mundo voxel, portas, obras e corpos de NPC.
 * {@link #step()} avança o Core e o mundo um tick (20 por segundo).
 */
public final class VoxelHost {
    public final VoxelWorld world;
    public final LoadArea area;
    public final KingdomsCore core;
    public final VoxelPhysicalPort physical;
    public final VoxelBuilder builder;
    public final NpcMover mover;
    public final WorldSim sim;
    public final RepairCrew repair;
    public final Fluids fluids;

    public VoxelHost(long seed) {
        this(seed, new WorldState(), new CoreConfig());
    }

    public VoxelHost(long seed, WorldState state, CoreConfig cfg) {
        cfg.hostName = "godot";
        world = new VoxelWorld(seed);
        area = new LoadArea(world);
        core = new KingdomsCore(state, cfg);
        core.setWorld(new VoxelWorldPort(world, area, core::tick));
        physical = new VoxelPhysicalPort(world, area);
        core.setPhysical(physical);
        LlmConfig llm = new LlmConfig();
        llm.provider = "mock";
        core.llm().configure(llm);
        builder = new VoxelBuilder(core, world, area, physical);
        mover = new NpcMover(core, world, area, physical);
        sim = new WorldSim(core, world, area, mover);
        repair = new RepairCrew(core, world, area, mover, physical, sim);
        fluids = new Fluids(core, world);
    }

    /** Um tick: Core, depois o mundo reage (obras materializam, corpos andam, plantas crescem). */
    public void step() {
        core.step();
        builder.tick();
        mover.tick();
        sim.tick();
        repair.tick();
        if (core.tick() % 5 == 0) fluids.tick();
        if (core.tick() % 20 == 0) growCrops();
    }

    private void growCrops() {
        for (Map.Entry<Pos, Integer> e : world.crops().entrySet()) {
            if (e.getValue() < 100 && Noise.hash(core.tick(), e.getKey().x(), e.getKey().y(), e.getKey().z()) < 0.12)
                e.setValue(Math.min(100, e.getValue() + 1));
        }
    }
}
