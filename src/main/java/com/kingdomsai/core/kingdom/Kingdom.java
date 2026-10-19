package com.kingdomsai.core.kingdom;

import com.kingdomsai.core.common.Pos;

import java.util.*;

/**
 * Uma entidade política (Polity). O jogador é só um dos agentes do mundo:
 * reinos de IA usam exatamente a mesma classe, os mesmos sistemas e os mesmos validadores.
 */
public final class Kingdom {
    public enum PolityType { VILLAGE, CITY_STATE, KINGDOM, EMPIRE, TRIBE, THEOCRACY, REPUBLIC }

    public UUID id;
    public String name;
    public PolityType polity = PolityType.VILLAGE;
    /** UUID do jogador que é rei; null para reinos de IA. */
    public UUID rulerPlayer;
    /** Nome exibido do governante (jogador ou NPC). */
    public String rulerName;
    public UUID rulerNpc;
    public Pos center;
    public long foundedTick;
    public int color;

    public Map<ResourceType, Double> stock = new EnumMap<>(ResourceType.class);
    /** Variação do último tick econômico, para o HUD. */
    public Map<ResourceType, Double> lastDelta = new EnumMap<>(ResourceType.class);

    public double stability = 80;
    public double morale = 75;
    public double legitimacy = 70;

    public KingdomPersonality personality = KingdomPersonality.balanced();
    public Laws laws = new Laws();
    /** Leis em texto livre (v7), contador e modo cívico: council (o conselho decide) | king (só o rei) | off. */
    public java.util.List<com.kingdomsai.core.civic.Decree> decrees = new java.util.ArrayList<>();
    public int decreeCounter;
    public String civic = "council";

    /** Reputação perante o mundo. */
    public double honor = 60;
    /** Fama de crueldade (massacres, escravidão): 0..100, cai devagar. Pesa na estabilidade e na diplomacia (v3). */
    public double infamy = 0;
    /** Roteiro de início (v6): o conselheiro cuida das etapas nos primeiros dias; etapas cumpridas; próximo lembrete. */
    public boolean openingAuto = true;
    public java.util.Set<String> openingDone = new java.util.LinkedHashSet<>();
    public long openingNudge;
    public boolean openingFinished;
    /** Tesouro físico: o que havia nos baús na última conferência (null = ainda não conferido) (v4). */
    public Map<ResourceType, Integer> treasurySeen;
    /** Itens do registro que não couberam nos baús (v4). */
    public int treasuryOverflow;
    /** Bens guardados que não são recurso (ferramentas, sementes, tochas, carvão, minério bruto, armaduras) (v5). */
    public Map<String, Integer> goods = new java.util.TreeMap<>();
    /** O que havia de bens nos baús na última conferência (v5). */
    public Map<String, Integer> treasurySeenGoods;
    /** Baús que formavam o tesouro na última conferência ("x y z") — para mudar de lugar sem duplicar (v5). */
    public List<String> treasuryChests;
    /** Ferreiro: progresso da ferramenta/fundição em andamento (v5). */
    public double forgeProgress, smeltProgress;
    /** Fundições que ainda rendem do último carvão queimado (1 carvão funde 8) (v5). */
    public int fuelLeft;
    public double reliability = 60;

    /** Pontos marcados pelo rei com a Bandeira do Reino (spawn, praça, mina, bosque). */
    public Map<Marker, Pos> markers = new EnumMap<>(Marker.class);

    /** Postura estratégica do rei de IA (aggressive|defensive|mercantile|isolationist; vazio = nenhuma) até stanceUntilTick. */
    public String stance = "";
    public long stanceUntilTick;
    /** Crônica resumida do reino (300–600 caracteres), atualizada por regra a partir dos eventos marcantes. */
    public String chronicleSummary = "";

    public boolean famine;
    public boolean shortageWarned;
    public boolean housingWarned;

    public Kingdom() {}

    /** Onde chegam os novos moradores e o rei renasce: o marco, senão o centro. */
    public Pos spawnPoint() {
        Pos p = markers.get(Marker.SPAWN);
        return p != null ? p : center;
    }

    /** Ponto marcado ou o padrão. */
    public Pos marker(Marker m, Pos fallback) {
        Pos p = markers.get(m);
        return p != null ? p : fallback;
    }

    public boolean isPlayerKingdom() {
        return rulerPlayer != null;
    }

    public double get(ResourceType r) {
        return stock.getOrDefault(r, 0.0);
    }

    public void add(ResourceType r, double amount) {
        stock.put(r, Math.max(0, get(r) + amount));
    }

    public boolean has(Map<ResourceType, Integer> cost) {
        for (var e : cost.entrySet()) if (get(e.getKey()) + 1e-6 < e.getValue()) return false;
        return true;
    }

    public void pay(Map<ResourceType, Integer> cost) {
        for (var e : cost.entrySet()) add(e.getKey(), -e.getValue());
    }

    public static final class Laws {
        /** 0 = isento, 1 = baixo, 2 = normal, 3 = alto, 4 = extorsivo */
        public int taxLevel = 2;
        public boolean conscription = false;
        public boolean openMigration = true;
    }
}
