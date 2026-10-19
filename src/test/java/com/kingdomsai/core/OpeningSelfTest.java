package com.kingdomsai.core;

import com.kingdomsai.core.ai.Opening;
import com.kingdomsai.core.cli.CommandService;
import com.kingdomsai.core.common.Pos;
import com.kingdomsai.core.event.EventType;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.kingdom.Marker;
import com.kingdomsai.core.kingdom.ResourceType;
import com.kingdomsai.core.llm.LlmConfig;
import com.kingdomsai.core.npc.Npc;
import com.kingdomsai.core.npc.Profession;
import com.kingdomsai.core.skill.PhysicalJob;

import java.util.*;

/**
 * Roteiro de início: um reino novo, sem o rei fazer nada, não pode perder no começo. Com o conselheiro automático,
 * ele garante a comida (fazenda/fazendeiros), o armazém e as casas; sem ele, a comida acaba — mas ninguém morre de fome
 * nos primeiros dias. Depois da carência, a fome mata de novo.
 */
public final class OpeningSelfTest {
    private static int passed, failed;

    public static void main(String[] args) throws Exception {
        int[] r = run();
        System.out.println("\n" + r[0] + " passaram, " + r[1] + " falharam.");
        System.exit(r[1] == 0 ? 0 : 1);
    }

    record Game(KingdomsCore core, CommandService cli, UUID player, Kingdom k, SkillSelfTest.FakeWorld world) {}

    static Game newGame(String name) {
        SkillSelfTest.FakeWorld world = new SkillSelfTest.FakeWorld();
        world.time = 6000;
        CoreConfig cfg = new CoreConfig();
        cfg.rivalKingdoms = 0;
        KingdomsCore core = new KingdomsCore(new WorldState(), cfg);
        core.setWorld(world);
        core.setPhysical(world);
        LlmConfig mock = new LlmConfig();
        mock.provider = "mock";
        core.llm().configure(mock);
        UUID player = UUID.randomUUID();
        CommandService cli = new CommandService(core);
        List<String> out = cli.execute(player, "Andraus", new Pos(0, 64, 0), "found " + name);
        print(out);
        core.updatePlayerPos(player, new Pos(0, 64, 0));
        return new Game(core, cli, player, core.kingdomOfPlayer(player), world);
    }

    public static int[] run() throws Exception {
        passed = failed = 0;
        System.out.println("\n# Roteiro de início (não perder no começo)");

        // --- 1. o rei não faz nada; o conselheiro cuida do roteiro
        Game g = newGame("Reino Novo");
        Kingdom k = g.k();
        KingdomsCore core = g.core();
        int pop0 = core.population(k.id);
        double food0 = core.economy().projected(k).getOrDefault(ResourceType.FOOD, 0.0);
        check("sem fazenda, a comida do começo está caindo (" + String.format(Locale.ROOT, "%.1f", food0) + " por ciclo)", food0 < 0);
        List<String> plan = g.cli().execute(g.player(), "Andraus", new Pos(0, 64, 0), "roteiro");
        print(plan);
        check("/k roteiro mostra o checklist com a comida primeiro", plan.stream().anyMatch(l -> l.startsWith("▶ 1. Comida garantida")));
        int deaths = 0;
        boolean famine = false;
        for (int min = 0; min < 30; min++) {
            seconds(core, 60);
            famine |= k.famine;
        }
        deaths = core.bus().log().recent(200, e -> e.type() == EventType.NPC_DIED && e.message().contains("fome")).size();
        print(g.cli().execute(g.player(), "Andraus", new Pos(0, 64, 0), "roteiro"));
        double foodEnd = core.economy().projected(k).getOrDefault(ResourceType.FOOD, 0.0);
        check("o conselheiro fez a fazenda e a comida se sustenta (" + String.format(Locale.ROOT, "%.1f", foodEnd) + " por ciclo, "
                + (int) k.get(ResourceType.FOOD) + " no celeiro)", core.completedOf(k.id, "farm") >= 1
                && (foodEnd >= 0 || k.get(ResourceType.FOOD) > core.population(k.id) * 20));
        check("ninguém morreu de fome, o reino não passou fome e o povo ficou (" + core.population(k.id) + "/" + pop0 + ")", deaths == 0 && !famine
                && core.population(k.id) >= pop0);
        check("armazém e casas também vieram do roteiro", k.openingDone.contains("armazem") && core.completedOf(k.id, "storage") >= 1
                && k.openingDone.contains("casas"));
        check("o rei foi avisado de cada etapa", core.bus().log().recent(300, e -> e.type() == EventType.OPENING_STEP && e.message().contains("✓")).size() >= 3
                && core.bus().log().recent(300, e -> e.type() == EventType.OPENING_STEP && e.message().contains("conselheiro")).size() >= 1);

        // --- 2. materiais: marcado o bosque e a mina, o conselheiro põe lenhador e minerador para trabalhar
        g.world().tree(new Pos(40, 64, -40));
        g.world().tree(new Pos(46, 64, -36));
        k.markers.put(Marker.FOREST, new Pos(40, 64, -40));
        k.markers.put(Marker.MINE, new Pos(60, 50, 0));
        k.openingNudge = 0;
        for (int s = 0; s < 240 && !k.openingDone.contains("materiais"); s++) seconds(core, 1);
        Npc lumber = core.citizens(k.id).stream().filter(n -> n.profession == Profession.LUMBERJACK).findFirst().orElseThrow();
        PhysicalJob lj = lumber.jobId == null ? null : core.state().jobs.get(lumber.jobId);
        check("com bosque e mina marcados, lenhador e minerador foram trabalhar", k.openingDone.contains("materiais") && lj != null && lj.continuous);

        // --- 3. sem conselheiro automático: a comida acaba, mas a carência segura os primeiros dias
        Game g2 = newGame("Reino Largado");
        g2.cli().execute(g2.player(), "Andraus", new Pos(0, 64, 0), "roteiro auto off");
        Kingdom k2 = g2.k();
        k2.civic = "off"; // "largado" = ninguém age, nem o povo por vontade própria
        KingdomsCore c2 = g2.core();
        check("dá para desligar o conselheiro automático", !k2.openingAuto);
        boolean famine2 = false;
        for (int min = 0; min < 30; min++) {
            seconds(c2, 60);
            famine2 |= k2.famine;
        }
        int deaths2 = c2.bus().log().recent(300, e -> e.type() == EventType.NPC_DIED && e.message().contains("fome")).size();
        double minHealth = c2.citizens(k2.id).stream().mapToDouble(n -> n.health).min().orElse(100);
        check("largado sem roteiro, o reino entra em fome", famine2 && c2.completedOf(k2.id, "farm") == 0);
        check("mas nos 2 primeiros dias ninguém morre de fome (saúde mínima " + (int) minHealth + ")", deaths2 == 0 && minHealth >= 19.9);
        check("e o rei recebe o alerta de comida do roteiro", c2.bus().log().recent(300, e -> e.type() == EventType.OPENING_STEP && e.message().contains("Comida")).size() > 0);

        Npc weak = c2.citizens(k2.id).get(1);
        weak.hunger = 0;
        weak.health = 3;
        g2.world().time = 18000; // madrugada: ninguém come
        seconds(c2, 60);
        boolean weakDied = c2.bus().log().recent(300, e -> e.type() == EventType.NPC_DIED && weak.id.equals(e.actorId())).size() > 0;
        check("faminto no reino novo enfraquece mas não morre (saúde " + (int) weak.health + ")", !weakDied && weak.health >= 2.9);

        // --- 4. passada a carência, a fome volta a matar
        k2.foundedTick = c2.tick() - Opening.GRACE - 1;
        Npc victim = c2.citizens(k2.id).get(0);
        victim.hunger = 0;
        victim.health = 3;
        seconds(c2, 60);
        check("depois dos primeiros dias, a fome mata", !victim.alive);
        return new int[]{passed, failed};
    }

    private static void seconds(KingdomsCore core, int s) {
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
