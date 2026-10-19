package com.kingdomsai.core;

import com.kingdomsai.core.llm.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * F3.3/F3.4: fila única do LlmGateway com prioridade, deduplicação, descarte de pedidos vencidos, concorrência limitada
 * e nenhum processo do Claude Code vivo depois que o jogo fecha. Provider lento falso: sem rede e sem CLI.
 */
public final class LlmQueueSelfTest {
    private static int passed, failed;

    public static void main(String[] args) throws Exception {
        int[] r = run();
        System.out.println("\n" + r[0] + " passaram, " + r[1] + " falharam.");
        System.exit(r[1] == 0 ? 0 : 1);
    }

    /** Provider que só responde quando o teste manda; guarda a ordem em que foi chamado e quantos estavam ao mesmo tempo. */
    static final class SlowProvider implements LlmProvider {
        final List<String> order = Collections.synchronizedList(new ArrayList<>());
        final List<CompletableFuture<ProviderResult>> pending = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger concurrent = new AtomicInteger(), peak = new AtomicInteger();

        public String name() {
            return "claude_code";
        }

        public CompletableFuture<String> complete(LlmRequest r, LlmConfig c) {
            return completeDetailed(r, c).thenApply(ProviderResult::text);
        }

        public CompletableFuture<ProviderResult> completeDetailed(LlmRequest r, LlmConfig c) {
            order.add(r.purpose() + ":" + (r.npcId() == null ? "-" : r.npcId().toString().substring(0, 4)));
            int now = concurrent.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            CompletableFuture<ProviderResult> f = new CompletableFuture<>();
            pending.add(f);
            return f.whenComplete((x, e) -> concurrent.decrementAndGet());
        }

        /** Responde a chamada mais antiga ainda aberta. */
        void finishOne(String reply) {
            CompletableFuture<ProviderResult> f;
            synchronized (pending) {
                f = pending.isEmpty() ? null : pending.remove(0);
            }
            if (f != null) f.complete(new ProviderResult("{\"reply\":\"" + reply + "\",\"actions\":[]}",
                    new ProviderResult.Usage(0.001, 10, 5, 0, 0, 100, "m")));
        }

        void waitCalls(int n) throws InterruptedException {
            for (int i = 0; i < 200 && order.size() < n; i++) TimeUnit.MILLISECONDS.sleep(10);
        }
    }

    public static int[] run() throws Exception {
        passed = failed = 0;
        System.out.println("\n# Fila de IA: prioridade, deduplicação, vencimento, concorrência");

        // --- prioridade: 1 vaga; A ocupa; B..E esperam e saem por prioridade
        KingdomsCore core = new KingdomsCore(new WorldState(), new CoreConfig());
        core.setWorld(new SkillSelfTest.FakeWorld());
        SlowProvider slow = new SlowProvider();
        core.llm().setClaudeCodeProvider(slow);
        LlmConfig cfg = new LlmConfig();
        cfg.provider = "claude_code";
        cfg.maxConcurrentCalls = 1;
        cfg.maxCallsPerMinute = 100;
        core.llm().configure(cfg);
        UUID n1 = UUID.fromString("11110000-0000-0000-0000-000000000000"), n2 = UUID.fromString("22220000-0000-0000-0000-000000000000"),
                n3 = UUID.fromString("33330000-0000-0000-0000-000000000000"), n4 = UUID.fromString("44440000-0000-0000-0000-000000000000");
        CompletableFuture<Plan> a = core.llm().background(req("conversa", n1));
        slow.waitCalls(1);
        CompletableFuture<Plan> b = core.llm().background(req("agenda", n2));
        CompletableFuture<Plan> c = core.llm().background(req("conversa", n3));
        CompletableFuture<LlmGateway.Result> council = core.llm().submit(req("council", null));
        CompletableFuture<LlmGateway.Result> king = core.llm().submit(req("npc_dialogue", n4));
        TimeUnit.MILLISECONDS.sleep(100);
        check("1 vaga: só a primeira chamada foi ao provider", slow.order.size() == 1 && slow.peak.get() == 1);
        for (int i = 0; i < 5; i++) {
            slow.finishOne("ok" + i);
            slow.waitCalls(Math.min(5, i + 2));
            TimeUnit.MILLISECONDS.sleep(30);
        }
        a.get(5, TimeUnit.SECONDS);
        b.get(5, TimeUnit.SECONDS);
        c.get(5, TimeUnit.SECONDS);
        council.get(5, TimeUnit.SECONDS);
        king.get(5, TimeUnit.SECONDS);
        System.out.println("    | ordem no provider: " + slow.order);
        check("fala do rei > conselho > agenda > conversa entre súditos", slow.order.size() == 5
                && slow.order.get(1).startsWith("npc_dialogue") && slow.order.get(2).startsWith("council")
                && slow.order.get(3).startsWith("agenda") && slow.order.get(4).startsWith("conversa"));
        check("nunca mais de 1 chamada ao mesmo tempo", slow.peak.get() == 1);

        // --- deduplicação: mesma finalidade + mesmo NPC pendentes viram uma chamada só
        KingdomsCore core2 = new KingdomsCore(new WorldState(), new CoreConfig());
        core2.setWorld(new SkillSelfTest.FakeWorld());
        SlowProvider slow2 = new SlowProvider();
        core2.llm().setClaudeCodeProvider(slow2);
        LlmConfig cfg2 = cfg.copy();
        cfg2.maxConcurrentCalls = 1;
        core2.llm().configure(cfg2);
        UUID same = UUID.fromString("aaaa0000-0000-0000-0000-000000000000");
        CompletableFuture<Plan> blocker = core2.llm().background(req("conversa", n1));
        slow2.waitCalls(1);
        CompletableFuture<Plan> d1 = core2.llm().background(req("agenda", same));
        CompletableFuture<Plan> d2 = core2.llm().background(req("agenda", same));
        CompletableFuture<Plan> other = core2.llm().background(req("agenda", n2));
        slow2.finishOne("blocker");
        slow2.waitCalls(2);
        slow2.finishOne("dedup");
        slow2.waitCalls(3);
        slow2.finishOne("other");
        Plan p1 = d1.get(5, TimeUnit.SECONDS), p2 = d2.get(5, TimeUnit.SECONDS);
        check("pedidos idênticos pendentes são fundidos (1 chamada para 2 pedidos)", slow2.order.size() == 3 && core2.llm().fusedRequests() == 1);
        check("os dois recebem a mesma resposta", p1 != null && p2 != null && p1.reply().equals("dedup") && p2.reply().equals("dedup"));
        check("NPC diferente não é fundido", other.get(5, TimeUnit.SECONDS).reply().equals("other"));
        check("a chamada fundida entra uma vez só no livro-caixa (3 chamadas, não 4)", core2.llm().ledger().totalCalls() == 3
                && Math.abs(core2.llm().ledger().totalCostUsd() - 0.003) < 1e-9);
        blocker.get(5, TimeUnit.SECONDS);

        // --- vencimento: o NPC já mudou de estado quando chegou a vez do pedido
        KingdomsCore core3 = new KingdomsCore(new WorldState(), new CoreConfig());
        core3.setWorld(new SkillSelfTest.FakeWorld());
        SlowProvider slow3 = new SlowProvider();
        core3.llm().setClaudeCodeProvider(slow3);
        LlmConfig cfg3 = cfg.copy();
        cfg3.maxConcurrentCalls = 1;
        core3.llm().configure(cfg3);
        CompletableFuture<Plan> running = core3.llm().background(req("conversa", n1));
        slow3.waitCalls(1);
        CompletableFuture<Plan> late = core3.llm().background(req("conversa", n2));   // espera na fila
        CompletableFuture<Plan> fresh = core3.llm().background(req("agenda", n3));
        for (int i = 0; i < 20 * 30; i++) core3.step();                                 // 30 s de jogo passam na fila
        slow3.finishOne("running");
        slow3.waitCalls(2);
        TimeUnit.MILLISECONDS.sleep(50);
        check("conversa vencida na fila é descartada sem chamar o provider", late.get(5, TimeUnit.SECONDS) == null && !slow3.order.contains("conversa:2222")
                && core3.llm().staleDropped() == 1);
        check("a agenda (vida útil maior) ainda vale e é chamada", slow3.order.size() == 2 && slow3.order.get(1).startsWith("agenda"));
        slow3.finishOne("fresh");
        check("e responde", fresh.get(5, TimeUnit.SECONDS).reply().equals("fresh"));
        running.get(5, TimeUnit.SECONDS);
        check("o descarte aparece no livro-caixa", core3.llm().ledger().snapshot().get("conversa").stale == 1);

        // --- concorrência configurável
        KingdomsCore core4 = new KingdomsCore(new WorldState(), new CoreConfig());
        core4.setWorld(new SkillSelfTest.FakeWorld());
        SlowProvider slow4 = new SlowProvider();
        core4.llm().setClaudeCodeProvider(slow4);
        LlmConfig cfg4 = cfg.copy();
        cfg4.maxConcurrentCalls = 3;
        core4.llm().configure(cfg4);
        List<CompletableFuture<Plan>> fs = new ArrayList<>();
        for (int i = 0; i < 7; i++) fs.add(core4.llm().background(req("agenda", UUID.randomUUID())));
        slow4.waitCalls(3);
        TimeUnit.MILLISECONDS.sleep(100);
        check("padrão: 3 chamadas ao mesmo tempo, o resto espera", slow4.peak.get() == 3 && slow4.order.size() == 3);
        for (int i = 0; i < 7; i++) {
            slow4.finishOne("x");
            TimeUnit.MILLISECONDS.sleep(20);
        }
        for (var f : fs) f.get(5, TimeUnit.SECONDS);
        check("todas as 7 terminam, sem passar de 3 simultâneas", slow4.order.size() == 7 && slow4.peak.get() == 3);

        // --- nenhum processo do claude fica vivo depois que o jogo fecha
        HangingProcess hang = new HangingProcess();
        ClaudeCodeProvider prov = new ClaudeCodeProvider((cmd, dir) -> {
            hang.systemFile = dir.resolve(cmd.get(cmd.indexOf("--system-prompt-file") + 1));
            return hang;
        }, false, Files.createTempDirectory("kai-live"), 300);
        LlmConfig pc = new LlmConfig();
        pc.provider = "claude_code";
        pc.timeoutMs = 120_000;
        CompletableFuture<ProviderResult> hung = prov.completeDetailed(req("agenda", n1), pc);
        for (int i = 0; i < 100 && ClaudeCodeProvider.liveProcesses() == 0; i++) TimeUnit.MILLISECONDS.sleep(20);
        check("processo em andamento fica registrado", ClaudeCodeProvider.liveProcesses() == 1 && hang.alive);
        ClaudeCodeProvider.killAll();  // é o que o gancho de desligamento da JVM faz
        check("killAll encerra o processo do claude (e filhos)", !hang.alive && ClaudeCodeProvider.liveProcesses() == 0);
        hang.release.countDown();
        try {
            hung.get(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
        return new int[]{passed, failed};
    }

    private static LlmRequest req(String purpose, UUID npc) {
        return new LlmRequest(purpose, "sys", "user", null, npc, "");
    }

    /** Processo que nunca termina sozinho (até ser morto). */
    static final class HangingProcess extends Process {
        volatile boolean alive = true;
        volatile java.nio.file.Path systemFile;
        final CountDownLatch release = new CountDownLatch(1);

        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        public int waitFor() throws InterruptedException {
            release.await();
            return 1;
        }

        public boolean waitFor(long t, TimeUnit u) throws InterruptedException {
            return release.await(t, u) || !alive;
        }

        public int exitValue() {
            if (alive) throw new IllegalThreadStateException();
            return 1;
        }

        public void destroy() {
            alive = false;
            release.countDown();
        }

        @Override
        public Process destroyForcibly() {
            destroy();
            return this;
        }

        public java.util.stream.Stream<ProcessHandle> descendants() {
            return java.util.stream.Stream.empty();
        }
    }

    private static void check(String name, boolean ok) {
        if (ok) passed++;
        else failed++;
        System.out.println((ok ? "  [OK]   " : "  [FAIL] ") + name);
    }
}
