package com.kingdomsai.core;

import com.kingdomsai.core.construction.Building;
import com.kingdomsai.core.diplomacy.Diplomacy;
import com.kingdomsai.core.event.GameEvent;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.npc.Npc;
import com.kingdomsai.core.territory.TerritoryMap;

import java.util.*;

/** Todo o estado salvo do mundo. Cada entidade tem UUID — nunca só o nome. */
public final class WorldState {
    public static final int SCHEMA_VERSION = 8;

    public int schemaVersion = SCHEMA_VERSION;
    public long seed = new Random().nextLong();
    public long tick;
    public long eventSeq;

    public Map<UUID, Kingdom> kingdoms = new LinkedHashMap<>();
    public Map<UUID, Npc> npcs = new LinkedHashMap<>();
    public Map<UUID, Building> buildings = new LinkedHashMap<>();
    public TerritoryMap territory = new TerritoryMap();
    public Map<String, Diplomacy.Link> links = new HashMap<>();
    public Map<String, Diplomacy.Attitude> attitudes = new HashMap<>();
    public List<Diplomacy.Treaty> treaties = new ArrayList<>();
    /** Crônica do mundo ("Ano 1: Reino de Andraus fundado."). */
    /** Plantas paramétricas do mundo (guardamos só os parâmetros; a planta é regerada ao carregar). */
    public Map<String, com.kingdomsai.core.construction.ParametricBlueprints.Spec> blueprintSpecs = new LinkedHashMap<>();
    /** Plantas salvas do mundo ou importadas de .nbt (blocos explícitos). */
    public List<com.kingdomsai.core.construction.Blueprint> savedBlueprints = new ArrayList<>();
    public List<String> chronicle = new ArrayList<>();
    /** Cadeias de trabalho (v2). */
    public Map<UUID, com.kingdomsai.core.work.WorkChain> chains = new LinkedHashMap<>();
    public int chainCounter;
    /** Livros e cartas (v2). */
    public Map<UUID, com.kingdomsai.core.work.Document> documents = new LinkedHashMap<>();
    /** Ordens físicas: quebrar, baús, fabricar (v2). */
    public Map<UUID, com.kingdomsai.core.skill.PhysicalJob> jobs = new LinkedHashMap<>();
    public int jobCounter;
    /** Chunks que ESTE mod mantém carregados ("x:z"), para soltar certinho mesmo depois de reiniciar. */
    public Set<String> forcedChunks = new LinkedHashSet<>();
    /** Último tick em que cada rei foi visto (relatório "enquanto Vossa Majestade esteve fora"). */
    public Map<UUID, Long> playerLastSeen = new HashMap<>();
    /** Tropas e colonos fora de casa (v3). */
    public Map<UUID, com.kingdomsai.core.military.Campaign> campaigns = new LinkedHashMap<>();
    public int campaignCounter;
    /** Kits de trabalho e estoque inicial de bens já entregues (saves antigos recebem ao abrir) (v5). */
    public boolean kitsGranted = true;
    /** Petições dos súditos (v7). */
    public Map<UUID, com.kingdomsai.core.civic.Petition> petitions = new LinkedHashMap<>();
    public int petitionCounter;
    public List<GameEvent> events = new ArrayList<>();

    public WorldState() {}
}
