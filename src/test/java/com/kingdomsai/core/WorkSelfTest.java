package com.kingdomsai.core;

import com.kingdomsai.core.action.ActionRequest;
import com.kingdomsai.core.action.ActionResult;
import com.kingdomsai.core.action.ActionType;
import com.kingdomsai.core.cli.CommandService;
import com.kingdomsai.core.common.Pos;
import com.kingdomsai.core.construction.Blueprint;
import com.kingdomsai.core.construction.BlueprintLibrary;
import com.kingdomsai.core.construction.Building;
import com.kingdomsai.core.event.EventType;
import com.kingdomsai.core.event.GameEvent;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.kingdom.ResourceType;
import com.kingdomsai.core.llm.LlmConfig;
import com.kingdomsai.core.npc.*;
import com.kingdomsai.core.persistence.Persistence;
import com.kingdomsai.core.port.WorldPort;
import com.kingdomsai.core.work.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Cadeias de trabalho de ponta a ponta, sem Minecraft: o rei fala, a cadeia é validada, as pessoas andam,
 * mineram, entregam, fundem, guardam; a cadeia quebra quando o ferreiro morre e é retomada de onde parou
 * quando chega um novo ferreiro. Também lavoura, livros e cartas.
 */
public final class WorkSelfTest {
    private static int passed, failed;
    private static KingdomsCore core;
    private static CommandService cli;
    private static UUID player;
    private static final List<String> async = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        int[] r = run();
        System.out.println("\n" + r[0] + " passaram, " + r[1] + " falharam.");
        System.exit(r[1] == 0 ? 0 : 1);
    }

    public static int[] run() throws Exception {
        passed = failed = 0;
        System.out.println("\n# Cadeias de trabalho");
        WorldPort flat = new WorldPort() {
            public SiteCheck checkSite(int x, int z, Blueprint bp) {
                return new SiteCheck(SiteCheck.Kind.OK, 64, 1.0);
            }

            public long dayTime() {
                return 6000;
            }

            public boolean isLoaded(int x, int z) {
                return true;
            }

            public int surfaceY(int x, int z) {
                return 64;
            }
        };
        CoreConfig cfg = new CoreConfig();
        cfg.rivalKingdoms = 0;
        core = new KingdomsCore(new WorldState(), cfg);
        core.setWorld(flat);
        LlmConfig mock = new LlmConfig();
        mock.provider = "mock";
        core.llm().configure(mock);
        player = UUID.randomUUID();
        cli = new CommandService(core);
        cli.setNotifier((p, lines) -> async.addAll(lines));
        cli.execute(player, "Andraus", new Pos(0, 64, 0), "found Reino de Teste");
        Kingdom k = core.kingdomOfPlayer(player);
        Npc miner = first(k, Profession.MINER), smith = first(k, Profession.BLACKSMITH), farmer = first(k, Profession.FARMER);

        // --- 1. validação antes de existir
        ActionResult r = chain(k, "template", "minerar_ferreiro");
        print(r);
        check("sem forja → cadeia recusada (chain_invalid)", !r.ok() && r.code().equals("chain_invalid") && r.message().contains("forja"));

        complete(k, "smithy", 20, 0);
        complete(k, "storage", 20, 14);
        complete(k, "farm", -24, 0);

        r = chain(k, "steps", "[{\"role\":\"ferreiro\",\"type\":\"STORE\",\"item\":\"iron_ingot\"},{\"role\":\"ferreiro\",\"type\":\"SMELT\",\"amount\":\"4\"}]");
        print(r);
        check("guardar antes de fundir → 'não terá na mão'", !r.ok() && r.message().contains("não terá"));
        r = chain(k, "steps", "[{\"role\":\"minerador\",\"type\":\"MINE\",\"params\":{\"item\":\"raw_iron\",\"amount\":\"4\"}},"
                + "{\"role\":\"minerador\",\"type\":\"DELIVER\",\"params\":{\"item\":\"raw_iron\",\"to\":\"smithy\"}},"
                + "{\"role\":\"ferreiro\",\"type\":\"SMELT\",\"params\":{\"amount\":\"4\"}},{\"role\":\"ferreiro\",\"type\":\"STORE\"}]");
        print(r);
        check("ninguém leva carvão à forja → recusada", !r.ok() && r.message().toLowerCase().contains("carvão"));
        r = chain(k, "steps", "[{\"role\":\"fazendeiro\",\"type\":\"HARVEST\"},{\"role\":\"fazendeiro\",\"type\":\"STORE\"}]");
        check("colher sem plantar → recusada", !r.ok() && r.message().contains("PLANT"));
        r = chain(k, "template", "minerar_ferreiro", "ferreiro", farmer.name);
        print(r);
        check("fazendeiro no papel de ferreiro → não sabe fundir", !r.ok() && r.message().contains("não sabe fazer"));
        r = chain(k, "template", "escrever", "npc", farmer.name);
        check("escrever sem biblioteca → recusada", !r.ok() && r.message().contains("biblioteca"));

        // --- 2. o rei fala com o minerador: ele adota a rotina daqui em diante
        say(miner, "Daqui pra frente minere ferro e leve para o ferreiro derreter e guardar no baú.");
        WorkChain iron = core.state().chains.values().stream().filter(c -> c.name.startsWith("Ferro")).findFirst().orElse(null);
        check("conversa vira cadeia (CHAIN)", iron != null && async.stream().anyMatch(s -> s.startsWith("✓ CHAIN")));
        check("minerador e ferreiro adotaram a rotina", iron != null && iron.id.equals(miner.dutyChainId) && iron.id.equals(smith.dutyChainId));
        check("o NPC prometeu em português", async.stream().anyMatch(s -> s.contains("Daqui em diante vou cuidar do ferro")));

        double ironBefore = k.get(ResourceType.IRON);
        boolean sawWaiting = false, sawCarry = false, smithyGotOre = false;
        for (int s = 0; s < 260 && iron.cycles == 0; s++) {
            seconds(1);
            WorkChain.Role sr = iron.roles.get("ferreiro");
            sawWaiting |= sr.state == WorkChain.DutyState.WAITING && sr.status.contains("ferro bruto");
            sawCarry |= miner.carrying.getOrDefault(Item.RAW_IRON, 0) > 0;
            Building sm = ChainValidator.findBuilding(core, k, Place.SMITHY, true);
            smithyGotOre |= sm.inventory.getOrDefault(Item.RAW_IRON, 0) > 0;
        }
        print(core.work().describe(iron));
        check("minerador carregou ferro bruto na mão", sawCarry);
        check("ferreiro ESPEROU o ferro (não quebrou)", sawWaiting);
        check("o ferro chegou ao baú da forja", smithyGotOre);
        check("ciclo completo: barras guardadas no armazém", iron.cycles >= 1 && k.get(ResourceType.IRON) >= ironBefore + 7.9);

        // --- 3. quebra: o ferreiro morre no meio do trabalho
        seconds(20);
        WorkChain.Role smithRole = iron.roles.get("ferreiro"), minerRole = iron.roles.get("minerador");
        int smithCursor = smithRole.cursor, minerCursor = minerRole.cursor;
        smith.alive = false;
        core.bus().publish(core.tick(), EventType.NPC_DIED, GameEvent.Severity.WARN, k.id, smith.id, smith.name + " morreu.");
        seconds(1);
        check("ferreiro morreu → cadeia QUEBRADA com motivo", iron.status == WorkChain.Status.BROKEN && iron.brokenReason.contains(smith.name));
        seconds(WorkSystem.RETRY_SECONDS + 2);
        check("sem outro ferreiro → continua quebrada e diz por quê", iron.status == WorkChain.Status.BROKEN && iron.brokenReason.contains("ferreiro"));
        print(cli.execute(player, "Andraus", new Pos(0, 64, 0), "chain " + iron.number + " resume"));

        // --- 4. retomada: o rei designa um novo ferreiro
        Npc lumber = first(k, Profession.LUMBERJACK);
        print(cli.execute(player, "Andraus", new Pos(0, 64, 0), "npc job " + lumber.name + " ferreiro"));
        seconds(WorkSystem.RETRY_SECONDS + 2);
        check("novo ferreiro assumiu e a cadeia foi RETOMADA", iron.status == WorkChain.Status.ACTIVE
                && iron.roles.get("ferreiro").npcId.equals(lumber.id) && iron.id.equals(lumber.dutyChainId));
        String stoppedAt = iron.log.stream().filter(l -> l.contains("Parou em: ")).reduce((a, b) -> b).orElse("?").replaceAll(".*Parou em: ", "");
        String resumedAt = iron.log.stream().filter(l -> l.contains("Retomada de onde parou: ")).reduce((a, b) -> b).orElse("!").replaceAll(".*parou: ", "");
        System.out.println("    | parou em: " + stoppedAt + " / retomou em: " + resumedAt + " (cursores antes da quebra: ferreiro "
                + smithCursor + ", minerador " + minerCursor + ")");
        check("retomou exatamente da etapa onde parou", stoppedAt.equals(resumedAt));
        check("evento CHAIN_RESUMED publicado", !core.bus().log().recent(50, e -> e.type() == EventType.CHAIN_RESUMED).isEmpty());

        // --- 5. mudar de postura pelo chat: "pare com essa rotina"
        say(miner, "Pode parar com essa rotina.");
        check("STOP_CHAIN: o minerador largou a rotina", miner.dutyChainId == null && async.stream().anyMatch(s -> s.startsWith("✓ STOP_CHAIN")));
        check("a rotina do minerador foi encerrada", iron.status == WorkChain.Status.STOPPED);
        core.work().stop(iron, "teste");

        // --- 6. lavoura: plantar → esperar amadurecer → colher → guardar
        say(farmer, "Plante e colha o trigo daqui pra frente.");
        WorkChain farm = core.state().chains.get(farmer.dutyChainId);
        check("fazendeiro adotou o ciclo da lavoura", farm != null && farm.name.contains("lavoura"));
        boolean waitedGrowth = false;
        for (int s = 0; s < (int) (WorkSystem.GROW_TICKS / 20) + 120 && farm.cycles == 0; s++) {
            seconds(1);
            WorkChain.Role fr = farm.roles.values().iterator().next();
            waitedGrowth |= fr.state == WorkChain.DutyState.WAITING && fr.status.contains("amadurecer");
        }
        print(core.work().describe(farm));
        check("esperou o trigo amadurecer", waitedGrowth);
        check("colheu e guardou (ciclo completo)", farm.cycles >= 1 && farm.log.stream().anyMatch(l -> l.contains("colheu")));

        // --- 7. biblioteca: escrever e ler
        complete(k, "library", -24, 16);
        Npc scholar = core.citizens(k.id).stream().filter(n -> n.profession == Profession.BUILDER).findFirst().orElseThrow();
        print(cli.execute(player, "Andraus", new Pos(0, 64, 0), "npc job " + scholar.name + " estudioso"));
        farmer.traits.put(Trait.CURIOSITY, 10);
        Npc farmer2 = core.citizens(k.id).stream().filter(n -> n.profession == Profession.FARMER && n.dutyChainId == null).findFirst().orElseThrow();
        farmer2.traits.put(Trait.CURIOSITY, 10);
        r = chain(k, "template", "escrever", "npc", farmer2.name);
        check("fazendeiro iletrado não escreve", !r.ok() && r.message().contains("não sabe escrever"));
        say(scholar, "Escreva um livro sobre a mina de ferro.");
        WorkChain write = core.state().chains.get(scholar.dutyChainId);
        check("estudioso foi escrever", write != null && !write.repeat);
        for (int s = 0; s < 200 && write.status == WorkChain.Status.ACTIVE; s++) seconds(1);
        Document book = core.state().documents.values().stream().filter(d -> d.kind == Document.Kind.BOOK).findFirst().orElse(null);
        check("livro escrito e tarefa concluída", write.status == WorkChain.Status.DONE && book != null && book.title.contains("mina"));
        if (book != null) System.out.println(book.text);
        Npc reader = core.advisor().advisorNpc(k);
        print(cli.execute(player, "Andraus", new Pos(0, 64, 0), "chain new ler npc=" + reader.name));
        for (int s = 0; s < 200 && reader.dutyChainId != null; s++) seconds(1);
        check("conselheiro leu o livro e lembra dele", book != null && book.readers.contains(reader.id)
                && reader.memories.stream().anyMatch(m -> m.text().startsWith("Li «")));

        // --- 8. carta em mãos
        say(scholar, "Escreva uma carta para " + farmer.name + " dizendo que a colheita foi boa");
        WorkChain letter = core.state().chains.get(scholar.dutyChainId);
        check("carta virou cadeia de 2 etapas", letter != null && letter.steps.size() == 2);
        for (int s = 0; s < 200 && letter != null && letter.status == WorkChain.Status.ACTIVE; s++) seconds(1);
        check("carta entregue e lembrada pelo destinatário", letter != null && letter.status == WorkChain.Status.DONE
                && farmer.memories.stream().anyMatch(m -> m.text().contains("carta") && m.text().contains("colheita foi boa")));

        // --- 9. ordem com "faça ... forja" não vira construção
        async.clear();
        Npc newMiner = first(k, Profession.MINER);
        say(newMiner, "Faça o trabalho: minere e entregue na forja para o ferreiro.");
        check("rotina com 'forja' não constrói uma forja", async.stream().noneMatch(s -> s.startsWith("✓ BUILD")));

        // --- 10. save/load
        Path tmp = Files.createTempDirectory("kai-work").resolve("kingdomsai.json");
        Persistence.save(core.snapshotForSave(), tmp);
        WorldState loaded = Persistence.load(tmp);
        check("save/load preserva cadeias, livros e baús", loaded.chains.size() == core.state().chains.size()
                && loaded.documents.size() == core.state().documents.size()
                && loaded.chains.get(farm.id).steps.get(0).type == StepType.PLANT
                && loaded.schemaVersion == WorldState.SCHEMA_VERSION);
        KingdomsCore core2 = new KingdomsCore(loaded, cfg);
        core2.setWorld(flat);
        for (int i = 0; i < 20 * 30; i++) core2.step();
        check("cadeias continuam rodando após carregar", core2.state().chains.get(farm.id).live());

        print(cli.execute(player, "Andraus", new Pos(0, 64, 0), "chains"));
        print(cli.execute(player, "Andraus", new Pos(0, 64, 0), "books"));
        return new int[]{passed, failed};
    }

    // ------------------------------------------------------------------ util

    private static ActionResult chain(Kingdom k, String... kv) {
        return core.actions().execute(ActionRequest.of(k.id, player, ActionRequest.ActorKind.PLAYER, ActionType.CHAIN, ActionRequest.Source.TEST, kv));
    }

    private static void say(Npc n, String text) {
        async.clear();
        cli.execute(player, "Andraus", new Pos(0, 64, 0), "npc talk " + n.name + " " + text);
        print(async);
    }

    private static void seconds(int s) {
        for (int i = 0; i < s * 20; i++) core.step();
    }

    private static Npc first(Kingdom k, Profession p) {
        return core.citizens(k.id).stream().filter(n -> n.profession == p).findFirst().orElseThrow();
    }

    /** Prédio pronto na hora (o teste não espera os construtores). */
    private static void complete(Kingdom k, String id, int dx, int dz) {
        Blueprint bp = BlueprintLibrary.get(id);
        Building b = core.construction().planAt(k, bp, k.center.offset(dx, 0, dz), true);
        if (b == null) throw new IllegalStateException("sem espaço para " + id);
        b.status = Building.Status.COMPLETE;
        b.progress = bp.blockCount();
        b.placed = b.progress;
        b.completedTick = core.tick();
        core.bus().publish(core.tick(), EventType.BUILDING_COMPLETED, GameEvent.Severity.GOOD, k.id, null, bp.displayName() + " pronta.");
        core.bus().dispatch();
    }

    private static void check(String name, boolean ok) {
        if (ok) passed++;
        else failed++;
        System.out.println((ok ? "  [OK]   " : "  [FAIL] ") + name);
    }

    private static void print(ActionResult r) {
        System.out.println("    | " + (r.ok() ? "✓ " : "✗ [" + r.code() + "] ") + r.message().replace("\n", "\n    | "));
    }

    private static void print(List<String> lines) {
        for (String l : lines) System.out.println("    | " + l);
    }
}
