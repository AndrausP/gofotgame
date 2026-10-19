package com.kingdomsai.core.ai;

import com.kingdomsai.core.KingdomsCore;
import com.kingdomsai.core.action.ActionRequest;
import com.kingdomsai.core.action.ActionResult;
import com.kingdomsai.core.action.ActionType;
import com.kingdomsai.core.common.Text;
import com.kingdomsai.core.event.EventType;
import com.kingdomsai.core.event.GameEvent;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.kingdom.Marker;
import com.kingdomsai.core.kingdom.ResourceType;
import com.kingdomsai.core.life.Places;
import com.kingdomsai.core.npc.Npc;
import com.kingdomsai.core.npc.Office;
import com.kingdomsai.core.npc.Profession;
import com.kingdomsai.core.skill.PhysicalJob;

import java.util.*;

/**
 * Roteiro de início: o que um reino novo precisa nos primeiros dias para não morrer de fome nem se desfazer —
 * comida primeiro (sem fazenda, a comida inicial acaba em ~15 min), armazém, casas, madeira e pedra, guarda para a noite
 * e um lugar para a vida da vila. O rei vê o checklist ({@code /k roteiro}) e recebe lembretes; com o conselheiro
 * automático ligado (padrão), nos 3 primeiros dias o conselheiro cuida da etapa da vez pelo mesmo pipeline validado
 * (ActionSystem) que o rei usa. Nos 2 primeiros dias ninguém morre de fome (a saúde não cai abaixo de 20).
 */
public final class Opening {
    public static final long DAY = 24000;
    /** O conselheiro age sozinho nos primeiros dias (depois só o checklist). */
    public static final long PERIOD = 3 * DAY;
    /** Carência: fome não mata nos primeiros dias. */
    public static final long GRACE = 2 * DAY;
    /** Lembrete da etapa da vez. */
    public static final long NUDGE = 20L * 180;

    public record Step(String id, String title, String why, String say) {}

    public static final List<Step> STEPS = List.of(
            new Step("comida", "Comida garantida", "sem fazenda, a comida inicial acaba em ~15 minutos e o povo passa fome",
                    "\"Conselheiro, cuide da comida\" (ou \"construam uma fazenda\")"),
            new Step("armazem", "Armazém com baús", "o estoque do reino mora nos baús; lá ficam a ração e as ferramentas de reserva",
                    "\"construam um armazém\""),
            new Step("casas", "Casa para todos", "quem dorme ao relento fica triste, trabalha menos e um dia vai embora", "\"construam 2 casas\""),
            new Step("materiais", "Madeira e pedra entrando", "toda obra precisa de material: lenhador e minerador trabalhando de verdade",
                    "marque o bosque e a mina com a Bandeira; ao lenhador \"produza madeira\", ao minerador \"trabalhe na mina\""),
            new Step("defesa", "Guarda para a noite", "à noite aparecem monstros; 1 guarda para cada 8 moradores",
                    "\"Capitão, monte um exército\" ou /k assign guarda"),
            new Step("vida", "Vida na vila", "praça marcada e uma capela ou taverna: humor, conversa, gente que fica",
                    "marque a praça com a Bandeira; \"construam uma capela\""));

    private final KingdomsCore core;
    private final Map<UUID, Long> lastAct = new HashMap<>();

    public Opening(KingdomsCore core) {
        this.core = core;
    }

    public long age(Kingdom k) {
        return core.tick() - k.foundedTick;
    }

    /** Ninguém morre de fome enquanto o reino é novo. */
    public boolean grace(Kingdom k) {
        return k != null && k.isPlayerKingdom() && age(k) < GRACE;
    }

    public boolean done(Kingdom k, Step s) {
        if (k.openingDone.contains(s.id()) && !s.id().equals("comida")) return true; // comida é conferida sempre: o povo cresce e come mais
        int pop = core.population(k.id);
        return switch (s.id()) {
            case "comida" -> core.completedOf(k.id, "farm") >= 1 && core.economy().projected(k).getOrDefault(ResourceType.FOOD, 0.0) >= 0;
            case "armazem" -> core.completedOf(k.id, "storage") >= 1;
            case "casas" -> pop <= core.housingCapacity(k.id);
            case "materiais" -> working(k, Profession.LUMBERJACK) && working(k, Profession.MINER);
            case "defesa" -> core.military(k.id) >= Math.max(1, pop / 8);
            case "vida" -> k.markers.containsKey(Marker.GATHER) && (Places.church(core, k) != null || Places.tavern(core, k) != null);
            default -> true;
        };
    }

    /** Lenhador/minerador trabalhando de verdade: trabalho contínuo ou rotina dada pelo rei. */
    private boolean working(Kingdom k, Profession p) {
        for (Npc n : core.citizens(k.id)) {
            if (n.profession != p) continue;
            if (n.dutyChainId != null) return true;
            PhysicalJob j = n.jobId == null ? null : core.state().jobs.get(n.jobId);
            if (j != null && j.status.live() && j.continuous) return true;
        }
        return false;
    }

    public Step current(Kingdom k) {
        for (Step s : STEPS) if (!done(k, s)) return s;
        return null;
    }

    /** A cada 30 s: marca etapas cumpridas, avisa o rei e (nos primeiros dias) o conselheiro cuida da etapa da vez. */
    public void tick() {
        for (Kingdom k : List.copyOf(core.state().kingdoms.values())) {
            if (!k.isPlayerKingdom() || k.openingFinished) continue;
            if (k.openingDone.contains("comida") && !done(k, STEPS.get(0)) && age(k) < PERIOD) {
                k.openingDone.remove("comida"); // a população cresceu e a lavoura não acompanha: comida volta a ser a etapa da vez
                k.openingNudge = 0;
            }
            for (Step s : STEPS)
                if (!k.openingDone.contains(s.id()) && done(k, s)) {
                    k.openingDone.add(s.id());
                    Step next = current(k);
                    core.bus().publish(core.tick(), EventType.OPENING_STEP, GameEvent.Severity.GOOD, k.id, null,
                            "Roteiro de início (" + k.openingDone.size() + "/" + STEPS.size() + "): " + s.title() + " ✓."
                                    + (next == null ? "" : " Próximo: " + next.title() + " — " + next.say() + "."));
                }
            Step cur = current(k);
            if (cur == null) {
                k.openingFinished = true;
                core.bus().publish(core.tick(), EventType.OPENING_STEP, GameEvent.Severity.GOOD, k.id, null,
                        "Roteiro de início concluído: " + k.name + " tem comida, armazém, casas, material, guarda e vida própria.");
                core.chronicle(k.name + " deixou de ser um acampamento: o reino está de pé.");
                continue;
            }
            if (age(k) >= PERIOD) continue;
            if (core.tick() >= k.openingNudge) {
                k.openingNudge = core.tick() + NUDGE;
                core.bus().publish(core.tick(), EventType.OPENING_STEP, cur.id().equals("comida") ? GameEvent.Severity.WARN : GameEvent.Severity.INFO, k.id, null,
                        "Roteiro de início (" + (k.openingDone.size() + 1) + "/" + STEPS.size() + "): " + cur.title() + " — " + cur.why() + ". Diga: " + cur.say()
                                + (k.openingAuto ? ". (O conselheiro já está cuidando disso.)" : "."));
            }
            if (k.openingAuto && core.tick() - lastAct.getOrDefault(k.id, -99999L) >= 20L * 60) act(k, cur);
        }
    }

    /** O conselheiro cuida da etapa da vez (pelos validadores, em nome do rei). */
    public List<String> act(Kingdom k, Step s) {
        lastAct.put(k.id, core.tick());
        List<String> out = new ArrayList<>();
        UUID king = k.rulerPlayer;
        switch (s.id()) {
            case "comida" -> {
                // fazenda primeiro (cada uma rende +60% para até 3 fazendeiros); depois camponeses para a lavoura —
                // nunca tira o único lenhador/minerador (sem eles não há material para nada)
                long planned = core.construction().projects(k.id).stream().filter(b -> b.blueprintId.equals("farm")).count();
                int farms = core.completedOf(k.id, "farm");
                int farmers = core.count(k.id, Profession.FARMER);
                if (farms + planned == 0 || farms > 0 && planned == 0 && farmers > farms * 3)
                    out.add(line(exec(k, king, ActionType.BUILD, "blueprint", "farm")));
                else if (farms > 0 && core.economy().projected(k).getOrDefault(ResourceType.FOOD, 0.0) < 0 && core.count(k.id, Profession.PEASANT) > 0)
                    out.add(line(exec(k, king, ActionType.WORK, "profession", "FARMER", "amount", "1", "from", "PEASANT")));
            }
            case "armazem" -> {
                if (core.construction().projects(k.id).stream().noneMatch(b -> b.blueprintId.equals("storage")))
                    out.add(line(exec(k, king, ActionType.BUILD, "blueprint", "storage")));
            }
            case "casas" -> out.addAll(core.director().pursue(k, "BUILD_HOUSING", king));
            case "materiais" -> {
                for (Profession p : List.of(Profession.LUMBERJACK, Profession.MINER))
                    if (core.count(k.id, p) == 0 && core.count(k.id, Profession.PEASANT) > 0)
                        out.add(line(exec(k, king, ActionType.WORK, "profession", p.name(), "amount", "1", "from", "PEASANT")));
                if (k.markers.containsKey(Marker.FOREST)) out.add(labor(k, king, Profession.LUMBERJACK, "wood"));
                if (k.markers.containsKey(Marker.MINE)) out.add(labor(k, king, Profession.MINER, "ore"));
            }
            case "defesa" -> {
                if (core.count(k.id, Profession.PEASANT) > 0)
                    out.add(line(exec(k, king, ActionType.WORK, "profession", "GUARD", "amount", "1", "from", "PEASANT")));
            }
            default -> {
            }
        }
        out.removeIf(l -> l == null || l.isBlank() || l.startsWith("Avaliei") || l.startsWith("Nada a fazer"));
        if (!out.isEmpty()) {
            Npc adv = advisor(k);
            core.bus().publish(core.tick(), EventType.OPENING_STEP, GameEvent.Severity.GOOD, k.id, adv == null ? null : adv.id,
                    (adv == null ? "O conselho" : "«" + adv.name + "» (conselheiro)") + " cuidou do roteiro — " + s.title() + ": "
                            + Text.truncate(String.join(" ", out), 220));
        }
        return out;
    }

    private String labor(Kingdom k, UUID king, Profession p, String labor) {
        for (Npc n : core.citizens(k.id)) {
            if (n.profession != p || n.dutyChainId != null) continue;
            PhysicalJob j = n.jobId == null ? null : core.state().jobs.get(n.jobId);
            if (j != null && j.status.live()) continue;
            return line(exec(k, king, ActionType.JOB, "kind", "labor", "labor", labor, "npc", n.name));
        }
        return null;
    }

    private ActionResult exec(Kingdom k, UUID king, ActionType type, String... kv) {
        return core.actions().execute(ActionRequest.of(k.id, king, ActionRequest.ActorKind.PLAYER, type, ActionRequest.Source.DIRECTOR, kv));
    }

    private static String line(ActionResult r) {
        return r == null ? null : (r.ok() ? "✓ " : "✗ ") + r.message().split("\n")[0];
    }

    private Npc advisor(Kingdom k) {
        for (Npc n : core.citizens(k.id)) if (n.office == Office.ADVISOR) return n;
        return null;
    }

    /** O checklist para o rei. */
    public List<String> describe(Kingdom k) {
        List<String> out = new ArrayList<>();
        out.add("# Roteiro de início — " + k.name + " (dia " + (age(k) / DAY + 1) + ")");
        Step cur = current(k);
        int i = 1;
        for (Step s : STEPS) {
            boolean ok = done(k, s);
            String mark = ok ? "✓" : s == cur ? "▶" : " ";
            out.add(mark + " " + i++ + ". " + s.title() + (ok ? "" : " — " + s.why() + ". Diga: " + s.say()));
        }
        double food = core.economy().projected(k).getOrDefault(ResourceType.FOOD, 0.0);
        double left = core.economy().foodTicksLeft(k);
        out.add("Comida: " + (int) k.get(ResourceType.FOOD) + " no celeiro, " + (food >= 0 ? "+" : "") + Text.fmt(food) + " por ciclo"
                + (left == Double.POSITIVE_INFINITY ? " (estável)" : " (acaba em ~" + (int) (left * core.config().economicTickSeconds / 60) + " min)"));
        out.add(age(k) < PERIOD ? "Conselheiro automático: " + (k.openingAuto ? "ligado" : "desligado") + " (age nos 3 primeiros dias) · /k roteiro auto "
                + (k.openingAuto ? "off" : "on") : "Os primeiros dias passaram: agora o reino é com Vossa Majestade.");
        if (grace(k)) out.add("Proteção do começo: ninguém morre de fome até o dia " + (GRACE / DAY + 1) + ".");
        if (cur == null) out.add("✓ Roteiro concluído.");
        return out;
    }
}
