package com.kingdomsai.core.economy;

import com.kingdomsai.core.KingdomsCore;
import com.kingdomsai.core.common.Text;
import com.kingdomsai.core.event.EventType;
import com.kingdomsai.core.event.GameEvent;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.kingdom.ResourceType;
import com.kingdomsai.core.npc.Npc;
import com.kingdomsai.core.npc.Profession;
import com.kingdomsai.core.npc.Trait;

import java.util.EnumMap;
import java.util.Map;

/**
 * Economia por tick econômico: produção por profissão, consumo, impostos e manutenção do exército.
 * Cadeias: Fazenda→Comida→População; Mina→Ferro→Ferreiro→Armas→Exército.
 */
public final class EconomySystem {
    /** Valores de referência para comércio/diplomacia. */
    public static final Map<ResourceType, Double> VALUE = Map.of(
            ResourceType.FOOD, 1.0, ResourceType.WOOD, 1.0, ResourceType.STONE, 1.0,
            ResourceType.IRON, 4.0, ResourceType.GOLD, 2.0, ResourceType.WEAPONS, 8.0);

    public static final double FOOD_PER_CITIZEN = 1.0;

    private final KingdomsCore core;

    public EconomySystem(KingdomsCore core) {
        this.core = core;
    }

    public void tick() {
        for (Kingdom k : core.state().kingdoms.values()) tick(k);
    }

    private void tick(Kingdom k) {
        Map<ResourceType, Double> d = projected(k);
        for (var e : d.entrySet()) k.add(e.getKey(), e.getValue());
        k.lastDelta = d;
        int pop = core.population(k.id);
        double food = k.get(ResourceType.FOOD);
        if (food <= 0.5 && pop > 0) {
            if (!k.famine) {
                k.famine = true;
                core.bus().publish(core.tick(), EventType.FAMINE_STARTED, GameEvent.Severity.DANGER, k.id, null,
                        "Fome em " + k.name + "! Os estoques de comida acabaram.");
                core.chronicle("A fome atingiu " + k.name + ".");
            }
        } else if (k.famine && food > pop * 5) {
            k.famine = false;
            core.bus().publish(core.tick(), EventType.FAMINE_ENDED, GameEvent.Severity.GOOD, k.id, null, "A fome acabou em " + k.name + ".");
        }
        double perTick = d.getOrDefault(ResourceType.FOOD, 0.0);
        if (!k.famine && perTick < 0 && food / -perTick < 30) {
            if (!k.shortageWarned) {
                k.shortageWarned = true;
                core.bus().publish(core.tick(), EventType.FOOD_SHORTAGE, GameEvent.Severity.WARN, k.id, null,
                        "Falta de comida: estoque para ~" + (int) (food / -perTick * core.config().economicTickSeconds / 60) + " min.");
            }
        } else if (perTick >= 0) k.shortageWarned = false;
        smithy(k);
        // Fome de cada NPC (necessidade) acompanha o estoque.
        // as refeições (e a fome de quem não come) ficam com a vida dos súditos (LifeSystem)
    }

    /** Reserva mínima de ferramentas no armazém (o ferreiro repõe o que os súditos gastam). */
    public static final Map<String, Integer> TOOL_RESERVE = Map.of("axe", 2, "pickaxe", 2, "hoe", 1, "shovel", 1);

    /**
     * Ferreiro: funde o minério bruto que os mineradores guardaram (1 carvão funde 8) e forja as ferramentas que faltam
     * na reserva do armazém — de ferro se houver barras, senão de pedra. Tudo sai e entra pelo estoque (e aparece nos baús).
     */
    private void smithy(Kingdom k) {
        int smiths = 0;
        for (Npc n : core.citizens(k.id)) if (n.profession == Profession.BLACKSMITH && n.isFree() && !n.onDuty) smiths++;
        if (smiths == 0) return;
        double rate = smiths * (core.completedOf(k.id, "smithy") > 0 ? 0.5 : 0.25);
        // 1) fundir
        int raw = k.goods.getOrDefault("minecraft:raw_iron", 0);
        if (raw > 0) {
            k.smeltProgress += rate * 3;
            int smelted = 0;
            while (k.smeltProgress >= 1 && raw > 0) {
                if (k.fuelLeft <= 0) {
                    String fuel = k.goods.getOrDefault("minecraft:coal", 0) > 0 ? "minecraft:coal" : k.goods.getOrDefault("minecraft:charcoal", 0) > 0 ? "minecraft:charcoal" : null;
                    if (fuel == null) break;
                    k.goods.merge(fuel, -1, Integer::sum);
                    k.goods.values().removeIf(v -> v <= 0);
                    k.fuelLeft = 8;
                }
                k.goods.merge("minecraft:raw_iron", -1, Integer::sum);
                k.goods.values().removeIf(v -> v <= 0);
                k.add(ResourceType.IRON, 1);
                k.fuelLeft--;
                k.smeltProgress -= 1;
                raw--;
                smelted++;
            }
            if (raw == 0 || k.fuelLeft <= 0 && k.goods.getOrDefault("minecraft:coal", 0) == 0) k.smeltProgress = Math.min(k.smeltProgress, 1);
            if (smelted > 0 && raw > 0 && k.fuelLeft <= 0 && k.goods.getOrDefault("minecraft:coal", 0) == 0)
                core.bus().publish(core.tick(), EventType.SMITHY, GameEvent.Severity.WARN, k.id, null,
                        "A forja de " + k.name + " ficou sem carvão: há " + raw + " minério(s) de ferro esperando.");
        }
        // 2) forjar o que falta na reserva
        String type = null;
        for (var e : TOOL_RESERVE.entrySet()) {
            int have = 0;
            for (var g : k.goods.entrySet()) if (e.getKey().equals(com.kingdomsai.core.skill.Inventory.toolType(g.getKey()))) have += g.getValue();
            if (have < e.getValue()) {
                type = e.getKey();
                break;
            }
        }
        if (type == null) {
            k.forgeProgress = 0;
            return;
        }
        int ingots = switch (type) {
            case "hoe" -> 2;
            case "shovel" -> 1;
            default -> 3;
        };
        boolean iron = k.get(ResourceType.IRON) >= ingots;
        if (!iron && (k.get(ResourceType.STONE) < ingots || k.get(ResourceType.WOOD) < 2)) return;
        k.forgeProgress += rate;
        if (k.forgeProgress < 1) return;
        k.forgeProgress -= 1;
        if (iron) k.add(ResourceType.IRON, -ingots);
        else k.add(ResourceType.STONE, -ingots);
        k.add(ResourceType.WOOD, -2); // cabo (2 gravetos)
        String item = "minecraft:" + (iron ? "iron_" : "stone_") + type;
        k.goods.merge(item, 1, Integer::sum);
        core.bus().publish(core.tick(), EventType.SMITHY, GameEvent.Severity.INFO, k.id, null,
                "O ferreiro de " + k.name + " fez " + com.kingdomsai.core.skill.ItemNames.display(item) + " (reserva no armazém).");
    }

    /** Variação por tick econômico — usada também pelo conselheiro e pelo HUD. */
    public Map<ResourceType, Double> projected(Kingdom k) {
        Map<ResourceType, Double> d = new EnumMap<>(ResourceType.class);
        for (ResourceType r : ResourceType.values()) d.put(r, 0.0);
        int farms = core.completedOf(k.id, "farm");
        int smithies = core.completedOf(k.id, "smithy");
        int farmers = 0;
        double moraleFactor = 0.6 + k.morale / 250.0; // 0.6..1.0
        for (Npc n : core.citizens(k.id)) {
            // Quem cumpre uma cadeia produz de verdade (itens no armazém); não conta de novo aqui. Comer, come.
            if (n.onDuty) {
                add(d, ResourceType.FOOD, -FOOD_PER_CITIZEN);
                continue;
            }
            // Cativo não trabalha e come pouco; escravizado trabalha à força (rende 60%) e come menos.
            if (n.freedom == com.kingdomsai.core.npc.Freedom.CAPTIVE) {
                add(d, ResourceType.FOOD, -0.6);
                continue;
            }
            boolean enslaved = n.freedom == com.kingdomsai.core.npc.Freedom.ENSLAVED;
            double skill = 0.8 + n.trait(Trait.DISCIPLINE) / 250.0;
            double f = skill * moraleFactor * (n.hunger < 20 ? 0.5 : 1.0) * (enslaved ? 0.6 : 1.0) * com.kingdomsai.core.life.LifeSystem.workFactor(n);
            switch (n.profession) {
                case FARMER -> {
                    farmers++;
                    double bonus = farmers <= farms * 3 ? 1.6 : 1.0;
                    add(d, ResourceType.FOOD, 3.0 * f * bonus);
                }
                case LUMBERJACK -> add(d, ResourceType.WOOD, 2.2 * f);
                case MINER -> {
                    add(d, ResourceType.STONE, 2.0 * f);
                    add(d, ResourceType.IRON, 0.45 * f);
                }
                case BLACKSMITH -> {
                    double rate = (smithies > 0 ? 0.5 : 0.25) * f;
                    if (k.get(ResourceType.IRON) + d.get(ResourceType.IRON) >= 2 * rate) {
                        add(d, ResourceType.IRON, -2 * rate);
                        add(d, ResourceType.WEAPONS, rate);
                    }
                }
                case MERCHANT -> add(d, ResourceType.GOLD, 1.5 * f * (0.5 + k.personality.commerce / 100.0));
                case PEASANT -> add(d, ResourceType.FOOD, 0.8 * f);
                // Exército não custa ouro: custa comida. Soldado come 2 (3 em campanha: carroças até o front); guarda 1,5.
                case SOLDIER -> add(d, ResourceType.FOOD, -(com.kingdomsai.core.military.MilitarySystem.SOLDIER_FOOD - FOOD_PER_CITIZEN)
                        - (core.warfare().atWar(n) ? com.kingdomsai.core.military.MilitarySystem.CAMPAIGN_EXTRA_FOOD : 0));
                case GUARD -> add(d, ResourceType.FOOD, -(com.kingdomsai.core.military.MilitarySystem.GUARD_FOOD - FOOD_PER_CITIZEN));
                default -> {
                }
            }
            add(d, ResourceType.FOOD, enslaved ? -0.8 : -FOOD_PER_CITIZEN);
        }
        int pop = core.population(k.id);
        add(d, ResourceType.GOLD, pop * k.laws.taxLevel * 0.12);
        return d;
    }

    private static void add(Map<ResourceType, Double> d, ResourceType r, double v) {
        d.merge(r, v, Double::sum);
    }

    /** Quantos ticks econômicos até a comida acabar (Double.POSITIVE_INFINITY se crescendo). */
    public double foodTicksLeft(Kingdom k) {
        double perTick = projected(k).get(ResourceType.FOOD);
        if (perTick >= 0) return Double.POSITIVE_INFINITY;
        return k.get(ResourceType.FOOD) / -perTick;
    }
}
