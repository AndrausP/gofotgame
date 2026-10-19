package com.kingdomsai.core.skill;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.kingdomsai.core.KingdomsCore;
import com.kingdomsai.core.common.Pos;
import com.kingdomsai.core.common.Text;
import com.kingdomsai.core.construction.Blueprint;
import com.kingdomsai.core.construction.Building;
import com.kingdomsai.core.construction.VillageWall;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.npc.Npc;
import com.kingdomsai.core.npc.Office;
import com.kingdomsai.core.npc.Profession;
import com.kingdomsai.core.port.PhysicalPort;
import com.kingdomsai.core.port.PhysicalPort.BlockInfo;
import com.kingdomsai.core.port.PhysicalPort.Recipe;
import com.kingdomsai.core.work.ChainValidator;
import com.kingdomsai.core.work.Place;

import java.util.*;
import java.util.function.Predicate;

/**
 * Transforma uma ordem física em tarefas e valida tudo ANTES de o NPC mexer um dedo:
 *
 * - Quebrar: área carregada, perto do rei, nunca dentro de construções nem em terra de outro reino;
 *   pula baús/fornalhas (block entities), blocos indestrutíveis e vizinhos de água/lava (sem inundação);
 *   cava de cima para baixo e deixa uma escada para sair de buracos fundos; avisa quando falta a ferramenta.
 * - Árvore: só tronco com folhas (árvore natural, não a parede de uma casa); replanta a muda.
 * - Baús: só do próprio reino (ou do rei, ao lado dele); confere o que tem dentro.
 * - Fabricar: receita do jogo, bancada/fornalha por perto, ofício certo (ferramentas de metal = ferreiro).
 *   Ingredientes que faltam viram tarefas automáticas: buscar no baú ou fabricar antes
 *   (picareta → gravetos → tábuas → toras), até 3 níveis.
 * - Mochila com limite: o que passar disso vai para o baú do armazém no meio do trabalho.
 */
public final class JobPlanner {
    public static final int MAX_VOLUME = 343;
    public static final int MAX_SIDE = 32;
    public static final int KING_RANGE = 64;
    public static final int MAX_TREE_LOGS = 96;
    public static final int MAX_LEAVES = 160;

    public record Plan(List<String> errors, List<String> warnings, List<String> lines, PhysicalJob job) {
        public boolean ok() {
            return errors.isEmpty();
        }
    }

    private final KingdomsCore core;
    private final PhysicalPort port;
    private final Kingdom k;
    private final UUID player;
    private final List<String> errors = new ArrayList<>(), warnings = new ArrayList<>();
    private final List<PhysicalJob.Task> tasks = new ArrayList<>();
    private Map<String, Integer> bag;
    private Npc npc;
    private String lastProduct;
    /** Planejando um lote de trabalho contínuo (sem guardar ao fim de cada lote; local já autorizado). */
    private boolean laborMode;
    private Map<String, String> laborSpec;

    private JobPlanner(KingdomsCore core, Kingdom k, UUID player) {
        this.core = core;
        this.port = core.physical();
        this.k = k;
        this.player = player;
    }

    // ------------------------------------------------------------------ esquema

    /** Lê as tarefas dos parâmetros (kind=... ou tasks=[...]). @throws IllegalArgumentException se mal formado. */
    public static List<Map<String, String>> specs(Map<String, String> p) {
        List<Map<String, String>> out = new ArrayList<>();
        String json = p.get("tasks");
        if (json != null && !json.isBlank()) {
            try {
                JsonElement root = JsonParser.parseString(json);
                if (!root.isJsonArray()) throw new IllegalArgumentException("tasks deve ser uma lista JSON.");
                for (JsonElement e : root.getAsJsonArray()) {
                    if (!e.isJsonObject()) continue;
                    Map<String, String> m = new LinkedHashMap<>();
                    for (var en : e.getAsJsonObject().entrySet())
                        if (en.getValue().isJsonPrimitive()) m.put(en.getKey(), en.getValue().getAsString());
                        else if (en.getValue().isJsonObject())
                            for (var inner : en.getValue().getAsJsonObject().entrySet()) m.put(inner.getKey(), inner.getValue().getAsString());
                    m.putIfAbsent("kind", m.getOrDefault("do", m.getOrDefault("type", "")));
                    out.add(m);
                }
            } catch (JsonParseException | IllegalStateException | UnsupportedOperationException e) {
                throw new IllegalArgumentException("tasks não é um JSON válido: " + e.getMessage());
            }
        } else if (p.get("kind") != null) {
            out.add(new LinkedHashMap<>(p));
        }
        if (out.isEmpty()) throw new IllegalArgumentException("JOB precisa de kind (break|dig|tunnel|clear|chop|clear_trees|gather|labor|take|put|craft|give) ou tasks.");
        if (out.size() > 8) throw new IllegalArgumentException("No máximo 8 tarefas por ordem.");
        for (Map<String, String> m : out)
            if (kindOf(m.get("kind")) == null) throw new IllegalArgumentException("Tarefa desconhecida: «" + m.get("kind") + "».");
        if (truthy(p.get("give")) && out.stream().noneMatch(m -> "give".equals(kindOf(m.get("kind")))))
            out.add(new LinkedHashMap<>(Map.of("kind", "give")));
        return out;
    }

    static String kindOf(String s) {
        String n = Text.norm(s);
        return switch (n) {
            case "break", "quebrar", "quebre", "mine_block" -> "break";
            case "dig", "cavar", "cave", "escavar", "buraco", "hole" -> "dig";
            case "tunnel", "tunel", "galeria" -> "tunnel";
            case "clear", "limpar", "limpe", "aplainar" -> "clear";
            case "clear_trees", "desmatar", "desmate", "derrubar_arvores", "limpar_arvores", "arvores", "trees" -> "clear_trees";
            case "gather", "coletar", "colete", "coletem", "buscar", "busque", "juntar", "junte", "extrair", "minerar", "minere", "collect", "mine" -> "gather";
            case "labor", "trabalhar", "trabalhe", "produzir", "produza", "produce", "work" -> "labor";
            case "chop", "cortar", "corte", "arvore", "tree" -> "chop";
            case "take", "pegar", "pegue", "retirar", "withdraw" -> "take";
            case "put", "guardar", "guarde", "depositar", "deposit", "store" -> "put";
            case "craft", "fabricar", "fabrique", "fazer", "faca", "criar", "crie", "forjar", "smelt", "fundir" -> "craft";
            case "give", "entregar", "entregue", "dar" -> "give";
            default -> null;
        };
    }

    // ------------------------------------------------------------------ plano

    public static Plan plan(KingdomsCore core, Kingdom k, UUID player, Map<String, String> params) {
        JobPlanner jp = new JobPlanner(core, k, player);
        return jp.run(params);
    }

    private Plan run(Map<String, String> params) {
        List<Map<String, String>> specs = specs(params);
        npc = chooseNpc(params.get("npc"), kindOf(specs.get(0).get("kind")), specs);
        if (npc == null) return done();
        bag = new TreeMap<>(npc.bag);
        if (npc.pos != null && core.playerPos(player) != null && npc.pos.distXZ(core.playerPos(player)) > 400)
            errors.add(npc.name + " está longe demais (" + (int) npc.pos.distXZ(core.playerPos(player)) + " blocos). Chame-o antes (tecla G).");
        for (Map<String, String> s : specs) {
            int before = errors.size();
            switch (kindOf(s.get("kind"))) {
                case "break", "dig", "tunnel", "clear" -> area(s);
                case "chop" -> chop(s);
                case "clear_trees" -> clearTrees(s);
                case "labor" -> laborPlan(s);
                case "gather" -> gather(s);
                case "take" -> take(s);
                case "put" -> put(s);
                case "craft" -> craft(s);
                case "give" -> give(s);
                default -> {
                }
            }
            if (errors.size() > before) break; // o resto depende desta tarefa
        }
        if (errors.isEmpty() && Inventory.slotsUsed(bag) > Inventory.SLOTS)
            warnings.add("Rende mais do que cabe na mochila (" + Inventory.SLOTS + " espaços): " + npc.name + " vai ao armazém esvaziar no meio do trabalho.");
        if (errors.isEmpty() && (!tasks.isEmpty() || laborSpec != null)) prepareKit();
        return done();
    }

    private Plan done() {
        if (!errors.isEmpty() || tasks.isEmpty() && laborSpec == null) {
            if (errors.isEmpty()) errors.add("Nada a fazer.");
            return new Plan(errors, warnings, List.of(), null);
        }
        PhysicalJob job = new PhysicalJob();
        job.kingdomId = k.id;
        job.npcId = npc.id;
        job.orderedBy = player;
        job.tasks.addAll(tasks);
        if (laborSpec != null) {
            job.continuous = true;
            job.labor = laborSpec.get("labor");
            job.site = new Pos(num(laborSpec.get("x")), num(laborSpec.get("y")), num(laborSpec.get("z")));
            job.radius = num(laborSpec.get("radius"));
            job.quota = num(laborSpec.getOrDefault("quota", "0"));
            job.oreId = laborSpec.getOrDefault("ore", "");
            job.tunnelDir = num(laborSpec.getOrDefault("dir", "0"));
            job.tunnelLength = num(laborSpec.getOrDefault("tunnel", "0"));
            job.name = Text.truncate(laborSpec.get("name"), 48);
        } else {
            PhysicalJob.Task main = tasks.stream().filter(t -> !t.auto).findFirst().orElse(tasks.get(0));
            job.name = Text.truncate(main.label, 48);
        }
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < tasks.size(); i++)
            lines.add((i + 1) + ". " + tasks.get(i).label + (tasks.get(i).auto ? " (planejado)" : ""));
        if (job.continuous)
            lines.add("↻ " + job.name + " — repete: volta ao armazém quando a mochila enche, troca ferramenta gasta, come da ração, dorme à noite.");
        return new Plan(errors, warnings, lines, job);
    }

    // ------------------------------------------------------------------ quem faz

    private Npc chooseNpc(String name, String firstKind, List<Map<String, String>> specs) {
        if (name != null && !name.isBlank()) {
            Npc n = core.findNpc(k.id, name);
            if (n == null) errors.add("Não encontrei " + name + " em " + k.name + ".");
            else if (n.office == Office.KING) errors.add("O rei não pega no pesado.");
            return errors.isEmpty() ? n : null;
        }
        Profession want = switch (firstKind) {
            case "break", "dig", "tunnel", "clear" -> Profession.MINER;
            case "chop", "clear_trees" -> Profession.LUMBERJACK;
            case "labor" -> {
                String l = laborOf(specs.get(0));
                yield "wood".equals(l) ? Profession.LUMBERJACK : "farm".equals(l) ? Profession.FARMER : Profession.MINER;
            }
            case "gather" -> {
                String it = Text.norm(specs.get(0).getOrDefault("item", "pedra"));
                yield it.matches("madeira|lenha|troncos?") ? Profession.LUMBERJACK : it.matches("terra|areia|cascalho|argila") ? Profession.PEASANT : Profession.MINER;
            }
            default -> null;
        };
        for (Map<String, String> s : specs)
            if ("craft".equals(kindOf(s.get("kind")))) {
                String item = ItemNames.resolve(s.get("item"));
                if (item != null && ItemNames.smithing(item)) want = Profession.BLACKSMITH;
            }
        Pos king = core.playerPos(player);
        Npc best = null;
        double bestScore = -1e9;
        for (Npc n : core.citizens(k.id)) {
            if (n.office == Office.KING || n.profession.isMilitary()) continue;
            double score = (want != null && n.profession == want ? 1000 : 0) - (n.jobId != null ? 500 : 0)
                    - (king != null && n.pos != null ? n.pos.distXZ(king) : 0);
            if (score > bestScore) {
                bestScore = score;
                best = n;
            }
        }
        if (best == null) errors.add("Não há ninguém disponível para isso.");
        return best;
    }

    /** Ferramentas que o NPC tem: as do ofício + as que estiverem na mochila. */
    /** Ferramentas que o NPC TEM de verdade (na mochila; quebram com o uso e são repostas no armazém). */
    public static Set<String> tools(Npc n) {
        Set<String> t = new HashSet<>();
        for (var e : n.bag.entrySet()) {
            String type = Inventory.toolType(e.getKey());
            if (type != null && e.getValue() > 0) t.add(type);
        }
        return t;
    }

    /** Ferramentas com que ele vai trabalhar: as da mochila + as que vai buscar no armazém antes de começar. */
    private Set<String> usable() {
        Set<String> t = tools(npc);
        for (var e : k.goods.entrySet()) {
            String type = Inventory.toolType(e.getKey());
            if (type != null && e.getValue() > 0) t.add(type);
        }
        return t;
    }

    /** O reino tem uma ferramenta deste tipo guardada? */
    public static boolean kingdomHasTool(Kingdom k, String type) {
        for (var e : k.goods.entrySet()) if (e.getValue() > 0 && type.equals(Inventory.toolType(e.getKey()))) return true;
        return false;
    }

    /** Onde fica o "armazém" para ir guardar/buscar: baú do armazém (ou do Salão Real); sem baús, a praça da vila. */
    public static Pos storagePoint(KingdomsCore core, Kingdom k) {
        List<Pos> treasury = core.treasury().chests(k);
        if (!treasury.isEmpty()) return treasury.get(0);
        Building b = ChainValidator.findBuilding(core, k, Place.STORAGE, true);
        if (b != null && b.origin != null && b.origin.y() != Integer.MIN_VALUE) return b.entrance();
        return k.marker(com.kingdomsai.core.kingdom.Marker.GATHER, k.center);
    }

    public static PhysicalJob.Task resupplyTask(Pos at, Collection<String> toolTypes) {
        PhysicalJob.Task t = new PhysicalJob.Task();
        t.kind = PhysicalJob.Kind.RESUPPLY;
        t.at = at;
        t.item = String.join(",", toolTypes);
        t.auto = true;
        List<String> names = new ArrayList<>();
        for (String type : toolTypes) names.add(toolName(type));
        t.label = "passar no armazém: " + (names.isEmpty() ? "" : String.join(", ", names) + ", ") + "ração e o kit do ofício";
        return t;
    }

    public static PhysicalJob.Task storeTask(Pos at) {
        PhysicalJob.Task t = new PhysicalJob.Task();
        t.kind = PhysicalJob.Kind.STORE;
        t.at = at;
        t.auto = true;
        t.label = "guardar no armazém o que juntou";
        return t;
    }

    // ------------------------------------------------------------------ quebrar

    private void area(Map<String, String> s) {
        String kind = kindOf(s.get("kind"));
        Pos a, b;
        int[] size = size(s.get("size"));
        if (s.get("x") != null && s.get("y") != null && s.get("z") != null) {
            a = new Pos(num(s.get("x")), num(s.get("y")), num(s.get("z")));
            b = s.get("x2") != null ? new Pos(num(s.get("x2")), num(s.get("y2") == null ? s.get("y") : s.get("y2")), num(s.get("z2"))) : a;
        } else {
            KingdomsCore.Look look = core.playerLook(player);
            if (look == null || look.block() == null) {
                errors.add("Mire no bloco (ou informe x y z) para eu saber onde.");
                return;
            }
            Pos l = look.block();
            switch (kind) {
                case "dig" -> {
                    int w = size == null ? 3 : size[0], d = size == null ? 3 : size.length > 1 ? size[1] : size[0];
                    int depth = size == null ? 3 : size.length > 2 ? size[2] : Math.max(1, Math.min(w, d));
                    a = new Pos(l.x() - (w - 1) / 2, l.y() - depth + 1, l.z() - (d - 1) / 2);
                    b = new Pos(a.x() + w - 1, l.y(), a.z() + d - 1);
                }
                case "tunnel" -> {
                    int len = s.get("length") != null ? num(s.get("length")) : size != null ? size[0] : 8;
                    int[] dir = dir(look.facing());
                    a = l;
                    b = l.offset(dir[0] * (len - 1), 1, dir[1] * (len - 1));
                }
                case "clear" -> {
                    int w = size == null ? 5 : size[0], d = size == null ? 5 : size.length > 1 ? size[1] : size[0];
                    a = new Pos(l.x() - (w - 1) / 2, l.y() + 1, l.z() - (d - 1) / 2);
                    b = new Pos(a.x() + w - 1, l.y() + 4, a.z() + d - 1);
                }
                default -> a = b = l;
            }
        }
        Pos lo = new Pos(Math.min(a.x(), b.x()), Math.min(a.y(), b.y()), Math.min(a.z(), b.z()));
        Pos hi = new Pos(Math.max(a.x(), b.x()), Math.max(a.y(), b.y()), Math.max(a.z(), b.z()));
        int w = hi.x() - lo.x() + 1, h = hi.y() - lo.y() + 1, d = hi.z() - lo.z() + 1;
        if (w > MAX_SIDE || h > MAX_SIDE || d > MAX_SIDE || (long) w * h * d > MAX_VOLUME) {
            errors.add("Área grande demais (" + w + "×" + d + "×" + h + "): no máximo " + MAX_VOLUME + " blocos por ordem e " + MAX_SIDE + " de lado.");
            return;
        }
        if (!nearKing(center(lo, hi))) return;
        if (!port.isLoaded(lo) || !port.isLoaded(hi)) {
            errors.add("Essa área não está carregada. Chegue mais perto.");
            return;
        }
        Set<String> tools = usable();
        int prot = 0, foreign = 0, chests = 0, fluid = 0, unbreakable = 0, noDrop = 0, claimed = 0;
        String protName = null, foreignName = null, missingTool = null;
        boolean stairs = kind.equals("dig") && h >= 3 && Math.max(w, d) >= 2;
        List<Pos> queue = new ArrayList<>();
        for (int y = hi.y(); y >= lo.y(); y--)
            for (int x = lo.x(); x <= hi.x(); x++)
                for (int z = lo.z(); z <= hi.z(); z++) {
                    Pos p = new Pos(x, y, z);
                    BlockInfo info = port.block(p);
                    if (info.air() || info.fluid()) {
                        if (info.fluid()) fluid++;
                        continue;
                    }
                    Building owner = protectedBy(p);
                    if (owner != null) {
                        prot++;
                        protName = owner.blueprint().displayName();
                        continue;
                    }
                    Kingdom terr = foreignOwner(p);
                    if (terr != null) {
                        foreign++;
                        foreignName = terr.name;
                        continue;
                    }
                    if (info.blockEntity()) {
                        chests++;
                        continue;
                    }
                    if (!port.mayBreak(player, p)) {
                        claimed++;
                        continue;
                    }
                    if (!info.breakable() || info.hardness() < 0) {
                        unbreakable++;
                        continue;
                    }
                    if (info.nearFluid()) {
                        fluid++;
                        continue;
                    }
                    if (stairs && keepForStairs(p, lo, hi)) continue;
                    if (info.needsTool() && info.tool() != null && !tools.contains(info.tool())) {
                        noDrop++;
                        missingTool = info.tool();
                    } else if (info.drop() != null) bag.merge(info.drop(), 1, Integer::sum);
                    queue.add(p);
                }
        if (kind.equals("tunnel")) {
            Pos start = a;
            queue.sort(Comparator.comparingDouble((Pos p) -> Math.abs(p.x() - start.x()) + Math.abs(p.z() - start.z())).thenComparing(p -> -p.y()));
        }
        if (foreign > 0) {
            errors.add("Parte disso é terra de " + foreignName + " — quebrar lá seria invasão.");
            return;
        }
        if (queue.isEmpty()) {
            errors.add(claimed > 0 && prot == 0 ? "Essa área é protegida (claim de outro mod, spawn do servidor ou borda do mundo): Vossa Majestade não pode quebrar ali, e eu também não."
                    : prot > 0 ? "Isso é parte de «" + protName + "»: não destruo construções do reino."
                    : chests > 0 ? "Só há baús/fornalhas aí — não quebro contêineres (os itens se perderiam)."
                    : fluid > 0 ? "É água/lava ou está colado nelas: quebrar inundaria a área."
                    : "Não há nada que eu possa quebrar aí.");
            return;
        }
        if (prot > 0) warnings.add(prot + " bloco(s) de «" + protName + "» foram preservados.");
        if (claimed > 0) warnings.add(claimed + " bloco(s) protegidos (claim/spawn do servidor) ficam.");
        if (chests > 0) warnings.add(chests + " baú(s)/fornalha(s) ficam onde estão (contêineres não são quebrados).");
        if (fluid > 0) warnings.add(fluid + " bloco(s) perto de água/lava ficam (evita inundação).");
        if (unbreakable > 0) warnings.add(unbreakable + " bloco(s) indestrutíveis ignorados.");
        if (noDrop > 0) warnings.add(npc.name + " não tem " + toolName(missingTool) + ": " + noDrop + " bloco(s) vão demorar e não render nada.");
        if (stairs) warnings.add("Deixarei uma escada num canto para sair do buraco.");
        PhysicalJob.Task t = new PhysicalJob.Task();
        t.kind = PhysicalJob.Kind.BREAK;
        t.blocks = queue;
        t.total = queue.size();
        String what = switch (kind) {
            case "dig" -> "cavar " + w + "×" + d + "×" + h;
            case "tunnel" -> "abrir túnel de " + Math.max(w, d) + " blocos";
            case "clear" -> "limpar área " + w + "×" + d;
            default -> queue.size() == 1 ? "quebrar " + ItemNames.display(port.block(queue.get(0)).id()) : "quebrar " + queue.size() + " blocos";
        };
        t.label = what + " em " + lo.x() + " " + lo.y() + " " + lo.z() + " (" + queue.size() + " blocos)";
        tasks.add(t);
    }

    /** Escada no canto: na coluna i (ao longo de x), os blocos abaixo da camada i ficam — degraus de 1 bloco. */
    private static boolean keepForStairs(Pos p, Pos lo, Pos hi) {
        if (p.z() != lo.z()) return false;
        int col = p.x() - lo.x();
        int layer = hi.y() - p.y(); // 0 = camada de cima
        return col < hi.y() - lo.y() && layer > col;
    }

    // ------------------------------------------------------------------ árvore

    private void chop(Map<String, String> s) {
        Pos start;
        if (s.get("x") != null) start = new Pos(num(s.get("x")), num(s.get("y")), num(s.get("z")));
        else {
            KingdomsCore.Look look = core.playerLook(player);
            if (look == null || look.block() == null) {
                errors.add("Mire na árvore para eu saber qual cortar.");
                return;
            }
            start = look.block();
        }
        if (!nearKing(start)) return;
        if (!port.block(start).log()) {
            errors.add("Isso não é um tronco (" + ItemNames.display(port.block(start).id()) + ").");
            return;
        }
        Set<Pos> logs = new LinkedHashSet<>();
        ArrayDeque<Pos> q = new ArrayDeque<>(List.of(start));
        while (!q.isEmpty() && logs.size() < MAX_TREE_LOGS) {
            Pos p = q.poll();
            if (logs.contains(p) || Math.abs(p.x() - start.x()) > 6 || Math.abs(p.z() - start.z()) > 6) continue;
            if (!port.block(p).log()) continue;
            logs.add(p);
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = -1; dy <= 1; dy++)
                    for (int dz = -1; dz <= 1; dz++) if (dx != 0 || dy != 0 || dz != 0) q.add(p.offset(dx, dy, dz));
        }
        Set<Pos> leaves = new LinkedHashSet<>();
        for (Pos l : logs)
            for (int dx = -3; dx <= 3 && leaves.size() < MAX_LEAVES; dx++)
                for (int dy = -1; dy <= 3; dy++)
                    for (int dz = -3; dz <= 3; dz++) {
                        Pos p = l.offset(dx, dy, dz);
                        if (!leaves.contains(p) && port.block(p).leaves()) leaves.add(p);
                    }
        if (leaves.size() < 3) {
            errors.add("Isso não parece uma árvore (tronco sem folhas) — pode ser parte de uma construção.");
            return;
        }
        for (Pos p : logs)
            if (!port.mayBreak(player, p)) {
                errors.add("Essa árvore está numa área protegida (claim de outro mod ou spawn do servidor).");
                return;
            }
        for (Pos p : logs)
            if (protectedBy(p) != null) {
                errors.add("Essa madeira é parte de «" + protectedBy(p).blueprint().displayName() + "».");
                return;
            }
        if (foreignOwner(start) != null) {
            errors.add("Essa árvore está em terra de " + foreignOwner(start).name + ".");
            return;
        }
        Pos base = logs.stream().min(Comparator.comparingInt(Pos::y)).orElse(start);
        List<Pos> queue = new ArrayList<>(logs);
        queue.sort(Comparator.comparingInt(Pos::y));
        int high = 0;
        for (Iterator<Pos> it = queue.iterator(); it.hasNext(); ) {
            Pos p = it.next();
            if (p.y() - base.y() > 12) {
                it.remove();
                high++;
            }
        }
        List<Pos> leafQueue = new ArrayList<>(leaves);
        leafQueue.removeIf(p -> p.y() - base.y() > 12 || protectedBy(p) != null);
        leafQueue.sort(Comparator.comparingInt(Pos::y));
        String logId = port.block(start).id();
        Set<String> tools = usable();
        if (!tools.contains("axe")) warnings.add(npc.name + " não tem machado: vai demorar mais.");
        bag.merge(port.block(start).drop() == null ? logId : port.block(start).drop(), queue.size(), Integer::sum);
        if (high > 0) warnings.add(high + " tora(s) altas demais ficam (mais de 12 blocos acima do chão).");
        PhysicalJob.Task t = new PhysicalJob.Task();
        t.kind = PhysicalJob.Kind.CHOP;
        t.blocks = new ArrayList<>(queue);
        t.blocks.addAll(leafQueue);
        t.total = t.blocks.size();
        t.label = "cortar a árvore (" + queue.size() + " toras" + (leafQueue.isEmpty() ? "" : ", " + leafQueue.size() + " folhas") + ") em "
                + base.x() + " " + base.y() + " " + base.z();
        tasks.add(t);
        String sapling = saplingFor(logId);
        BlockInfo ground = port.block(base.offset(0, -1, 0));
        if (sapling != null && ground.id().matches(".*(dirt|grass|podzol|mud|moss).*")) {
            PhysicalJob.Task p = new PhysicalJob.Task();
            p.kind = PhysicalJob.Kind.PLANT;
            p.at = base;
            p.block = sapling;
            p.auto = true;
            p.label = "replantar " + ItemNames.display(sapling) + " (se cair uma das folhas)";
            tasks.add(p);
        }
    }

    public static String saplingFor(String logId) {
        if (logId == null) return null;
        String p = logId.substring(logId.indexOf(':') + 1);
        if (p.startsWith("mangrove")) return "minecraft:mangrove_propagule";
        if (p.startsWith("stripped_") || p.startsWith("crimson") || p.startsWith("warped")) return null;
        for (String wood : new String[]{"dark_oak", "oak", "spruce", "birch", "jungle", "acacia", "cherry"})
            if (p.startsWith(wood + "_")) return "minecraft:" + wood + "_sapling";
        return null;
    }

    // ------------------------------------------------------------------ baús

    private void take(Map<String, String> s) {
        String spec = ItemNames.resolve(s.get("item"));
        if (spec == null) {
            errors.add("Não sei que item é «" + s.get("item") + "».");
            return;
        }
        Pos chest = chest(s.get("from"));
        if (chest == null) return;
        Map<String, Integer> contents = port.container(chest);
        Predicate<String> m = ItemNames.matcher(spec);
        int have = contents.entrySet().stream().filter(e -> m.test(e.getKey())).mapToInt(Map.Entry::getValue).sum();
        int want = num(s.getOrDefault("count", "0"));
        if (have == 0) {
            errors.add("O baú em " + chest + " não tem " + ItemNames.display(spec) + ".");
            return;
        }
        if (want <= 0) want = have;
        if (want > have) {
            warnings.add("O baú só tem " + have + " de " + ItemNames.display(spec) + " (pedido: " + want + ").");
            want = have;
        }
        addTake(chest, spec, want, contents, false);
    }

    private void addTake(Pos chest, String spec, int want, Map<String, Integer> contents, boolean auto) {
        Predicate<String> m = ItemNames.matcher(spec);
        int left = want;
        for (var e : contents.entrySet()) {
            if (left <= 0) break;
            if (!m.test(e.getKey())) continue;
            int n = Math.min(left, e.getValue());
            bag.merge(e.getKey(), n, Integer::sum);
            left -= n;
        }
        PhysicalJob.Task t = new PhysicalJob.Task();
        t.kind = PhysicalJob.Kind.TAKE;
        t.at = chest;
        t.item = spec;
        t.count = want;
        t.auto = auto;
        t.label = "pegar " + want + " " + ItemNames.display(spec) + " no baú em " + chest;
        tasks.add(t);
    }

    private void put(Map<String, String> s) {
        String spec = s.get("item") == null || s.get("item").isBlank() ? "#all" : ItemNames.resolve(s.get("item"));
        if (spec == null) {
            errors.add("Não sei que item é «" + s.get("item") + "».");
            return;
        }
        Pos chest = chest(s.get("to") != null ? s.get("to") : s.get("from"));
        if (chest == null) return;
        Predicate<String> m = ItemNames.matcher(spec);
        int have = bag.entrySet().stream().filter(e -> m.test(e.getKey())).mapToInt(Map.Entry::getValue).sum();
        if (have == 0) {
            errors.add(npc.name + " não terá " + ItemNames.display(spec) + " na mochila para guardar.");
            return;
        }
        int want = num(s.getOrDefault("count", "0"));
        if (want <= 0 || want > have) want = have;
        int left = want;
        for (var e : new ArrayList<>(bag.entrySet())) {
            if (left <= 0) break;
            if (!m.test(e.getKey())) continue;
            int n = Math.min(left, e.getValue());
            bag.merge(e.getKey(), -n, Integer::sum);
            left -= n;
        }
        bag.values().removeIf(v -> v <= 0);
        PhysicalJob.Task t = new PhysicalJob.Task();
        t.kind = PhysicalJob.Kind.PUT;
        t.at = chest;
        t.item = spec;
        t.count = want;
        t.label = spec.equals("#all") ? "guardar tudo (" + want + " itens) no baú em " + chest
                : "guardar " + want + " " + ItemNames.display(spec) + " no baú em " + chest;
        tasks.add(t);
    }

    /** "look" = baú na mira do rei · "storage" = baú do armazém · "x y z" · null = mira se for baú, senão armazém. */
    private Pos chest(String from) {
        Pos p = null;
        String f = from == null ? "" : Text.norm(from);
        KingdomsCore.Look look = core.playerLook(player);
        if (f.matches("-?\\d+[ ,]+-?\\d+[ ,]+-?\\d+")) {
            String[] v = f.split("[ ,]+");
            p = new Pos(num(v[0]), num(v[1]), num(v[2]));
        } else if (f.matches("storage|armazem|celeiro|deposito")) p = storageChest();
        else if (f.matches("look|mira|aqui|esse|este|esse bau|este bau")) p = look == null ? null : look.block();
        else {
            if (look != null && look.block() != null && port.container(look.block()) != null) p = look.block();
            else p = storageChest();
        }
        if (p == null) {
            errors.add(f.matches("storage|armazem|celeiro|deposito") || f.isEmpty()
                    ? "Não achei baú: mire num baú ou construa um armazém." : "Mire num baú para eu saber qual.");
            return null;
        }
        if (port.container(p) == null) {
            errors.add("Em " + p + " não há baú (é " + ItemNames.display(port.block(p).id()) + ").");
            return null;
        }
        UUID owner = core.state().territory.ownerAt(p);
        Pos king = core.playerPos(player);
        boolean mine = k.id.equals(owner) || (owner == null && king != null && king.distXZ(p) <= 12);
        if (!mine) {
            Kingdom o = owner == null ? null : core.kingdom(owner);
            errors.add(o != null ? "Esse baú é de " + o.name + ": mexer nele seria roubo." : "Esse baú não é do reino (fora do território e longe de Vossa Majestade).");
            return null;
        }
        return p;
    }

    private Pos storageChest() {
        Building b = ChainValidator.findBuilding(core, k, Place.STORAGE, true);
        if (b != null && b.origin.y() != Integer.MIN_VALUE) {
            Pos c = port.findNear(b.centerPos(), "minecraft:chest", 6);
            if (c == null) c = port.findNear(b.centerPos(), "minecraft:barrel", 6);
            if (c != null) return c;
        }
        // sem armazém: os baús do tesouro (Salão Real) — é lá que o estoque do reino mora
        List<Pos> treasury = core.treasury().chests(k);
        return treasury.isEmpty() ? null : treasury.get(0);
    }

    // ------------------------------------------------------------------ coleta na região

    /** Centro de uma coleta: x y z, a mira do rei, um marco (mina/bosque) ou onde o rei está. */
    private Pos gatherCenter(Map<String, String> s, com.kingdomsai.core.kingdom.Marker marker) {
        if (s.get("x") != null && s.get("z") != null)
            return new Pos(num(s.get("x")), s.get("y") == null ? core.world().surfaceY(num(s.get("x")), num(s.get("z"))) : num(s.get("y")), num(s.get("z")));
        if ("vila".equals(Text.norm(s.getOrDefault("around", "")))) return VillageWall.bounds(core, k).center(k.center.y());
        KingdomsCore.Look look = core.playerLook(player);
        boolean atLook = "look".equals(s.get("at"));
        if (!atLook && marker != null && k.markers.containsKey(marker)) return k.markers.get(marker);
        if (look != null && look.block() != null) return look.block();
        return core.playerPos(player);
    }

    /** "Limpe as árvores da região": derruba várias árvores (tronco + folhas, replanta) num raio; pula as protegidas. */
    private void clearTrees(Map<String, String> s) {
        Pos c = gatherCenter(s, null);
        if (c == null) {
            errors.add("Não sei onde: mire na região ou fique perto das árvores.");
            return;
        }
        boolean village = "vila".equals(Text.norm(s.getOrDefault("around", "")));
        int radius = Math.max(4, Math.min(24, num(s.getOrDefault("radius", village ? String.valueOf(VillageWall.bounds(core, k).radius() + 8) : "12"))));
        int max = Math.max(1, Math.min(20, num(s.getOrDefault("count", "12"))));
        List<Pos> bases = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++)
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) continue;
                for (int dy = -6; dy <= 10; dy++) {
                    Pos p = c.offset(dx, dy, dz);
                    BlockInfo b = port.block(p);
                    if (b.log() && !port.block(p.offset(0, -1, 0)).log()) {
                        bases.add(p);
                        break;
                    }
                }
            }
        bases.sort(Comparator.comparingDouble(p -> p.distSq(c)));
        int done = 0, skipped = 0;
        for (Pos b : bases) {
            if (done >= max) break;
            int errBefore = errors.size(), tasksBefore = tasks.size();
            Map<String, Integer> bagBefore = new TreeMap<>(bag);
            chop(new LinkedHashMap<>(Map.of("kind", "chop", "x", String.valueOf(b.x()), "y", String.valueOf(b.y()), "z", String.valueOf(b.z()))));
            if (errors.size() > errBefore) { // árvore protegida, de outro reino, longe, ou não é árvore: pula
                errors.subList(errBefore, errors.size()).clear();
                while (tasks.size() > tasksBefore) tasks.remove(tasks.size() - 1);
                bag.clear();
                bag.putAll(bagBefore);
                skipped++;
                continue;
            }
            done++;
        }
        if (done == 0) {
            errors.add("Não achei árvores que eu possa derrubar num raio de " + radius + " blocos" + (skipped > 0 ? " (" + skipped + " protegidas, longe ou de outro reino)" : "") + ".");
            return;
        }
        if (skipped > 0) warnings.add(skipped + " árvore(s) ficaram (protegidas, de construção, de outro reino ou longe do rei).");
        if (bases.size() - skipped > done) warnings.add("Há mais árvores na região: derrubo " + done + " por ordem (peça de novo para continuar).");
        deposit();
    }

    private static final Map<String, List<String>> GATHER_BLOCKS = Map.of(
            "pedra", List.of("minecraft:stone", "minecraft:andesite", "minecraft:diorite", "minecraft:granite", "minecraft:tuff", "minecraft:deepslate", "minecraft:cobblestone"),
            "terra", List.of("minecraft:dirt", "minecraft:grass_block", "minecraft:coarse_dirt"),
            "areia", List.of("minecraft:sand", "minecraft:red_sand"),
            "cascalho", List.of("minecraft:gravel"),
            "argila", List.of("minecraft:clay"),
            "carvao", List.of("minecraft:coal_ore", "minecraft:deepslate_coal_ore"),
            "ferro", List.of("minecraft:iron_ore", "minecraft:deepslate_iron_ore"),
            "cobre", List.of("minecraft:copper_ore", "minecraft:deepslate_copper_ore"));

    /** "Vá coletar pedra": acha os N blocos expostos mais perto (mina marcada, mira ou rei), quebra e guarda no armazém. */
    private void gather(Map<String, String> s) {
        String item = Text.norm(s.getOrDefault("item", "pedra")).replaceAll("s$", "");
        if (item.matches("madeira|lenha|tronco")) {
            Map<String, String> t = new LinkedHashMap<>(s);
            t.put("count", String.valueOf(Math.max(1, Math.min(20, (num(s.getOrDefault("count", "32")) + 4) / 5))));
            clearTrees(t);
            return;
        }
        if (item.equals("rocha")) item = "pedra";
        List<String> ids = GATHER_BLOCKS.get(item);
        if (ids == null) {
            errors.add("Não sei coletar «" + s.get("item") + "» (pedra, terra, areia, cascalho, argila, carvão, ferro, cobre, madeira).");
            return;
        }
        boolean ore = !item.matches("terra|areia|cascalho|argila");
        Pos c = gatherCenter(s, ore ? com.kingdomsai.core.kingdom.Marker.MINE : null);
        if (c == null) {
            errors.add("Não sei onde: mire no lugar, fique perto, ou marque a mina com a Bandeira.");
            return;
        }
        boolean fromMarker = ore && k.markers.containsKey(com.kingdomsai.core.kingdom.Marker.MINE) && c.equals(k.markers.get(com.kingdomsai.core.kingdom.Marker.MINE));
        if (!fromMarker && !nearKing(c)) return; // mina marcada é terra do reino: vale mesmo com o rei longe
        int radius = Math.max(4, Math.min(24, num(s.getOrDefault("radius", "16"))));
        int count = Math.max(1, Math.min(128, num(s.getOrDefault("count", "32"))));
        Set<String> tools = usable();
        List<Pos> found = new ArrayList<>();
        int prot = 0, foreign = 0;
        for (int dx = -radius; dx <= radius; dx++)
            for (int dz = -radius; dz <= radius; dz++)
                for (int dy = -8; dy <= 8; dy++) {
                    Pos p = c.offset(dx, dy, dz);
                    BlockInfo b = port.block(p);
                    if (!ids.contains(b.id())) continue;
                    if (!exposed(p)) continue; // só o que dá para alcançar sem cavar túnel
                    if (b.blockEntity() || b.nearFluid() || !b.breakable() || b.hardness() < 0) continue;
                    if (protectedBy(p) != null) {
                        prot++;
                        continue;
                    }
                    if (foreignOwner(p) != null) {
                        foreign++;
                        continue;
                    }
                    if (!port.mayBreak(player, p)) continue;
                    found.add(p);
                }
        if (found.isEmpty()) {
            errors.add("Não achei " + item + " exposta num raio de " + radius + " blocos" + (foreign > 0 ? " (a que há é de outro reino)" : prot > 0 ? " (só em construções)" : "")
                    + (ore && !k.markers.containsKey(com.kingdomsai.core.kingdom.Marker.MINE) ? ". Marque a mina com a Bandeira (\"marque aqui como mina\")." : "."));
            return;
        }
        Pos center = c;
        found.sort(Comparator.comparingDouble((Pos p) -> p.distSq(center)).thenComparing(p -> -p.y())); // perto e de cima para baixo
        List<Pos> queue = new ArrayList<>(found.subList(0, Math.min(count, found.size())));
        int noDrop = 0;
        String missing = null;
        for (Pos p : queue) {
            BlockInfo b = port.block(p);
            if (b.needsTool() && b.tool() != null && !tools.contains(b.tool())) {
                noDrop++;
                missing = b.tool();
            } else if (b.drop() != null) bag.merge(b.drop(), 1, Integer::sum);
        }
        if (noDrop > 0) warnings.add(npc.name + " não tem " + toolName(missing) + ": " + noDrop + " bloco(s) vão demorar e não render nada.");
        if (queue.size() < count) warnings.add("Só achei " + queue.size() + " bloco(s) de " + item + " expostos por perto.");
        PhysicalJob.Task t = new PhysicalJob.Task();
        t.kind = PhysicalJob.Kind.BREAK;
        t.blocks = queue;
        t.total = queue.size();
        t.label = "coletar " + queue.size() + " de " + item + " perto de " + c.x() + " " + c.y() + " " + c.z();
        tasks.add(t);
        deposit();
    }

    private boolean exposed(Pos p) {
        for (Pos n : new Pos[]{p.offset(1, 0, 0), p.offset(-1, 0, 0), p.offset(0, 1, 0), p.offset(0, -1, 0), p.offset(0, 0, 1), p.offset(0, 0, -1)})
            if (port.block(n).air()) return true;
        return false;
    }

    /** No fim da coleta, guarda tudo no armazém (ou nos baús do Salão): é lá que o estoque do reino mora. */
    private void deposit() {
        if (bag.isEmpty() || laborMode) return;
        if (storageChest() == null)
            warnings.add("Sem armazém nem baú no Salão Real: guarda na praça da vila (só no registro do reino).");
        tasks.add(storeTask(storagePoint(core, k)));
    }

    // ------------------------------------------------------------------ kit e trabalho contínuo

    /** Antes de começar: tem a ferramenta do serviço, ração e espaço? Se não, passa no armazém primeiro. */
    private void prepareKit() {
        Set<String> need = new TreeSet<>();
        for (PhysicalJob.Task t : tasks)
            switch (t.kind) {
                case CHOP -> need.add("axe");
                case FARM -> need.add("hoe");
                case BREAK -> {
                    for (int i = 0; i < Math.min(4096, t.blocks.size()); i++) {
                        String tool = port.block(t.blocks.get(i)).tool();
                        if (tool != null) need.add(tool);
                    }
                }
                default -> {
                }
            }
        if (laborSpec != null) need.add(switch (laborSpec.get("labor")) {
            case "wood" -> "axe";
            case "farm" -> "hoe";
            default -> "pickaxe";
        });
        Set<String> have = tools(npc);
        List<String> fetch = new ArrayList<>();
        for (String type : need) {
            if (have.contains(type)) continue;
            if (kingdomHasTool(k, type)) fetch.add(type);
            else if (warnings.stream().noneMatch(w -> w.contains("não tem " + toolName(type)))) warnings.add(npc.name + " não tem " + toolName(type) + " e não há no armazém: vai devagar"
                    + (type.equals("pickaxe") ? " e pedra/minério não rendem nada" : "") + ". O ferreiro repõe a reserva.");
        }
        boolean kitLow = Kit.wantsResupply(npc);
        boolean crowded = Inventory.freeSlots(npc) < 6;
        if (fetch.isEmpty() && !kitLow && !crowded) return;
        Pos store = storagePoint(core, k);
        List<PhysicalJob.Task> pre = new ArrayList<>();
        if (crowded) pre.add(storeTask(store));
        pre.add(resupplyTask(store, fetch));
        tasks.addAll(0, pre);
    }

    static String laborOf(Map<String, String> s) {
        String w = Text.norm(s.getOrDefault("labor", s.getOrDefault("item", "madeira")));
        if (w.matches("wood|madeira|lenha|toras?|troncos?|floresta|bosque|lenhador")) return "wood";
        if (w.matches("stone|pedra|pedras|rocha|pedregulho|pedreira")) return "stone";
        if (w.matches("ore|ferro|carvao|cobre|ouro|minerio|minerios|mina|minerar")) return "ore";
        if (w.matches("farm|fazenda|lavoura|trigo|plantacao|colheita|comida|plantar|colher")) return "farm";
        return null;
    }

    /** "Produza madeira", "trabalhe na mina", "cuide da fazenda": trabalho que se repete, num local do reino. */
    private void laborPlan(Map<String, String> s) {
        String labor = laborOf(s);
        if (labor == null) {
            errors.add("Que trabalho? madeira, pedra, minério (ferro, carvão, cobre) ou fazenda.");
            return;
        }
        String w = Text.norm(s.getOrDefault("labor", "") + " " + s.getOrDefault("item", ""));
        String ore = !labor.equals("ore") ? "" : w.contains("carvao") ? "minecraft:coal_ore" : w.contains("cobre") ? "minecraft:copper_ore"
                : w.contains("ouro") ? "minecraft:gold_ore" : "minecraft:iron_ore";
        Pos site = null;
        int radius = Math.max(8, Math.min(32, num(s.getOrDefault("radius", "16"))));
        if (s.get("x") != null && s.get("z") != null) site = gatherCenter(s, null);
        else switch (labor) {
            case "wood" -> site = k.markers.containsKey(com.kingdomsai.core.kingdom.Marker.FOREST) ? k.markers.get(com.kingdomsai.core.kingdom.Marker.FOREST) : gatherCenter(s, null);
            case "stone", "ore" -> site = k.markers.containsKey(com.kingdomsai.core.kingdom.Marker.MINE) ? k.markers.get(com.kingdomsai.core.kingdom.Marker.MINE) : gatherCenter(s, null);
            default -> {
                Building farm = nearestFarm(core, k, core.playerPos(player) != null ? core.playerPos(player) : k.center);
                if (farm == null) {
                    errors.add("Não há fazenda pronta para cuidar. Construa uma (\"construa uma fazenda\").");
                    return;
                }
                site = farm.centerPos();
                radius = Math.max(farm.blueprint().sizeX(), farm.blueprint().sizeZ());
            }
        }
        if (site == null) {
            errors.add(labor.equals("wood") ? "Onde? Marque o bosque com a Bandeira (\"marque aqui como bosque\") ou mire nas árvores."
                    : "Onde? Marque a mina com a Bandeira (\"marque aqui como mina\") ou mire no lugar.");
            return;
        }
        Kingdom owner = core.kingdom(core.state().territory.ownerAt(site));
        if (owner != k) {
            errors.add("Esse lugar " + (owner == null ? "não é território do reino" : "é de " + owner.name) + ": trabalho contínuo só em terra do reino (reivindique antes).");
            return;
        }
        int quota = num(s.getOrDefault("quota", s.getOrDefault("count", "0")));
        String what = switch (labor) {
            case "wood" -> "produzir madeira";
            case "stone" -> "tirar pedra";
            case "ore" -> "minerar " + ItemNames.display(ore).replace("minério de ", "");
            default -> "cuidar da fazenda";
        };
        laborSpec = new LinkedHashMap<>(Map.of("labor", labor, "x", String.valueOf(site.x()), "y", String.valueOf(site.y()), "z", String.valueOf(site.z()),
                "radius", String.valueOf(radius), "quota", String.valueOf(Math.max(0, quota)), "ore", ore,
                "name", what + " em " + site.x() + " " + site.z() + (quota > 0 ? " (meta " + quota + ")" : " (sem fim)")));
        // primeiro lote já planejado (o resto o SkillSystem planeja conforme trabalha)
        PhysicalJob probe = new PhysicalJob();
        probe.labor = labor;
        probe.site = site;
        probe.radius = radius;
        probe.oreId = ore;
        laborMode = true;
        int before = errors.size();
        batch(probe);
        if (errors.size() > before) { // nada disponível agora: começa esperando (árvores crescem, plantação amadurece)
            warnings.add(errors.get(before) + " " + npc.name + " fica no local e tenta de novo.");
            errors.subList(before, errors.size()).clear();
        }
        laborSpec.put("dir", String.valueOf(probe.tunnelDir));
        laborSpec.put("tunnel", String.valueOf(probe.tunnelLength));
    }

    /** Próximo lote de um trabalho contínuo (chamado pelo SkillSystem quando o anterior acaba). */
    public static List<PhysicalJob.Task> nextBatch(KingdomsCore core, Kingdom k, PhysicalJob job, Npc n) {
        JobPlanner jp = new JobPlanner(core, k, job.orderedBy);
        jp.npc = n;
        jp.bag = new TreeMap<>(n.bag);
        jp.laborMode = true;
        jp.batch(job);
        return jp.errors.isEmpty() ? jp.tasks : List.of();
    }

    /** Motivo de não haver lote agora (para o rei saber). */
    public static String idleReason(KingdomsCore core, Kingdom k, PhysicalJob job, Npc n) {
        JobPlanner jp = new JobPlanner(core, k, job.orderedBy);
        jp.npc = n;
        jp.bag = new TreeMap<>(n.bag);
        jp.laborMode = true;
        jp.batch(job);
        return jp.errors.isEmpty() ? "" : jp.errors.get(0);
    }

    private void batch(PhysicalJob job) {
        switch (job.labor) {
            case "wood" -> nextTree(job);
            case "stone" -> gather(new LinkedHashMap<>(Map.of("kind", "gather", "item", "pedra", "count", "16", "radius", String.valueOf(job.radius),
                    "x", String.valueOf(job.site.x()), "y", String.valueOf(job.site.y()), "z", String.valueOf(job.site.z()))));
            case "ore" -> nextOre(job);
            case "farm" -> nextPlots(job);
            default -> errors.add("Trabalho desconhecido: " + job.labor);
        }
    }

    /** Lenhador: a árvore permitida mais perto dele no bosque (tronco + folhas; replanta a muda). */
    private void nextTree(PhysicalJob job) {
        List<Pos> bases = new ArrayList<>();
        Pos c = job.site;
        for (int dx = -job.radius; dx <= job.radius; dx++)
            for (int dz = -job.radius; dz <= job.radius; dz++) {
                if (dx * dx + dz * dz > job.radius * job.radius) continue;
                for (int dy = -6; dy <= 10; dy++) {
                    Pos p = c.offset(dx, dy, dz);
                    if (port.block(p).log() && !port.block(p.offset(0, -1, 0)).log()) {
                        bases.add(p);
                        break;
                    }
                }
            }
        Pos from = npc.pos != null ? npc.pos : c;
        bases.sort(Comparator.comparingDouble(p -> p.distSq(from)));
        for (Pos b : bases) {
            int errBefore = errors.size(), tasksBefore = tasks.size();
            Map<String, Integer> bagBefore = new TreeMap<>(bag);
            chop(new LinkedHashMap<>(Map.of("kind", "chop", "x", String.valueOf(b.x()), "y", String.valueOf(b.y()), "z", String.valueOf(b.z()))));
            if (errors.size() == errBefore) return; // uma árvore por lote
            errors.subList(errBefore, errors.size()).clear();
            while (tasks.size() > tasksBefore) tasks.remove(tasks.size() - 1);
            bag.clear();
            bag.putAll(bagBefore);
        }
        errors.add("Não há árvore que eu possa derrubar no bosque agora (as mudas ainda estão crescendo?).");
    }

    private static boolean isOre(String id) {
        return id.endsWith("_ore") || id.equals("minecraft:ancient_debris");
    }

    /** Minerador: minério à vista perto da mina; senão abre galeria controlada (1×2, de cima para baixo, tocha a cada 8). */
    private void nextOre(PhysicalJob job) {
        List<Pos> ores = new ArrayList<>();
        for (int dx = -job.radius; dx <= job.radius; dx++)
            for (int dz = -job.radius; dz <= job.radius; dz++)
                for (int dy = -6; dy <= 6; dy++) {
                    Pos p = job.site.offset(dx, dy, dz);
                    BlockInfo b = port.block(p);
                    if (!isOre(b.id()) || !exposed(p) || b.nearFluid() || protectedBy(p) != null || foreignOwner(p) != null || !port.mayBreak(player, p)) continue;
                    ores.add(p);
                }
        if (!ores.isEmpty()) {
            Pos from = npc.pos != null ? npc.pos : job.site;
            ores.sort(Comparator.comparingDouble(p -> p.distSq(from)));
            List<Pos> q = new ArrayList<>(ores.subList(0, Math.min(8, ores.size())));
            for (Pos p : q) if (port.block(p).drop() != null) bag.merge(port.block(p).drop(), 1, Integer::sum);
            PhysicalJob.Task t = new PhysicalJob.Task();
            t.kind = PhysicalJob.Kind.BREAK;
            t.blocks = q;
            t.total = q.size();
            t.label = "tirar " + q.size() + " minério(s) à vista perto da mina";
            tasks.add(t);
            return;
        }
        tunnel(job);
    }

    private void tunnel(PhysicalJob job) {
        int[][] dirs = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
        for (int attempt = 0; attempt < 4; attempt++) {
            int[] d = dirs[Math.floorMod(job.tunnelDir, 4)];
            List<Pos> queue = new ArrayList<>(), ores = new ArrayList<>();
            boolean blocked = false;
            Pos torchAt = null;
            int advanced = 0;
            for (int i = job.tunnelLength; i < job.tunnelLength + 8 && !blocked; i++) {
                Pos floor = job.site.offset(d[0] * i, 0, d[1] * i);
                List<Pos> cut = new ArrayList<>();
                for (Pos p : List.of(floor.offset(0, 1, 0), floor)) { // de cima para baixo
                    BlockInfo b = port.block(p);
                    if (b.fluid() || b.nearFluid() || port.block(p.offset(0, 1, 0)).fluid()) {
                        blocked = true; // água/lava à frente: não abre (evita inundar a mina)
                        break;
                    }
                    if (b.air()) continue;
                    if (b.blockEntity() || !b.breakable() || b.hardness() < 0 || protectedBy(p) != null || foreignOwner(p) != null || !port.mayBreak(player, p)) {
                        blocked = true;
                        break;
                    }
                    cut.add(p);
                }
                if (blocked) break;
                queue.addAll(cut);
                advanced++;
                for (Pos side : List.of(floor.offset(d[1], 0, d[0]), floor.offset(-d[1], 0, -d[0]), floor.offset(d[1], 1, d[0]), floor.offset(-d[1], 1, -d[0]),
                        floor.offset(0, 2, 0), floor.offset(0, -1, 0)))
                    if (isOre(port.block(side).id()) && !ores.contains(side) && protectedBy(side) == null && foreignOwner(side) == null && !port.block(side).nearFluid())
                        ores.add(side);
                if ((i + 1) % 8 == 0) torchAt = floor;
            }
            if (queue.isEmpty() && ores.isEmpty()) {
                if (blocked || advanced == 0) {
                    job.tunnelDir++; // água/lava/construção à frente: vira
                    job.tunnelLength = 0;
                    continue;
                }
                job.tunnelLength += advanced; // trecho já aberto: segue em frente
                continue;
            }
            Set<String> tl = usable();
            for (Pos p : queue) {
                BlockInfo b = port.block(p);
                if (b.drop() != null && (!b.needsTool() || b.tool() == null || tl.contains(b.tool()))) bag.merge(b.drop(), 1, Integer::sum);
            }
            for (Pos p : ores) {
                BlockInfo b = port.block(p);
                if (b.drop() != null && (!b.needsTool() || tl.contains(b.tool()))) bag.merge(b.drop(), 1, Integer::sum);
            }
            PhysicalJob.Task t = new PhysicalJob.Task();
            t.kind = PhysicalJob.Kind.BREAK;
            t.blocks = new ArrayList<>(queue);
            t.blocks.addAll(ores);
            t.total = t.blocks.size();
            t.label = "abrir galeria (" + advanced + " blocos" + (ores.isEmpty() ? "" : ", " + ores.size() + " minério(s) na parede") + ")";
            tasks.add(t);
            job.tunnelLength += advanced;
            if (blocked) {
                job.tunnelDir++;
                job.tunnelLength = 0;
            }
            if (torchAt != null) {
                PhysicalJob.Task torch = new PhysicalJob.Task();
                torch.kind = PhysicalJob.Kind.PLANT;
                torch.at = torchAt;
                torch.block = "minecraft:torch";
                torch.auto = true;
                torch.label = "pôr tocha na galeria (escuro)";
                tasks.add(torch);
            }
            return;
        }
        errors.add("A mina está bloqueada em todas as direções (água/lava, construções ou terra de outro reino).");
    }

    public static Building nearestFarm(KingdomsCore core, Kingdom k, Pos near) {
        Building best = null;
        for (Building b : core.buildings(k.id)) {
            if (!b.isComplete() || b.origin == null || b.origin.y() == Integer.MIN_VALUE) continue;
            if (b.blueprint().placements().stream().noneMatch(p -> p.material() == com.kingdomsai.core.construction.Material.CROP)) continue;
            if (best == null || b.centerPos().distSq(near) < best.centerPos().distSq(near)) best = b;
        }
        return best;
    }

    /** Fazendeiro: canteiros que pedem trabalho (maduro → colher e replantar; vazio → plantar; terra → arar e plantar). */
    private void nextPlots(PhysicalJob job) {
        Building farm = nearestFarm(core, k, job.site);
        if (farm == null) {
            errors.add("A fazenda sumiu.");
            return;
        }
        List<Pos> need = new ArrayList<>();
        int growing = 0, noSeed = 0;
        // canteiro vazio só entra se houver semente (na mochila, no armazém ou da colheita deste lote)
        int seeds = bag.getOrDefault("minecraft:wheat_seeds", 0) + Kit.stock(k, "minecraft:wheat_seeds");
        for (var pl : farm.blueprint().placements()) {
            if (pl.material() != com.kingdomsai.core.construction.Material.CROP) continue;
            Pos p = farm.origin.offset(pl.x(), pl.y(), pl.z());
            if (foreignOwner(p) != null || !port.mayBreak(player, p)) continue;
            int g = port.growth(p);
            BlockInfo b = port.block(p), below = port.block(p.offset(0, -1, 0));
            if (g >= 100) {
                need.add(p);
                seeds++; // o trigo maduro devolve a semente para replantar
            } else if (g >= 0) growing++;
            else if (b.air() && (below.id().endsWith("farmland") || below.id().matches(".*:(dirt|grass_block|coarse_dirt)"))) {
                if (seeds > 0) {
                    need.add(p);
                    seeds--;
                } else noSeed++;
            }
        }
        if (need.isEmpty()) {
            errors.add(noSeed > 0 ? "Sem sementes (nem no armazém) para " + noSeed + " canteiro(s) vazio(s): espero a colheita devolver sementes."
                    : growing > 0 ? "A plantação ainda está crescendo (" + growing + " canteiros)." : "Nenhum canteiro precisa de trabalho agora.");
            return;
        }
        Pos from = npc.pos != null ? npc.pos : job.site;
        need.sort(Comparator.comparingDouble(p -> p.distSq(from)));
        PhysicalJob.Task t = new PhysicalJob.Task();
        t.kind = PhysicalJob.Kind.FARM;
        t.blocks = need;
        t.total = need.size();
        t.block = "minecraft:wheat";
        t.label = "cuidar de " + need.size() + " canteiro(s) da fazenda (colher, arar, plantar)";
        tasks.add(t);
    }

    // ------------------------------------------------------------------ fabricar

    private void craft(Map<String, String> s) {
        String item = ItemNames.resolve(s.get("item"));
        if (item == null || item.startsWith("#") || !port.itemExists(item)) {
            errors.add("Não sei que item é «" + s.get("item") + "».");
            return;
        }
        int count = Math.max(1, Math.min(64, num(s.getOrDefault("count", "1"))));
        if (ItemNames.smithing(item) && npc.profession != Profession.BLACKSMITH) {
            errors.add(ItemNames.display(item) + " é trabalho de ferreiro — " + npc.name + " (" + npc.title().toLowerCase() + ") não sabe forjar.");
            return;
        }
        String why = planCraft(item, count, 0);
        if (why != null) errors.add(why);
        else lastProduct = item;
    }

    /** Planeja fabricar (com sub-tarefas). @return null se deu certo; senão o motivo. Desfaz o que tentou ao falhar. */
    private String planCraft(String item, int count, int depth) {
        List<Recipe> recipes = port.recipes(item);
        if (recipes.isEmpty()) return "Não existe receita para " + ItemNames.display(item) + ".";
        String firstWhy = null;
        for (Recipe r : recipes.subList(0, Math.min(6, recipes.size()))) {
            Map<String, Integer> bagBefore = new TreeMap<>(bag);
            int tasksBefore = tasks.size();
            int warnBefore = warnings.size();
            String why = tryRecipe(r, item, count, depth);
            if (why == null) return null;
            bag = bagBefore;
            while (tasks.size() > tasksBefore) tasks.remove(tasks.size() - 1);
            while (warnings.size() > warnBefore) warnings.remove(warnings.size() - 1);
            if (firstWhy == null) firstWhy = why;
        }
        return firstWhy;
    }

    private String tryRecipe(Recipe r, String item, int count, int depth) {
        int units = (count + r.count() - 1) / r.count();
        Pos station = null;
        if (r.station() != PhysicalPort.Station.NONE) {
            String block = r.station() == PhysicalPort.Station.FURNACE ? "minecraft:furnace" : "minecraft:crafting_table";
            station = findStation(block);
            if (station == null)
                return "Não há " + (r.station() == PhysicalPort.Station.FURNACE ? "fornalha" : "bancada de trabalho") + " por perto para fazer "
                        + ItemNames.display(item) + ". Coloque uma perto de Vossa Majestade ou do centro da vila.";
        }
        // ingredientes agrupados por casa (as opções de cada casa)
        Map<List<String>, Integer> need = new LinkedHashMap<>();
        for (List<String> slot : r.ingredients()) if (!slot.isEmpty()) need.merge(slot, units, Integer::sum);
        if (r.station() == PhysicalPort.Station.FURNACE)
            need.merge(List.of("minecraft:coal", "minecraft:charcoal"), (units + 7) / 8, Integer::sum);
        for (var e : need.entrySet()) {
            String why = obtain(e.getKey(), e.getValue(), depth);
            if (why != null) return why;
        }
        // consome e produz (na simulação)
        for (var e : need.entrySet()) consume(e.getKey(), e.getValue());
        bag.merge(item, units * r.count(), Integer::sum);
        PhysicalJob.Task t = new PhysicalJob.Task();
        t.kind = PhysicalJob.Kind.CRAFT;
        t.item = item;
        t.count = units;
        t.at = station;
        t.block = r.station().name();
        t.auto = depth > 0;
        t.label = (r.station() == PhysicalPort.Station.FURNACE ? "fundir " : "fabricar ") + (units * r.count()) + " " + ItemNames.display(item)
                + (r.station() == PhysicalPort.Station.CRAFTING_TABLE ? " na bancada" : r.station() == PhysicalPort.Station.FURNACE ? " na fornalha" : "");
        tasks.add(t);
        return null;
    }

    /** Garante n itens de qualquer uma das opções na mochila simulada: já tem → baú → fabricar antes. */
    private String obtain(List<String> options, int n, int depth) {
        int have = count(options);
        if (have >= n) return null;
        int missing = n - have;
        // 1) baús de onde dá para pegar (o da mira e o do armazém)
        for (Pos chest : sources()) {
            Map<String, Integer> c = port.container(chest);
            if (c == null) continue;
            for (String opt : options) {
                if (missing <= 0) break;
                int inChest = c.getOrDefault(opt, 0) - alreadyTaken(chest, opt);
                if (inChest <= 0) continue;
                int take = Math.min(inChest, missing);
                addTake(chest, opt, take, Map.of(opt, take), true);
                missing -= take;
            }
        }
        if (missing <= 0) return null;
        // 2) fabricar antes (até 3 níveis: picareta → gravetos → tábuas)
        if (depth < 3) {
            for (String opt : options.subList(0, Math.min(8, options.size()))) {
                if (port.recipes(opt).isEmpty()) continue;
                if (ItemNames.smithing(opt) && npc.profession != Profession.BLACKSMITH) continue;
                if (planCraft(opt, missing, depth + 1) == null) return null;
            }
        }
        return "Faltam " + missing + " " + ItemNames.display(options.get(0)) + (options.size() > 1 ? " (ou similar)" : "")
                + " — não há na mochila, nos baús nem dá para fabricar com o que temos.";
    }

    private int alreadyTaken(Pos chest, String item) {
        int n = 0;
        for (PhysicalJob.Task t : tasks) if (t.kind == PhysicalJob.Kind.TAKE && chest.equals(t.at) && item.equals(t.item)) n += t.count;
        return n;
    }

    private List<Pos> sources() {
        List<Pos> out = new ArrayList<>();
        KingdomsCore.Look look = core.playerLook(player);
        if (look != null && look.block() != null && port.container(look.block()) != null) {
            UUID owner = core.state().territory.ownerAt(look.block());
            if (owner == null || owner.equals(k.id)) out.add(look.block());
        }
        Pos st = storageChest();
        if (st != null && !out.contains(st)) out.add(st);
        return out;
    }

    private int count(List<String> options) {
        int n = 0;
        for (String o : options) n += bag.getOrDefault(o, 0);
        return n;
    }

    private void consume(List<String> options, int n) {
        for (String o : options) {
            if (n <= 0) break;
            int have = bag.getOrDefault(o, 0);
            int use = Math.min(have, n);
            if (use > 0) bag.merge(o, -use, Integer::sum);
            n -= use;
        }
        bag.values().removeIf(v -> v <= 0);
    }

    private Pos findStation(String block) {
        Pos king = core.playerPos(player);
        Pos p = npc.pos == null ? null : port.findNear(npc.pos, block, 16);
        if (p == null && king != null) p = port.findNear(king, block, 24);
        if (p == null) p = port.findNear(k.center, block, 32);
        return p;
    }

    // ------------------------------------------------------------------ entregar

    private void give(Map<String, String> s) {
        String spec = s.get("item") != null && !s.get("item").isBlank() ? ItemNames.resolve(s.get("item"))
                : lastProduct != null ? lastProduct : "#all";
        if (spec == null) {
            errors.add("Não sei que item é «" + s.get("item") + "».");
            return;
        }
        if (core.playerPos(player) == null) {
            errors.add("Não sei onde Vossa Majestade está.");
            return;
        }
        Predicate<String> m = ItemNames.matcher(spec);
        int have = bag.entrySet().stream().filter(e -> m.test(e.getKey())).mapToInt(Map.Entry::getValue).sum();
        if (have == 0) {
            errors.add(npc.name + " não terá " + ItemNames.display(spec) + " para entregar.");
            return;
        }
        int want = num(s.getOrDefault("count", "0"));
        if (want <= 0 || want > have) want = have;
        int left = want;
        for (var e : new ArrayList<>(bag.entrySet())) {
            if (left <= 0) break;
            if (!m.test(e.getKey())) continue;
            int n = Math.min(left, e.getValue());
            bag.merge(e.getKey(), -n, Integer::sum);
            left -= n;
        }
        bag.values().removeIf(v -> v <= 0);
        PhysicalJob.Task t = new PhysicalJob.Task();
        t.kind = PhysicalJob.Kind.GIVE;
        t.item = spec;
        t.count = want;
        t.label = "entregar " + want + " " + ItemNames.display(spec) + " a Vossa Majestade";
        tasks.add(t);
    }

    // ------------------------------------------------------------------ regras de proteção

    private boolean nearKing(Pos p) {
        if (laborMode) return true; // trabalho contínuo: o local já foi validado como terra do reino
        Pos king = core.playerPos(player);
        if (king != null && king.distXZ(p) > KING_RANGE) {
            errors.add("Fica a " + (int) king.distXZ(p) + " blocos de Vossa Majestade: trabalho com as mãos só onde o rei pode ver (até "
                    + KING_RANGE + ").");
            return false;
        }
        return true;
    }

    /** Construção (de qualquer reino) que ocupa este bloco. Muralha protege só o anel. */
    public Building protectedBy(Pos p) {
        return protectedBy(core, p);
    }

    public static Building protectedBy(KingdomsCore core, Pos p) {
        for (Building b : core.state().buildings.values()) {
            if (b.status == Building.Status.ABANDONED || b.origin == null || b.origin.y() == Integer.MIN_VALUE) continue;
            Blueprint bp = b.blueprint();
            if (bp == null) continue;
            int x0 = b.origin.x(), z0 = b.origin.z(), x1 = x0 + bp.sizeX() - 1, z1 = z0 + bp.sizeZ() - 1;
            if (p.x() < x0 || p.x() > x1 || p.z() < z0 || p.z() > z1) continue;
            boolean ring = VillageWall.isWall(bp);
            if (ring && p.x() != x0 && p.x() != x1 && p.z() != z0 && p.z() != z1) continue;
            int slack = ring ? 9 : 0;
            if (p.y() < b.origin.y() - 1 - slack || p.y() > b.origin.y() + bp.sizeY() + slack) continue;
            if (b.placed == 0 && !b.isComplete()) continue; // obra que nem começou não protege terreno
            return b;
        }
        return null;
    }

    private Kingdom foreignOwner(Pos p) {
        UUID o = core.state().territory.ownerAt(p);
        return o == null || o.equals(k.id) ? null : core.kingdom(o);
    }

    // ------------------------------------------------------------------ util

    private static Pos center(Pos a, Pos b) {
        return new Pos((a.x() + b.x()) / 2, (a.y() + b.y()) / 2, (a.z() + b.z()) / 2);
    }

    static int[] dir(String facing) {
        return switch (facing == null ? "" : facing) {
            case "north" -> new int[]{0, -1};
            case "south" -> new int[]{0, 1};
            case "east" -> new int[]{1, 0};
            case "west" -> new int[]{-1, 0};
            default -> new int[]{0, 1};
        };
    }

    static int[] size(String s) {
        if (s == null || s.isBlank()) return null;
        String[] parts = s.toLowerCase(Locale.ROOT).split("[x×*]");
        int[] out = new int[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) out[i] = Math.max(1, Math.min(MAX_SIDE, Integer.parseInt(parts[i].trim())));
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }

    static int num(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (RuntimeException e) {
            return 0;
        }
    }

    static boolean truthy(String v) {
        return v != null && Text.norm(v).matches("true|sim|1|yes|s");
    }

    static String toolName(String tool) {
        return switch (tool == null ? "" : tool) {
            case "pickaxe" -> "picareta";
            case "axe" -> "machado";
            case "shovel" -> "pá";
            case "hoe" -> "enxada";
            default -> "a ferramenta certa";
        };
    }
}
