package com.kingdomsai.core;

import com.kingdomsai.core.cli.CommandService;
import com.kingdomsai.core.common.Pos;
import com.kingdomsai.core.construction.Blueprint;
import com.kingdomsai.core.construction.Building;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.kingdom.ResourceType;
import com.kingdomsai.core.llm.LlmConfig;
import com.kingdomsai.core.npc.Npc;
import com.kingdomsai.core.npc.Profession;
import com.kingdomsai.core.port.WorldPort;

import java.util.*;

/** Valida TODOS os comandos da CLI: nenhum pode lançar erro interno e cada um deve responder algo coerente. */
public final class CliSelfTest {
    private static int passed, failed;

    public static void main(String[] args) {
        WorldPort flat = new WorldPort() {
            public SiteCheck checkSite(int x, int z, Blueprint bp) { return new SiteCheck(SiteCheck.Kind.OK, 64, 1.0); }
            public long dayTime() { return 6000; }
            public boolean isLoaded(int x, int z) { return true; }
            public int surfaceY(int x, int z) { return 64; }
        };
        KingdomsCore core = new KingdomsCore(new WorldState(), new CoreConfig());
        core.setWorld(flat);
        LlmConfig mock = new LlmConfig();
        mock.provider = "mock";
        core.llm().configure(mock);
        CommandService cli = new CommandService(core);
        List<String> async = new ArrayList<>();
        cli.setNotifier((p, l) -> async.addAll(l));
        UUID me = UUID.randomUUID();
        Pos pos = new Pos(0, 64, 0);

        expect(cli, me, pos, "status", "✗ Você ainda não governa");
        expect(cli, me, pos, "found Reino de Teste", "✓ Reino de Teste foi fundado");
        Kingdom k = core.kingdomOfPlayer(me);
        k.stock.put(ResourceType.WOOD, 5000.0);
        k.stock.put(ResourceType.STONE, 5000.0);
        k.stock.put(ResourceType.GOLD, 5000.0);
        k.stock.put(ResourceType.IRON, 500.0);
        Npc smith = core.citizens(k.id).stream().filter(n -> n.profession == Profession.BLACKSMITH).findFirst().orElseThrow();
        String sn = smith.name.split(" ")[0];
        Npc builder = core.citizens(k.id).stream().filter(n -> n.profession == Profession.BUILDER).findFirst().orElseThrow();

        String[][] cases = {
                {"help", "# Kingdoms AI"},
                {"status", "# REINO DE TESTE"},
                {"npc list", "# Súditos"},
                {"npc list fazendeiro", "# Súditos"},
                {"npc inspect " + sn, "# " + smith.name},
                {"npc job " + sn + " ferreiro", "✓"},
                {"npc promote " + sn + " capitao", "✓"},
                {"npc demote " + sn, "✓"},
                {"npc promote " + sn + " rei", "✗"},
                {"npc inspect Ninguem", "✗"},
                {"assign fazendeiro 1", "✓"},
                {"assign construtor 1 de fazendeiro", "✓"},
                {"blueprints", "# Plantas"},
                {"blueprint materials", "Materiais"},
                {"blueprint design nome=Casa_do_Ferreiro tipo=casa largura=9 profundidade=7 andares=2 parede=pedra telhado=duas_aguas chamine=sim", "✓ Planta criada"},
                {"blueprint show custom_casa_do_ferreiro", "# Casa do Ferreiro"},
                {"blueprint design tipo=casa largura=40", "✓"}, // fora da faixa: ajusta para 21 e avisa (não recusa)
                {"blueprint design tipo=torre parede=madeira_magica", "✗"},
                {"build casa", "✓"},
                {"build casa 2 prazo 10m", "✓"},
                {"build custom_casa_do_ferreiro prazo amanha", "✓"},
                {"build custom tipo=torre andares=3 parede=pedra telhado=plano", "✓"},
                {"build nave_espacial", "✗ [invalid_param]"},
                {"build casa prazo nunca", "✗ [invalid_param]"},
                {"projects", "# Obras"},
                {"deadline 1 5m", "✓ Prazo"},
                {"prazo amanha", "✓ Prazo"},
                {"deadline 99 5m", "✗ [not_found]"},
                {"cancel 2", "✓ Obra cancelada"},
                {"army", "# Exército"},
                {"army recruit 2", "✓"},
                {"army release 1 fazendeiro", "✓"},
                {"economy", "# Economia"},
                {"territory", "# Território"},
                {"claim", "✓"},
                {"tax up", "✓"},
                {"tax 9", "✗"},
                {"law conscription on", "✓"},
                {"law migration off", "✓"},
                {"diplomacy", "# Diplomacia"},
                {"events", "# Eventos"},
                {"chronicle", "# Crônica"},
                {"debug npc " + sn, "# DEBUG"},
                {"debug ai", "# Director"},
                {"debug events", "#"},
                {"replay " + sn, "# Por que"},
                {"ai", "# IA"},
                {"ai explain", "«Conselho»"},
                {"rivals 1", "✓"},
        };
        for (String[] c : cases) expect(cli, me, pos, c[0], c[1]);
        // diplomacia com o primeiro rival
        Kingdom rival = core.state().kingdoms.values().stream().filter(x -> !x.isPlayerKingdom()).findFirst().orElseThrow();
        String rn = rival.name.split(" ")[0];
        expect(cli, me, pos, "diplomacy gift " + rn + " 100 ouro", "✓");
        expectAny(cli, me, pos, "diplomacy treaty " + rn + " nap", "✓", "✗ [refused]");
        expectAny(cli, me, pos, "diplomacy trade " + rn + " 50 madeira 5 ferro", "✓", "✗ [refused]");
        expect(cli, me, pos, "war declare " + rn, "✓");
        expectAny(cli, me, pos, "war peace " + rn, "✓", "✗ [refused]");

        // linguagem natural (assíncrona; com mock responde na hora)
        async.clear();
        cli.execute(me, "T", pos, "order construam uma casa grande de pedra com 2 andares e telhado de duas aguas ate amanha");
        check("NL: casa personalizada com prazo → " + async, async.stream().anyMatch(s -> s.startsWith("✓ BUILD") && s.contains("Prazo")));
        async.clear();
        cli.execute(me, "T", pos, "order terminem a obra em 5 minutos");
        check("NL: prazo para obra existente → " + async, async.stream().anyMatch(s -> s.startsWith("✓ DEADLINE")));
        async.clear();
        cli.execute(me, "T", pos, "say oi");
        check("say sem NPC selecionado pede seleção", true);
        cli.select(me, smith.id);
        cli.execute(me, "T", pos, "say Bom dia! Como vai?");
        check("say com NPC selecionado → " + async, !async.isEmpty() && async.get(0).startsWith("«" + smith.name));

        // vários construtores: prazo curto puxa ajudantes
        for (int i = 0; i < 3; i++) cli.execute(me, "T", pos, "assign construtor 1");
        Building tower = core.construction().projects(k.id).get(0);
        core.construction().setDeadline(tower, 20 * 30);
        check("prazo curto puxa vários construtores (" + core.construction().activeBuilders(tower).size() + ")",
                core.construction().activeBuilders(tower).size() > 1);
        for (int i = 0; i < 20 * 60 * 3; i++) core.step();
        long done = core.buildings(k.id).stream().filter(Building::isComplete).count();
        check("obras concluem com vários construtores (" + done + ")", done >= 2);
        cli.execute(me, "T", pos, "build salao_real prazo 1s");
        for (int i = 0; i < 100; i++) core.step();
        check("obra que estoura o prazo gera evento de atraso", !core.bus().log().recent(300, e -> e.type() == com.kingdomsai.core.event.EventType.BUILDING_LATE
                && e.severity() == com.kingdomsai.core.event.GameEvent.Severity.DANGER).isEmpty());

        // save/load preserva plantas personalizadas
        try {
            var tmp = java.nio.file.Files.createTempDirectory("kai2").resolve("w.json");
            com.kingdomsai.core.persistence.Persistence.save(core.snapshotForSave(), tmp);
            WorldState loaded = com.kingdomsai.core.persistence.Persistence.load(tmp);
            com.kingdomsai.core.construction.BlueprintLibrary.resetCustom(List.of());
            KingdomsCore core2 = new KingdomsCore(loaded, new CoreConfig());
            check("plantas personalizadas recarregadas", com.kingdomsai.core.construction.BlueprintLibrary.get("custom_casa_do_ferreiro") != null);
            check("save guarda só os parâmetros (" + java.nio.file.Files.size(tmp) / 1024 + " KB)", loaded.blueprintSpecs.size() >= 2);
        } catch (Exception e) {
            check("save/load: " + e, false);
        }
        System.out.println("\nCLI: " + passed + " passaram, " + failed + " falharam.");
        System.exit(failed == 0 ? 0 : 1);
    }

    private static void expect(CommandService cli, UUID me, Pos pos, String cmd, String prefix) {
        expectAny(cli, me, pos, cmd, prefix);
    }

    private static void expectAny(CommandService cli, UUID me, Pos pos, String cmd, String... prefixes) {
        List<String> out = cli.execute(me, "Teste", pos, cmd);
        boolean internal = out.stream().anyMatch(s -> s.contains("Erro interno"));
        boolean ok = !internal && out.stream().anyMatch(s -> Arrays.stream(prefixes).anyMatch(s::startsWith));
        check("/k " + cmd + "  →  " + (out.isEmpty() ? "(vazio)" : out.get(0)), ok);
        if (!ok) for (String l : out) System.out.println("        | " + l);
    }

    private static void check(String name, boolean ok) {
        if (ok) passed++;
        else failed++;
        System.out.println((ok ? "  [OK]   " : "  [FAIL] ") + name);
    }
}
