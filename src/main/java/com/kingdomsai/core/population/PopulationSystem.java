package com.kingdomsai.core.population;

import com.kingdomsai.core.KingdomsCore;
import com.kingdomsai.core.common.Pos;
import com.kingdomsai.core.common.Text;
import com.kingdomsai.core.construction.Building;
import com.kingdomsai.core.event.EventType;
import com.kingdomsai.core.event.GameEvent;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.kingdom.ResourceType;
import com.kingdomsai.core.npc.*;

import java.util.List;
import java.util.Map;

/** Crescimento, migração, estabilidade, moral, relações sociais e promoção automática de NPCs. */
public final class PopulationSystem {
    private final KingdomsCore core;

    public PopulationSystem(KingdomsCore core) {
        this.core = core;
    }

    /** Tick de população (padrão 60s). */
    public void tick() {
        for (Kingdom k : core.state().kingdoms.values()) {
            growth(k);
            social(k);
        }
        promotions();
    }

    /** Tick estratégico (padrão 30s): estabilidade, moral, lealdade. */
    public void strategicTick() {
        for (Kingdom k : core.state().kingdoms.values()) {
            int pop = core.population(k.id);
            int cap = core.housingCapacity(k.id);
            int homeless = Math.max(0, pop - cap);
            double target = 72;
            if (k.famine) target -= 40;
            else if (k.shortageWarned) target -= 12;
            target -= (k.laws.taxLevel - 2) * 9;
            target -= homeless * 2.5;
            target += (k.morale - 60) / 5.0;
            target += (k.legitimacy - 70) / 4.0;
            if (k.laws.conscription) target -= 6;
            target -= k.infamy / 4.0; // medo do rei cruel
            double before = k.stability;
            k.stability = Text.clamp(k.stability + (target - k.stability) * 0.15, 0, 100);
            double moraleTarget = 70 + (k.famine ? -35 : 0) + (k.get(ResourceType.FOOD) > pop * 20 ? 8 : 0)
                    - (k.laws.taxLevel >= 3 ? 10 : 0) + core.completedOf(k.id, "town_hall") * 5;
            boolean atWar = core.diplomacy().atWar(k.id);
            if (atWar) moraleTarget -= 12;
            k.morale = Text.clamp(k.morale + (moraleTarget - k.morale) * 0.15, 0, 100);
            if (before >= 35 && k.stability < 35)
                core.bus().publish(core.tick(), EventType.STABILITY_LOW, GameEvent.Severity.DANGER, k.id, null,
                        "A estabilidade de " + k.name + " está perigosamente baixa (" + (int) k.stability + ").");
            for (Npc n : core.citizens(k.id)) {
                double lt = 35 + n.trait(Trait.LOYALTY) * 0.35 + (k.stability - 50) * 0.4 - (k.laws.taxLevel - 2) * 5 + (n.mood - 60) * 0.2;
                n.loyalty = Text.clamp((int) Math.round(n.loyalty + (lt - n.loyalty) * 0.05), 0, 100);
            }
        }
    }

    private void growth(Kingdom k) {
        int pop = core.population(k.id);
        int cap = core.housingCapacity(k.id);
        if (pop >= cap) {
            if (!k.housingWarned && pop > 0) {
                k.housingWarned = true;
                core.bus().publish(core.tick(), EventType.HOUSING_FULL, GameEvent.Severity.WARN, k.id, null,
                        "Não há casas para novos moradores em " + k.name + " (" + pop + "/" + cap + ").");
            }
        } else k.housingWarned = false;

        if (k.famine && pop > 2 && core.rng().nextDouble() < 0.25) {
            List<Npc> cs = core.citizens(k.id);
            cs.sort((a, b) -> Integer.compare(a.loyalty, b.loyalty));
            Npc leaving = cs.get(0);
            if (leaving.office == Office.NONE) {
                leaving.alive = false;
                core.bus().publish(core.tick(), EventType.NPC_LEFT, GameEvent.Severity.DANGER, k.id, leaving.id,
                        leaving.name + " abandonou " + k.name + " por causa da fome.");
            }
            return;
        }
        boolean canGrow = pop < cap && pop < core.config().maxNpcsPerKingdom && k.stability > 40
                && k.get(ResourceType.FOOD) > Math.max(30, pop * 8) && k.laws.openMigration && k.infamy < 60; // ninguém muda para o reino do tirano
        if (canGrow && core.rng().nextDouble() < 0.55) {
            Pos spawn = k.center;
            Building hall = core.buildings(k.id).stream().filter(b -> b.isComplete() && b.blueprintId.equals("town_hall"))
                    .findFirst().orElse(null);
            if (hall != null && hall.origin.y() != Integer.MIN_VALUE) spawn = hall.entrance();
            if (k.markers.containsKey(com.kingdomsai.core.kingdom.Marker.SPAWN)) spawn = k.spawnPoint(); // o rei marcou o spawn
            Npc n = core.createNpc(k, Profession.PEASANT, spawn);
            core.bus().publish(core.tick(), EventType.POPULATION_GROWTH, GameEvent.Severity.GOOD, k.id, n.id,
                    n.name + " chegou para morar em " + k.name + ".", Map.of("npc", n.id.toString()));
        }
    }

    private void social(Kingdom k) {
        List<Npc> cs = core.citizens(k.id);
        if (cs.size() < 2) return;
        for (int i = 0; i < Math.min(4, cs.size() / 2); i++) {
            Npc a = cs.get(core.rng().nextInt(cs.size()));
            Npc b = cs.get(core.rng().nextInt(cs.size()));
            if (a == b) continue;
            double soc = (a.trait(Trait.SOCIABILITY) + b.trait(Trait.SOCIABILITY)) / 200.0;
            boolean sameJob = a.profession == b.profession;
            int aff = (int) Math.round(soc * 4 + (sameJob ? 2 : 0));
            int riv = 0;
            if (a.trait(Trait.AGGRESSION) > 70 && core.rng().nextInt(4) == 0) riv = 6;
            if (sameJob && a.trait(Trait.AMBITION) > 70 && b.fame > a.fame) riv += 4; // inveja
            a.relationTo(b.id).adjust(1, 0, 0, riv, aff - riv / 2);
            b.relationTo(a.id).adjust(1, 0, 0, riv / 2, aff - riv / 2);
        }
    }

    /** NPC comum pode virar importante: cargo, fama ou memórias marcantes. */
    private void promotions() {
        for (Npc n : core.allAlive()) {
            IntelligenceLevel before = n.level;
            if (n.office != Office.NONE) n.level = n.level.atLeast(IntelligenceLevel.IMPORTANT);
            long strong = n.memories.stream().filter(m -> m.importance() >= 70).count();
            if (n.fame >= 25 || strong >= 2) n.level = n.level.atLeast(IntelligenceLevel.IMPORTANT);
            else if (!n.memories.isEmpty()) n.level = n.level.atLeast(IntelligenceLevel.CONTEXTUAL);
            if (before.ordinal() < IntelligenceLevel.IMPORTANT.ordinal() && n.level.ordinal() >= IntelligenceLevel.IMPORTANT.ordinal()) {
                core.bus().publish(core.tick(), EventType.NPC_BECAME_IMPORTANT, GameEvent.Severity.INFO, n.kingdomId, n.id,
                        n.displayName() + " tornou-se uma figura conhecida no reino.");
            }
        }
    }
}
