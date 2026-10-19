package com.kingdomsai.core;

import com.kingdomsai.core.action.ActionRequest;
import com.kingdomsai.core.action.ActionResult;
import com.kingdomsai.core.action.ActionType;
import com.kingdomsai.core.cli.CommandService;
import com.kingdomsai.core.common.Pos;
import com.kingdomsai.core.construction.BlueprintLibrary;
import com.kingdomsai.core.construction.Building;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.kingdom.ResourceType;
import com.kingdomsai.core.llm.LlmConfig;
import com.kingdomsai.core.npc.*;
import com.kingdomsai.core.persistence.Persistence;
import com.kingdomsai.core.skill.ChunkKeeper;
import com.kingdomsai.core.skill.PhysicalJob;
import com.kingdomsai.core.work.WorkChain;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * "O súdito faz o que mandei mesmo comigo longe": o rei dá a ordem, vai embora (o mundo descarrega
 * perto da obra) e a ordem continua — o jogo segura só os chunks da etapa atual e solta no fim.
 * Também: entrega com o rei fora vai para o armazém, rotinas seguem, relatório na volta, save no meio.
 */
public final class PersistenceSelfTest {
    private static int passed, failed;
    private static KingdomsCore core;
    private static CommandService cli;
    private static UUID player;
    private static final List<String> async = new ArrayList<>();
    private static SkillSelfTest.FakeWorld world;

    public static void main(String[] args) throws Exception {
        int[] r = run();
        System.out.println("\n" + r[0] + " passaram, " + r[1] + " falharam.");
        System.exit(r[1] == 0 ? 0 : 1);
    }

    public static int[] run() throws Exception {
        passed = failed = 0;
        System.out.println("\n# Persistência: ordens continuam com o rei longe");
        world = new SkillSelfTest.FakeWorld();
        CoreConfig cfg = new CoreConfig();
        cfg.rivalKingdoms = 0;
        core = new KingdomsCore(new WorldState(), cfg);
        core.setWorld(world);
        core.setPhysical(world);
        LlmConfig mock = new LlmConfig();
        mock.provider = "mock";
        core.llm().configure(mock);
        player = UUID.randomUUID();
        cli = new CommandService(core);
        cli.setNotifier((p, lines) -> async.addAll(lines));
        cli.execute(player, "Andraus", new Pos(0, 64, 0), "found Reino de Teste");
        Kingdom k = core.kingdomOfPlayer(player);
        k.civic = "off"; // estes testes medem o efeito exato das ordens; a vontade própria tem suíte própria (CivicSelfTest)
        k.openingAuto = false; // o roteiro de início tem o próprio teste (aqui ele gastaria a madeira medida)
        Npc miner = prof(k, Profession.MINER), smith = prof(k, Profession.BLACKSMITH), lumber = prof(k, Profession.LUMBERJACK);
        Building storage = complete(k, "storage", new Pos(-30, 64, 10));
        Pos stChest = storage.centerPos().offset(0, 0, -1);
        world.set(stChest, "minecraft:chest");
        world.chests.put(stChest, new TreeMap<>());
        world.viewer = new Pos(0, 64, 0); // a partir daqui só carrega perto do rei (e o que for forçado)

        // --- 1. ordem dada perto, rei vai embora: continua e termina
        king(new Pos(0, 64, 0), new Pos(20, 63, 20));
        say(miner, "Cave um buraco 3x3x3 aqui.");
        PhysicalJob dig = job(miner);
        check("ordem criada perto do rei", dig != null);
        goAway(new Pos(800, 64, 0));
        seconds(3);
        check("rei a 800 blocos: chunks da obra mantidos pelo jogo", !world.forced.isEmpty()
                && world.forced.containsAll(ChunkKeeper.chunksFor(dig)) && core.state().forcedChunks.equals(world.forced));
        runUntilDone(dig, 200);
        check("buraco cavado com o rei longe", dig.status == PhysicalJob.Status.DONE && world.isAir(new Pos(21, 61, 21)));
        seconds(6);
        check("terminou → chunks soltos (nada fica carregado à toa)", world.forced.isEmpty() && core.state().forcedChunks.isEmpty());

        // --- 2. desligado na config: espera até o rei voltar (comportamento honesto, não falha)
        core.config().keepOrderChunksLoaded = false;
        king(new Pos(0, 64, 0), new Pos(-20, 63, 20));
        say(miner, "Cave um buraco 3x3 aqui.");
        PhysicalJob waitJob = job(miner);
        goAway(new Pos(800, 64, 0));
        seconds(40);
        check("sem manter chunks: ESPERA com motivo claro (não falha)", waitJob.status == PhysicalJob.Status.WAITING
                && waitJob.reason.contains("descarregou"));
        world.viewer = new Pos(-10, 64, 10);
        core.updatePlayerPos(player, new Pos(-10, 64, 10));
        runUntilDone(waitJob, 200);
        check("rei voltou → a ordem continuou de onde parou", waitJob.status == PhysicalJob.Status.DONE);
        core.config().keepOrderChunksLoaded = true;

        // --- 3. limite de chunks: a segunda ordem espera na fila e depois anda
        core.config().maxForcedChunks = 1;
        king(new Pos(0, 64, 0), new Pos(40, 63, 0));
        jobAction(k, "npc", miner.name, "kind", "break");
        PhysicalJob first = job(miner);
        king(new Pos(0, 64, 0), new Pos(0, 63, 40));
        jobAction(k, "npc", lumber.name, "kind", "break");
        PhysicalJob second = job(lumber);
        goAway(new Pos(800, 64, 0));
        boolean queued = false;
        for (int s = 0; s < 120 && (first.status.live() || second.status.live()); s++) {
            seconds(1);
            queued |= second.status == PhysicalJob.Status.WAITING && second.reason.contains("limite de 1 chunks");
            check1(world.forced.size() <= 1);
        }
        check("limite respeitado: nunca mais de 1 chunk forçado", limitOk);
        check("segunda ordem esperou na fila", queued);
        check("as duas terminaram", first.status == PhysicalJob.Status.DONE && second.status == PhysicalJob.Status.DONE);
        core.config().maxForcedChunks = 16;

        // --- 4. chunk que o jogador já segurava com /forceload não é nosso
        world.playerForced.add(ChunkKeeper.key(new Pos(70, 63, 70)));
        king(new Pos(60, 64, 60), new Pos(70, 63, 70));
        jobAction(k, "npc", miner.name, "kind", "break");
        PhysicalJob pf = job(miner);
        goAway(new Pos(800, 64, 0));
        runUntilDone(pf, 60);
        seconds(6);
        check("chunk do /forceload do jogador: usado, nunca assumido nem solto", pf.status == PhysicalJob.Status.DONE
                && world.playerForced.contains(ChunkKeeper.key(new Pos(70, 63, 70))) && !core.state().forcedChunks.contains(ChunkKeeper.key(new Pos(70, 63, 70))));

        // --- 5. "me entregue" com o rei longe/fora do jogo: fica no armazém e avisa onde
        Pos chest = new Pos(-10, 63, -10);
        world.set(chest, "minecraft:chest");
        world.chests.put(chest, new TreeMap<>(Map.of("minecraft:iron_ingot", 3, "minecraft:oak_log", 1)));
        world.set(new Pos(-6, 64, -10), "minecraft:crafting_table");
        world.viewer = new Pos(-8, 64, -8);
        king(new Pos(-8, 64, -8), chest);
        say(smith, "Faça uma picareta de ferro e me entregue.");
        PhysicalJob gift = job(smith);
        goAway(new Pos(800, 64, 0));
        core.playerLeft(player);
        runUntilDone(gift, 200);
        check("rei fora do jogo: picareta guardada no baú do armazém", gift.status == PhysicalJob.Status.DONE
                && world.chests.get(stChest).getOrDefault("minecraft:iron_pickaxe", 0) == 1 && world.given.isEmpty());
        check("e o aviso diz onde ficou", core.bus().log().recent(20, e -> e.type() == com.kingdomsai.core.event.EventType.JOB_DONE)
                .stream().anyMatch(e -> e.message().contains("baú do armazém")));

        // --- 6. rotina continua com o rei longe + relatório na volta
        world.viewer = new Pos(0, 64, 0);
        king(new Pos(0, 64, 0), null);
        say(lumber, "Daqui pra frente corte lenha e guarde no armazém.");
        WorkChain wood = lumber.dutyChainId == null ? null : core.state().chains.get(lumber.dutyChainId);
        check("rotina de lenha criada", wood != null);
        core.reports().tick(player, new Pos(0, 64, 0));
        double woodBefore = k.get(ResourceType.WOOD);
        goAway(new Pos(800, 64, 0));
        core.reports().tick(player, new Pos(800, 64, 0)); // saiu da vila
        king(new Pos(0, 64, 0), new Pos(25, 63, -25));
        jobAction(k, "npc", miner.name, "kind", "dig", "size", "2x2x2");
        goAway(new Pos(800, 64, 0));
        for (int s = 0; s < 200 && wood.cycles < 2; s++) seconds(1);
        check("rotina seguiu com o rei longe (" + wood.cycles + " ciclos)", wood.cycles >= 2 && k.get(ResourceType.WOOD) > woodBefore - 10);
        seconds(Math.max(0, (int) (com.kingdomsai.core.event.AwayReport.MIN_AWAY_TICKS / 20)));
        List<String> report = core.reports().tick(player, new Pos(5, 64, 5)); // voltou
        print(report);
        check("na volta: relatório do que aconteceu", !report.isEmpty() && report.get(0).contains("esteve fora")
                && report.stream().anyMatch(l -> l.contains("Lenha para o armazém") && l.contains("ciclo"))
                && report.stream().anyMatch(l -> l.contains("terminou")));
        check("/k report repete o último relatório", cli.execute(player, "Andraus", new Pos(5, 64, 5), "report").equals(report));

        // --- 7. salvar no meio de uma ordem longe e recarregar: continua e solta os chunks
        king(new Pos(0, 64, 0), new Pos(-40, 63, -40));
        say(miner, "Cave um buraco 3x3x3 aqui.");
        PhysicalJob mid = job(miner);
        goAway(new Pos(800, 64, 0));
        for (int s = 0; s < 120 && mid.tasks.get(0).done < 5; s++) seconds(1);
        int doneBefore = mid.tasks.get(0).done;
        core.state().forcedChunks.add("99:99"); // sobra de uma sessão antiga
        world.forced.add("99:99");
        Path tmp = Files.createTempDirectory("kai-persist").resolve("kingdomsai.json");
        Persistence.save(core.snapshotForSave(), tmp);
        WorldState loaded = Persistence.load(tmp);
        KingdomsCore core2 = new KingdomsCore(loaded, cfg);
        core2.setWorld(world);
        core2.setPhysical(world);
        core2.updatePlayerPos(player, new Pos(800, 64, 0));
        PhysicalJob mid2 = loaded.jobs.get(mid.id);
        check("save guarda a ordem no meio (" + doneBefore + " de " + mid.tasks.get(0).total + " blocos)", doneBefore >= 5 && doneBefore < mid.tasks.get(0).total
                && mid2 != null && mid2.status.live() && mid2.tasks.get(0).done == doneBefore);
        for (int s = 0; s < 200 && mid2.status.live(); s++) for (int i = 0; i < 20; i++) core2.step();
        for (int i = 0; i < 20 * 6; i++) core2.step();
        check("depois de recarregar: terminou longe do rei", mid2.status == PhysicalJob.Status.DONE && world.isAir(new Pos(-39, 61, -39)));
        check("sobra de chunk antiga foi solta; nada fica carregado", !world.forced.contains("99:99") && loaded.forcedChunks.isEmpty());
        return new int[]{passed, failed};
    }

    // ------------------------------------------------------------------ util

    private static boolean limitOk = true;

    private static void check1(boolean ok) {
        limitOk &= ok;
    }

    private static Npc prof(Kingdom k, Profession p) {
        return core.citizens(k.id).stream().filter(n -> n.profession == p).findFirst().orElseThrow();
    }

    private static Building complete(Kingdom k, String id, Pos at) {
        Building b = core.construction().planAt(k, BlueprintLibrary.get(id), at, true);
        b.status = Building.Status.COMPLETE;
        b.progress = b.placed = b.blueprint().blockCount();
        return b;
    }

    private static void king(Pos at, Pos look) {
        world.viewer = at;
        core.updatePlayerPos(player, at);
        core.updatePlayerLook(player, look, "south");
    }

    private static void goAway(Pos far) {
        world.viewer = far;
        core.updatePlayerPos(player, far);
        core.updatePlayerLook(player, null, "south");
    }

    private static PhysicalJob job(Npc n) {
        return n.jobId == null ? null : core.state().jobs.get(n.jobId);
    }

    private static ActionResult jobAction(Kingdom k, String... kv) {
        return core.actions().execute(ActionRequest.of(k.id, player, ActionRequest.ActorKind.PLAYER, ActionType.JOB, ActionRequest.Source.TEST, kv));
    }

    private static void say(Npc n, String text) {
        async.clear();
        Pos at = core.playerPos(player);
        KingdomsCore.Look look = core.playerLook(player);
        cli.execute(player, "Andraus", at, "npc talk " + n.name + " " + text);
        core.updatePlayerLook(player, look == null ? null : look.block(), "south");
        print(async);
    }

    private static void runUntilDone(PhysicalJob j, int maxSeconds) {
        for (int s = 0; s < maxSeconds && j != null && j.status.live(); s++) seconds(1);
    }

    private static void seconds(int s) {
        for (int i = 0; i < s * 20; i++) core.step();
    }

    private static void check(String name, boolean ok) {
        if (ok) passed++;
        else failed++;
        System.out.println((ok ? "  [OK]   " : "  [FAIL] ") + name);
    }

    private static void print(List<String> lines) {
        for (String l : lines) System.out.println("    | " + l);
    }
}
