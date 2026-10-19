package com.kingdomsai.core.kingdom;

import java.util.Random;

/** Dois reinos na mesma situação reagem diferente por causa da personalidade. Valores 0..100. */
public final class KingdomPersonality {
    public int militarism;
    public int diplomacy;
    public int commerce;
    public int religiosity;
    public int expansionism;
    public int tolerance;

    public KingdomPersonality() {}

    public static KingdomPersonality random(Random r) {
        KingdomPersonality p = new KingdomPersonality();
        p.militarism = 15 + r.nextInt(80);
        p.diplomacy = 15 + r.nextInt(80);
        p.commerce = 15 + r.nextInt(80);
        p.religiosity = 10 + r.nextInt(85);
        p.expansionism = 15 + r.nextInt(80);
        p.tolerance = 10 + r.nextInt(85);
        return p;
    }

    public static KingdomPersonality balanced() {
        KingdomPersonality p = new KingdomPersonality();
        p.militarism = p.diplomacy = p.commerce = p.religiosity = p.expansionism = p.tolerance = 50;
        return p;
    }

    public String summary() {
        StringBuilder sb = new StringBuilder();
        if (militarism > 70) sb.append("militarista, ");
        if (diplomacy > 70) sb.append("diplomático, ");
        if (commerce > 70) sb.append("comercial, ");
        if (expansionism > 70) sb.append("expansionista, ");
        if (religiosity > 70) sb.append("religioso, ");
        if (tolerance < 30) sb.append("intolerante, ");
        if (sb.isEmpty()) return "moderado";
        return sb.substring(0, sb.length() - 2);
    }
}
