package com.kingdomsai.core.construction;

import com.kingdomsai.core.KingdomsCore;
import com.kingdomsai.core.common.Pos;
import com.kingdomsai.core.common.Text;
import com.kingdomsai.core.construction.Blueprint.Facing;
import com.kingdomsai.core.construction.Blueprint.Placement;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.kingdom.ResourceType;

import java.util.*;

/**
 * "Construa um muro ao redor da vila": o jogo sabe o tamanho da vila (área ocupada pelas construções + folga)
 * e gera uma muralha sob medida — anel de pedra que acompanha o relevo, com dois portões, ameias,
 * torres nos cantos e tochas. A muralha é um anel: construções dentro dela continuam permitidas.
 */
public final class VillageWall {
    public static final String SOURCE = "wall";
    public static final int MARGIN = 5;
    public static final int MIN_HALF = 10;
    public static final int MAX_SIDE = 128;

    private VillageWall() {}

    /** Retângulo da vila em coordenadas do mundo (inclusivo). */
    public record Bounds(int x0, int z0, int x1, int z1) {
        public int width() {
            return x1 - x0 + 1;
        }

        public int depth() {
            return z1 - z0 + 1;
        }

        public int perimeter() {
            return 2 * (width() + depth()) - 4;
        }

        /** Meia-largura maior: "raio" aproximado da vila. */
        public int radius() {
            return Math.max(width(), depth()) / 2;
        }

        public Pos center(int y) {
            return new Pos((x0 + x1) / 2, y, (z0 + z1) / 2);
        }

        public boolean contains(Pos p) {
            return p != null && p.x() >= x0 && p.x() <= x1 && p.z() >= z0 && p.z() <= z1;
        }
    }

    public static boolean isWall(Blueprint bp) {
        return bp != null && SOURCE.equals(bp.source());
    }

    /** Palavras que pedem a muralha da vila em vez de uma planta da biblioteca. */
    public static boolean isWallRequest(String blueprint) {
        if (blueprint == null) return false;
        return Text.norm(blueprint).matches("(muro|muros|muralha|muralhas|wall|walls|village_wall|palicada|fortificacao|fortaleza_muro)(_.*)?|muralha_\\d+");
    }

    /** Área ocupada pela vila: praça central + todas as construções do reino (menos muralhas), com folga. */
    public static Bounds bounds(KingdomsCore core, Kingdom k) {
        int x0 = k.center.x() - MIN_HALF, x1 = k.center.x() + MIN_HALF, z0 = k.center.z() - MIN_HALF, z1 = k.center.z() + MIN_HALF;
        for (Building b : core.buildings(k.id)) {
            Blueprint bp = b.blueprint();
            if (bp == null || isWall(bp) || b.origin == null) continue;
            x0 = Math.min(x0, b.origin.x() - MARGIN);
            z0 = Math.min(z0, b.origin.z() - MARGIN);
            x1 = Math.max(x1, b.origin.x() + bp.sizeX() - 1 + MARGIN);
            z1 = Math.max(z1, b.origin.z() + bp.sizeZ() - 1 + MARGIN);
        }
        // limite de segurança: vilas enormes ficam centradas no núcleo
        if (x1 - x0 + 1 > MAX_SIDE) {
            int c = (x0 + x1) / 2;
            x0 = c - MAX_SIDE / 2;
            x1 = x0 + MAX_SIDE - 1;
        }
        if (z1 - z0 + 1 > MAX_SIDE) {
            int c = (z0 + z1) / 2;
            z0 = c - MAX_SIDE / 2;
            z1 = z0 + MAX_SIDE - 1;
        }
        return new Bounds(x0, z0, x1, z1);
    }

    public static Building existing(KingdomsCore core, Kingdom k) {
        for (Building b : core.buildings(k.id)) if (isWall(b.blueprint())) return b;
        return null;
    }

    /** Uma linha para a LLM e para a CLI: "o NPC sabe o tamanho da vila". */
    public static String describe(KingdomsCore core, Kingdom k) {
        Bounds b = bounds(core, k);
        Building w = existing(core, k);
        return "Vila: área de " + b.width() + "×" + b.depth() + " blocos (x " + b.x0() + "…" + b.x1() + ", z " + b.z0() + "…" + b.z1()
                + "), cerca de " + b.radius() + " blocos do centro até a borda; muralha: "
                + (w == null ? "não tem" : w.isComplete() ? "pronta" : "em obra (" + (int) w.percent() + "%)") + ".";
    }

    /**
     * Gera a planta da muralha para a vila de hoje. Determinística para o mesmo mundo/tick
     * (o validador de recursos e a execução geram a mesma planta).
     */
    public static Blueprint generate(KingdomsCore core, Kingdom k, int height) {
        Bounds bb = bounds(core, k);
        int w = bb.width(), d = bb.depth();
        int baseY = core.world().surfaceY(k.center.x(), k.center.z());
        if (baseY == Integer.MIN_VALUE) baseY = k.center.y();

        // anel em ordem (sentido horário a partir do canto noroeste), com a altura do chão de cada coluna
        List<int[]> ring = new ArrayList<>();
        for (int x = 0; x < w; x++) ring.add(new int[]{x, 0});
        for (int z = 1; z < d; z++) ring.add(new int[]{w - 1, z});
        for (int x = w - 2; x >= 0; x--) ring.add(new int[]{x, d - 1});
        for (int z = d - 2; z >= 1; z--) ring.add(new int[]{0, z});
        int[] dy = new int[ring.size()];
        for (int i = 0; i < ring.size(); i++) {
            int sy = core.world().surfaceY(bb.x0() + ring.get(i)[0], bb.z0() + ring.get(i)[1]);
            dy[i] = sy == Integer.MIN_VALUE ? 0 : Text.clamp(sy - baseY, -8, 8);
        }

        List<Placement> p = new ArrayList<>();
        // 1) limpa o caminho (só vegetação/terra natural: o executor nunca derruba o que o jogador fez)
        for (int i = 0; i < ring.size(); i++)
            for (int y = 0; y <= height + 1; y++) p.add(new Placement(ring.get(i)[0], dy[i] + y, ring.get(i)[1], Material.AIR, Facing.NONE));
        // 2) fundação (portões viram caminho)
        for (int i = 0; i < ring.size(); i++) {
            int[] c = ring.get(i);
            p.add(new Placement(c[0], dy[i] - 1, c[1], gate(c, w, d) ? Material.PATH : Material.FOUNDATION, Facing.NONE));
        }
        // 3) a muralha sobe camada por camada, em volta da vila
        for (int y = 0; y < height; y++)
            for (int i = 0; i < ring.size(); i++) {
                int[] c = ring.get(i);
                if (gate(c, w, d) && y < 3) continue; // vão do portão (3 de altura)
                p.add(new Placement(c[0], dy[i] + y, c[1], corner(c, w, d) ? Material.PILLAR : Material.WALL, Facing.NONE));
            }
        // 4) ameias, torres nos cantos e tochas
        for (int i = 0; i < ring.size(); i++) {
            int[] c = ring.get(i);
            if (corner(c, w, d)) {
                p.add(new Placement(c[0], dy[i] + height, c[1], Material.PILLAR, Facing.NONE));
                p.add(new Placement(c[0], dy[i] + height + 1, c[1], Material.LIGHT, Facing.NONE));
            } else if (i % 2 == 0) {
                p.add(new Placement(c[0], dy[i] + height, c[1], Material.ROOF_EDGE, Facing.NONE));
                if (i % 8 == 4) p.add(new Placement(c[0], dy[i] + height + 1, c[1], Material.LIGHT, Facing.NONE));
            }
        }
        int solid = (int) p.stream().filter(x -> x.material() != Material.AIR && x.material() != Material.LIGHT).count();
        Map<ResourceType, Integer> cost = new EnumMap<>(ResourceType.class);
        cost.put(ResourceType.STONE, Math.max(20, solid / 4));
        cost.put(ResourceType.WOOD, 10);
        Map<Material, String> mats = new EnumMap<>(Material.class);
        mats.put(Material.WALL, "minecraft:stone_bricks");
        mats.put(Material.FOUNDATION, "minecraft:cobblestone");
        mats.put(Material.PILLAR, "minecraft:chiseled_stone_bricks");
        mats.put(Material.ROOF_EDGE, "minecraft:stone_brick_wall");
        int n = 1;
        for (Blueprint saved : core.state().savedBlueprints) if (isWall(saved)) n++;
        return new Blueprint("muralha_" + n, "Muralha da vila (" + w + "×" + d + ")", Blueprint.Category.MILITARY, w, height + 2, d,
                Collections.unmodifiableMap(cost), 0, List.copyOf(p), SOURCE, Collections.unmodifiableMap(mats));
    }

    /** Origem da muralha no mundo (quina noroeste, y do centro da vila). */
    public static Pos origin(KingdomsCore core, Kingdom k) {
        Bounds bb = bounds(core, k);
        int baseY = core.world().surfaceY(k.center.x(), k.center.z());
        if (baseY == Integer.MIN_VALUE) baseY = k.center.y();
        return new Pos(bb.x0(), baseY, bb.z0());
    }

    private static boolean gate(int[] c, int w, int d) {
        return (c[1] == 0 || c[1] == d - 1) && Math.abs(c[0] - w / 2) <= 1;
    }

    private static boolean corner(int[] c, int w, int d) {
        return (c[0] == 0 || c[0] == w - 1) && (c[1] == 0 || c[1] == d - 1);
    }
}
