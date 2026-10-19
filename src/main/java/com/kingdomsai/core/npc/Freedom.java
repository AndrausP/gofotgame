package com.kingdomsai.core.npc;

/** Condição de uma pessoa no reino. Cativos e escravizados não são recrutados, promovidos nem mandados para outro ofício. */
public enum Freedom {
    FREE("Livre"),
    /** Preso de guerra: não trabalha, come pouco, espera o destino que o rei der (libertar, escravizar, executar). */
    CAPTIVE("Cativo"),
    /** Trabalho forçado: rende menos, não tem lealdade, foge ou se revolta se houver pouca vigilância. */
    ENSLAVED("Escravizado");

    public final String display;

    Freedom(String display) {
        this.display = display;
    }
}
