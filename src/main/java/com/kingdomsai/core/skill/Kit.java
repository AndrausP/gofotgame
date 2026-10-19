package com.kingdomsai.core.skill;

import com.kingdomsai.core.economy.TreasurySystem;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.npc.Npc;
import com.kingdomsai.core.npc.Profession;

import java.util.*;

/**
 * O que cada ofício carrega para trabalhar (ferramenta, reserva, comida, consumíveis, equipamento, itens de trabalho),
 * e as duas idas ao armazém: REABASTECER (tira do estoque do reino o que falta) e DEPOSITAR (guarda o que juntou).
 * Tudo sai e entra pelo estoque do reino (recursos em {@link Kingdom#stock}, demais itens em {@link Kingdom#goods});
 * os baús mostram isso ({@link TreasurySystem}).
 */
public final class Kit {
    public enum Role {
        TOOL("ferramenta"), SPARE("reserva"), FOOD("comida"), CONSUMABLE("consumível"), EQUIPMENT("equipamento"), WORK("item de trabalho");

        public final String display;

        Role(String display) {
            this.display = display;
        }
    }

    /**
     * Uma necessidade do kit.
     *
     * @param items alternativas, da melhor para a pior (ferramentas: o tipo vale qualquer material)
     * @param min   abaixo disso, ele volta ao armazém antes de trabalhar
     * @param fill  quanto leva quando reabastece
     * @param slot  equipamento vestido (head/chest/offhand) ou null (fica na mochila)
     */
    public record Need(String label, Role role, List<String> items, int min, int fill, String slot) {
        public boolean tool() {
            return role == Role.TOOL || role == Role.SPARE;
        }

        public String toolType() {
            return tool() ? Inventory.toolType(items.get(0)) : null;
        }
    }

    private static final List<String> FOOD = List.of("minecraft:bread", "minecraft:baked_potato", "minecraft:cooked_beef", "minecraft:apple", "minecraft:carrot");
    private static final List<String> AXES = List.of("minecraft:iron_axe", "minecraft:stone_axe", "minecraft:wooden_axe");
    private static final List<String> PICKS = List.of("minecraft:iron_pickaxe", "minecraft:stone_pickaxe", "minecraft:wooden_pickaxe");
    private static final List<String> HOES = List.of("minecraft:iron_hoe", "minecraft:stone_hoe", "minecraft:wooden_hoe");

    private Kit() {}

    /** A tabela de equipamento por profissão. */
    public static List<Need> of(Profession p) {
        List<Need> k = new ArrayList<>();
        switch (p) {
            case FARMER -> {
                k.add(new Need("enxada", Role.TOOL, HOES, 1, 1, null));
                k.add(new Need("sementes", Role.CONSUMABLE, List.of("minecraft:wheat_seeds"), 4, 16, null));
                k.add(new Need("balde", Role.WORK, List.of("minecraft:water_bucket", "minecraft:bucket"), 0, 1, null));
            }
            case LUMBERJACK -> {
                k.add(new Need("machado", Role.TOOL, AXES, 1, 1, null));
                k.add(new Need("machado reserva", Role.SPARE, AXES, 0, 2, null));
                k.add(new Need("mudas", Role.CONSUMABLE, List.of("minecraft:oak_sapling", "minecraft:spruce_sapling", "minecraft:birch_sapling"), 0, 4, null));
            }
            case MINER -> {
                k.add(new Need("picareta", Role.TOOL, PICKS, 1, 1, null));
                k.add(new Need("picareta reserva", Role.SPARE, PICKS, 0, 2, null));
                k.add(new Need("tochas", Role.CONSUMABLE, List.of("minecraft:torch"), 4, 16, null));
            }
            case BUILDER -> {
                k.add(new Need("picareta", Role.TOOL, PICKS, 1, 1, null));
                k.add(new Need("machado", Role.TOOL, AXES, 1, 1, null));
                k.add(new Need("blocos", Role.WORK, List.of("minecraft:cobblestone", "minecraft:oak_planks"), 0, 32, null));
            }
            case BLACKSMITH -> {
                k.add(new Need("materiais", Role.WORK, List.of("minecraft:iron_ingot", "minecraft:raw_iron"), 0, 4, null));
                k.add(new Need("carvão", Role.CONSUMABLE, List.of("minecraft:coal", "minecraft:charcoal"), 0, 8, null));
                k.add(new Need("combustível", Role.CONSUMABLE, List.of("minecraft:charcoal"), 0, 4, null));
                k.add(new Need("ferramenta", Role.TOOL, PICKS, 0, 1, null));
            }
            case GUARD -> {
                k.add(new Need("escudo", Role.EQUIPMENT, List.of("minecraft:shield"), 0, 1, "offhand"));
                k.add(new Need("armadura", Role.EQUIPMENT, List.of("minecraft:iron_chestplate", "minecraft:chainmail_chestplate", "minecraft:leather_chestplate"), 0, 1, "chest"));
                k.add(new Need("elmo", Role.EQUIPMENT, List.of("minecraft:iron_helmet", "minecraft:chainmail_helmet", "minecraft:leather_helmet"), 0, 1, "head"));
            }
            case SOLDIER -> {
                k.add(new Need("armadura", Role.EQUIPMENT, List.of("minecraft:iron_chestplate", "minecraft:chainmail_chestplate", "minecraft:leather_chestplate"), 0, 1, "chest"));
                k.add(new Need("elmo", Role.EQUIPMENT, List.of("minecraft:iron_helmet", "minecraft:chainmail_helmet", "minecraft:leather_helmet"), 0, 1, "head"));
                k.add(new Need("escudo", Role.EQUIPMENT, List.of("minecraft:shield"), 0, 1, "offhand"));
            }
            default -> {
            }
        }
        int food = p == Profession.SOLDIER ? 6 : p.isMilitary() ? 3 : p == Profession.FARMER || p == Profession.LUMBERJACK || p == Profession.MINER
                || p == Profession.BUILDER || p == Profession.BLACKSMITH ? 4 : 2;
        k.add(new Need(p == Profession.SOLDIER ? "suprimentos (ração)" : "comida", Role.FOOD, FOOD, 1, food, null));
        return k;
    }

    /** Quanto ele tem do que a necessidade pede (ferramentas: qualquer material do tipo; equipamento: o vestido). */
    public static int have(Npc n, Need need) {
        if (need.slot() != null) return n.gear.containsKey(need.slot()) ? 1 : 0;
        if (need.role() == Role.FOOD) return Inventory.foodCount(n);
        if (need.tool()) return Inventory.count(n, need.toolType());
        int c = 0;
        for (String id : need.items()) c += n.bag.getOrDefault(id, 0);
        return c;
    }

    /** O que falta para trabalhar (abaixo do mínimo). Vazio = pode começar. */
    public static List<Need> missing(Npc n) {
        List<Need> out = new ArrayList<>();
        for (Need need : of(n.profession)) if (have(n, need) < need.min()) out.add(need);
        return out;
    }

    /** Falta algo que vale a ida ao armazém (abaixo do mínimo, ou ferramenta sem reserva). */
    public static boolean wantsResupply(Npc n) {
        for (Need need : of(n.profession)) {
            int h = have(n, need);
            if (h < need.min() || need.role() == Role.SPARE && h < need.fill() || need.role() == Role.FOOD && h < 2) return true;
        }
        return false;
    }

    /** Kit inicial: os moradores chegam com as ferramentas do ofício (não sai do estoque). */
    public static void grantStarter(Npc n) {
        for (Need need : of(n.profession)) {
            if (need.role() == Role.WORK && need.min() == 0 && n.profession == Profession.BUILDER) continue; // blocos vêm do armazém
            if (need.role() == Role.WORK && n.profession == Profession.BLACKSMITH) continue;
            // chegam com o básico: ferramenta de pedra, equipamento mais simples (couro), comida/sementes/tochas
            String item = need.tool() ? need.items().get(Math.min(1, need.items().size() - 1))
                    : need.slot() != null ? need.items().get(need.items().size() - 1) : need.items().get(0);
            int want = need.fill() - have(n, need);
            if (want <= 0) continue;
            if (need.slot() != null) n.gear.put(need.slot(), item);
            else n.bag.merge(item, need.role() == Role.CONSUMABLE ? Math.max(need.min(), need.fill() / 2) : want, Integer::sum);
        }
    }

    // ------------------------------------------------------------------ estoque do reino

    /** Quanto do item o reino tem guardado (recursos pelo registro; o resto em goods). */
    public static int stock(Kingdom k, String id) {
        TreasurySystem.Unit u = TreasurySystem.unit(id);
        if (u != null && TreasurySystem.CANONICAL.get(u.resource()).equals(id)) return (int) Math.floor(k.get(u.resource()) / u.value() + 1e-6);
        return k.goods.getOrDefault(id, 0);
    }

    private static int takeFromKingdom(Kingdom k, String id, int n) {
        int have = stock(k, id);
        int t = Math.min(have, n);
        if (t <= 0) return 0;
        TreasurySystem.Unit u = TreasurySystem.unit(id);
        if (u != null && TreasurySystem.CANONICAL.get(u.resource()).equals(id)) k.add(u.resource(), -t * (double) u.value());
        else {
            k.goods.merge(id, -t, Integer::sum);
            k.goods.values().removeIf(v -> v <= 0);
        }
        return t;
    }

    /** Guarda no estoque do reino: o que é recurso vira registro (tora = 4 madeira); o resto vira bem guardado. */
    public static void giveToKingdom(Kingdom k, String id, int n) {
        if (n <= 0) return;
        TreasurySystem.Unit u = TreasurySystem.unit(id);
        if (u != null) k.add(u.resource(), n * (double) u.value());
        else k.goods.merge(id, n, Integer::sum);
    }

    /** O resultado de uma ida ao armazém. */
    public record Trip(Map<String, Integer> moved, List<String> lacking) {
        public boolean nothing() {
            return moved.isEmpty();
        }
    }

    /**
     * Reabastece até o "fill" de cada necessidade, com o melhor material disponível. Ferramentas: só completa o número
     * (principal + reserva). Comida vem do estoque de comida (pão).
     */
    public static Trip resupply(Kingdom k, Npc n) {
        Map<String, Integer> moved = new TreeMap<>();
        List<String> lacking = new ArrayList<>();
        for (Need need : of(n.profession)) {
            int want = need.fill() - have(n, need);
            if (want <= 0) continue;
            int got = 0;
            for (String id : need.items()) {
                if (got >= want) break;
                int room = need.slot() != null ? 1 : Inventory.room(n, id);
                int t = takeFromKingdom(k, id, Math.min(want - got, room));
                if (t <= 0) continue;
                if (need.slot() != null) n.gear.put(need.slot(), id);
                else n.bag.merge(id, t, Integer::sum);
                moved.merge(id, t, Integer::sum);
                got += t;
                if (need.slot() != null) break;
            }
            if (have(n, need) < need.min()) lacking.add(need.label());
        }
        return new Trip(moved, lacking);
    }

    /**
     * Deposita tudo o que não é do kit (o kit fica: ferramentas, a comida da ração, sementes/mudas/tochas até o "fill").
     */
    public static Trip deposit(Kingdom k, Npc n) {
        Map<String, Integer> moved = surplus(n);
        for (var e : moved.entrySet()) {
            giveToKingdom(k, e.getKey(), e.getValue());
            n.bag.merge(e.getKey(), -e.getValue(), Integer::sum);
        }
        n.bag.values().removeIf(v -> v <= 0);
        return new Trip(moved, List.of());
    }

    /** O que ele guardaria no armazém agora (tudo o que passa do kit). Não mexe em nada. */
    public static Map<String, Integer> surplus(Npc n) {
        Map<String, Integer> keep = new HashMap<>();
        for (Need need : of(n.profession)) {
            if (need.slot() != null) continue;
            int budget = need.fill();
            if (need.tool()) {
                for (var e : n.bag.entrySet())
                    if (need.toolType().equals(Inventory.toolType(e.getKey()))) keep.merge(e.getKey(), Math.min(e.getValue(), budget), Math::max);
                continue;
            }
            List<String> ids = need.role() == Role.FOOD ? n.bag.keySet().stream().filter(i -> Inventory.nutrition(i) > 0).toList() : need.items();
            for (String id : ids) {
                int have = n.bag.getOrDefault(id, 0);
                int kept = Math.min(have, budget);
                if (kept > 0) keep.merge(id, kept, Integer::sum);
                budget -= kept;
            }
        }
        Map<String, Integer> out = new TreeMap<>();
        for (var e : n.bag.entrySet()) {
            int give = e.getValue() - keep.getOrDefault(e.getKey(), 0);
            if (give > 0) out.put(e.getKey(), give);
        }
        return out;
    }

    /** Linha para o rei/IA: "machado de pedra 80% (+1 reserva) · comida 3 · mudas 4 · 18/27 espaços". */
    public static String describe(Npc n) {
        List<String> parts = new ArrayList<>();
        for (Need need : of(n.profession)) {
            if (need.role() == Role.SPARE) continue;
            if (need.tool()) {
                String t = Inventory.bestTool(n, need.toolType());
                int c = Inventory.count(n, need.toolType());
                parts.add(t == null ? "sem " + need.label() : ItemNames.display(t) + " " + Inventory.health(n, t) + "%" + (c > 1 ? " (+" + (c - 1) + " reserva)" : ""));
            } else if (need.slot() != null) {
                if (n.gear.containsKey(need.slot())) parts.add(ItemNames.display(n.gear.get(need.slot())));
            } else parts.add(need.label() + " " + have(n, need));
        }
        if (!n.equipped.isEmpty()) parts.add(0, "espada do arsenal");
        parts.add(Inventory.slotsUsed(n.bag) + "/" + Inventory.SLOTS + " espaços");
        return String.join(" · ", parts);
    }

    /** Estoque inicial do armazém de um reino novo: reservas para trocar ferramentas e sementes, tochas, carvão. */
    public static Map<String, Integer> starterGoods() {
        Map<String, Integer> g = new TreeMap<>();
        g.put("minecraft:stone_axe", 2);
        g.put("minecraft:stone_pickaxe", 2);
        g.put("minecraft:stone_hoe", 1);
        g.put("minecraft:iron_axe", 1);
        g.put("minecraft:iron_pickaxe", 1);
        g.put("minecraft:torch", 32);
        g.put("minecraft:wheat_seeds", 24);
        g.put("minecraft:oak_sapling", 8);
        g.put("minecraft:coal", 16);
        g.put("minecraft:charcoal", 8);
        g.put("minecraft:shield", 1);
        g.put("minecraft:chainmail_chestplate", 2);
        g.put("minecraft:chainmail_helmet", 2);
        g.put("minecraft:bucket", 1);
        return g;
    }
}
