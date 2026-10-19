package com.kingdomsai.live;

import com.kingdomsai.core.CoreConfig;
import com.kingdomsai.core.WorldState;
import com.kingdomsai.core.cli.CommandService;
import com.kingdomsai.core.common.Pos;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.llm.LlmConfig;
import com.kingdomsai.core.npc.Npc;
import com.kingdomsai.voxel.VoxelHost;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Verificação MANUAL com um servidor Ollama de verdade (não roda na suíte): mede latência e confere que a LLM
 * consegue conversar e escrever leis. Uso: LlmLiveCheck [modelo-conselho] [modelo-súditos]
 */
public final class LlmLiveCheck {
    public static void main(String[] args) throws Exception {
        System.setProperty("stdout.encoding", "UTF-8");
        String council = args.length > 0 ? args[0] : "llama3.1:8b";
        String small = args.length > 1 ? args[1] : "gemma3:1b";
        CoreConfig cfg = new CoreConfig();
        cfg.rivalKingdoms = 0;
        cfg.aiKingdomsEnabled = false;
        VoxelHost h = new VoxelHost(42, new WorldState(), cfg);
        LlmConfig llm = new LlmConfig();
        llm.provider = "ollama";
        llm.model = council;
        llm.npcModel = small;
        llm.timeoutMs = 90000;
        h.core.llm().configure(llm);
        UUID player = UUID.randomUUID();
        CommandService cli = new CommandService(h.core);
        List<String> async = new ArrayList<>();
        cli.setNotifier((p, l) -> {
            synchronized (async) {
                async.addAll(l);
            }
        });
        int g = h.world.groundY(0, 0);
        Pos king = new Pos(0, g, 0);
        h.core.updatePlayerPos(player, king);
        h.area.setViewer(player, king);
        cli.execute(player, "Andraus", king, "found Reino do Teste Vivo");
        Kingdom k = h.core.kingdomOfPlayer(player);
        for (int i = 0; i < 20 * 30; i++) h.step();
        System.out.println(h.core.llm().status());
        System.out.println("teste: " + h.core.llm().test().get());
        Npc n = h.core.citizens(k.id).get(0);
        for (String q : new String[]{"Como você está hoje?", "O que você acha de uma lei que proíbe camas vermelhas?"}) {
            async.clear();
            long t0 = System.currentTimeMillis();
            cli.execute(player, "Andraus", king, "npc talk " + n.name + " " + q);
            waitFor(h, async, 90);
            System.out.println("[" + (System.currentTimeMillis() - t0) + " ms] " + q + " → " + async);
        }
        async.clear();
        long t0 = System.currentTimeMillis();
        cli.execute(player, "Andraus", king, "order proíbo camas vermelhas nas casas, troquem por verdes");
        waitFor(h, async, 120);
        System.out.println("[" + (System.currentTimeMillis() - t0) + " ms] ordem do rei → " + async);
        for (var d : k.decrees) System.out.println("decreto #" + d.number + ": " + d.text + (d.rule == null ? "" : "  [regra: " + d.rule.spec() + "]"));
        System.exit(0);
    }

    private static void waitFor(VoxelHost h, List<String> async, int seconds) throws InterruptedException {
        long end = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < end) {
            synchronized (async) {
                if (!async.isEmpty()) break;
            }
            h.step();
            Thread.sleep(20);
        }
    }
}
