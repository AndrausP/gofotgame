package com.kingdomsai.live;

import com.kingdomsai.core.CoreConfig;
import com.kingdomsai.core.WorldState;
import com.kingdomsai.core.cli.CommandService;
import com.kingdomsai.core.common.Pos;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.llm.ClaudeCodeProvider;
import com.kingdomsai.core.llm.LlmConfig;
import com.kingdomsai.core.llm.LlmRequest;
import com.kingdomsai.core.llm.Plan;
import com.kingdomsai.core.llm.ProviderResult;
import com.kingdomsai.core.npc.Npc;
import com.kingdomsai.voxel.VoxelHost;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Bench MANUAL do Claude Code como IA (não roda no {@code test}; faz chamadas reais e gasta cota/crédito).
 * Uso: {@code ./gradlew liveCheck -PliveClass=com.kingdomsai.live.ClaudeCodeBench --args="20 npc_dialogue,council,agenda,conversa"}
 * (ou {@code java -cp ... com.kingdomsai.live.ClaudeCodeBench [chamadasPorFinalidade] [finalidades] [paralelo]}).
 * Para cada finalidade faz N chamadas com contexto real do {@code ContextBuilder}/{@code LifeSystem} num mundo de teste e
 * imprime p50/p95 de latência de relógio, custo médio, tokens de cache criados/lidos e a taxa de JSON válido.
 */
public final class ClaudeCodeBench {
    private static final String[] QUESTIONS = {"Como você está hoje?", "Construa uma casa pequena perto da praça.", "Chame todos aqui.",
            "O que você acha de uma lei que proíbe camas vermelhas?", "Cuide da fazenda.", "Quem é o seu melhor amigo?", "Recrute três soldados.",
            "Há comida suficiente?", "Corte lenha e guarde no armazém.", "Faça uma picareta de ferro e me entregue."};
    private static final String[] ORDERS = {"construam um celeiro e uma casa", "cuide da comida", "aumente os impostos", "proíbo camas vermelhas",
            "recrutem soldados", "quanto ouro temos?", "marquem a praça aqui", "faltou pedra, resolvam", "mande uma carta ao rei vizinho", "descansem hoje"};

    private record Sample(long wallMs, ProviderResult.Usage usage, boolean valid, String error) {}

    public static void main(String[] args) throws Exception {
        System.setProperty("stdout.encoding", "UTF-8");
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 20;
        List<String> purposes = args.length > 1 ? List.of(args[1].split(",")) : List.of("npc_dialogue", "council", "agenda", "conversa");
        int parallel = args.length > 2 ? Integer.parseInt(args[2]) : 1;

        CoreConfig cfg = new CoreConfig();
        cfg.rivalKingdoms = 0;
        cfg.aiKingdomsEnabled = false;
        VoxelHost h = new VoxelHost(42, new WorldState(), cfg);
        UUID player = UUID.randomUUID();
        CommandService cli = new CommandService(h.core);
        int g = h.world.groundY(0, 0);
        Pos king = new Pos(0, g, 0);
        h.core.updatePlayerPos(player, king);
        h.area.setViewer(player, king);
        cli.execute(player, "Andraus", king, "found Reino do Bench");
        Kingdom k = h.core.kingdomOfPlayer(player);
        for (int i = 0; i < 20 * 60; i++) h.step();
        List<Npc> citizens = h.core.citizens(k.id);

        LlmConfig llm = new LlmConfig();
        llm.provider = "claude_code";
        llm.timeoutMs = 120_000;
        if (System.getenv("BENCH_EFFORT") != null) llm.claudeEffort = System.getenv("BENCH_EFFORT");
        if (System.getenv("BENCH_NPC_MODEL") != null) llm.claudeNpcModel = System.getenv("BENCH_NPC_MODEL");
        if (System.getenv("BENCH_COUNCIL_MODEL") != null) llm.claudeCouncilModel = System.getenv("BENCH_COUNCIL_MODEL");
        ClaudeCodeProvider provider = new ClaudeCodeProvider();
        System.out.println("# Bench do Claude Code — " + java.time.LocalDate.now() + " · CLI " + cliVersion(llm) + " · " + n + " chamadas por finalidade · paralelo " + parallel);
        System.out.println("esforço: " + (llm.claudeEffort.isEmpty() ? "(padrão)" : llm.claudeEffort) + " · modelos: súditos " + llm.claudeNpcModel + " · conselho " + llm.claudeCouncilModel + "\n");
        System.out.println("| finalidade | modelo | p50 (s) | p95 (s) | custo médio (US$) | entrada (tok) | saída (tok) | cache criado | cache lido | JSON válido | falhas |");
        System.out.println("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |");

        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, parallel));
        double total = 0;
        for (String purpose : purposes) {
            List<LlmRequest> reqs = new ArrayList<>();
            for (int i = 0; i < n; i++) reqs.add(request(h, purpose, k, citizens, i));
            List<Sample> samples = new ArrayList<>();
            List<Future<Sample>> fs = new ArrayList<>();
            for (LlmRequest r : reqs) fs.add(pool.submit(() -> call(provider, llm, r)));
            for (Future<Sample> f : fs) samples.add(f.get(10, TimeUnit.MINUTES));
            total += report(purpose, llm.claudeModelFor(purpose), samples);
        }
        pool.shutdownNow();
        System.out.printf(Locale.ROOT, "%nGasto estimado total do bench: US$ %.4f%n", total);
        System.exit(0);
    }

    private static LlmRequest request(VoxelHost h, String purpose, Kingdom k, List<Npc> citizens, int i) {
        Npc npc = citizens.get(i % citizens.size());
        return switch (purpose) {
            case "npc_dialogue" -> h.core.contextBuilder().npcDialogue(k, npc, QUESTIONS[i % QUESTIONS.length]);
            case "council" -> h.core.contextBuilder().councilOrder(k, ORDERS[i % ORDERS.length]);
            case "agenda" -> h.core.life().agendaRequest(npc);
            case "conversa" -> h.core.life().conversationRequest(npc, citizens.get((i + 1) % citizens.size()));
            default -> throw new IllegalArgumentException("finalidade desconhecida: " + purpose);
        };
    }

    private static Sample call(ClaudeCodeProvider provider, LlmConfig cfg, LlmRequest req) {
        long t0 = System.currentTimeMillis();
        try {
            ProviderResult r = provider.completeDetailed(req, cfg).get(130, TimeUnit.SECONDS);
            boolean ok;
            try {
                Plan.parse(r.text());
                ok = true;
            } catch (RuntimeException e) {
                ok = false;
            }
            return new Sample(System.currentTimeMillis() - t0, r.usage(), ok, null);
        } catch (Exception e) {
            return new Sample(System.currentTimeMillis() - t0, ProviderResult.Usage.UNKNOWN, false, String.valueOf(e.getMessage()));
        }
    }

    private static double report(String purpose, String model, List<Sample> samples) {
        List<Long> ok = new ArrayList<>();
        double cost = 0;
        long in = 0, out = 0, create = 0, read = 0;
        int valid = 0, failed = 0, priced = 0;
        for (Sample s : samples) {
            if (s.error() != null) {
                failed++;
                continue;
            }
            ok.add(s.wallMs());
            if (s.usage().known()) {
                cost += s.usage().costUsd();
                priced++;
            }
            in += s.usage().inputTokens();
            out += s.usage().outputTokens();
            create += s.usage().cacheCreationTokens();
            read += s.usage().cacheReadTokens();
            if (s.valid()) valid++;
        }
        Collections.sort(ok);
        int done = Math.max(1, ok.size());
        System.out.printf(Locale.ROOT, "| %s | %s | %.1f | %.1f | %.4f | %d | %d | %d | %d | %d/%d | %d |%n", purpose, model, pct(ok, 50) / 1000.0, pct(ok, 95) / 1000.0,
                priced == 0 ? 0.0 : cost / priced, in / done, out / done, create / done, read / done, valid, samples.size(), failed);
        for (Sample s : samples) if (s.error() != null) System.out.println("  falha: " + s.error());
        return cost;
    }

    private static long pct(List<Long> sorted, int p) {
        if (sorted.isEmpty()) return 0;
        int idx = (int) Math.min(sorted.size() - 1, Math.ceil(p / 100.0 * sorted.size()) - 1);
        return sorted.get(Math.max(0, idx));
    }

    private static String cliVersion(LlmConfig cfg) {
        try {
            Process p = new ProcessBuilder(cfg.claudeCommand, "--version").redirectErrorStream(true).start();
            String v = new String(p.getInputStream().readAllBytes()).strip();
            p.waitFor(10, TimeUnit.SECONDS);
            return v;
        } catch (Exception e) {
            return "?";
        }
    }
}
