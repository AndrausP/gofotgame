package com.kingdomsai.core.npc;

public enum NpcActivity {
    IDLE("à toa"),
    WORK("trabalhando"),
    BUILD("construindo"),
    PATROL("patrulhando"),
    GUARD("de guarda"),
    SOCIALIZE("conversando na praça"),
    GO_HOME("indo para casa"),
    SLEEP("dormindo"),
    TALKING("falando com o rei"),
    SUMMONED("atendendo ao chamado do rei"),
    MARCH("em marcha"),
    TRAIN("treinando"),
    IMPRISONED("preso"),
    // --- vida (core/life): o que gente faz quando não está trabalhando
    EAT("comendo"),
    REST("descansando"),
    PRAY("rezando"),
    READ("lendo"),
    CHAT("conversando"),
    VISIT("visitando alguém"),
    WANDER("passeando"),
    FLEE("fugindo");

    public final String display;

    NpcActivity(String display) {
        this.display = display;
    }

    /** Não está trabalhando (pausa a rotina/cadeia de trabalho). */
    public boolean leisure() {
        return this == SLEEP || this == SOCIALIZE || this == TALKING || this == SUMMONED || this == EAT || this == REST || this == PRAY
                || this == READ || this == CHAT || this == VISIT || this == WANDER || this == FLEE;
    }
}
