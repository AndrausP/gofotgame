package com.kingdomsai.bridge;

import com.kingdomsai.core.CoreConfig;
import com.kingdomsai.core.WorldState;
import com.kingdomsai.voxel.VoxelHost;
import com.kingdomsai.voxel.WorldSave;

import java.nio.file.Path;

/**
 * Uso: java -cp ... com.kingdomsai.bridge.BridgeMain [--port 7777] [--seed N] [--name "Reino"] [--empty] [--save pasta]
 *        [--llm ollama|mock|claude_code] [--model M] [--npc-model M] [--endpoint URL] [--calls N]   (sem --llm: regras)
 *        Claude Code: [--claude-model M (súditos, padrão haiku)] [--claude-council-model M (conselho, padrão sonnet)]
 *        [--claude-command CMD] [--timeout MS] [--budget-usd N (teto da sessão; padrão 1.00)] [--effort low|medium|high|xhigh|max]
 *        Variáveis: KINGDOMS_CLAUDE_MODEL, KINGDOMS_CLAUDE_COUNCIL_MODEL, KINGDOMS_CLAUDE_BUDGET_USD (os argumentos vencem)
 * Com --save, carrega o jogo da pasta se existir e salva sozinho a cada ~5 minutos de jogo (e ao encerrar com Ctrl+C).
 */
public final class BridgeMain {
    /** Teto de gasto por sessão quando o provider é o Claude Code e nada foi pedido. */
    public static final double DEFAULT_CLAUDE_BUDGET_USD = 1.0;

    /**
     * Monta a configuração da IA a partir dos argumentos (e de variáveis de ambiente, que os argumentos vencem).
     * Com {@code --llm claude_code}, {@code --model X} vale para o conselho (compatibilidade) e avisa.
     */
    public static com.kingdomsai.core.llm.LlmConfig llmFromArgs(String[] args, java.util.function.Function<String, String> env) {
        com.kingdomsai.core.llm.LlmConfig llm = new com.kingdomsai.core.llm.LlmConfig();
        llm.provider = "mock";
        String envModel = env.apply("KINGDOMS_CLAUDE_MODEL"), envCouncil = env.apply("KINGDOMS_CLAUDE_COUNCIL_MODEL"), envBudget = env.apply("KINGDOMS_CLAUDE_BUDGET_USD");
        if (envModel != null && !envModel.isBlank()) llm.claudeNpcModel = envModel.trim();
        if (envCouncil != null && !envCouncil.isBlank()) llm.claudeCouncilModel = envCouncil.trim();
        boolean budgetSet = false, councilSet = false;
        if (envBudget != null && !envBudget.isBlank()) {
            llm.budgetUsd = Double.parseDouble(envBudget.trim());
            budgetSet = true;
        }
        String model = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--llm" -> llm.provider = args[++i];
                case "--model" -> model = args[++i];
                case "--npc-model" -> llm.npcModel = args[++i];
                case "--endpoint" -> llm.endpoint = args[++i];
                case "--calls" -> llm.maxCallsPerMinute = Integer.parseInt(args[++i]);
                case "--claude-model" -> llm.claudeNpcModel = args[++i];
                case "--claude-council-model" -> {
                    llm.claudeCouncilModel = args[++i];
                    councilSet = true;
                }
                case "--claude-command" -> llm.claudeCommand = args[++i];
                case "--timeout" -> llm.timeoutMs = Integer.parseInt(args[++i]);
                case "--budget-usd" -> {
                    llm.budgetUsd = Double.parseDouble(args[++i]);
                    budgetSet = true;
                }
                case "--effort" -> llm.claudeEffort = args[++i];
                default -> {
                }
            }
        }
        if (model != null) {
            llm.model = model;
            if (llm.isClaudeCode() && !councilSet) {
                llm.claudeCouncilModel = model;
                System.out.println("bridge: aviso: com --llm claude_code, --model " + model + " vale para o conselho (--claude-council-model). "
                        + "Os súditos usam --claude-model (" + llm.claudeNpcModel + ").");
            }
        }
        if (llm.isClaudeCode() && !budgetSet) llm.budgetUsd = DEFAULT_CLAUDE_BUDGET_USD;
        return llm;
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("stdout.encoding", "UTF-8");
        int port = BridgeServer.DEFAULT_PORT;
        boolean empty = false;
        long seed = 20240601L;
        long startTick = 21000; // o mundo novo começa de manhã (o relógio do jogo soma 6000: tick 21000 = hora 3000)
        String name = "Reino de Andraus";
        Path save = null;
        com.kingdomsai.core.llm.LlmConfig llm = llmFromArgs(args, System::getenv);
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--seed" -> seed = Long.parseLong(args[++i]);
                case "--empty" -> empty = true;
                case "--start-tick" -> startTick = Long.parseLong(args[++i]);
                case "--name" -> name = args[++i];
                case "--save" -> save = Path.of(args[++i]);
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
