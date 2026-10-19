package com.kingdomsai.core;

/** Configuração da simulação (preenchida pelo adaptador a partir do kingdomsai-common.toml). */
public final class CoreConfig {
    public int economicTickSeconds = 10;
    public int strategicTickSeconds = 30;
    public int populationTickSeconds = 60;
    public int cellSize = 32;
    public int initialClaimRadius = 3;
    public int startingCitizens = 10;
    public int rivalKingdoms = 2;
    public int rivalDistance = 320;
    public int maxNpcsPerKingdom = 60;
    /** Blocos por segundo que um construtor coloca. */
    public double builderBlocksPerSecond = 2.0;
    public boolean aiKingdomsEnabled = true;
    public boolean constructionEnabled = true;
    public boolean diplomacyEnabled = true;
    /** Ordens com as mãos continuam com o rei longe: o jogo mantém carregados os chunks onde o súdito trabalha. */
    public boolean keepOrderChunksLoaded = true;
    /** Limite de chunks mantidos carregados à distância (desempenho do servidor). */
    public int maxForcedChunks = 16;
    /** Limite de blocos quebrados por segundo somando todos os súditos (evita lag com muitas ordens de corte/mina). */
    public int maxBreaksPerSecond = 40;
    /** Vida dos súditos: a IA (se configurada) decide a agenda dos importantes e escreve conversas entre eles. */
    public boolean lifeLlm = true;
    /** Chamadas por minuto da IA para a vida dos súditos (as falas com o rei têm prioridade). */
    public int lifeLlmPerMinute = 4;

    public CoreConfig() {}

    public int ticks(int seconds) {
        return Math.max(1, seconds * 20);
    }
}
