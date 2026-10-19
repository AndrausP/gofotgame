package com.kingdomsai.core.military;

import com.kingdomsai.core.common.Pos;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Uma tropa (ou leva de colonos) fora de casa: marcha até o alvo, luta ou finca marcos, segura a terra e volta.
 * Salva no mundo: fechar o jogo no meio da marcha não perde ninguém.
 */
public final class Campaign {
    public enum Kind {
        ATTACK("Ataque"),
        SETTLE("Colonização"),
        /** Treino: o instrutor leva a tropa a um campo e eles treinam em formação (disciplina e coragem sobem). */
        TRAIN("Treino"),
        /** Ir a um lugar e ficar lá (reunir a tropa na praça, apresentar-se ao capitão, "fiquem aqui"). */
        MOVE("Deslocamento");

        public final String display;

        Kind(String display) {
            this.display = display;
        }
    }

    public enum Status {
        MARCHING("marchando"),
        HOLDING("segurando a terra"),
        RETURNING("voltando para casa"),
        DONE("concluída"),
        FAILED("fracassou");

        public final String display;

        Status(String display) {
            this.display = display;
        }

        public boolean live() {
            return this == MARCHING || this == HOLDING || this == RETURNING;
        }
    }

    public UUID id;
    public int number;
    public UUID kingdomId;
    public Kind kind = Kind.ATTACK;
    public Status status = Status.MARCHING;
    /** Dono da terra no momento da ordem (null = terra livre). */
    public UUID targetKingdomId;
    public Pos target;
    public Pos origin;
    /** Quem decidiu quem vai (general, capitão ou o soldado mais experiente). */
    public UUID commanderId;
    public List<UUID> members = new ArrayList<>();
    /** "Sem piedade": civis do lugar conquistado morrem em vez de virar cativos (só o rei, com confirmação). */
    public boolean noQuarter;
    /** Ataque sem declaração de guerra (a guerra é declarada na hora, com desonra). */
    public boolean surprise;
    public long startTick, arriveTick, holdUntil, returnTick;
    /** Treino/deslocamento: quanto tempo ficam no lugar. */
    public int holdSeconds = 60;
    public int kills, losses, captives, cellsTaken;
    public String order = "";
    public String result = "";
    public List<String> log = new ArrayList<>();

    public Campaign() {}

    public boolean live() {
        return status != null && status.live();
    }

    /** Guerra de verdade (fora de casa, come mais, não pode ser interrompida por outra ordem qualquer). */
    public boolean war() {
        return kind == Kind.ATTACK || kind == Kind.SETTLE;
    }

    public void log(String line) {
        log.add(line);
        if (log.size() > 30) log.remove(0);
    }
}
