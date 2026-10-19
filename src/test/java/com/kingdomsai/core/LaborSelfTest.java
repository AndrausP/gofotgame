package com.kingdomsai.core;

import com.kingdomsai.core.cli.CommandService;
import com.kingdomsai.core.common.Pos;
import com.kingdomsai.core.common.Text;
import com.kingdomsai.core.construction.BlueprintLibrary;
import com.kingdomsai.core.construction.Building;
import com.kingdomsai.core.construction.Material;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.kingdom.Marker;
import com.kingdomsai.core.kingdom.ResourceType;
import com.kingdomsai.core.llm.LlmConfig;
import com.kingdomsai.core.llm.RuleInterpreter;
import com.kingdomsai.core.npc.Npc;
import com.kingdomsai.core.npc.Profession;
import com.kingdomsai.core.persistence.Persistence;
import com.kingdomsai.core.skill.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * O súdito como corpo + estado no Core: kit por ofício (ferramenta, reserva, comida, consumíveis, equipamento) e o ciclo
 * de trabalho contínuo — lenhador (corta, replanta, troca o machado gasto), mineiro (galeria, tochas no escuro, minério
 * que vai à forja), fazendeiro (ara, planta, colhe, replanta, busca sementes); meta, noite, longe da vista e validações.
 */
public final class LaborSelfTest {
    private static int passed, failed;
    private static KingdomsCore core;
    private static CommandService cli;
    private static UUID player;
    private static final List<String> async = new ArrayList<>();
    private static SkillSelfTest.FakeWorld world;

    public static void main(String[] args) throws Exception {
        int[] r = run();
        System.out.println("\n" + r[0] + " passaram, " + r[1] + " falharam.");
        System.exit(r[1] == 0 ? 0 : 1);
    }

    public static int[] run() throws Exception {
        passed = failed = 0;
        System.out.println("\n# Mochila, kit por ofício e trabalho contínuo");
        world = new SkillSelfTest.FakeWorld();
        world.time = 6000;
        CoreConfig cfg = new CoreConfig();
        cfg.rivalKingdoms = 0;
        cfg.startingCitizens = 12; // inclui guarda e soldado
        core = new KingdomsCore(new WorldState(), cfg);
        core.setWorld(world);
        core.setPhysical(world);
        LlmConfig mock = new LlmConfig();
        mock.provider = "mock";
        core.llm().configure(mock);
        player = UUID.randomUUID();
        cli = new CommandService(core);
        cli.setNotifier((p, lines) -> async.addAll(lines));
        cli.execute(player, "Andraus", new Pos(0, 64, 0), "found Reino do Trabalho");
        Kingdom k = core.kingdomOfPlayer(player);
        k.openingAuto = false; // o roteiro de início tem o próprio teste (aqui ele faria outra fazenda)
        core.updatePlayerPos(player, new Pos(0, 64, 0));
        Building storage = complete(core.construction().planAt(k, BlueprintLibrary.get("storage"), new Pos(-30, 64, 10), true));
        for (var pl : storage.blueprint().placements())
            if (pl.material() == Material.CHEST) {
                Pos c = storage.origin.offset(pl.x(), pl.y(), pl.z());
                world.set(c, "minecraft:chest");
                world.chests.put(c, new TreeMap<>());
            }
        k.add(ResourceType.FOOD, 400); // celeiro cheio: a ração vem daqui (a fome é testada no fim)
        core.treasury().sync(k);

        Npc farmer = prof(k, Profession.FARMER), lumber = prof(k, Profession.LUMBERJACK), miner = prof(k, Profession.MINER);
        Npc builder = prof(k, Profession.BUILDER), smith = prof(k, Profession.BLACKSMITH), guard = prof(k, Profession.GUARD);
        Npc soldier = prof(k, Profession.SOLDIER);

        // --- 1. kit por ofício (chegam com o básico; nada de ferramenta imaginária)
        for (Npc n : List.of(farmer, lumber, miner, builder, smith, guard, soldier))
            System.out.println("    | " + n.name + " (" + n.profession.display + "): " + Kit.describe(n));
        check("fazendeiro: enxada, sementes, balde e comida", Inventory.bestTool(farmer, "hoe") != null && farmer.bag.getOrDefault("minecraft:wheat_seeds", 0) >= 4
                && farmer.bag.getOrDefault("minecraft:water_bucket", 0) == 1 && Inventory.foodCount(farmer) > 0);
        check("lenhador: machado + machado reserva, mudas e comida", Inventory.count(lumber, "axe") == 2
                && lumber.bag.getOrDefault("minecraft:oak_sapling", 0) > 0 && Inventory.foodCount(lumber) > 0);
        check("mineiro: picareta + reserva, tochas e comida", Inventory.count(miner, "pickaxe") == 2 && miner.bag.getOrDefault("minecraft:torch", 0) >= 4
                && Inventory.foodCount(miner) > 0);
        check("construtor: picareta, machado e comida", Inventory.bestTool(builder, "pickaxe") != null && Inventory.bestTool(builder, "axe") != null);
        check("ferreiro: carvão e combustível", smith.bag.getOrDefault("minecraft:coal", 0) > 0 && smith.bag.getOrDefault("minecraft:charcoal", 0) > 0);
        check("guarda: escudo, armadura e elmo vestidos", "minecraft:shield".equals(guard.gear.get("offhand"))
                && guard.gear.containsKey("chest") && guard.gear.containsKey("head"));
        check("soldado: armadura, escudo e suprimentos", soldier.gear.containsKey("chest") && soldier.gear.containsKey("offhand") && Inventory.foodCount(soldier) >= 3);
        check("ferramentas de pedra no começo (com durabilidade de verdade)", Inventory.bestTool(lumber, "axe").equals("minecraft:stone_axe")
                && Inventory.durability("minecraft:stone_axe") == 131 && Inventory.health(lumber, "minecraft:stone_axe") == 100);
        check("mochila de 27 espaços, pilhas de 64 (ferramenta ocupa 1)", Inventory.SLOTS == 27 && Inventory.maxStack("minecraft:oak_log") == 64
                && Inventory.maxStack("minecraft:iron_pickaxe") == 1);
        List<String> bag = cli.execute(player, "Andraus", new Pos(0, 64, 0), "bag " + miner.name);
        print(bag);
        check("/k bag mostra mochila, kit e espaços", bag.stream().anyMatch(l -> l.startsWith("Kit:")) && bag.stream().anyMatch(l -> l.contains("/27 espaços")));

        // --- 2. frases do chat → trabalho contínuo
        check("'produza madeira' → lenha", "wood".equals(RuleInterpreter.laborWord(Text.norm("Produza madeira"))));
        check("'trabalhe na mina' → mina", "ore".equals(RuleInterpreter.laborWord(Text.norm("Trabalhe na mina"))));
        check("'cuide da fazenda' / 'colha o trigo' → fazenda", "farm".equals(RuleInterpreter.laborWord(Text.norm("Cuide da fazenda")))
                && "farm".equals(RuleInterpreter.laborWord(Text.norm("Colha o trigo"))));
        check("'minere ferro e leve ao ferreiro' fica com a rotina (cadeia)", RuleInterpreter.laborWord(Text.norm("Minere ferro e leve ao ferreiro")) == null);
        check("'escreva uma carta dizendo que a colheita foi boa' não é trabalho na fazenda",
                RuleInterpreter.laborWord(Text.norm("Escreva uma carta para Ana dizendo que a colheita foi boa")) == null);
        check("'cuide da comida' / 'aumente a produção de madeira' ficam com o conselho", RuleInterpreter.laborWord(Text.norm("Cuide da comida")) == null
                && RuleInterpreter.laborWord(Text.norm("Aumente a produção de madeira")) == null);

        // --- 3. lenhador: vai ao bosque, corta as árvores permitidas, replanta, troca o machado gasto, guarda à noite
        Pos t1 = new Pos(40, 64, -40), t2 = new Pos(46, 64, -36), t3 = new Pos(36, 64, -46);
        for (Pos t : List.of(t1, t2, t3)) world.tree(t);
        k.markers.put(Marker.FOREST, t1);
        lumber.wear.put("minecraft:stone_axe", 129); // o machado está quase no fim
        say(lumber, "Produza madeira.");
        PhysicalJob wood = job(lumber);
        check("'produza madeira' vira trabalho contínuo do lenhador", wood != null && wood.continuous && "wood".equals(wood.labor)
                && async.stream().anyMatch(s -> s.contains("↻")));
        for (int s = 0; s < 600 && wood != null && !(world.isAir(t1) && world.isAir(t2) && world.isAir(t3) && wood.current() == null); s++) seconds(1);
        seconds(2);
        print(core.skills().describe(wood));
        check("cortou as três árvores de verdade (toras e folhas)", world.isAir(t1.offset(0, 2, 0)) && world.isAir(t2.offset(0, 4, 0)) && world.isAir(t3.offset(0, 1, 0)));
        check("replantou as mudas no lugar", "minecraft:oak_sapling".equals(world.id(t1)) && "minecraft:oak_sapling".equals(world.id(t2)));
        check("machado gasto quebrou e ele pegou o reserva", wood.log.stream().anyMatch(l -> l.contains("trocou pela reserva")) && Inventory.count(lumber, "axe") == 1);
        check("toras na mochila (15) e desgaste do machado novo anotado", lumber.bag.getOrDefault("minecraft:oak_log", 0) == 15
                && lumber.wear.getOrDefault("minecraft:stone_axe", 0) > 0);
        check("sem árvore no bosque: espera e tenta de novo (não termina)", wood.status == PhysicalJob.Status.WAITING && wood.reason.contains("árvore"));
        check("lenhador ganhou prática (habilidade lenhar)", lumber.skillXp.getOrDefault("lenhar", 0) >= 15);
        double woodBefore = k.get(ResourceType.WOOD);
        world.time = 14000; // noite
        for (int s = 0; s < 120 && wood.status != PhysicalJob.Status.PAUSED; s++) seconds(1);
        print(core.skills().describe(wood));
        check("à noite guarda o que juntou no armazém e dorme", wood.status == PhysicalJob.Status.PAUSED && wood.reason.startsWith("noite")
                && !lumber.bag.containsKey("minecraft:oak_log"));
        check("produção registrada: 15 toras → +60 de madeira no reino", wood.produced == 15 && k.get(ResourceType.WOOD) - woodBefore >= 59.9);
        check("guardou o lucro mas ficou com o kit (machado, mudas, ração)", Inventory.count(lumber, "axe") >= 1 && Inventory.foodCount(lumber) > 0);
        world.time = 1000; // manhã
        seconds(2);
        check("de manhã volta ao trabalho", wood.status != PhysicalJob.Status.PAUSED && wood.log.stream().anyMatch(l -> l.contains("Amanheceu")));

        // --- 4. meta: "produza 8 toras" para quando guardar 8
        Pos t4 = new Pos(52, 64, -30), t5 = new Pos(56, 64, -24);
        world.tree(t4);
        world.tree(t5);
        k.markers.put(Marker.FOREST, t4);
        say(lumber, "Produza 8 toras.");
        PhysicalJob quota = job(lumber);
        check("ordem nova substitui a anterior; meta 8", quota != null && quota != wood && quota.quota == 8 && wood.status == PhysicalJob.Status.CANCELLED);
        runUntilDone(quota, 600);
        print(core.skills().describe(quota));
        check("meta cumprida: cortou, levou ao armazém e encerrou", quota.status == PhysicalJob.Status.DONE && quota.produced >= 8
                && quota.note.contains("Meta cumprida"));

        // --- 5. mineiro: confere picareta/ração/tochas, abre galeria controlada, tocha no escuro, minério vai à forja
        Pos mine = new Pos(60, 50, 0);
        k.markers.put(Marker.MINE, mine);
        world.set(new Pos(63, 50, 1), "minecraft:iron_ore");
        world.set(new Pos(65, 51, -1), "minecraft:iron_ore");
        miner.bag.remove("minecraft:torch");
        say(miner, "Trabalhe na mina de ferro.");
        PhysicalJob ore = job(miner);
        check("'trabalhe na mina de ferro' → trabalho contínuo de minério de ferro", ore != null && "ore".equals(ore.labor) && ore.oreId.equals("minecraft:iron_ore"));
        check("sem tochas: passa no armazém antes de descer", ore != null && ore.tasks.get(0).kind == PhysicalJob.Kind.RESUPPLY);
        Pos torchAt = mine.offset(7, 0, 0);
        for (int s = 0; s < 400 && !"minecraft:torch".equals(world.id(torchAt)); s++) seconds(1);
        print(core.skills().describe(ore));
        check("pegou tochas no armazém", ore.log.stream().anyMatch(l -> l.contains("Pegou no armazém") && l.contains("tocha")));
        check("galeria 1×2 aberta de verdade", world.isAir(mine.offset(1, 0, 0)) && world.isAir(mine.offset(1, 1, 0)) && world.isAir(mine.offset(6, 1, 0))
                && !world.isAir(mine.offset(1, 2, 0)));
        check("minério da parede tirado (ferro bruto)", world.isAir(new Pos(63, 50, 1)) && world.isAir(new Pos(65, 51, -1))
                && miner.bag.getOrDefault("minecraft:raw_iron", 0) + k.goods.getOrDefault("minecraft:raw_iron", 0) >= 2);
        check("tocha posta no escuro (a cada 8 blocos)", "minecraft:torch".equals(world.id(torchAt)));
        check("picareta desgasta (pedra 131 usos)", miner.wear.values().stream().anyMatch(v -> v > 0));
        world.time = 14000;
        for (int s = 0; s < 200 && ore.status != PhysicalJob.Status.PAUSED; s++) seconds(1);
        int raw = k.goods.getOrDefault("minecraft:raw_iron", 0);
        check("à noite guarda o minério no armazém", ore.status == PhysicalJob.Status.PAUSED && !miner.bag.containsKey("minecraft:raw_iron") && raw >= 2);
        seconds(30); // o ferreiro funde com carvão (e forja a reserva de ferramentas)
        check("ferro bruto vai à forja (fundido com carvão)", k.goods.getOrDefault("minecraft:raw_iron", 0) < raw);
        world.time = 6000;
        core.skills().cancel(ore, "teste");

        // --- 6. fazendeiro: ara com a enxada, planta, colhe o maduro, replanta, busca sementes, entrega comida
        Building farm = complete(core.construction().planAt(k, BlueprintLibrary.get("farm"), new Pos(-40, 64, 40), true));
        List<Pos> plots = new ArrayList<>();
        for (var pl : farm.blueprint().placements())
            if (pl.material() == Material.CROP) plots.add(farm.origin.offset(pl.x(), pl.y(), pl.z()));
        for (int i = 0; i < plots.size(); i++) {
            Pos p = plots.get(i);
            if (i < 2) continue; // terra crua: precisa arar
            world.set(p.offset(0, -1, 0), "minecraft:farmland");
            if (i < 6) {
                world.set(p, "minecraft:wheat");
                world.crops.put(p, 100); // maduro
            } else if (i < 10) {
                world.set(p, "minecraft:wheat");
                world.crops.put(p, 40); // crescendo
            }
        }
        farmer.bag.put("minecraft:wheat_seeds", 4); // poucas: vai ter de buscar no armazém
        k.goods.put("minecraft:wheat_seeds", 64);
        say(farmer, "Cuide da fazenda.");
        PhysicalJob crops = job(farmer);
        check("'cuide da fazenda' → trabalho contínuo do fazendeiro", crops != null && "farm".equals(crops.labor));
        for (int s = 0; s < 400 && crops != null && !(crops.status == PhysicalJob.Status.WAITING && crops.current() == null); s++) seconds(1);
        print(core.skills().describe(crops));
        check("colheu os maduros (trigo na mochila)", farmer.bag.getOrDefault("minecraft:wheat", 0) >= 4 || crops.gained.getOrDefault("minecraft:wheat", 0) >= 4);
        check("replantou onde colheu", "minecraft:wheat".equals(world.id(plots.get(2))) && world.crops.getOrDefault(plots.get(2), -1) < 100);
        check("arou a terra crua com a enxada e plantou", "minecraft:farmland".equals(world.id(plots.get(0).offset(0, -1, 0)))
                && "minecraft:wheat".equals(world.id(plots.get(0))) && farmer.wear.keySet().stream().anyMatch(t -> t.endsWith("_hoe")));
        check("não mexeu no que está crescendo", world.crops.getOrDefault(plots.get(7), -1) == 40);
        check("acabaram as sementes → buscou no armazém", crops.log.stream().anyMatch(l -> l.contains("sementes")));
        check("tudo plantado: espera crescer (e tenta de novo)", crops.status == PhysicalJob.Status.WAITING && crops.reason.contains("crescendo"));
        for (Pos p : plots) if (world.crops.containsKey(p)) world.crops.put(p, 100); // amadureceu
        seconds(40);
        world.time = 14000;
        for (int s = 0; s < 200 && crops.status != PhysicalJob.Status.PAUSED; s++) seconds(1);
        print(core.skills().describe(crops));
        check("colheita guardada vira comida do reino (produção registrada)", crops.produced >= 8
                && crops.log.stream().anyMatch(l -> l.contains("Guardou no armazém") && l.contains("trigo"))
                && com.kingdomsai.core.economy.TreasurySystem.unit("minecraft:wheat").resource() == ResourceType.FOOD);
        world.time = 6000;
        core.skills().cancel(crops, "teste");

        // --- 7. longe da vista (LOD): o trabalho segue simulado no Core, o mundo não muda, a mochila enche e ele guarda
        Pos far = new Pos(50, 64, -64);
        world.tree(far);
        k.markers.put(Marker.FOREST, far);
        lumber.bag.merge("minecraft:stone_axe", 1, Integer::sum);
        say(lumber, "Trabalhe no bosque.");
        PhysicalJob lod = job(lumber);
        world.viewer = new Pos(0, 64, 0);
        world.viewRadius = 30;
        int logs0 = lumber.bag.getOrDefault("minecraft:oak_log", 0);
        int wear0 = lumber.wear.values().stream().mapToInt(Integer::intValue).sum();
        seconds(150);
        print(core.skills().describe(lod));
        check("área descarregada → trabalho simulado", lod.reason.contains("simulado"));
        check("rende no ritmo do machado de pedra (toras na mochila)", lumber.bag.getOrDefault("minecraft:oak_log", 0) - logs0 >= 5);
        check("ferramenta gasta mesmo longe (usa a melhor: machado de ferro)", lumber.wear.getOrDefault("minecraft:iron_axe", 0) > 0
                && lumber.wear.values().stream().mapToInt(Integer::intValue).sum() > wear0);
        check("o mundo não muda enquanto ninguém olha (árvore de pé)", world.id(far).endsWith("_log"));
        String[] junk = {"andesite", "flint", "bone", "string", "feather", "leather", "paper", "book", "brick", "glass", "sand", "gravel",
                "white_wool", "red_wool", "blue_wool", "green_wool", "lime_wool", "black_wool", "gray_wool", "pink_wool", "cyan_wool", "clay_ball"};
        for (String j : junk) lumber.bag.put("minecraft:" + j, 64);
        double w0 = k.get(ResourceType.WOOD);
        seconds(3);
        check("mochila cheia longe → leva ao armazém (estoque recebe)", lod.log.stream().anyMatch(l -> l.contains("longe da vista")) && k.get(ResourceType.WOOD) > w0
                && !lumber.bag.containsKey("minecraft:flint"));
        world.viewer = null;
        for (int s = 0; s < 60 && (lod.current() == null || lod.current().kind != PhysicalJob.Kind.CHOP); s++) seconds(1);
        check("rei voltou: volta ao corte de verdade", lod.current() != null && lod.current().kind == PhysicalJob.Kind.CHOP);

        // --- 8. validações: território, limite de blocos por segundo
        k.markers.put(Marker.MINE, new Pos(900, 50, 0));
        say(miner, "Trabalhe na mina.");
        check("mina fora do território → recusa", async.stream().anyMatch(s -> s.startsWith("✗") && s.contains("terra do reino")));
        core.skills().cancel(lod, "teste");
        cfg.maxBreaksPerSecond = 1;
        core.updatePlayerLook(player, new Pos(10, 63, 20), "south");
        say(miner, "Cave um buraco 3x3 aqui.");
        PhysicalJob dig = job(miner);
        int maxPerSecond = 0, before = world.broken.size();
        for (int s = 0; s < 120 && dig != null && dig.status.live(); s++) {
            int b = world.broken.size();
            seconds(1);
            maxPerSecond = Math.max(maxPerSecond, world.broken.size() - b);
        }
        check("limite global de blocos por segundo respeitado", maxPerSecond == 1 && world.broken.size() - before >= 9);
        cfg.maxBreaksPerSecond = 40;

        // --- 9. fome: armazém sem comida → trabalha com fome (mais devagar), sem vaivém infinito ao armazém
        Pos t6 = new Pos(30, 64, -56);
        world.tree(t6);
        k.markers.put(Marker.FOREST, t6);
        double food = k.get(ResourceType.FOOD);
        Map<String, Integer> pantry = new TreeMap<>(k.goods);
        cfg.economicTickSeconds = 100000; // as fazendas param de produzir durante o teste (o celeiro fica vazio mesmo)
        for (Pos c : core.treasury().chests(k)) world.chests.get(c).keySet().removeIf(id -> Inventory.nutrition(id) > 0); // tiram o pão dos baús
        core.treasury().sync(k);
        k.add(ResourceType.FOOD, -k.get(ResourceType.FOOD));
        k.goods.keySet().removeIf(id -> Inventory.nutrition(id) > 0);
        core.treasury().sync(k);
        lumber.bag.keySet().removeIf(id -> Inventory.nutrition(id) > 0);
        say(lumber, "Produza madeira.");
        PhysicalJob hungry = job(lumber);
        lumber.hunger = 25;
        seconds(70);
        long trips = hungry.tasks.stream().filter(t -> t.kind == PhysicalJob.Kind.STORE || t.kind == PhysicalJob.Kind.RESUPPLY).count();
        check("sem comida no armazém: trabalha com fome, sem vaivém", hungry.status.live() && trips <= 2
                && hungry.log.stream().anyMatch(l -> l.contains("trabalhando com fome")));
        core.skills().cancel(hungry, "teste");
        cfg.economicTickSeconds = 10;
        k.add(ResourceType.FOOD, food);
        k.goods.clear();
        k.goods.putAll(pantry);
        core.treasury().sync(k);

        // --- 10. save/load: kit, desgaste, prática e trabalho contínuo sobrevivem
        Path tmp = Files.createTempDirectory("kai-labor").resolve("kingdomsai.json");
        Persistence.save(core.snapshotForSave(), tmp);
        WorldState loaded = Persistence.load(tmp);
        Npc l2 = loaded.npcs.get(lumber.id);
        PhysicalJob w2 = loaded.jobs.get(wood.id);
        check("save/load preserva mochila, desgaste, equipamento e prática", l2.bag.equals(lumber.bag) && l2.wear.equals(lumber.wear)
                && loaded.npcs.get(guard.id).gear.equals(guard.gear) && l2.skillXp.equals(lumber.skillXp));
        check("save/load preserva o trabalho contínuo (local, produção)", w2 != null && w2.continuous && w2.produced == wood.produced && w2.site.equals(wood.site));
        return new int[]{passed, failed};
    }

    // ------------------------------------------------------------------ util

    private static Building complete(Building b) {
        b.status = Building.Status.COMPLETE;
        b.placed = b.progress = b.blueprint().blockCount();
        return b;
    }

    private static Npc prof(Kingdom k, Profession p) {
        return core.citizens(k.id).stream().filter(n -> n.profession == p).findFirst().orElseThrow();
    }

    private static PhysicalJob job(Npc n) {
        return n.jobId == null ? null : core.state().jobs.get(n.jobId);
    }

    private static void say(Npc n, String text) {
        async.clear();
        cli.execute(player, "Andraus", core.playerPos(player), "npc talk " + n.name + " " + text);
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

    private static void print(List<String> lines) {
        for (String l : lines) System.out.println("    | " + l);
    }
}
