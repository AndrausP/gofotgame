package com.kingdomsai.core.civic;

import java.util.UUID;

/** Lei em texto livre do reino. Alguns efeitos mudam a simulação de verdade; todas entram no que os súditos sabem. */
public final class Decree {
    public static final String NONE = "NONE", CURFEW = "CURFEW", PEACE = "PEACE", FEAST = "FEAST";

    public int number;
    public String text = "";
    public String effect = NONE;
    public String authorName = "";
    public UUID authorId;
    public long enactedTick;
    public boolean active = true;
    /** Número da petição que originou o decreto (0 = ordem direta). */
    public int viaPetition;
    /** Efeito sobre propriedades do mundo (cama vermelha, casa de pedra...): o LawEnforcer cobra de verdade. null = lei só em texto. */
    public com.kingdomsai.core.law.PropertyRule rule;

    public Decree() {}

    public static String parseEffect(String s) {
        if (s == null) return NONE;
        String v = s.trim().toUpperCase();
        return switch (v) {
            case CURFEW, PEACE, FEAST -> v;
            default -> NONE;
        };
    }

    /** Palpite do efeito pelo texto ("toque de recolher", "proibido brigar", "festa"). */
    public static String guessEffect(String text) {
        String t = com.kingdomsai.core.common.Text.norm(text);
        if (t.contains("recolher") || t.contains("toque de") || t.contains("proibido sair a noite") || t.contains("ficar em casa")) return CURFEW;
        if (t.contains("brig") || t.contains("paz entre") || t.contains("violencia") || t.contains("proibid") && t.contains("lutar")) return PEACE;
        if (t.contains("festa") || t.contains("banquete") || t.contains("festival")) return FEAST;
        return NONE;
    }
}
