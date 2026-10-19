package com.kingdomsai.core.law;

import com.kingdomsai.core.KingdomsCore;
import com.kingdomsai.core.civic.Decree;
import com.kingdomsai.core.common.Pos;
import com.kingdomsai.core.common.Text;
import com.kingdomsai.core.construction.Blueprint;
import com.kingdomsai.core.construction.Building;
import com.kingdomsai.core.event.EventType;
import com.kingdomsai.core.event.GameEvent;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.npc.Freedom;
import com.kingdomsai.core.npc.Npc;
import com.kingdomsai.core.npc.NpcActivity;
import com.kingdomsai.core.port.PhysicalPort;
import com.kingdomsai.core.skill.PhysicalJob;

import java.util.*;

/**
 * Faz a lei valer: compara cada regra sobre propriedades com o mundo e transforma a diferença em trabalho de gente.
 * Quem cumpre ganha um pouco de lealdade; quem se recusa perde, e a reincidência vira castigo. A regra nunca mexe
 * no mundo direto — vira uma ordem física (PhysicalJob REPLACE) que passa pelo mesmo caminho de qualquer outra.
 */
public final class LawEnforcer {
    public static final int MAX_JOBS_PER_KINGDOM = 6;
    public static final long DEFIANCE_COOLDOWN = 20L * 120;
    public static final int STRIKES_TO_PUNISH = 3;

    private final KingdomsCore core;
    /** Posição → ordem em andamento (some quando a ordem acaba). */
    private final Map<Pos, UUID> assigned = new HashMap<>();
    /** Quem se recusou: "npc@pos" → quando volta a ser cobrado. */
    private final Map<String, Long> defiant = new HashMap<>();
    private final Map<UUID, Integer> strikes = new HashMap<>();
    /** Violações que não têm conserto (sem troca possível): não insistir. */
    private final Set<String> hopeless = new HashSet<>();

    public LawEnforcer(KingdomsCore core) {
        this.core = core;
    }

    /** Quantas violações a regra tem agora (para a UI e os testes); só conta onde o mundo está carregado. */
    public int violations(Kingdom k, Decree d) {
        return d.rule == null ? 0 : scan(k, d.rule).size();
    }

    public void tickSecond() {
        long sec = core.tick() / 20;
        if (sec % 6 != 0) return;
        for (Kingdom k : core.state().kingdoms.values()) {
            if (!k.isPlayerKingdom() || k.decrees == null) continue;
            assigned.values().removeIf(id -> {
                PhysicalJob j = core.state().jobs.get(id);
                return j == null || !j.status.live();
            });
            for (Decree d : k.decrees) {
                if (!d.active || d.rule == null) continue;
                try {
                    enforce(k, d);
                } catch (RuntimeException e) {
                    // uma regra ruim não pode derrubar o resto do reino
                }
            }
        }
    }

    private record Violation(Pos at, String id, Building building) {}

    private List<Violation> scan(Kingdom k, PropertyRule rule) {
        List<Violation> out = new ArrayList<>();
        if (rule.scope.equals(PropertyRule.PEOPLE)) return out;
        PhysicalPort port = core.physical();
        for (Building b : core.buildings(k.id)) {
            if (b.status != Building.Status.COMPLETE || b.origin == null || b.origin.y() == Integer.MIN_VALUE) continue;
            Blueprint bp = b.blueprint();
            if (bp == null) continue;
            if (rule.scope.equals(PropertyRule.HOMES) && bp.housing() <= 0) continue;
            for (int x = 0; x < bp.sizeX(); x++)
                for (int z = 0; z < bp.sizeZ(); z++)
                    for (int y = 0; y <= bp.sizeY(); y++) {
                        Pos p = new Pos(b.origin.x() + x, b.origin.y() + y, b.origin.z() + z);
                        if (!port.isLoaded(p)) continue;
                        PhysicalPort.BlockInfo info = port.block(p);
                        if (info.air() || info.fluid()) continue;
                        if (rule.matches(Props.of(info.id()))) out.add(new Violation(p, info.id(), b));
                    }
        }
        return out;
    }

    private void enforce(Kingdom k, Decree d) {
        PropertyRule rule = d.rule;
        if (rule.scope.equals(PropertyRule.PEOPLE)) {
            confiscate(k, d, rule);
            return;
        }
        int live = 0;
        for (UUID id : assigned.values()) {
            PhysicalJob j = core.state().jobs.get(id);
            if (j != null && k.id.equals(j.kingdomId)) live++;
        }
        for (Violation v : scan(k, rule)) {
            if (live >= MAX_JOBS_PER_KINGDOM) return;
            if (assigned.containsKey(v.at())) continue;
            String newId = rule.action.equals(PropertyRule.REMOVE) ? "" : rule.replacement(v.id());
            if (newId == null) {
                hopeless.add(v.id() + "@" + d.number);
                continue;
            }
            Npc who = pick(k, v);
            if (who == null) continue;
            String key = who.id + "@" + v.at();
            if (core.tick() < defiant.getOrDefault(key, 0L)) continue;
            double obey = 0.35 + who.loyalty / 200.0 + (d.viaPetition > 0 ? 0.15 : 0);
            if (core.rng().nextDouble() > obey) {
                defy(k, d, who, v, key);
                continue;
            }
            PhysicalJob job = new PhysicalJob();
            job.kingdomId = k.id;
            job.npcId = who.id;
            job.orderedBy = k.rulerPlayer;
            job.name = "cumprir a lei #" + d.number + " (" + d.text + ")";
            PhysicalJob.Task t = new PhysicalJob.Task();
            t.kind = PhysicalJob.Kind.REPLACE;
            t.at = v.at();
            t.item = v.id();
            t.block = newId;
            t.label = newId.isEmpty() ? "tirar " + Text.truncate(v.id(), 30) : "trocar " + Text.truncate(v.id(), 30) + " por " + Text.truncate(newId, 30);
            t.count = d.number;
            job.tasks.add(t);
            core.skills().start(k, job, "Lei #" + d.number + ": " + d.text);
            assigned.put(v.at(), job.id);
            live++;
        }
    }

    /** Quem cuida: o morador da casa; senão o súdito livre mais perto. */
    private Npc pick(Kingdom k, Violation v) {
        List<Npc> pool = new ArrayList<>();
        for (UUID id : v.building().residents) {
            Npc n = core.npc(id);
            if (free(n)) pool.add(n);
        }
        if (pool.isEmpty())
            for (Npc n : core.citizens(k.id)) if (free(n) && !n.profession.isMilitary()) pool.add(n);
        Npc best = null;
        for (Npc n : pool) if (best == null || n.pos != null && v.at().distSq(n.pos) < v.at().distSq(best.pos)) best = n;
        return best;
    }

    private boolean free(Npc n) {
        return n != null && n.alive && n.pos != null && n.freedom == Freedom.FREE && n.jobId == null && n.activity != NpcActivity.SUMMONED
                && n.activity != NpcActivity.SLEEP && !core.life().asleep(n, core.tick());
    }

    private void defy(Kingdom k, Decree d, Npc who, Violation v, String key) {
        defiant.put(key, core.tick() + DEFIANCE_COOLDOWN);
        who.loyalty = (int) Text.clamp(who.loyalty - 2, 0, 100);
        who.remember(core.tick(), "Não aceito a lei #" + d.number + " (" + d.text + "): é a minha casa.", 30, null, "lei", "desobediencia");
        int s = strikes.merge(who.id, 1, Integer::sum);
        if (s >= STRIKES_TO_PUNISH) {
            strikes.remove(who.id);
            who.loyalty = (int) Text.clamp(who.loyalty - 4, 0, 100);
            who.mood = Text.clamp(who.mood - 8, 0, 100);
            who.fear = Math.min(100, who.fear + 15);
            who.remember(core.tick(), "Fui castigado por desobedecer a lei #" + d.number + ".", 60, null, "lei", "castigo");
            core.bus().publish(core.tick(), EventType.LAW_ENFORCED, GameEvent.Severity.WARN, k.id, who.id,
                    who.name + " foi castigado por desobedecer a lei #" + d.number + ".", Map.of("decree", String.valueOf(d.number)));
            defiant.put(key, 0L); // depois do castigo, cumpre
        } else {
            core.bus().publish(core.tick(), EventType.LAW_ENFORCED, GameEvent.Severity.INFO, k.id, who.id,
                    who.name + " se recusa a cumprir a lei #" + d.number + " (" + s + "/" + STRIKES_TO_PUNISH + ").", Map.of("decree", String.valueOf(d.number)));
        }
    }

    /** Scope people: o que os súditos carregam e a lei proíbe vai para o armazém do reino. */
    private void confiscate(Kingdom k, Decree d, PropertyRule rule) {
        for (Npc n : core.citizens(k.id)) {
            if (!n.alive || n.freedom != Freedom.FREE) continue;
            for (var e : new ArrayList<>(n.bag.entrySet())) {
                if (!rule.matches(Props.of(e.getKey()))) continue;
                k.goods.merge(e.getKey(), e.getValue(), Integer::sum);
                n.bag.remove(e.getKey());
                n.loyalty = (int) Text.clamp(n.loyalty - 1, 0, 100);
                n.mood = Text.clamp(n.mood - 2, 0, 100);
                n.remember(core.tick(), "Levaram meu " + e.getKey().substring(e.getKey().indexOf(':') + 1) + " por causa da lei #" + d.number + ".", 25, null, "lei");
                core.bus().publish(core.tick(), EventType.LAW_ENFORCED, GameEvent.Severity.INFO, k.id, n.id,
                        n.name + " teve " + e.getValue() + "x " + e.getKey() + " confiscado(s) (lei #" + d.number + ").", Map.of("decree", String.valueOf(d.number)));
            }
        }
        core.treasury().sync(k);
    }
}
