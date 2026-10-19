package com.kingdomsai.core.construction;

import com.kingdomsai.core.common.Text;
import com.kingdomsai.core.construction.Blueprint.Category;
import com.kingdomsai.core.construction.Blueprint.Facing;
import com.kingdomsai.core.construction.Blueprint.Placement;
import com.kingdomsai.core.kingdom.ResourceType;

import java.util.*;

/** Plantas geradas proceduralmente em código (sem arquivos .nbt): fáceis de versionar e de variar. */
public final class BlueprintLibrary {
    private static final Map<String, Blueprint> BY_ID = new LinkedHashMap<>();
    /** Plantas criadas no mundo (paramétricas, salvas, importadas). Recarregadas a cada mundo aberto. */
    private static final Map<String, Blueprint> CUSTOM = new LinkedHashMap<>();
    private static final Map<String, int[]> SOLID_PREFIX = new HashMap<>();

    static {
        register(house("house_small", "Casa pequena", Category.HOUSING, 5, 5, 3, 1,
                cost(ResourceType.WOOD, 30, ResourceType.STONE, 10), 3, false, false));
        register(house("house_medium", "Casa média", Category.HOUSING, 7, 7, 4, 2,
                cost(ResourceType.WOOD, 55, ResourceType.STONE, 25), 5, false, false));
        register(house("barracks", "Quartel", Category.MILITARY, 9, 7, 4, 4,
                cost(ResourceType.WOOD, 60, ResourceType.STONE, 40, ResourceType.IRON, 5), 4, true, false));
        register(house("smithy", "Forja", Category.INDUSTRY, 5, 5, 3, 0,
                cost(ResourceType.WOOD, 20, ResourceType.STONE, 35), 0, true, false));
        register(house("storage", "Armazém", Category.STORAGE, 5, 5, 3, 0,
                cost(ResourceType.WOOD, 35, ResourceType.STONE, 10), 0, false, true));
        register(house("town_hall", "Salão Real", Category.CIVIC, 9, 9, 4, 1,
                cost(ResourceType.WOOD, 50, ResourceType.STONE, 50), 2, false, true));
        register(house("library", "Biblioteca", Category.CIVIC, 7, 7, 4, 0,
                cost(ResourceType.WOOD, 45, ResourceType.STONE, 30), 0, false, false));
        register(farm());
    }

    private BlueprintLibrary() {}

    public static Blueprint get(String id) {
        if (id == null) return null;
        Blueprint b = BY_ID.get(id);
        return b != null ? b : CUSTOM.get(id);
    }

    /** Todas: embutidas + do mundo. */
    public static Collection<Blueprint> all() {
        List<Blueprint> out = new ArrayList<>(BY_ID.values());
        out.addAll(CUSTOM.values());
        return out;
    }

    public static Collection<Blueprint> builtins() {
        return BY_ID.values();
    }

    public static Collection<Blueprint> custom() {
        return CUSTOM.values();
    }

    public static synchronized void registerCustom(Blueprint b) {
        CUSTOM.put(b.id(), b);
        SOLID_PREFIX.remove(b.id());
    }

    public static synchronized void removeCustom(String id) {
        CUSTOM.remove(id);
        SOLID_PREFIX.remove(id);
    }

    public static synchronized void resetCustom(Collection<Blueprint> fromSave) {
        for (String id : CUSTOM.keySet()) SOLID_PREFIX.remove(id);
        CUSTOM.clear();
        if (fromSave != null) for (Blueprint b : fromSave) if (b != null && b.id() != null) CUSTOM.put(b.id(), b);
    }

    /** prefix[i] = quantos passos não-AIR existem antes do passo i. Tamanho = blockCount + 1. */
    public static synchronized int[] solidPrefix(Blueprint b) {
        return SOLID_PREFIX.computeIfAbsent(b.id(), k -> {
            int[] p = new int[b.placements().size() + 1];
            for (int i = 0; i < b.placements().size(); i++)
                p[i + 1] = p[i] + (b.placements().get(i).material() == Material.AIR ? 0 : 1);
            return p;
        });
    }

    /** Aceita id, nome exibido ou palavras em português ("casa", "fazenda", "quartel"...). */
    public static Blueprint find(String s) {
        if (s == null) return null;
        String n = Text.norm(s).replace(' ', '_');
        Blueprint b = get(n);
        if (b != null) return b;
        for (Blueprint bp : all()) if (Text.norm(bp.displayName()).replace(' ', '_').equals(n)) return bp;
        for (Blueprint bp : CUSTOM.values()) if (bp.id().equals("custom_" + n)) return bp;
        if (n.contains("media") || n.contains("grande") || n.contains("medium")) return BY_ID.get("house_medium");
        if (n.startsWith("casa") || n.startsWith("house") || n.startsWith("residen") || n.startsWith("moradia")) return BY_ID.get("house_small");
        if (n.startsWith("fazend") || n.startsWith("plantac") || n.startsWith("farm") || n.startsWith("campo")) return BY_ID.get("farm");
        if (n.startsWith("quartel") || n.startsWith("barrack") || n.startsWith("caserna")) return BY_ID.get("barracks");
        if (n.startsWith("forja") || n.startsWith("ferrar") || n.startsWith("smith")) return BY_ID.get("smithy");
        if (n.startsWith("armaz") || n.startsWith("celeiro") || n.startsWith("deposit") || n.startsWith("storage")) return BY_ID.get("storage");
        if (n.startsWith("bibliot") || n.startsWith("library") || n.startsWith("escola")) return BY_ID.get("library");
        if (n.startsWith("salao") || n.startsWith("prefeit") || n.startsWith("castelo") || n.startsWith("hall") || n.startsWith("paco")) return BY_ID.get("town_hall");
        return null;
    }

    private static void register(Blueprint b) {
        BY_ID.put(b.id(), b);
    }

    private static Map<ResourceType, Integer> cost(Object... kv) {
        Map<ResourceType, Integer> m = new EnumMap<>(ResourceType.class);
        for (int i = 0; i < kv.length; i += 2) m.put((ResourceType) kv[i], (Integer) kv[i + 1]);
        return Collections.unmodifiableMap(m);
    }

    private static Blueprint house(String id, String name, Category cat, int w, int d, int wallH, int beds,
                                   Map<ResourceType, Integer> cost, int housing, boolean forge, boolean storage) {
        List<Placement> p = new ArrayList<>();
        int roofLayers = (Math.min(w, d) + 2 + 1) / 2;
        int height = wallH + roofLayers + 1;
        // 1) limpar o terreno (grama alta, flores, árvores)
        for (int y = 0; y < height; y++)
            for (int x = -1; x <= w; x++)
                for (int z = -1; z <= d; z++)
                    p.add(new Placement(x, y, z, Material.AIR, Facing.NONE));
        // 2) fundação e piso
        for (int x = 0; x < w; x++)
            for (int z = 0; z < d; z++) {
                boolean edge = x == 0 || z == 0 || x == w - 1 || z == d - 1;
                p.add(new Placement(x, -1, z, edge ? Material.FOUNDATION : Material.FLOOR, Facing.NONE));
            }
        int doorX = w / 2;
        // 3) paredes
        for (int y = 0; y < wallH; y++)
            for (int x = 0; x < w; x++)
                for (int z = 0; z < d; z++) {
                    boolean edgeX = x == 0 || x == w - 1, edgeZ = z == 0 || z == d - 1;
                    if (!edgeX && !edgeZ) continue;
                    if (z == 0 && x == doorX && y < 2) continue; // vão da porta
                    Material m;
                    if (edgeX && edgeZ) m = Material.PILLAR;
                    else if (y == 1 && isWindowSlot(x, z, w, d, doorX)) m = Material.WINDOW;
                    else m = Material.WALL;
                    p.add(new Placement(x, y, z, m, Facing.NONE));
                }
        // 4) telhado em pirâmide com beiral
        for (int layer = 0; layer < roofLayers; layer++) {
            int x0 = -1 + layer, x1 = w - layer, z0 = -1 + layer, z1 = d - layer;
            if (x0 > x1 || z0 > z1) break;
            for (int x = x0; x <= x1; x++)
                for (int z = z0; z <= z1; z++) {
                    boolean ring = x == x0 || x == x1 || z == z0 || z == z1;
                    if (!ring && layer < roofLayers - 1) continue; // telhado oco por dentro
                    p.add(new Placement(x, wallH + layer, z, layer == 0 ? Material.ROOF_EDGE : Material.ROOF, Facing.NONE));
                }
        }
        // 5) porta
        p.add(new Placement(doorX, 0, 0, Material.DOOR_LOWER, Facing.NORTH));
        p.add(new Placement(doorX, 1, 0, Material.DOOR_UPPER, Facing.NORTH));
        p.add(new Placement(doorX, -1, -1, Material.PATH, Facing.NONE));
        // 6) interior
        int bedX = 1;
        for (int b = 0; b < beds; b++) {
            int bx = bedX + b * 2;
            if (bx >= w - 1) break;
            if (bx == doorX) bx++;
            if (bx >= w - 1) break;
            p.add(new Placement(bx, 0, d - 3, Material.BED_FOOT, Facing.SOUTH));
            p.add(new Placement(bx, 0, d - 2, Material.BED_HEAD, Facing.SOUTH));
        }
        if (forge) {
            p.add(new Placement(1, 0, 1, Material.FURNACE, Facing.SOUTH));
            p.add(new Placement(w - 2, 0, 1, Material.ANVIL, Facing.NONE));
        }
        if (storage) {
            for (int x = 1; x < w - 1; x++) {
                if (x == doorX) continue;
                p.add(new Placement(x, 0, 1, x % 2 == 0 ? Material.CHEST : Material.BARREL, Facing.SOUTH));
            }
        }
        if (id.equals("town_hall")) {
            p.add(new Placement(w / 2, 0, d / 2, Material.BELL, Facing.NONE));
        }
        if (id.equals("library")) {
            // estantes no fundo e nas laterais, atril no meio: onde se lê e se escreve
            for (int x = 1; x < w - 1; x++)
                for (int y = 0; y < 2; y++) p.add(new Placement(x, y, d - 2, Material.BOOKSHELF, Facing.NONE));
            for (int z = 2; z < d - 2; z++) {
                p.add(new Placement(1, 0, z, Material.BOOKSHELF, Facing.NONE));
                p.add(new Placement(w - 2, 0, z, Material.BOOKSHELF, Facing.NONE));
            }
            p.add(new Placement(w / 2, 0, d / 2, Material.LECTERN, Facing.NORTH));
        }
        if (!forge && !storage && w >= 5) p.add(new Placement(w - 2, 0, 1, Material.CRAFTING, Facing.NONE));
        p.add(new Placement(w - 2, 0, d / 2, Material.LIGHT, Facing.NONE));
        p.add(new Placement(1, 0, 1, forge ? Material.LIGHT : Material.CHEST, Facing.SOUTH));
        dedupe(p);
        return new Blueprint(id, name, cat, w, height, d, cost, housing, List.copyOf(p));
    }

    private static boolean isWindowSlot(int x, int z, int w, int d, int doorX) {
        if (z == 0) return x != doorX && Math.abs(x - doorX) == 2;
        if (z == d - 1) return x == w / 2;
        return z == d / 2; // laterais
    }

    private static Blueprint farm() {
        List<Placement> p = new ArrayList<>();
        int s = 9;
        for (int y = 0; y < 3; y++)
            for (int x = -1; x <= s; x++)
                for (int z = -1; z <= s; z++) p.add(new Placement(x, y, z, Material.AIR, Facing.NONE));
        int c = s / 2;
        for (int x = 0; x < s; x++)
            for (int z = 0; z < s; z++) {
                boolean edge = x == 0 || z == 0 || x == s - 1 || z == s - 1;
                if (edge) {
                    p.add(new Placement(x, -1, z, Material.PATH, Facing.NONE));
                    boolean gate = z == 0 && x == c;
                    if (!gate) p.add(new Placement(x, 0, z, Material.FENCE, Facing.NONE));
                } else if (x == c && z == c) {
                    p.add(new Placement(x, -1, z, Material.WATER, Facing.NONE));
                } else {
                    p.add(new Placement(x, -1, z, Material.FARMLAND, Facing.NONE));
                }
            }
        for (int x = 1; x < s - 1; x++)
            for (int z = 1; z < s - 1; z++)
                if (!(x == c && z == c)) p.add(new Placement(x, 0, z, Material.CROP, Facing.NONE));
        p.add(new Placement(0, 1, 0, Material.LIGHT, Facing.NONE));
        p.add(new Placement(s - 1, 1, s - 1, Material.LIGHT, Facing.NONE));
        p.add(new Placement(s - 1, 1, 0, Material.LIGHT, Facing.NONE));
        p.add(new Placement(0, 1, s - 1, Material.LIGHT, Facing.NONE));
        dedupe(p);
        return new Blueprint("farm", "Fazenda", Category.FARM, s, 3, s,
                cost(ResourceType.WOOD, 12), 0, List.copyOf(p));
    }

    /** Mantém só a última colocação de cada posição, preservando a ordem de construção. */
    private static void dedupe(List<Placement> p) {
        Map<Long, Integer> last = new HashMap<>();
        for (int i = 0; i < p.size(); i++) last.put(key(p.get(i)), i);
        List<Placement> out = new ArrayList<>();
        for (int i = 0; i < p.size(); i++) if (last.get(key(p.get(i))) == i) out.add(p.get(i));
        // AIR que será sobrescrito depois já foi removido; mantém AIR restante primeiro.
        p.clear();
        p.addAll(out);
    }

    private static long key(Placement pl) {
        return ((long) (pl.x() + 512) << 40) | ((long) (pl.y() + 512) << 20) | (pl.z() + 512);
    }
}
