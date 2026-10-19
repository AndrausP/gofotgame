package com.kingdomsai.core.skill;

import com.kingdomsai.core.npc.Npc;

import java.util.List;
import java.util.Map;

/**
 * Regras da mochila de um súdito, iguais às do Minecraft: 27 espaços, pilhas de 64 (ferramentas, armas e armaduras ocupam
 * um espaço cada; pérolas/ovos 16), ferramentas com durabilidade por material, comida que mata a fome.
 * O estado (itens e desgaste) mora no Core ({@link Npc#bag}, {@link Npc#wear}); a entidade só mostra.
 */
public final class Inventory {
    public static final int SLOTS = 27;
    /** Espaços que ele mantém livres para o que vai coletar antes de voltar ao armazém. */
    public static final int RESERVE_SLOTS = 2;

    private Inventory() {}

    public static int maxStack(String id) {
        String n = id.startsWith("minecraft:") ? id.substring(10) : id;
        if (isTool(id) || isArmor(id) || n.endsWith("_bucket") || n.equals("bucket") || n.equals("shield") || n.equals("bow")
                || n.equals("crossbow") || n.endsWith("_boat") || n.equals("saddle") || n.equals("writable_book") || n.equals("written_book")) return 1;
        if (n.equals("egg") || n.equals("ender_pearl") || n.equals("snowball") || n.endsWith("_sign") || n.equals("honey_bottle")) return 16;
        return 64;
    }

    public static int slotsUsed(Map<String, Integer> bag) {
        int s = 0;
        for (var e : bag.entrySet()) if (e.getValue() > 0) s += (e.getValue() + maxStack(e.getKey()) - 1) / maxStack(e.getKey());
        return s;
    }

    public static int freeSlots(Npc n) {
        return SLOTS - slotsUsed(n.bag);
    }

    /** Cheia para quem está coletando: sobrou só a reserva. */
    public static boolean full(Npc n) {
        return freeSlots(n) <= RESERVE_SLOTS - 1;
    }

    /** Quantos itens deste tipo ainda cabem (preenche pilhas abertas e espaços livres). */
    public static int room(Npc n, String id) {
        int stack = maxStack(id), have = n.bag.getOrDefault(id, 0);
        int openInStack = have % stack == 0 ? 0 : stack - have % stack;
        return openInStack + Math.max(0, freeSlots(n)) * stack;
    }

    // ------------------------------------------------------------------ ferramentas

    public static boolean isTool(String id) {
        return id.matches(".*_(pickaxe|axe|shovel|hoe|sword)$") || id.endsWith("shears") || id.endsWith("fishing_rod") || id.endsWith("flint_and_steel");
    }

    public static boolean isArmor(String id) {
        return id.matches(".*_(helmet|chestplate|leggings|boots)$");
    }

    /** "pickaxe", "axe", "shovel", "hoe", "sword" ou null. */
    public static String toolType(String id) {
        for (String t : List.of("pickaxe", "shovel", "hoe", "sword", "axe")) if (id.endsWith("_" + t)) return t;
        return null;
    }

    /** Durabilidade do Minecraft por material (madeira 59, pedra 131, ferro 250, ouro 32, diamante 1561, netherita 2031). */
    public static int durability(String id) {
        String n = id.startsWith("minecraft:") ? id.substring(10) : id;
        if (n.equals("shield")) return 336;
        if (n.equals("shears")) return 238;
        if (n.startsWith("wooden_")) return 59;
        if (n.startsWith("stone_")) return 131;
        if (n.startsWith("iron_")) return 250;
        if (n.startsWith("golden_")) return 32;
        if (n.startsWith("diamond_")) return 1561;
        if (n.startsWith("netherite_")) return 2031;
        return 100;
    }

    /** Melhor material primeiro: quebra mais rápido e dura mais. */
    public static int tier(String id) {
        String n = id.startsWith("minecraft:") ? id.substring(10) : id;
        if (n.startsWith("netherite_")) return 6;
        if (n.startsWith("diamond_")) return 5;
        if (n.startsWith("iron_")) return 4;
        if (n.startsWith("stone_")) return 3;
        if (n.startsWith("golden_")) return 2;
        if (n.startsWith("wooden_")) return 1;
        return 0;
    }

    /** A ferramenta deste tipo que ele usa agora (a melhor da mochila) ou null. */
    public static String bestTool(Npc n, String type) {
        if (type == null) return null;
        String best = null;
        for (var e : n.bag.entrySet())
            if (e.getValue() > 0 && type.equals(toolType(e.getKey())) && (best == null || tier(e.getKey()) > tier(best))) best = e.getKey();
        return best;
    }

    public static int count(Npc n, String type) {
        int c = 0;
        for (var e : n.bag.entrySet()) if (type.equals(toolType(e.getKey()))) c += e.getValue();
        return c;
    }

    /** Velocidade por material (madeira 2, pedra 4, ferro 6, diamante 8 — como no jogo) relativa ao ferro. */
    public static double speedFactor(String toolId) {
        return switch (tier(toolId)) {
            case 6 -> 1.5;
            case 5 -> 1.33;
            case 4 -> 1.0;
            case 3 -> 0.67;
            case 2 -> 2.0;
            case 1 -> 0.33;
            default -> 0.2;
        };
    }

    /**
     * Gasta 1 de durabilidade. Se a ferramenta quebrar, some da mochila e o desgaste zera (a próxima é nova).
     *
     * @return true se quebrou agora
     */
    public static boolean wear(Npc n, String toolId) {
        int d = n.wear.merge(toolId, 1, Integer::sum);
        if (d < durability(toolId)) return false;
        n.wear.remove(toolId);
        n.bag.merge(toolId, -1, Integer::sum);
        n.bag.values().removeIf(v -> v <= 0);
        return true;
    }

    /** % de vida da ferramenta em uso (100 = nova). */
    public static int health(Npc n, String toolId) {
        return Math.max(0, 100 - 100 * n.wear.getOrDefault(toolId, 0) / durability(toolId));
    }

    // ------------------------------------------------------------------ comida

    /** Quanto a comida mata a fome (escala 0-100 da fome do súdito). */
    public static int nutrition(String id) {
        String n = id.startsWith("minecraft:") ? id.substring(10) : id;
        return switch (n) {
            case "cooked_beef", "cooked_porkchop", "pumpkin_pie", "golden_carrot" -> 40;
            case "cooked_mutton", "cooked_chicken", "cooked_salmon", "baked_potato", "rabbit_stew", "mushroom_stew", "beetroot_soup" -> 30;
            case "bread", "cooked_cod", "cooked_rabbit" -> 25;
            case "apple", "carrot", "melon_slice", "sweet_berries", "beetroot", "potato", "cookie" -> 10;
            default -> 0;
        };
    }

    public static String bestFood(Npc n) {
        String best = null;
        for (var e : n.bag.entrySet())
            if (e.getValue() > 0 && nutrition(e.getKey()) > 0 && (best == null || nutrition(e.getKey()) > nutrition(best))) best = e.getKey();
        return best;
    }

    public static int foodCount(Npc n) {
        int c = 0;
        for (var e : n.bag.entrySet()) if (nutrition(e.getKey()) > 0) c += e.getValue();
        return c;
    }

    /** Come uma porção da mochila se estiver com fome. @return o que comeu, ou null. */
    public static String eat(Npc n) {
        String f = bestFood(n);
        if (f == null) return null;
        n.bag.merge(f, -1, Integer::sum);
        n.bag.values().removeIf(v -> v <= 0);
        n.hunger = Math.min(100, n.hunger + nutrition(f));
        return f;
    }
}
