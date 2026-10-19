package com.kingdomsai.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kingdomsai.core.action.ActionRequest;
import com.kingdomsai.core.action.ActionResult;
import com.kingdomsai.core.action.ActionType;
import com.kingdomsai.core.cli.CommandService;
import com.kingdomsai.core.common.Pos;
import com.kingdomsai.core.diplomacy.Diplomacy;
import com.kingdomsai.core.event.EventType;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.kingdom.KingdomPersonality;
import com.kingdomsai.core.kingdom.ResourceType;
import com.kingdomsai.core.llm.LlmConfig;
import com.kingdomsai.core.military.Campaign;
import com.kingdomsai.core.npc.*;
import com.kingdomsai.core.persistence.Persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Guerra e domínio, como o rei pediu: exército sem ouro (custa comida), quem é competente decide quem vai, terra livre até
 * um limite e tomada além dele (colonos e tropas), batalha, vila conquistada, cativos, escravidão, libertação, massacre —
 * tudo pelo chat, com confirmação, guardas que podem se recusar e consequências.
 */
public final class MilitarySelfTest {
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
        System.out.println("\n# Guerra e domínio");
        core = newCore(7);
        player = UUID.randomUUID();
        cli = new CommandService(core);
        cli.setNotifier((p, lines) -> async.addAll(lines));
        cli.execute(player, "Andraus", new Pos(0, 64, 0), "found Reino de Andraus");
        Kingdom k = core.kingdomOfPlayer(player);
        Kingdom eld = core.foundKingdom("Eldmark", null, null, new Pos(400, 64, 0), KingdomPersonality.balanced(), 10);
        k.stock.put(ResourceType.FOOD, 5000.0); // a guerra testada aqui não é a da fome
        eld.stock.put(ResourceType.FOOD, 5000.0);
        eld.laws.openMigration = false;

        // ---------------------------------------------------------------- 1. exército sem ouro
        double gold = k.get(ResourceType.GOLD);
        double foodBefore = core.economy().projected(k).get(ResourceType.FOOD);
        List<String> out = say("army recruit 4");
        check("convocar não custa ouro", k.get(ResourceType.GOLD) == gold && core.count(k.id, Profession.SOLDIER) == 4);
        double foodAfter = core.economy().projected(k).get(ResourceType.FOOD);
        check("o exército custa comida (" + fmt(foodBefore) + " → " + fmt(foodAfter) + " por ciclo)", foodAfter <= foodBefore - 6);
        check("a resposta avisa do custo em comida", out.stream().anyMatch(s -> s.contains("custa comida")));

        Npc captain = core.citizens(k.id).stream().filter(n -> n.profession == Profession.SOLDIER).findFirst().orElseThrow();
        exec(k, ActionType.PROMOTE, "npc", captain.name, "office", "CAPTAIN");
        check("capitão nomeado", captain.office == Office.CAPTAIN);
        List<String> rec = talk(captain, "Capitão, monte um exército para nos defender.");
        check("sem número, o capitão decide quantos convocar", rec.stream().anyMatch(s -> s.contains("RECRUIT") && s.contains("decidiu convocar")));

        // ---------------------------------------------------------------- 2. terra livre com limite
        double gold2 = k.get(ResourceType.GOLD);
        String lastClaim = "";
        for (int i = 0; i < 20; i++) {
            List<String> c = say("claim 5");
            lastClaim = c.get(0);
            if (c.get(0).startsWith("✗")) break;
        }
        print(List.of(lastClaim));
        check("reivindicar terra livre é grátis", k.get(ResourceType.GOLD) == gold2);
        check("até um limite (" + core.warfare().claimLimit(k) + " células)", lastClaim.contains("claim_limit"));
        check("o limite ensina a tomar: colonos ou tropas", lastClaim.contains("SETTLE") && lastClaim.contains("ATTACK"));
        int owned = core.state().territory.countOwned(k.id);

        // colonos além do limite, onde o rei está (pelo chat)
        List<String> st = say("order mandem 3 colonos para cá", new Pos(0, 64, -420));
        print(st);
        Campaign settle = core.warfare().campaigns(k.id).stream().filter(c -> c.kind == Campaign.Kind.SETTLE).findFirst().orElse(null);
        check("colonos partiram pelo chat", settle != null && settle.members.size() == 3);

        // save no meio da marcha → carrega → chega
        steps(600);
        Path tmp = Files.createTempDirectory("kai-war").resolve("w.json");
        Persistence.save(core.snapshotForSave(), tmp);
        WorldState loaded = Persistence.load(tmp);
        KingdomsCore reloaded = new KingdomsCore(loaded, core.config());
        reloaded.setWorld(new SkillSelfTest.FakeWorld());
        mock(reloaded);
        Kingdom rk = reloaded.kingdom(k.id);
        for (int i = 0; i < 20 * 140; i++) reloaded.step();
        check("save no meio da marcha: colonos chegam depois de carregar",
                rk.id.equals(reloaded.state().territory.ownerAt(new Pos(0, 64, -420))));
        steps(20 * 140);
        check("colônia além do limite: terra é nossa", k.id.equals(core.state().territory.ownerAt(new Pos(0, 64, -420)))
                && core.state().territory.countOwned(k.id) > owned);

        // ---------------------------------------------------------------- 3. invadir: o capitão escolhe quem vai
        double honor = k.honor;
        check("ainda em paz com Eldmark", core.diplomacy().link(k.id, eld.id).state != Diplomacy.State.WAR);
        List<String> atk = talk(captain, "Ataquem a fronteira de Eldmark!");
        Campaign c1 = core.warfare().find(k.id, null);
        check("ataque pelo chat: tropa partiu", c1 != null && c1.kind == Campaign.Kind.ATTACK && c1.live());
        check("o capitão escolheu quem vai", atk.stream().anyMatch(s -> s.contains("escolheu")));
        check("ataque sem declaração declara guerra", core.diplomacy().link(k.id, eld.id).state == Diplomacy.State.WAR);
        check("…e custa honra (" + fmt(honor) + " → " + fmt(k.honor) + ")", k.honor <= honor - 15);
        check("o capitão vai junto", c1.members.contains(captain.id));
        int eldCells = core.state().territory.countOwned(eld.id);
        steps(20 * 120);
        print(c1.log);
        check("batalha na fronteira vencida", c1.result.startsWith("✓") && c1.cellsTaken > 0);
        check("células mudaram de dono (" + eldCells + " → " + core.state().territory.countOwned(eld.id) + ")",
                core.state().territory.countOwned(eld.id) < eldCells);
        steps(20 * 200);
        System.out.println("    | tropa #" + c1.number + ": " + c1.status + ", capitão vivo=" + captain.alive);
        check("tropa voltou para casa", c1.status == Campaign.Status.DONE && captain.campaignId == null);

        // vila sem defesa: cai e vira cativa
        for (Npc n : core.citizens(eld.id)) if (n.profession.isMilitary()) n.alive = false;
        int eldPeople = core.population(eld.id);
        List<String> atk2 = say("order ataquem a vila de Eldmark");
        print(atk2);
        steps(20 * 130);
        Campaign c2 = core.warfare().campaigns(k.id).get(core.warfare().campaigns(k.id).size() - 1);
        print(c2.log);
        long captives = core.citizens(k.id).stream().filter(n -> n.freedom == Freedom.CAPTIVE).count();
        check("vila de Eldmark caiu", c2.result.contains("CAIU") && core.state().territory.countOwned(eld.id) == 0);
        check("os moradores viraram cativos (" + captives + "/" + eldPeople + ")", captives == eldPeople && core.population(eld.id) == 0);
        Npc oneCaptive = core.citizens(k.id).stream().filter(n -> n.freedom == Freedom.CAPTIVE).findFirst().orElseThrow();
        check("cativo lembra de onde veio", eld.id.equals(oneCaptive.originKingdomId) && oneCaptive.office == Office.NONE);
        ActionResult rr = exec(k, ActionType.RECRUIT, "amount", "1");
        check("cativo não é convocado", core.citizens(k.id).stream().noneMatch(n -> !n.isFree() && n.profession.isMilitary()));
        ActionResult pr = exec(k, ActionType.PROMOTE, "npc", oneCaptive.name, "office", "GOVERNOR");
        check("cativo não recebe cargo", !pr.ok() && pr.code().equals("not_free"));

        // ---------------------------------------------------------------- 4. escravizar e libertar
        Npc guard = core.citizens(k.id).stream().filter(n -> n.profession == Profession.GUARD && n.isFree()).findFirst().orElseThrow();
        double legit = k.legitimacy;
        List<String> en = talk(guard, "Escravizem os cativos e ponham na mina.");
        long slaves = core.citizens(k.id).stream().filter(n -> n.freedom == Freedom.ENSLAVED).count();
        check("escravizar pelo chat (sem confirmação)", en.stream().anyMatch(s -> s.startsWith("✓ ENSLAVE")) && slaves == captives);
        check("trabalho forçado na mina", core.citizens(k.id).stream().filter(n -> n.freedom == Freedom.ENSLAVED).allMatch(n -> n.profession == Profession.MINER));
        check("custa legitimidade e dá infâmia", k.legitimacy < legit && k.infamy > 0);
        check("rende menos (título mostra escravizado)", oneCaptive.title().contains("escravizado"));

        // sem vigilância: fogem ou se revoltam
        List<Npc> military = core.citizens(k.id).stream().filter(n -> n.profession.isMilitary()).toList();
        military.forEach(n -> n.profession = Profession.FARMER);
        int before = (int) slaves;
        for (int i = 0; i < 60 && core.citizens(k.id).stream().filter(n -> n.freedom == Freedom.ENSLAVED).count() == before; i++)
            core.warfare().strategicTick();
        core.bus().dispatch();
        long left = core.citizens(k.id).stream().filter(n -> n.freedom == Freedom.ENSLAVED).count();
        boolean revoltOrEscape = !core.bus().log().recent(200, e -> e.type() == EventType.SLAVE_ESCAPED || e.type() == EventType.SLAVE_REVOLT).isEmpty();
        check("sem guardas, escravizados fogem ou se revoltam (" + before + " → " + left + ")", left < before && revoltOrEscape);
        military.forEach(n -> n.profession = n == guard ? Profession.GUARD : Profession.SOLDIER);

        if (left == 0) { // revolta levou todos: põe mais um cativo para testar a libertação
            Npc extra = core.citizens(k.id).stream().filter(n -> n.isFree() && !n.profession.isMilitary() && n.office == Office.NONE).findFirst().orElseThrow();
            extra.freedom = Freedom.ENSLAVED;
        }
        List<String> fr = talk(guard, "Libertem os escravos.");
        check("libertar pelo chat", fr.stream().anyMatch(s -> s.startsWith("✓ FREE"))
                && core.citizens(k.id).stream().allMatch(Npc::isFree));

        // ---------------------------------------------------------------- 5. matar: só o rei, com confirmação; os guardas decidem
        KingdomsCore c = newCore(11);
        KingdomsCore saved = core;
        CommandService savedCli = cli;
        core = c;
        cli = new CommandService(core);
        cli.setNotifier((p, lines) -> async.addAll(lines));
        cli.execute(player, "Andraus", new Pos(0, 64, 0), "found Reino de Andraus");
        Kingdom m = core.kingdomOfPlayer(player);
        Kingdom witness = core.foundKingdom("Morância", null, null, new Pos(-400, 64, 0), KingdomPersonality.balanced(), 8);
        exec(m, ActionType.RECRUIT, "amount", "2");
        Npc g = core.citizens(m.id).stream().filter(n -> n.profession == Profession.GUARD).findFirst().orElseThrow();
        Npc governor = core.citizens(m.id).stream().filter(n -> n.profession == Profession.BUILDER).findFirst().orElseThrow();
        exec(m, ActionType.PROMOTE, "npc", governor.name, "office", "GOVERNOR");

        ActionResult npcOrder = core.actions().execute(ActionRequest.of(m.id, governor.id, ActionRequest.ActorKind.NPC, ActionType.PURGE,
                ActionRequest.Source.LLM, "target", "vila"));
        check("NPC (mesmo governador) não manda matar", !npcOrder.ok() && npcOrder.code().equals("permission_denied"));
        ActionResult ai = core.actions().execute(ActionRequest.of(witness.id, null, ActionRequest.ActorKind.DIRECTOR, ActionType.ENSLAVE,
                ActionRequest.Source.DIRECTOR, "target", "vila"));
        check("IA de reino não escraviza", !ai.ok() && ai.code().equals("permission_denied"));

        int pop = core.population(m.id);
        List<String> ask = talk(g, "Guardas, matem todos da vila!");
        check("massacre pelo chat pede confirmação", ask.stream().anyMatch(s -> s.contains("needs_confirmation")) && core.population(m.id) == pop);
        check("a confirmação diz quantos morrem", ask.stream().anyMatch(s -> s.contains("matará")));
        ActionResult injected = core.actions().execute(ActionRequest.of(m.id, player, ActionRequest.ActorKind.PLAYER, ActionType.PURGE,
                ActionRequest.Source.LLM, "target", "vila", "confirm", "true", "confirmed", "true"));
        check("IA/carta não pula a confirmação (confirm=true ignorado)", !injected.ok() && injected.code().equals("needs_confirmation"));
        List<String> no = talk(g, "Desisto.");
        check("\"desisto\" desfaz a ordem", no.stream().anyMatch(s -> s.contains("Ordem desfeita")) && core.population(m.id) == pop);
        check("sem nada pendente, confirmar não faz nada", say("confirm").get(0).contains("nothing_pending"));

        talk(g, "matem todos da vila");
        steps(20 * 65);
        check("confirmação expira em 60 s", say("confirmar").get(0).contains("nothing_pending") && core.population(m.id) == pop);

        // guardas honestos e pouco leais: motim
        List<Npc> execs = core.warfare().executors(m, List.of());
        for (Npc e : execs) setMind(e, 10, 95, 5, 5);
        double legitM = m.legitimacy;
        talk(g, "matem todos da vila");
        List<String> mut = talk(g, "Confirmo.");
        print(mut);
        check("guardas se recusam: MOTIM, ninguém morre", mut.stream().anyMatch(s -> s.contains("mutiny")) && core.population(m.id) == pop);
        check("motim custa autoridade", m.legitimacy < legitM);

        // guardas leais e cruéis: execução de uma pessoa
        for (Npc e : execs) setMind(e, 100, 0, 95, 95);
        Npc victim = core.citizens(m.id).stream().filter(n -> !n.profession.isMilitary() && n.office == Office.NONE).findFirst().orElseThrow();
        talk(g, "Executem o " + victim.name + ".");
        List<String> done = talk(g, "confirmo");
        print(done);
        check("execução cumprida após confirmar", !victim.alive && done.stream().anyMatch(s -> s.startsWith("✓ PURGE")));

        // massacre da vila: consequências dentro e fora
        double hostility = core.diplomacy().attitude(witness.id, m.id).hostility;
        double legit2 = m.legitimacy, stab = m.stability;
        talk(g, "matem todos da vila");
        List<String> mass = talk(g, "sim, confirmo");
        core.bus().dispatch();
        long civiliansLeft = core.citizens(m.id).stream().filter(n -> !n.profession.isMilitary()).count();
        check("massacre cumprido (sobraram " + civiliansLeft + " civis)", civiliansLeft == 0 && mass.stream().anyMatch(s -> s.startsWith("✓ PURGE")));
        check("legitimidade e estabilidade despencam (" + fmt(legit2) + "→" + fmt(m.legitimacy) + ", " + fmt(stab) + "→" + fmt(m.stability) + ")",
                m.legitimacy < legit2 - 15 && m.stability < stab - 15);
        check("infâmia alta", m.infamy >= 20);
        check("os vizinhos ficam sabendo (hostilidade " + fmt(hostility) + " → " + fmt(core.diplomacy().attitude(witness.id, m.id).hostility) + ")",
                core.diplomacy().attitude(witness.id, m.id).hostility > hostility);
        check("os soldados lembram do que fizeram", execs.stream().filter(n -> n.alive).anyMatch(n -> n.memories.stream().anyMatch(x -> x.text().contains("Cumpri a ordem"))));
        check("crônica registra", core.state().chronicle.stream().anyMatch(s -> s.contains("massacre")));

        // ---------------------------------------------------------------- 5b. só o chat (sem /k): vocativo, cargo, conselho, perto, confirmo
        Npc farmer = core.citizens(m.id).stream().filter(n -> n.isFree() && !n.profession.isMilitary() && n.office == Office.NONE).findFirst().orElse(null);
        List<String> chatOut = new ArrayList<>();
        async.clear();
        boolean addressed = cli.chat(player, "Andraus", new Pos(0, 64, 0), g.name + ", recrutem 1 soldado", chatOut);
        check("chat \"Nome, ordem\" fala com o súdito", addressed && async.stream().anyMatch(x -> x.contains("RECRUIT")));
        async.clear();
        boolean council = cli.chat(player, "Andraus", new Pos(0, 64, 0), "conselho, reivindiquem 1 célula", chatOut);
        check("chat \"conselho, ordem\" vai ao conselho", council && async.stream().anyMatch(x -> x.contains("CLAIM")));
        Npc cap2 = core.citizens(m.id).stream().filter(n -> n.profession == Profession.SOLDIER && n.alive).findFirst().orElseThrow();
        exec(m, ActionType.PROMOTE, "npc", cap2.name, "office", "CAPTAIN");
        async.clear();
        boolean byTitle = cli.chat(player, "Andraus", new Pos(0, 64, 0), "Capitão, ataquem Morância", chatOut);
        check("chat \"Capitão, ...\" acha o capitão pelo cargo", byTitle && async.stream().anyMatch(x -> x.contains("ATTACK") && x.contains("Capitão")));
        Pos far = new Pos(5000, 64, 5000);
        check("conversa entre jogadores passa direto", !cli.chat(player, "Andraus", far, "alguém quer trocar diamante?", chatOut));
        async.clear();
        cli.chat(player, "Andraus", new Pos(0, 64, 0), g.name + ", executem os cativos", chatOut);
        boolean nothing = async.stream().anyMatch(x -> x.contains("not_found"));
        check("chat sem alvo válido: recusa com motivo", nothing);
        async.clear();
        cli.chat(player, "Andraus", new Pos(0, 64, 0), g.name + ", cortem toda a mata perto da vila", chatOut);
        check("\"mata\" (floresta) não vira massacre", async.stream().noneMatch(x -> x.contains("PURGE")) && core.actions().pending(player) == null);

        // ---------------------------------------------------------------- 6. reino de IA em guerra ataca quem é mais fraco
        KingdomsCore d = newCore(13);
        d.config().aiKingdomsEnabled = true;
        UUID p2 = UUID.randomUUID();
        Kingdom weak = d.foundKingdom("Reino Fraco", p2, "Rei", new Pos(0, 64, 0), KingdomPersonality.balanced(), 6);
        Kingdom strong = d.foundKingdom("Vardun", null, null, new Pos(300, 64, 0), KingdomPersonality.balanced(), 12);
        for (Npc n : d.citizens(weak.id)) if (n.profession.isMilitary()) n.profession = Profession.FARMER;
        int made = 0;
        for (Npc n : d.citizens(strong.id)) if (n.office == Office.NONE && made < 6) {
            n.profession = Profession.SOLDIER;
            made++;
        }
        strong.stock.put(ResourceType.FOOD, 5000.0);
        d.diplomacy().declareWar(strong, weak);
        d.director().tick();
        System.out.println("    | " + d.director().lastReasoning(strong.id));
        d.bus().log().recent(20, e -> e.type() == EventType.ACTION_REJECTED || e.type() == EventType.AI_DECISION).forEach(e -> System.out.println("    | " + e.message()));
        Campaign enemy = d.warfare().campaigns(strong.id).stream().filter(Campaign::live).findFirst().orElse(null);
        check("IA em guerra e mais forte manda tropa contra o rei", enemy != null && weak.id.equals(enemy.targetKingdomId));

        // ---------------------------------------------------------------- 7. save antigo (schema 2) abre sem os campos novos
        core = saved;
        cli = savedCli;
        Path old = Files.createTempDirectory("kai-old").resolve("old.json");
        Persistence.save(core.snapshotForSave(), old);
        JsonObject root = JsonParser.parseString(Files.readString(old)).getAsJsonObject();
        root.addProperty("schemaVersion", 2);
        root.remove("campaigns");
        root.remove("campaignCounter");
        for (var e : root.getAsJsonObject("npcs").entrySet()) {
            e.getValue().getAsJsonObject().remove("freedom");
            e.getValue().getAsJsonObject().remove("campaignId");
        }
        for (var e : root.getAsJsonObject("kingdoms").entrySet()) e.getValue().getAsJsonObject().remove("infamy");
        Files.writeString(old, root.toString());
        WorldState ws = Persistence.load(old);
        check("save v2 → v3: todos livres, sem campanhas", ws.schemaVersion == WorldState.SCHEMA_VERSION && ws.campaigns.isEmpty()
                && ws.npcs.values().stream().allMatch(n -> n.freedom == Freedom.FREE));
        KingdomsCore oldCore = new KingdomsCore(ws, core.config());
        oldCore.setWorld(new SkillSelfTest.FakeWorld());
        mock(oldCore);
        for (int i = 0; i < 20 * 60; i++) oldCore.step();
        check("mundo antigo roda com o sistema de guerra", oldCore.kingdomOfPlayer(player) != null);

        return new int[]{passed, failed};
    }

    // ------------------------------------------------------------------ helpers

    private static KingdomsCore newCore(long seed) {
        WorldState s = new WorldState();
        s.seed = seed;
        CoreConfig cfg = new CoreConfig();
        cfg.rivalKingdoms = 0;
        cfg.aiKingdomsEnabled = false;
        KingdomsCore c = new KingdomsCore(s, cfg);
        SkillSelfTest.FakeWorld w = new SkillSelfTest.FakeWorld();
        c.setWorld(w);
        c.setPhysical(w);
        mock(c);
        return c;
    }

    private static void mock(KingdomsCore c) {
        LlmConfig l = new LlmConfig();
        l.provider = "mock";
        c.llm().configure(l);
    }

    private static void setMind(Npc n, int loyalty, int honesty, int aggression, int discipline) {
        n.loyalty = loyalty;
        n.traits.put(Trait.HONESTY, honesty);
        n.traits.put(Trait.AGGRESSION, aggression);
        n.traits.put(Trait.DISCIPLINE, discipline);
        n.relations.clear();
    }

    private static List<String> say(String line) {
        return say(line, new Pos(0, 64, 0));
    }

    private static List<String> say(String line, Pos where) {
        async.clear();
        List<String> out = new ArrayList<>(cli.execute(player, "Andraus", where, line));
        out.addAll(async);
        return out;
    }

    private static List<String> talk(Npc n, String text) {
        async.clear();
        cli.execute(player, "Andraus", new Pos(0, 64, 0), "npc talk " + n.name + " " + text);
        core.bus().dispatch();
        List<String> out = new ArrayList<>(async);
        print(out);
        return out;
    }

    private static ActionResult exec(Kingdom k, ActionType t, String... kv) {
        return core.actions().execute(ActionRequest.of(k.id, player, ActionRequest.ActorKind.PLAYER, t, ActionRequest.Source.TEST, kv));
    }

    private static void steps(int n) {
        for (int i = 0; i < n; i++) core.step();
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
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
