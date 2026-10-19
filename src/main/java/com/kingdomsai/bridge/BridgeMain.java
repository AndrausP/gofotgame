package com.kingdomsai.bridge;

import com.kingdomsai.core.CoreConfig;
import com.kingdomsai.core.WorldState;
import com.kingdomsai.voxel.VoxelHost;
import com.kingdomsai.voxel.WorldSave;

import java.nio.file.Path;

/**
 * Uso: java -cp ... com.kingdomsai.bridge.BridgeMain [--port 7777] [--seed N] [--name "Reino"] [--empty] [--save pasta]
 *        [--llm ollama|mock|claude_code] [--model M] [--npc-model M] [--endpoint URL] [--calls N]   (sem --llm: regras)
 * Com --save, carrega o jogo da pasta se existir e salva sozinho a cada ~5 minutos de jogo (e ao encerrar com Ctrl+C).
 */
public final class BridgeMain {
    public static void main(String[] args) throws Exception {
        System.setProperty("stdout.encoding", "UTF-8");
        int port = BridgeServer.DEFAULT_PORT;
        boolean empty = false;
        long seed = 20240601L;
        long startTick = 21000; // o mundo novo começa de manhã (o relógio do jogo soma 6000: tick 21000 = hora 3000)
        String name = "Reino de Andraus";
        Path save = null;
        com.kingdomsai.core.llm.LlmConfig llm = new com.kingdomsai.core.llm.LlmConfig();
        llm.provider = "mock";
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--seed" -> seed = Long.parseLong(args[++i]);
                case "--empty" -> empty = true;
                case "--start-tick" -> startTick = Long.parseLong(args[++i]);
                case "--name" -> name = args[++i];
                case "--save" -> save = Path.of(args[++i]);
                case "--llm" -> llm.provider = args[++i];
                case "--model" -> llm.model = args[++i];
                case "--npc-model" -> llm.npcModel = args[++i];
                case "--endpoint" -> llm.endpoint = args[++i];
                case "--calls" -> llm.maxCallsPerMinute = Integer.parseInt(args[++i]);
                default -> {
                }
            }
        }
        CoreConfig cfg = new CoreConfig();
        VoxelHost host;
        boolean fresh = true;
        if (save != null && WorldSave.exists(save)) {
            host = WorldSave.load(save, cfg);
            fresh = false;
            System.out.println("bridge: jogo carregado de " + save);
        } else {
            WorldState state = new WorldState();
            state.seed = seed;
            state.tick = startTick;
            host = new VoxelHost(seed, state, cfg);
        }
        host.core.llm().configure(llm);
        BridgeServer bridge = new BridgeServer(host);
        if (fresh && !empty) bridge.found(name);
        if (save != null) {
            final Path dir = save;
            bridge.setAutosave(dir, 20L * 60 * 5);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    bridge.saveNow();
                    System.out.println("bridge: jogo salvo em " + dir);
                } catch (Exception e) {
                    System.out.println("bridge: falha ao salvar: " + e);
                }
            }));
        }
        bridge.run(port);
    }
}
