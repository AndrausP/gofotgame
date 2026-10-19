package com.kingdomsai.core;

import com.kingdomsai.core.civic.Decree;
import com.kingdomsai.core.cli.CommandService;
import com.kingdomsai.core.common.Pos;
import com.kingdomsai.core.construction.Building;
import com.kingdomsai.core.event.EventType;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.kingdom.ResourceType;
import com.kingdomsai.core.law.LawText;
import com.kingdomsai.core.law.Props;
import com.kingdomsai.core.law.PropertyRule;
import com.kingdomsai.core.npc.Npc;
import com.kingdomsai.voxel.*;

import java.util.*;

/** Leis como regras sobre propriedades: a LLM escreve a regra, o povo cumpre no mundo. */
public final class LawSelfTest {
    private static int passed, failed;

    public static void main(String[] args) throws Exception {
        int[] r = run();
        System.out.println("\n" + r[0] + " passaram, " + r[1] + " falharam.");
        System.exit(r[1] == 0 ? 0 : 1);
    }

    public static int[] run() {
        passed = failed = 0;
        System.out.println("\n# Leis sobre propriedades");

        // ---- vocabulário
        Props bed = Props.of("minecraft:red_bed");
        check("red_bed: cor vermelha, tag bed, material cloth", "red".equals(bed.color()) && "bed".equals(bed.tag()) && "cloth".equals(bed.material()));
        Props lb = Props.of("minecraft:light_blue_bed");
        check("light_blue_bed: cor de duas palavras", "light_blue".equals(lb.color()) && "bed".equals(lb.tag()));
        check("cobblestone é pedra, oak_planks é madeira, gold_ore é ouro",
                "stone".equals(Props.of("minecraft:cobblestone").material()) && "wood".equals(Props.of("minecraft:oak_planks").material())
                        && "gold".equals(Props.of("minecraft:gold_ore").material()));

        // ---- regra
        PropertyRule r = PropertyRule.parse("scope=homes tag=bed color=red action=replace with=blue");
        check("regra casa com cama vermelha e não com azul", r.matches(bed) && !r.matches(Props.of("minecraft:blue_bed")) && !r.matches(Props.of("minecraft:red_wool")));
        check("troca a cor mantendo o resto: red_bed → blue_bed", "minecraft:blue_bed".equals(r.replacement("minecraft:red_bed")));
        check("regra sem como trocar não insiste (cama sem cor)", r.replacement("minecraft:oak_planks") == null);
        PropertyRule stone = PropertyRule.parse("scope=homes material=stone action=replace with=wood");
        check("casas só de madeira: cobblestone vira oak_planks", "minecraft:oak_planks".equals(stone.replacement("minecraft:cobblestone")));
        String[] bad = {"", "color=red", "scope=mundo tag=bed", "scope=homes tag=bed action=replace", "scope=homes tag=bed color=bolinha", "foo=bar", "tag"};
        boolean allRejected = true;
        for (String b : bad) {
            try {
                PropertyRule.parse(b);
                allRejected = false;
                System.out.println("   aceitou sem querer: \"" + b + "\"");
            } catch (IllegalArgumentException expected) {
            }
        }
        check("regras mal formadas são recusadas com motivo", allRejected);

        // ---- frases em português → regra
        check("\"proibido ter cama vermelha\"", "scope=homes tag=bed color=red action=replace with=blue".equals(LawText.rule("proibido ter cama vermelha")));
        check("\"ninguem pode ter cama azul, troque por branca\"", "scope=homes tag=bed color=blue action=replace with=white".equals(LawText.rule("ninguem pode ter cama azul, troque por branca")));
        check("\"fica proibido porta\" sem cor remove", "scope=homes tag=door action=remove".equals(LawText.rule("fica proibido porta")));
        check("\"as casas so de madeira\"", "scope=homes material=stone action=replace with=wood".equals(LawText.rule("as casas so de madeira")));
        check("\"ninguem anda com ouro fora do bau\"", "scope=people tag=gold action=confiscate".equals(LawText.rule("ninguem anda com ouro fora do bau")));
        check("conversa comum não vira lei", LawText.rule("como esta a colheita") == null && LawText.rule("a cama esta confortavel") == null);

        // ---- no mundo: decreto → súditos trocam as camas
        CoreConfig cfg = new CoreConfig();
        cfg.rivalKingdoms = 0;
        cfg.aiKingdomsEnabled = false;
        WorldState s = new WorldState();
        s.seed = 9;
        VoxelHost h = new VoxelHost(42, s, cfg);
        UUID player = UUID.randomUUID();
        CommandService cli = new CommandService(h.core);
        List<String> async = new ArrayList<>();
        cli.setNotifier((p, l) -> async.addAll(l));
        int g = h.world.groundY(0, 0);
        Pos king = new Pos(0, g, 0);
        h.core.updatePlayerPos(player, king);
        h.area.setViewer(player, king);
        cli.execute(player, "Andraus", king, "found Reino de Leis");
        Kingdom k = h.core.kingdomOfPlayer(player);
        k.civic = "off";
        k.stock.put(ResourceType.WOOD, 3000.0);
        k.stock.put(ResourceType.STONE, 3000.0);
        k.stock.put(ResourceType.FOOD, 3000.0);
        cli.execute(player, "Andraus", king, "build casa 2");
        cli.execute(player, "Andraus", king, "build casa 2");
        for (int i = 0; i < 20 * 60 * 4; i++) h.step();
        int redBefore = countBlocks(h, k, "minecraft:red_bed");
        check("as casas têm camas vermelhas (" + redBefore + ")", redBefore >= 2);
        List<EventType> seen = new ArrayList<>();
        h.core.bus().subscribeAll(e -> seen.add(e.type()));

        async.clear();
        List<String> out = cli.execute(player, "Andraus", king, "order proíbo camas vermelhas nas casas, troquem por azul");
        System.out.println("   → " + out + " " + async);
        Decree law = null;
        for (Decree d : k.decrees) if (d.rule != null) law = d;
        check("a ordem vira decreto com regra", law != null && "red".equals(law.rule.color) && "bed".equals(law.rule.tag));
        for (int i = 0; i < 20 * 60 * 6; i++) h.step();
        int redAfter = countBlocks(h, k, "minecraft:red_bed"), blueAfter = countBlocks(h, k, "minecraft:blue_bed");
        System.out.println("   camas vermelhas: " + redBefore + " → " + redAfter + ", azuis: " + blueAfter + ", eventos de lei: " + Collections.frequency(seen, EventType.LAW_ENFORCED));
        check("os súditos trocaram as camas no mundo", redAfter < redBefore && blueAfter >= 1);
        check("cumprir e recusar geram eventos da lei", seen.contains(EventType.LAW_ENFORCED));
        int obeyed = 0;
        for (Npc n : h.core.citizens(k.id)) if (n.memories.stream().anyMatch(m -> m.text().contains("Cumpri a lei"))) obeyed++;
        check("quem cumpriu guarda isso na memória (" + obeyed + ")", obeyed >= 1);

        // ---- ouro com o povo é confiscado
        Npc someone = h.core.citizens(k.id).get(0);
        someone.bag.put("minecraft:gold_ingot", 3);
        cli.execute(player, "Andraus", king, "order decrete que ninguém anda com ouro fora do baú");
        for (int i = 0; i < 20 * 20; i++) h.step();
        check("o ouro que o súdito carregava foi confiscado", !someone.bag.containsKey("minecraft:gold_ingot"));
        return new int[]{passed, failed};
    }

    private static int countBlocks(VoxelHost h, Kingdom k, String id) {
        int n = 0;
        for (Building b : h.core.buildings(k.id)) {
            if (b.origin == null || b.origin.y() == Integer.MIN_VALUE) continue;
            var bp = b.blueprint();
            for (int x = 0; x < bp.sizeX(); x++)
                for (int z = 0; z < bp.sizeZ(); z++)
                    for (int y = 0; y <= bp.sizeY(); y++)
                        if (h.world.get(b.origin.x() + x, b.origin.y() + y, b.origin.z() + z).id().equals(id)) n++;
        }
        return n;
    }

    private static void check(String name, boolean ok) {
        if (ok) passed++;
        else failed++;
        System.out.println((ok ? "  [OK]   " : "  [FAIL] ") + name);
    }
}
