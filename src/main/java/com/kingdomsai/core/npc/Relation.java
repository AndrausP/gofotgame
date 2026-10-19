package com.kingdomsai.core.npc;

import com.kingdomsai.core.common.Text;

/** Relação individual de um NPC com outra pessoa (NPC ou o rei). Valores 0..100. */
public final class Relation {
    public int trust = 50;
    public int respect = 50;
    public int fear = 10;
    public int rivalry = 0;
    public int affection = 40;

    public Relation() {}

    public void adjust(int dTrust, int dRespect, int dFear, int dRivalry, int dAffection) {
        trust = Text.clamp(trust + dTrust, 0, 100);
        respect = Text.clamp(respect + dRespect, 0, 100);
        fear = Text.clamp(fear + dFear, 0, 100);
        rivalry = Text.clamp(rivalry + dRivalry, 0, 100);
        affection = Text.clamp(affection + dAffection, 0, 100);
    }

    public String label() {
        if (rivalry > 65) return "rival";
        if (affection > 75 && trust > 65) return "amigo próximo";
        if (affection > 60) return "amigo";
        if (fear > 60) return "teme";
        if (trust < 25) return "desconfia";
        return "conhecido";
    }
}
