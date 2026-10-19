package com.kingdomsai.core.work;

import com.kingdomsai.core.common.Text;

/**
 * Onde uma etapa acontece. Prédios precisam existir e estar prontos; MINE/FOREST são lugares na natureza
 * do reino; RECIPIENT é "onde o destinatário estiver" (cartas).
 */
public enum Place {
    FARM("Fazenda", false, "farm", "fazenda", "campo", "lavoura", "plantacao"),
    SMITHY("Forja", false, "smithy", "forja", "ferraria", "fornalha", "ferreiro"),
    STORAGE("Armazém", true, "storage", "armazem", "bau", "deposito", "celeiro"),
    LIBRARY("Biblioteca", false, "library", "biblioteca", "escola"),
    MINE("Mina", false, null, "mina", "mine", "pedreira", "quarry"),
    FOREST("Bosque", true, null, "bosque", "floresta", "forest", "mata"),
    RECIPIENT("Destinatário", true, null, "destinatario", "recipient");

    public final String display;
    /** Gênero do nome, para "na forja" / "no armazém". */
    public final boolean masculine;
    /** Planta do prédio que serve de local; null = lugar fora de prédios. */
    public final String blueprintId;
    private final String[] aliases;

    Place(String display, boolean masculine, String blueprintId, String... aliases) {
        this.display = display;
        this.masculine = masculine;
        this.blueprintId = blueprintId;
        this.aliases = aliases;
    }

    /** "a forja" / "o armazém" */
    public String a() {
        return (masculine ? "o " : "a ") + display.toLowerCase();
    }

    /** "na forja" / "no armazém" */
    public String na() {
        return "n" + a();
    }

    /** "da forja" / "do armazém" */
    public String da() {
        return "d" + a();
    }

    public boolean isBuilding() {
        return blueprintId != null;
    }

    public static Place parse(String s) {
        if (s == null) return null;
        String n = Text.norm(s);
        for (Place p : values()) {
            if (p.name().equalsIgnoreCase(n) || Text.norm(p.display).equals(n)) return p;
            for (String a : p.aliases) if (a.equals(n)) return p;
        }
        return null;
    }
}
