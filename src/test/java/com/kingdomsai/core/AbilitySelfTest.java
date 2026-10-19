package com.kingdomsai.core;

import com.kingdomsai.core.cli.CommandService;
import com.kingdomsai.core.common.Pos;
import com.kingdomsai.core.construction.*;
import com.kingdomsai.core.event.EventType;
import com.kingdomsai.core.event.GameEvent;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.kingdom.ResourceType;
import com.kingdomsai.core.llm.LlmConfig;
import com.kingdomsai.core.npc.*;
import com.kingdomsai.core.port.WorldPort;

import java.util.*;

/** Chamar/seguir/dispensar NPCs e a muralha sob medida ("o NPC sabe o tamanho da vila"). */
public final class AbilitySelfTest {
    private static int passed, failed;
    private static KingdomsCore core;
    private static CommandService cli;
    private static UUID player;
    private static final List<String> async = new ArrayList<>();
    /** Relevo: um morro a leste (x > 30) para a muralha acompanhar. */
    private static long dayTime = 6000;

    public static void main(String[] args) {
        int[] r = run();
        System.out.println("\n" + r[0] + " passaram, " + r[1] + " falharam.");
        System.exit(r[1] == 0 ? 0 : 1);
    }

    public static int[] run() {
        passed = failed = 0;
        System.out.println("\n# Habilidades: chamar NPC e muralha da vila");
        WorldPort hilly = new WorldPort() {
            public SiteCheck checkSite(int x, int z, Blueprint bp) {
                return new SiteCheck(SiteCheck.Kind.OK, 64, 1.0);
            }

            public long dayTime() {
                return dayTime;
            }

            public boolean isLoaded(int x, int z) {
                return true;
            }

            public int surfaceY(int x, int z) {
                return x > 30 ? 67 : 64;
            }
        };
        CoreConfig cfg = new CoreConfig();
        cfg.rivalKingdoms = 0;
        core = new KingdomsCore(new WorldState(), cfg);
        core.setWorld(hilly);
        LlmConfig mock = new LlmConfig();
        mock.provider = "mock";
        core.llm().configure(mock);
        player = UUID.randomUUID();
        cli = new CommandService(core);
        cli.setNotifier((p, lines) -> async.addAll(lines));
        cli.execute(player, "Andraus", new Pos(0, 64, 0), "found Reino de Teste");
        Kingdom k = core.kingdomOfPlayer(player);

        // --- 1. chamar no Manager (/k call) — o NPC anda até onde o rei está
        Npc farmer = core.citizens(k.id).stream().filter(n -> n.profession == Profession.FARMER).findFirst().orElseThrow();
        farmer.pos = new Pos(40, 64, 40);
        Pos king = new Pos(-20, 80, -10); // câmera do Manager no ar: vale o x/z
        print(cli.execute(player, "Andraus", king, "call " + farmer.name));
        check("call: NPC atende ao chamado", core.scheduler().isSummoned(farmer));
        int steps = 0;
        while (steps++ < 60 && !farmer.summonArrived) seconds(1);
        check("NPC chegou até o rei (" + steps + " s)", farmer.summonArrived && farmer.pos.distXZ(king) <= 4);
        check("evento NPC_ARRIVED com fala", !core.bus().log().recent(20, e -> e.type() == EventType.NPC_ARRIVED).isEmpty());
        dayTime = 15000; // noite
        seconds(2);
        check("chamado vale até à noite (não foi dormir)", farmer.activity == NpcActivity.SUMMONED);
        dayTime = 6000;
        print(cli.execute(player, "Andraus", king, "dismiss " + farmer.name));
        seconds(1);
        check("dismiss: voltou à rotina", !core.scheduler().isSummoned(farmer) && farmer.activity != NpcActivity.SUMMONED);

        // --- 2. pelo chat: "venha aqui" e "me siga"
        Npc miner = core.citizens(k.id).stream().filter(n -> n.profession == Profession.MINER).findFirst().orElseThrow();
        say(miner, "Venha aqui, por favor.", new Pos(10, 64, 10));
        check("'venha aqui' vira SUMMON para quem ouviu", async.stream().anyMatch(s -> s.startsWith("✓ SUMMON")) && core.scheduler().isSummoned(miner));
        say(miner, "Me siga.", new Pos(10, 64, 10));
        check("'me siga' vira FOLLOW", miner.following && async.stream().anyMatch(s -> s.startsWith("✓ FOLLOW")));
        core.updatePlayerPos(player, new Pos(60, 64, -30));
        for (int i = 0; i < 40 && miner.pos.distXZ(new Pos(60, 64, -30)) > 4; i++) seconds(1);
        check("seguindo: acompanha o rei quando ele anda", miner.pos.distXZ(new Pos(60, 64, -30)) <= 4);
        say(miner, "Pode ir, está dispensado.", new Pos(60, 64, -30));
        check("'pode ir' vira DISMISS", !core.scheduler().isSummoned(miner));
        miner.pos = new Pos(900, 64, 900);
        var far = cli.execute(player, "Andraus", new Pos(0, 64, 0), "call " + miner.name);
        print(far);
        check("longe demais é recusado com a distância", far.get(0).contains("too_far"));

        // --- 3. o reino sabe o tamanho da vila
        var b0 = VillageWall.bounds(core, k);
        print(cli.execute(player, "Andraus", new Pos(0, 64, 0), "village"));
        check("vila tem tamanho mínimo em volta do centro", b0.width() >= 21 && b0.contains(k.center));
        Building house = core.construction().planAt(k, BlueprintLibrary.get("house_small"), k.center.offset(30, 0, 12), true);
        var b1 = VillageWall.bounds(core, k);
        check("casa nova amplia a vila (" + b0.width() + " → " + b1.width() + ")", b1.x1() >= house.origin.x() + 4 + VillageWall.MARGIN);
        String ctx = core.contextBuilder().npcDialogue(k, farmer, "olá").user();
        check("a IA recebe o tamanho da vila no contexto", ctx.contains("Vila: área de " + b1.width() + "×" + b1.depth()));

        // --- 4. "construa um muro ao redor da vila"
        k.stock.put(ResourceType.STONE, 5.0);
        async.clear();
        cli.execute(player, "Andraus", new Pos(0, 64, 0), "order construa um muro ao redor da vila");
        print(async);
        check("sem pedra suficiente → recusada com a conta", async.stream().anyMatch(s -> s.contains("insufficient_resources") && s.contains("Pedra")));
        k.stock.put(ResourceType.STONE, 2000.0);
        async.clear();
        cli.execute(player, "Andraus", new Pos(0, 64, 0), "order construa uma muralha alta ao redor da vila");
        print(async);
        Building wall = VillageWall.existing(core, k);
        check("muralha planejada como obra", wall != null && async.stream().anyMatch(s -> s.startsWith("✓ BUILD")));
        Blueprint bp = wall.blueprint();
        var vb = VillageWall.bounds(core, k);
        check("muralha cerca a vila inteira (" + bp.sizeX() + "×" + bp.sizeZ() + ")", bp.sizeX() == vb.width() && bp.sizeZ() == vb.depth()
                && wall.origin.x() == vb.x0() && wall.origin.z() == vb.z0());
        boolean everyBuildingInside = core.buildings(k.id).stream().filter(b -> b != wall)
                .allMatch(b -> b.origin.x() > vb.x0() && b.origin.z() > vb.z0()
                        && b.origin.x() + b.blueprint().sizeX() <= vb.x1() && b.origin.z() + b.blueprint().sizeZ() <= vb.z1());
        check("todas as construções ficam dentro do anel", everyBuildingInside);
        boolean onlyRing = bp.placements().stream().allMatch(p -> p.x() == 0 || p.z() == 0 || p.x() == bp.sizeX() - 1 || p.z() == bp.sizeZ() - 1);
        check("planta só ocupa o contorno (não derruba o miolo)", onlyRing);
        int gx = bp.sizeX() / 2;
        boolean gateOpen = bp.placements().stream().noneMatch(p -> p.x() == gx && p.z() == 0 && p.y() >= 0 && p.y() < 3 && p.material() != Material.AIR);
        check("portão norte aberto (3 de altura)", gateOpen);
        int maxY = bp.placements().stream().filter(p -> p.material() == Material.WALL).mapToInt(Blueprint.Placement::y).max().orElse(0);
        check("altura 'alta' = 5 e acompanha o morro a leste (topo y+" + maxY + ")", maxY == 3 + 4);
        check("muralha alta é cobrada em pedra", k.get(ResourceType.STONE) < 2000);
        var again = core.actions().execute(com.kingdomsai.core.action.ActionRequest.of(k.id, player,
                com.kingdomsai.core.action.ActionRequest.ActorKind.PLAYER, com.kingdomsai.core.action.ActionType.BUILD,
                com.kingdomsai.core.action.ActionRequest.Source.TEST, "blueprint", "muralha"));
        check("segunda muralha é recusada", !again.ok() && again.code().equals("already_exists"));

        // --- 5. dá para continuar construindo dentro da muralha
        Building inside = core.construction().plan(k, BlueprintLibrary.get("house_small"), null);
        check("casa nova ainda cabe (anel não bloqueia o miolo)", inside != null);
        check("anel colide com quem cruza o traçado", wall.overlaps(new Pos(vb.x0() - 2, 64, vb.z0() + 10), BlueprintLibrary.get("house_small"), 1));
        check("guarda patrulha a borda da vila", core.citizens(k.id).stream().filter(n -> n.profession == Profession.GUARD)
                .allMatch(n -> core.scheduler().decide(n, 6000).target().distXZ(vb.center(64)) >= 14));
        return new int[]{passed, failed};
    }

    private static void say(Npc n, String text, Pos at) {
        async.clear();
        cli.execute(player, "Andraus", at, "npc talk " + n.name + " " + text);
        print(async);
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
