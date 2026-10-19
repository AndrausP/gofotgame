package com.kingdomsai.core.diplomacy;

import com.kingdomsai.core.common.Text;

import java.util.*;

/** Tipos de dados da diplomacia: estado da relação, atitudes e tratados. */
public final class Diplomacy {
    private Diplomacy() {}

    public enum State {
        PEACE("Paz"), TENSION("Tensão"), HOSTILE("Hostil"), WAR("Guerra"), ARMISTICE("Armistício");
        public final String display;

        State(String display) {
            this.display = display;
        }
    }

    public enum TreatyType {
        NON_AGGRESSION("Pacto de não agressão"),
        TRADE_AGREEMENT("Acordo comercial"),
        DEFENSIVE_ALLIANCE("Aliança defensiva"),
        TRIBUTE("Tributo"),
        OPEN_BORDERS("Fronteiras abertas");
        public final String display;

        TreatyType(String display) {
            this.display = display;
        }

        public static TreatyType parse(String s) {
            String n = Text.norm(s);
            if (n.contains("agress") || n.contains("nap")) return NON_AGGRESSION;
            if (n.contains("comerc") || n.contains("trade")) return TRADE_AGREEMENT;
            if (n.contains("alian") || n.contains("alliance")) return DEFENSIVE_ALLIANCE;
            if (n.contains("tribut")) return TRIBUTE;
            if (n.contains("fronteir") || n.contains("border")) return OPEN_BORDERS;
            for (TreatyType t : values()) if (t.name().equalsIgnoreCase(n)) return t;
            return null;
        }
    }

    /** Estado simétrico entre dois reinos. */
    public static final class Link {
        public UUID a, b;
        public State state = State.PEACE;
        public long stateSince;
        public boolean contact;

        public Link() {}
    }

    /** Atitude de um reino em relação a outro (assimétrica). Valores 0..100. */
    public static final class Attitude {
        public double trust = 40;
        public double fear = 10;
        public double respect = 40;
        public double hostility = 15;
        public double tradeDependence = 0;

        public Attitude() {}

        public String label() {
            if (hostility > 80) return "odeia você";
            if (hostility > 60) return "hostil";
            if (fear > 65) return "teme você";
            if (trust > 70 && hostility < 30) return "amigável";
            if (respect > 70) return "respeita você";
            if (tradeDependence > 50) return "depende do seu comércio";
            return "neutro";
        }
    }

    public static final class Treaty {
        public UUID id;
        public TreatyType type;
        public UUID a, b;
        public long startTick;
        public long expiresTick;
        public int violations;

        public Treaty() {}

        public boolean involves(UUID k) {
            return k.equals(a) || k.equals(b);
        }
    }

    public static String pairKey(UUID x, UUID y) {
        return x.compareTo(y) < 0 ? x + "|" + y : y + "|" + x;
    }

    public static String dirKey(UUID from, UUID to) {
        return from + ">" + to;
    }
}
