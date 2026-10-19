package com.kingdomsai.voxel;

import com.kingdomsai.core.KingdomsCore;
import com.kingdomsai.core.common.Pos;
import com.kingdomsai.core.construction.Blueprint;
import com.kingdomsai.core.construction.Building;
import com.kingdomsai.core.construction.Material;
import com.kingdomsai.core.event.EventType;
import com.kingdomsai.core.event.GameEvent;
import com.kingdomsai.core.npc.Npc;

import java.util.*;

/**
 * O mundo se mexe sozinho: clima, desgaste, fogo, desabamento e explosão — todos regras sobre voxels.
 * O que gente construiu perde integridade com o tempo e a chuva; sem reparo vira ruína e depois mato.
 */
public final class WorldSim {
    public enum Weather { CLEAR, RAIN, STORM }

    /** Quanto mais alto, mais rápido tudo envelhece (testes e demonstrações aceleram). */
    public double decayScale = 1.0;

    private final VoxelWorld world;
    private final LoadArea area;
    private final KingdomsCore core;
    private final NpcMover mover;
    private final Random rng;

    private Weather weather = Weather.CLEAR;
    private long weatherUntil = 6000;
    private final Map<Pos, Integer> fires = new LinkedHashMap<>();
    private final ArrayDeque<Pos> supportChecks = new ArrayDeque<>();
    private Pos[] pass = new Pos[0];
    private int cursor;
    private long passStart;
    private long passTicks = 200;
    private long nextStrike;
    /** Blocos de cada obra que precisam de reparo (desgastados ou destruídos). */
    private final Map<UUID, Set<Pos>> damage = new HashMap<>();
    private final Map<UUID, Map<Long, Blueprint.Placement>> placementIndex = new HashMap<>();
    private final Set<UUID> warned = new HashSet<>();

    public WorldSim(KingdomsCore core, VoxelWorld world, LoadArea area, NpcMover mover) {
        this.core = core;
        this.world = world;
        this.area = area;
        this.mover = mover;
        this.rng = new Random(world.gen().seed() ^ 0x5EED);
        world.removalListener = supportChecks::add;
        weatherUntil = core.tick() + 14000 + rng.nextInt(8000); // o mundo começa de céu limpo
    }

    public Weather weather() {
        return weather;
    }

    public void setWeather(Weather w, long ticks) {
        weather = w;
        weatherUntil = core.tick() + ticks;
    }

    public double rain() {
        return switch (weather) {
            case CLEAR -> 0;
            case RAIN -> 0.6;
            case STORM -> 1.0;
        };
    }

    public Map<UUID, Set<Pos>> damage() {
        return damage;
    }

    public Set<Pos> fires() {
        return fires.keySet();
    }

    // ------------------------------------------------------------------ tick

    public void tick() {
        long t = core.tick();
        if (t % 20 == 0) tickWeather(t);
        decayStep(t);
        if (t % 4 == 0) fireStep();
        supportStep();
        if (weather == Weather.STORM && t >= nextStrike) {
            nextStrike = t + 400 + rng.nextInt(800);
            lightning();
        }
    }

    private void tickWeather(long t) {
        if (t < weatherUntil) return;
        Weather prev = weather;
        switch (weather) {
            case CLEAR -> {
                weather = Weather.RAIN;
                weatherUntil = t + 2000 + rng.nextInt(4000);
            }
            case RAIN -> {
                if (rng.nextInt(4) == 0) {
                    weather = Weather.STORM;
                    weatherUntil = t + 1500 + rng.nextInt(1500);
                } else {
                    weather = Weather.CLEAR;
                    weatherUntil = t + 6000 + rng.nextInt(14000);
                }
            }
            case STORM -> {
                weather = Weather.RAIN;
                weatherUntil = t + 1000 + rng.nextInt(2000);
            }
        }
        if (prev != weather)
            core.bus().publish(t, EventType.WEATHER_CHANGED, GameEvent.Severity.INFO, null, null,
                    switch (weather) {
                        case CLEAR -> "O céu abriu.";
                        case RAIN -> "Começou a chover.";
                        case STORM -> "Tempestade!";
                    }, Map.of("weather", weather.name()));
    }

    // ------------------------------------------------------------------ desgaste

    /** Taxa-base de perda de inteireza por tick: madeira ~10 dias, pedra ~40 dias (1 dia = 24000 ticks). */
    private static double baseRate(BlockType b) {
        if (b == Blocks.RUBBLE || b == Blocks.MOSS) return 100.0 / (6 * 24000);
        if (b == Blocks.FARMLAND || b == Blocks.DIRT_PATH) return 100.0 / (8 * 24000);
        if (b == Blocks.GLASS || b == Blocks.OAK_DOOR || b == Blocks.RED_BED || b == Blocks.BANNER || b == Blocks.LADDER) return 100.0 / (8 * 24000);
        String id = b.id();
        if (id.contains("plank") || id.contains("log") || id.contains("fence") || id.contains("hay") || id.contains("bookshelf") || b == Blocks.CRAFTING
                || b == Blocks.CHEST || b == Blocks.BARREL || b == Blocks.LECTERN) return 100.0 / (10 * 24000);
        return 100.0 / (40 * 24000);
    }

    private void decayStep(long t) {
        if (cursor >= pass.length) {
            pass = world.integrity().keySet().toArray(new Pos[0]);
            cursor = 0;
            passTicks = Math.max(20, t - passStart);
            passStart = t;
            if (pass.length == 0) return;
        }
        // um passe dura ~100 ticks (se couber no orçamento); dt do desgaste = duração real do passe anterior
        int budget = Math.min(400, Math.max(1, (pass.length + 99) / 100));
        for (int i = 0; i < budget && cursor < pass.length; i++, cursor++) {
            Pos p = pass[cursor];
            Float cur = world.integrity().get(p);
            if (cur == null) continue;
            BlockType b = world.get(p);
            if (b.air() || b.fluid()) {
                world.integrity().remove(p);
                continue;
            }
            double rate = baseRate(b) * passTicks * decayScale;
            boolean exposed = skyExposed(p);
            double wet = exposed ? 1 + 2.0 * rain() : 1;
            rate *= exposed ? wet : 0.45;
            if (b.leaves()) continue;
            float next = (float) (cur - rate);
            if (next <= 0) {
                wear(p, b);
                continue;
            }
            world.integrity().put(p, next);
            if (next < 70) weathered(p, b);
        }
    }

    private boolean skyExposed(Pos p) {
        for (int y = p.y() + 1; y < Chunk.HEIGHT; y++) {
            BlockType t = world.get(p.x(), y, p.z());
            if (t.solid() && !t.leaves()) return false;
        }
        return true;
    }

    private static BlockType worn(BlockType b) {
        if (b.id().endsWith("_planks") && b != Blocks.WEATHERED_PLANKS) return Blocks.WEATHERED_PLANKS;
        if (b == Blocks.COBBLE) return Blocks.MOSSY_COBBLE;
        if (b == Blocks.STONE_BRICKS) return Blocks.CRACKED_STONE_BRICKS;
        if (b == Blocks.BRICKS) return Blocks.CRACKED_BRICKS;
        return null;
    }

    private void weathered(Pos p, BlockType b) {
        BlockType w = worn(b);
        if (w == null) return;
        world.keepIntegrity = true;
        world.set(p, w);
        world.keepIntegrity = false;
        recordDamage(p);
    }

    /** Integridade zerou: o bloco cede. Pedra vira entulho; madeira e o resto somem; o que estava em cima pode cair. */
    private void wear(Pos p, BlockType b) {
        world.integrity().remove(p);
        if (b == Blocks.RUBBLE) {
            world.set(p, Blocks.MOSS);
            world.markBuilt(p);
            return;
        }
        if (b == Blocks.MOSS) {
            world.set(p, Blocks.GRASS);
            Pos up = p.offset(0, 1, 0);
            if (world.get(up).air() && rng.nextInt(3) == 0) world.set(up, Blocks.BUSH);
            return;
        }
        if (b == Blocks.FARMLAND || b == Blocks.DIRT_PATH) {
            world.set(p, Blocks.DIRT);
            return;
        }
        recordDamage(p);
        boolean stone = b.tool() != null && b.tool().equals("pickaxe");
        world.set(p, stone && rng.nextInt(10) < 7 ? Blocks.RUBBLE : Blocks.AIR);
        if (world.get(p) == Blocks.RUBBLE) world.markBuilt(p);
        supportChecks.add(p);
    }

    // ------------------------------------------------------------------ danos por obra

    private Building buildingAt(Pos p) {
        for (Building b : core.state().buildings.values()) {
            if (b.origin == null || b.origin.y() == Integer.MIN_VALUE || b.status == Building.Status.ABANDONED) continue;
            Blueprint bp = b.blueprint();
            if (bp == null) continue;
            int dx = p.x() - b.origin.x(), dy = p.y() - b.origin.y(), dz = p.z() - b.origin.z();
            if (dx >= 0 && dx < bp.sizeX() && dz >= 0 && dz < bp.sizeZ() && dy >= -1 && dy <= bp.sizeY() + 2) return b;
        }
        return null;
    }

    private static long local(int dx, int dy, int dz) {
        return ((long) (dx + 64) << 24) | ((long) (dy + 8) << 12) | (dz + 64);
    }

    /** O bloco que a planta pede nesta posição, ou null. */
    public BlockType expected(Building b, Pos p) {
        Blueprint bp = b.blueprint();
        Map<Long, Blueprint.Placement> idx = placementIndex.computeIfAbsent(b.id, k -> {
            Map<Long, Blueprint.Placement> m = new HashMap<>();
            for (Blueprint.Placement pl : bp.placements()) m.put(local(pl.x(), pl.y(), pl.z()), pl);
            return m;
        });
        Blueprint.Placement pl = idx.get(local(p.x() - b.origin.x(), p.y() - b.origin.y(), p.z() - b.origin.z()));
        if (pl == null || pl.material() == Material.AIR) return null;
        return Palette.block(pl, bp, b.variant);
    }

    private void recordDamage(Pos p) {
        Building b = buildingAt(p);
        if (b == null) return;
        BlockType exp = expected(b, p);
        if (exp == null) return;
        Set<Pos> set = damage.computeIfAbsent(b.id, k -> new LinkedHashSet<>());
        boolean first = set.isEmpty();
        set.add(p);
        if (first && warned.add(b.id)) {
            core.bus().publish(core.tick(), EventType.BUILDING_DAMAGED, GameEvent.Severity.WARN, b.kingdomId, null,
                    b.blueprint().displayName() + " está se deteriorando.", Map.of("building", b.id.toString()));
        }
        checkRuin(b, set);
    }

    private void checkRuin(Building b, Set<Pos> set) {
        if (b.status != Building.Status.COMPLETE) return;
        int total = b.blueprint().solidTotal();
        if (total <= 0) return;
        int gone = 0;
        for (Pos q : set) {
            BlockType now = world.get(q);
            if (now.air() || now == Blocks.RUBBLE || now == Blocks.MOSS || now == Blocks.FIRE) gone++;
        }
        if (gone < total * 0.6) return;
        b.status = Building.Status.ABANDONED;
        for (UUID id : b.residents) {
            Npc n = core.npc(id);
            if (n != null && b.id.equals(n.homeId)) n.homeId = null;
        }
        b.residents.clear();
        damage.remove(b.id);
        core.bus().publish(core.tick(), EventType.BUILDING_RUINED, GameEvent.Severity.DANGER, b.kingdomId, null,
                b.blueprint().displayName() + " virou ruína.", Map.of("building", b.id.toString()));
    }

    /** Chamado pela equipe de reparo quando a obra está inteira de novo. */
    public void repaired(Building b) {
        damage.remove(b.id);
        warned.remove(b.id);
        core.bus().publish(core.tick(), EventType.BUILDING_REPAIRED, GameEvent.Severity.GOOD, b.kingdomId, null,
                b.blueprint().displayName() + " foi consertada.", Map.of("building", b.id.toString()));
    }

    // ------------------------------------------------------------------ desabamento

    private void supportStep() {
        int budget = 6;
        while (budget-- > 0 && !supportChecks.isEmpty()) {
            Pos p = supportChecks.poll();
            for (int[] d : new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}}) {
                Pos n = p.offset(d[0], d[1], d[2]);
                BlockType t = world.get(n);
                if (t.solid() && world.isBuilt(n)) collapseIfUnsupported(n);
            }
        }
    }

    /** Percorre o que está ligado a n; se nada daquilo toca o chão, tudo cai. */
    private void collapseIfUnsupported(Pos start) {
        Set<Pos> seen = new LinkedHashSet<>();
        ArrayDeque<Pos> queue = new ArrayDeque<>();
        queue.add(start);
        seen.add(start);
        while (!queue.isEmpty()) {
            Pos c = queue.poll();
            for (int[] d : new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}}) {
                Pos n = c.offset(d[0], d[1], d[2]);
                if (seen.contains(n)) continue;
                if (n.y() <= 0) return;
                BlockType t = world.get(n);
                if (!t.solid()) continue;
                if (!world.isBuilt(n)) return; // tocou terreno natural: está firme
                seen.add(n);
                if (seen.size() > 600) return; // estrutura enorme: assume firme
                queue.add(n);
            }
        }
        List<Pos> group = new ArrayList<>(seen);
        group.sort(Comparator.comparingInt(Pos::y));
        for (Pos g : group) {
            BlockType b = world.get(g);
            world.set(g, Blocks.AIR);
            recordDamage(g);
            int y = g.y();
            while (y > 1 && !world.get(g.x(), y - 1, g.z()).solid()) y--;
            if (rng.nextInt(10) < 6 && world.get(g.x(), y, g.z()).air() && (b.tool() != null && b.tool().equals("pickaxe") || rng.nextBoolean())) {
                world.set(g.x(), y, g.z(), Blocks.RUBBLE);
                world.markBuilt(new Pos(g.x(), y, g.z()));
            }
        }
        core.bus().publish(core.tick(), EventType.BUILDING_DAMAGED, GameEvent.Severity.WARN, null, null,
                "Uma estrutura desabou (" + group.size() + " blocos).", Map.of("blocks", String.valueOf(group.size())));
    }

    // ------------------------------------------------------------------ fogo

    private static boolean flammable(BlockType b) {
        if (b.air() || b.fluid()) return false;
        String id = b.id();
        return b.leaves() || b.log() || id.contains("plank") || id.contains("fence") || id.contains("door") || id.contains("hay")
                || id.contains("bookshelf") || id.contains("bed") || id.contains("ladder") || id.contains("wheat") || id.contains("banner")
                || b == Blocks.CRAFTING || b == Blocks.CHEST || b == Blocks.BARREL || b == Blocks.LECTERN || b == Blocks.BUSH || b == Blocks.OAK_SAPLING;
    }

    private static final int[][] DIRS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    /** Acende fogo numa célula de ar vizinha de algo inflamável. */
    public boolean ignite(Pos at) {
        BlockType cur = world.get(at);
        if (cur == Blocks.FIRE || fires.containsKey(at)) return false;
        if (!cur.air() && cur.solid()) {
            // acende o bloco inflamável em si
            if (!flammable(cur)) return false;
        } else if (!cur.air() && !cur.fluid() && !flammable(cur)) return false;
        else if (cur.fluid()) return false;
        boolean fuel = flammable(cur);
        for (int[] d : DIRS) if (flammable(world.get(at.offset(d[0], d[1], d[2])))) fuel = true;
        if (!fuel) return false;
        world.set(at, Blocks.FIRE);
        fires.put(at, 0);
        return true;
    }

    private void fireStep() {
        if (fires.isEmpty()) return;
        List<Pos> list = new ArrayList<>(fires.keySet());
        for (Pos p : list) {
            if (world.get(p) != Blocks.FIRE) {
                fires.remove(p);
                continue;
            }
            int age = fires.merge(p, 1, Integer::sum);
            boolean water = false;
            for (int[] d : DIRS) if (world.get(p.offset(d[0], d[1], d[2])).fluid()) water = true;
            if (water || rng.nextDouble() < rain() * 0.12 && skyExposed(p) || age > 90 + rng.nextInt(40)) {
                out(p);
                continue;
            }
            boolean fuel = false;
            for (int[] d : DIRS) {
                Pos n = p.offset(d[0], d[1], d[2]);
                BlockType nb = world.get(n);
                if (flammable(nb)) {
                    fuel = true;
                    if (rng.nextDouble() < 0.07 * (nb.leaves() ? 2 : 1) * (1 - rain() * 0.7)) {
                        world.set(n, Blocks.FIRE);
                        fires.put(n, 0);
                        recordDamage(n);
                        supportChecks.add(n);
                    }
                } else if (nb.air() && d[1] >= 0 && rng.nextDouble() < 0.04 * (1 - rain() * 0.7)) {
                    ignite(n);
                }
            }
            if (!fuel && age > 12) out(p);
            hurtNear(p);
        }
    }

    private void out(Pos p) {
        fires.remove(p);
        if (world.get(p) == Blocks.FIRE) world.set(p, Blocks.AIR);
    }

    private void hurtNear(Pos fire) {
        if (core.tick() % 20 != 0) return;
        for (Npc n : core.allAlive()) {
            if (n.pos == null || n.pos.distXZ(fire) > 1.6 || Math.abs(n.pos.y() - fire.y()) > 2) continue;
            core.life().onHurt(n.id, Math.max(0, n.health - 9), "fire", null, fire);
        }
    }

    private void lightning() {
        List<Pos> views = new ArrayList<>(area.viewers());
        if (views.isEmpty()) return;
        Pos v = views.get(rng.nextInt(views.size()));
        int x = v.x() + rng.nextInt(121) - 60, z = v.z() + rng.nextInt(121) - 60;
        if (!area.loaded(x, z)) return;
        int y = world.topSolid(x, z);
        Pos strike = new Pos(x, y + 1, z);
        if (ignite(strike) || ignite(new Pos(x, y, z))) {
            core.bus().publish(core.tick(), EventType.FIRE_STARTED, GameEvent.Severity.WARN, null, null,
                    "Um raio incendiou algo perto de (" + x + ", " + z + ").", Map.of("x", String.valueOf(x), "z", String.valueOf(z)));
        }
    }

    // ------------------------------------------------------------------ explosão

    /** Explosão esférica: resistência = dureza; o que sobra instável desaba; gente por perto se fere. */
    public int explode(Pos c, int radius, double power) {
        int destroyed = 0;
        for (int dx = -radius; dx <= radius; dx++)
            for (int dy = -radius; dy <= radius; dy++)
                for (int dz = -radius; dz <= radius; dz++) {
                    double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (d > radius) continue;
                    Pos p = c.offset(dx, dy, dz);
                    BlockType b = world.get(p);
                    if (!b.breakable()) continue;
                    double chance = power * (1 - d / (radius + 0.5)) / (0.4 + Math.max(0, b.hardness()) * 0.35);
                    if (rng.nextDouble() < chance) {
                        recordDamage(p);
                        boolean stone = b.tool() != null && b.tool().equals("pickaxe");
                        world.set(p, Blocks.AIR);
                        if (stone && rng.nextInt(6) == 0) {
                            world.set(p, Blocks.RUBBLE);
                            world.markBuilt(p);
                        }
                        destroyed++;
                        if (flammable(b) && rng.nextInt(5) == 0) ignite(p);
                        supportChecks.add(p);
                    }
                }
        for (Npc n : core.allAlive()) {
            if (n.pos == null) continue;
            double d = Math.sqrt(n.pos.distSq(c));
            if (d <= radius * 1.6) core.life().onHurt(n.id, Math.max(0, n.health - 60 * power * (1 - d / (radius * 1.6 + 1))), "explosion", null, c);
        }
        core.bus().publish(core.tick(), EventType.EXPLOSION, GameEvent.Severity.WARN, null, null,
                "Explosão em (" + c.x() + ", " + c.y() + ", " + c.z() + "): " + destroyed + " blocos.", Map.of("blocks", String.valueOf(destroyed)));
        return destroyed;
    }
}
