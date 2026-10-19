package com.kingdomsai.core.construction.program;

import com.kingdomsai.core.common.Text;

/** Tipos de módulo de um programa arquitetônico. A LLM escolhe tipos e dimensões; o compilador gera os blocos. */
public enum ModuleType {
    /** Nave/salão retangular com telhado (gable, vault, flat). */
    HALL(true),
    /** Torre quadrada ou redonda com andares, escada de mão e telhado (cone ou ameias). */
    TOWER(true),
    /** Abside: semicírculo no fundo de outro módulo, com meia-cúpula. */
    APSE(true),
    /** Claustro: pátio com galeria coberta em volta. */
    CLOISTER(true),
    /** Trecho de muralha com ameias. */
    WALL_SEGMENT(true),
    /** Portão: dois pilares, passagem e verga. */
    GATE(true),
    /** Contrafortes: colunas ao longo das paredes compridas do módulo alvo. */
    BUTTRESS_ROW(false),
    /** Faixa de janelas (vitrais) nas paredes do módulo alvo. */
    WINDOW_BAND(false),
    /** Define o telhado do módulo alvo (gable, vault, flat, cone). */
    ROOF(false),
    /** Escada externa encostada no módulo alvo. */
    STAIR(true),
    /** Vão elevado (ponte) com guarda-corpo. */
    BRIDGE_SPAN(true);

    /** Tem corpo próprio (caixa no plano) e portanto participa da checagem de sobreposição. */
    public final boolean solid;

    ModuleType(boolean solid) {
        this.solid = solid;
    }

    public static ModuleType parse(String s) {
        if (s == null) return null;
        String n = Text.norm(s).replace('-', '_').replace(' ', '_');
        for (ModuleType t : values()) if (t.name().equalsIgnoreCase(n)) return t;
        return switch (n) {
            case "nave", "salao", "hall_nave" -> HALL;
            case "torre" -> TOWER;
            case "abside" -> APSE;
            case "claustro" -> CLOISTER;
            case "muralha", "muro", "wall" -> WALL_SEGMENT;
            case "portao", "gate_house" -> GATE;
            case "contraforte", "contrafortes", "buttress" -> BUTTRESS_ROW;
            case "vitrais", "janelas", "windows", "window" -> WINDOW_BAND;
            case "telhado" -> ROOF;
            case "escada" -> STAIR;
            case "ponte", "bridge" -> BRIDGE_SPAN;
            default -> null;
        };
    }
}
