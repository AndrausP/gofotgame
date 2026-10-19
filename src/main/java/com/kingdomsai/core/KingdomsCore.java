package com.kingdomsai.core;

import com.kingdomsai.core.action.ActionSystem;
import com.kingdomsai.core.ai.Advisor;
import com.kingdomsai.core.ai.KingdomDirector;
import com.kingdomsai.core.ai.NpcScheduler;
import com.kingdomsai.core.common.Pos;
import com.kingdomsai.core.common.Text;
import com.kingdomsai.core.construction.Blueprint;
import com.kingdomsai.core.construction.BlueprintLibrary;
import com.kingdomsai.core.construction.Building;
import com.kingdomsai.core.construction.ConstructionSystem;
import com.kingdomsai.core.diplomacy.DiplomacySystem;
import com.kingdomsai.core.economy.EconomySystem;
import com.kingdomsai.core.event.EventBus;
import com.kingdomsai.core.event.EventLog;
import com.kingdomsai.core.event.EventType;
import com.kingdomsai.core.event.GameEvent;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.kingdom.KingdomPersonality;
import com.kingdomsai.core.kingdom.ResourceType;
import com.kingdomsai.core.llm.ContextBuilder;
import com.kingdomsai.core.llm.DialogueService;
import com.kingdomsai.core.llm.LlmGateway;
import com.kingdomsai.core.npc.*;
import com.kingdomsai.core.population.PopulationSystem;
import com.kingdomsai.core.port.WorldPort;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Fachada do Core. Dono do estado, do Event Bus e dos sistemas.
 * Todas as mutações acontecem na thread do servidor (o adaptador garante isso).
 */
public final class KingdomsCore {
    public static final int[] PALETTE = {0xD4A017, 0x3B82F6, 0xDC2626, 0x16A34A, 0x9333EA, 0xEA580C, 0x0891B2, 0xBE185D};

    private final WorldState state;
    private final CoreConfig config;
    private final EventBus bus;
    private final Random rng;
    private WorldPort world = WorldPort.NONE;
    /** Última posição conhecida de cada jogador (adaptador e CLI atualizam) — para "venha aqui" e "me siga". */
    private final Map<UUID, Pos> playerPositions = new HashMap<>();
    private final Set<UUID> online = new HashSet<>();
    /** Para onde cada jogador está olhando (bloco na mira + direção) — "quebre isso", "abra esse baú". */
    private final Map<UUID, Look> playerLooks = new HashMap<>();
    private com.kingdomsai.core.port.PhysicalPort physical = com.kingdomsai.core.port.PhysicalPort.NONE;

    /** @param facing north|south|east|west */
    public record Look(Pos block, String facing) {}
    private java.util.concurrent.Executor mainThread = Runnable::run;

    private final ActionSystem actions;
    private final ConstructionSystem construction;
    private final EconomySystem economy;
    private final PopulationSystem population;
    private final DiplomacySystem diplomacy;
    private final KingdomDirector director;
    private final NpcScheduler scheduler;
    private final Advisor advisor;
    private final ContextBuilder contextBuilder;
    private final LlmGateway llm;
    private final DialogueService dialogue;
    private final com.kingdomsai.core.work.WorkSystem work;
    private final com.kingdomsai.core.skill.SkillSystem skills;
    private final com.kingdomsai.core.military.MilitarySystem warfare;
    private final com.kingdomsai.core.economy.TreasurySystem treasury;
    private final com.kingdomsai.core.event.AwayReport reports;
    private final com.kingdomsai.core.life.LifeSystem life;
    private final com.kingdomsai.core.civic.CivicSystem civic;
    private final com.kingdomsai.core.law.LawEnforcer law;
    private final com.kingdomsai.core.ai.Opening opening;
    /** Desempenho: tempo médio e máximo de um tick do Core (ms) na última janela de 60 s. */
    private final long[] perfWindow = new long[60];
    private long perfSecondNanos, perfMaxNanos, perfWindowMax;
    private int perfIdx;

    public KingdomsCore(WorldState state, CoreConfig config) {
        this.state = state;
        this.config = config;
        this.state.territory.cellSize = config.cellSize;
        reloadBlueprints();
        EventLog log = new EventLog(1500);
        log.restore(state.events);
        this.bus = new EventBus(log, state.eventSeq);
        this.rng = new Random(state.seed ^ state.tick);
        this.actions = new ActionSystem(this);
        this.construction = new ConstructionSystem(this);
        this.economy = new EconomySystem(this);
        this.population = new PopulationSystem(this);
        this.diplomacy = new DiplomacySystem(this);
        this.director = new KingdomDirector(this);
        this.scheduler = new NpcScheduler(this);
        this.advisor = new Advisor(this);
        this.contextBuilder = new ContextBuilder(this);
        this.llm = new LlmGateway(this);
        this.dialogue = new DialogueService(this);
        this.work = new com.kingdomsai.core.work.WorkSystem(this);
        this.skills = new com.kingdomsai.core.skill.SkillSystem(this);
        this.warfare = new com.kingdomsai.core.military.MilitarySystem(this);
        this.treasury = new com.kingdomsai.core.economy.TreasurySystem(this);
        this.reports = new com.kingdomsai.core.event.AwayReport(this);
        this.life = new com.kingdomsai.core.life.LifeSystem(this);
        this.civic = new com.kingdomsai.core.civic.CivicSystem(this);
        this.law = new com.kingdomsai.core.law.LawEnforcer(this);
        this.opening = new com.kingdomsai.core.ai.Opening(this);
        wireReactions();
        if (!state.kitsGranted) { // save antigo (v4 ou antes): moradores recebem o kit do ofício; reinos, as reservas
            for (Npc n : allAlive()) if (n.bag.keySet().stream().noneMatch(com.kingdomsai.core.skill.Inventory::isTool)) com.kingdomsai.core.skill.Kit.grantStarter(n);
            for (Kingdom k : state.kingdoms.values()) if (k.goods.isEmpty()) k.goods.putAll(com.kingdomsai.core.skill.Kit.starterGoods());
            state.kitsGranted = true;
        }
    }

    /** Reações entre sistemas via eventos (Military não mexe em Religion: publica, e quem quiser reage). */
    private void wireReactions() {
        bus.subscribe(EventType.WAR_DECLARED, e -> {
            for (Kingdom k : state.kingdoms.values()) {
                if (k.id.equals(e.kingdomId())) continue;
                k.morale = Text.clamp(k.morale - (k.id.toString().equals(e.data("target")) ? 10 : 2), 0, 100);
            }
            Kingdom aggressor = kingdom(e.kingdomId());
            if (aggressor != null) aggressor.honor = Text.clamp(aggressor.honor - 5, 0, 100);
        });
        // Crueldade se espalha: os outros reinos ficam hostis e desconfiados; o reino de origem das vítimas, inimigo mortal.
        bus.subscribe(EventType.ATROCITY, e -> {
            Kingdom k = kingdom(e.kingdomId());
            if (k == null) return;
            int dead = parseInt(e.data("dead")), own = parseInt(e.data("own"));
            for (Kingdom o : state.kingdoms.values()) {
                if (o == k) continue;
                var att = diplomacy.attitude(o.id, k.id);
                boolean victimsFromHere = o.id.toString().equals(e.data("origin"));
                double tolerance = o.personality.tolerance / 100.0;
                att.hostility = Text.clamp(att.hostility + (victimsFromHere ? 100 : (6 + Math.min(20, dead)) * (1.2 - tolerance * 0.5)), 0, 100);
                att.trust = Text.clamp(att.trust - (victimsFromHere ? 100 : 10 + own), 0, 100);
                att.fear = Text.clamp(att.fear + Math.min(15, dead), 0, 100);
            }
        });
        bus.subscribe(EventType.ENSLAVED, e -> {
            Kingdom k = kingdom(e.kingdomId());
            if (k == null) return;
            for (Kingdom o : state.kingdoms.values()) {
                if (o == k) continue;
                var att = diplomacy.attitude(o.id, k.id);
                boolean fromHere = o.id.toString().equals(e.data("origin"));
                att.hostility = Text.clamp(att.hostility + (fromHere ? 25 : 3), 0, 100);
                att.trust = Text.clamp(att.trust - (fromHere ? 20 : 4), 0, 100);
            }
        });
        // obra começou: os materiais saem dos baús na hora; armazém novo: o estoque passa para ele
        bus.subscribe(EventType.BUILDING_STARTED, e -> {
            Kingdom k = kingdom(e.kingdomId());
            if (k != null) treasury.sync(k);
        });
        bus.subscribe(EventType.BUILDING_COMPLETED, e -> {
            Kingdom k = kingdom(e.kingdomId());
            if (k != null) treasury.sync(k);
        });
        bus.subscribe(EventType.BUILDING_COMPLETED, e -> {
            Kingdom k = kingdom(e.kingdomId());
            if (k != null) k.morale = Text.clamp(k.morale + 1, 0, 100);
        });
        bus.subscribe(EventType.NPC_DIED, e -> {
            Npc dead = npc(e.actorId());
            if (dead == null) return;
            for (Npc n : citizens(dead.kingdomId)) {
                Relation r = n.relations.get(dead.id);
                if (r != null && r.affection > 60)
                    n.remember(state.tick, dead.name + " morreu. Sinto falta.", 70, dead.id, "morte", "luto");
            }
        });
    }

    private static int parseInt(String s) {
        try {
            return s == null ? 0 : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ tick

    /** Avança a simulação em 1 tick do jogo (chamado 20x por segundo pelo adaptador). */
    public void step() {
        long t0 = System.nanoTime();
        state.tick++;
        long t = state.tick;
        if (t % 20 == 0) {
            civic.tickSecond();  // vontade própria: petições e decretos
            law.tickSecond();    // leis sobre objetos: cobra o cumprimento
            life.tickSecond();   // necessidades, humor, intenções, conversas, reflexos (antes do agendador decidir o destino)
            scheduler.tickSecond();
            work.tickSecond();   // zera e marca quem está em cadeia (onDuty)
            skills.tickSecond(); // ordens físicas marcam por cima
            construction.tickSecond();
            warfare.tickSecond();
        }
        if (t % config.ticks(config.economicTickSeconds) == 0) {
            economy.tick();
            treasury.syncAll(); // o estoque mora nos baús: produção entra, consumo sai, o que o jogador mexeu conta
        }
        if (t % config.ticks(config.populationTickSeconds) == 0) population.tick();
        if (t % config.ticks(config.strategicTickSeconds) == 0) {
            population.strategicTick();
            warfare.strategicTick();
            if (config.diplomacyEnabled) diplomacy.tick();
            if (config.aiKingdomsEnabled) director.tick();
            opening.tick(); // roteiro de início dos reinos dos jogadores
        }
        bus.dispatch();
        long dt = System.nanoTime() - t0;
        perfSecondNanos += dt;
        perfMaxNanos = Math.max(perfMaxNanos, dt);
        if (t % 20 == 0) {
            perfWindow[perfIdx++ % perfWindow.length] = perfSecondNanos;
            perfWindowMax = perfIdx % perfWindow.length == 0 ? perfMaxNanos : Math.max(perfWindowMax, perfMaxNanos);
            perfSecondNanos = 0;
            if (perfIdx % perfWindow.length == 0) perfMaxNanos = 0;
        }
    }

    /** Milissegundos médios por tick do Core no último minuto (20 ticks = 1 s) e o pior tick. */
    public double[] perf() {
        int n = Math.min(perfIdx, perfWindow.length);
        long sum = 0;
        for (int i = 0; i < n; i++) sum += perfWindow[i];
        double avg = n == 0 ? 0 : sum / (double) n / 20 / 1e6;
        return new double[]{avg, Math.max(perfWindowMax, perfMaxNanos) / 1e6};
    }

    public com.kingdomsai.core.law.LawEnforcer law() {
        return law;
    }

    public com.kingdomsai.core.civic.CivicSystem civic() {
        return civic;
    }

    public com.kingdomsai.core.life.LifeSystem life() {
        return life;
    }

    public com.kingdomsai.core.ai.Opening opening() {
        return opening;
    }

    // ------------------------------------------------------------------ founding

    public Kingdom foundKingdom(String name, UUID rulerPlayer, String rulerName, Pos center, KingdomPersonality personality,
                                int citizens) {
        Kingdom k = new Kingdom();
        k.id = com.kingdomsai.core.common.Ids.next();
        k.name = name;
        k.rulerPlayer = rulerPlayer;
        k.rulerName = rulerName;
        k.center = center;
        k.foundedTick = state.tick;
        k.personality = personality;
        k.color = PALETTE[state.kingdoms.size() % PALETTE.length];
        k.stock.put(ResourceType.FOOD, 220.0);
        k.stock.put(ResourceType.WOOD, 160.0);
        k.stock.put(ResourceType.STONE, 110.0);
        k.stock.put(ResourceType.IRON, 20.0);
        k.stock.put(ResourceType.GOLD, 500.0);
        k.stock.put(ResourceType.WEAPONS, 4.0);
        k.goods.putAll(com.kingdomsai.core.skill.Kit.starterGoods()); // reservas no armazém: ferramentas, tochas, sementes, carvão
        state.kingdoms.put(k.id, k);
        state.territory.claimRadius(center, config.initialClaimRadius, k.id, state.tick);

        Profession[] start = {Profession.FARMER, Profession.FARMER, Profession.FARMER, Profession.BUILDER,
                Profession.BUILDER, Profession.LUMBERJACK, Profession.MINER, Profession.BLACKSMITH,
                Profession.GUARD, Profession.MERCHANT, Profession.SOLDIER, Profession.PEASANT, Profession.FARMER, Profession.PRIEST};
        for (int i = 0; i < citizens; i++) {
            Profession p = i < start.length ? start[i] : Profession.PEASANT;
            createNpc(k, p, center.offset(rng.nextInt(9) - 4, 0, rng.nextInt(9) - 4));
        }
        // NPC comum vira conselheiro: personagem importante desde o início.
        citizens(k.id).stream().filter(n -> n.profession == Profession.PEASANT || n.profession == Profession.MERCHANT)
                .findFirst().ifPresent(n -> {
                    n.office = Office.ADVISOR;
                    n.level = IntelligenceLevel.IMPORTANT;
                });
        if (rulerPlayer == null) {
            // Reinos de IA têm um governante NPC.
            Npc ruler = createNpc(k, Profession.PEASANT, center);
            ruler.office = Office.KING;
            ruler.level = IntelligenceLevel.IMPORTANT;
            ruler.traits.put(Trait.AMBITION, 60 + rng.nextInt(40));
            k.rulerNpc = ruler.id;
            k.rulerName = ruler.name;
        }
        chronicle(k.name + " foi fundado por " + k.rulerName + ".");
        bus.publish(state.tick, EventType.KINGDOM_FOUNDED, GameEvent.Severity.GOOD, k.id, null,
                k.name + " foi fundado.");
        // Primeira obra: o Salão Real (marca o centro do reino).
        Blueprint hall = BlueprintLibrary.get("town_hall");
        Building b = construction.planAt(k, hall, center.offset(-hall.sizeX() / 2, 0, 3), true);
        if (b == null) construction.plan(k, hall, null);
        bus.dispatch();
        return k;
    }

    public Npc createNpc(Kingdom k, Profession profession, Pos pos) {
        Npc n = new Npc();
        n.id = com.kingdomsai.core.common.Ids.next();
        n.female = rng.nextBoolean();
        Set<String> taken = state.npcs.values().stream().map(x -> x.name.toLowerCase()).collect(Collectors.toSet());
        n.name = NameGenerator.person(rng, n.female, taken);
        n.skin = rng.nextInt(1000);
        n.kingdomId = k.id;
        n.profession = profession;
        n.pos = pos;
        n.bornTick = state.tick;
        for (Trait t : Trait.values()) n.traits.put(t, Text.clamp((int) Math.round(50 + rng.nextGaussian() * 22), 1, 99));
        n.loyalty = Text.clamp(45 + n.trait(Trait.LOYALTY) / 3 + rng.nextInt(10), 0, 100);
        n.hunger = 70 + rng.nextInt(30);
        n.energy = 70 + rng.nextInt(30);
        if (profession.isMilitary() && k.get(ResourceType.WEAPONS) >= 1) { // arma do arsenal do reino
            k.add(ResourceType.WEAPONS, -1);
            n.equipped = "iron_sword";
        }
        com.kingdomsai.core.skill.Kit.grantStarter(n); // chega com as ferramentas e a ração do ofício
        state.npcs.put(n.id, n);
        // Relações iniciais com alguns vizinhos.
        List<Npc> others = citizens(k.id);
        Collections.shuffle(others, rng);
        for (Npc o : others.subList(0, Math.min(3, others.size()))) {
            if (o.id.equals(n.id)) continue;
            Relation r = n.relationTo(o.id);
            r.affection = 30 + rng.nextInt(50);
            r.trust = 30 + rng.nextInt(50);
            if (n.trait(Trait.AGGRESSION) > 70 && rng.nextInt(3) == 0) r.rivalry = 40 + rng.nextInt(40);
            Relation back = o.relationTo(n.id);
            back.affection = r.affection;
            back.trust = r.trust;
            back.rivalry = r.rivalry;
        }
        return n;
    }

    /** Recarrega a biblioteca global com as plantas deste mundo. */
    public void reloadBlueprints() {
        List<com.kingdomsai.core.construction.Blueprint> custom = new ArrayList<>(state.savedBlueprints);
        for (var spec : state.blueprintSpecs.values()) {
            try {
                custom.add(com.kingdomsai.core.construction.ParametricBlueprints.generate(spec));
            } catch (RuntimeException e) {
                System.err.println("[KingdomsAI] planta inválida no save: " + e);
            }
        }
        BlueprintLibrary.resetCustom(custom);
    }

    /** Cria (ou reaproveita) uma planta paramétrica e a registra no mundo. */
    public com.kingdomsai.core.construction.Blueprint registerSpec(com.kingdomsai.core.construction.ParametricBlueprints.Spec spec) {
        var bp = com.kingdomsai.core.construction.ParametricBlueprints.generate(spec);
        boolean isNew = !state.blueprintSpecs.containsKey(bp.id());
        state.blueprintSpecs.put(bp.id(), spec);
        BlueprintLibrary.registerCustom(bp);
        if (isNew) bus.publish(state.tick, EventType.BLUEPRINT_CREATED, GameEvent.Severity.INFO, null, null,
                "Nova planta: " + bp.displayName() + " (" + bp.id() + ").");
        return bp;
    }

    /** Registra uma planta salva do mundo / .nbt. */
    public void registerSaved(com.kingdomsai.core.construction.Blueprint bp) {
        state.savedBlueprints.removeIf(b -> b.id().equals(bp.id()));
        state.savedBlueprints.add(bp);
        BlueprintLibrary.registerCustom(bp);
        bus.publish(state.tick, EventType.BLUEPRINT_CREATED, GameEvent.Severity.INFO, null, null,
                "Nova planta salva: " + bp.displayName() + " (" + bp.blockCount() + " passos).");
    }

    public boolean deleteBlueprint(String id) {
        boolean removed = state.blueprintSpecs.remove(id) != null | state.savedBlueprints.removeIf(b -> b.id().equals(id));
        if (removed) BlueprintLibrary.removeCustom(id);
        return removed;
    }

    public void chronicle(String line) {
        long day = state.tick / 24000;
        state.chronicle.add("Dia " + (day + 1) + ": " + line);
        if (state.chronicle.size() > 300) state.chronicle.remove(0);
    }

    // ------------------------------------------------------------------ queries

    public WorldState state() {
        return state;
    }

    public CoreConfig config() {
        return config;
    }

    public EventBus bus() {
        return bus;
    }

    public Random rng() {
        return rng;
    }

    public long tick() {
        return state.tick;
    }

    public void updatePlayerPos(UUID player, Pos pos) {
        if (player != null && pos != null) {
            playerPositions.put(player, pos);
            online.add(player);
        }
    }

    /** O jogador saiu do jogo (o adaptador avisa). A última posição continua conhecida. */
    public void playerLeft(UUID player) {
        online.remove(player);
    }

    public boolean isOnline(UUID player) {
        return player != null && online.contains(player);
    }

    public Pos playerPos(UUID player) {
        return player == null ? null : playerPositions.get(player);
    }

    public WorldPort world() {
        return world;
    }

    public void setWorld(WorldPort port) {
        this.world = port == null ? WorldPort.NONE : port;
    }

    /** Executor da thread do servidor — resultados assíncronos (LLM) voltam por aqui. */
    public java.util.concurrent.Executor mainThread() {
        return mainThread;
    }

    public void setMainThread(java.util.concurrent.Executor executor) {
        this.mainThread = executor == null ? Runnable::run : executor;
    }

    public ActionSystem actions() {
        return actions;
    }

    public ConstructionSystem construction() {
        return construction;
    }

    public EconomySystem economy() {
        return economy;
    }

    public DiplomacySystem diplomacy() {
        return diplomacy;
    }

    public KingdomDirector director() {
        return director;
    }

    public NpcScheduler scheduler() {
        return scheduler;
    }

    public Advisor advisor() {
        return advisor;
    }

    public ContextBuilder contextBuilder() {
        return contextBuilder;
    }

    public LlmGateway llm() {
        return llm;
    }

    public DialogueService dialogue() {
        return dialogue;
    }

    public com.kingdomsai.core.work.WorkSystem work() {
        return work;
    }

    /** Tesouro físico: o estoque do reino nos baús do armazém/salão. */
    public com.kingdomsai.core.economy.TreasurySystem treasury() {
        return treasury;
    }

    /** Guerra e domínio: campanhas, batalhas, colonos, cativos, massacre/escravidão/libertação. */
    public com.kingdomsai.core.military.MilitarySystem warfare() {
        return warfare;
    }

    public com.kingdomsai.core.skill.SkillSystem skills() {
        return skills;
    }

    public com.kingdomsai.core.event.AwayReport reports() {
        return reports;
    }

    public com.kingdomsai.core.port.PhysicalPort physical() {
        return physical;
    }

    public void setPhysical(com.kingdomsai.core.port.PhysicalPort port) {
        this.physical = port == null ? com.kingdomsai.core.port.PhysicalPort.NONE : port;
    }

    public void updatePlayerLook(UUID player, Pos block, String facing) {
        if (player != null) playerLooks.put(player, new Look(block, facing));
    }

    public Look playerLook(UUID player) {
        return player == null ? null : playerLooks.get(player);
    }

    public int llmMaxChars() {
        return llm.config().maxContextChars;
    }

    public Kingdom kingdom(UUID id) {
        return id == null ? null : state.kingdoms.get(id);
    }

    public Npc npc(UUID id) {
        return id == null ? null : state.npcs.get(id);
    }

    public Kingdom kingdomOfPlayer(UUID player) {
        for (Kingdom k : state.kingdoms.values()) if (player.equals(k.rulerPlayer)) return k;
        return null;
    }

    public Kingdom findKingdom(String nameOrId) {
        String n = Text.norm(nameOrId);
        for (Kingdom k : state.kingdoms.values())
            if (Text.norm(k.name).equals(n) || k.id.toString().equals(nameOrId)) return k;
        for (Kingdom k : state.kingdoms.values())
            if (Text.norm(k.name).contains(n) && n.length() >= 3) return k;
        return null;
    }

    public List<Npc> citizens(UUID kingdomId) {
        List<Npc> out = new ArrayList<>();
        for (Npc n : state.npcs.values()) if (n.alive && kingdomId.equals(n.kingdomId)) out.add(n);
        return out;
    }

    public List<Npc> allAlive() {
        List<Npc> out = new ArrayList<>();
        for (Npc n : state.npcs.values()) if (n.alive) out.add(n);
        return out;
    }

    public Npc findNpc(UUID kingdomId, String name) {
        if (name == null) return null;
        String n = Text.norm(name);
        for (Npc x : state.npcs.values())
            if (x.alive && (kingdomId == null || kingdomId.equals(x.kingdomId)) && Text.norm(x.name).equals(n)) return x;
        for (Npc x : state.npcs.values())
            if (x.alive && (kingdomId == null || kingdomId.equals(x.kingdomId)) && Text.norm(x.name).startsWith(n) && n.length() >= 3)
                return x;
        return null;
    }

    public List<Building> buildings(UUID kingdomId) {
        List<Building> out = new ArrayList<>();
        for (Building b : state.buildings.values())
            if (kingdomId.equals(b.kingdomId) && b.status != Building.Status.ABANDONED) out.add(b);
        return out;
    }

    public int population(UUID kingdomId) {
        int n = 0;
        for (Npc x : state.npcs.values()) if (x.alive && kingdomId.equals(x.kingdomId)) n++;
        return n;
    }

    public int count(UUID kingdomId, Profession p) {
        int n = 0;
        for (Npc x : state.npcs.values()) if (x.alive && kingdomId.equals(x.kingdomId) && x.profession == p) n++;
        return n;
    }

    public int military(UUID kingdomId) {
        return count(kingdomId, Profession.SOLDIER) + count(kingdomId, Profession.GUARD);
    }

    public int housingCapacity(UUID kingdomId) {
        int cap = 6; // acampamento inicial
        for (Building b : buildings(kingdomId)) if (b.isComplete()) cap += b.blueprint().housing();
        return cap;
    }

    public int completedOf(UUID kingdomId, String blueprintId) {
        int n = 0;
        for (Building b : buildings(kingdomId)) if (b.isComplete() && b.blueprintId.equals(blueprintId)) n++;
        return n;
    }

    public long day() {
        return state.tick / 24000 + 1;
    }

    /** Antes de salvar: copia contadores voláteis para o estado. */
    public WorldState snapshotForSave() {
        state.eventSeq = bus.currentSeq();
        state.events = bus.log().recent(400, e -> true);
        return state;
    }
}
