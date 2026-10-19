package com.kingdomsai.core.ai;

import com.kingdomsai.core.KingdomsCore;
import com.kingdomsai.core.action.ActionRequest;
import com.kingdomsai.core.action.ActionResult;
import com.kingdomsai.core.action.ActionType;
import com.kingdomsai.core.construction.Building;
import com.kingdomsai.core.diplomacy.Diplomacy;
import com.kingdomsai.core.event.EventType;
import com.kingdomsai.core.event.GameEvent;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.kingdom.ResourceType;
import com.kingdomsai.core.npc.Profession;

import java.util.*;

/**
 * AI Director de cada reino de IA: Utility AI (sem LLM). Observa o estado, calcula prioridades
 * e emite ações pelo MESMO pipeline de validação que o jogador usa.
 */
public final class KingdomDirector {
    private final KingdomsCore core;
    /** Última análise por reino — usada pelo /kingdom debug ai. */
    private final Map<UUID, String> lastReasoning = new HashMap<>();

    public record Priority(String goal, double score, String reason) {}

    public KingdomDirector(KingdomsCore core) {
        this.core = core;
    }

    public String lastReasoning(UUID k) {
        return lastReasoning.getOrDefault(k, "(ainda não avaliado)");
    }

    public void tick() {
        for (Kingdom k : List.copyOf(core.state().kingdoms.values())) {
            if (k.isPlayerKingdom()) continue;
            run(k);
        }
    }

    private final Map<java.util.UUID, Kingdom> attackTarget = new HashMap<>();

    public List<Priority> evaluate(Kingdom k) {
        List<Priority> ps = new ArrayList<>();
        int pop = core.population(k.id);
        int cap = core.housingCapacity(k.id);
        double foodTicks = core.economy().foodTicksLeft(k);
        double foodScore = foodTicks == Double.POSITIVE_INFINITY ? (k.get(ResourceType.FOOD) < pop * 10 ? 0.3 : 0.05)
                : Math.min(1.0, 40 / Math.max(1, foodTicks));
        ps.add(new Priority("INCREASE_FOOD", foodScore + (k.famine ? 1 : 0),
                foodTicks == Double.POSITIVE_INFINITY ? "comida estável" : "comida acaba em " + (int) foodTicks + " ticks"));
        ps.add(new Priority("BUILD_HOUSING", pop >= cap - 1 ? 0.7 : 0.1, "moradia " + pop + "/" + cap));
        double threat = 0;
        String threatBy = "ninguém";
        for (Kingdom o : core.state().kingdoms.values()) {
            if (o == k) continue;
            Diplomacy.Attitude att = core.diplomacy().attitude(k.id, o.id);
            Diplomacy.Link l = core.diplomacy().link(k.id, o.id);
            double t = (att.hostility / 100.0) * (core.military(o.id) + 1.0) / (core.military(k.id) + 1.0);
            if (l.state == Diplomacy.State.WAR) t += 1;
            if (t > threat) {
                threat = t;
                threatBy = o.name;
            }
        }
        int desiredMil = (int) Math.ceil(pop * (0.08 + k.personality.militarism / 500.0) * (1 + Math.min(threat, 2)));
        ps.add(new Priority("FORTIFY", core.military(k.id) < desiredMil ? 0.3 + threat * 0.4 + k.personality.militarism / 200.0 : 0,
                "ameaça " + String.format(Locale.ROOT, "%.2f", threat) + " (" + threatBy + "), militares " + core.military(k.id) + "/" + desiredMil));
        ps.add(new Priority("EXPAND", k.personality.expansionism / 140.0 * (core.warfare().freeClaims(k) > 0 ? 1 : 0),
                "expansionismo " + k.personality.expansionism + ", terra livre " + core.warfare().freeClaims(k)));
        // Em guerra e mais forte: manda a tropa (o comandante escolhe quem e o objetivo).
        Kingdom prey = null;
        double edge = 0;
        boolean busy = core.warfare().campaigns(k.id).stream().anyMatch(c -> c.live());
        if (!busy && !k.famine)
            for (Kingdom o : core.state().kingdoms.values()) {
                if (o == k || core.population(o.id) == 0 || core.diplomacy().link(k.id, o.id).state != Diplomacy.State.WAR) continue;
                double force = 0;
                for (var n : core.warfare().available(k, false)) force += core.warfare().power(n, k);
                double def = core.warfare().defenseAt(o, o.center) * 0.5 + 0.5;
                if (force / def > edge) {
                    edge = force / def;
                    prey = o;
                }
            }
        attackTarget.put(k.id, prey);
        ps.add(new Priority("CAMPAIGN", prey != null && edge >= 1.4 ? 0.45 + k.personality.militarism / 200.0 : 0,
                prey == null ? "sem guerra/sem tropa" : "vantagem " + String.format(Locale.ROOT, "%.1f", edge) + " sobre " + prey.name));
        boolean needBuilder = core.count(k.id, Profession.BUILDER) == 0;
        boolean needWood = k.get(ResourceType.WOOD) < 60 && core.count(k.id, Profession.LUMBERJACK) < 2;
        boolean needStone = k.get(ResourceType.STONE) < 40 && core.count(k.id, Profession.MINER) < 2;
        ps.add(new Priority("BALANCE_WORKFORCE", needBuilder || needWood || needStone ? 0.6 : 0.05,
                needBuilder ? "sem construtores" : needWood ? "pouca madeira" : needStone ? "pouca pedra" : "ok"));
        ps.sort(Comparator.comparingDouble(Priority::score).reversed());
        return ps;
    }

    private void run(Kingdom k) {
        List<Priority> ps = evaluate(k);
        StringBuilder why = new StringBuilder();
        for (Priority p : ps) why.append(p.goal()).append('=').append(String.format(Locale.ROOT, "%.2f", p.score())).append(" (").append(p.reason()).append(") ");
        lastReasoning.put(k.id, why.toString());
        int actionsTaken = 0;
        for (Priority p : ps) {
            if (p.goal().equals("CAMPAIGN")) {
                // o general age em paralelo ao conselho civil: a guerra não espera a fazenda ficar pronta
                if (p.score() >= 0.5) {
                    ActionResult r = act(k, p.goal());
                    if (r != null && r.ok())
                        core.bus().publish(core.tick(), EventType.AI_DECISION, GameEvent.Severity.WARN, k.id, k.rulerNpc,
                                k.name + ": CAMPAIGN — " + r.message(), Map.of("goal", p.goal(), "reason", p.reason()));
                }
                continue;
            }
            if (actionsTaken >= 2 || p.score() < 0.25) break;
            ActionResult r = act(k, p.goal());
            if (r != null && r.ok()) {
                actionsTaken++;
                core.bus().publish(core.tick(), EventType.AI_DECISION, GameEvent.Severity.INFO, k.id, k.rulerNpc,
                        k.name + ": " + p.goal() + " — " + r.message(), Map.of("goal", p.goal(), "reason", p.reason()));
            }
        }
    }

    private ActionResult act(Kingdom k, String goal) {
        switch (goal) {
            case "INCREASE_FOOD" -> {
                int farms = core.completedOf(k.id, "farm") + (int) core.construction().projects(k.id).stream().filter(b -> b.blueprintId.equals("farm")).count();
                if (farms < Math.max(1, core.count(k.id, Profession.FARMER) / 3)) {
                    ActionResult r = exec(k, ActionType.BUILD, "blueprint", "farm");
                    if (r.ok()) return r;
                }
                // nunca tira o único lenhador: sem madeira não há fazenda, casa nem nada
                String from = core.count(k.id, Profession.PEASANT) > 0 ? "PEASANT" : core.count(k.id, Profession.MERCHANT) > 1 ? "MERCHANT"
                        : core.count(k.id, Profession.LUMBERJACK) > 1 ? "LUMBERJACK" : null;
                if (from == null) {
                    long building = core.construction().projects(k.id).stream().filter(b -> b.blueprintId.equals("farm")).count();
                    return building > 0 ? ActionResult.ok("Fazenda já em obras: quando ficar pronta, a lavoura rende 60% a mais (não tiro o único lenhador).")
                            : null;
                }
                return exec(k, ActionType.WORK, "profession", "FARMER", "amount", "1", "from", from);
            }
            case "BUILD_HOUSING" -> {
                long pending = core.construction().projects(k.id).stream().filter(b -> b.blueprint().housing() > 0).count();
                if (pending >= 2) return null;
                return exec(k, ActionType.BUILD, "blueprint", k.get(ResourceType.WOOD) > 90 ? "house_medium" : "house_small");
            }
            case "FORTIFY" -> {
                if (core.economy().foodTicksLeft(k) < 20) return null; // soldado come: sem comida, não convoca
                if (core.completedOf(k.id, "barracks") == 0 && core.military(k.id) >= 4 && core.construction().projects(k.id).isEmpty()) {
                    ActionResult r = exec(k, ActionType.BUILD, "blueprint", "barracks");
                    if (r.ok()) return r;
                }
                ActionResult r = exec(k, ActionType.RECRUIT, "amount", "1");
                if (r.ok()) spiesReport(k);
                return r;
            }
            case "EXPAND" -> {
                return exec(k, ActionType.CLAIM, "amount", "1");
            }
            case "CAMPAIGN" -> {
                Kingdom prey = attackTarget.get(k.id);
                return prey == null ? null : exec(k, ActionType.ATTACK, "target", prey.name);
            }
            case "BALANCE_WORKFORCE" -> {
                String to = core.count(k.id, Profession.BUILDER) == 0 ? "BUILDER"
                        : k.get(ResourceType.WOOD) < 60 ? "LUMBERJACK" : "MINER";
                String from = core.count(k.id, Profession.PEASANT) > 0 ? "PEASANT" : "FARMER";
                if (from.equals("FARMER") && core.count(k.id, Profession.FARMER) <= 2) return null;
                return exec(k, ActionType.WORK, "profession", to, "amount", "1", "from", from);
            }
            default -> {
                return null;
            }
        }
    }

    /** Rei por quem o conselheiro está agindo (objetivo delegado); null = IA de reino agindo por si. */
    private UUID actingFor;

    private ActionResult exec(Kingdom k, ActionType type, String... kv) {
        if (actingFor != null)
            return core.actions().execute(ActionRequest.of(k.id, actingFor, ActionRequest.ActorKind.PLAYER, type, ActionRequest.Source.LLM, kv));
        return core.actions().execute(ActionRequest.of(k.id, null, ActionRequest.ActorKind.DIRECTOR, type,
                ActionRequest.Source.DIRECTOR, kv));
    }

    /** Objetivos que o rei pode delegar ao conselheiro (e o "jeito" de pensar de cada um). */
    public static String goalOf(String text) {
        String n = com.kingdomsai.core.common.Text.norm(text == null ? "" : text);
        if (n.matches(".*(comida|aliment|fome|colheita|lavoura|food).*")) return "INCREASE_FOOD";
        if (n.matches(".*(moradi|casa|habita|lar|housing).*")) return "BUILD_HOUSING";
        if (n.matches(".*(defes|exercit|seguranc|protec|fortific|militar|defense).*")) return "FORTIFY";
        if (n.matches(".*(territ|expan|terra|fronteir).*")) return "EXPAND";
        if (n.matches(".*(madeira|lenha|pedra|trabalh|mao de obra|construtor|producao|economia|workforce).*")) return "BALANCE_WORKFORCE";
        return null;
    }

    /**
     * "Conselheiro, cuide da comida": o conselheiro avalia o reino como a IA dos rivais avalia o dela e executa os passos
     * em nome do rei (até 3), pelo mesmo ActionSystem. Retorna o raciocínio e o que foi feito.
     */
    public List<String> pursue(Kingdom k, String goal, UUID king) {
        List<String> out = new ArrayList<>();
        Priority p = evaluate(k).stream().filter(x -> x.goal().equals(goal)).findFirst().orElse(null);
        out.add("Avaliei: " + (p == null ? goal : p.reason()) + ".");
        actingFor = king;
        try {
            for (int i = 0; i < 3; i++) {
                ActionResult r = act(k, goal);
                if (r == null) break;
                out.add((r.ok() ? "✓ " : "✗ ") + r.message());
                if (!r.ok()) break;
                if (goal.equals("BUILD_HOUSING") || goal.equals("EXPAND") && i >= 1) break;
                Priority again = evaluate(k).stream().filter(x -> x.goal().equals(goal)).findFirst().orElse(null);
                if (again == null || again.score() < 0.35) break; // resolvido o bastante
            }
        } finally {
            actingFor = null;
        }
        if (out.size() == 1) out.add("Nada a fazer agora: " + (p == null ? "já está em ordem" : p.reason()) + ".");
        return out;
    }

    /** Reinos do jogador que estão de olho neste reino recebem o relatório dos espiões. */
    private void spiesReport(Kingdom k) {
        for (Kingdom p : core.state().kingdoms.values()) {
            if (!p.isPlayerKingdom()) continue;
            Diplomacy.Link l = core.diplomacy().link(k.id, p.id);
            Diplomacy.Attitude att = core.diplomacy().attitude(k.id, p.id);
            if (l.state != Diplomacy.State.PEACE || att.hostility > 45)
                core.bus().publish(core.tick(), EventType.MILITARY_BUILDUP, GameEvent.Severity.WARN, p.id, null,
                        "Espiões relatam movimentação militar em " + k.name + " (" + core.military(k.id) + " militares).",
                        Map.of("other", k.id.toString()));
        }
    }

    /** Usado na fundação dos reinos de IA: já começam com algumas obras. */
    public void bootstrap(Kingdom k) {
        exec(k, ActionType.BUILD, "blueprint", "house_small");
        exec(k, ActionType.BUILD, "blueprint", "farm");
    }

    public List<Building> projects(Kingdom k) {
        return core.construction().projects(k.id);
    }
}
