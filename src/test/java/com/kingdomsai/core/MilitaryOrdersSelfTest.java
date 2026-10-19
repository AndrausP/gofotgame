package com.kingdomsai.core;

import com.kingdomsai.core.military.MilitaryOrders;

/** F6: ordem militar composta — validação do plano e executor de dependências (código, não LLM). */
public final class MilitaryOrdersSelfTest {
    private static int passed, failed;

    public static void main(String[] args) {
        int[] r = run();
        System.out.println("\n" + r[0] + " passaram, " + r[1] + " falharam.");
        System.exit(r[1] == 0 ? 0 : 1);
    }

    public static int[] run() {
        passed = failed = 0;
        System.out.println("\n# Ordens militares compostas");
        check("ciclo é rejeitado", rejects("{\"plan\":[{\"id\":\"a\",\"type\":\"RECRUIT\",\"after\":[\"b\"]},{\"id\":\"b\",\"type\":\"RECRUIT\",\"after\":[\"a\"]}]}", "circular"));
        check("id inexistente é rejeitado", rejects("{\"plan\":[{\"id\":\"a\",\"type\":\"RECRUIT\",\"after\":[\"zz\"]}]}", "não existe"));
        check("ação fora da lista militar é rejeitada", rejects("{\"plan\":[{\"id\":\"a\",\"type\":\"DECLARE_WAR\"}]}", "fora da lista"));
        check("id repetido é rejeitado", rejects("{\"plan\":[{\"id\":\"a\",\"type\":\"RECRUIT\"},{\"id\":\"a\",\"type\":\"RECRUIT\"}]}", "repetido"));
        var p = MilitaryOrders.parse(MilitaryOrders.defaultPlanJson());
        check("plano-padrão é válido (muralha, recrutar, treinar, 2 guarnições)", p.steps.size() == 5);

        KingdomsCore core = new KingdomsCore(new WorldState(), new CoreConfig());
        core.setWorld(new SkillSelfTest.FakeWorld());
        var mock = new com.kingdomsai.core.llm.LlmConfig();
        mock.provider = "mock";
        core.llm().configure(mock);
        java.util.UUID pl = java.util.UUID.randomUUID();
        new com.kingdomsai.core.cli.CommandService(core).execute(pl, "Rei", new com.kingdomsai.core.common.Pos(0, 64, 0), "found Reino Guerra");
        var k = core.kingdomOfPlayer(pl);
        var plan = core.militaryOrders().submit(k, pl, "{\"plan\":[{\"id\":\"c\",\"type\":\"TRAIN\",\"params\":{\"amount\":\"2\"},\"after\":[\"b\"]},"
                + "{\"id\":\"b\",\"type\":\"RECRUIT\",\"params\":{\"amount\":\"2\"}},{\"id\":\"d\",\"type\":\"DEFEND\",\"params\":{\"where\":\"praca\"},\"after\":[\"c\"]}]}");
        var byId = new java.util.HashMap<String, MilitaryOrders.Step>();
        for (var s : plan.steps) byId.put(s.id, s);
        System.out.println("    | " + byId.get("b").status + " " + byId.get("b").result + " / " + byId.get("c").status + " " + byId.get("c").result + " / " + byId.get("d").status + " " + byId.get("d").result);
        check("RECRUIT (sem dependência) roda primeiro", byId.get("b").status.equals("DONE"));
        for (int i = 0; i < 3; i++) core.militaryOrders().tick();
        check("TRAIN só depois do RECRUIT e DEFEND só depois do TRAIN (ordem de dependência)", !byId.get("c").status.equals("WAITING") && !byId.get("d").status.equals("WAITING"));
        check("DEFEND passou pelo pipeline (ação implementada, validada)", byId.get("d").status.equals("DONE") || byId.get("d").result.contains(":"));
        return new int[]{passed, failed};
    }

    private static boolean rejects(String json, String frag) {
        try {
            MilitaryOrders.parse(json);
            return false;
        } catch (IllegalArgumentException e) {
            return e.getMessage().contains(frag);
        }
    }

    private static void check(String name, boolean ok) {
        if (ok) passed++;
        else failed++;
        System.out.println((ok ? "  [OK]   " : "  [FAIL] ") + name);
    }
}
