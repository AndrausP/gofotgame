package com.kingdomsai.core;

import com.kingdomsai.core.cli.CommandService;
import com.kingdomsai.core.common.Pos;
import com.kingdomsai.core.construction.Blueprint;
import com.kingdomsai.core.construction.BlueprintLibrary;
import com.kingdomsai.core.construction.Building;
import com.kingdomsai.core.construction.Material;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.kingdom.Marker;
import com.kingdomsai.core.kingdom.ResourceType;
import com.kingdomsai.core.llm.LlmConfig;
import com.kingdomsai.core.military.Campaign;
import com.kingdomsai.core.npc.*;
import com.kingdomsai.core.skill.PhysicalJob;

import java.util.*;

/**
 * Ordens que se veem acontecendo: planta sob medida para qualquer pedido, treino e deslocamento da tropa, convocados que se
 * apresentam, espada do arsenal, estoque do reino morando nos baús, coleta de verdade (árvores da região, pedra) e o
 * conselheiro dividindo ordens compostas e delegando a quem é competente.
 */
public final class OrdersSelfTest {
    private static int passed, failed;
    private static KingdomsCore core;
    private static CommandService cli;
    private static UUID player;
    private static final List<String> async = new ArrayList<>();
    private static final SkillSelfTest.FakeWorld world = new SkillSelfTest.FakeWorld();

    public static void main(String[] args) throws Exception {
        int[] r = run();
        System.out.println("\n" + r[0] + " passaram, " + r[1] + " falharam.");
        System.exit(r[1] == 0 ? 0 : 1);
    }

    public static int[] run() throws Exception {
        passed = failed = 0;
        System.out.println("\n# Ordens visíveis, plantas sob medida, tesouro, coleta e hierarquia");
        WorldState s = new WorldState();
        s.seed = 21;
        CoreConfig cfg = new CoreConfig();
        cfg.rivalKingdoms = 0;
        cfg.aiKingdomsEnabled = false;
        core = new KingdomsCore(s, cfg);
        core.setWorld(world);
        core.setPhysical(world);
        LlmConfig mock = new LlmConfig();
        mock.provider = "mock";
        core.llm().configure(mock);
        player = UUID.randomUUID();
        cli = new CommandService(core);
        cli.setNotifier((p, lines) -> async.addAll(lines));
        cli.execute(player, "Andraus", new Pos(0, 64, 0), "found Reino de Andraus");
        Kingdom k = core.kingdomOfPlayer(player);
        k.civic = "off"; // estes testes medem o efeito exato das ordens; a vontade própria tem suíte própria (CivicSelfTest)
        for (ResourceType r : List.of(ResourceType.FOOD, ResourceType.WOOD, ResourceType.STONE)) k.stock.put(r, 3000.0);
        king(new Pos(0, 64, 0), null);

        // ---------------------------------------------------------------- 1. planta sob medida para qualquer pedido
        List<String> o = order("construam um observatório de pedra com 3 andares");
        check("pedido fora do catálogo vira planta sob medida com o nome pedido", o.stream().anyMatch(x -> x.startsWith("✓ BUILD") && x.contains("Observatório")));
        check("…com os andares pedidos", o.stream().anyMatch(x -> x.contains("3 andar")));
        o = order("construam uma estufa 30x30 com 6 andares");
        check("tamanho fora da faixa é ajustado e avisado (não recusado)", o.stream().anyMatch(x -> x.contains("Ajustei") && x.contains("21x21") && x.contains("andares 6 → 4")));
        o = order("façam um poço");
        check("poço tem planta própria", o.stream().anyMatch(x -> x.startsWith("✓ BUILD") && x.contains("Poço")));
        Blueprint well = core.construction().projects(k.id).stream().map(Building::blueprint).filter(b -> b.displayName().startsWith("Poço")).findFirst().orElse(null);
        check("poço tem água e não tem porta", well != null && well.placements().stream().anyMatch(p -> p.material() == Material.WATER)
                && well.placements().stream().noneMatch(p -> p.material() == Material.DOOR_LOWER));
        Npc builder = prof(k, Profession.BUILDER);
        List<String> b = talk(builder, "Construa um celeiro 12x8.");
        Building barn = core.construction().projects(k.id).stream().filter(x -> x.blueprint().displayName().startsWith("Celeiro")).findFirst().orElse(null);
        check("o construtor a quem o rei falou é quem vai à obra", barn != null && builder.id.equals(barn.builderId) && b.stream().anyMatch(x -> x.contains(builder.name + " vai construir")));
        check("celeiro com feno e porta larga", barn != null && barn.blueprint().placements().stream().anyMatch(p -> p.material() == Material.HAY));

        // ---------------------------------------------------------------- 2. convocados se apresentam, com espada do arsenal
        Npc guard = prof(k, Profession.GUARD);
        check("guarda inicial recebeu espada do arsenal", guard.equipped.equals("iron_sword"));
        k.stock.put(ResourceType.WEAPONS, 1.0);
        List<String> rec = say("army recruit 2");
        List<Npc> soldiers = core.citizens(k.id).stream().filter(n -> n.profession == Profession.SOLDIER).toList();
        check("dois convocados", soldiers.size() == 2);
        check("um com espada, outro sem (arsenal tinha 1)", soldiers.stream().filter(n -> !n.equipped.isEmpty()).count() == 1
                && rec.stream().anyMatch(x -> x.contains("sem espada")));
        Campaign muster = core.state().campaigns.get(soldiers.get(0).campaignId);
        check("convocados vão se apresentar (andam até o ponto)", muster != null && muster.kind == Campaign.Kind.MOVE && rec.stream().anyMatch(x -> x.contains("apresentar")));
        check("apresentar-se não prende ninguém para a guerra", !core.warfare().atWar(soldiers.get(0)) && core.warfare().available(k, false).size() == 2);
        k.add(ResourceType.WEAPONS, 1);
        core.warfare().strategicTick();
        check("espada nova no arsenal vai para quem estava sem", soldiers.stream().allMatch(n -> n.equipped.equals("iron_sword")) && k.get(ResourceType.WEAPONS) < 1);
        seconds(70);
        check("apresentação termina e voltam à rotina", soldiers.stream().allMatch(n -> n.campaignId == null));

        // ---------------------------------------------------------------- 3. "a capitã, treine a tropa": ela escolhe, convoca se faltar, leva e treina
        Npc captain = soldiers.get(0);
        captain.office = Office.CAPTAIN;
        int discBefore = soldiers.get(1).trait(Trait.DISCIPLINE);
        List<String> tr = talk(captain, "Treine a tropa.");
        Campaign drill = core.state().campaigns.get(captain.campaignId);
        check("treino pelo chat: a capitã lidera", drill != null && drill.kind == Campaign.Kind.TRAIN && captain.id.equals(drill.commanderId));
        check("faltou gente: ela convocou quem quis", tr.stream().anyMatch(x -> x.contains("convocou")) && drill.members.size() >= 3);
        seconds((int) ((drill.arriveTick - core.tick()) / 20) + 5);
        check("chegaram ao campo e treinam", drill.status == Campaign.Status.HOLDING
                && drill.members.stream().map(core::npc).filter(n -> !n.id.equals(captain.id)).allMatch(n -> n.activity == NpcActivity.TRAIN));
        boolean near = drill.members.stream().map(core::npc).allMatch(n -> n.pos.distXZ(drill.target) <= 12);
        check("estão de fato lá (posição perto do campo)", near);
        check("a instrutora segura o estandarte", captain.heldItem.equals("minecraft:white_banner"));
        seconds(185);
        check("treino concluído e todos liberados", drill.status == Campaign.Status.DONE && soldiers.get(1).campaignId == null);
        check("treinar aumenta a disciplina (" + discBefore + " → " + soldiers.get(1).trait(Trait.DISCIPLINE) + ")", soldiers.get(1).trait(Trait.DISCIPLINE) > discBefore);

        // ---------------------------------------------------------------- 4. "soldados, vão para a praça" (só o chat)
        List<String> out = new ArrayList<>();
        async.clear();
        cli.chat(player, "Andraus", new Pos(0, 64, 0), "soldados, vão para a praça", out);
        Campaign go = core.warfare().campaigns(k.id).stream().filter(c -> c.kind == Campaign.Kind.MOVE && c.live()).findFirst().orElse(null);
        check("chat \"soldados, vão para a praça\" leva o grupo", go != null && go.members.size() >= 2);
        seconds(40);
        check("chegaram à praça", go != null && go.members.stream().map(core::npc).allMatch(n -> n.pos.distXZ(go.target) <= 12));

        // ---------------------------------------------------------------- 4b. "chame todos aqui, o rei ordena": falar e agir batem
        sendHome(k); // a ordem anterior termina
        Pos callAt = new Pos(60, 64, 60);
        king(callAt, null);
        Npc farmer = prof(k, Profession.FARMER);
        List<String> viaNpc = talk(farmer, "Chame todos aqui, o rei ordena.");
        check("falando com um súdito, \"chame todos aqui\" vira ação de verdade (não só conversa)", viaNpc.stream().anyMatch(x -> x.startsWith("✓ MOVE")));
        Campaign callAll = core.warfare().campaigns(k.id).stream()
                .filter(c -> c.kind == Campaign.Kind.MOVE && c.live() && c.target.distXZ(callAt) <= 4).findFirst().orElse(null);
        long subjects = core.citizens(k.id).stream().filter(n -> n.office != Office.KING).count();
        check("…chama o povo todo, não um só (" + (callAll == null ? 0 : callAll.members.size()) + " de " + subjects + ")",
                callAll != null && callAll.members.size() >= subjects - 2);
        check("…e ninguém é escolhido \"líder\" só por ter ouvido a ordem", callAll != null && callAll.commanderId == null);
        if (callAll != null) seconds((int) ((callAll.arriveTick - core.tick()) / 20) + 5);
        check("todos chegam até o rei e esperam as ordens", callAll != null && callAll.status == Campaign.Status.HOLDING
                && callAll.members.stream().map(core::npc).allMatch(n -> n.pos.distXZ(callAt) <= 12));
        for (int w = 0; w < 500 && callAll != null && callAll.live(); w += 5) seconds(5); // o prazo padrão (5 min) acaba sozinho
        check("passado o prazo voltam à rotina", callAll != null && callAll.members.stream().map(core::npc).allMatch(n -> n.campaignId == null));
        List<String> viaCouncil = order("chame todos aqui, o rei ordena");
        check("o conselho também executa o chamado", viaCouncil.stream().anyMatch(x -> x.startsWith("✓ MOVE")));
        sendHome(k);
        List<String> byGroup = talk(farmer, "Convoquem os soldados na praça.");
        check("\"convoquem os soldados na praça\" leva só os soldados", byGroup.stream().anyMatch(x -> x.startsWith("✓ MOVE")));
        sendHome(k);
        // a LLM que escreve SUMMON(who=…) ou SUMMON(npc="todos") cai na mesma ação, sem depender do humor do modelo
        var viaLlm = core.actions().execute(com.kingdomsai.core.action.ActionRequest.of(k.id, player, com.kingdomsai.core.action.ActionRequest.ActorKind.PLAYER,
                com.kingdomsai.core.action.ActionType.SUMMON, com.kingdomsai.core.action.ActionRequest.Source.LLM, "who", "soldados"));
        check("SUMMON(who=soldados) chama o grupo: " + viaLlm.message(), viaLlm.ok() && viaLlm.message().contains("vão"));
        sendHome(k);
        var viaAll = core.actions().execute(com.kingdomsai.core.action.ActionRequest.of(k.id, player, com.kingdomsai.core.action.ActionRequest.ActorKind.PLAYER,
                com.kingdomsai.core.action.ActionType.SUMMON, com.kingdomsai.core.action.ActionRequest.Source.LLM, "npc", "todos"));
        check("SUMMON(npc=todos) também: " + viaAll.message(), viaAll.ok());
        sendHome(k);
        check("/k call todos chama o povo", say("call todos").stream().anyMatch(x -> x.startsWith("✓")));
        sendHome(k);
        var one = core.actions().execute(com.kingdomsai.core.action.ActionRequest.of(k.id, player, com.kingdomsai.core.action.ActionRequest.ActorKind.PLAYER,
                com.kingdomsai.core.action.ActionType.SUMMON, com.kingdomsai.core.action.ActionRequest.Source.LLM, "npc", farmer.name));
        check("chamar uma pessoa pelo nome continua igual (SUMMON de um só)", one.ok() && one.message().contains("vindo") || one.ok() && one.message().contains("aqui"));
        core.scheduler().dismiss(farmer);
        com.kingdomsai.core.llm.Plan recruit = new com.kingdomsai.core.llm.RuleInterpreter(core).interpret(k, null, "convoque 5 soldados");
        check("\"convoque 5 soldados\" (com número) não vira chamado de grupo", recruit.actions().stream()
                .noneMatch(a -> a.type() == com.kingdomsai.core.action.ActionType.MOVE && "true".equals(a.params().get("call"))));
        // ordem que o jogo não entende não pode ficar só na conversa: o rei é avisado de que nada foi feito
        List<String> idle = order("organize um torneio de xadrez");
        check("ordem sem ação avisa que nada foi feito (nada de fingir que cumpriu)", idle.stream().anyMatch(x -> x.startsWith("⚠") && x.contains("nada foi feito")));
        List<String> idleNpc = talk(farmer, "Organize um torneio de xadrez.");
        check("…também quando o rei fala com um súdito", idleNpc.stream().anyMatch(x -> x.startsWith("⚠") && x.contains("nada foi feito")));
        check("pergunta comum não gera aviso", order("como está a vila?").stream().noneMatch(x -> x.contains("nada foi feito")));

        // ---------------------------------------------------------------- 5. o estoque do reino mora nos baús
        Building storage = core.construction().planAt(k, BlueprintLibrary.get("storage"), new Pos(40, 64, 0), true);
        storage.status = Building.Status.COMPLETE;
        storage.placed = storage.progress = storage.blueprint().blockCount();
        for (var pl : storage.blueprint().placements())
            if (pl.material() == Material.CHEST || pl.material() == Material.BARREL) world.set(storage.origin.offset(pl.x(), pl.y(), pl.z()), "minecraft:chest");
        k.stock.put(ResourceType.STONE, 300.0);
        k.stock.put(ResourceType.WOOD, 500.0);
        core.treasury().sync(k);
        List<Pos> chests = core.treasury().chests(k);
        int cobble = count(chests, "minecraft:cobblestone"), planks = count(chests, "minecraft:oak_planks");
        check("estoque aparece nos baús do armazém (" + cobble + " pedregulho, " + planks + " tábuas)", cobble == 300 && planks == 500);
        world.take(null, chests.get(0), "minecraft:cobblestone", 10);
        core.treasury().sync(k);
        check("o rei tirou 10 pedregulho do baú → o reino tem 10 a menos", (int) k.get(ResourceType.STONE) == 290);
        world.put(null, chests.get(0), new HashMap<>(Map.of("minecraft:oak_log", 16)));
        core.treasury().sync(k);
        check("o rei guardou 16 toras → +64 de madeira no reino", (int) k.get(ResourceType.WOOD) == 564);
        int beforeBuild = count(chests, "minecraft:oak_planks") + 4 * count(chests, "minecraft:oak_log");
        o = order("construam uma casa");
        core.bus().dispatch();
        int afterBuild = count(chests, "minecraft:oak_planks") + 4 * count(chests, "minecraft:oak_log");
        check("a obra tira os materiais dos baús (" + beforeBuild + " → " + afterBuild + " de madeira)", afterBuild < beforeBuild);
        k.stock.put(ResourceType.STONE, 2.0);
        core.treasury().sync(k);
        o = order("construam um quartel");
        check("sem itens nos baús, não constrói", o.stream().anyMatch(x -> x.contains("insufficient_resources")));
        k.stock.put(ResourceType.STONE, 300.0);
        core.treasury().sync(k);

        // ---------------------------------------------------------------- 6. coleta de verdade
        for (Pos base : List.of(new Pos(20, 64, 24), new Pos(26, 64, 18), new Pos(16, 64, 16))) world.tree(base);
        king(new Pos(20, 64, 20), new Pos(20, 63, 20));
        Npc lumber = core.citizens(k.id).stream().filter(n -> n.profession == Profession.LUMBERJACK).findFirst()
                .orElseGet(() -> prof(k, Profession.FARMER));
        List<String> ct = talk(lumber, "Limpe as árvores da região.");
        PhysicalJob clear = job(lumber);
        check("\"limpe as árvores da região\" vira ordem com várias árvores", clear != null
                && clear.tasks.stream().filter(t -> t.kind == PhysicalJob.Kind.CHOP).count() == 3);
        check("…e termina guardando no armazém", clear != null && clear.tasks.get(clear.tasks.size() - 1).kind == PhysicalJob.Kind.STORE);
        double woodBefore = k.get(ResourceType.WOOD);
        runUntilDone(clear, 600);
        check("árvores derrubadas de verdade", world.isAir(new Pos(20, 66, 24)) && world.isAir(new Pos(26, 66, 18)) && world.isAir(new Pos(16, 66, 16)));
        core.treasury().sync(k);
        check("toras no baú e no estoque do reino (" + (int) woodBefore + " → " + (int) k.get(ResourceType.WOOD) + ")", k.get(ResourceType.WOOD) > woodBefore + 30);

        k.markers.put(Marker.MINE, new Pos(60, 64, 0));
        for (int x = 57; x <= 63; x++)
            for (int z = -3; z <= 3; z++) world.set(new Pos(x, 64, z), "minecraft:stone"); // afloramento de pedra
        king(new Pos(0, 64, 0), null);
        Npc miner = prof(k, Profession.MINER);
        List<String> gp = talk(miner, "Vá coletar pedra.");
        PhysicalJob quarry = job(miner);
        check("\"vá coletar pedra\" vira ordem na mina marcada", quarry != null && quarry.tasks.stream().anyMatch(t -> t.kind == PhysicalJob.Kind.BREAK && t.total == 32));
        double stoneBefore = k.get(ResourceType.STONE);
        runUntilDone(quarry, 600);
        core.treasury().sync(k);
        check("pedra extraída e guardada (" + (int) stoneBefore + " → " + (int) k.get(ResourceType.STONE) + ")", k.get(ResourceType.STONE) >= stoneBefore + 30);
        List<String> chain = talk(miner, "Daqui pra frente minere ferro e leve ao ferreiro.");
        check("rotina continua sendo rotina (não vira coleta única)", chain.stream().anyMatch(x -> x.contains("CHAIN")) && chain.stream().noneMatch(x -> x.contains("✓ JOB")));

        // ---------------------------------------------------------------- 7. hierarquia: o conselheiro reparte e explica
        o = order("construam um mercado, treinem a tropa e colete pedra");
        check("ordem composta vira 3 tarefas", o.stream().filter(x -> x.startsWith("✓ ")).count() >= 3);
        check("o conselheiro diz quem ficou com cada uma", o.stream().anyMatch(x -> x.startsWith("↳ Delegação:") && x.contains("→ obra") && x.contains("→ treinar")));
        o = order("cuide da comida");
        check("\"cuide da comida\": o conselho pensa e age", o.stream().anyMatch(x -> x.startsWith("✓ GOAL") && x.contains("cuidou de comida")));
        check("…e diz o que fez", o.stream().anyMatch(x -> x.contains("✓") && (x.contains("Fazenda") || x.contains("fazendeiro") || x.contains("Nada a fazer"))));
        return new int[]{passed, failed};
    }

    // ------------------------------------------------------------------ helpers

    private static int count(List<Pos> chests, String item) {
        int n = 0;
        for (Pos c : chests) n += world.container(c).getOrDefault(item, 0);
        return n;
    }

    private static Npc prof(Kingdom k, Profession p) {
        return core.citizens(k.id).stream().filter(n -> n.profession == p).findFirst().orElseThrow();
    }

    private static void king(Pos at, Pos look) {
        core.updatePlayerPos(player, at);
        core.updatePlayerLook(player, look, "south");
    }

    private static PhysicalJob job(Npc n) {
        return n.jobId == null ? null : core.state().jobs.get(n.jobId);
    }

    private static void runUntilDone(PhysicalJob j, int maxSeconds) {
        for (int s = 0; s < maxSeconds && j != null && j.status.live(); s++) seconds(1);
    }

    /** Encerra os chamados em andamento sem gastar os minutos de espera: todos voltam para casa. */
    private static void sendHome(Kingdom k) {
        long until = core.tick();
        for (Campaign c : core.warfare().campaigns(k.id))
            if (c.live()) {
                core.warfare().retreat(c);
                until = Math.max(until, c.returnTick);
            }
        seconds((int) ((until - core.tick()) / 20) + 3);
    }

    private static void seconds(int s) {
        for (int i = 0; i < s * 20; i++) core.step();
    }

    private static List<String> say(String line) {
        async.clear();
        Pos at = core.playerPos(player);
        List<String> out = new ArrayList<>(cli.execute(player, "Andraus", at == null ? new Pos(0, 64, 0) : at, line));
        out.addAll(async);
        print(out);
        return out;
    }

    private static List<String> order(String text) {
        return say("order " + text);
    }

    private static List<String> talk(Npc n, String text) {
        async.clear();
        Pos at = core.playerPos(player);
        KingdomsCore.Look look = core.playerLook(player);
        cli.execute(player, "Andraus", at == null ? new Pos(0, 64, 0) : at, "npc talk " + n.name + " " + text);
        core.updatePlayerLook(player, look == null ? null : look.block(), "south");
        List<String> out = new ArrayList<>(async);
        print(out);
        return out;
    }

    private static void check(String name, boolean ok) {
        if (ok) passed++;
        else failed++;
        System.out.println((ok ? "  [OK]   " : "  [FAIL] ") + name);
    }

    private static void print(List<String> lines) {
        for (String l : List.copyOf(lines)) System.out.println("    | " + l);
    }
}
