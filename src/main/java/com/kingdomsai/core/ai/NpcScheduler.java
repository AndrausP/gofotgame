package com.kingdomsai.core.ai;

import com.kingdomsai.core.KingdomsCore;
import com.kingdomsai.core.common.Pos;
import com.kingdomsai.core.common.Text;
import com.kingdomsai.core.construction.Building;
import com.kingdomsai.core.event.EventType;
import com.kingdomsai.core.event.GameEvent;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.kingdom.Marker;
import com.kingdomsai.core.npc.Npc;
import com.kingdomsai.core.npc.NpcActivity;

/**
 * Rotina dos NPCs (Utility AI simples, sem LLM): decide O QUE fazer e ONDE.
 * O adaptador decide COMO (pathfinding, animação).
 */
public final class NpcScheduler {
    private final KingdomsCore core;

    public record Intent(NpcActivity activity, Pos target, double radius) {}

    public NpcScheduler(KingdomsCore core) {
        this.core = core;
    }

    public static final int SUMMON_SECONDS = 180;
    public static final int MAX_SUMMON_DISTANCE = 400;

    /** O rei chama: o NPC larga o que faz (a rotina fica em pausa) e vai até ele; com follow, acompanha. */
    public void summon(Npc n, java.util.UUID by, Pos target, boolean follow, int seconds) {
        n.summonTarget = target;
        n.summonedBy = by;
        n.following = follow;
        n.summonArrived = false;
        n.summonUntil = core.tick() + 20L * Math.max(10, seconds);
        n.currentTask = follow ? "Acompanhar o rei" : "Atender ao chamado do rei";
    }

    public void dismiss(Npc n) {
        n.summonTarget = null;
        n.summonedBy = null;
        n.following = false;
        n.summonArrived = false;
    }

    public boolean isSummoned(Npc n) {
        return n.summonTarget != null && core.tick() < n.summonUntil;
    }

    public void tickSecond() {
        long time = core.world().dayTime();
        for (Npc n : core.allAlive()) {
            if (n.summonTarget != null) {
                if (core.tick() >= n.summonUntil) {
                    dismiss(n);
                } else {
                    if (n.following) {
                        Pos p = core.playerPos(n.summonedBy);
                        if (p != null) n.summonTarget = p;
                    }
                    if (!n.summonArrived && n.pos != null && n.pos.distXZ(n.summonTarget) <= 4) {
                        n.summonArrived = true;
                        if (!n.following) n.summonUntil = Math.max(n.summonUntil, core.tick() + 20L * 60); // espera 1 min por ordens
                        core.bus().publish(core.tick(), EventType.NPC_ARRIVED, GameEvent.Severity.INFO, n.kingdomId, n.id,
                                n.name + " chegou: \"" + (n.following ? "Vou com Vossa Majestade." : "Às suas ordens, Majestade.") + "\"");
                    }
                }
            }
            Intent i = decide(n, time);
            if (n.activity != NpcActivity.TALKING) n.activity = i.activity();
            if (!n.materialized && i.target() != null && n.pos != null) {
                // NPC abstrato: "teleporta" gradualmente para o destino (simulação agregada). Fugindo corre; cansado/ferido anda devagar.
                int step = i.activity() == NpcActivity.SUMMONED || i.activity() == NpcActivity.FLEE ? 6 : n.energy < 15 || n.health < 40 ? 3 : 4;
                n.pos = moveTowards(n.pos, i.target(), step);
                if (i.activity() == NpcActivity.IMPRISONED) n.pos = i.target();
            }
        }
    }

    private static Pos moveTowards(Pos from, Pos to, int step) {
        int dx = to.x() - from.x(), dz = to.z() - from.z();
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d <= step) return new Pos(to.x(), to.y() == Integer.MIN_VALUE ? from.y() : to.y(), to.z());
        return new Pos(from.x() + (int) Math.round(dx / d * step), from.y(), from.z() + (int) Math.round(dz / d * step));
    }

    public Intent decide(Npc n, long dayTime) {
        Kingdom k = core.kingdom(n.kingdomId);
        if (k == null) return new Intent(NpcActivity.IDLE, n.pos, 4);
        // chamado do rei vem antes de tudo (até do sono)
        if (isSummoned(n)) return new Intent(NpcActivity.SUMMONED, n.summonTarget, n.following ? 3 : 2);
        // em campanha (marcha, ocupação, volta) ou preso como cativo
        Intent war = core.warfare().intentFor(n);
        if (war != null) return war;
        // reflexo: quem vê um monstro foge (até no meio de uma ordem)
        Intent flee = core.life().emergencyIntent(n);
        if (flee != null) return flee;
        // ordem física direta (quebrar, baú, fabricar) também passa na frente do sono
        Intent job = core.skills().intentFor(n);
        if (job != null) return job;
        int h = Math.abs(n.id.hashCode());

        // cada um dorme no seu horário (disciplina, sociabilidade; metade dos guardas no turno da noite)
        if (core.life().asleep(n, dayTime)) {
            n.currentTask = "Dormindo";
            Building home = n.homeId == null ? null : core.state().buildings.get(n.homeId);
            if (home != null && home.isComplete() && home.origin.y() != Integer.MIN_VALUE)
                return new Intent(NpcActivity.SLEEP, home.centerPos(), 2);
            return new Intent(NpcActivity.SLEEP, k.spawnPoint().offset((h % 9) - 4, 0, (h / 9 % 9) - 4), 3);
        }
        // a vida fora do serviço: conversa, refeição, descanso, lazer do fim de tarde, visita
        Intent life = core.life().intentFor(n);
        if (life != null) return life;
        if (core.life().evening(n, dayTime))
            return new Intent(NpcActivity.SOCIALIZE, k.marker(Marker.GATHER, k.center).offset((h % 11) - 5, 0, (h / 11 % 7) - 3), 4);

        // Rotina dada pelo rei (cadeia de trabalho) vem antes da rotina da profissão.
        Intent duty = core.work().intentFor(n);
        if (duty != null) return duty;

        return switch (n.profession) {
            case BUILDER -> {
                Building b = core.construction().currentProjectOf(n);
                if (b != null) {
                    n.currentTask = "Construir " + b.blueprint().displayName();
                    Pos target = b.origin.y() == Integer.MIN_VALUE ? b.entrance() : b.entrance();
                    yield new Intent(NpcActivity.BUILD, target, 3);
                }
                n.currentTask = "Aguardando obras";
                yield new Intent(NpcActivity.IDLE, k.center.offset(3, 0, -3), 6);
            }
            case FARMER -> {
                Building farm = nearest(k, "farm", n.pos);
                n.currentTask = farm != null ? "Cuidar da fazenda" : "Plantar nos campos";
                yield new Intent(NpcActivity.WORK, farm != null ? farm.centerPos() : workSpot(k, h, 16), farm != null ? 4 : 7);
            }
            case LUMBERJACK -> {
                n.currentTask = "Cortar lenha";
                Pos forest = k.markers.get(Marker.FOREST);
                yield new Intent(NpcActivity.WORK, forest != null ? spread(forest, h, 6) : workSpot(k, h + 3, 28), 8);
            }
            case MINER -> {
                n.currentTask = "Extrair pedra e ferro";
                Pos mine = k.markers.get(Marker.MINE);
                yield new Intent(NpcActivity.WORK, mine != null ? spread(mine, h, 4) : workSpot(k, h + 7, 24), 6);
            }
            case BLACKSMITH -> {
                Building smithy = nearest(k, "smithy", n.pos);
                n.currentTask = "Forjar armas";
                yield new Intent(NpcActivity.WORK, smithy != null ? smithy.centerPos() : k.center.offset(-5, 0, -5), 2);
            }
            case GUARD -> {
                Pos alarm = core.life().alertFor(n);
                if (alarm != null) {
                    n.currentTask = "Atender a um pedido de socorro";
                    yield new Intent(NpcActivity.PATROL, alarm, 2);
                }
                // a ronda segue a borda da vila (ou o pé da muralha, se houver)
                var vb = com.kingdomsai.core.construction.VillageWall.bounds(core, k);
                boolean walled = com.kingdomsai.core.construction.VillageWall.existing(core, k) != null;
                n.currentTask = walled ? "Patrulhar a muralha" : "Patrulhar a borda da vila";
                long phase = (core.tick() / (20 * 40) + h) % 6;
                double a = phase * Math.PI / 3;
                int r = Math.max(14, vb.radius() - (walled ? 3 : 1));
                yield new Intent(NpcActivity.PATROL, vb.center(k.center.y()).offset((int) (Math.cos(a) * r), 0, (int) (Math.sin(a) * r)), 3);
            }
            case SOLDIER -> {
                Pos alarm = core.life().alertFor(n);
                if (alarm != null) {
                    n.currentTask = "Atender a um pedido de socorro";
                    yield new Intent(NpcActivity.PATROL, alarm, 2);
                }
                Building barracks = nearest(k, "barracks", n.pos);
                n.currentTask = "Treinar";
                yield new Intent(NpcActivity.GUARD, barracks != null ? barracks.entrance() : k.center.offset(-6, 0, 6), 4);
            }
            case MERCHANT, PRIEST, SCHOLAR -> {
                n.currentTask = n.profession.display;
                yield new Intent(NpcActivity.WORK, k.center.offset((h % 7) - 3, 0, (h / 7 % 5) - 2), 3);
            }
            default -> {
                n.currentTask = "Ajudar na vila";
                yield new Intent(NpcActivity.IDLE, k.center.offset((h % 15) - 7, 0, (h / 15 % 15) - 7), 8);
            }
        };
    }

    /** Lugar de trabalho "na natureza" (bosque, pedreira) numa direção estável por NPC. */
    /** Cada um num canto perto do marco (não ficam todos empilhados). */
    private static Pos spread(Pos p, int hash, int r) {
        return p.offset((hash % (2 * r + 1)) - r, 0, (hash / 7 % (2 * r + 1)) - r);
    }

    private Pos workSpot(Kingdom k, int hash, int dist) {
        double a = (hash % 360) * Math.PI / 180.0;
        return k.center.offset((int) (Math.cos(a) * dist), 0, (int) (Math.sin(a) * dist));
    }

    private Building nearest(Kingdom k, String blueprintId, Pos from) {
        Building best = null;
        double bd = Double.MAX_VALUE;
        for (Building b : core.buildings(k.id)) {
            if (!b.isComplete() || !b.blueprintId.equals(blueprintId) || b.origin.y() == Integer.MIN_VALUE) continue;
            double d = from == null ? 0 : b.centerPos().distSq(from);
            if (d < bd) {
                bd = d;
                best = b;
            }
        }
        return best;
    }
}
