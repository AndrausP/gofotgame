package com.kingdomsai.core.persistence;

import com.google.gson.JsonObject;
import com.kingdomsai.core.WorldState;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.kingdom.ResourceType;
import com.kingdomsai.core.npc.Npc;

import java.util.*;

/** Migração de saves entre versões do schema. Cada versão nova adiciona um passo aqui. */
public final class Migrations {
    private Migrations() {}

    public static void migrate(JsonObject root) {
        int v = root.has("schemaVersion") ? root.get("schemaVersion").getAsInt() : 0;
        if (v < 1) {
            // v0 → v1: versão inicial; nada a converter.
            root.addProperty("schemaVersion", 1);
        }
        if (v < 2) {
            // v1 → v2: cadeias de trabalho, livros/cartas, baús e mãos dos NPCs, ordens físicas e mochila. Campos novos; os padrões vêm do repair().
            root.addProperty("schemaVersion", 2);
        }
        if (v < 3) {
            // v2 → v3: guerra e domínio — campanhas, cativos/escravizados (Npc.freedom), infâmia do reino. Campos novos; padrões no repair().
            root.addProperty("schemaVersion", 3);
        }
        if (v < 4) {
            // v3 → v4: armas individuais (Npc.equipped), treinos/deslocamentos, tesouro físico nos baús. Padrões no repair().
            root.addProperty("schemaVersion", 4);
        }
        if (v < 5) {
            // v4 → v5: mochila real (ferramentas com desgaste, ração, equipamento vestido), bens do reino nos baús, trabalho contínuo.
            // Moradores antigos recebem o kit do ofício e o reino o estoque inicial de bens ao abrir (KingdomsCore).
            root.addProperty("kitsGranted", false);
            root.addProperty("schemaVersion", 5);
        }
        if (v < 6) {
            // v5 → v6: vida dos súditos — necessidades (companhia, saúde, medo), humor, par, intenção, objetivo pessoal, falas recentes.
            // Campos novos com padrão; nada a converter.
            root.addProperty("schemaVersion", 6);
        }
        if (v < 7) {
            // v6 → v7: vontade própria — petições, decretos e modo cívico. Campos novos com padrão; nada a converter.
            root.addProperty("schemaVersion", 7);
        }
        if (v < 8) {
            // v7 → v8: leis sobre propriedades (Decree.rule, ordens REPLACE). Campo novo opcional; nada a converter.
            root.addProperty("schemaVersion", 8);
        }
        // if (v < 9) { ... }
    }

    /** Garante campos não nulos após carregar (Gson ignora inicializadores para campos ausentes em alguns casos). */
    public static void repair(WorldState s) {
        if (s.kingdoms == null) s.kingdoms = new LinkedHashMap<>();
        if (s.npcs == null) s.npcs = new LinkedHashMap<>();
        if (s.buildings == null) s.buildings = new LinkedHashMap<>();
        if (s.links == null) s.links = new HashMap<>();
        if (s.attitudes == null) s.attitudes = new HashMap<>();
        if (s.treaties == null) s.treaties = new ArrayList<>();
        if (s.chronicle == null) s.chronicle = new ArrayList<>();
        if (s.blueprintSpecs == null) s.blueprintSpecs = new LinkedHashMap<>();
        if (s.savedBlueprints == null) s.savedBlueprints = new ArrayList<>();
        if (s.chains == null) s.chains = new LinkedHashMap<>();
        if (s.documents == null) s.documents = new LinkedHashMap<>();
        if (s.jobs == null) s.jobs = new LinkedHashMap<>();
        if (s.forcedChunks == null) s.forcedChunks = new LinkedHashSet<>();
        if (s.playerLastSeen == null) s.playerLastSeen = new HashMap<>();
        if (s.petitions == null) s.petitions = new LinkedHashMap<>();
        for (var k : s.kingdoms.values()) {
            if (k.decrees == null) k.decrees = new ArrayList<>();
            if (k.civic == null || k.civic.isBlank()) k.civic = "council";
        }
        if (s.campaigns == null) s.campaigns = new LinkedHashMap<>();
        for (var c : s.campaigns.values()) {
            if (c.members == null) c.members = new ArrayList<>();
            if (c.log == null) c.log = new ArrayList<>();
            if (c.result == null) c.result = "";
            if (c.order == null) c.order = "";
            if (c.kind == null) c.kind = com.kingdomsai.core.military.Campaign.Kind.ATTACK;
            if (c.status == null) c.status = com.kingdomsai.core.military.Campaign.Status.FAILED;
            if (c.number > s.campaignCounter) s.campaignCounter = c.number;
        }
        for (var j : s.jobs.values()) {
            if (j.tasks == null) j.tasks = new ArrayList<>();
            if (j.log == null) j.log = new ArrayList<>();
            if (j.gained == null) j.gained = new TreeMap<>();
            if (j.reason == null) j.reason = "";
            if (j.note == null) j.note = "";
            if (j.labor == null) j.labor = "";
            if (j.oreId == null) j.oreId = "";
            if (j.continuous && (j.site == null || j.labor.isEmpty())) j.continuous = false; // trabalho contínuo sem local não retoma
            for (var t : j.tasks) if (t.blocks == null) t.blocks = new ArrayList<>();
            if (j.number > s.jobCounter) s.jobCounter = j.number;
        }
        for (var c : s.chains.values()) {
            if (c.steps == null) c.steps = new ArrayList<>();
            if (c.roles == null) c.roles = new LinkedHashMap<>();
            if (c.log == null) c.log = new ArrayList<>();
            if (c.cycleOutput == null) c.cycleOutput = new EnumMap<>(com.kingdomsai.core.work.Item.class);
            if (c.brokenReason == null) c.brokenReason = "";
            if (c.number > s.chainCounter) s.chainCounter = c.number;
        }
        for (var d : s.documents.values()) {
            if (d.facts == null) d.facts = new ArrayList<>();
            if (d.readers == null) d.readers = new ArrayList<>();
        }
        for (var b : s.buildings.values()) {
            if (b.inventory == null) b.inventory = new EnumMap<>(com.kingdomsai.core.work.Item.class);
            if (b.builderIds == null) b.builderIds = new ArrayList<>();
            if (b.builderId != null && !b.builderIds.contains(b.builderId)) b.builderIds.add(b.builderId);
            if (b.residents == null) b.residents = new ArrayList<>();
        }
        if (s.events == null) s.events = new ArrayList<>();
        for (Kingdom k : s.kingdoms.values()) {
            if (k.stock == null) k.stock = new EnumMap<>(ResourceType.class);
            if (k.lastDelta == null) k.lastDelta = new EnumMap<>(ResourceType.class);
            if (k.laws == null) k.laws = new Kingdom.Laws();
            if (k.markers == null) k.markers = new EnumMap<>(com.kingdomsai.core.kingdom.Marker.class);
            if (k.goods == null) k.goods = new TreeMap<>();
            if (k.openingDone == null) k.openingDone = new LinkedHashSet<>();
        }
        for (Npc n : s.npcs.values()) {
            if (n.relations == null) n.relations = new HashMap<>();
            if (n.memories == null) n.memories = new ArrayList<>();
            else n.memories = new ArrayList<>(n.memories);
            if (n.traits == null) n.traits = new HashMap<>();
            if (n.currentTask == null) n.currentTask = "";
            if (n.lastDecision == null) n.lastDecision = "";
            if (n.lastLlmCall == null) n.lastLlmCall = "";
            if (n.carrying == null) n.carrying = new EnumMap<>(com.kingdomsai.core.work.Item.class);
            if (n.heldItem == null) n.heldItem = "";
            if (n.bag == null) n.bag = new TreeMap<>();
            if (n.freedom == null) n.freedom = com.kingdomsai.core.npc.Freedom.FREE;
            if (n.equipped == null) n.equipped = "";
            if (n.wear == null) n.wear = new TreeMap<>();
            if (n.gear == null) n.gear = new TreeMap<>();
            if (n.skillXp == null) n.skillXp = new TreeMap<>();
            // v6: vida
            if (n.intention == null) n.intention = new com.kingdomsai.core.life.Intention();
            if (n.intention.kind == null) n.intention.kind = com.kingdomsai.core.life.Intention.Kind.NONE;
            if (n.intention.reason == null) n.intention.reason = "";
            if (n.intention.place == null) n.intention.place = "";
            if (n.intention.source == null) n.intention.source = "rotina";
            if (n.goal == null) n.goal = "";
            if (n.recentTalk == null) n.recentTalk = new ArrayList<>();
            if (n.spilled == null) n.spilled = new TreeMap<>();
            if (n.partnerId != null && (s.npcs.get(n.partnerId) == null || !s.npcs.get(n.partnerId).alive)) n.partnerId = null;
            // quem estava numa campanha que sumiu do save volta para casa
            if (n.campaignId != null && (!s.campaigns.containsKey(n.campaignId) || !s.campaigns.get(n.campaignId).live())) n.campaignId = null;
        }
        s.schemaVersion = WorldState.SCHEMA_VERSION;
    }
}
