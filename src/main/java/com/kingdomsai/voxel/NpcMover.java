package com.kingdomsai.voxel;

import com.kingdomsai.core.KingdomsCore;
import com.kingdomsai.core.ai.NpcScheduler;
import com.kingdomsai.core.common.Pos;
import com.kingdomsai.core.npc.Npc;
import com.kingdomsai.core.npc.NpcActivity;
import com.kingdomsai.core.port.PhysicalPort;

import java.util.*;

/**
 * Corpo dos NPCs no mundo voxel (o NpcMaterializer + NpcRoutineGoal do mod): quem está na área carregada ganha corpo,
 * recebe do Core o que fazer e onde (NpcScheduler) e anda até lá por um caminho de verdade sobre os blocos.
 * Fora da área volta a ser só números e o Core o move de forma abstrata.
 */
public final class NpcMover {
    /** Estado físico de um NPC com corpo. */
    public static final class Walker {
        public double x, y, z;
        public double yaw;
        Pos target;
        double radius = 4;
        List<int[]> path = List.of();
        int pathIdx;
        int recalc;
        int stuck;
        double lastDist = Double.MAX_VALUE;
        long pathAt;
    }

    public static final int MAX_ACTIVE = 400;
    private static final int MAX_NODES = 2500;

    private final KingdomsCore core;
    private final VoxelWorld world;
    private final LoadArea area;
    private final VoxelPhysicalPort port;
    private final Map<UUID, Walker> walkers = new HashMap<>();
    private final Map<UUID, Pos> errands = new HashMap<>();
    private final Random rng = new Random(7);

    public NpcMover(KingdomsCore core, VoxelWorld world, LoadArea area, VoxelPhysicalPort port) {
        this.core = core;
        this.world = world;
        this.area = area;
        this.port = port;
    }

    public Walker walker(UUID id) {
        return walkers.get(id);
    }

    /** Missão que passa na frente da rotina (reparo): vai até lá e fica. */
    public void setErrand(UUID npc, Pos at) {
        errands.put(npc, at);
    }

    public void clearErrand(UUID npc) {
        if (errands.remove(npc) != null) {
            Walker w = walkers.get(npc);
            if (w != null) w.recalc = 0;
        }
    }

    public boolean onErrand(UUID npc) {
        return errands.containsKey(npc);
    }

    public int active() {
        return walkers.size();
    }

    // ------------------------------------------------------------------ chão

    private boolean passable(int x, int y, int z) {
        if (y < 0 || y >= Chunk.HEIGHT) return y >= Chunk.HEIGHT;
        BlockType t = world.get(x, y, z);
        return !t.solid() && !t.fluid();
    }

    public boolean standable(int x, int y, int z) {
        if (y < 1) return false;
        BlockType below = world.get(x, y - 1, z);
        if (!below.solid()) return false;
        return passable(x, y, z) && passable(x, y + 1, z);
    }

    /** Altura em que se pode ficar de pé perto de y nesta coluna, ou -1. */
    int standY(int x, int z, int nearY) {
        for (int dy : new int[]{0, 1, -1, -2, -3, 2, -4}) if (standable(x, nearY + dy, z)) return nearY + dy;
        int g = world.groundY(x, z);
        return standable(x, g, z) ? g : -1;
    }

    // ------------------------------------------------------------------ ciclo

    public void tick() {
        long tick = core.tick();
        if (tick % 20 == 0) rematerialize();
        long dayTime = (tick + 6000) % 24000L;
        for (var en : walkers.entrySet()) {
            Npc n = core.npc(en.getKey());
            if (n == null || !n.alive) continue;
            step(n, en.getValue(), tick, dayTime);
        }
    }

    private void rematerialize() {
        int count = walkers.size();
        for (Npc n : core.allAlive()) {
            if (n.pos == null) continue;
            Walker w = walkers.get(n.id);
            boolean near = area.loaded(n.pos.x(), n.pos.z());
            if (w != null && !near) {
                n.pos = new Pos((int) Math.floor(w.x), (int) Math.round(w.y), (int) Math.floor(w.z));
                n.materialized = false;
                walkers.remove(n.id);
                count--;
            } else if (w == null && near && count < MAX_ACTIVE) {
                int y = standY(n.pos.x(), n.pos.z(), n.pos.y() == Integer.MIN_VALUE ? 70 : n.pos.y());
                if (y < 0) continue;
                Walker nw = new Walker();
                nw.x = n.pos.x() + 0.5;
                nw.z = n.pos.z() + 0.5;
                nw.y = y;
                nw.yaw = rng.nextDouble() * Math.PI * 2;
                walkers.put(n.id, nw);
                n.materialized = true;
                n.pos = new Pos(n.pos.x(), y, n.pos.z());
                count++;
            }
        }
        for (Iterator<UUID> it = walkers.keySet().iterator(); it.hasNext(); ) {
            UUID id = it.next();
            Npc n = core.npc(id);
            if (n == null || !n.alive) {
                if (n != null) n.materialized = false;
                it.remove();
            }
        }
    }

    private void step(Npc n, Walker w, long tick, long dayTime) {
        if (n.activity == NpcActivity.TALKING || n.talkingWith != null && n.activity == NpcActivity.TALKING) return;
        Pos errand = errands.get(n.id);
        if (errand != null) {
            if (w.target == null || !w.target.equals(errand)) {
                w.target = errand;
                w.path = List.of();
            }
            w.radius = 2.5;
            n.activity = NpcActivity.WORK;
        } else if (--w.recalc <= 0) {
            w.recalc = n.activity == NpcActivity.SUMMONED || n.activity == NpcActivity.MARCH || n.activity == NpcActivity.TRAIN ? 20 : 40 + rng.nextInt(40);
            NpcScheduler.Intent in = core.scheduler().decide(n, dayTime);
            if (n.activity != NpcActivity.TALKING) n.activity = in.activity();
            Pos t = in.target();
            if (t != null) {
                double jitter = Math.max(1, in.radius());
                int x = t.x() + (int) Math.round((rng.nextDouble() * 2 - 1) * jitter);
                int z = t.z() + (int) Math.round((rng.nextDouble() * 2 - 1) * jitter);
                if (!area.loaded(x, z)) {
                    double dx = x - w.x, dz = z - w.z, len = Math.max(1, Math.hypot(dx, dz));
                    x = (int) Math.round(w.x + dx / len * Math.min(24, len));
                    z = (int) Math.round(w.z + dz / len * Math.min(24, len));
                }
                if (area.loaded(x, z)) {
                    int y = t.y() == Integer.MIN_VALUE ? (int) w.y : t.y();
                    int sy = standY(x, z, Math.max(1, Math.min(Chunk.HEIGHT - 3, y)));
                    Pos goal = new Pos(x, sy < 0 ? world.groundY(x, z) : sy, z);
                    if (w.target == null || !w.target.equals(goal)) {
                        w.target = goal;
                        w.path = List.of();
                    }
                    w.radius = in.radius();
                }
            }
        }
        if (w.target == null) {
            sync(n, w);
            return;
        }
        // conversa: para perto do outro
        if (n.activity == NpcActivity.CHAT && n.talkingWith != null) {
            Npc other = core.npc(n.talkingWith);
            if (other != null && other.pos != null && Math.hypot(other.pos.x() + 0.5 - w.x, other.pos.z() + 0.5 - w.z) <= 3) {
                face(w, other.pos.x() + 0.5, other.pos.z() + 0.5);
                sync(n, w);
                return;
            }
        }
        double dist = Math.hypot(w.target.x() + 0.5 - w.x, w.target.z() + 0.5 - w.z);
        if (dist > w.radius + 1 || Math.abs(w.target.y() - w.y) > 4) {
            if ((w.pathIdx >= w.path.size() && tick - w.pathAt >= 10) || tick - w.pathAt > 120) plan(w);
            double bps = speed(n);
            advance(w, bps / 20.0);
            if (dist > w.lastDist - 0.02) w.stuck++;
            else w.stuck = 0;
            w.lastDist = dist;
            if (w.stuck > 600 && dist > 6) { // anti-travamento: se ninguém vê, vai para perto do destino
                boolean seen = false;
                for (Pos v : area.viewers()) if (Math.hypot(v.x() - w.x, v.z() - w.z) < 40) seen = true;
                if (seen) {
                    w.recalc = 0;
                    w.path = List.of();
                    w.stuck = 400;
                } else {
                    w.x = w.target.x() + 0.5;
                    w.z = w.target.z() + 0.5;
                    w.y = w.target.y();
                    w.stuck = 0;
                }
            }
        } else {
            w.stuck = 0;
            w.lastDist = dist;
            w.path = List.of();
            face(w, w.target.x() + 0.5, w.target.z() + 0.5);
            if (n.activity == NpcActivity.WORK && tick % 30 == 0 && rng.nextInt(3) == 0)
                port.animate(n.id, w.target, PhysicalPort.Anim.SWING, 0);
        }
        sync(n, w);
    }

    private double speed(Npc n) {
        return switch (n.activity) {
            case FLEE -> 6.0;
            case SUMMONED -> 5.2;
            case MARCH -> 4.8;
            case TRAIN -> 4.5;
            case PATROL -> 3.6;
            case WANDER -> 2.6;
            default -> n.energy < 15 || n.health < 40 ? 2.4 : 3.4;
        };
    }

    private void face(Walker w, double tx, double tz) {
        w.yaw = Math.atan2(tx - w.x, tz - w.z);
    }

    private void sync(Npc n, Walker w) {
        n.pos = new Pos((int) Math.floor(w.x), (int) Math.round(w.y), (int) Math.floor(w.z));
    }

    // ------------------------------------------------------------------ movimento

    private void advance(Walker w, double dist) {
        while (dist > 1e-6 && w.pathIdx < w.path.size()) {
            int[] c = w.path.get(w.pathIdx);
            double tx = c[0] + 0.5, tz = c[2] + 0.5;
            double dx = tx - w.x, dz = tz - w.z, d = Math.hypot(dx, dz);
            if (d <= dist) {
                w.x = tx;
                w.z = tz;
                w.y = c[1];
                dist -= d;
                w.pathIdx++;
            } else {
                w.x += dx / d * dist;
                w.z += dz / d * dist;
                double ty = c[1];
                w.y += Math.max(-0.5, Math.min(0.5, ty - w.y)) * Math.min(1.0, dist * 2);
                w.yaw = Math.atan2(dx, dz);
                dist = 0;
            }
        }
    }

    // ------------------------------------------------------------------ A*

    private static long key(int x, int y, int z) {
        return (((long) (x + 8192) * 16384L + (z + 8192)) * 128L) + y;
    }

    private static final class Node {
        final int x, y, z;
        double g, f;
        Node parent;

        Node(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private void plan(Walker w) {
        w.pathAt = core.tick();
        w.pathIdx = 0;
        int sx = (int) Math.floor(w.x), sz = (int) Math.floor(w.z);
        int sy = standY(sx, sz, (int) Math.round(w.y));
        if (sy < 0) sy = (int) Math.round(w.y);
        Pos goal = w.target;
        int reach = (int) Math.max(1, Math.floor(w.radius));
        Map<Long, Node> seen = new HashMap<>();
        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(a -> a.f));
        Node start = new Node(sx, sy, sz);
        start.f = h(start, goal);
        seen.put(key(sx, sy, sz), start);
        open.add(start);
        Node best = start;
        int expanded = 0;
        Node found = null;
        while (!open.isEmpty() && expanded++ < MAX_NODES) {
            Node cur = open.poll();
            if (Math.abs(cur.x - goal.x()) <= reach && Math.abs(cur.z - goal.z()) <= reach && Math.abs(cur.y - goal.y()) <= 3) {
                found = cur;
                break;
            }
            if (h(cur, goal) < h(best, goal)) best = cur;
            for (int dz = -1; dz <= 1; dz++)
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dz == 0) continue;
                    int nx = cur.x + dx, nz = cur.z + dz;
                    if (!area.loaded(nx, nz)) continue;
                    if (dx != 0 && dz != 0 && !(passable(cur.x + dx, cur.y, cur.z) && passable(cur.x, cur.y, cur.z + dz)
                            && passable(cur.x + dx, cur.y + 1, cur.z) && passable(cur.x, cur.y + 1, cur.z + dz))) continue;
                    int ny = -1;
                    for (int dy : new int[]{0, 1, -1, -2, -3}) {
                        if (standable(nx, cur.y + dy, nz) && (dy <= 0 || passable(cur.x, cur.y + 2, cur.z))) {
                            ny = cur.y + dy;
                            break;
                        }
                    }
                    if (ny < 0) continue;
                    double g = cur.g + (dx != 0 && dz != 0 ? 1.414 : 1.0) + Math.abs(ny - cur.y) * 0.5;
                    long k = key(nx, ny, nz);
                    Node ex = seen.get(k);
                    if (ex != null && ex.g <= g) continue;
                    Node nn = ex != null ? ex : new Node(nx, ny, nz);
                    nn.g = g;
                    nn.parent = cur;
                    nn.f = g + h(nn, goal) * 1.1;
                    seen.put(k, nn);
                    open.add(nn);
                }
        }
        Node end = found != null ? found : best;
        LinkedList<int[]> out = new LinkedList<>();
        for (Node c = end; c != null && c != start; c = c.parent) out.addFirst(new int[]{c.x, c.y, c.z});
        w.path = out;
        if (out.isEmpty() && found == null) w.stuck += 30; // sem caminho: conta como travado
    }

    private static double h(Node n, Pos goal) {
        return Math.hypot(n.x - goal.x(), n.z - goal.z()) + Math.abs(n.y - goal.y()) * 0.5;
    }
}
