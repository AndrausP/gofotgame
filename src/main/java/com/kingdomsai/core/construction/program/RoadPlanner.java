package com.kingdomsai.core.construction.program;

import com.kingdomsai.core.KingdomsCore;
import com.kingdomsai.core.common.Pos;
import com.kingdomsai.core.construction.Blueprint;
import com.kingdomsai.core.construction.Blueprint.Facing;
import com.kingdomsai.core.construction.Blueprint.Placement;
import com.kingdomsai.core.construction.Building;
import com.kingdomsai.core.construction.Material;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.kingdom.ResourceType;

import java.util.*;

/** Estrada entre dois pontos: A* sobre a superfície (evita obras, custa subir/descer), ponte de tábuas sobre a água; dividida em lotes. */
public final class RoadPlanner {
    public static final String SOURCE = "road";
    public static final int LOT = 24, MAX_SPAN = 160;

    private RoadPlanner() {}

    public static List<int[]> path(KingdomsCore core, Kingdom k, Pos a, Pos b) {
        if (a.distXZ(b) > MAX_SPAN) return List.of();
        Set<Long> blocked = new HashSet<>();
        for (Building bd : core.buildings(k.id)) {
            Blueprint bp = bd.blueprint();
            if (bp == null || bd.origin == null || SOURCE.equals(bp.source()) || com.kingdomsai.core.construction.VillageWall.isWall(bp)) continue;
            for (int x = bd.origin.x() - 1; x <= bd.origin.x() + bp.sizeX(); x++)
                for (int z = bd.origin.z() - 1; z <= bd.origin.z() + bp.sizeZ(); z++) blocked.add(key(x, z));
        }
        blocked.remove(key(a.x(), a.z()));
        blocked.remove(key(b.x(), b.z()));
        record Node(int x, int z, double g, double f, Node from) {}
        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::f));
        Map<Long, Double> best = new HashMap<>();
        open.add(new Node(a.x(), a.z(), 0, a.distXZ(b), null));
        best.put(key(a.x(), a.z()), 0.0);
        int guard = 0;
        while (!open.isEmpty() && guard++ < 40000) {
            Node n = open.poll();
            if (n.x == b.x() && n.z == b.z()) {
                LinkedList<int[]> out = new LinkedList<>();
                for (Node c = n; c != null; c = c.from) out.addFirst(new int[]{c.x, c.z});
                return out;
            }
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int x = n.x + d[0], z = n.z + d[1];
                long kk = key(x, z);
                if (blocked.contains(kk) || Math.abs(x - a.x()) > MAX_SPAN || Math.abs(z - a.z()) > MAX_SPAN) continue;
                int y = core.world().surfaceY(x, z), py = core.world().surfaceY(n.x, n.z);
                if (y == Integer.MIN_VALUE || py == Integer.MIN_VALUE) continue;
                double g = n.g + 1 + Math.abs(y - py) * 3 + (water(core, x, y, z) ? 4 : 0);
                if (g >= best.getOrDefault(kk, Double.MAX_VALUE)) continue;
                best.put(kk, g);
                open.add(new Node(x, z, g, g + Math.abs(x - b.x()) + Math.abs(z - b.z()), n));
            }
        }
        return List.of();
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static boolean water(KingdomsCore core, int x, int y, int z) {
        var info = core.physical() == null ? null : core.physical().block(new Pos(x, y, z));
        return info != null && info.id() != null && info.id().contains("water");
    }

    /** Planta de um trecho do caminho (lote). */
    static Blueprint lot(KingdomsCore core, List<int[]> cells, int index) {
        int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (int[] c : cells) {
            x0 = Math.min(x0, c[0]);
            z0 = Math.min(z0, c[1]);
            x1 = Math.max(x1, c[0]);
            z1 = Math.max(z1, c[1]);
        }
        int baseY = core.world().surfaceY(cells.get(0)[0], cells.get(0)[1]);
        List<Placement> p = new ArrayList<>();
        int bridges = 0;
        for (int[] c : cells) {
            int y = core.world().surfaceY(c[0], c[1]);
            boolean wet = water(core, c[0], y, c[1]);
            int dy = Math.max(-8, Math.min(8, y - baseY));
            p.add(new Placement(c[0] - x0, dy - 1, c[1] - z0, wet ? Material.FLOOR : Material.PATH, Facing.NONE));
            if (wet) bridges++;
        }
        Map<ResourceType, Integer> cost = new EnumMap<>(ResourceType.class);
        cost.put(ResourceType.STONE, Math.max(2, cells.size() / 4));
        if (bridges > 0) cost.put(ResourceType.WOOD, bridges);
        String id = "estrada_" + Integer.toHexString(Objects.hash(x0, z0, x1, z1, index, cells.size()));
        return new Blueprint(id, "Estrada (trecho " + (index + 1) + ")", Blueprint.Category.CIVIC, x1 - x0 + 1, 3, z1 - z0 + 1, Collections.unmodifiableMap(cost), 0,
                List.copyOf(p), SOURCE, Map.of());
    }

    /** Abre a estrada em lotes (cada um é uma obra: vários construtores trabalham juntos). Retorna as obras. */
    public static List<Building> build(KingdomsCore core, Kingdom k, Pos a, Pos b) {
        List<int[]> path = path(core, k, a, b);
        List<Building> made = new ArrayList<>();
        for (int i = 0, n = 0; i < path.size(); i += LOT, n++) {
            List<int[]> cells = path.subList(i, Math.min(path.size(), i + LOT));
            Blueprint bp = lot(core, cells, n);
            boolean ok = true;
            for (var e : bp.cost().entrySet()) if (k.get(e.getKey()) < e.getValue()) ok = false;
            if (!ok) break;
            core.registerSaved(bp);
            int baseY = core.world().surfaceY(cells.get(0)[0], cells.get(0)[1]);
            int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE;
            for (int[] c : cells) {
                x0 = Math.min(x0, c[0]);
                z0 = Math.min(z0, c[1]);
            }
            Building bd = core.construction().planAt(k, bp, new Pos(x0, baseY, z0), true);
            if (bd == null) continue;
            k.pay(bp.cost());
            made.add(bd);
        }
        return made;
    }
}
