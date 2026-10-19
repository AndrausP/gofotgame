package com.kingdomsai.core;

import com.kingdomsai.core.cli.CommandService;
import com.kingdomsai.core.common.Pos;
import com.kingdomsai.core.construction.Building;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.kingdom.ResourceType;
import com.kingdomsai.core.npc.Npc;
import com.kingdomsai.core.npc.Profession;
import com.kingdomsai.core.skill.PhysicalJob;
import com.kingdomsai.voxel.*;

import java.util.*;

/** O mundo voxel em Java puro: geração, obras, corpos de NPC e ordens físicas sem Minecraft. */
public final class VoxelSelfTest {
    private static int passed, failed;

    public static void main(String[] args) throws Exception {
        int[] r = run();
        System.out.println("\n" + r[0] + " passaram, " + r[1] + " falharam.");
        System.exit(r[1] == 0 ? 0 : 1);
    }

    public static int[] run() {
        passed = failed = 0;
        System.out.println("\n# Mundo voxel");
        // ---- geração
        VoxelWorld a = new VoxelWorld(42), b = new VoxelWorld(42), c = new VoxelWorld(43);
        boolean same = true, differs = false;
        for (int cx = -3; cx <= 3 && same; cx++)
            for (int cz = -3; cz <= 3; cz++) {
                if (!Arrays.equals(a.chunk(cx, cz).blocks, b.chunk(cx, cz).blocks)) same = false;
                if (!Arrays.equals(a.chunk(cx, cz).blocks, c.chunk(cx, cz).blocks)) differs = true;
            }
        check("mesma semente gera o mesmo mundo", same);
        check("semente diferente gera outro mundo", differs);
        int g = a.groundY(0, 0);
        check("origem é terra firme plana (chão em " + g + ")", g >= 64 && g <= 80 && a.get(0, g - 1, 0).natural());
        int logs = 0, ores = 0, water = 0, leaves = 0;
        for (int x = -160; x < 160; x += 1)
            for (int z = -160; z < 160; z += 2) {
                int top = a.topSolid(x, z);
                BlockType t = a.get(x, top, z);
                if (t.leaves()) leaves++;
                if (t.fluid()) water++;
                for (int y = top; y > top - 8 && y > 0; y--) if (a.get(x, y, z).log()) logs++;
            }
        for (int x = -48; x < 48; x++)
            for (int z = -48; z < 48; z++)
                for (int y = 1; y < 60; y++) if (a.get(x, y, z).id().endsWith("_ore")) ores++;
        check("há florestas (" + logs + " troncos, " + leaves + " folhas)", logs > 20 && leaves > 100);
        check("há minérios no subsolo (" + ores + ")", ores > 30);
        check("há água (" + water + ")", water > 100);
        check("troncos não atravessam bordas de chunk (árvore inteira)", treesWhole(a));

        // ---- edição e registro
        VoxelWorld w = new VoxelWorld(1);
        w.drainChanges();
        check("set muda o bloco", w.set(5, 90, 5, Blocks.COBBLE) && w.get(5, 90, 5) == Blocks.COBBLE);
        check("set igual não registra", !w.set(5, 90, 5, Blocks.COBBLE));
        check("alteração fica na fila para a rede", w.drainChanges().size() >= 1 && w.drainChanges().isEmpty());
        w.set(6, 90, 5, Blocks.CHEST);
        w.container(new Pos(6, 90, 5)).put("minecraft:bread", 3);
        w.set(6, 90, 5, Blocks.AIR);
        check("trocar o bloco apaga o conteúdo do baú", w.containers().get(new Pos(6, 90, 5)) == null);

        // ---- Core habitando o mundo
        VoxelHost host = new VoxelHost(42);
        UUID player = UUID.randomUUID();
        CommandService cli = new CommandService(host.core);
        List<String> async = new ArrayList<>();
        cli.setNotifier((p, lines) -> async.addAll(lines));
        Pos king = new Pos(0, g, 0);
        host.core.updatePlayerPos(player, king);
        host.area.setViewer(player, king);
        cli.execute(player, "Andraus", king, "found Reino de Teste");
        Kingdom k = host.core.kingdomOfPlayer(player);
        k.civic = "off";
        check("reino fundado no mundo voxel", k != null && host.core.population(k.id) == 10);
        for (int i = 0; i < 20 * 40; i++) host.step();
        int bodies = 0;
        for (Npc n : host.core.citizens(k.id)) if (n.materialized) bodies++;
        check("súditos ganham corpo perto do rei (" + bodies + "/10)", bodies >= 8);
        int placedBlocks = 0, onWorld = 0;
        for (Building bd : host.core.buildings(k.id)) {
            if (bd.origin == null || bd.origin.y() == Integer.MIN_VALUE) continue;
            placedBlocks += bd.placed;
            for (int i = 0; i < Math.min(bd.placed, bd.blueprint().placements().size()); i++) {
                var p = bd.blueprint().placements().get(i);
                if (p.material() != com.kingdomsai.core.construction.Material.AIR
                        && !host.world.get(bd.origin.x() + p.x(), bd.origin.y() + p.y(), bd.origin.z() + p.z()).air()) onWorld++;
            }
        }
        check("obras aparecem como blocos no mundo (" + onWorld + " blocos, " + placedBlocks + " passos)", onWorld > 20);

        // ---- chamado: todos vêm andando pelo caminho
        Pos far = new Pos(40, host.world.groundY(40, 40), 40);
        host.core.updatePlayerPos(player, far);
        host.area.setViewer(player, far);
        for (Npc n : host.core.citizens(k.id)) cli.execute(player, "Andraus", far, "call " + n.name);
        double before = avg(host, k, far);
        for (int i = 0; i < 20 * 40; i++) host.step();
        double after = avg(host, k, far);
        System.out.println("   distância média ao chamado: " + (int) before + " → " + (int) after);
        check("o povo anda até quem chamou", after < 12 && after < before);

        // ---- ordem física: derrubar árvores de verdade
        Pos tree = nearestLog(host.world, new Pos(0, g, 0), 70);
        check("há árvore perto do reino", tree != null);
        if (tree != null) {
            Pos base = new Pos(tree.x(), host.world.groundY(tree.x(), tree.z()), tree.z());
            host.core.updatePlayerPos(player, base);
            host.area.setViewer(player, base);
            int logsBefore = countLogs(host.world, base, 8);
            k.stock.put(ResourceType.WOOD, 0.0);
            Npc lumber = host.core.citizens(k.id).stream().filter(n -> n.profession == Profession.LUMBERJACK).findFirst()
                    .orElse(host.core.citizens(k.id).get(0));
            cli.execute(player, "Andraus", base, "npc talk " + lumber.name + " Limpe as árvores da região.");
            PhysicalJob job = lumber.jobId == null ? null : host.core.state().jobs.get(lumber.jobId);
            check("a ordem de limpar árvores virou tarefa física", job != null);
            for (int s = 0; s < 900 && job != null && job.status.live(); s++)
                for (int i = 0; i < 20; i++) host.step();
            int logsAfter = countLogs(host.world, base, 8);
            System.out.println("   toras ao redor: " + logsBefore + " → " + logsAfter + ", estado: " + (job == null ? "-" : job.status + " " + job.reason));
            check("árvores foram derrubadas no mundo", logsAfter < logsBefore);
            check("a ordem terminou", job != null && !job.status.live());
        }
        // ---- reinos rivais nascem em terra firme escolhida pela geração
        VoxelHost rv = new VoxelHost(42);
        UUID p2 = UUID.randomUUID();
        CommandService cli2 = new CommandService(rv.core);
        cli2.setNotifier((p, l) -> {});
        rv.core.updatePlayerPos(p2, king);
        rv.area.setViewer(p2, king);
        cli2.execute(p2, "Andraus", king, "found Reino Vizinho");
        int rivals = 0;
        boolean allDry = true;
        for (Kingdom o : rv.core.state().kingdoms.values()) {
            if (o.isPlayerKingdom()) continue;
            rivals++;
            int h = rv.world.gen().height(o.center.x(), o.center.z());
            if (h <= WorldGen.SEA + 1 || h > 90) allDry = false;
            System.out.println("   rival " + o.name + " em (" + o.center.x() + ", " + o.center.z() + "), chão " + h);
        }
        check("há reinos rivais e todos nasceram em terra firme (" + rivals + ")", rivals >= 1 && allDry);
        return new int[]{passed, failed};
    }

    private static boolean treesWhole(VoxelWorld w) {
        // toda folha tem tronco do mesmo tipo a até 4 blocos de distância (árvore não foi cortada pela borda)
        for (int x = -64; x < 64; x++)
            for (int z = -64; z < 64; z++) {
                int top = w.topSolid(x, z);
                if (!w.get(x, top, z).leaves()) continue;
                boolean trunk = false;
                for (int dx = -3; dx <= 3 && !trunk; dx++)
                    for (int dz = -3; dz <= 3 && !trunk; dz++)
                        for (int y = top - 6; y <= top; y++) if (w.get(x + dx, y, z + dz).log()) trunk = true;
                if (!trunk) return false;
            }
        return true;
    }

    private static Pos nearestLog(VoxelWorld w, Pos from, int radius) {
        Pos best = null;
        double bd = 1e18;
        for (int x = from.x() - radius; x <= from.x() + radius; x++)
            for (int z = from.z() - radius; z <= from.z() + radius; z++) {
                int top = w.topSolid(x, z);
                for (int y = top; y > top - 8 && y > 0; y--)
                    if (w.get(x, y, z).log()) {
                        double d = Math.hypot(x - from.x(), z - from.z());
                        if (d < bd && d > 12) {
                            bd = d;
                            best = new Pos(x, y, z);
                        }
                        break;
                    }
            }
        return best;
    }

    private static int countLogs(VoxelWorld w, Pos c, int r) {
        int n = 0;
        for (int x = c.x() - r; x <= c.x() + r; x++)
            for (int z = c.z() - r; z <= c.z() + r; z++)
                for (int y = Math.max(1, c.y() - 4); y < Math.min(Chunk.HEIGHT, c.y() + 14); y++) if (w.get(x, y, z).log()) n++;
        return n;
    }

    private static double avg(VoxelHost h, Kingdom k, Pos at) {
        double sum = 0;
        int n = 0;
        for (Npc npc : h.core.citizens(k.id)) {
            if (npc.pos == null) continue;
            sum += Math.hypot(npc.pos.x() - at.x(), npc.pos.z() - at.z());
            n++;
        }
        return n == 0 ? 1e9 : sum / n;
    }

    private static void check(String name, boolean ok) {
        if (ok) passed++;
        else failed++;
        System.out.println((ok ? "  [OK]   " : "  [FAIL] ") + name);
    }
}
