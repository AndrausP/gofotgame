package com.kingdomsai.core.military;

import com.kingdomsai.core.KingdomsCore;
import com.kingdomsai.core.ai.NpcScheduler;
import com.kingdomsai.core.common.Pos;
import com.kingdomsai.core.common.Text;
import com.kingdomsai.core.construction.Building;
import com.kingdomsai.core.construction.VillageWall;
import com.kingdomsai.core.diplomacy.Diplomacy;
import com.kingdomsai.core.event.EventType;
import com.kingdomsai.core.event.GameEvent;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.kingdom.ResourceType;
import com.kingdomsai.core.npc.*;
import com.kingdomsai.core.territory.TerritoryMap;

import java.util.*;

/**
 * Guerra e domínio: exército sem ouro (custa comida), terra livre até um limite, colonos e tropas que tomam terra além dele,
 * batalhas, cativos, e as ordens extremas do rei (massacre, escravidão, libertação) — sempre com quem cumpre podendo recusar
 * e com consequências para a legitimidade, a estabilidade, a infâmia e a diplomacia.
 *
 * <p>Batalhas são simuladas (como as obras com o rei longe): força = soldados × coragem/disciplina/armas/moral,
 * defesa = militares do dono perto do alvo + muralha + milícia. Os NPCs marcham de verdade; o choque é abstrato.
 */
public final class MilitarySystem {
    public static final double MARCH_BLOCKS_PER_SECOND = 4.0;
    public static final int HOLD_SECONDS = 60;
    /** Comida por ciclo econômico (o civil come 1,0). */
    public static final double SOLDIER_FOOD = 2.0, GUARD_FOOD = 1.5, CAMPAIGN_EXTRA_FOOD = 1.0;
    public static final int CLAIM_BASE = 30, CLAIM_PER_CITIZEN = 2, CLAIM_PER_MILITARY = 3;

    private final KingdomsCore core;

    public MilitarySystem(KingdomsCore core) {
        this.core = core;
    }

    // ================================================================== território

    /** Até quantas células o reino sustenta por reivindicação pacífica (grátis). Além disso, só ocupando. */
    public int claimLimit(Kingdom k) {
        int free = 0;
        for (Npc n : core.citizens(k.id)) if (n.isFree()) free++;
        return CLAIM_BASE + CLAIM_PER_CITIZEN * free + CLAIM_PER_MILITARY * core.military(k.id);
    }

    public int freeClaims(Kingdom k) {
        return Math.max(0, claimLimit(k) - core.state().territory.countOwned(k.id));
    }

    // ================================================================== quem vai (o comandante decide)

    /** O comandante: o citado pelo nome; senão general > capitão > o soldado mais experiente. */
    public Npc commander(Kingdom k, String name) {
        if (name != null && !name.isBlank()) {
            Npc n = core.findNpc(k.id, name);
            if (n != null) return n;
        }
        Npc best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Npc n : core.citizens(k.id)) {
            if (!n.isFree()) continue;
            int s = n.office == Office.GENERAL ? 1000 : n.office == Office.CAPTAIN ? 900
                    : n.profession.isMilitary() ? n.fame * 3 + n.trait(Trait.COURAGE) + n.trait(Trait.DISCIPLINE) : Integer.MIN_VALUE;
            if (s > bestScore) {
                bestScore = s;
                best = n;
            }
        }
        return bestScore == Integer.MIN_VALUE ? null : best;
    }

    /** Militares em casa e livres para sair (não estão em campanha, chamados ou numa ordem com as mãos). */
    public List<Npc> available(Kingdom k, boolean withGuards) {
        List<Npc> out = new ArrayList<>();
        for (Npc n : core.citizens(k.id)) {
            if (!n.isFree() || atWar(n) || n.office == Office.KING) continue;
            if (n.profession == Profession.SOLDIER || withGuards && n.profession == Profession.GUARD) out.add(n);
        }
        out.sort(Comparator.comparingDouble((Npc n) -> -power(n, k)));
        return out;
    }

    /** Está numa campanha de guerra (ataque/colonização) — treino e deslocamento não contam. */
    public boolean atWar(Npc n) {
        if (n.campaignId == null) return false;
        Campaign c = core.state().campaigns.get(n.campaignId);
        return c != null && c.live() && c.war();
    }

    /** Força de combate de uma pessoa: espada do arsenal vale +30%; treino aparece em disciplina e coragem. */
    public double power(Npc n, Kingdom k) {
        double p = n.profession == Profession.SOLDIER ? 1.0 : n.profession == Profession.GUARD ? 0.8 : 0.25;
        p *= 0.75 + n.trait(Trait.COURAGE) / 400.0 + n.trait(Trait.DISCIPLINE) / 400.0;
        if (!n.equipped.isEmpty() && n.profession.isMilitary()) p *= 1.3;
        p *= 0.7 + k.morale / 333.0;
        if (n.hunger < 20) p *= 0.6;
        return p;
    }

    /** Defesa estimada do dono da terra num ponto (o que o comandante calcula antes de escolher a tropa). */
    public double defenseAt(Kingdom owner, Pos where) {
        if (owner == null) return 0;
        double near = nearFactor(owner, where);
        double d = 0;
        int civilians = 0;
        for (Npc n : core.citizens(owner.id)) {
            if (atWar(n) || !n.isFree()) continue;
            if (n.profession.isMilitary()) d += power(n, owner);
            else civilians++;
        }
        d *= near;
        if (near >= 1.0) {
            d += 0.15 * civilians; // milícia: gente da vila defende a própria casa
            Building wall = VillageWall.existing(core, owner);
            if (wall != null && wall.isComplete()) d *= 1.5;
        }
        return d;
    }

    private double nearFactor(Kingdom owner, Pos where) {
        double dist = where.distXZ(owner.center);
        return dist < 80 ? 1.0 : dist < 200 ? 0.6 : 0.35;
    }

    /** "Ele determina quem faz guerra": leva o bastante para vencer com folga, sem deixar a vila nua à toa. */
    public List<Npc> chooseTroops(Kingdom k, Kingdom owner, Pos where, Integer amount, boolean withGuards) {
        List<Npc> pool = available(k, withGuards);
        if (amount != null) return new ArrayList<>(pool.subList(0, Math.min(amount, pool.size())));
        double need = defenseAt(owner, where) * 1.35 + (owner == null ? 0.5 : 1.0);
        List<Npc> out = new ArrayList<>();
        double got = 0;
        for (Npc n : pool) {
            if (got >= need && out.size() >= 2) break;
            out.add(n);
            got += power(n, k);
        }
        return out;
    }

    /** Quantos convocar quando o rei não diz o número: o comandante olha a maior ameaça. */
    public int recruitDecision(Kingdom k) {
        int threat = 0;
        for (Kingdom o : core.state().kingdoms.values()) {
            if (o == k) continue;
            Diplomacy.State st = core.diplomacy().link(k.id, o.id).state;
            if (st == Diplomacy.State.WAR || st == Diplomacy.State.HOSTILE || st == Diplomacy.State.TENSION)
                threat = Math.max(threat, core.military(o.id));
        }
        int want = threat + 2 - core.military(k.id);
        return Math.max(1, Math.min(10, want));
    }

    // ================================================================== campanhas

    public List<Campaign> campaigns(UUID kingdomId) {
        List<Campaign> out = new ArrayList<>();
        for (Campaign c : core.state().campaigns.values()) if (kingdomId.equals(c.kingdomId)) out.add(c);
        out.sort(Comparator.comparingInt(c -> c.number));
        return out;
    }

    public Campaign find(UUID kingdomId, String ref) {
        List<Campaign> live = campaigns(kingdomId).stream().filter(Campaign::live).toList();
        if (ref == null || ref.isBlank()) return live.isEmpty() ? null : live.get(live.size() - 1);
        String n = Text.norm(ref).replace("#", "");
        for (Campaign c : campaigns(kingdomId)) if (String.valueOf(c.number).equals(n)) return c;
        for (Campaign c : live) {
            Kingdom t = core.kingdom(c.targetKingdomId);
            if (t != null && Text.norm(t.name).contains(n)) return c;
            for (UUID id : c.members) {
                Npc m = core.npc(id);
                if (m != null && Text.norm(m.name).startsWith(n)) return c;
            }
        }
        return null;
    }

    public Campaign launch(Kingdom k, Campaign.Kind kind, Npc commander, List<Npc> people, Kingdom owner, Pos target, boolean noQuarter,
                           boolean surprise, String order) {
        Campaign c = new Campaign();
        c.id = UUID.randomUUID();
        c.number = ++core.state().campaignCounter;
        c.kingdomId = k.id;
        c.kind = kind;
        c.targetKingdomId = owner == null ? null : owner.id;
        c.target = target;
        c.origin = k.spawnPoint();
        c.commanderId = commander == null ? null : commander.id;
        c.noQuarter = noQuarter;
        c.surprise = surprise;
        c.order = order == null ? "" : Text.truncate(order, 120);
        c.startTick = core.tick();
        c.arriveTick = core.tick() + 20L * travelSeconds(c.origin, target);
        for (Npc n : people) {
            leaveDrill(n);
            c.members.add(n.id);
            n.campaignId = c.id;
            n.jobId = null;
            core.scheduler().dismiss(n);
            n.remember(core.tick(), (kind == Campaign.Kind.ATTACK ? "Parti em campanha contra " : "Parti para colonizar ")
                    + where(owner, target) + ".", 60, null, "guerra");
        }
        core.state().campaigns.put(c.id, c);
        String who = names(people);
        c.log("Partiram: " + who + (commander != null && !people.contains(commander) ? " (escolhidos por " + commander.displayName() + ")" : "")
                + ". Chegada em ~" + travelSeconds(c.origin, target) + " s.");
        core.bus().publish(core.tick(), EventType.CAMPAIGN_STARTED, GameEvent.Severity.INFO, k.id, commander == null ? null : commander.id,
                (kind == Campaign.Kind.ATTACK ? "Tropa #" : "Colonos #") + c.number + " partiu para " + where(owner, target) + ": " + who + ".",
                Map.of("campaign", c.id.toString()));
        if (owner != null)
            core.bus().publish(core.tick(), EventType.CAMPAIGN_STARTED, GameEvent.Severity.DANGER, owner.id, null,
                    (kind == Campaign.Kind.ATTACK ? "⚔ Tropa de " + k.name + " (" + people.size() + ") marcha contra nós" : "Colonos de " + k.name + " vêm tomar nossa terra")
                            + " — chegam em ~" + travelSeconds(c.origin, target) + " s em (" + target.x() + ", " + target.z() + ").",
                    Map.of("campaign", c.id.toString(), "other", k.id.toString()));
        return c;
    }

    public static int travelSeconds(Pos from, Pos to) {
        return (int) Math.max(10, Math.round(from.distXZ(to) / MARCH_BLOCKS_PER_SECOND));
    }

    public String retreat(Campaign c) {
        if (!c.live()) return "A campanha #" + c.number + " já terminou.";
        if (c.status == Campaign.Status.RETURNING) return "A tropa #" + c.number + " já está voltando.";
        startReturn(c, "Retirada ordenada pelo rei.");
        return "Tropa #" + c.number + " recuando. Chega em casa em ~" + (c.returnTick - core.tick()) / 20 + " s.";
    }

    private void startReturn(Campaign c, String why) {
        c.status = Campaign.Status.RETURNING;
        Pos from = c.target;
        c.returnTick = core.tick() + 20L * travelSeconds(from, c.origin);
        c.log(why);
    }

    /** Cada segundo: chegada, batalha, segurar a terra, volta. */
    public void tickSecond() {
        for (Campaign c : new ArrayList<>(core.state().campaigns.values())) {
            if (!c.live()) continue;
            Kingdom k = core.kingdom(c.kingdomId);
            List<Npc> troops = members(c);
            if (k == null || troops.isEmpty()) {
                end(c, Campaign.Status.FAILED, "Ninguém da tropa sobrou.");
                continue;
            }
            long now = core.tick();
            if (!c.war()) {
                drillTick(c, k, troops, now);
                continue;
            }
            switch (c.status) {
                case MARCHING -> {
                    if (now >= c.arriveTick) arrive(c, k, troops);
                }
                case HOLDING -> {
                    if (now >= c.holdUntil) startReturn(c, "Terra segura; a tropa volta para casa.");
                }
                case RETURNING -> {
                    if (now >= c.returnTick) end(c, c.result.startsWith("✗") ? Campaign.Status.FAILED : Campaign.Status.DONE, "Voltaram para casa.");
                }
                default -> {
                }
            }
        }
    }

    private List<Npc> members(Campaign c) {
        List<Npc> out = new ArrayList<>();
        for (Iterator<UUID> it = c.members.iterator(); it.hasNext(); ) {
            Npc n = core.npc(it.next());
            if (n == null || !n.alive || !c.kingdomId.equals(n.kingdomId)) it.remove();
            else out.add(n);
        }
        return out;
    }

    private void end(Campaign c, Campaign.Status st, String why) {
        c.status = st;
        c.log(why);
        for (UUID id : c.members) {
            Npc n = core.npc(id);
            if (n != null && c.id.equals(n.campaignId)) {
                n.campaignId = null;
                if (n.heldItem.equals("minecraft:white_banner")) n.heldItem = "";
            }
        }
        core.bus().publish(core.tick(), EventType.CAMPAIGN_ENDED, st == Campaign.Status.DONE ? GameEvent.Severity.GOOD : GameEvent.Severity.WARN,
                c.kingdomId, null, (c.kind == Campaign.Kind.ATTACK ? "Tropa #" : "Colonos #") + c.number + ": " + why
                        + (c.result.isBlank() ? "" : " " + c.result), Map.of("campaign", c.id.toString()));
    }

    private void arrive(Campaign c, Kingdom k, List<Npc> troops) {
        for (Npc n : troops)
            if (!n.materialized) n.pos = c.target.offset(Math.abs(n.id.hashCode()) % 7 - 3, 0, Math.abs(n.id.hashCode() / 7) % 7 - 3);
        TerritoryMap.Cell cell = core.state().territory.cellAt(c.target);
        Kingdom owner = cell == null || cell.owner == null || cell.owner.equals(k.id) ? null : core.kingdom(cell.owner);
        if (c.kind == Campaign.Kind.SETTLE) settle(c, k, troops, owner);
        else if (owner == null) {
            int taken = takeCells(k, null, c.target, 50);
            c.cellsTaken += taken;
            c.result = "✓ Terra ocupada sem luta: " + taken + " célula(s).";
            hold(c, k);
            core.bus().publish(core.tick(), EventType.TERRITORY_CONQUERED, GameEvent.Severity.GOOD, k.id, null,
                    k.name + " ocupou " + taken + " célula(s) de terra livre com a tropa #" + c.number + ".");
        } else battle(c, k, troops, owner);
    }

    private void hold(Campaign c, Kingdom k) {
        c.status = Campaign.Status.HOLDING;
        c.holdUntil = core.tick() + 20L * HOLD_SECONDS;
        c.log(c.result);
    }

    private void battle(Campaign c, Kingdom k, List<Npc> troops, Kingdom owner) {
        Random rng = core.rng();
        Npc cmd = core.npc(c.commanderId);
        double bonus = cmd == null ? 0 : cmd.office == Office.GENERAL ? 0.15 : cmd.office == Office.CAPTAIN ? 0.08 : 0.03;
        double att = 0;
        for (Npc n : troops) att += power(n, k);
        att *= (1 + bonus) * (0.85 + rng.nextDouble() * 0.3) * (k.famine ? 0.7 : 1.0);
        double near = nearFactor(owner, c.target);
        List<Npc> defenders = new ArrayList<>();
        for (Npc n : core.citizens(owner.id))
            if (n.isFree() && !atWar(n) && n.profession.isMilitary()) defenders.add(n);
        double def = defenseAt(owner, c.target) * (0.85 + rng.nextDouble() * 0.3);
        double ratio = att + def <= 0 ? 1 : att / (att + def);
        boolean win = ratio > 0.5;
        int present = (int) Math.round(defenders.size() * near);
        int attLoss = (int) Math.round(troops.size() * (1 - ratio) * (win ? 0.35 : 0.6) * (0.7 + rng.nextDouble() * 0.6));
        int defLoss = (int) Math.round(present * ratio * (win ? 0.8 : 0.35) * (0.7 + rng.nextDouble() * 0.6));
        attLoss = Math.min(attLoss, troops.size() - (win ? 1 : 0));
        List<Npc> weakest = new ArrayList<>(troops);
        weakest.sort(Comparator.comparingDouble(n -> power(n, k)));
        List<String> fallen = new ArrayList<>();
        for (int i = 0; i < attLoss && i < weakest.size(); i++) {
            die(weakest.get(i), "morreu em batalha contra " + owner.name);
            fallen.add(weakest.get(i).name);
        }
        defenders.sort(Comparator.comparingDouble(n -> power(n, owner)));
        List<String> enemyFallen = new ArrayList<>();
        for (int i = 0; i < defLoss && i < defenders.size(); i++) {
            die(defenders.get(i), "morreu defendendo " + owner.name + " de " + k.name);
            enemyFallen.add(defenders.get(i).name);
        }
        c.losses += fallen.size();
        c.kills += enemyFallen.size();
        String tally = String.format(Locale.ROOT, "Força %.1f × %.1f.", att, def)
                + (fallen.isEmpty() ? "" : " Nossos mortos: " + String.join(", ", fallen) + ".")
                + (enemyFallen.isEmpty() ? "" : " Mortos de " + owner.name + ": " + String.join(", ", enemyFallen) + ".");
        core.diplomacy().attitude(owner.id, k.id).hostility = 100;
        if (win) {
            int taken = takeCells(k, owner, c.target, 60);
            c.cellsTaken += taken;
            c.result = "✓ Vitória sobre " + owner.name + ": " + taken + " célula(s) tomada(s). " + tally;
            for (Npc n : members(c)) n.remember(core.tick(), "Vencemos " + owner.name + " em batalha.", 75, null, "guerra", "vitória");
            core.bus().publish(core.tick(), EventType.BATTLE_WON, GameEvent.Severity.GOOD, k.id, cmd == null ? null : cmd.id,
                    "Tropa #" + c.number + " venceu " + owner.name + " e tomou " + taken + " célula(s). " + tally,
                    Map.of("campaign", c.id.toString(), "other", owner.id.toString()));
            core.bus().publish(core.tick(), EventType.BATTLE_LOST, GameEvent.Severity.DANGER, owner.id, null,
                    owner.name + " perdeu " + taken + " célula(s) para " + k.name + ".", Map.of("other", k.id.toString()));
            k.morale = Text.clamp(k.morale + 5, 0, 100);
            owner.morale = Text.clamp(owner.morale - 10, 0, 100);
            boolean capital = c.target.distXZ(owner.center) <= core.state().territory.cellSize * 1.5;
            boolean noArmyLeft = available(owner, true).isEmpty();
            if (capital && noArmyLeft) conquer(c, k, owner);
            hold(c, k);
        } else {
            c.result = "✗ Derrota diante de " + owner.name + ". " + tally;
            for (Npc n : members(c)) n.remember(core.tick(), "Fomos derrotados por " + owner.name + ".", 70, null, "guerra", "derrota");
            core.bus().publish(core.tick(), EventType.BATTLE_LOST, GameEvent.Severity.DANGER, k.id, cmd == null ? null : cmd.id,
                    "Tropa #" + c.number + " foi derrotada por " + owner.name + ". " + tally,
                    Map.of("campaign", c.id.toString(), "other", owner.id.toString()));
            k.morale = Text.clamp(k.morale - 8, 0, 100);
            startReturn(c, c.result);
        }
        core.chronicle((win ? k.name + " venceu " : owner.name + " repeliu ") + (win ? owner.name : k.name) + " em batalha.");
    }

    /** A vila caiu: os civis viram cativos (ou morrem, se o rei ordenou "sem piedade"); o resto da terra passa ao vencedor. */
    private void conquer(Campaign c, Kingdom k, Kingdom owner) {
        List<Npc> people = new ArrayList<>();
        for (Npc n : core.citizens(owner.id)) if (!atWar(n)) people.add(n);
        if (c.noQuarter) {
            for (Npc n : people) die(n, "morreu no saque de " + owner.name + " (sem piedade)");
            c.kills += people.size();
            atrocity(k, people.size(), 0, people.size(), owner, "o saque sem piedade de " + owner.name);
        } else {
            for (Npc n : people) {
                n.originKingdomId = owner.id;
                n.kingdomId = k.id;
                n.freedom = Freedom.CAPTIVE;
                if (!n.equipped.isEmpty()) k.add(ResourceType.WEAPONS, 1); // a espada do vencido vai para o arsenal
                n.equipped = "";
                n.office = Office.NONE;
                n.campaignId = null;
                n.dutyChainId = null;
                n.jobId = null;
                n.homeId = null;
                n.loyalty = 10;
                n.remember(core.tick(), owner.name + " caiu. Fui levado cativo para " + k.name + ".", 95, null, "guerra", "cativeiro");
            }
            c.captives += people.size();
            if (!people.isEmpty())
                core.bus().publish(core.tick(), EventType.CAPTIVES_TAKEN, GameEvent.Severity.WARN, k.id, null,
                        people.size() + " cativo(s) de " + owner.name + ": " + names(people) + ". Decida: libertar, escravizar ou executar.");
        }
        int moved = 0;
        for (TerritoryMap.Cell cell : core.state().territory.cells.values())
            if (owner.id.equals(cell.owner)) {
                cell.owner = k.id;
                cell.control = 40;
                moved++;
            }
        c.cellsTaken += moved;
        for (Building b : core.buildings(owner.id)) b.kingdomId = k.id;
        owner.stability = 0;
        Diplomacy.Link l = core.diplomacy().link(k.id, owner.id);
        if (l.state == Diplomacy.State.WAR) core.diplomacy().setState(l, k, owner, Diplomacy.State.ARMISTICE);
        c.result += " " + owner.name + " CAIU: a vila e " + moved + " célula(s) são de " + k.name + ".";
        core.bus().publish(core.tick(), EventType.VILLAGE_CONQUERED, GameEvent.Severity.GOOD, k.id, null,
                owner.name + " caiu diante de " + k.name + (c.noQuarter ? " — ninguém foi poupado." : " — " + people.size() + " cativo(s)."),
                Map.of("other", owner.id.toString()));
        core.chronicle(owner.name + " foi conquistado por " + k.name + ".");
    }

    /** Toma a célula do alvo e as vizinhas (3×3) que forem livres ou do dono derrotado. Nunca as de um terceiro. */
    private int takeCells(Kingdom k, Kingdom owner, Pos target, double control) {
        TerritoryMap t = core.state().territory;
        int cx = target.cellX(t.cellSize), cz = target.cellZ(t.cellSize), n = 0;
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                TerritoryMap.Cell cell = t.cellAt(cx + dx, cz + dz);
                UUID o = cell == null ? null : cell.owner;
                if (o != null && (o.equals(k.id) || owner == null || !o.equals(owner.id))) continue;
                t.claim(cx + dx, cz + dz, k.id, control, core.tick());
                n++;
            }
        return n;
    }

    private void settle(Campaign c, Kingdom k, List<Npc> settlers, Kingdom owner) {
        if (owner != null) {
            double def = defenseAt(owner, c.target);
            if (def >= 1.0) {
                c.result = "✗ Os colonos foram expulsos por gente de " + owner.name + " (defesa " + Text.fmt(def) + "). Mande tropas (ATTACK).";
                core.diplomacy().attitude(owner.id, k.id).hostility = Text.clamp(core.diplomacy().attitude(owner.id, k.id).hostility + 15, 0, 100);
                startReturn(c, c.result);
                return;
            }
            core.diplomacy().attitude(owner.id, k.id).hostility = Text.clamp(core.diplomacy().attitude(owner.id, k.id).hostility + 30, 0, 100);
            k.honor = Text.clamp(k.honor - 5, 0, 100);
        }
        int taken = takeCells(k, owner, c.target, 45);
        c.cellsTaken += taken;
        c.result = "✓ Colonos fincaram os marcos: " + taken + " célula(s)" + (owner == null ? "" : " tomadas de " + owner.name + " (sem defesa)") + ".";
        for (Npc n : settlers) n.remember(core.tick(), "Fundei um posto em " + where(owner, c.target) + ".", 65, null, "colônia");
        core.bus().publish(core.tick(), EventType.SETTLED, GameEvent.Severity.GOOD, k.id, null,
                "Colonos #" + c.number + " tomaram " + taken + " célula(s)" + (owner == null ? "" : " de " + owner.name) + ".");
        hold(c, k);
    }

    private void die(Npc n, String cause) {
        n.alive = false;
        n.campaignId = null;
        core.bus().publish(core.tick(), EventType.NPC_DIED, GameEvent.Severity.WARN, n.kingdomId, n.id, n.name + " " + cause + ".");
    }

    /** Intenção da rotina: marchar, segurar a terra, voltar; cativos ficam presos no centro. */
    public NpcScheduler.Intent intentFor(Npc n) {
        if (n.campaignId != null) {
            Campaign c = core.state().campaigns.get(n.campaignId);
            if (c == null || !c.live()) {
                n.campaignId = null;
                return null;
            }
            if (!c.war()) {
                if (n.jobId != null) { // ordem com as mãos dada no meio do treino: ela vale mais
                    leaveDrill(n);
                    return null;
                }
                return drillIntent(c, n);
            }
            n.heldItem = n.id.equals(c.commanderId) ? "minecraft:white_banner" : "";
            int h = Math.abs(n.id.hashCode());
            Pos spot = c.target.offset(h % 7 - 3, 0, h / 7 % 7 - 3);
            return switch (c.status) {
                case MARCHING -> {
                    n.currentTask = (c.kind == Campaign.Kind.ATTACK ? "Marchando contra " : "Indo colonizar ") + where(core.kingdom(c.targetKingdomId), c.target)
                            + " (~" + Math.max(0, (c.arriveTick - core.tick()) / 20) + " s)";
                    yield new NpcScheduler.Intent(NpcActivity.MARCH, spot, 3);
                }
                case HOLDING -> {
                    n.currentTask = c.kind == Campaign.Kind.ATTACK ? "Segurando a terra tomada" : "Fincando marcos da colônia";
                    yield new NpcScheduler.Intent(NpcActivity.GUARD, spot, 4);
                }
                default -> {
                    n.currentTask = "Voltando para casa";
                    yield new NpcScheduler.Intent(NpcActivity.MARCH, c.origin.offset(h % 7 - 3, 0, h / 7 % 7 - 3), 4);
                }
            };
        }
        if (n.freedom == Freedom.CAPTIVE) {
            Kingdom k = core.kingdom(n.kingdomId);
            if (k == null) return null;
            int h = Math.abs(n.id.hashCode());
            n.currentTask = "Preso (cativo de guerra)";
            return new NpcScheduler.Intent(NpcActivity.IMPRISONED, k.center.offset(-6 + h % 3, 0, 6 + h / 3 % 3), 1);
        }
        return null;
    }

    // ================================================================== treino e deslocamento (ordens que se VÊEM)

    /** Treino ou "vão para tal lugar": o líder vai na frente, os outros em fileiras de 4; ficam {@code seconds} lá. */
    public Campaign assemble(Kingdom k, Campaign.Kind kind, Npc leader, List<Npc> people, Pos target, int seconds, String order) {
        Campaign c = new Campaign();
        c.id = UUID.randomUUID();
        c.number = ++core.state().campaignCounter;
        c.kingdomId = k.id;
        c.kind = kind;
        c.target = target;
        c.origin = leader != null && leader.pos != null ? leader.pos : k.center;
        c.commanderId = leader == null ? null : leader.id;
        c.order = order == null ? "" : Text.truncate(order, 120);
        c.holdSeconds = Math.max(10, Math.min(20 * 60, seconds));
        c.startTick = core.tick();
        List<Npc> all = new ArrayList<>();
        if (leader != null) all.add(leader);
        for (Npc n : people) if (!all.contains(n)) all.add(n);
        int farthest = 0;
        for (Npc n : all) {
            leaveDrill(n);
            c.members.add(n.id);
            n.campaignId = c.id;
            core.scheduler().dismiss(n);
            if (n.pos != null) farthest = Math.max(farthest, (int) n.pos.distXZ(target));
        }
        c.arriveTick = core.tick() + 20L * Math.max(3, Math.round(farthest / MARCH_BLOCKS_PER_SECOND));
        core.state().campaigns.put(c.id, c);
        c.log((kind == Campaign.Kind.TRAIN ? "Treino" : "Deslocamento") + ": " + names(all) + " → (" + target.x() + ", " + target.z() + ").");
        core.bus().publish(core.tick(), EventType.CAMPAIGN_STARTED, GameEvent.Severity.INFO, k.id, leader == null ? null : leader.id,
                (kind == Campaign.Kind.TRAIN ? "Treino #" : "Grupo #") + c.number + ": " + names(all) + " a caminho de (" + target.x() + ", " + target.z() + ").",
                Map.of("campaign", c.id.toString()));
        return c;
    }

    /** Sai de um treino/deslocamento (outra ordem chegou). Guerra não se larga assim. */
    public void leaveDrill(Npc n) {
        if (n.campaignId == null) return;
        Campaign c = core.state().campaigns.get(n.campaignId);
        if (c == null || !c.live()) {
            n.campaignId = null;
            return;
        }
        if (c.war()) return;
        c.members.remove(n.id);
        n.campaignId = null;
        if (n.heldItem.equals("minecraft:white_banner")) n.heldItem = "";
    }

    private void drillTick(Campaign c, Kingdom k, List<Npc> people, long now) {
        if (c.status == Campaign.Status.MARCHING && now >= c.arriveTick) {
            c.status = Campaign.Status.HOLDING;
            c.holdUntil = now + 20L * c.holdSeconds;
            for (Npc n : people) if (!n.materialized) n.pos = spot(c, n, false);
            c.log("Chegaram: " + names(people) + ".");
        } else if (c.status == Campaign.Status.HOLDING) {
            long held = (now - (c.holdUntil - 20L * c.holdSeconds)) / 20;
            if (c.kind == Campaign.Kind.TRAIN && held > 0 && held % 30 == 0) {
                for (Npc n : people) {
                    if (n.id.equals(c.commanderId)) continue;
                    n.traits.merge(Trait.DISCIPLINE, 1, (a, b) -> Math.min(95, a + b));
                    if (held % 60 == 0) n.traits.merge(Trait.COURAGE, 1, (a, b) -> Math.min(95, a + b));
                }
                c.kills++; // contador de rodadas de treino
            }
            if (now >= c.holdUntil) {
                Npc lead = core.npc(c.commanderId);
                if (c.kind == Campaign.Kind.TRAIN) {
                    if (lead != null) lead.fame += 1;
                    for (Npc n : people)
                        if (!n.id.equals(c.commanderId))
                            n.remember(core.tick(), "Treinei com " + (lead == null ? "a tropa" : lead.name) + ".", 40, lead == null ? null : lead.id, "treino");
                    c.result = "✓ Treino concluído: " + c.kills + " rodada(s), disciplina +" + c.kills + (c.kills >= 2 ? ", coragem +" + c.kills / 2 : "") + ".";
                } else c.result = "✓ Cumprido.";
                end(c, Campaign.Status.DONE, c.kind == Campaign.Kind.TRAIN ? "Treino encerrado." : "Dispensados do lugar combinado.");
            }
        }
    }

    private NpcScheduler.Intent drillIntent(Campaign c, Npc n) {
        boolean leader = n.id.equals(c.commanderId);
        boolean training = c.kind == Campaign.Kind.TRAIN && c.status == Campaign.Status.HOLDING;
        Pos at = spot(c, n, training);
        Npc lead = core.npc(c.commanderId);
        n.heldItem = leader && c.kind == Campaign.Kind.TRAIN ? "minecraft:white_banner" : "";
        if (c.status == Campaign.Status.MARCHING) {
            n.currentTask = c.kind == Campaign.Kind.TRAIN ? (leader ? "Levando a tropa para treinar" : "Indo treinar com " + (lead == null ? "a tropa" : lead.name))
                    : "Indo para (" + c.target.x() + ", " + c.target.z() + ") — ordem do rei";
            return new NpcScheduler.Intent(NpcActivity.MARCH, at, 1);
        }
        if (training) {
            n.currentTask = leader ? "Instruindo a tropa" : "Treinando com " + (lead == null ? "a tropa" : lead.name);
            return new NpcScheduler.Intent(NpcActivity.TRAIN, at, 0.5);
        }
        n.currentTask = "Esperando no lugar combinado (" + Math.max(0, (c.holdUntil - core.tick()) / 20) + " s)";
        return new NpcScheduler.Intent(NpcActivity.GUARD, at, 1);
    }

    /** Formação: o líder no ponto; os outros em fileiras de 4, 3 blocos à frente. No treino, a fileira avança e recua a cada 10 s. */
    private Pos spot(Campaign c, Npc n, boolean drilling) {
        if (n.id.equals(c.commanderId)) return c.target;
        int i = 0;
        for (UUID id : c.members) {
            if (id.equals(n.id)) break;
            if (!id.equals(c.commanderId)) i++;
        }
        int row = i / 4, col = i % 4;
        int step = drilling && (core.tick() / 200) % 2 == 1 ? 2 : 0;
        return c.target.offset((col - 2) * 2 + 1, 0, 3 + row * 2 + step);
    }

    /** Arsenal: militares sem espada pegam uma do estoque do reino (as do ferreiro). */
    public void armFromArsenal(Kingdom k) {
        for (Npc n : core.citizens(k.id)) {
            if (k.get(ResourceType.WEAPONS) < 1) return;
            if (n.profession.isMilitary() && n.isFree() && n.equipped.isEmpty()) {
                k.add(ResourceType.WEAPONS, -1);
                n.equipped = "iron_sword";
            }
        }
    }

    // ================================================================== ordens extremas

    /** Quem obedece e quem se recusa. Lealdade, agressividade e disciplina empurram para obedecer; honestidade e amizade, para recusar. */
    public record Obedience(List<Npc> obey, List<Npc> refuse) {
        public boolean carried() {
            return !obey.isEmpty() && obey.size() >= refuse.size();
        }
    }

    public Obedience obedience(Kingdom k, List<Npc> executors, List<Npc> victims, boolean ownPeople, double leniency) {
        List<Npc> obey = new ArrayList<>(), refuse = new ArrayList<>();
        Random rng = core.rng();
        for (Npc n : executors) {
            double s = n.loyalty * 0.5 + n.trait(Trait.AGGRESSION) * 0.3 + n.trait(Trait.DISCIPLINE) * 0.3 - n.trait(Trait.HONESTY) * 0.35
                    + (ownPeople ? -15 : 15) + k.infamy * 0.15 + leniency + rng.nextDouble() * 20;
            for (Npc v : victims) {
                Relation r = n.relations.get(v.id);
                if (r != null && r.affection > 60) {
                    s -= 20; // não mata/acorrenta um amigo
                    break;
                }
            }
            (s >= 40 ? obey : refuse).add(n);
        }
        return new Obedience(obey, refuse);
    }

    /** Alvos de uma ordem: "vila"/"todos" (civis do reino), "cativos", "escravos" ou uma pessoa pelo nome. */
    public List<Npc> targets(Kingdom k, String target) {
        String t = Text.norm(target == null ? "" : target);
        List<Npc> out = new ArrayList<>();
        boolean village = t.matches(".*\\b(vila|aldeia|todos|todo mundo|povo|civis|moradores|cidade|reino)\\b.*");
        boolean captives = t.matches(".*\\b(cativ\\w*|prisioneir\\w*|pres[oa]s?|capturad\\w*)\\b.*");
        boolean slaves = t.matches(".*\\b(escrav\\w*|servos?)\\b.*");
        if (village || captives || slaves) {
            for (Npc n : core.citizens(k.id)) {
                if (n.office == Office.KING) continue;
                if (captives && n.freedom == Freedom.CAPTIVE || slaves && n.freedom == Freedom.ENSLAVED
                        || village && !n.profession.isMilitary() && !atWar(n)) out.add(n);
            }
            return out;
        }
        Npc n = core.findNpc(k.id, target);
        if (n != null) out.add(n);
        return out;
    }

    /** Quem cumpre: guardas e soldados em casa, que não são alvo. */
    public List<Npc> executors(Kingdom k, List<Npc> victims) {
        List<Npc> out = new ArrayList<>();
        for (Npc n : available(k, true)) if (!victims.contains(n)) out.add(n);
        return out;
    }

    public String describePurge(Kingdom k, List<Npc> victims) {
        long own = victims.stream().filter(v -> v.isFree()).count();
        return "Isso matará " + victims.size() + " pessoa(s)" + (victims.size() <= 6 ? " (" + names(victims) + ")" : "")
                + (own > 0 ? ", " + own + " delas súditos livres" : "") + ". Cai a legitimidade, a estabilidade e a moral; os vizinhos vão saber.";
    }

    /** Massacre/execução. Retorna a mensagem; se os guardas se recusarem, ninguém morre (motim). */
    public String purge(Kingdom k, List<Npc> victims) {
        List<Npc> execs = executors(k, victims);
        boolean ownPeople = victims.stream().anyMatch(v -> v.isFree() && v.originKingdomId == null);
        double leniency = victims.size() == 1 ? 10 : 0;
        Npc cmd = commander(k, null);
        if (cmd != null && cmd.office != Office.NONE && execs.contains(cmd)) leniency += 10; // o comandante dá o exemplo
        Obedience ob = obedience(k, execs, victims, ownPeople, leniency);
        if (!ob.carried()) return mutiny(k, ob, "matar " + (victims.size() == 1 ? victims.get(0).name : victims.size() + " pessoas"));
        int own = 0, held = 0;
        for (Npc v : victims) {
            if (v.isFree()) own++;
            else held++;
            v.alive = false;
            v.campaignId = null;
        }
        for (Npc n : ob.obey()) {
            n.remember(core.tick(), "Cumpri a ordem do rei e matei " + (victims.size() == 1 ? victims.get(0).name : victims.size() + " pessoas") + ".", 90, null, "massacre");
            n.traits.merge(Trait.AGGRESSION, 5, (a, b) -> Math.min(100, a + b));
        }
        String deserted = desert(k, ob.refuse(), "se recusou a matar e fugiu do reino");
        Kingdom origin = null;
        for (Npc v : victims) if (v.originKingdomId != null) origin = core.kingdom(v.originKingdomId);
        atrocity(k, victims.size(), own, held, origin, victims.size() == 1 ? "a execução de " + victims.get(0).name : "o massacre de " + victims.size() + " pessoas");
        for (Npc w : core.citizens(k.id)) {
            if (ob.obey().contains(w)) continue;
            w.loyalty = Text.clamp(w.loyalty - (own > 0 ? 15 : 3), 0, 100);
            w.remember(core.tick(), "O rei mandou matar " + (victims.size() == 1 ? victims.get(0).name : victims.size() + " pessoas") + ". Tenho medo.", 85, null, "massacre", "medo");
        }
        return (victims.size() == 1 ? victims.get(0).name + " foi executado" : victims.size() + " pessoa(s) foram mortas")
                + " por " + names(ob.obey()) + "." + deserted
                + String.format(Locale.ROOT, " Legitimidade %.0f · estabilidade %.0f · infâmia %.0f.", k.legitimacy, k.stability, k.infamy);
    }

    private String mutiny(Kingdom k, Obedience ob, String what) {
        k.stability = Text.clamp(k.stability - 8, 0, 100);
        k.legitimacy = Text.clamp(k.legitimacy - 10, 0, 100);
        for (Npc n : ob.refuse()) {
            n.loyalty = Text.clamp(n.loyalty - 10, 0, 100);
            n.remember(core.tick(), "Recusei a ordem do rei de " + what + ".", 85, null, "motim");
        }
        String refused = ob.refuse().size() == 1 ? " se recusou a " : " se recusaram a ";
        String would = ob.obey().size() == 1 ? " obedeceria" : " obedeceriam";
        core.bus().publish(core.tick(), EventType.MUTINY, GameEvent.Severity.DANGER, k.id, null,
                "Motim: " + names(ob.refuse()) + refused + what + (ob.obey().isEmpty() ? "" : " (só " + names(ob.obey()) + would + ")") + ".");
        return "MOTIM: " + names(ob.refuse()) + refused + what + (ob.obey().isEmpty() ? "" : "; só " + names(ob.obey()) + would)
                + ". Ninguém morreu. Lealdade baixa ou gente honesta demais: guardas mais leais (ou mais cruéis) cumpririam.";
    }

    private String desert(Kingdom k, List<Npc> refusers, String why) {
        if (refusers.isEmpty()) return "";
        for (Npc n : refusers) {
            n.alive = false;
            n.campaignId = null;
            core.bus().publish(core.tick(), EventType.DESERTION, GameEvent.Severity.WARN, k.id, n.id, n.name + " " + why + ".");
        }
        return " Desertaram: " + names(refusers) + ".";
    }

    /** Consequências de matar: o reino e os vizinhos reagem (via evento ATROCITY). */
    private void atrocity(Kingdom k, int dead, int own, int held, Kingdom origin, String what) {
        k.legitimacy = Text.clamp(k.legitimacy - Math.min(60, 4 + 3 * own + 0.5 * held), 0, 100);
        k.stability = Text.clamp(k.stability - Math.min(60, 6 + 3 * own + 0.5 * held), 0, 100);
        k.morale = Text.clamp(k.morale - Math.min(50, 5 + 2 * own), 0, 100);
        k.infamy = Text.clamp(k.infamy + Math.min(100, 5 + 3 * own + 2 * held), 0, 100);
        k.honor = Text.clamp(k.honor - Math.min(40, 2 * own + held), 0, 100);
        Map<String, String> data = new HashMap<>();
        data.put("dead", String.valueOf(dead));
        data.put("own", String.valueOf(own));
        if (origin != null) data.put("origin", origin.id.toString());
        core.bus().publish(core.tick(), EventType.ATROCITY, GameEvent.Severity.DANGER, k.id, null,
                "Por ordem de " + k.rulerName + ": " + what + " em " + k.name + ".", data);
        core.chronicle(k.rulerName + " ordenou " + what + " em " + k.name + ".");
    }

    /** Escravizar: cativos (sem resistência) ou súditos (os guardas podem se recusar). */
    public String enslave(Kingdom k, List<Npc> victims, Profession work) {
        List<Npc> execs = executors(k, victims);
        boolean ownPeople = victims.stream().anyMatch(v -> v.isFree());
        Obedience ob = ownPeople ? obedience(k, execs, victims, true, 15) : new Obedience(execs, List.of());
        if (!ob.carried()) return mutiny(k, ob, "acorrentar " + (victims.size() == 1 ? victims.get(0).name : victims.size() + " súditos"));
        int own = 0;
        for (Npc v : victims) {
            if (v.isFree()) own++;
            v.freedom = Freedom.ENSLAVED;
            v.profession = work;
            v.office = Office.NONE;
            v.loyalty = 5;
            v.dutyChainId = null;
            v.remember(core.tick(), "Fui escravizado por ordem do rei. Trabalho à força como " + work.display.toLowerCase() + ".", 95, null, "escravidão");
        }
        int held = victims.size() - own;
        k.infamy = Text.clamp(k.infamy + 2 + 3 * own + held, 0, 100);
        k.legitimacy = Text.clamp(k.legitimacy - (own > 0 ? 3 + 2 * own : 1), 0, 100);
        if (own > 0) {
            k.stability = Text.clamp(k.stability - (4 + 2 * own), 0, 100);
            for (Npc w : core.citizens(k.id))
                if (w.isFree()) {
                    w.loyalty = Text.clamp(w.loyalty - 8, 0, 100);
                    w.remember(core.tick(), "O rei escravizou gente nossa: " + names(victims) + ".", 75, null, "escravidão", "medo");
                }
        }
        Map<String, String> data = new HashMap<>();
        for (Npc v : victims) if (v.originKingdomId != null) data.put("origin", v.originKingdomId.toString());
        data.put("count", String.valueOf(victims.size()));
        data.put("own", String.valueOf(own));
        core.bus().publish(core.tick(), EventType.ENSLAVED, GameEvent.Severity.WARN, k.id, null,
                victims.size() + " pessoa(s) escravizada(s) em " + k.name + " (trabalho forçado: " + work.display.toLowerCase() + ").", data);
        int guards = available(k, true).size();
        return names(victims) + " agora trabalham à força como " + work.display.toLowerCase() + " (rendem 60%, comem pouco)."
                + (guards * 3 < enslavedCount(k) ? " ⚠ Poucos guardas para vigiar " + enslavedCount(k) + " escravizados: vão fugir ou se revoltar." : "")
                + String.format(Locale.ROOT, " Infâmia %.0f.", k.infamy);
    }

    public String free(Kingdom k, List<Npc> people, boolean home) {
        int returned = 0;
        for (Npc v : people) {
            boolean wasSlave = v.freedom == Freedom.ENSLAVED;
            Kingdom origin = v.originKingdomId == null ? null : core.kingdom(v.originKingdomId);
            v.freedom = Freedom.FREE;
            if (home && origin != null && core.population(origin.id) > 0) {
                v.kingdomId = origin.id;
                v.loyalty = 50;
                v.campaignId = null;
                v.homeId = null;
                v.remember(core.tick(), "Fui libertado por " + k.rulerName + " e voltei para " + origin.name + ".", 85, null, "liberdade");
                var att = core.diplomacy().attitude(origin.id, k.id);
                att.trust = Text.clamp(att.trust + 5, 0, 100);
                att.hostility = Text.clamp(att.hostility - 5, 0, 100);
                returned++;
            } else {
                v.loyalty = wasSlave && v.originKingdomId == null ? 45 : 30;
                v.remember(core.tick(), "Fui libertado por " + k.rulerName + ". Agora sou livre em " + k.name + ".", 85, null, "liberdade");
            }
        }
        k.legitimacy = Text.clamp(k.legitimacy + Math.min(10, 2 + people.size() * 0.5), 0, 100);
        k.infamy = Text.clamp(k.infamy - 2.0 * people.size(), 0, 100);
        core.bus().publish(core.tick(), EventType.FREED, GameEvent.Severity.GOOD, k.id, null,
                people.size() + " pessoa(s) libertada(s) em " + k.name + (returned > 0 ? " (" + returned + " voltaram para casa)" : "") + ".");
        return names(people) + " agora são livres" + (returned > 0 ? "; " + returned + " voltaram para a terra natal" : " e ficam no reino") + ".";
    }

    public int enslavedCount(Kingdom k) {
        int n = 0;
        for (Npc x : core.citizens(k.id)) if (x.freedom == Freedom.ENSLAVED) n++;
        return n;
    }

    public int captiveCount(Kingdom k) {
        int n = 0;
        for (Npc x : core.citizens(k.id)) if (x.freedom == Freedom.CAPTIVE) n++;
        return n;
    }

    /** Tick estratégico: fugas e revoltas de quem está preso/escravizado, desertores com fome, a infâmia esfria. */
    public void strategicTick() {
        Random rng = core.rng();
        for (Kingdom k : core.state().kingdoms.values()) {
            k.infamy = Text.clamp(k.infamy - 0.3, 0, 100);
            armFromArsenal(k);
            List<Npc> held = new ArrayList<>();
            for (Npc n : core.citizens(k.id)) if (!n.isFree()) held.add(n);
            if (!held.isEmpty()) {
                List<Npc> guards = available(k, true);
                double watch = Math.min(1.0, guards.size() * 3.0 / held.size());
                long slaves = held.stream().filter(n -> n.freedom == Freedom.ENSLAVED).count();
                if (slaves >= 4 && slaves > guards.size() * 3L && rng.nextDouble() < 0.2) {
                    revolt(k, held, guards);
                    continue;
                }
                for (Npc n : held) {
                    double chance = (n.freedom == Freedom.ENSLAVED ? 0.06 : 0.03) * (1 - watch);
                    if (rng.nextDouble() < chance) {
                        n.alive = false;
                        core.bus().publish(core.tick(), EventType.SLAVE_ESCAPED, GameEvent.Severity.WARN, k.id, n.id,
                                n.name + " (" + n.freedom.display.toLowerCase() + ") fugiu: há poucos guardas vigiando.");
                    }
                }
            }
            if (k.famine)
                for (Campaign c : campaigns(k.id)) {
                    if (!c.live()) continue;
                    for (Npc n : members(c))
                        if (rng.nextDouble() < 0.05) {
                            n.alive = false;
                            core.bus().publish(core.tick(), EventType.DESERTION, GameEvent.Severity.WARN, k.id, n.id,
                                    n.name + " desertou da tropa #" + c.number + ": não chega comida ao front.");
                        }
                }
        }
    }

    private void revolt(Kingdom k, List<Npc> held, List<Npc> guards) {
        Random rng = core.rng();
        String guardDead = "";
        if (!guards.isEmpty()) {
            Npc g = guards.get(rng.nextInt(guards.size()));
            die(g, "foi morto na revolta dos escravizados");
            guardDead = " " + g.name + " morreu.";
        }
        int escaped = 0;
        for (Npc n : held)
            if (rng.nextBoolean()) {
                n.alive = false;
                escaped++;
            }
        k.stability = Text.clamp(k.stability - 15, 0, 100);
        core.bus().publish(core.tick(), EventType.SLAVE_REVOLT, GameEvent.Severity.DANGER, k.id, null,
                "Revolta dos escravizados em " + k.name + "! " + escaped + " fugiram." + guardDead + " Vigilância insuficiente (1 guarda para cada 3).");
        core.chronicle("Os escravizados de " + k.name + " se revoltaram.");
    }

    // ================================================================== texto

    private String where(Kingdom owner, Pos target) {
        return owner != null ? owner.name + " (" + target.x() + ", " + target.z() + ")" : "terra livre (" + target.x() + ", " + target.z() + ")";
    }

    public static String names(List<Npc> people) {
        if (people.isEmpty()) return "ninguém";
        List<String> n = new ArrayList<>();
        for (int i = 0; i < people.size() && i < 8; i++) n.add(people.get(i).name);
        return String.join(", ", n) + (people.size() > 8 ? " e mais " + (people.size() - 8) : "");
    }

    /** Resumo para o conselho/IA e para o Manager. */
    public String summary(Kingdom k) {
        StringBuilder sb = new StringBuilder();
        long live = campaigns(k.id).stream().filter(Campaign::live).count();
        sb.append("Militares ").append(core.military(k.id)).append(" (em campanha: ")
                .append(core.citizens(k.id).stream().filter(this::atWar).count()).append(")");
        if (live > 0) sb.append(" · tropas fora: ").append(live);
        int cap = captiveCount(k), sl = enslavedCount(k);
        if (cap > 0) sb.append(" · cativos ").append(cap);
        if (sl > 0) sb.append(" · escravizados ").append(sl);
        sb.append(" · terra ").append(core.state().territory.countOwned(k.id)).append('/').append(claimLimit(k)).append(" células");
        if (k.infamy >= 1) sb.append(" · infâmia ").append((int) k.infamy);
        return sb.toString();
    }
}
