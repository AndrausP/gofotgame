package com.kingdomsai.core.life;

import com.kingdomsai.core.common.Pos;

import java.util.UUID;

/**
 * O que a pessoa decidiu fazer agora quando não há ordem do rei: comer, descansar, visitar um amigo, rezar, ler, passear,
 * fugir. Uma intenção por vez (como o "controlador cognitivo" do PIANO): o que ela fala e o que ela faz vêm daqui.
 */
public final class Intention {
    public enum Kind {
        NONE("—"),
        EAT("comer"),
        REST("descansar"),
        SOCIALIZE("conversar na praça"),
        VISIT("visitar"),
        PRAY("rezar"),
        READ("ler"),
        WANDER("passear"),
        HOME("ficar em casa"),
        WORK("trabalhar"),
        FLEE("fugir"),
        SEEK_HELP("pedir ajuda");

        public final String display;

        Kind(String display) {
            this.display = display;
        }
    }

    public Kind kind = Kind.NONE;
    /** Pessoa-alvo (visitar, conversar) ou null. */
    public UUID targetNpc;
    /** Lugar-alvo (casa, praça, capela, biblioteca) ou null. */
    public Pos target;
    public String place = "";
    /** Por quê, em português ("está com fome", "faz tempo que não vê a Bruna"). */
    public String reason = "";
    /** Até quando (tick). */
    public long until;
    /** Quem decidiu: rotina, necessidade, reflexo, IA. */
    public String source = "rotina";

    public Intention() {}

    public static Intention of(Kind kind, Pos target, String place, String reason, long until, String source) {
        Intention i = new Intention();
        i.kind = kind;
        i.target = target;
        i.place = place == null ? "" : place;
        i.reason = reason == null ? "" : reason;
        i.until = until;
        i.source = source;
        return i;
    }

    public boolean active(long tick) {
        return kind != Kind.NONE && tick < until;
    }

    public String describe() {
        if (kind == Kind.NONE) return "—";
        return kind.display + (place.isBlank() ? "" : " (" + place + ")") + (reason.isBlank() ? "" : " — " + reason);
    }
}
