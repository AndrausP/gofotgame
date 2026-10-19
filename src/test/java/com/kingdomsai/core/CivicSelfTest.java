package com.kingdomsai.core;

import com.kingdomsai.core.civic.Decree;
import com.kingdomsai.core.civic.Petition;
import com.kingdomsai.core.common.Pos;
import com.kingdomsai.core.event.EventType;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.kingdom.ResourceType;
import com.kingdomsai.core.llm.Plan;
import com.kingdomsai.core.llm.RuleInterpreter;
import com.kingdomsai.core.npc.Npc;
import com.kingdomsai.core.persistence.Persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Vontade própria: o povo pede, o conselho (ou o rei) decide, as leis valem de verdade. */
public final class CivicSelfTest {
    private static int passed, failed;

    public static void main(String[] args) throws Exception {
        int[] r = run();
        System.out.println("\n" + r[0] + " passaram, " + r[1] + " falharam.");
        System.exit(r[1] == 0 ? 0 : 1);
    }

    private static Npc someone(KingdomsCore core, Kingdom k) {
        for (Npc n : core.citizens(k.id)) if (n.alive) return n;
        throw new IllegalStateException("sem súditos");
    }

    private static String cmd(OpeningSelfTest.Game g, String line) {
        List<String> out = g.cli().execute(g.player(), "Andraus", new Pos(0, 64, 0), line);
        for (String l : out) System.out.println("    | " + l);
        return String.join("\n", out);
    }

    public static int[] run() throws Exception {
        passed = failed = 0;
        System.out.println("\n# Vontade própria (petições, decretos, conselho)");

        // --- 1. sozinhos, com fome, os súditos pedem comida; o conselho atende em nome do rei
        OpeningSelfTest.Game g = OpeningSelfTest.newGame("Reino Livre");
        KingdomsCore core = g.core();
        Kingdom k = g.k();
        check("modo padrão é o conselho", "council".equals(k.civic));
        Petition first = null;
        for (int i = 0; i < 60 && first == null; i++) {
            for (Npc n : core.citizens(k.id)) { n.hunger = 15; n.mood = 40; }
            seconds(core, 10);
            List<Petition> all = core.civic().petitions(k.id);
            if (!all.isEmpty()) first = all.get(0);
        }
        check("sem ninguém mandar, um súdito abre uma petição por conta própria", first != null);
        if (first != null) {
            System.out.println("    | #" + first.number + " «" + first.authorName + "»: " + first.text + " (" + first.reason + ")");
            check("a petição nasce de uma queixa real (comida/moradia) e é uma ação do jogo", first.kind.equals("FOOD") || first.kind.equals("HOUSING"));
            for (int i = 0; i < 40 && first.status == Petition.Status.PENDING; i++) {
                for (Npc n : core.citizens(k.id)) { n.hunger = Math.max(n.hunger, 10); }
                seconds(core, 10);
            }
            check("o conselho decide sozinho (aprovou ou recusou com motivo)", first.status != Petition.Status.PENDING && !first.verdict.isBlank());
            check("a decisão virou evento para o rei",
                    !core.bus().log().recent(300, e -> e.type() == EventType.PETITION_DECIDED).isEmpty()
                            && !core.bus().log().recent(300, e -> e.type() == EventType.PETITION_FILED).isEmpty());
            if (first.status == Petition.Status.APPROVED)
                check("petição aprovada executa a ordem de verdade (PLAYER_ORDER)",
                        !core.bus().log().recent(400, e -> e.type() == EventType.PLAYER_ORDER).isEmpty());
        }

        // --- 2. modo rei: nada é decidido sem o rei; aprovar executa; rejeitar não muda nada
        OpeningSelfTest.Game g2 = OpeningSelfTest.newGame("Reino do Rei");
        KingdomsCore c2 = g2.core();
        Kingdom k2 = g2.k();
        cmd(g2, "civic king");
        check("civic king muda o modo", "king".equals(k2.civic));
        Npc a = someone(c2, k2);
        k2.laws.taxLevel = 3;
        a.mood = 40;
        Petition tax = c2.civic().file(k2, a, "TAX_DOWN");
        check("petição de imposto aberta", tax != null && tax.status == Petition.Status.PENDING);
        seconds(c2, 150);
        check("no modo rei o conselho NÃO decide sozinho", tax.status == Petition.Status.PENDING && k2.laws.taxLevel == 3);
        String listing = cmd(g2, "petitions");
        check("/k petitions lista o pedido", listing.contains("#" + tax.number) && listing.contains("pendente"));
        double moodBefore = a.mood;
        String res = cmd(g2, "approve " + tax.number);
        check("approve atende: imposto cai e o autor fica contente",
                res.contains("✓") && k2.laws.taxLevel == 2 && tax.status == Petition.Status.APPROVED && a.mood > moodBefore);
        check("approve de novo é recusado", cmd(g2, "approve " + tax.number).contains("✗"));
        k2.laws.conscription = true;
        Npc b = c2.citizens(k2.id).size() > 1 ? c2.citizens(k2.id).get(1) : a;
        Petition cons = c2.civic().file(k2, b, "NO_CONSCRIPTION");
        double moodB = b.mood;
        cmd(g2, "reject " + cons.number);
        check("reject não muda a lei e desanima o autor",
                cons.status == Petition.Status.REJECTED && k2.laws.conscription && b.mood < moodB);
        Petition lazy = c2.civic().file(k2, a, "GUARDS");
        seconds(c2, 500);
        check("rei que ignora o pedido: expira e o autor lembra", lazy.status == Petition.Status.EXPIRED
                && a.memories.stream().anyMatch(m -> m.tags().contains("peticao") && m.text().contains("ninguém me respondeu")));
        cmd(g2, "civic off");
        int before = c2.civic().petitions(k2.id).size();
        for (Npc n : c2.citizens(k2.id)) { n.hunger = 10; n.mood = 30; }
        seconds(c2, 120);
        check("civic off: o povo não pede nada", c2.civic().petitions(k2.id).size() == before);

        // --- 3. decretos: leis de verdade
        OpeningSelfTest.Game g3 = OpeningSelfTest.newGame("Reino das Leis");
        KingdomsCore c3 = g3.core();
        Kingdom k3 = g3.k();
        Npc p = someone(c3, k3);
        String d1 = cmd(g3, "decree toque de recolher: todos em casa ao anoitecer");
        check("decree promulga a lei com efeito CURFEW", d1.contains("✓") && c3.civic().has(k3, Decree.CURFEW));
        check("com toque de recolher, o súdito já dorme às 19h30", c3.life().asleep(p, 13600));
        check("lei repetida é recusada", cmd(g3, "decree toque de recolher para todos").contains("✗"));
        String ds = cmd(g3, "decrees");
        check("/k decrees lista a lei", ds.contains("#1") && ds.contains("CURFEW"));
        cmd(g3, "revoke 1");
        check("revogada: sem toque de recolher, volta a rotina normal",
                !c3.civic().has(k3, Decree.CURFEW) && c3.life().asleep(p, 13600) == com.kingdomsai.core.life.LifeSystem.asleep(p, 13600, false));
        cmd(g3, "decree fica proibido brigar na vila");
        check("lei de paz vale (PEACE)", c3.civic().has(k3, Decree.PEACE));
        check("as leis entram no que os súditos sabem", c3.civic().lawsLine(k3).contains("proibido brigar"));
        cmd(g3, "decree o rei é justo e generoso");
        check("lei sem efeito mecânico também vale (texto livre)", k3.decrees.stream().anyMatch(d -> d.active && d.effect.equals(Decree.NONE)));
        k3.stock.put(ResourceType.FOOD, 0.0);
        check("festa sem comida é recusada", cmd(g3, "decree uma grande festa para o povo").contains("✗"));
        k3.stock.put(ResourceType.FOOD, 2000.0);
        for (Npc n : c3.citizens(k3.id)) n.mood = 40;
        double food = k3.get(ResourceType.FOOD);
        cmd(g3, "decree uma grande festa para o povo");
        check("festa com fartura: gasta comida e levanta o ânimo, uma vez só",
                k3.get(ResourceType.FOOD) < food && p.mood > 40 && !c3.civic().has(k3, Decree.FEAST)
                        && k3.decrees.stream().anyMatch(d -> d.effect.equals(Decree.FEAST) && !d.active));
        String bad = cmd(g3, "revoke 99");
        check("revogar lei que não existe dá erro claro", bad.contains("✗"));

        // --- 4. linguagem natural → ação real
        RuleInterpreter ri = new RuleInterpreter(c3);
        Plan dec = ri.interpret(k3, null, "decrete que ninguém pode sair depois do pôr do sol");
        check("\"decrete que...\" vira DECREE", dec.actions().stream().anyMatch(x -> x.rawType().equals("DECREE")));
        Petition open = c3.civic().file(k3, p, "TAX_DOWN");
        Plan ok = ri.interpret(k3, null, "aprove a petição " + (open == null ? 1 : open.number));
        check("\"aprove a petição N\" vira PETITION approve", ok.actions().stream().anyMatch(x -> x.rawType().equals("PETITION")
                && "approve".equals(x.params().get("verdict"))));
        Plan no = ri.interpret(k3, null, "recuse o pedido");
        check("\"recuse o pedido\" vira PETITION reject", no.actions().stream().anyMatch(x -> x.rawType().equals("PETITION")
                && "reject".equals(x.params().get("verdict"))));

        // --- 5. LLM livre: texto saneado e limitado
        Petition free = c3.civic().fileDecree(k3, p, "  Todo\n\nmundo   divide o pão\u0007 " + "x".repeat(400), "BANANA", "porque sim");
        check("decreto vindo da LLM é saneado e limitado", free != null && free.text.length() <= 160 && !free.text.contains("\n")
                && free.effect.equals(Decree.NONE));

        // --- 6. save/load
        Path tmp = Files.createTempDirectory("kai-civic").resolve("kingdomsai.json");
        Persistence.save(c3.snapshotForSave(), tmp);
        WorldState loaded = Persistence.load(tmp);
        Kingdom lk = loaded.kingdoms.get(k3.id);
        check("save/load preserva petições, leis e modo (schema " + loaded.schemaVersion + ")", loaded.petitions.size() == c3.state().petitions.size()
                && lk.decrees.size() == k3.decrees.size() && lk.civic.equals(k3.civic) && loaded.schemaVersion == WorldState.SCHEMA_VERSION
                && lk.decreeCounter == k3.decreeCounter);

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
}
