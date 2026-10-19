package com.kingdomsai.core;

import com.kingdomsai.core.cli.CommandService;
import com.kingdomsai.core.common.Pos;
import com.kingdomsai.core.construction.Building;
import com.kingdomsai.core.event.EventType;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.kingdom.ResourceType;
import com.kingdomsai.voxel.*;

import java.util.*;

/** O mundo se mexe sozinho: fogo, explosão, desabamento, desgaste, reparo, ruína e mato. */
public final class WorldSimSelfTest {
    private static int passed, failed;

    public static void main(String[] args) throws Exception {
        int[] r = run();
        System.out.println("\n" + r[0] + " passaram, " + r[1] + " falharam.");
        System.exit(r[1] == 0 ? 0 : 1);
    }

    private static VoxelHost host(long seed) {
        CoreConfig cfg = new CoreConfig();
        cfg.rivalKingdoms = 0;
        cfg.aiKingdomsEnabled = false;
        WorldState s = new WorldState();
        s.seed = 5;
        return new VoxelHost(seed, s, cfg);
    }

    private static void run(VoxelHost h, int ticks) {
        for (int i = 0; i < ticks; i++) h.step();
    }

    /** Caixa oca de blocos "construídos" (como se fosse uma casa) em cima do chão. */
    private static void box(VoxelHost h, Pos o, int sx, int sy, int sz, BlockType b) {
        for (int x = 0; x < sx; x++)
            for (int y = 0; y < sy; y++)
                for (int z = 0; z < sz; z++) {
                    boolean wall = x == 0 || z == 0 || x == sx - 1 || z == sz - 1 || y == sy - 1;
                    if (!wall) continue;
                    Pos p = o.offset(x, y, z);
                    h.world.set(p, b);
                    h.world.markBuilt(p);
                }
    }

    private static int totalDamage(VoxelHost h) {
        int n = 0;
        for (var e : h.sim.damage().entrySet()) {
            Building b = h.core.state().buildings.get(e.getKey());
            if (b != null && b.status == Building.Status.COMPLETE) n += e.getValue().size();
        }
        return n;
    }

    private static Pos at(VoxelHost h, int x, int z) {
        return new Pos(x, h.world.groundY(x, z), z);
    }

    private static int count(VoxelHost h, Pos o, int sx, int sy, int sz, BlockType b) {
        int n = 0;
        for (int x = 0; x < sx; x++) for (int y = 0; y < sy; y++) for (int z = 0; z < sz; z++) if (h.world.get(o.offset(x, y, z)) == b) n++;
        return n;
    }

    public static int[] run() {
        passed = failed = 0;
        System.out.println("\n# Mundo que se mexe");
        VoxelHost h = host(42);
        int g = h.world.groundY(0, 0);
        Pos view = new Pos(0, g, 0);
        h.area.setViewer(UUID.randomUUID(), view);
        h.sim.setWeather(WorldSim.Weather.CLEAR, 10_000_000);

        // ---- fogo
        Pos o = at(h, 10, 10);
        box(h, o, 6, 4, 6, Blocks.OAK_PLANKS);
        int before = count(h, o, 6, 4, 6, Blocks.OAK_PLANKS);
        check("ignite acende fogo ao lado de madeira", h.sim.ignite(o.offset(1, 1, -1)));
        run(h, 20 * 25);
        int after = count(h, o, 6, 4, 6, Blocks.OAK_PLANKS);
        check("o fogo se espalha e consome a madeira (" + before + " → " + after + ")", after < before * 0.8);
        run(h, 20 * 30);
        check("o fogo acaba sozinho quando não há mais o que queimar", h.sim.fires().isEmpty());
        // chuva apaga
        Pos o2 = at(h, 30, 10);
        box(h, o2, 6, 4, 6, Blocks.OAK_PLANKS);
        h.sim.ignite(o2.offset(1, 1, -1));
        h.sim.setWeather(WorldSim.Weather.STORM, 10_000_000);
        run(h, 20 * 8);
        int wet = count(h, o2, 6, 4, 6, Blocks.OAK_PLANKS);
        check("tempestade apaga e contém o fogo (" + wet + " tábuas)", h.sim.fires().isEmpty() && wet > 35);
        h.sim.setWeather(WorldSim.Weather.CLEAR, 10_000_000);

        // ---- desabamento por falta de apoio
        Pos tw = at(h, 50, 30);
        for (int y = 0; y <= 6; y++) {
            h.world.set(tw.offset(0, y, 0), Blocks.COBBLE);
            h.world.markBuilt(tw.offset(0, y, 0));
        }
        h.world.set(tw.offset(0, 0, 0), Blocks.AIR); // tira a base: a torre fica no ar
        h.world.set(tw.offset(0, -1, 0), Blocks.AIR);
        h.sim.explode(tw.offset(0, -1, 0), 1, 0.1);
        run(h, 40);
        check("torre sem base desaba", h.world.get(tw.offset(0, 3, 0)).air() || h.world.get(tw.offset(0, 3, 0)) == Blocks.RUBBLE);
        Pos wall = at(h, 60, 30);
        for (int y = 0; y < 4; y++) {
            h.world.set(wall.offset(0, y, 0), Blocks.COBBLE);
            h.world.markBuilt(wall.offset(0, y, 0));
        }
        h.world.set(wall.offset(0, 3, 0), Blocks.AIR);
        h.sim.explode(wall.offset(5, 0, 5), 1, 0.01);
        run(h, 40);
        check("parede ligada ao chão continua de pé", h.world.get(wall.offset(0, 1, 0)) == Blocks.COBBLE);

        // ---- explosão
        Pos ex = at(h, 80, 40).offset(0, -6, 0);
        for (int x = -5; x <= 5; x++) for (int y = -5; y <= 5; y++) for (int z = -5; z <= 5; z++) h.world.set(ex.offset(x, y, z), Blocks.STONE);
        int destroyed = h.sim.explode(ex, 4, 1.0);
        check("explosão abre uma cratera (" + destroyed + " blocos)", destroyed > 60 && h.world.get(ex).air());
        check("longe do raio fica intacto", h.world.get(ex.offset(5, 5, 5)) == Blocks.STONE);

        // ---- inundação: um dique rompe, a água escorre; sem fonte, recua
        Pos dam = at(h, 100, 20).offset(0, -1, 0);
        int base = dam.y();
        for (int x = -1; x <= 7; x++) for (int z = -1; z <= 7; z++) for (int y = 0; y <= 4; y++) h.world.set(dam.offset(x, y, z), Blocks.AIR);
        for (int x = -1; x <= 7; x++) for (int z = -1; z <= 7; z++) h.world.set(dam.offset(x, 0, z), Blocks.STONE);
        for (int x = 0; x <= 6; x++) for (int z = 0; z <= 6; z++) for (int y = 1; y <= 3; y++) {
            boolean wallBlock = x == 0 || z == 0 || x == 6 || z == 6;
            h.world.set(dam.offset(x, y, z), wallBlock ? Blocks.COBBLE : Blocks.WATER);
        }
        for (int x = -1; x <= 7; x++) for (int z = -1; z <= 7; z++) if (x == -1 || z == -1 || x == 7 || z == 7) h.world.set(dam.offset(x, 0, z), Blocks.STONE);
        run(h, 100);
        int leak0 = h.fluids.flowing();
        check("o dique segura a água (" + leak0 + " blocos correndo)", leak0 == 0);
        h.world.set(dam.offset(0, 1, 3), Blocks.AIR); // rompe o dique pelo pé
        run(h, 20 * 12);
        int flowing = h.fluids.flowing();
        int out = 0;
        for (int x = -6; x < 0; x++) for (int z = 0; z <= 7; z++) for (int y = 0; y <= 3; y++) if (h.world.get(dam.offset(x, y, z)).fluid()) out++;
        System.out.println("   água corrente: " + flowing + ", fora do tanque: " + out + ", eventos de enchente: " + h.fluids.activeCells());
        check("a água escorre pela brecha e se espalha no chão", flowing > 15 && out > 8);
        check("a inundação tem teto (não engole o mundo)", flowing < Fluids.MAX_FLOWING);
        for (int x = 1; x <= 5; x++) for (int z = 1; z <= 5; z++) for (int y = 1; y <= 3; y++) h.world.set(dam.offset(x, y, z), Blocks.AIR); // some a fonte
        run(h, 20 * 40);
        int receded = h.fluids.flowing();
        System.out.println("   água corrente depois de secar a fonte: " + receded);
        check("sem fonte a água recua", receded < flowing / 3);

        // ---- desgaste, reparo, ruína num reino de verdade
        VoxelHost k1 = host(42);
        UUID player = UUID.randomUUID();
        CommandService cli = new CommandService(k1.core);
        cli.setNotifier((p, l) -> {});
        k1.core.updatePlayerPos(player, view);
        k1.area.setViewer(player, view);
        k1.sim.setWeather(WorldSim.Weather.CLEAR, 10_000_000);
        cli.execute(player, "Andraus", view, "found Reino de Teste");
        Kingdom k = k1.core.kingdomOfPlayer(player);
        k.civic = "off";
        k.stock.put(ResourceType.WOOD, 5000.0);
        k.stock.put(ResourceType.STONE, 5000.0);
        k.stock.put(ResourceType.FOOD, 5000.0);
        cli.execute(player, "Andraus", view, "build casa 2");
        run(k1, 20 * 60 * 3);
        Building house = null;
        for (Building b : k1.core.buildings(k.id)) if (b.status == Building.Status.COMPLETE && b.blueprint().housing() > 0) house = b;
        if (house == null) for (Building b : k1.core.buildings(k.id)) if (b.status == Building.Status.COMPLETE) house = b;
        check("há uma obra concluída para envelhecer", house != null);
        if (house != null) {
            Building hb = house;
            int blocks = 0;
            for (var p : hb.blueprint().placements())
                if (p.material() != com.kingdomsai.core.construction.Material.AIR && k1.world.isBuilt(new Pos(hb.origin.x() + p.x(), hb.origin.y() + p.y(), hb.origin.z() + p.z()))) blocks++;
            check("blocos da obra entram na conta de desgaste (" + blocks + ")", blocks > 30);
            List<EventType> seen = new ArrayList<>();
            k1.core.bus().subscribeAll(e -> seen.add(e.type()));
            k.stock.put(ResourceType.WOOD, 0.0);
            k.stock.put(ResourceType.STONE, 0.0);
            k1.sim.decayScale = 150;
            run(k1, 20 * 40);
            int wornCount = totalDamage(k1);
            System.out.println("   danos registrados: " + wornCount + ", eventos: " + new HashSet<>(seen));
            check("sem material a obra se deteriora (" + wornCount + " blocos)", wornCount > 10 && seen.contains(EventType.BUILDING_DAMAGED));
            k1.sim.decayScale = 0;
            k.stock.put(ResourceType.WOOD, 5000.0);
            k.stock.put(ResourceType.STONE, 5000.0);
            for (int sec = 0; sec < 400 && totalDamage(k1) > 0; sec++) run(k1, 20);
            int left = totalDamage(k1);
            System.out.println("   danos após o reparo: " + left + ", status: " + hb.status);
            if (left > 0) for (var e : k1.sim.damage().entrySet()) {
                Building bb = k1.core.state().buildings.get(e.getKey());
                System.out.println("     obra " + (bb == null ? "?" : bb.blueprintId + " " + bb.status) + " restam " + e.getValue().size() + " crew=" + k1.repair.crew(e.getKey()).size());
                int c = 0;
                for (Pos q : e.getValue()) if (c++ < 4) System.out.println("       " + q + " atual=" + k1.world.get(q).id() + " esperado=" + (bb == null ? "?" : String.valueOf(k1.sim.expected(bb, q) == null ? null : k1.sim.expected(bb, q).id())) + " built=" + k1.world.isBuilt(q));
            }
            check("com material os súditos consertam tudo", left == 0 && hb.status == Building.Status.COMPLETE && seen.contains(EventType.BUILDING_REPAIRED));

            // sem material e sem cuidado: vira ruína, depois mato
            k.stock.put(ResourceType.WOOD, 0.0);
            k.stock.put(ResourceType.STONE, 0.0);
            k1.sim.decayScale = 60000;
            run(k1, 20 * 60 * 2);
            check("sem reparo a obra vira ruína (" + hb.status + ")", hb.status == Building.Status.ABANDONED && seen.contains(EventType.BUILDING_RUINED));
            int rubble = count(k1, hb.origin, hb.blueprint().sizeX(), hb.blueprint().sizeY(), hb.blueprint().sizeZ(), Blocks.RUBBLE)
                    + count(k1, hb.origin, hb.blueprint().sizeX(), hb.blueprint().sizeY(), hb.blueprint().sizeZ(), Blocks.MOSS);
            k1.sim.decayScale = 400000;
            run(k1, 20 * 60 * 3);
            int standing = 0;
            for (var p : hb.blueprint().placements()) {
                BlockType t = k1.world.get(hb.origin.x() + p.x(), hb.origin.y() + p.y(), hb.origin.z() + p.z());
                if (t.solid() && !t.natural()) standing++;
            }
            System.out.println("   entulho/musgo no auge: " + rubble + ", blocos de obra ainda de pé: " + standing);
            check("com o tempo as ruínas voltam à natureza", standing < 10);
        }
        return new int[]{passed, failed};
    }

    private static void check(String name, boolean ok) {
        if (ok) passed++;
        else failed++;
        System.out.println((ok ? "  [OK]   " : "  [FAIL] ") + name);
    }
}
