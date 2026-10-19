package com.kingdomsai.live;

import com.kingdomsai.core.CoreConfig;
import com.kingdomsai.core.WorldState;
import com.kingdomsai.core.cli.CommandService;
import com.kingdomsai.core.common.Pos;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.npc.Npc;
import com.kingdomsai.core.npc.Profession;
import com.kingdomsai.voxel.VoxelHost;

import java.util.UUID;

/** Medição manual: custo por tick com centenas de súditos andando de verdade. Uso: PerfCheck [súditos] */
public final class PerfCheck {
    public static void main(String[] args) {
        System.setProperty("stdout.encoding", "UTF-8");
        int extra = args.length > 0 ? Integer.parseInt(args[0]) : 300;
        CoreConfig cfg = new CoreConfig();
        cfg.rivalKingdoms = 0;
        cfg.aiKingdomsEnabled = false;
        VoxelHost h = new VoxelHost(42, new WorldState(), cfg);
        UUID player = UUID.randomUUID();
        CommandService cli = new CommandService(h.core);
        cli.setNotifier((p, l) -> {});
        int g = h.world.groundY(0, 0);
        Pos king = new Pos(0, g, 0);
        h.core.updatePlayerPos(player, king);
        h.area.setViewer(player, king);
        cli.execute(player, "Andraus", king, "found Reino Grande");
        Kingdom k = h.core.kingdomOfPlayer(player);
        k.civic = "off";
        Profession[] ps = Profession.values();
        for (int i = 0; i < extra; i++) {
            Pos at = new Pos((i % 20) * 3 - 30, g, (i / 20) * 3 - 20);
            Npc n = h.core.createNpc(k, ps[i % ps.length], at);
            n.pos = new Pos(at.x(), h.world.groundY(at.x(), at.z()), at.z());
        }
        System.out.println("súditos: " + h.core.citizens(k.id).size());
        long worst = 0, total = 0;
        int ticks = 20 * 120;
        for (int i = 0; i < ticks; i++) {
            long t0 = System.nanoTime();
            h.step();
            long dt = System.nanoTime() - t0;
            total += dt;
            worst = Math.max(worst, dt);
            if (i % 600 == 599) System.out.printf("  %4d s: média %.2f ms/tick, pior %.1f ms, corpos %d%n", (i + 1) / 20, total / 1e6 / (i + 1), worst / 1e6, h.mover.active());
        }
        System.out.printf("média %.2f ms/tick (orçamento 50 ms), pior %.1f ms, corpos em cena %d, chunks %d%n", total / 1e6 / ticks, worst / 1e6, h.mover.active(), h.world.loadedChunks().size());
    }
}
