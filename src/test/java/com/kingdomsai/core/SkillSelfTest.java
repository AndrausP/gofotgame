package com.kingdomsai.core;

import com.kingdomsai.core.cli.CommandService;
import com.kingdomsai.core.common.Pos;
import com.kingdomsai.core.construction.Blueprint;
import com.kingdomsai.core.construction.BlueprintLibrary;
import com.kingdomsai.core.construction.Building;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.kingdom.KingdomPersonality;
import com.kingdomsai.core.llm.LlmConfig;
import com.kingdomsai.core.npc.*;
import com.kingdomsai.core.persistence.Persistence;
import com.kingdomsai.core.port.PhysicalPort;
import com.kingdomsai.core.port.WorldPort;
import com.kingdomsai.core.skill.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Ordens com as mãos num mundo de mentira (blocos, baús, receitas): cada regra de jogo é verificada —
 * proteção de construções, baús e terra alheia; inundação; buraco com escada; árvore + replantio;
 * receitas encadeadas buscando no baú; mochila cheia esvaziando no armazém; chamado pausando a ordem.
 */
public final class SkillSelfTest {
    private static int passed, failed;
    private static KingdomsCore core;
    private static CommandService cli;
    private static UUID player;
    private static final List<String> async = new ArrayList<>();
    private static final FakeWorld world = new FakeWorld();
    private static long dayTime = 6000;

    public static void main(String[] args) throws Exception {
        int[] r = run();
        System.out.println("\n" + r[0] + " passaram, " + r[1] + " falharam.");
        System.exit(r[1] == 0 ? 0 : 1);
    }

    public static int[] run() throws Exception {
        passed = failed = 0;
        System.out.println("\n# Habilidades com as mãos (quebrar, baús, fabricar)");
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
        Npc miner = prof(k, Profession.MINER), lumber = prof(k, Profession.LUMBERJACK), smith = prof(k, Profession.BLACKSMITH);
        Npc farmer = prof(k, Profession.FARMER);
        king(new Pos(0, 64, 0), null);

        // --- 1. regras do planejador
        say(miner, "Cave um buraco aqui.");
        check("sem mira → pede para mirar", async.stream().anyMatch(s -> s.contains("Mire")));

        king(new Pos(0, 64, 0), new Pos(10, 63, 10));
        say(miner, "Cave um buraco 3x3x3 aqui.");
        PhysicalJob dig = job(miner);
        check("'cave um buraco 3x3x3' vira ordem JOB", dig != null && async.stream().anyMatch(s -> s.startsWith("✓ JOB")));
        check("buraco fundo deixa escada (24 de 27 blocos)", dig != null && dig.tasks.get(0).total == 24);
        runUntilDone(dig, 120);
        check("buraco cavado de verdade (blocos viraram ar)", world.isAir(new Pos(11, 61, 11)) && world.isAir(new Pos(10, 63, 10)));
        check("degraus ficaram (dá para sair do buraco)", !world.isAir(new Pos(9, 61, 9)) && !world.isAir(new Pos(9, 62, 9)) && !world.isAir(new Pos(10, 61, 9)));
        check("cava de cima para baixo (nunca quebra embaixo antes de cima)", world.topDownOk());
        check("terra foi para a mochila", miner.bag.getOrDefault("minecraft:dirt", 0) >= 20);
        print(core.skills().describe(dig));

        Building house = core.construction().planAt(k, BlueprintLibrary.get("house_small"), new Pos(30, 64, 30), true);
        house.status = Building.Status.COMPLETE;
        house.placed = house.progress = house.blueprint().blockCount();
        world.set(new Pos(31, 64, 31), "minecraft:oak_planks");
        king(new Pos(25, 64, 25), new Pos(31, 64, 31));
        say(miner, "Quebre esse bloco.");
        check("não quebra construção do reino", async.stream().anyMatch(s -> s.contains("não destruo construções")));

        world.set(new Pos(-10, 63, -10), "minecraft:chest");
        world.chests.put(new Pos(-10, 63, -10), new TreeMap<>(Map.of("minecraft:iron_ingot", 3, "minecraft:oak_log", 2)));
        world.set(new Pos(-12, 63, -10), "minecraft:water");
        king(new Pos(-8, 64, -8), new Pos(-10, 63, -10));
        var r = jobAction(k, "npc", miner.name, "kind", "dig", "size", "5x5x1");
        print(r);
        check("baú no meio da área fica de pé (aviso)", r.ok() && r.message().contains("baú(s)/fornalha(s) ficam"));
        check("bloco colado na água fica (sem inundação)", r.message().contains("perto de água/lava"));
        core.skills().cancel(job(miner), "teste");

        Kingdom rival = core.foundKingdom("Reino Vizinho", null, "Rival", new Pos(400, 64, 0), KingdomPersonality.balanced(), 3);
        king(new Pos(290, 64, 0), new Pos(292, 63, 0));
        r = jobAction(k, "npc", miner.name, "kind", "break");
        check("terra de outro reino → 'seria invasão'", !r.ok() && r.message().contains("invasão") && r.message().contains(rival.name));
        king(new Pos(0, 64, 0), new Pos(5, 63, 5));
        r = jobAction(k, "npc", miner.name, "kind", "dig", "size", "20x20x20");
        check("área grande demais é recusada", !r.ok() && r.message().contains("grande demais"));
        r = jobAction(k, "npc", miner.name, "kind", "break", "x", "200", "y", "63", "z", "0");
        check("longe do rei (>64) é recusado", !r.ok() && r.message().contains("onde o rei pode ver"));
        r = jobAction(k, "npc", farmer.name, "kind", "break", "x", "6", "y", "50", "z", "6");
        print(r);
        check("sem picareta: passa no armazém e pega a do reino", r.ok() && r.message().contains("passar no armazém: picareta"));
        core.skills().cancel(job(farmer), "teste");
        Map<String, Integer> goods = new TreeMap<>(k.goods);
        k.goods.keySet().removeIf(id -> id.endsWith("_pickaxe"));
        r = jobAction(k, "npc", farmer.name, "kind", "break", "x", "6", "y", "50", "z", "6");
        print(r);
        check("sem picareta nem no armazém: avisa que pedra não rende", r.ok() && r.message().contains("não tem picareta")
                && r.message().contains("não render") && !r.message().contains("passar no armazém: picareta"));
        core.skills().cancel(job(farmer), "teste");
        k.goods.clear();
        k.goods.putAll(goods);

        // --- 2. árvore: corta tronco e folhas, replanta a muda
        world.tree(new Pos(20, 64, -20));
        king(new Pos(16, 64, -16), new Pos(20, 65, -20));
        say(lumber, "Corte essa árvore.");
        PhysicalJob chop = job(lumber);
        check("'corte essa árvore' vira ordem", chop != null);
        runUntilDone(chop, 120);
        check("tronco inteiro cortado (e as folhas)", world.isAir(new Pos(20, 65, -20)) && world.isAir(new Pos(20, 68, -20))
                && world.isAir(new Pos(21, 68, -19)));
        check("toras na mochila", lumber.bag.getOrDefault("minecraft:oak_log", 0) == 5);
        check("muda replantada no lugar", "minecraft:oak_sapling".equals(world.id(new Pos(20, 64, -20))));
        world.set(new Pos(40, 64, -40), "minecraft:oak_log");
        world.set(new Pos(40, 65, -40), "minecraft:oak_log");
        king(new Pos(36, 64, -36), new Pos(40, 64, -40));
        r = jobAction(k, "npc", lumber.name, "kind", "chop");
        check("tronco sem folhas não é árvore (protege vigas)", !r.ok() && r.message().contains("não parece uma árvore"));

        // --- 3. baús
        king(new Pos(-8, 64, -8), new Pos(-10, 63, -10));
        say(miner, "Pegue 2 barras de ferro desse baú.");
        PhysicalJob take = job(miner);
        runUntilDone(take, 60);
        check("pegou 2 barras de verdade do baú", miner.bag.getOrDefault("minecraft:iron_ingot", 0) == 2
                && world.chests.get(new Pos(-10, 63, -10)).get("minecraft:iron_ingot") == 1);
        check("abriu e fechou a tampa do baú", world.anims.contains("CHEST_OPEN") && world.anims.contains("CHEST_CLOSE"));
        say(miner, "Guarde as barras de ferro nesse baú.");
        runUntilDone(job(miner), 60);
        check("guardou de volta (baú com 3)", world.chests.get(new Pos(-10, 63, -10)).get("minecraft:iron_ingot") == 3
                && !miner.bag.containsKey("minecraft:iron_ingot"));
        world.set(new Pos(300, 63, 2), "minecraft:chest");
        world.chests.put(new Pos(300, 63, 2), new TreeMap<>(Map.of("minecraft:diamond", 9)));
        king(new Pos(296, 64, 2), new Pos(300, 63, 2));
        r = jobAction(k, "npc", miner.name, "kind", "take", "item", "diamante", "from", "look");
        check("baú de outro reino → 'seria roubo'", !r.ok() && r.message().contains("roubo"));

        // --- 4. fabricar com planejamento: picareta de ferro = 3 barras (baú) + 2 gravetos (← tábuas ← tora do baú)
        world.set(new Pos(-6, 64, -10), "minecraft:crafting_table");
        king(new Pos(-8, 64, -8), new Pos(-10, 63, -10));
        say(farmer, "Faça uma espada de ferro.");
        check("fazendeiro não forja espada de ferro", async.stream().anyMatch(s -> s.contains("trabalho de ferreiro")));
        say(smith, "Faça uma picareta de ferro e me entregue.");
        PhysicalJob craft = job(smith);
        check("planejou buscar e fabricar os ingredientes", craft != null && craft.tasks.size() >= 5
                && async.stream().anyMatch(s -> s.contains("graveto") && s.contains("planejado"))
                && async.stream().anyMatch(s -> s.contains("pegar 3 barra de ferro")));
        runUntilDone(craft, 180);
        print(core.skills().describe(craft));
        check("rei recebeu a picareta de ferro", world.given.getOrDefault("minecraft:iron_pickaxe", 0) == 1);
        check("ingredientes saíram do baú (sem duplicar)", !world.chests.get(new Pos(-10, 63, -10)).containsKey("minecraft:iron_ingot")
                && world.chests.get(new Pos(-10, 63, -10)).getOrDefault("minecraft:oak_log", 0) == 1);
        check("sobras ficam com o ferreiro (2 gravetos, 2 tábuas: 4−2 viraram gravetos)", smith.bag.getOrDefault("minecraft:stick", 0) == 2
                && smith.bag.getOrDefault("minecraft:oak_planks", 0) == 2);
        r = jobAction(k, "npc", smith.name, "kind", "craft", "item", "picareta de ferro");
        check("sem ferro em lugar nenhum → diz o que falta", !r.ok() && r.message().contains("Faltam 3 barra de ferro"));
        world.set(new Pos(-6, 64, -10), "minecraft:air");
        r = jobAction(k, "npc", smith.name, "kind", "craft", "item", "baú");
        check("sem bancada por perto → recusa e explica", !r.ok() && r.message().contains("bancada"));

        // --- 5. mochila cheia esvazia no armazém; chamado pausa; noite não para; cancelar
        Building storage = core.construction().planAt(k, BlueprintLibrary.get("storage"), new Pos(-30, 64, 10), true);
        storage.status = Building.Status.COMPLETE;
        storage.placed = storage.progress = storage.blueprint().blockCount();
        for (var pl : storage.blueprint().placements()) // os baús do armazém, onde a planta os põe
            if (pl.material() == com.kingdomsai.core.construction.Material.CHEST) {
                Pos c = storage.origin.offset(pl.x(), pl.y(), pl.z());
                world.set(c, "minecraft:chest");
                world.chests.put(c, new TreeMap<>());
            }
        miner.bag.clear();
        king(new Pos(40, 64, 40), new Pos(40, 63, 40));
        r = jobAction(k, "npc", miner.name, "kind", "dig", "size", "7x7x7");
        print(r);
        check("sem picareta → passa no armazém antes e pega a do reino", r.ok() && r.message().contains("passar no armazém") && r.message().contains("picareta"));
        PhysicalJob big = job(miner);
        PhysicalJob.Task digTask = big.tasks.stream().filter(t -> t.kind == PhysicalJob.Kind.BREAK).findFirst().orElseThrow();
        seconds(5);
        print(cli.execute(player, "Andraus", new Pos(40, 64, 40), "call " + miner.name));
        seconds(2);
        check("chamado do rei PAUSA a ordem (não falha)", big.status == PhysicalJob.Status.PAUSED);
        print(cli.execute(player, "Andraus", new Pos(40, 64, 40), "dismiss " + miner.name));
        dayTime = 15000;
        seconds(3);
        check("dispensado → retoma; ordem direta segue de noite", big.status == PhysicalJob.Status.ACTIVE);
        dayTime = 6000;
        world.set(digTask.blocks.get(5), "minecraft:chest");
        world.chests.put(digTask.blocks.get(5), new TreeMap<>(Map.of("minecraft:bread", 1)));
        Pos chestPlaced = digTask.blocks.get(5);
        for (int s = 0; s < 120 && big.current() != digTask; s++) seconds(1);
        check("buscou a picareta no armazém antes de cavar", Inventory.bestTool(miner, "pickaxe") != null);
        // mochila quase cheia de tralha: no meio do buraco ele tem de voltar, guardar e retornar
        String[] junk = {"andesite", "diorite", "granite", "gravel", "sand", "clay_ball", "flint", "bone", "string", "feather",
                "leather", "paper", "book", "brick", "glass", "wool", "white_wool", "red_wool", "blue_wool", "green_wool", "lime_wool"};
        for (String j : junk) miner.bag.put("minecraft:" + j, 64);
        runUntilDone(big, 900);
        print(core.skills().describe(big));
        check("baú colocado no meio do trabalho é poupado", "minecraft:chest".equals(world.id(chestPlaced)));
        List<Pos> stChests = core.treasury().chests(k);
        check("mochila cheia → voltou, guardou no baú do armazém e retomou", !stChests.isEmpty()
                && stChests.stream().anyMatch(c -> world.chests.get(c).containsKey("minecraft:flint"))
                && big.log.stream().anyMatch(l -> l.contains("Mochila cheia")) && big.log.stream().anyMatch(l -> l.contains("Guardou no armazém")));
        check("guardou a tralha mas ficou com o kit (picareta e ração)", Inventory.bestTool(miner, "pickaxe") != null && !miner.bag.containsKey("minecraft:flint"));
        check("ordem grande concluída", big.status == PhysicalJob.Status.DONE);

        king(new Pos(0, 64, 0), new Pos(-3, 63, 3));
        say(miner, "Cave um buraco 3x3 aqui.");
        say(miner, "Pare com isso.");
        check("'pare com isso' cancela a ordem física", async.stream().anyMatch(s -> s.startsWith("✓ CANCEL_JOB")) && miner.jobId == null);

        // --- 6. inalcançável é pulado, não trava
        king(new Pos(0, 64, 0), new Pos(3, 63, -3));
        r = jobAction(k, "npc", lumber.name, "kind", "break");
        PhysicalJob stuck = job(lumber);
        stuck.tasks.removeIf(t -> t.auto); // só o bloco: a ida ao armazém já foi testada acima
        lumber.materialized = true; // entidade "presa": o Core não a move
        lumber.pos = new Pos(-60, 64, 0);
        runUntilDone(stuck, SkillSystem.MOVE_TIMEOUT + 10);
        lumber.materialized = false;
        check("bloco inalcançável (preso longe) é pulado após " + SkillSystem.MOVE_TIMEOUT + " s", stuck.log.stream().anyMatch(l -> l.contains("pulei"))
                && stuck.status == PhysicalJob.Status.DONE);

        // --- 7. save/load
        Path tmp = Files.createTempDirectory("kai-skill").resolve("kingdomsai.json");
        Persistence.save(core.snapshotForSave(), tmp);
        WorldState loaded = Persistence.load(tmp);
        check("save/load preserva ordens e mochilas", loaded.jobs.size() == core.state().jobs.size()
                && loaded.npcs.get(smith.id).bag.equals(smith.bag));
        print(cli.execute(player, "Andraus", new Pos(0, 64, 0), "jobs"));
        return new int[]{passed, failed};
    }

    // ------------------------------------------------------------------ util

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

    private static com.kingdomsai.core.action.ActionResult jobAction(Kingdom k, String... kv) {
        return core.actions().execute(com.kingdomsai.core.action.ActionRequest.of(k.id, player,
                com.kingdomsai.core.action.ActionRequest.ActorKind.PLAYER, com.kingdomsai.core.action.ActionType.JOB,
                com.kingdomsai.core.action.ActionRequest.Source.TEST, kv));
    }

    private static void say(Npc n, String text) {
        async.clear();
        Pos at = core.playerPos(player);
        KingdomsCore.Look look = core.playerLook(player);
        cli.execute(player, "Andraus", at, "npc talk " + n.name + " " + text);
        core.updatePlayerLook(player, look == null ? null : look.block(), "south");
        print(async);
    }

    private static void runUntilDone(PhysicalJob j, int maxSeconds) {
        for (int s = 0; s < maxSeconds && j != null && j.status.live(); s++) seconds(1);
    }

    private static void seconds(int s) {
        for (int i = 0; i < s * 20; i++) core.step();
    }

    private static void check(String name, boolean ok) {
        if (ok) passed++;
        else failed++;
        System.out.println((ok ? "  [OK]   " : "  [FAIL] ") + name);
    }

    private static void print(com.kingdomsai.core.action.ActionResult r) {
        System.out.println("    | " + (r.ok() ? "✓ " : "✗ [" + r.code() + "] ") + r.message().replace("\n", "\n    | "));
    }

    private static void print(List<String> lines) {
        for (String l : lines) System.out.println("    | " + l);
    }

    // ------------------------------------------------------------------ mundo de mentira

    /** Chão plano: grama em y=63, terra 60..62, pedra abaixo, bedrock em 0. */
    static final class FakeWorld implements WorldPort, PhysicalPort {
        final Map<Pos, String> blocks = new HashMap<>();
        final Map<Pos, Map<String, Integer>> chests = new HashMap<>();
        final Map<String, Integer> given = new TreeMap<>();
        final List<String> anims = new ArrayList<>();
        final List<Pos> broken = new ArrayList<>();
        int leafCounter;
        /** null = tudo carregado; senão só perto do "jogador" (raio viewRadius) e os chunks forçados. */
        Pos viewer;
        int viewRadius = 128;
        final Set<String> forced = new HashSet<>();
        final Set<String> playerForced = new HashSet<>();
        int forceCalls;
        /** Hora do dia deste mundo (-1 = a do SkillSelfTest). */
        long time = -1;
        /** Plantações: crescimento 0..100 por posição de trigo. */
        final Map<Pos, Integer> crops = new HashMap<>();
        /** Monstros que os súditos materializados podem ver. */
        final List<PhysicalPort.Sighting> monsters = new ArrayList<>();

        public List<PhysicalPort.Sighting> threatsNear(Pos p, int radius) {
            List<PhysicalPort.Sighting> out = new ArrayList<>();
            for (PhysicalPort.Sighting m : monsters) if (m.pos().distXZ(p) <= radius) out.add(m);
            return out;
        }

        String id(Pos p) {
            String s = blocks.get(p);
            if (s != null) return s;
            if (p.y() > 63) return "minecraft:air";
            if (p.y() == 63) return "minecraft:grass_block";
            if (p.y() >= 60) return "minecraft:dirt";
            return p.y() <= 0 ? "minecraft:bedrock" : "minecraft:stone";
        }

        boolean isAir(Pos p) {
            return id(p).equals("minecraft:air");
        }

        void set(Pos p, String id) {
            blocks.put(p, id);
            if (!id.equals("minecraft:chest")) chests.remove(p);
        }

        void tree(Pos base) {
            for (int y = 0; y < 5; y++) set(base.offset(0, y, 0), "minecraft:oak_log");
            for (int dx = -2; dx <= 2; dx++)
                for (int dz = -2; dz <= 2; dz++)
                    for (int dy = 3; dy <= 5; dy++) {
                        Pos p = base.offset(dx, dy, dz);
                        if (isAir(p)) set(p, "minecraft:oak_leaves");
                    }
        }

        /** Nenhum bloco foi quebrado antes de um bloco acima dele na mesma coluna (dentro da mesma ordem). */
        boolean topDownOk() {
            for (int i = 0; i < broken.size(); i++)
                for (int j = i + 1; j < broken.size(); j++) {
                    Pos a = broken.get(i), b = broken.get(j);
                    if (a.x() == b.x() && a.z() == b.z() && b.y() > a.y() && Math.abs(i - j) < 40) return false;
                }
            return true;
        }

        // WorldPort
        public SiteCheck checkSite(int x, int z, Blueprint bp) {
            return new SiteCheck(SiteCheck.Kind.OK, 64, 1.0);
        }

        public long dayTime() {
            return time >= 0 ? time : dayTime;
        }

        public boolean isLoaded(int x, int z) {
            if (Math.abs(x) >= 1000 || Math.abs(z) >= 1000) return false;
            if (viewer == null) return true;
            String key = Math.floorDiv(x, 16) + ":" + Math.floorDiv(z, 16);
            return viewer.distXZ(new Pos(x, 64, z)) <= viewRadius || forced.contains(key) || playerForced.contains(key);
        }

        public int surfaceY(int x, int z) {
            return 64;
        }

        // PhysicalPort
        public boolean isLoaded(Pos p) {
            return isLoaded(p.x(), p.z());
        }

        public BlockInfo block(Pos p) {
            String id = id(p);
            boolean air = id.equals("minecraft:air"), water = id.equals("minecraft:water");
            boolean nearFluid = false;
            for (Pos n : List.of(p.offset(1, 0, 0), p.offset(-1, 0, 0), p.offset(0, 0, 1), p.offset(0, 0, -1), p.offset(0, 1, 0)))
                if (id(n).equals("minecraft:water")) nearFluid = true;
            boolean ore = id.endsWith("_ore");
            double hard = ore ? 3.0 : switch (id) {
                case "minecraft:wheat", "minecraft:torch", "minecraft:oak_sapling" -> 0.0;
                case "minecraft:stone" -> 1.5;
                case "minecraft:dirt" -> 0.5;
                case "minecraft:grass_block" -> 0.6;
                case "minecraft:oak_log", "minecraft:oak_planks" -> 2.0;
                case "minecraft:oak_leaves" -> 0.2;
                case "minecraft:bedrock" -> -1;
                default -> 1.0;
            };
            String tool = id.equals("minecraft:stone") || ore ? "pickaxe" : id.contains("dirt") || id.contains("grass") || id.contains("farmland") ? "shovel"
                    : id.contains("log") || id.contains("planks") ? "axe" : null;
            String drop = switch (id) {
                case "minecraft:stone" -> "minecraft:cobblestone";
                case "minecraft:iron_ore" -> "minecraft:raw_iron";
                case "minecraft:coal_ore" -> "minecraft:coal";
                case "minecraft:farmland" -> "minecraft:dirt";
                case "minecraft:grass_block" -> "minecraft:dirt";
                case "minecraft:oak_leaves" -> null;
                default -> air ? null : id;
            };
            return new BlockInfo(id, air, !air && !water && hard >= 0, hard, id.equals("minecraft:chest"), nearFluid, water,
                    id.endsWith("_log"), id.endsWith("_leaves"), tool, id.equals("minecraft:stone") || ore, drop);
        }

        public Map<String, Integer> breakBlock(UUID npc, Pos p, String tool) {
            BlockInfo info = block(p);
            blocks.put(p, "minecraft:air");
            broken.add(p);
            if (info.leaves()) return leafCounter++ % 4 == 0 ? Map.of("minecraft:oak_sapling", 1) : Map.of();
            if (info.id().equals("minecraft:wheat")) { // maduro: trigo + semente; verde: só a semente
                int g = Objects.requireNonNullElse(crops.remove(p), 0);
                return g >= 100 ? Map.of("minecraft:wheat", 1, "minecraft:wheat_seeds", 1) : Map.of("minecraft:wheat_seeds", 1);
            }
            // a ferramenta chega como item de verdade da mochila ("minecraft:stone_pickaxe")
            if (info.needsTool() && (tool == null || !tool.endsWith("_" + info.tool()))) return Map.of();
            return info.drop() == null ? Map.of() : Map.of(info.drop(), 1);
        }

        public boolean place(UUID npc, Pos p, String blockId) {
            if (!isAir(p)) return false;
            String below = id(p.offset(0, -1, 0));
            if (blockId.equals("minecraft:wheat") && !below.equals("minecraft:farmland")) return false; // trigo só em terra arada
            if (blockId.endsWith("_sapling") && !below.matches(".*(dirt|grass).*")) return false;
            if (blockId.endsWith("torch") && below.equals("minecraft:air")) return false;
            blocks.put(p, blockId);
            if (blockId.equals("minecraft:wheat")) crops.put(p, 0);
            return true;
        }

        public int growth(Pos p) {
            return id(p).equals("minecraft:wheat") ? crops.getOrDefault(p, 0) : -1;
        }

        public boolean till(UUID npc, Pos p) {
            String id = id(p);
            if (!(id.equals("minecraft:dirt") || id.equals("minecraft:grass_block")) || !isAir(p.offset(0, 1, 0))) return false;
            blocks.put(p, "minecraft:farmland");
            return true;
        }

        public Map<String, Integer> container(Pos p) {
            return id(p).equals("minecraft:chest") ? chests.computeIfAbsent(p, x -> new TreeMap<>()) : null;
        }

        public int take(UUID npc, Pos chest, String item, int n) {
            Map<String, Integer> c = container(chest);
            int have = c.getOrDefault(item, 0), t = Math.min(have, n);
            if (have - t <= 0) c.remove(item);
            else c.put(item, have - t);
            return t;
        }

        public Map<String, Integer> put(UUID npc, Pos chest, Map<String, Integer> items) {
            Map<String, Integer> c = container(chest);
            items.forEach((k, v) -> c.merge(k, v, Integer::sum));
            return Map.of();
        }

        public List<Recipe> recipes(String item) {
            List<String> planks = List.of("minecraft:oak_planks", "minecraft:spruce_planks", "minecraft:birch_planks");
            return switch (item) {
                case "minecraft:iron_pickaxe" -> List.of(new Recipe(item, 1, List.of(List.of("minecraft:iron_ingot"), List.of("minecraft:iron_ingot"),
                        List.of("minecraft:iron_ingot"), List.of("minecraft:stick"), List.of("minecraft:stick")), Station.CRAFTING_TABLE));
                case "minecraft:iron_sword" -> List.of(new Recipe(item, 1, List.of(List.of("minecraft:iron_ingot"), List.of("minecraft:iron_ingot"),
                        List.of("minecraft:stick")), Station.CRAFTING_TABLE));
                case "minecraft:stick" -> List.of(new Recipe(item, 4, List.of(planks, planks), Station.NONE));
                case "minecraft:oak_planks" -> List.of(new Recipe(item, 4, List.of(List.of("minecraft:oak_log", "minecraft:oak_wood")), Station.NONE));
                case "minecraft:chest" -> List.of(new Recipe(item, 1, Collections.nCopies(8, planks), Station.CRAFTING_TABLE));
                case "minecraft:torch" -> List.of(new Recipe(item, 4, List.of(List.of("minecraft:coal", "minecraft:charcoal"), List.of("minecraft:stick")), Station.NONE));
                case "minecraft:iron_ingot" -> List.of(new Recipe(item, 1, List.of(List.of("minecraft:raw_iron")), Station.FURNACE));
                default -> List.of();
            };
        }

        public boolean itemExists(String item) {
            return item.matches("minecraft:(iron_pickaxe|iron_sword|stick|oak_planks|chest|torch|iron_ingot|oak_log|diamond|raw_iron|coal|dirt|cobblestone)");
        }

        public Pos findNear(Pos p, String blockId, int radius) {
            Pos best = null;
            for (var e : blocks.entrySet())
                if (e.getValue().equals(blockId) && Math.abs(e.getKey().x() - p.x()) <= radius && Math.abs(e.getKey().z() - p.z()) <= radius
                        && (best == null || e.getKey().distSq(p) < best.distSq(p))) best = e.getKey();
            return best;
        }

        public boolean give(UUID npc, UUID player, Map<String, Integer> items) {
            items.forEach((k, v) -> given.merge(k, v, Integer::sum));
            return true;
        }

        public void animate(UUID npc, Pos at, Anim anim, int stage) {
            if (anim != Anim.SWING && anim != Anim.CRACK) anims.add(anim.name());
        }

        /** Blocos "protegidos por claim" (outro mod) e itens soltos no chão. */
        final Set<Pos> claimed = new HashSet<>();
        final Map<String, Integer> dropped = new TreeMap<>();

        public boolean mayBreak(UUID player, Pos p) {
            return !claimed.contains(p);
        }

        public void drop(Pos p, Map<String, Integer> items) {
            items.forEach((k, v) -> dropped.merge(k, v, Integer::sum));
        }

        public boolean chunkForced(int chunkX, int chunkZ) {
            String k = chunkX + ":" + chunkZ;
            return forced.contains(k) || playerForced.contains(k);
        }

        public void forceChunk(int chunkX, int chunkZ, boolean on) {
            forceCalls++;
            if (on) forced.add(chunkX + ":" + chunkZ);
            else forced.remove(chunkX + ":" + chunkZ);
        }
    }
}
