package com.kingdomsai.core.work;

import com.kingdomsai.core.KingdomsCore;
import com.kingdomsai.core.common.Text;
import com.kingdomsai.core.construction.Building;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.npc.Npc;
import com.kingdomsai.core.npc.Office;
import com.kingdomsai.core.npc.Profession;
import com.kingdomsai.core.npc.Trait;

import java.util.*;

/**
 * Valida uma cadeia ANTES de ela existir — "a IA gera a cadeia, o jogo confere se ela fecha":
 *
 * 1. Esquema: etapas conhecidas, quantidades na faixa, parâmetros obrigatórios.
 * 2. Pessoas: cada papel tem alguém vivo, do reino, capaz (ferreiro para fundir, alfabetizado para ler/escrever).
 * 3. Lugares: forja, fazenda, armazém, biblioteca existem (em obra = a etapa espera; inexistente = erro).
 * 4. Fluxo: simula um ciclo — ninguém entrega o que não tem na mão; o que a forja consome alguém traz;
 *    colher exige plantar; ler exige livro; avisa gargalos e sobras que vão encher as mãos.
 */
public final class ChainValidator {
    public static final int CARRY_CAPACITY = 48;
    public static final int HARVEST_YIELD = 12;
    public static final int MAX_AMOUNT = 32;

    public record Validation(List<String> errors, List<String> warnings, List<String> plan, WorkChain chain) {
        public boolean ok() {
            return errors.isEmpty();
        }
    }

    private ChainValidator() {}

    public static Validation validate(KingdomsCore core, Kingdom k, ChainSpec spec) {
        List<String> errors = new ArrayList<>(), warnings = new ArrayList<>(), plan = new ArrayList<>();
        if (spec.steps.isEmpty()) errors.add("A cadeia não tem etapas.");
        if (spec.steps.size() > ChainSpec.MAX_STEPS) errors.add("No máximo " + ChainSpec.MAX_STEPS + " etapas por cadeia.");
        if (!errors.isEmpty()) return new Validation(errors, warnings, plan, null);

        WorkChain c = new WorkChain();
        c.kingdomId = k.id;
        c.name = spec.name == null || spec.name.isBlank() ? "Cadeia" : spec.name;
        c.repeat = spec.repeat;

        // --- 1. etapas
        for (int i = 0; i < spec.steps.size(); i++) {
            ChainSpec.StepSpec ss = spec.steps.get(i);
            String pre = "Etapa " + (i + 1) + ": ";
            StepType type = StepType.parse(ss.type);
            if (type == null) {
                errors.add(pre + "«" + ss.type + "» não é uma etapa conhecida. Use: " + Arrays.toString(StepType.values()) + ".");
                continue;
            }
            WorkChain.Step s = new WorkChain.Step();
            s.role = ss.role == null || ss.role.isBlank() ? "npc" : Text.norm(ss.role);
            s.type = type;
            Map<String, String> p = ss.params;
            int def = switch (type) {
                case MINE -> 8;
                case CHOP -> 12;
                case SMELT -> 4;
                case FORGE -> 2;
                default -> 0;
            };
            s.amount = ChainTemplates.parseAmount(p.get("amount"), def);
            if (s.amount < 0 || s.amount > MAX_AMOUNT || (type.perUnit && s.amount == 0)) {
                errors.add(pre + "quantidade deve ser de 1 a " + MAX_AMOUNT + ".");
                continue;
            }
            s.place = type.defaultPlace;
            switch (type) {
                case MINE -> {
                    s.item = p.get("item") == null ? Item.RAW_IRON : Item.parse(p.get("item"));
                    if (s.item != Item.RAW_IRON && s.item != Item.STONE && s.item != Item.COAL) {
                        errors.add(pre + "na mina só se extrai ferro bruto, carvão ou pedra.");
                        continue;
                    }
                }
                case CHOP -> s.item = Item.LOG;
                case SMELT -> s.item = Item.IRON_INGOT;
                case FORGE -> s.item = Item.SWORD;
                case DELIVER, STORE, PICKUP -> {
                    if (p.get("item") != null && !p.get("item").isBlank()) {
                        s.item = Item.parse(p.get("item"));
                        if (s.item == null) {
                            errors.add(pre + "item desconhecido «" + p.get("item") + "».");
                            continue;
                        }
                    } else if (type == StepType.PICKUP) {
                        errors.add(pre + "PICKUP precisa de item.");
                        continue;
                    }
                    if (type == StepType.PICKUP) {
                        s.place = Place.parse(p.get("from"));
                        if (s.place == null || !s.place.isBuilding()) {
                            errors.add(pre + "PICKUP precisa de from=<prédio> (forja, armazém, fazenda, biblioteca).");
                            continue;
                        }
                        if (s.amount == 0) s.amount = 1;
                    }
                    if (type == StepType.DELIVER) {
                        String to = p.get("to");
                        Place pl = Place.parse(to);
                        if (pl != null && pl.isBuilding()) s.place = pl;
                        else {
                            Npc target = to == null ? null : core.findNpc(null, to);
                            if (target == null) {
                                errors.add(pre + "não sei para onde entregar («" + to + "»): use um prédio ou o nome de uma pessoa.");
                                continue;
                            }
                            s.place = Place.RECIPIENT;
                            s.targetNpc = target.id;
                            s.targetName = target.name;
                            if (s.item != Item.LETTER) {
                                errors.add(pre + "em mãos só se entregam cartas; mercadoria vai para um prédio (forja, armazém...).");
                                continue;
                            }
                        }
                    }
                }
                case WRITE -> {
                    s.kind = "letter".equals(Text.norm(p.get("kind"))) || "carta".equals(Text.norm(p.get("kind"))) ? "letter" : "book";
                    s.topic = Text.truncate(nz(p.get("topic")), 60);
                    s.text = Text.truncate(nz(p.get("text")), 240);
                    if (s.kind.equals("letter")) {
                        Npc target = core.findNpc(null, p.get("to"));
                        if (target == null) {
                            errors.add(pre + "não encontrei o destinatário «" + nz(p.get("to")) + "».");
                            continue;
                        }
                        s.targetNpc = target.id;
                        s.targetName = target.name;
                        // carta: escreve na biblioteca se houver; senão onde estiver
                        if (findBuilding(core, k, Place.LIBRARY, true) == null) s.place = null;
                    }
                }
                case READ -> s.title = Text.truncate(nz(p.get("title")), 60);
                default -> {
                }
            }
            c.steps.add(s);
        }
        if (!errors.isEmpty()) return new Validation(errors, warnings, plan, null);

        // --- 2. pessoas
        Set<UUID> used = new HashSet<>();
        for (String role : rolesInOrder(c)) {
            List<WorkChain.Step> mine = c.steps.stream().filter(s -> s.role.equals(role)).toList();
            Profession prof = Profession.parse(role);
            String binding = spec.roles.get(role);
            Npc n = null;
            if (binding != null && !binding.isBlank()) {
                n = core.findNpc(k.id, binding);
                if (n == null) {
                    Profession bp = Profession.parse(binding);
                    if (bp == null) {
                        errors.add("Não encontrei «" + binding + "» em " + k.name + " para o papel de " + role + ".");
                        continue;
                    }
                    prof = bp;
                }
            }
            if (n == null) n = pick(core, k, prof, mine, used);
            if (n == null) {
                errors.add(prof != null
                        ? "Não há " + article(prof) + " disponível para o papel de " + role + ". Designe alguém (ex.: /k assign " + Text.norm(prof.display) + " 1)."
                        : "Ninguém no reino consegue fazer as etapas de «" + role + "»" + (needsLiteracy(mine) ? " (é preciso saber ler/escrever: estudioso ou sacerdote)." : "."));
                continue;
            }
            if (n.office == Office.KING) {
                errors.add(n.name + " é o rei: não cumpre tarefas braçais.");
                continue;
            }
            if (!used.add(n.id)) {
                errors.add(n.name + " não pode ter dois papéis na mesma cadeia.");
                continue;
            }
            for (WorkChain.Step s : mine) {
                String who = n.name + " (" + n.title().toLowerCase() + ")";
                if (s.type.skilled && !s.type.professions.contains(n.profession))
                    errors.add("«" + s.type.display + "» exige " + professionsText(s.type) + " — " + who + " não sabe fazer isso.");
                else if (!s.type.professions.isEmpty() && !s.type.professions.contains(n.profession) && s.type.literacy == StepType.Literacy.NONE)
                    warnings.add(who + " não é do ofício: vai " + s.type.display + " mais devagar.");
                if (s.type.literacy == StepType.Literacy.READ && !canRead(n))
                    errors.add(who + " não sabe ler. Peça a um estudioso, sacerdote ou mercador.");
                if (s.type.literacy == StepType.Literacy.WRITE && !canWrite(n))
                    errors.add(who + " não sabe escrever. Peça a um estudioso ou sacerdote.");
            }
            if (n.dutyChainId != null) {
                WorkChain old = core.state().chains.get(n.dutyChainId);
                if (old != null && old.live())
                    warnings.add(n.name + " vai largar «" + old.name + "» e adotar esta nova rotina.");
            }
            WorkChain.Role r = new WorkChain.Role();
            r.name = role;
            r.npcId = n.id;
            r.profession = prof != null ? prof : (mine.stream().anyMatch(s -> s.type.skilled) ? n.profession : null);
            c.roles.put(role, r);
        }

        // --- 3. lugares
        Set<Place> checked = EnumSet.noneOf(Place.class);
        for (WorkChain.Step s : c.steps) {
            if (s.place == null || !s.place.isBuilding() || !checked.add(s.place)) continue;
            if (findBuilding(core, k, s.place, true) != null) continue;
            if (findBuilding(core, k, s.place, false) != null)
                warnings.add(s.place.display + " ainda está em obra: as etapas lá esperam ela ficar pronta.");
            else
                errors.add("Não há " + s.place.display.toLowerCase() + " em " + k.name + ". Construa antes (ex.: /k build "
                        + Text.norm(s.place.display) + ").");
        }
        if (!errors.isEmpty()) return new Validation(errors, warnings, plan, null);

        // --- 4. fluxo de itens (um ciclo)
        Map<String, Map<Item, Integer>> carry = new HashMap<>();
        Map<Place, Map<Item, Integer>> supply = new EnumMap<>(Place.class), demand = new EnumMap<>(Place.class);
        Map<String, Integer> peak = new HashMap<>();
        boolean plants = c.steps.stream().anyMatch(s -> s.type == StepType.PLANT);
        boolean writesBook = c.steps.stream().anyMatch(s -> s.type == StepType.WRITE && s.kind.equals("book"));
        for (int i = 0; i < c.steps.size(); i++) {
            WorkChain.Step s = c.steps.get(i);
            String pre = "Etapa " + (i + 1) + " (" + s.describe() + "): ";
            Map<Item, Integer> hand = carry.computeIfAbsent(s.role, r -> new EnumMap<>(Item.class));
            String who = who(core, c, s.role);
            switch (s.type) {
                case MINE, CHOP -> hand.merge(s.item, s.amount, Integer::sum);
                case PLANT -> add(demand, Place.FARM, Item.SEEDS, 1);
                case HARVEST -> {
                    Building farm = findBuilding(core, k, Place.FARM, true);
                    if (!plants && (farm == null || farm.cropPlantedTick <= 0))
                        errors.add(pre + "não há nada plantado e nenhuma etapa planta. Adicione PLANT antes.");
                    hand.merge(Item.WHEAT, HARVEST_YIELD, Integer::sum);
                    add(supply, Place.FARM, Item.SEEDS, 2);
                }
                case DELIVER, STORE -> {
                    Map<Item, Integer> moved = take(hand, s.item, s.amount);
                    if (moved == null) {
                        errors.add(pre + who + " não terá " + (s.item == null ? "nada" : s.item.display.toLowerCase())
                                + " na mão" + (s.item != null && s.amount > 0 ? " (" + s.amount + ")" : "") + ". Falta uma etapa antes que produza isso.");
                        continue;
                    }
                    if (s.place != null && s.place.isBuilding() && s.type == StepType.DELIVER)
                        for (var e : moved.entrySet()) add(supply, s.place, e.getKey(), e.getValue());
                    if (s.type == StepType.STORE)
                        for (var e : moved.keySet()) if (e.resource == null)
                            warnings.add(pre + e.display + " não vira estoque do reino: fica guardado no baú do armazém.");
                }
                case PICKUP -> {
                    add(demand, s.place, s.item, s.amount);
                    hand.merge(s.item, s.amount, Integer::sum);
                }
                case SMELT -> {
                    add(demand, Place.SMITHY, Item.RAW_IRON, s.amount);
                    add(demand, Place.SMITHY, Item.COAL, (s.amount + 1) / 2);
                    hand.merge(Item.IRON_INGOT, s.amount, Integer::sum);
                }
                case FORGE -> {
                    if (hand.getOrDefault(Item.IRON_INGOT, 0) < s.amount * 2) {
                        errors.add(pre + who + " precisa de " + (s.amount * 2) + " barras de ferro na mão (2 por espada); só terá "
                                + hand.getOrDefault(Item.IRON_INGOT, 0) + ". Coloque SMELT/PICKUP antes.");
                        continue;
                    }
                    hand.merge(Item.IRON_INGOT, -s.amount * 2, Integer::sum);
                    hand.merge(Item.SWORD, s.amount, Integer::sum);
                }
                case WRITE -> {
                    if (s.kind.equals("letter")) hand.merge(Item.LETTER, 1, Integer::sum);
                }
                case READ -> {
                    boolean anyBook = core.state().documents.values().stream()
                            .anyMatch(d -> d.kind == Document.Kind.BOOK && k.id.equals(d.kingdomId));
                    if (!anyBook && !writesBook) errors.add(pre + "a biblioteca não tem livros. Mande alguém escrever um antes.");
                }
            }
            int total = hand.values().stream().mapToInt(Integer::intValue).sum();
            peak.merge(s.role, total, Math::max);
        }
        for (var e : peak.entrySet())
            if (e.getValue() > CARRY_CAPACITY)
                errors.add(who(core, c, e.getKey()) + " teria " + e.getValue() + " itens na mão (máx. " + CARRY_CAPACITY + "). Divida em entregas menores.");
        for (var pe : demand.entrySet()) {
            Building b = findBuilding(core, k, pe.getKey(), true);
            for (var ie : pe.getValue().entrySet()) {
                int need = ie.getValue();
                int comes = supply.getOrDefault(pe.getKey(), Map.of()).getOrDefault(ie.getKey(), 0);
                int stock = b == null ? 0 : b.inventory.getOrDefault(ie.getKey(), 0);
                Place where = pe.getKey();
                if (comes == 0 && stock < need) {
                    if (ie.getKey() == Item.SEEDS)
                        warnings.add("A fazenda não tem sementes guardadas: as primeiras virão do celeiro (2 de comida por plantio).");
                    else
                        errors.add(cap(where.a()) + " vai precisar de " + ie.getKey().display.toLowerCase() + " (" + need
                                + " por ciclo), mas nenhuma etapa entrega lá e o baú tem " + stock + ".");
                } else if (comes > 0 && comes < need && c.repeat)
                    warnings.add("Gargalo " + where.na() + ": chegam " + comes + " de " + ie.getKey().display.toLowerCase()
                            + " por ciclo, mas a etapa usa " + need + ". Ela vai esperar.");
                else if (comes == 0 && c.repeat)
                    warnings.add(cap(where.a()) + " usa " + ie.getKey().display.toLowerCase() + " do baú (" + stock + "); quando acabar, a cadeia espera.");
            }
        }
        for (var e : carry.entrySet()) {
            int left = e.getValue().values().stream().mapToInt(Integer::intValue).sum();
            if (left <= 0) continue;
            String what = summary(e.getValue());
            warnings.add(who(core, c, e.getKey()) + " termina " + (c.repeat ? "cada ciclo" : "a tarefa") + " com " + what
                    + " na mão (nenhuma etapa entrega/guarda)" + (c.repeat ? " — as mãos vão encher e a cadeia vai parar." : "."));
        }
        if (!errors.isEmpty()) return new Validation(errors, warnings, plan, null);

        for (int i = 0; i < c.steps.size(); i++) {
            WorkChain.Step s = c.steps.get(i);
            plan.add((i + 1) + ". " + who(core, c, s.role) + " — " + s.describe());
        }
        return new Validation(errors, warnings, plan, c);
    }

    // ------------------------------------------------------------------ regras compartilhadas com o WorkSystem

    /** Ler: estudiosos, sacerdotes, mercadores, quem tem cargo, os curiosos e quem já aprendeu. */
    public static boolean canRead(Npc n) {
        return n.literate || n.profession == Profession.SCHOLAR || n.profession == Profession.PRIEST || n.profession == Profession.MERCHANT
                || n.office != Office.NONE || n.trait(Trait.CURIOSITY) >= 60;
    }

    /** Escrever é mais raro: estudiosos, sacerdotes, quem tem cargo e os muito curiosos. */
    public static boolean canWrite(Npc n) {
        return n.profession == Profession.SCHOLAR || n.profession == Profession.PRIEST || n.office != Office.NONE
                || n.trait(Trait.CURIOSITY) >= 80;
    }

    /** Prédio do reino que serve de local (armazém aceita o Salão Real como depósito). */
    public static Building findBuilding(KingdomsCore core, Kingdom k, Place place, boolean complete) {
        if (place == null || !place.isBuilding()) return null;
        Building found = null;
        for (Building b : core.buildings(k.id)) {
            boolean match = b.blueprintId.equals(place.blueprintId) || (place == Place.STORAGE && b.blueprintId.equals("town_hall"));
            if (!match || (complete && !b.isComplete())) continue;
            if (b.origin == null || b.origin.y() == Integer.MIN_VALUE) continue;
            // preferir o prédio "de verdade" (armazém) ao Salão Real
            if (found == null || (b.blueprintId.equals(place.blueprintId) && !found.blueprintId.equals(place.blueprintId))) found = b;
        }
        return found;
    }

    private static Npc pick(KingdomsCore core, Kingdom k, Profession prof, List<WorkChain.Step> steps, Set<UUID> used) {
        boolean read = steps.stream().anyMatch(s -> s.type.literacy == StepType.Literacy.READ);
        boolean write = steps.stream().anyMatch(s -> s.type.literacy == StepType.Literacy.WRITE);
        Npc best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Npc n : core.citizens(k.id)) {
            if (used.contains(n.id) || n.office == Office.KING) continue;
            if (prof != null && n.profession != prof) continue;
            if (write && !canWrite(n)) continue;
            if (read && !canRead(n)) continue;
            if (prof == null && !write && !read && n.profession.isMilitary()) continue;
            int score = 0;
            WorkChain busy = n.dutyChainId == null ? null : core.state().chains.get(n.dutyChainId);
            if (busy != null && busy.live()) score -= 100;
            if (prof == null && (n.profession == Profession.SCHOLAR || n.profession == Profession.PRIEST)) score += 20;
            score += n.trait(Trait.DISCIPLINE) / 10;
            if (score > bestScore) {
                bestScore = score;
                best = n;
            }
        }
        return best;
    }

    static List<String> rolesInOrder(WorkChain c) {
        List<String> out = new ArrayList<>();
        for (WorkChain.Step s : c.steps) if (!out.contains(s.role)) out.add(s.role);
        return out;
    }

    private static boolean needsLiteracy(List<WorkChain.Step> steps) {
        return steps.stream().anyMatch(s -> s.type.literacy != StepType.Literacy.NONE);
    }

    private static String who(KingdomsCore core, WorkChain c, String role) {
        WorkChain.Role r = c.roles.get(role);
        Npc n = r == null ? null : core.npc(r.npcId);
        return n == null ? "«" + role + "»" : n.name;
    }

    private static String article(Profession p) {
        return "nenhum " + p.display.toLowerCase();
    }

    private static String professionsText(StepType t) {
        List<String> names = new ArrayList<>();
        for (Profession p : t.professions) names.add(p.display.toLowerCase());
        return String.join(" ou ", names);
    }

    /** Tira da mão: item null = tudo; amount 0 = todo o item. null se não houver o que tirar. */
    static Map<Item, Integer> take(Map<Item, Integer> hand, Item item, int amount) {
        Map<Item, Integer> moved = new EnumMap<>(Item.class);
        if (item == null) {
            for (var e : hand.entrySet()) if (e.getValue() > 0) moved.put(e.getKey(), e.getValue());
            if (moved.isEmpty()) return null;
            hand.clear();
            return moved;
        }
        int have = hand.getOrDefault(item, 0);
        int want = amount == 0 ? have : amount;
        if (have <= 0 || have < want) return null;
        hand.put(item, have - want);
        moved.put(item, want);
        return moved;
    }

    private static void add(Map<Place, Map<Item, Integer>> m, Place p, Item i, int v) {
        m.computeIfAbsent(p, x -> new EnumMap<>(Item.class)).merge(i, v, Integer::sum);
    }

    public static String summary(Map<Item, Integer> items) {
        List<String> parts = new ArrayList<>();
        for (var e : items.entrySet()) if (e.getValue() > 0) parts.add(e.getValue() + " " + e.getKey().display.toLowerCase());
        return parts.isEmpty() ? "nada" : String.join(", ", parts);
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }
}
