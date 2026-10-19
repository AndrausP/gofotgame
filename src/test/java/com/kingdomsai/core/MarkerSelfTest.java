package com.kingdomsai.core;

import com.kingdomsai.core.cli.CommandService;
import com.kingdomsai.core.common.Pos;
import com.kingdomsai.core.event.EventType;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.kingdom.Marker;
import com.kingdomsai.core.kingdom.ResourceType;
import com.kingdomsai.core.llm.LlmConfig;
import com.kingdomsai.core.npc.*;
import com.kingdomsai.core.persistence.Persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Bandeira do Reino: marcar spawn/praça/mina/bosque, validação do lugar e efeito nos súditos. */
public final class MarkerSelfTest {
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
        System.out.println("\n# Bandeira do Reino (marcos)");
        SkillSelfTest.FakeWorld world = new SkillSelfTest.FakeWorld();
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

        // --- 1. a bandeira manda "mark <tipo> x y z"
        List<String> out = cli.execute(player, "Andraus", new Pos(0, 64, 0), "mark spawn 12 64 -8");
        print(out);
        check("spawn marcado", out.get(0).startsWith("✓") && new Pos(12, 64, -8).equals(k.markers.get(Marker.SPAWN)));
        check("evento MARKER_SET com coordenadas (o adaptador põe o renascer do rei ali)",
                core.bus().log().recent(5, e -> e.type() == EventType.MARKER_SET).stream().anyMatch(e -> "12".equals(e.data("x")) && "SPAWN".equals(e.data("kind"))));
        out = cli.execute(player, "Andraus", new Pos(0, 64, 0), "mark spawn 500 64 500");
        check("fora do território → recusado", out.get(0).contains("outside_territory"));
        world.set(new Pos(5, 63, 5), "minecraft:water");
        out = cli.execute(player, "Andraus", new Pos(0, 64, 0), "mark spawn 5 64 5");
        check("em cima de água → recusado", out.get(0).contains("unsafe") && out.get(0).contains("água"));
        out = cli.execute(player, "Andraus", new Pos(0, 64, 0), "mark spawn 6 70 6");
        check("no ar (sem chão) → recusado", out.get(0).contains("chão firme"));
        world.set(new Pos(7, 65, 7), "minecraft:stone");
        out = cli.execute(player, "Andraus", new Pos(0, 64, 0), "mark spawn 7 64 7");
        check("sem 2 blocos livres → recusado", out.get(0).contains("espaço"));
        out = cli.execute(player, "Andraus", new Pos(0, 64, 0), "mark mina 7 64 7");
        check("mina pode ser em lugar apertado (é onde se cava)", out.get(0).startsWith("✓"));

        // --- 2. efeitos nos súditos
        k.stock.put(ResourceType.FOOD, 5000.0);
        for (int i = 0; i < 6; i++) core.construction().planAt(k, com.kingdomsai.core.construction.BlueprintLibrary.get("house_medium"),
                new Pos(40 + i * 12, 64, 40), true).status = com.kingdomsai.core.construction.Building.Status.COMPLETE;
        int before = core.population(k.id);
        for (int i = 0; i < 20 * 60 * 6 && core.population(k.id) <= before; i++) core.step(); // espera um morador NOVO (alguém pode ter ido embora)
        Npc newcomer = core.citizens(k.id).stream().max(Comparator.comparingLong(n -> n.bornTick)).orElseThrow();
        check("novo morador chega no spawn marcado", core.population(k.id) > before && newcomer.pos.distXZ(new Pos(12, 64, -8)) <= 6);
        cli.execute(player, "Andraus", new Pos(0, 64, 0), "mark praca 20 64 20");
        Npc farmer = core.citizens(k.id).stream().filter(n -> n.profession == Profession.FARMER).findFirst().orElseThrow();
        farmer.intention = new com.kingdomsai.core.life.Intention(); // sem plano próprio para a tarde: vai para a praça
        farmer.talkingWith = null;
        check("fim de tarde: súditos vão para a praça marcada", core.scheduler().decide(farmer, 11500).target().distXZ(new Pos(20, 64, 20)) <= 8
                && com.kingdomsai.core.life.Places.plaza(k, farmer).distXZ(new Pos(20, 64, 20)) <= 8);
        Npc miner = core.citizens(k.id).stream().filter(n -> n.profession == Profession.MINER).findFirst().orElseThrow();
        check("minerador vai trabalhar na mina marcada", core.scheduler().decide(miner, 6000).target().distXZ(new Pos(7, 64, 7)) <= 6);
        check("cadeias usam a mina marcada", core.work().natureSpot(k, 0).equals(new Pos(7, 64, 7)));

        // --- 3. linguagem natural, listagem, remover, save
        core.updatePlayerPos(player, new Pos(-15, 64, 15));
        core.updatePlayerLook(player, new Pos(-15, 63, 15), "south");
        async.clear();
        cli.execute(player, "Andraus", new Pos(-15, 64, 15), "order marque aqui como o bosque");
        print(async);
        check("'marque aqui como o bosque' marca em cima do bloco da mira", new Pos(-15, 64, 15).equals(k.markers.get(Marker.FOREST)));
        out = cli.execute(player, "Andraus", new Pos(0, 64, 0), "marks");
        print(out);
        check("/k marks lista os 4 marcos", out.size() == 5 && out.stream().noneMatch(l -> l.contains("não marcado")));
        cli.execute(player, "Andraus", new Pos(0, 64, 0), "mark praca remover");
        check("remover a praça volta ao padrão", !k.markers.containsKey(Marker.GATHER));
        Path tmp = Files.createTempDirectory("kai-mark").resolve("kingdomsai.json");
        Persistence.save(core.snapshotForSave(), tmp);
        WorldState loaded = Persistence.load(tmp);
        check("save/load preserva os marcos", loaded.kingdoms.get(k.id).markers.get(Marker.SPAWN).equals(new Pos(12, 64, -8)));
        return new int[]{passed, failed};
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
