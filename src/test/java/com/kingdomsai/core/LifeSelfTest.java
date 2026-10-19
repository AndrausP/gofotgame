package com.kingdomsai.core;

import com.kingdomsai.core.cli.CommandService;
import com.kingdomsai.core.common.Pos;
import com.kingdomsai.core.construction.Blueprint;
import com.kingdomsai.core.construction.BlueprintLibrary;
import com.kingdomsai.core.construction.Building;
import com.kingdomsai.core.construction.ParametricBlueprints;
import com.kingdomsai.core.event.EventType;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.kingdom.KingdomPersonality;
import com.kingdomsai.core.kingdom.ResourceType;
import com.kingdomsai.core.life.Intention;
import com.kingdomsai.core.life.LifeSystem;
import com.kingdomsai.core.life.Places;
import com.kingdomsai.core.llm.LlmConfig;
import com.kingdomsai.core.llm.LlmProvider;
import com.kingdomsai.core.llm.LlmRequest;
import com.kingdomsai.core.llm.Plan;
import com.kingdomsai.core.npc.*;
import com.kingdomsai.core.persistence.Persistence;
import com.kingdomsai.core.port.PhysicalPort;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Súditos que agem como gente: horário pessoal, refeições, fome que mata, humor, lazer pela personalidade, conversas,
 * boatos de boca em boca, brigas, namoro e casamento, fuga de monstro, guarda atendendo ao socorro, agressão lembrada,
 * ferido descansando, agenda dos importantes pela IA (validada) e desempenho com centenas de súditos.
 */
public final class LifeSelfTest {
    private static int passed, failed;
    private static KingdomsCore core;
    private static CommandService cli;
    private static UUID player;
    private static SkillSelfTest.FakeWorld world;
    private static final List<LifeSystem.ChatLine> heard = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        int[] r = run();
        System.out.println("\n" + r[0] + " passaram, " + r[1] + " falharam.");
        System.exit(r[1] == 0 ? 0 : 1);
    }

    public static int[] run() throws Exception {
        passed = failed = 0;
        heard.clear();
        System.out.println("\n# Vida dos súditos (gente de verdade)");
        world = new SkillSelfTest.FakeWorld();
        world.time = 6000;
        CoreConfig cfg = new CoreConfig();
        cfg.rivalKingdoms = 0;
        cfg.startingCitizens = 14;
        core = new KingdomsCore(new WorldState(), cfg);
        core.setWorld(world);
        core.setPhysical(world);
        LlmConfig mock = new LlmConfig();
        mock.provider = "mock";
        core.llm().configure(mock);
        player = UUID.randomUUID();
        cli = new CommandService(core);
        cli.execute(player, "Andraus", new Pos(0, 64, 0), "found Reino Vivo");
        Kingdom k = core.kingdomOfPlayer(player);
        k.civic = "off"; // estes testes medem o efeito exato das ordens; a vontade própria tem suíte própria (CivicSelfTest)
        k.openingAuto = false;
        k.foundedTick -= com.kingdomsai.core.ai.Opening.PERIOD; // reino já estabelecido: sem a carência do começo (testada no roteiro)
        core.updatePlayerPos(player, new Pos(0, 64, 0));
        k.add(ResourceType.FOOD, 3000);
        core.life().setChatListener(heard::add);
        List<Npc> cs = core.citizens(k.id);
        Npc farmer = prof(k, Profession.FARMER), guard = prof(k, Profession.GUARD), priest = prof(k, Profession.PRIEST);
        Building h1 = home(k, new Pos(30, 64, 30)), h2 = home(k, new Pos(44, 64, 30)), h3 = home(k, new Pos(58, 64, 30));
        live(farmer, h1);

        // --- 1. cada um tem seu horário
        Npc probe = cs.get(5);
        probe.traits.put(Trait.DISCIPLINE, 90);
        long wakeDisciplined = LifeSystem.wake(probe);
        probe.traits.put(Trait.DISCIPLINE, 10);
        long wakeLazy = LifeSystem.wake(probe);
        probe.traits.put(Trait.DISCIPLINE, 50);
        check("disciplinado acorda mais cedo que o preguiçoso", Math.floorMod(wakeLazy - wakeDisciplined, 24000) == 640);
        probe.traits.put(Trait.SOCIABILITY, 90);
        long late = LifeSystem.bedtime(probe);
        probe.traits.put(Trait.SOCIABILITY, 10);
        check("sociável vai dormir mais tarde", late - LifeSystem.bedtime(probe) == 640);
        probe.traits.put(Trait.SOCIABILITY, 50);
        check("de madrugada todos dormem; ao meio-dia ninguém (fora o turno da noite)", cs.stream().allMatch(n -> core.life().asleep(n, 18000))
                && cs.stream().noneMatch(n -> core.life().asleep(n, 6000)));
        Npc guard2 = core.createNpc(k, Profession.GUARD, k.center);
        seconds(31);
        long nightGuards = core.citizens(k.id).stream().filter(n -> core.life().nightShift(n)).count();
        check("com dois guardas, um faz o turno da noite (dorme de dia, vigia de noite)", nightGuards == 1
                && (core.life().asleep(guard, 6000) != core.life().asleep(guard2, 6000)) && (core.life().asleep(guard, 18000) != core.life().asleep(guard2, 18000)));

        // --- 2. fome, refeições, saúde
        farmer.hunger = 50;
        farmer.lastMealTick = -100000;
        farmer.intention = new Intention();
        world.time = 5800; // almoço
        boolean ate = false;
        for (int s = 0; s < 120 && !ate; s++) {
            seconds(1);
            ate = farmer.lastMealTick > 0;
        }
        check("na hora do almoço ele come (fome 50 → " + (int) farmer.hunger + ")", ate && farmer.hunger > 70);
        double h0 = farmer.hunger;
        world.time = 3000;
        seconds(60);
        check("a fome volta com o tempo (" + (int) h0 + " → " + (int) farmer.hunger + ")", farmer.hunger < h0 - 3);

        Npc starving = core.createNpc(k, Profession.PEASANT, k.center), widow = core.createNpc(k, Profession.PEASANT, k.center);
        starving.partnerId = widow.id;
        widow.partnerId = starving.id;
        starving.hunger = 0;
        starving.health = 2;
        world.time = 18000; // madrugada: ninguém vai comer
        seconds(40);
        check("sem comer, a saúde cai até a morte (\"morreu de fome\")", !starving.alive
                && core.bus().log().recent(30, e -> e.type() == EventType.NPC_DIED).stream().anyMatch(e -> e.message().contains("morreu de fome")));
        check("o par fica de luto e sozinho", widow.partnerId == null && widow.memories.stream().anyMatch(m -> m.text().contains("Perdi " + starving.name)));
        world.time = 6000;

        // --- 3. humor
        Npc happy = cs.get(2), sad = cs.get(3);
        live(happy, h2);
        happy.hunger = 95;
        happy.social = 95;
        happy.energy = 95;
        Npc buddy = cs.get(4);
        happy.relationTo(buddy.id).affection = 90;
        happy.relationTo(cs.get(6).id).affection = 80;
        sad.homeId = null;
        sad.hunger = 15;
        sad.social = 5;
        sad.energy = 20;
        sad.remember(core.tick(), "Meu irmão morreu na guerra.", 80, null, "luto", "morte");
        for (int i = 0; i < 40; i++) {
            happy.hunger = sad.hunger + 80;
            seconds(5);
        }
        check("humor reflete a vida: " + happy.name + " " + (int) happy.mood + " (" + LifeSystem.moodWord(happy) + ") × " + sad.name + " " + (int) sad.mood
                + " (" + LifeSystem.moodWord(sad) + ")", happy.mood > sad.mood + 15);
        check("humor muda o ritmo de trabalho", LifeSystem.workFactor(happy) > LifeSystem.workFactor(sad));

        // --- 4. fim de tarde: cada um do seu jeito (capela, biblioteca, praça, par)
        k.add(ResourceType.WOOD, 3000);
        k.add(ResourceType.STONE, 3000);
        Blueprint chapelBp = core.registerSpec(ParametricBlueprints.spec(Map.of("tipo", "capela", "nome", "Capela da Vila")));
        Building chapel = complete(core.construction().planAt(k, chapelBp, new Pos(-40, 64, 30), true));
        Building library = complete(core.construction().planAt(k, BlueprintLibrary.get("library"), new Pos(-40, 64, -30), true));
        check("a vila reconhece capela e biblioteca", Places.church(core, k) == chapel && Places.library(core, k) == library);
        priest.traits.put(Trait.RELIGIOSITY, 95);
        priest.traits.put(Trait.SOCIABILITY, 50);
        Npc secular = cs.get(7);
        secular.traits.put(Trait.RELIGIOSITY, 10);
        Npc curious = cs.get(8);
        curious.traits.put(Trait.CURIOSITY, 95);
        curious.literate = true;
        Npc party = cs.get(9);
        party.traits.put(Trait.SOCIABILITY, 98);
        party.traits.put(Trait.CURIOSITY, 20);
        party.traits.put(Trait.RELIGIOSITY, 20);
        // a mesma pessoa, com o traço alto e baixo, em 100 tardes
        int prayHigh = count(priest, Intention.Kind.PRAY), prayLow;
        priest.traits.put(Trait.RELIGIOSITY, 10);
        prayLow = count(priest, Intention.Kind.PRAY);
        priest.traits.put(Trait.RELIGIOSITY, 95);
        check("religioso vai rezar na capela (" + prayHigh + "/100 tardes); com pouca fé, " + prayLow, prayHigh >= 15 && prayLow == 0);
        int readHigh = count(curious, Intention.Kind.READ);
        curious.traits.put(Trait.CURIOSITY, 20);
        curious.literate = false;
        int readLow = count(curious, Intention.Kind.READ);
        curious.traits.put(Trait.CURIOSITY, 95);
        check("curioso vai ler na biblioteca (" + readHigh + "/100); sem curiosidade nem leitura, " + readLow, readHigh >= 15 && readLow == 0);
        int socHigh = count(party, Intention.Kind.SOCIALIZE);
        party.traits.put(Trait.SOCIABILITY, 5);
        int socLow = count(party, Intention.Kind.SOCIALIZE);
        party.traits.put(Trait.SOCIABILITY, 98);
        check("o sociável vai para a praça/taverna (" + socHigh + "/100) bem mais que o reservado (" + socLow + ")", socHigh > socLow + 15);
        priest.intention = Intention.of(Intention.Kind.PRAY, chapel.centerPos(), "capela", "rezar", core.tick() + 20 * 120, "rotina");
        var pi = core.scheduler().decide(priest, 11500);
        check("e vai mesmo: o agendador o leva à capela, rezando", pi.activity() == NpcActivity.PRAY && pi.target().distXZ(chapel.centerPos()) <= 2);

        // --- 5. conversas (civis: soldados saem para treinar no meio da conversa)
        world.time = 11500;
        List<Npc> civ = core.citizens(k.id).stream().filter(n -> !n.profession.isMilitary() && n.office == Office.NONE && n.alive
                && n != priest && n != farmer).toList();
        Npc a = civ.get(0), b = civ.get(1);
        place(a, k.center.offset(2, 0, 2));
        place(b, k.center.offset(3, 0, 2));
        a.social = 10;
        b.social = 10;
        for (Npc x : List.of(a, b)) {
            Relation r = x.relationTo(x == a ? b.id : a.id);
            r.affection = 50;
            r.rivalry = 0;
            r.trust = 50;
        }
        int affBefore = a.relationTo(b.id).affection;
        int linesBefore = heard.size();
        core.life().start(a, b, core.tick());
        seconds(45);
        List<LifeSystem.ChatLine> said = heard.subList(linesBefore, heard.size()).stream().filter(l -> l.listener() != null).toList();
        said.forEach(l -> System.out.println("    | " + l.format()));
        check("conversaram de verdade (" + said.size() + " falas, os dois falaram)", said.size() >= 2 && said.stream().anyMatch(l -> l.speaker().equals(a.id))
                && said.stream().anyMatch(l -> l.speaker().equals(b.id)));
        check("conversar mata a solidão e aproxima", a.social > 15 && b.social > 15
                && (a.relationTo(b.id).affection > affBefore || a.relationTo(b.id).rivalry > 0));
        check("lembram do que foi dito", !a.recentTalk.isEmpty() && !b.recentTalk.isEmpty());

        // --- 6. boato de boca em boca (e perde força a cada boca)
        Npc teller = civ.get(2);
        teller.remember(core.tick(), "Vi um lobo gigante perto da mina.", 70, null, "monstro", "perigo");
        List<Npc> crowd = new ArrayList<>(List.of(teller, civ.get(3), civ.get(4), civ.get(5), civ.get(6), civ.get(7)));
        Random mix = new Random(7);
        for (int round = 0; round < 40 && core.life().knowing(k.id, "lobo gigante") < 4; round++) {
            // quem sabe conversa com quem ainda não sabe (a vila se mistura)
            List<Npc> knowers = crowd.stream().filter(n -> n.alive && n.memories.stream().anyMatch(m -> m.text().contains("lobo gigante"))).toList();
            List<Npc> others = crowd.stream().filter(n -> n.alive && !knowers.contains(n)).toList();
            if (knowers.isEmpty() || others.isEmpty()) break;
            Npc x = knowers.get(mix.nextInt(knowers.size())), y = others.get(mix.nextInt(others.size()));
            place(x, k.center.offset(-3, 0, 4));
            place(y, k.center.offset(-2, 0, 4));
            core.life().start(x, y, core.tick());
            seconds(40);
        }
        int knowing = core.life().knowing(k.id, "lobo gigante");
        check("o boato correu a vila (" + knowing + " sabem)", knowing >= 4 && core.life().rumorsPassed() >= 3);
        Memory second = cs.stream().flatMap(n -> n.memories.stream()).filter(m -> m.text().contains("lobo gigante") && m.text().startsWith(teller.name + " me contou"))
                .findFirst().orElse(null);
        check("quem ouviu lembra quem contou, com menos certeza", second != null && second.importance() == 49 && second.tags().contains("boato"));

        // --- 7. briga entre rivais
        Npc r1 = civ.get(0), r2 = civ.get(1);
        r1.relationTo(r2.id).rivalry = 90;
        r1.traits.put(Trait.AGGRESSION, 85);
        int argBefore = core.life().arguments();
        for (int i = 0; i < 40 && core.life().arguments() == argBefore; i++) {
            r1.relationTo(r2.id).rivalry = 90;
            place(r1, k.center.offset(6, 0, -6));
            place(r2, k.center.offset(7, 0, -6));
            core.life().start(r1, r2, core.tick());
            seconds(40);
        }
        check("rivais discutem (briga registrada e lembrada)", core.life().arguments() > argBefore && r2.memories.stream().anyMatch(m -> m.text().contains("brigar"))
                && core.bus().log().recent(50, e -> e.type() == EventType.NPC_ARGUMENT).size() > 0);

        // --- 8. namoro → casal → moram juntos
        Npc l1 = civ.get(3), l2 = civ.get(4);
        live(l1, h3);
        l2.homeId = null;
        for (Npc l : List.of(l1, l2)) {
            Relation r = l.relationTo(l == l1 ? l2.id : l1.id);
            r.affection = 92;
            r.trust = 75;
            r.rivalry = 0;
            Npc ex = l.partnerId == null ? null : core.npc(l.partnerId); // solteiros para o teste (quem estava com eles também)
            if (ex != null) ex.partnerId = null;
            l.partnerId = null;
        }
        int couplesBefore = core.life().couplesFormed();
        for (int i = 0; i < 60 && !(l1.partnerId != null && l1.partnerId.equals(l2.id)); i++) {
            for (Npc l : List.of(l1, l2)) { // a paixão se mantém durante o teste (e ninguém se casou com outro no meio)
                Npc ex = l.partnerId == null ? null : core.npc(l.partnerId);
                if (ex != null && ex != l1 && ex != l2) {
                    ex.partnerId = null;
                    l.partnerId = null;
                }
                Relation r = l.relationTo(l == l1 ? l2.id : l1.id);
                r.affection = Math.max(r.affection, 90);
                r.trust = Math.max(r.trust, 70);
                r.rivalry = 0;
            }
            place(l1, k.center.offset(-6, 0, -6));
            place(l2, k.center.offset(-5, 0, -6));
            core.life().start(l1, l2, core.tick());
            seconds(40);
        }
        check(l1.name + " e " + l2.name + " viraram um casal", l1.partnerId != null && l1.partnerId.equals(l2.id) && l2.partnerId.equals(l1.id)
                && core.bus().log().recent(80, e -> e.type() == EventType.NPC_COUPLE).size() > 0);
        Building shared = l1.homeId == null ? null : core.state().buildings.get(l1.homeId);
        check("foram morar juntos (um mudou para a casa do outro)", shared != null && l1.homeId.equals(l2.homeId)
                && shared.residents.contains(l1.id) && shared.residents.contains(l2.id));
        int visits = 0;
        for (long d = 0; d < 100; d++) if (l2.id.equals(core.life().eveningPlan(l1, 11500, d * LifeSystem.DAY + 100).targetNpc)) visits++;
        check("o casal passa muitas tardes junto (" + visits + "/100)", visits >= 25);

        // --- 9. monstro: foge e grita; guarda vem; o medo passa
        world.time = 6000;
        Npc victim = civ.get(5);
        victim.intention = new Intention();
        place(victim, k.center.offset(10, 0, 10));
        victim.materialized = true;
        Pos zombie = k.center.offset(15, 0, 10);
        world.monsters.add(new PhysicalPort.Sighting("minecraft:zombie", zombie));
        int lb = heard.size();
        seconds(3);
        var fi = core.scheduler().decide(victim, 6000);
        check("viu o zumbi: foge (para longe dele)", fi.activity() == NpcActivity.FLEE && fi.target().distXZ(zombie) > victim.pos.distXZ(zombie));
        check("grita por socorro e lembra do perigo", heard.subList(lb, heard.size()).stream().anyMatch(l -> l.text().contains("zumbi") || l.text().contains("Zumbi"))
                && victim.memories.stream().anyMatch(m -> m.text().contains("zumbi")) && victim.fear > 20);
        check("evento de socorro para o rei", core.bus().log().recent(40, e -> e.type() == EventType.THREAT_SPOTTED).size() > 0);
        Npc dayGuard = core.life().nightShift(guard) ? guard2 : guard;
        dayGuard.intention = new Intention();
        dayGuard.talkingWith = null;
        var gi = core.scheduler().decide(dayGuard, 6000);
        check("o guarda vai até o grito", gi.activity() == NpcActivity.PATROL && gi.target().distXZ(zombie) <= 3);
        world.monsters.clear();
        victim.materialized = false;
        seconds(20);
        check("passado o susto, para de fugir", core.scheduler().decide(victim, 6000).activity() != NpcActivity.FLEE);

        // --- 10. agressão do rei fica na memória (e na lealdade)
        Npc hit = civ.get(6);
        int loyalBefore = hit.loyalty;
        lb = heard.size();
        core.life().onHurt(hit.id, 60, "player", player, k.center.offset(1, 0, 0));
        check("apanhou do rei: lembra, teme e perde lealdade", hit.memories.stream().anyMatch(m -> m.text().contains("O rei me bateu"))
                && hit.relationTo(player).fear >= 30 && hit.loyalty < loyalBefore && hit.health == 60);
        check("e reclama em voz alta", heard.subList(lb, heard.size()).stream().anyMatch(l -> l.text().contains("Majestade")));

        // --- 11. ferido descansa e melhora
        Npc hurt = civ.get(7);
        live(hurt, h2);
        place(hurt, k.center.offset(-30, 0, -10));
        hurt.health = 25;
        world.time = 3000;
        seconds(6);
        check("ferido vai descansar em casa", hurt.intention.kind == Intention.Kind.REST && hurt.intention.reason.contains("ferid"));
        seconds(60);
        check("e a saúde volta (" + (int) hurt.health + ")", hurt.health > 25);

        // --- 12. recolhe só o que ele mesmo derrubou
        Npc picker = cs.get(10);
        picker.spilled.put("minecraft:oak_log", 5);
        int got = core.life().pickup(picker, "minecraft:oak_log", 3);
        check("recolhe o que derrubou (mochila cheia antes) e não pega o que é dos outros", got == 3 && core.life().pickup(picker, "minecraft:diamond", 1) == 0);

        // --- 13. agenda dos importantes pela IA (validada)
        Npc advisor = core.citizens(k.id).stream().filter(n -> n.office == Office.ADVISOR).findFirst().orElseThrow();
        Npc friend = civ.get(1);
        FakeBrain brain = new FakeBrain(friend.name);
        core.llm().setClaudeCodeProvider(brain);
        LlmConfig live = new LlmConfig();
        live.provider = "claude_code";
        core.llm().configure(live);
        cfg.lifeLlmPerMinute = 50;
        place(advisor, k.center.offset(70, 0, -70)); // sozinho, longe de conversa
        world.time = 3000;
        for (int s = 0; s < 30 && !"IA".equals(advisor.intention.source); s++) seconds(1);
        check("a IA decidiu a agenda do conselheiro (visitar " + friend.name + ")", "IA".equals(advisor.intention.source)
                && advisor.intention.kind == Intention.Kind.VISIT && friend.id.equals(advisor.intention.targetNpc)
                && advisor.goal.contains(friend.name) && brain.agendaCalls > 0);
        Intention before = advisor.intention;
        core.life().applyAgenda(advisor.id, Plan.parse("{\"reply\":\"x\",\"actions\":[{\"type\":\"LIFE\",\"params\":{\"do\":\"visit\",\"target\":\"Fulano Inexistente\"}}]}"));
        check("pessoa inventada pela IA é ignorada", advisor.intention == before);
        core.life().applyAgenda(advisor.id, Plan.parse("{\"reply\":\"x\",\"actions\":[{\"type\":\"LIFE\",\"params\":{\"do\":\"voar\",\"target\":\"lua\"}}]}"));
        check("ação fora da lista é ignorada", advisor.intention == before);
        lb = heard.size();
        place(advisor, k.center.offset(-8, 0, 8));
        place(friend, k.center.offset(-7, 0, 8));
        advisor.intention = new Intention();
        core.life().start(advisor, friend, core.tick());
        seconds(40);
        check("conversa do importante escrita pela IA", heard.subList(lb, heard.size()).stream().anyMatch(l -> l.text().contains("Bons ventos")) && brain.talkCalls > 0);
        cfg.lifeLlm = false;
        core.llm().configure(mock);

        // --- 14. CLI
        List<String> life = cli.execute(player, "Andraus", new Pos(0, 64, 0), "life");
        print(life);
        check("/k life mostra humor, casais, boatos e o que se ouve", life.stream().anyMatch(l -> l.startsWith("Humor médio"))
                && life.stream().anyMatch(l -> l.startsWith("Casais:") && l.contains(l1.name)) && life.stream().anyMatch(l -> l.startsWith("Ouvido pela vila")));
        List<String> ins = cli.execute(player, "Andraus", new Pos(0, 64, 0), "npc inspect " + l1.name);
        print(ins);
        check("/k npc inspect mostra humor, necessidades, par e horário", ins.stream().anyMatch(l -> l.startsWith("Humor"))
                && ins.stream().anyMatch(l -> l.startsWith("Par: " + l2.name)) && ins.stream().anyMatch(l -> l.startsWith("Dia: acorda")));

        // --- 15. save/load
        Path tmp = Files.createTempDirectory("kai-life").resolve("kingdomsai.json");
        Persistence.save(core.snapshotForSave(), tmp);
        WorldState loaded = Persistence.load(tmp);
        Npc l1b = loaded.npcs.get(l1.id);
        check("save/load preserva par, humor, objetivo, falas e intenção", Objects.equals(l1b.partnerId, l2.id) && Math.abs(l1b.mood - l1.mood) < 0.01
                && l1b.recentTalk.equals(l1.recentTalk) && l1b.intention != null && loaded.npcs.get(advisor.id).goal.equals(advisor.goal));

        // --- 16. desempenho: 250 súditos vivendo
        Kingdom big = core.foundKingdom("Cidade Grande", null, "Prefeito", new Pos(600, 64, 600), KingdomPersonality.balanced(), 14);
        big.add(ResourceType.FOOD, 100000);
        for (int i = 0; i < 236; i++) core.createNpc(big, Profession.values()[i % 6], big.center.offset(i % 20 - 10, 0, i / 20 - 6));
        world.time = 11500;
        long t0 = System.nanoTime();
        seconds(120);
        double ms = (System.nanoTime() - t0) / 1e6 / (120 * 20);
        List<String> perf = cli.execute(player, "Andraus", new Pos(0, 64, 0), "perf");
        print(perf);
        check("250+ súditos com vida completa: " + String.format(Locale.ROOT, "%.2f", ms) + " ms por tick (orçamento 50 ms)", ms < 10 && core.allAlive().size() > 250);
        return new int[]{passed, failed};
    }

    /** IA falsa: agenda = visitar um amigo; conversa = falas prontas. */
    static final class FakeBrain implements LlmProvider {
        final String friend;
        int agendaCalls, talkCalls;

        FakeBrain(String friend) {
            this.friend = friend;
        }

        public String name() {
            return "claude_code";
        }

        public CompletableFuture<String> complete(LlmRequest r, LlmConfig c) {
            if (r.purpose().equals("agenda")) {
                agendaCalls++;
                return CompletableFuture.completedFuture("{\"reply\":\"Faz tempo que não vejo " + friend + ".\",\"actions\":[{\"type\":\"LIFE\",\"params\":"
                        + "{\"do\":\"visit\",\"target\":\"" + friend + "\",\"minutes\":\"3\",\"reason\":\"saudade\",\"goal\":\"ficar mais perto de " + friend + "\"}}]}");
            }
            talkCalls++;
            return CompletableFuture.completedFuture("{\"reply\":\"\",\"actions\":[{\"type\":\"LINE\",\"params\":{\"who\":\"A\",\"text\":\"Bons ventos, amigo!\"}},"
                    + "{\"type\":\"LINE\",\"params\":{\"who\":\"B\",\"text\":\"Bons ventos! O conselho anda ocupado?\"}},"
                    + "{\"type\":\"LINE\",\"params\":{\"who\":\"A\",\"text\":\"Sempre. O reino não dorme.\"}}]}");
        }
    }

    // ------------------------------------------------------------------ util

    private static int count(Npc n, Intention.Kind kind) {
        n.goal = ""; // só o traço (objetivos pessoais também puxam: "rezar na capela", "aprender a ler")
        int c = 0;
        for (long d = 0; d < 100; d++) if (core.life().eveningPlan(n, 11500, d * LifeSystem.DAY + 100).kind == kind) c++;
        return c;
    }

    private static Building home(Kingdom k, Pos at) {
        return complete(core.construction().planAt(k, BlueprintLibrary.get("house_medium"), at, true));
    }

    private static Building complete(Building b) {
        b.status = Building.Status.COMPLETE;
        b.placed = b.progress = b.blueprint().blockCount();
        if (b.origin.y() == Integer.MIN_VALUE) b.origin = new Pos(b.origin.x(), 64, b.origin.z());
        return b;
    }

    private static void live(Npc n, Building h) {
        Building old = n.homeId == null ? null : core.state().buildings.get(n.homeId);
        if (old != null) old.residents.remove(n.id);
        n.homeId = h.id;
        if (!h.residents.contains(n.id)) h.residents.add(n.id);
    }

    private static void place(Npc n, Pos p) {
        n.pos = p;
        n.talkingWith = null;
        n.intention = new Intention();
        n.fleeUntil = 0;
    }

    private static Npc prof(Kingdom k, Profession p) {
        return core.citizens(k.id).stream().filter(n -> n.profession == p).findFirst().orElseThrow();
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
