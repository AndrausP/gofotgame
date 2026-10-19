package com.kingdomsai.core.construction.program;

import com.kingdomsai.core.common.Text;
import com.kingdomsai.core.construction.Blueprint;
import com.kingdomsai.core.construction.Blueprint.Facing;
import com.kingdomsai.core.construction.Blueprint.Placement;
import com.kingdomsai.core.construction.Material;
import com.kingdomsai.core.construction.program.BuildingProgram.Module;
import com.kingdomsai.core.kingdom.ResourceType;

import java.util.*;

/**
 * Compilador determinístico: {@link BuildingProgram} → {@link Blueprint}. A LLM descreve módulos; aqui nascem os blocos,
 * em etapas (fundação → paredes → contrafortes → telhado → vitrais → interior), com ajuste de portas, aberturas entre
 * módulos e verificação pós-compilação (acesso por porta, telhado fechado, nada flutuando). Mesma entrada = mesma saída.
 *
 * <p>Coordenadas: x = largura, z = profundidade (a fachada e a porta ficam em z baixo), y = 0 é o piso, y = -1 a fundação.
 * Paredes ocupam y = 0..h; o vão livre é y = 1..h; o telhado começa em y = h + 1.
 */
public final class ProgramCompiler {
    private ProgramCompiler() {}

    public static final int FLOOR_H = 4;

    public enum Stage {
        FOUNDATION("fundação"), WALLS("paredes"), BUTTRESSES("contrafortes"), ROOF("telhado"), WINDOWS("vitrais"), INTERIOR("interior");
        public final String display;

        Stage(String display) {
            this.display = display;
        }
    }

    /** Faixa [from, to) de passos da planta que pertence a uma etapa. */
    public record StageSpan(Stage stage, int from, int to) {}

    public record Result(Blueprint blueprint, List<StageSpan> stages, List<String> errors, List<String> warnings, int solidBlocks) {
        public boolean ok() {
            return errors.isEmpty() && blueprint != null;
        }
    }

    // ------------------------------------------------------------------ estruturas internas

    private record Box(int x0, int z0, int x1, int z1) {
        int w() {
            return x1 - x0;
        }

        int d() {
            return z1 - z0;
        }

        int cx() {
            return (x0 + x1 - 1) / 2;
        }

        int cz() {
            return (z0 + z1 - 1) / 2;
        }

        boolean overlaps(Box o) {
            return x0 < o.x1 && o.x0 < x1 && z0 < o.z1 && o.z0 < z1;
        }

        boolean contains(int x, int z) {
            return x >= x0 && x < x1 && z >= z0 && z < z1;
        }

        boolean ring(int x, int z) {
            return contains(x, z) && (x == x0 || x == x1 - 1 || z == z0 || z == z1 - 1);
        }
    }

    private static final class Cell {
        Material m;
        Facing f;
        Stage stage;

        Cell(Material m, Facing f, Stage stage) {
            this.m = m;
            this.f = f;
            this.stage = stage;
        }
    }

    private static final class Grid {
        final Map<Long, Cell> cells = new HashMap<>();

        static long key(int x, int y, int z) {
            return ((long) (x + 4096) << 40) | ((long) (y + 64) << 20) | (z + 4096);
        }

        static int kx(long k) {
            return (int) (k >> 40) - 4096;
        }

        static int ky(long k) {
            return (int) ((k >> 20) & 0xFFFFF) - 64;
        }

        static int kz(long k) {
            return (int) (k & 0xFFFFF) - 4096;
        }

        void set(int x, int y, int z, Material m, Stage s) {
            set(x, y, z, m, Facing.NONE, s);
        }

        void set(int x, int y, int z, Material m, Facing f, Stage s) {
            cells.put(key(x, y, z), new Cell(m, f, s));
        }

        void clear(int x, int y, int z) {
            cells.remove(key(x, y, z));
        }

        Cell get(int x, int y, int z) {
            return cells.get(key(x, y, z));
        }

        boolean solid(int x, int y, int z) {
            Cell c = get(x, y, z);
            return c != null && structural(c.m);
        }
    }

    /** Material que segura a estrutura (porta, escada de mão e tocha não seguram nada). */
    private static boolean structural(Material m) {
        return switch (m) {
            case AIR, DOOR_LOWER, DOOR_UPPER, LADDER, LIGHT, PATH, BANNER -> false;
            default -> true;
        };
    }

    private static final class Geo {
        final Module mod;
        final ModuleType type;
        Box box;
        int h;
        int floors;
        boolean round;
        double radius;
        String roof = "";
        double xc;
        int rows;

        Geo(Module mod, ModuleType type) {
            this.mod = mod;
            this.type = type;
        }

        boolean enterable() {
            return type == ModuleType.HALL || type == ModuleType.TOWER;
        }
    }

    // ------------------------------------------------------------------ entrada

    public static Result compile(BuildingProgram p) {
        List<String> errors = new ArrayList<>(), warnings = new ArrayList<>();
        Style style = Style.parse(p.style);
        if (style == null) errors.add("estilo desconhecido: \"" + p.style + "\" (use romanesque, gothic, rustic ou fortress)");
        if (p.modules.isEmpty()) errors.add("o programa não tem módulos");
        if (p.modules.size() > BuildingProgram.MAX_MODULES) errors.add("módulos demais: " + p.modules.size() + " (máx. " + BuildingProgram.MAX_MODULES + ")");
        if (!errors.isEmpty()) return fail(errors, warnings);

        // ---- 1) validação de campos e ligações
        Map<String, Geo> geos = new LinkedHashMap<>();
        for (Module m : p.modules) {
            String id = m.id == null ? "" : m.id.trim();
            if (id.isEmpty()) {
                errors.add("módulo sem id");
                continue;
            }
            if (geos.containsKey(id)) {
                errors.add("id repetido: " + id);
                continue;
            }
            ModuleType t = m.moduleType();
            if (t == null) {
                errors.add("módulo " + id + ": tipo desconhecido \"" + m.type + "\"");
                continue;
            }
            fieldErrors(m, t, id, errors);
            geos.put(id, new Geo(m, t));
        }
        long roots = p.modules.stream().filter(m -> m.attach == null || m.attach.isBlank()).count();
        if (roots != 1) errors.add("precisa de exatamente 1 módulo raiz (sem attach); há " + roots);
        for (Geo g : geos.values()) {
            String a = g.mod.attach;
            if (a != null && !a.isBlank() && !geos.containsKey(a.trim())) errors.add("módulo " + g.mod.id + ": attach aponta para \"" + a + "\", que não existe");
            if (!g.type.solid && (a == null || a.isBlank())) errors.add("módulo " + g.mod.id + " (" + g.type + ") precisa de attach");
        }
        if (!errors.isEmpty()) return fail(errors, warnings);

        // ---- 2) tamanho de cada módulo e posição no plano
        for (Geo g : geos.values()) size(g, geos, style);
        Geo root = geos.get(p.root().id);
        root.box = new Box(0, 0, root.box == null ? 0 : root.box.w(), root.box == null ? 0 : root.box.d());
        if (!root.type.solid) {
            errors.add("a raiz não pode ser " + root.type);
            return fail(errors, warnings);
        }
        Set<String> placed = new HashSet<>(List.of(root.mod.id));
        boolean progress = true;
        while (progress) {
            progress = false;
            for (Geo g : geos.values()) {
                if (placed.contains(g.mod.id) || g == root) continue;
                Geo t = geos.get(g.mod.attach.trim());
                if (!placed.contains(t.mod.id)) continue;
                if (g.type.solid) {
                    String err = position(g, t);
                    if (err != null) errors.add(err);
                }
                placed.add(g.mod.id);
                progress = true;
            }
        }
        for (Geo g : geos.values()) if (!placed.contains(g.mod.id)) errors.add("módulo " + g.mod.id + ": ciclo de attach");
        if (!errors.isEmpty()) return fail(errors, warnings);

        List<Geo> solids = geos.values().stream().filter(g -> g.type.solid && g.box != null).toList();
        for (int i = 0; i < solids.size(); i++)
            for (int j = i + 1; j < solids.size(); j++)
                if (solids.get(i).box.overlaps(solids.get(j).box))
                    errors.add("os módulos " + solids.get(i).mod.id + " e " + solids.get(j).mod.id + " se sobrepõem");
        if (!errors.isEmpty()) return fail(errors, warnings);

        // ---- 3) telhados: o módulo ROOF só troca o tipo do alvo
        for (Geo g : geos.values()) {
            if (g.type == ModuleType.HALL) g.roof = roofKind(g.mod.roof, style.hallRoof);
            if (g.type == ModuleType.TOWER) g.roof = roofKind(g.mod.roof, style.towerRoof);
        }
        for (Geo g : geos.values()) {
            if (g.type != ModuleType.ROOF) continue;
            Geo t = geos.get(g.mod.attach.trim());
            String k = roofKind(g.mod.roof, "");
            if (k.isEmpty()) errors.add("módulo " + g.mod.id + ": telhado desconhecido \"" + g.mod.roof + "\" (gable, vault, flat, cone)");
            else if (t.type != ModuleType.HALL && t.type != ModuleType.TOWER) errors.add("módulo " + g.mod.id + ": telhado só vale para nave ou torre");
            else t.roof = k;
        }
        if (!errors.isEmpty()) return fail(errors, warnings);

        // ---- 4) geometria
        Grid g = new Grid();
        for (Geo m : geos.values()) {
            switch (m.type) {
                case HALL -> hall(g, m, style);
                case TOWER -> tower(g, m, style);
                case APSE -> apse(g, m, geos.get(m.mod.attach.trim()));
                case CLOISTER -> cloister(g, m);
                case WALL_SEGMENT -> wallSegment(g, m);
                case GATE -> gate(g, m);
                case STAIR -> stair(g, m, geos.get(m.mod.attach.trim()));
                case BRIDGE_SPAN -> bridge(g, m);
                default -> {
                }
            }
        }
        for (Geo m : geos.values()) {
            Geo t = m.mod.attach == null || m.mod.attach.isBlank() ? null : geos.get(m.mod.attach.trim());
            switch (m.type) {
                case BUTTRESS_ROW -> buttresses(g, m, t, style, errors);
                case WINDOW_BAND -> windows(g, m, t, style, errors);
                default -> {
                }
            }
        }
        if (!errors.isEmpty()) return fail(errors, warnings);
        for (Geo m : geos.values()) {
            Geo t = m.mod.attach == null || m.mod.attach.isBlank() ? null : geos.get(m.mod.attach.trim());
            if (t != null && m.type.solid && m.type != ModuleType.APSE) connect(g, m, t);
        }
        String doorErr = entrance(g, solids);
        if (doorErr != null) errors.add(doorErr);
        torches(g, geos.values());
        if (!errors.isEmpty()) return fail(errors, warnings);

        // ---- 5) limites duros
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE, minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        int solid = 0;
        for (long k : g.cells.keySet()) {
            Cell c = g.cells.get(k);
            int x = Grid.kx(k), y = Grid.ky(k), z = Grid.kz(k);
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minZ = Math.min(minZ, z);
            maxZ = Math.max(maxZ, z);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
            if (structural(c.m)) solid++;
        }
        int width = maxX - minX + 1, depth = maxZ - minZ + 1;
        if (maxY > BuildingProgram.MAX_HEIGHT) errors.add("altura acima do limite: " + maxY + " blocos (máx. " + BuildingProgram.MAX_HEIGHT + ")");
        if (width > BuildingProgram.MAX_SITE || depth > BuildingProgram.MAX_SITE)
            errors.add("a obra tem " + width + "×" + depth + " blocos; o máximo é " + BuildingProgram.MAX_SITE + "×" + BuildingProgram.MAX_SITE);
        if (p.site != null && p.site.width > 0 && p.site.depth > 0 && (width > p.site.width || depth > p.site.depth))
            errors.add("a obra (" + width + "×" + depth + ") não cabe no terreno (" + p.site.width + "×" + p.site.depth + ")");
        if (solid > BuildingProgram.MAX_BLOCKS) errors.add("obra grande demais: " + solid + " blocos (máx. " + BuildingProgram.MAX_BLOCKS + ")");
        if (!errors.isEmpty()) return fail(errors, warnings);

        // ---- 6) verificação pós-compilação
        verify(g, geos.values(), minX, maxX, minZ, maxZ, maxY, errors);
        if (!errors.isEmpty()) return fail(errors, warnings);

        return emit(p, style, g, geos.values(), minX, maxX, minZ, maxZ, maxY, warnings);
    }

    private static Result fail(List<String> errors, List<String> warnings) {
        return new Result(null, List.of(), List.copyOf(errors), List.copyOf(warnings), 0);
    }

    private static String roofKind(String s, String fallback) {
        String n = Text.norm(s == null ? "" : s);
        if (n.isEmpty()) return fallback;
        if (n.contains("vault") || n.contains("aboba") || n.contains("barril")) return "vault";
        if (n.contains("gable") || n.contains("duas") || n.contains("agua")) return "gable";
        if (n.contains("flat") || n.contains("plan") || n.contains("lage")) return "flat";
        if (n.contains("cone") || n.contains("pira") || n.contains("flecha") || n.contains("spire")) return "cone";
        return "";
    }

    private static void fieldErrors(Module m, ModuleType t, String id, List<String> errors) {
        switch (t) {
            case HALL -> {
                range(id, "w", m.w, 3, 48, errors);
                range(id, "d", m.d, 3, 56, errors);
                range(id, "h", m.h, 3, 30, errors);
            }
            case TOWER -> {
                if (m.r > 0) range(id, "r", m.r, 2, 8, errors);
                else range(id, "w", m.w, 3, 16, errors);
                range(id, "floors", m.floors, 1, 8, errors);
            }
            case APSE -> range(id, "r", m.r, 2, 12, errors);
            case CLOISTER -> {
                range(id, "w", m.w, 9, 40, errors);
                range(id, "d", m.d, 9, 40, errors);
            }
            case WALL_SEGMENT -> {
                range(id, "w", m.w > 0 ? m.w : m.length, 2, 56, errors);
                if (m.h != 0) range(id, "h", m.h, 2, 20, errors);
            }
            case GATE -> {
                if (m.w != 0) range(id, "w", m.w, 2, 9, errors);
                if (m.h != 0) range(id, "h", m.h, 4, 14, errors);
            }
            case STAIR -> {
                if (m.height != 0) range(id, "height", m.height, 1, 16, errors);
            }
            case BRIDGE_SPAN -> {
                if (m.length != 0) range(id, "length", m.length, 2, 40, errors);
            }
            default -> {
            }
        }
    }

    private static void range(String id, String field, int v, int lo, int hi, List<String> errors) {
        if (v < lo || v > hi) errors.add("módulo " + id + ": " + field + " = " + v + " fora de " + lo + ".." + hi);
    }

    // ------------------------------------------------------------------ tamanho e posição

    private static void size(Geo g, Map<String, Geo> geos, Style style) {
        Module m = g.mod;
        switch (g.type) {
            case HALL -> {
                g.h = m.h;
                g.box = new Box(0, 0, m.w, m.d);
            }
            case TOWER -> {
                g.floors = Math.max(1, m.floors);
                g.h = g.floors * FLOOR_H;
                g.round = m.r > 0;
                int side = g.round ? 2 * m.r + 1 : m.w;
                g.radius = g.round ? m.r + 0.5 : side / 2.0;
                g.box = new Box(0, 0, side, g.round ? side : (m.d > 0 ? m.d : side));
            }
            case APSE -> {
                Geo t = geos.get(m.attach.trim());
                int width = Math.min(2 * m.r + 1, t.box == null ? 2 * m.r + 1 : Math.max(3, t.mod.w));
                if (t.box != null && (width % 2) != (t.box.w() % 2)) width--;
                g.radius = width / 2.0;
                g.rows = (int) Math.ceil(g.radius);
                g.h = t.h;
                g.box = new Box(0, 0, width, g.rows);
            }
            case CLOISTER -> {
                g.h = 3;
                g.box = new Box(0, 0, m.w, m.d);
            }
            case WALL_SEGMENT -> {
                int len = m.w > 0 ? m.w : m.length;
                int thick = Math.max(1, Math.min(3, m.d > 0 ? m.d : 1));
                g.h = m.h > 0 ? m.h : 6;
                boolean z = Text.norm(m.axis).equals("z");
                g.box = z ? new Box(0, 0, thick, len) : new Box(0, 0, len, thick);
            }
            case GATE -> {
                int pass = m.w > 0 ? m.w : 3, thick = m.d > 0 ? Math.min(m.d, 6) : 3;
                g.h = m.h > 0 ? m.h : 6;
                g.xc = pass;
                boolean z = Text.norm(m.axis).equals("z");
                g.box = z ? new Box(0, 0, thick, pass + 2) : new Box(0, 0, pass + 2, thick);
            }
            case STAIR -> {
                int w = m.w > 0 ? m.w : 3, len = m.height > 0 ? m.height : (m.length > 0 ? m.length : 4);
                g.h = len;
                g.box = new Box(0, 0, w, len); // orientado em position()
            }
            case BRIDGE_SPAN -> {
                int w = m.w > 0 ? m.w : 3, len = m.length > 0 ? m.length : 8;
                g.h = m.height > 0 ? m.height : 4;
                boolean x = Text.norm(m.axis).equals("x");
                g.box = x ? new Box(0, 0, len, w) : new Box(0, 0, w, len);
            }
            default -> {
            }
        }
    }

    private static final Set<String> SIDES = Set.of("front", "back", "left", "right", "front-left", "front-right", "back-left", "back-right");

    /** Encosta o módulo no alvo. Retorna o erro, se houver. */
    private static String position(Geo g, Geo t) {
        String side = g.mod.side == null ? "" : Text.norm(g.mod.side).replace('_', '-').replace(' ', '-');
        if (g.type == ModuleType.APSE) {
            if (!side.isEmpty() && !side.equals("back")) return "módulo " + g.mod.id + ": abside só encosta no fundo (side = back)";
            if (t.type != ModuleType.HALL) return "módulo " + g.mod.id + ": abside só se liga a uma nave";
            side = "back";
        }
        if (side.isEmpty()) side = g.type == ModuleType.STAIR ? "front" : "right";
        if (!SIDES.contains(side)) return "módulo " + g.mod.id + ": side desconhecido \"" + g.mod.side + "\"";
        Box tb = t.box, b = g.box;
        int w = b.w(), d = b.d();
        if (g.type == ModuleType.STAIR) { // o comprimento da escada vai para fora da parede
            int len = g.h, sw = g.mod.w > 0 ? g.mod.w : 3;
            boolean alongZ = side.startsWith("front") || side.startsWith("back");
            w = alongZ ? sw : len;
            d = alongZ ? len : sw;
        }
        int x0, z0;
        boolean fr = side.startsWith("front"), bk = side.startsWith("back"), lf = side.endsWith("left"), rt = side.endsWith("right");
        if (g.type == ModuleType.APSE) {
            x0 = tb.x0 + (tb.w() - w) / 2;
            z0 = tb.z1;
        } else if (side.equals("front")) {
            z0 = tb.z0 - d;
            x0 = tb.cx() - (w - 1) / 2;
        } else if (side.equals("back")) {
            z0 = tb.z1;
            x0 = tb.cx() - (w - 1) / 2;
        } else if (side.equals("left")) {
            x0 = tb.x0 - w;
            z0 = tb.cz() - (d - 1) / 2;
        } else if (side.equals("right")) {
            x0 = tb.x1;
            z0 = tb.cz() - (d - 1) / 2;
        } else {
            x0 = lf ? tb.x0 - w : tb.x1;
            z0 = fr ? tb.z0 : tb.z1 - d;
            if (bk && !fr) z0 = tb.z1 - d;
        }
        g.box = new Box(x0, z0, x0 + w, z0 + d);
        if (g.type == ModuleType.APSE) g.xc = x0 + (w - 1) / 2.0;
        return null;
    }

    // ------------------------------------------------------------------ geometria

    private static void foundation(Grid g, Box b) {
        for (int x = b.x0; x < b.x1; x++) for (int z = b.z0; z < b.z1; z++) g.set(x, -1, z, Material.FOUNDATION, Stage.FOUNDATION);
    }

    private static void hall(Grid g, Geo m, Style style) {
        Box b = m.box;
        int h = m.h;
        foundation(g, b);
        for (int x = b.x0; x < b.x1; x++)
            for (int z = b.z0; z < b.z1; z++) {
                if (!b.ring(x, z)) {
                    g.set(x, 0, z, Material.FLOOR, Stage.WALLS);
                    continue;
                }
                boolean corner = (x == b.x0 || x == b.x1 - 1) && (z == b.z0 || z == b.z1 - 1);
                int along = (x == b.x0 || x == b.x1 - 1) ? z - b.z0 : x - b.x0;
                boolean pilaster = !corner && along % 4 == 0;
                for (int y = 0; y <= h; y++) g.set(x, y, z, corner || (pilaster && y > 0) ? Material.PILLAR : Material.WALL, Stage.WALLS);
            }
        // telhado: a cumeeira corre pelo lado maior
        boolean ridgeZ = b.w() <= b.d();
        int span = ridgeZ ? b.w() : b.d();
        int[] top = new int[span];
        for (int k = 0; k < span; k++) top[k] = roofTop(m.roof, h, k, span);
        for (int x = b.x0; x < b.x1; x++)
            for (int z = b.z0; z < b.z1; z++) {
                int k = ridgeZ ? x - b.x0 : z - b.z0;
                int lo = Math.min(top[k], Math.min(k > 0 ? top[k - 1] : top[k], k < span - 1 ? top[k + 1] : top[k]));
                boolean endRow = ridgeZ ? (z == b.z0 || z == b.z1 - 1) : (x == b.x0 || x == b.x1 - 1);
                for (int y = lo; y <= top[k]; y++) g.set(x, y, z, Material.ROOF, Stage.ROOF);
                if (endRow) for (int y = h + 1; y < lo; y++) g.set(x, y, z, Material.WALL, Stage.WALLS); // empena
            }
    }

    private static int roofTop(String kind, int h, int k, int span) {
        return switch (kind) {
            case "flat" -> h + 1;
            case "vault" -> {
                double r = (span - 1) / 2.0, dx = k - r;
                yield h + 1 + (int) Math.round(Math.sqrt(Math.max(0, r * r - dx * dx)));
            }
            default -> h + 1 + Math.min(k, span - 1 - k); // gable e cone na nave
        };
    }

    private static void tower(Grid g, Geo m, Style style) {
        Box b = m.box;
        int H = m.h;
        double cx = (b.x0 + b.x1 - 1) / 2.0, cz = (b.z0 + b.z1 - 1) / 2.0;
        List<int[]> cells = new ArrayList<>();
        for (int x = b.x0; x < b.x1; x++)
            for (int z = b.z0; z < b.z1; z++) {
                double dist = Math.hypot(x - cx, z - cz);
                if (!m.round || dist <= m.radius) cells.add(new int[]{x, z});
            }
        Set<Long> inside = new HashSet<>();
        for (int[] c : cells) inside.add(Grid.key(c[0], 0, c[1]));
        for (int[] c : cells) {
            int x = c[0], z = c[1];
            g.set(x, -1, z, Material.FOUNDATION, Stage.FOUNDATION);
            boolean edge = !inside.contains(Grid.key(x - 1, 0, z)) || !inside.contains(Grid.key(x + 1, 0, z))
                    || !inside.contains(Grid.key(x, 0, z - 1)) || !inside.contains(Grid.key(x, 0, z + 1));
            if (edge) for (int y = 0; y <= H; y++) g.set(x, y, z, (x + z) % 4 == 0 && y > 0 ? Material.PILLAR : Material.WALL, Stage.WALLS);
            else g.set(x, 0, z, Material.FLOOR, Stage.WALLS);
        }
        // escada de mão encostada na parede oeste; os andares têm o vão dela aberto
        int lx = -1, lz = (int) Math.round(cz);
        for (int[] c : cells) if (c[1] == lz && (lx < 0 || c[0] < lx)) lx = c[0] + 1;
        for (int i = 1; i < m.floors; i++)
            for (int[] c : cells)
                if (!inside.contains(Grid.key(c[0] - 1, 0, c[1])) || !inside.contains(Grid.key(c[0] + 1, 0, c[1])) || !inside.contains(Grid.key(c[0], 0, c[1] - 1)) || !inside.contains(Grid.key(c[0], 0, c[1] + 1))) continue;
                else if (!(c[0] == lx && c[1] == lz)) g.set(c[0], i * FLOOR_H, c[1], Material.FLOOR, Stage.WALLS);
        for (int y = 1; y <= H; y++) g.set(lx, y, lz, Material.LADDER, Facing.WEST, Stage.INTERIOR);
        // topo
        boolean cone = m.roof.equals("cone");
        Map<Long, Integer> topY = new HashMap<>();
        double R = Math.max(1, m.radius);
        for (int[] c : cells) {
            double dist = m.round ? Math.hypot(c[0] - cx, c[1] - cz) : Math.max(Math.abs(c[0] - cx), Math.abs(c[1] - cz));
            topY.put(Grid.key(c[0], 0, c[1]), H + 1 + (cone ? (int) Math.max(0, Math.floor(R - dist)) : 0));
        }
        if (cone) {
            for (int[] c : cells) {
                int t = topY.get(Grid.key(c[0], 0, c[1]));
                int lo = t;
                for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    Integer n = topY.get(Grid.key(c[0] + d[0], 0, c[1] + d[1]));
                    if (n != null) lo = Math.min(lo, n);
                }
                for (int y = lo; y <= t; y++) g.set(c[0], y, c[1], Material.ROOF, Stage.ROOF);
            }
        } else {
            for (int[] c : cells) {
                boolean edge = !inside.contains(Grid.key(c[0] - 1, 0, c[1])) || !inside.contains(Grid.key(c[0] + 1, 0, c[1]))
                        || !inside.contains(Grid.key(c[0], 0, c[1] - 1)) || !inside.contains(Grid.key(c[0], 0, c[1] + 1));
                if (!(c[0] == lx && c[1] == lz)) g.set(c[0], H + 1, c[1], edge ? Material.WALL : Material.ROOF, Stage.ROOF);
                if (edge && (c[0] + c[1]) % 2 == 0) g.set(c[0], H + 2, c[1], Material.ROOF_EDGE, Stage.ROOF); // ameias
            }
        }
    }

    private static void apse(Grid g, Geo m, Geo hall) {
        Box b = m.box, hb = hall.box;
        int h = hall.h;
        double R = m.radius, xc = m.xc;
        for (int x = b.x0; x < b.x1; x++)
            for (int z = b.z0; z < b.z1; z++) {
                int dz = z - b.z0;
                double dist = Math.hypot(x - xc, dz + 0.5);
                if (dist > R) continue;
                g.set(x, -1, z, Material.FOUNDATION, Stage.FOUNDATION);
                boolean wall = dist > R - 1.0 || (x == b.x0 || x == b.x1 - 1);
                if (wall) for (int y = 0; y <= h; y++) g.set(x, y, z, y > 0 && (x + z) % 4 == 0 ? Material.PILLAR : Material.WALL, Stage.WALLS);
                else g.set(x, 0, z, Material.FLOOR, Stage.WALLS);
                int top = h + 1 + (int) Math.round(Math.sqrt(Math.max(0, R * R - dist * dist)));
                int lo = Math.max(h + 1, top - 1);
                for (int y = lo; y <= top; y++) g.set(x, y, z, Material.ROOF, Stage.ROOF);
                if (wall) for (int y = h + 1; y < lo; y++) g.set(x, y, z, Material.WALL, Stage.WALLS);
            }
        // arco aberto na parede do fundo da nave (deixa as colunas das pontas)
        for (int x = b.x0 + 1; x < b.x1 - 1; x++) for (int y = 1; y <= h - 2; y++) g.clear(x, y, hb.z1 - 1);
        g.set((int) Math.round(xc), 1, b.z0 + Math.max(1, m.rows - 2), Material.LECTERN, Facing.SOUTH, Stage.INTERIOR); // altar
    }

    private static void cloister(Grid g, Geo m) {
        Box b = m.box;
        foundation(g, b);
        for (int x = b.x0; x < b.x1; x++)
            for (int z = b.z0; z < b.z1; z++) {
                int layer = Math.min(Math.min(x - b.x0, b.x1 - 1 - x), Math.min(z - b.z0, b.z1 - 1 - z));
                if (layer >= 3) {
                    g.set(x, 0, z, Material.PATH, Stage.WALLS);
                    continue;
                }
                g.set(x, 0, z, Material.FLOOR, Stage.WALLS);
                boolean arcade = (layer == 0 || layer == 2) && ((x + z) % 2 == 0 || x == b.x0 || x == b.x1 - 1 || z == b.z0 || z == b.z1 - 1 && false);
                boolean cornerCol = (layer == 0 || layer == 2) && (x - b.x0 + z - b.z0) % 2 == 0;
                if (cornerCol) for (int y = 1; y <= 3; y++) g.set(x, y, z, Material.PILLAR, Stage.WALLS);
                g.set(x, 4, z, Material.ROOF, Stage.ROOF);
            }
    }

    private static void wallSegment(Grid g, Geo m) {
        Box b = m.box;
        foundation(g, b);
        boolean alongX = b.w() >= b.d();
        for (int x = b.x0; x < b.x1; x++)
            for (int z = b.z0; z < b.z1; z++) {
                int i = alongX ? x - b.x0 : z - b.z0, len = alongX ? b.w() : b.d();
                boolean end = i == 0 || i == len - 1;
                for (int y = 0; y < m.h; y++) g.set(x, y, z, end ? Material.PILLAR : Material.WALL, Stage.WALLS);
                if (i % 2 == 0) g.set(x, m.h, z, Material.ROOF_EDGE, Stage.WALLS);
            }
    }

    private static void gate(Grid g, Geo m) {
        Box b = m.box;
        boolean passAlongZ = b.w() > b.d(); // a passagem atravessa a espessura
        for (int x = b.x0; x < b.x1; x++)
            for (int z = b.z0; z < b.z1; z++) {
                int u = passAlongZ ? x - b.x0 : z - b.z0, uMax = passAlongZ ? b.w() - 1 : b.d() - 1;
                boolean pillar = u == 0 || u == uMax;
                if (pillar) {
                    g.set(x, -1, z, Material.FOUNDATION, Stage.FOUNDATION);
                    for (int y = 0; y <= m.h; y++) g.set(x, y, z, Material.PILLAR, Stage.WALLS);
                } else {
                    g.set(x, 0, z, Material.PATH, Stage.WALLS);
                    for (int y = m.h - 1; y <= m.h; y++) g.set(x, y, z, Material.WALL, Stage.WALLS); // verga
                }
            }
    }

    private static void stair(Grid g, Geo m, Geo t) {
        Box b = m.box, tb = t.box;
        String side = m.mod.side == null || m.mod.side.isBlank() ? "front" : Text.norm(m.mod.side);
        for (int x = b.x0; x < b.x1; x++)
            for (int z = b.z0; z < b.z1; z++) {
                int j; // distância à parede do alvo (0 = encostado)
                if (side.startsWith("front")) j = tb.z0 - 1 - z;
                else if (side.startsWith("back")) j = z - tb.z1;
                else if (side.endsWith("left")) j = tb.x0 - 1 - x;
                else j = x - tb.x1;
                j = Math.max(0, j);
                g.set(x, -1, z, Material.FOUNDATION, Stage.FOUNDATION);
                for (int y = 0; y < m.h - j; y++) g.set(x, y, z, Material.WALL, Stage.WALLS);
            }
    }

    private static void bridge(Grid g, Geo m) {
        Box b = m.box;
        int deck = m.h;
        for (int x = b.x0; x < b.x1; x++)
            for (int z = b.z0; z < b.z1; z++) {
                g.set(x, deck, z, Material.FLOOR, Stage.WALLS);
                boolean cornerX = x == b.x0 || x == b.x1 - 1, cornerZ = z == b.z0 || z == b.z1 - 1;
                if (cornerX && cornerZ) {
                    g.set(x, -1, z, Material.FOUNDATION, Stage.FOUNDATION);
                    for (int y = 0; y < deck; y++) g.set(x, y, z, Material.PILLAR, Stage.WALLS);
                }
                boolean alongX = b.w() >= b.d();
                if (alongX ? (z == b.z0 || z == b.z1 - 1) : (x == b.x0 || x == b.x1 - 1)) g.set(x, deck + 1, z, Material.FENCE, Stage.WALLS);
            }
    }

    private static void buttresses(Grid g, Geo m, Geo t, Style style, List<String> errors) {
        if (t.type != ModuleType.HALL) {
            errors.add("módulo " + m.mod.id + ": contrafortes só valem para uma nave");
            return;
        }
        Box b = t.box;
        int spacing = m.mod.spacing > 0 ? m.mod.spacing : style.buttressEvery;
        if (spacing < 2 || spacing > 12) {
            errors.add("módulo " + m.mod.id + ": spacing " + spacing + " fora de 2..12");
            return;
        }
        int bh = Math.max(3, t.h * 2 / 3);
        boolean ridgeZ = b.w() <= b.d();
        int len = ridgeZ ? b.d() : b.w();
        for (int i = 2; i < len - 2; i += spacing)
            for (int side = 0; side < 2; side++) {
                int x = ridgeZ ? (side == 0 ? b.x0 - 1 : b.x1) : b.x0 + i;
                int z = ridgeZ ? b.z0 + i : (side == 0 ? b.z0 - 1 : b.z1);
                g.set(x, -1, z, Material.FOUNDATION, Stage.FOUNDATION);
                for (int y = 0; y <= bh; y++) g.set(x, y, z, Material.PILLAR, Stage.BUTTRESSES);
                g.set(x, bh + 1, z, Material.WALL, Stage.BUTTRESSES);
            }
    }

    private static void windows(Grid g, Geo m, Geo t, Style style, List<String> errors) {
        int spacing = m.mod.spacing > 0 ? m.mod.spacing : style.windowEvery;
        if (spacing < 2 || spacing > 10) {
            errors.add("módulo " + m.mod.id + ": spacing " + spacing + " fora de 2..10");
            return;
        }
        int ht = m.mod.height > 0 ? Math.min(m.mod.height, 10) : 3;
        Box b = t.box;
        if (t.type == ModuleType.HALL) {
            int top = Math.min(2 + ht - 1, t.h - 2);
            boolean ridgeZ = b.w() <= b.d();
            int len = ridgeZ ? b.d() : b.w();
            for (int i = 2; i < len - 2; i += spacing)
                for (int side = 0; side < 2; side++) {
                    int x = ridgeZ ? (side == 0 ? b.x0 : b.x1 - 1) : b.x0 + i;
                    int z = ridgeZ ? b.z0 + i : (side == 0 ? b.z0 : b.z1 - 1);
                    for (int y = 2; y <= top; y++) {
                        Cell c = g.get(x, y, z);
                        if (c != null && c.m == Material.WALL) g.set(x, y, z, Material.WINDOW, Stage.WINDOWS);
                    }
                }
        } else if (t.type == ModuleType.TOWER) {
            for (int f = 0; f < t.floors; f++) {
                int y = f * FLOOR_H + 2;
                for (int[] c : new int[][]{{b.cx(), b.z0}, {b.cx(), b.z1 - 1}, {b.x0, b.cz()}, {b.x1 - 1, b.cz()}}) {
                    Cell cell = g.get(c[0], y, c[1]);
                    if (cell != null && cell.m == Material.WALL) g.set(c[0], y, c[1], Material.WINDOW, Stage.WINDOWS);
                }
            }
        } else errors.add("módulo " + m.mod.id + ": janelas só valem para nave ou torre");
    }

    /** Abre uma porta no meio da parede que dois módulos encostados dividem. */
    private static void connect(Grid g, Geo m, Geo t) {
        if (!(m.enterable() || m.type == ModuleType.CLOISTER) || !(t.enterable() || t.type == ModuleType.CLOISTER)) return;
        Box a = m.box, b = t.box;
        if (a.x1 == b.x0 || a.x0 == b.x1) { // encostados em x: porta olha para x
            int lo = Math.max(a.z0, b.z0), hi = Math.min(a.z1, b.z1) - 1;
            if (hi < lo) return;
            int z = (lo + hi) / 2;
            int xa = a.x1 == b.x0 ? a.x1 - 1 : a.x0, xb = a.x1 == b.x0 ? b.x0 : b.x1 - 1;
            for (int y = 1; y <= 2; y++) {
                g.clear(xa, y, z);
                g.clear(xb, y, z);
            }
        } else if (a.z1 == b.z0 || a.z0 == b.z1) {
            int lo = Math.max(a.x0, b.x0), hi = Math.min(a.x1, b.x1) - 1;
            if (hi < lo) return;
            int x = (lo + hi) / 2;
            int za = a.z1 == b.z0 ? a.z1 - 1 : a.z0, zb = a.z1 == b.z0 ? b.z0 : b.z1 - 1;
            for (int y = 1; y <= 2; y++) {
                g.clear(x, y, za);
                g.clear(x, y, zb);
            }
        }
    }

    /** Porta de entrada na fachada do módulo mais à frente que tenha a frente livre. Erro se nenhum puder ter porta. */
    private static String entrance(Grid g, List<Geo> solids) {
        List<Geo> cands = new ArrayList<>(solids.stream().filter(Geo::enterable).toList());
        cands.sort(Comparator.comparingInt((Geo c) -> c.box.z0).thenComparingInt(c -> Math.abs(c.box.cx())));
        for (Geo c : cands) {
            int x = c.box.cx(), z = c.box.z0;
            boolean blocked = false;
            for (Geo o : solids) if (o != c && o.box.contains(x, z - 1) && o.type != ModuleType.GATE) blocked = true;
            if (blocked) continue;
            Cell wall = g.get(x, 1, z);
            if (wall == null) continue;
            g.set(x, 1, z, Material.DOOR_LOWER, Facing.SOUTH, Stage.INTERIOR);
            g.set(x, 2, z, Material.DOOR_UPPER, Facing.SOUTH, Stage.INTERIOR);
            return null;
        }
        boolean anyEnterable = !cands.isEmpty();
        return anyEnterable ? "porta sem acesso: a frente de todos os módulos com porta está bloqueada por outro módulo" : null;
    }

    private static void torches(Grid g, Collection<Geo> geos) {
        for (Geo m : geos) {
            if (m.type != ModuleType.HALL) continue;
            Box b = m.box;
            for (int i = 3; i < Math.max(b.w(), b.d()) - 3; i += 6) {
                boolean ridgeZ = b.w() <= b.d();
                int[][] spots = ridgeZ ? new int[][]{{b.x0 + 1, b.z0 + i}, {b.x1 - 2, b.z0 + i}} : new int[][]{{b.x0 + i, b.z0 + 1}, {b.x0 + i, b.z1 - 2}};
                for (int[] s : spots) if (g.get(s[0], 3, s[1]) == null && b.contains(s[0], s[1]) && !b.ring(s[0], s[1])) g.set(s[0], 3, s[1], Material.LIGHT, Stage.INTERIOR);
            }
        }
    }

    // ------------------------------------------------------------------ verificação

    private static void verify(Grid g, Collection<Geo> geos, int minX, int maxX, int minZ, int maxZ, int maxY, List<String> errors) {
        // 1) acesso: de fora, andando pelo ar e pelas portas, chega-se ao piso de cada cômodo
        Set<Long> seen = new HashSet<>();
        ArrayDeque<int[]> q = new ArrayDeque<>();
        int sx = minX - 1, sz = minZ - 1;
        q.add(new int[]{sx, 1, sz});
        seen.add(Grid.key(sx, 1, sz));
        int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (!q.isEmpty()) {
            int[] c = q.poll();
            for (int[] d : dirs) {
                int x = c[0] + d[0], y = c[1] + d[1], z = c[2] + d[2];
                if (x < minX - 1 || x > maxX + 1 || z < minZ - 1 || z > maxZ + 1 || y < 1 || y > maxY + 2) continue;
                if (g.solid(x, y, z)) continue;
                if (seen.add(Grid.key(x, y, z))) q.add(new int[]{x, y, z});
            }
        }
        for (Geo m : geos) {
            if (!m.type.solid || m.box == null) continue;
            if (m.type == ModuleType.WALL_SEGMENT || m.type == ModuleType.GATE || m.type == ModuleType.STAIR || m.type == ModuleType.BRIDGE_SPAN) continue;
            Box b = m.box;
            int x = b.cx(), z = b.cz();
            if (m.type == ModuleType.APSE) z = b.z0 + Math.max(1, m.rows - 2);
            if (g.solid(x, 1, z)) continue; // altar ou pilar no centro: o vão ao lado vale
            if (!seen.contains(Grid.key(x, 1, z)) && !seen.contains(Grid.key(x + 1, 1, z)) && !seen.contains(Grid.key(x - 1, 1, z))
                    && !seen.contains(Grid.key(x, 1, z + 1)) && !seen.contains(Grid.key(x, 1, z - 1)))
                errors.add("cômodo sem acesso: " + m.mod.id + " (" + m.type + ") não tem porta ligada ao exterior");
        }
        // 2) telhado fechado sobre os cômodos
        for (Geo m : geos) {
            if (m.type != ModuleType.HALL && m.type != ModuleType.TOWER && m.type != ModuleType.APSE) continue;
            Box b = m.box;
            int open = 0;
            for (int x = b.x0; x < b.x1; x++)
                for (int z = b.z0; z < b.z1; z++) {
                    Cell floor = g.get(x, 0, z);
                    if (floor == null || b.ring(x, z) && m.type != ModuleType.TOWER) continue;
                    if (floor.m != Material.FLOOR) continue;
                    Cell hatch = g.get(x, 1, z);
                    if (hatch != null && hatch.m == Material.LADDER) continue; // alçapão da escada de mão
                    boolean covered = false;
                    for (int y = 2; y <= maxY && !covered; y++) covered = g.solid(x, y, z);
                    if (!covered) open++;
                }
            if (open > 0) errors.add("telhado aberto: " + open + " célula(s) do piso de " + m.mod.id + " veem o céu");
        }
        // 3) nada flutua: todo bloco estrutural liga-se à fundação por faces
        Set<Long> ok = new HashSet<>();
        ArrayDeque<long[]> bq = new ArrayDeque<>();
        for (long k : g.cells.keySet()) if (Grid.ky(k) == -1) {
            ok.add(k);
            bq.add(new long[]{k});
        }
        while (!bq.isEmpty()) {
            long k = bq.poll()[0];
            int x = Grid.kx(k), y = Grid.ky(k), z = Grid.kz(k);
            for (int[] d : dirs) {
                long n = Grid.key(x + d[0], y + d[1], z + d[2]);
                Cell c = g.cells.get(n);
                if (c == null || !structural(c.m) || !ok.add(n)) continue;
                bq.add(new long[]{n});
            }
        }
        int floating = 0;
        for (var e : g.cells.entrySet()) if (structural(e.getValue().m) && !ok.contains(e.getKey())) floating++;
        if (floating > 0) errors.add("blocos flutuantes: " + floating + " bloco(s) sem apoio");
    }

    // ------------------------------------------------------------------ saída

    private static Result emit(BuildingProgram p, Style style, Grid g, Collection<Geo> geos, int minX, int maxX, int minZ, int maxZ, int maxY, List<String> warnings) {
        List<long[]> order = new ArrayList<>();
        for (var e : g.cells.entrySet()) order.add(new long[]{e.getValue().stage.ordinal(), Grid.ky(e.getKey()), Grid.kx(e.getKey()), Grid.kz(e.getKey())});
        // limpeza do terreno: o vão interior dos cômodos (y 1..2) sai antes da fundação
        List<Placement> placements = new ArrayList<>();
        List<StageSpan> spans = new ArrayList<>();
        for (Geo m : geos) {
            if (m.type != ModuleType.HALL && m.type != ModuleType.TOWER && m.type != ModuleType.CLOISTER && m.type != ModuleType.APSE) continue;
            Box b = m.box;
            for (int x = b.x0; x < b.x1; x++)
                for (int z = b.z0; z < b.z1; z++)
                    for (int y = 1; y <= 2; y++)
                        if (g.get(x, y, z) == null) placements.add(new Placement(x - minX, y, z - minZ, Material.AIR, Facing.NONE));
        }
        placements.sort(Comparator.comparingInt(Placement::y).thenComparingInt(Placement::x).thenComparingInt(Placement::z));
        if (!placements.isEmpty()) spans.add(new StageSpan(Stage.FOUNDATION, 0, placements.size()));
        order.sort((a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0]) : a[1] != b[1] ? Long.compare(a[1], b[1]) : a[2] != b[2] ? Long.compare(a[2], b[2]) : Long.compare(a[3], b[3]));
        int solid = 0;
        Map<Material, Integer> counts = new EnumMap<>(Material.class);
        Stage cur = null;
        int from = placements.size();
        for (long[] o : order) {
            int x = (int) o[2], y = (int) o[1], z = (int) o[3];
            Cell c = g.get(x, y, z);
            if (cur != c.stage) {
                if (cur != null) spans.add(new StageSpan(cur, from, placements.size()));
                cur = c.stage;
                from = placements.size();
            }
            placements.add(new Placement(x - minX, y, z - minZ, c.m, c.f));
            if (structural(c.m)) {
                solid++;
                counts.merge(c.m, 1, Integer::sum);
            }
        }
        if (cur != null) spans.add(new StageSpan(cur, from, placements.size()));
        // fundação vem antes de tudo: a limpeza (AIR) é a primeira faixa
        Map<Material, String> palette = style.palette();
        int wood = 0, stone = 0;
        for (var e : counts.entrySet()) {
            String id = palette.getOrDefault(e.getKey(), "minecraft:stone_bricks");
            if (id.contains("plank") || id.contains("log") || id.contains("wood")) wood += e.getValue();
            else stone += e.getValue();
        }
        Map<ResourceType, Integer> cost = new EnumMap<>(ResourceType.class);
        cost.put(ResourceType.WOOD, Math.max(10, (int) Math.ceil(wood / 4.0) + 10));
        cost.put(ResourceType.STONE, Math.max(10, (int) Math.ceil(stone / 4.0)));
        String name = p.name == null || p.name.isBlank() ? "Obra em " + style.display : Text.truncate(p.name, 48);
        String id = "prog_" + Text.norm(name).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "") + "_" + Integer.toHexString(p.toJson().hashCode());
        Blueprint bp = new Blueprint(id, name, Blueprint.Category.CIVIC, maxX - minX + 1, maxY + 3, maxZ - minZ + 1, Collections.unmodifiableMap(cost), 0,
                List.copyOf(placements), "program", Collections.unmodifiableMap(palette));
        return new Result(bp, List.copyOf(spans), List.of(), List.copyOf(warnings), solid);
    }
}
