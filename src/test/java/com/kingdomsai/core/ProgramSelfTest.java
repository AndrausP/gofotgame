package com.kingdomsai.core;

import com.kingdomsai.core.construction.program.*;

/** F5.1/F5.2: validação do programa arquitetônico, compilação determinística e verificações. */
public final class ProgramSelfTest {
    private static int passed, failed;

    public static void main(String[] args) {
        int[] r = run();
        System.out.println("\n" + r[0] + " passaram, " + r[1] + " falharam.");
        System.exit(r[1] == 0 ? 0 : 1);
    }

    public static int[] run() {
        passed = failed = 0;
        System.out.println("\n# Programa arquitetônico");
        for (String t : new String[]{"catedral", "mosteiro", "fortaleza", "torre"}) {
            BuildingProgram p = DefaultPrograms.forText(t);
            ProgramCompiler.Result r = ProgramCompiler.compile(p);
            System.out.println("    | " + t + ": " + (r.ok() ? r.blueprint().blockCount() + " passos, " + r.solidBlocks() + " blocos, " + r.blueprint().sizeX() + "x" + r.blueprint().sizeZ() + "x" + r.blueprint().sizeY() : r.errors()));
            check("programa-padrão \"" + t + "\" compila e passa nas verificações", r.ok());
        }
        BuildingProgram cat = DefaultPrograms.cathedral(Style.GOTHIC);
        ProgramCompiler.Result a = ProgramCompiler.compile(cat), b = ProgramCompiler.compile(BuildingProgram.parse(cat.toJson()));
        check("compila de forma idêntica duas vezes", a.ok() && b.ok() && a.blueprint().placements().equals(b.blueprint().placements()) && a.blueprint().id().equals(b.blueprint().id()));
        check("etapas em ordem: fundação → paredes → contrafortes → telhado → vitrais → interior", a.ok() && a.stages().stream().map(s -> s.stage().ordinal()).sorted().toList()
                .equals(a.stages().stream().map(s -> s.stage().ordinal()).toList()) && a.stages().stream().anyMatch(s -> s.stage() == ProgramCompiler.Stage.WINDOWS));
        check("custo coerente com os blocos", a.ok() && a.blueprint().cost().get(com.kingdomsai.core.kingdom.ResourceType.STONE) > 100);

        BuildingProgram over = DefaultPrograms.cathedral(Style.GOTHIC);
        BuildingProgram.Module t2 = over.modules.get(2);
        t2.attach = "nave";
        t2.side = "right";
        t2.type = "hall";
        t2.w = 10;
        t2.d = 10;
        t2.h = 6;
        over.modules.get(1).side = "right";
        over.modules.get(1).type = "hall";
        over.modules.get(1).w = 10;
        over.modules.get(1).d = 10;
        over.modules.get(1).h = 6;
        over.modules.get(1).floors = 0;
        check("rejeita módulos sobrepostos", hasError(ProgramCompiler.compile(over), "sobrep"));

        BuildingProgram tall = DefaultPrograms.cathedral(Style.GOTHIC);
        tall.modules.get(0).h = 30;
        tall.modules.get(0).w = 30;
        tall.modules.get(0).d = 30;
        tall.site.width = 64;
        tall.site.depth = 64;
        check("rejeita altura acima do limite", hasError(ProgramCompiler.compile(tall), "altura acima") || hasError(ProgramCompiler.compile(tall), "fora de"));

        BuildingProgram blocked = new BuildingProgram();
        blocked.style = "gothic";
        blocked.site.width = blocked.site.depth = 60;
        BuildingProgram.Module hall = new BuildingProgram.Module();
        hall.id = "nave";
        hall.type = "hall";
        hall.w = 8;
        hall.d = 12;
        hall.h = 6;
        BuildingProgram.Module wall = new BuildingProgram.Module();
        wall.id = "muro";
        wall.type = "wall_segment";
        wall.attach = "nave";
        wall.side = "front";
        wall.w = 8;
        wall.h = 6;
        blocked.modules.add(hall);
        blocked.modules.add(wall);
        check("rejeita porta sem acesso (frente bloqueada)", hasError(ProgramCompiler.compile(blocked), "porta sem acesso"));

        BuildingProgram bad = DefaultPrograms.cathedral(Style.GOTHIC);
        bad.modules.get(1).attach = "fantasma";
        check("rejeita attach para módulo inexistente", hasError(ProgramCompiler.compile(bad), "não existe"));
        BuildingProgram small = DefaultPrograms.cathedral(Style.GOTHIC);
        small.site.width = 10;
        check("rejeita obra que não cabe no terreno", hasError(ProgramCompiler.compile(small), "não cabe"));
        BuildingProgram style = DefaultPrograms.cathedral(Style.GOTHIC);
        style.style = "barroco";
        check("rejeita estilo desconhecido", hasError(ProgramCompiler.compile(style), "estilo"));
        try {
            voxelBuild();
            architect();
        } catch (Exception e) {
            e.printStackTrace();
            check("arquiteto sem exceção", false);
        }
        return new int[]{passed, failed};
    }

    /** Súditos constroem um programa até o fim no mundo voxel e os blocos conferem com o compilador. */
    private static void voxelBuild() {
        var host = new com.kingdomsai.voxel.VoxelHost(42);
        java.util.UUID pl = java.util.UUID.randomUUID();
        int g = host.world.groundY(0, 0);
        var king = new com.kingdomsai.core.common.Pos(0, g, 0);
        host.core.updatePlayerPos(pl, king);
        host.area.setViewer(pl, king);
        new com.kingdomsai.core.cli.CommandService(host.core).execute(pl, "Rei", king, "found Reino Obra");
        var k = host.core.kingdomOfPlayer(pl);
        k.civic = "off";
        k.add(com.kingdomsai.core.kingdom.ResourceType.STONE, 3000);
        k.add(com.kingdomsai.core.kingdom.ResourceType.WOOD, 3000);
        for (int i = 0; i < 20 * 120; i++) host.step();
        BuildingProgram prog = DefaultPrograms.fortress(Style.FORTRESS);
        prog.modules.removeIf(mm -> !mm.id.equals("torre-menagem") && !mm.id.equals("t1"));
        var res = ProgramCompiler.compile(prog);
        var out = host.core.architect().finishCompiled(k, prog, res, "teste", 0);
        check("obra do programa aberta no mundo voxel", out.ok());
        if (!out.ok()) return;
        var b = out.building();
        for (var n : host.core.citizens(k.id)) if (n.profession == com.kingdomsai.core.npc.Profession.BUILDER) b.builderIds.add(n.id);
        for (int i = 0; i < 20 * 3600 && !b.isComplete(); i++) {
            if (i % 40 == 0) for (var n : host.core.citizens(k.id)) if (n.pos != null && b.hasBuilder(n.id)) { }
            host.step();
        }
        System.out.println("    | obra " + b.status + " " + (int) b.percent() + "% — " + res.blueprint().blockCount() + " passos");
        check("os súditos terminam a obra do programa", b.isComplete());
        int ok = 0, total = 0;
        for (var pl2 : res.blueprint().placements()) {
            if (pl2.material() == com.kingdomsai.core.construction.Material.AIR || pl2.material() == com.kingdomsai.core.construction.Material.LIGHT) continue;
            total++;
            if (!host.world.get(b.origin.x() + pl2.x(), b.origin.y() + pl2.y(), b.origin.z() + pl2.z()).air()) ok++;
        }
        check("os blocos do mundo conferem com a planta compilada (" + ok + "/" + total + ")", total > 0 && ok >= total * 0.97);
    }

    private static void architect() throws Exception {
        // LLM falsa: 1ª resposta inválida (módulos sobrepostos), 2ª válida
        final String bad = "{\"style\":\"gothic\",\"modules\":[{\"id\":\"a\",\"type\":\"hall\",\"w\":8,\"d\":8,\"h\":6},{\"id\":\"b\",\"type\":\"hall\",\"attach\":\"zzz\",\"w\":8,\"d\":8,\"h\":6}]}";
        final String good = DefaultPrograms.tower(Style.GOTHIC).toJson();
        final java.util.List<String> users = new java.util.ArrayList<>();
        KingdomsCore core = new KingdomsCore(new WorldState(), new CoreConfig());
        core.setWorld(new SkillSelfTest.FakeWorld());
        core.llm().setClaudeCodeProvider(new com.kingdomsai.core.llm.LlmProvider() {
            public String name() {
                return "claude_code";
            }

            public java.util.concurrent.CompletableFuture<String> complete(com.kingdomsai.core.llm.LlmRequest r, com.kingdomsai.core.llm.LlmConfig c) {
                users.add(r.user());
                return java.util.concurrent.CompletableFuture.completedFuture(users.size() == 1 ? bad : good);
            }
        });
        com.kingdomsai.core.llm.LlmConfig cfg = new com.kingdomsai.core.llm.LlmConfig();
        cfg.provider = "claude_code";
        cfg.maxCallsPerMinute = 100;
        core.llm().configure(cfg);
        java.util.UUID pl = java.util.UUID.randomUUID();
        new com.kingdomsai.core.cli.CommandService(core).execute(pl, "Rei", new com.kingdomsai.core.common.Pos(0, 64, 0), "found Reino Arq");
        var k = core.kingdomOfPlayer(pl);
        k.add(com.kingdomsai.core.kingdom.ResourceType.STONE, 2000);
        k.add(com.kingdomsai.core.kingdom.ResourceType.WOOD, 2000);
        final java.util.List<com.kingdomsai.core.construction.program.Architect.Outcome> got = new java.util.ArrayList<>();
        core.architect().design(k, "construa uma catedral gótica", got::add);
        check("LLM inválida na 1ª, válida na 2ª: a obra nasce na segunda tentativa", got.size() == 1 && got.get(0).ok() && got.get(0).attempts() == 2 && got.get(0).source().equals("LLM"));
        check("a 2ª pergunta leva os erros do compilador", users.size() == 2 && users.get(1).contains("ERROS DA TENTATIVA ANTERIOR") && users.get(1).contains("não existe"));
        check("o pedido do rei vai marcado como não confiável", users.get(0).contains("<untrusted>construa uma catedral"));

        users.clear();
        got.clear();
        core.llm().setClaudeCodeProvider(new com.kingdomsai.core.llm.LlmProvider() {
            public String name() {
                return "claude_code";
            }

            public java.util.concurrent.CompletableFuture<String> complete(com.kingdomsai.core.llm.LlmRequest r, com.kingdomsai.core.llm.LlmConfig c) {
                users.add(r.user());
                return java.util.concurrent.CompletableFuture.completedFuture(bad);
            }
        });
        core.architect().design(k, "construa uma catedral gótica", got::add);
        check("duas respostas ruins: no máximo 2 tentativas e cai no programa-padrão", users.size() == 2 && got.size() == 1 && got.get(0).ok() && got.get(0).source().contains("programa-padrão"));

        KingdomsCore m = new KingdomsCore(new WorldState(), new CoreConfig());
        m.setWorld(new SkillSelfTest.FakeWorld());
        com.kingdomsai.core.llm.LlmConfig mc = new com.kingdomsai.core.llm.LlmConfig();
        mc.provider = "mock";
        m.llm().configure(mc);
        java.util.UUID p2 = java.util.UUID.randomUUID();
        var cli = new com.kingdomsai.core.cli.CommandService(m);
        cli.execute(p2, "Rei", new com.kingdomsai.core.common.Pos(0, 64, 0), "found Reino Regras");
        var k2 = m.kingdomOfPlayer(p2);
        k2.add(com.kingdomsai.core.kingdom.ResourceType.STONE, 2000);
        k2.add(com.kingdomsai.core.kingdom.ResourceType.WOOD, 2000);
        got.clear();
        m.architect().design(k2, "construa uma catedral", got::add);
        System.out.println("    | " + got.get(0).message());
        check("mock: \"construa uma catedral\" gera o programa-padrão", got.size() == 1 && got.get(0).ok() && got.get(0).source().equals("regras") && got.get(0).building() != null);
        var r = m.actions().execute(com.kingdomsai.core.action.ActionRequest.of(k2.id, p2, com.kingdomsai.core.action.ActionRequest.ActorKind.PLAYER,
                com.kingdomsai.core.action.ActionType.BUILD, com.kingdomsai.core.action.ActionRequest.Source.TEST, "blueprint", "program", "text", "uma fortaleza"));
        var wt = m.actions().execute(com.kingdomsai.core.action.ActionRequest.of(k2.id, p2, com.kingdomsai.core.action.ActionRequest.ActorKind.PLAYER,
                com.kingdomsai.core.action.ActionType.BUILD, com.kingdomsai.core.action.ActionRequest.Source.TEST, "blueprint", "watchtowers", "amount", "3"));
        System.out.println("    | " + wt.message());
        check("watchtowers: torres de vigia nos pontos altos da fronteira", wt.ok() || wt.code().equals("no_site"));
        check("BUILD blueprint=program passa pelo pipeline de validação", r.ok() || r.code().equals("no_site") || r.code().equals("insufficient_resources"));
    }

    private static boolean hasError(ProgramCompiler.Result r, String frag) {
        System.out.println("    | " + r.errors());
        return !r.ok() && r.errors().stream().anyMatch(e -> e.contains(frag));
    }

    private static void check(String name, boolean ok) {
        if (ok) passed++;
        else failed++;
        System.out.println((ok ? "  [OK]   " : "  [FAIL] ") + name);
    }
}
