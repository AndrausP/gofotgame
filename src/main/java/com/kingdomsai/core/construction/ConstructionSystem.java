package com.kingdomsai.core.construction;

import com.kingdomsai.core.KingdomsCore;
import com.kingdomsai.core.common.Pos;
import com.kingdomsai.core.event.EventType;
import com.kingdomsai.core.event.GameEvent;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.npc.Npc;
import com.kingdomsai.core.npc.Profession;
import com.kingdomsai.core.port.WorldPort.SiteCheck;
import com.kingdomsai.core.territory.TerritoryMap;

import java.util.*;

/**
 * Construction Engine: encontra terreno, verifica território, reserva área, cria o projeto,
 * atribui construtores e avança o progresso (abstrato quando ninguém está olhando).
 */
public final class ConstructionSystem {
    public static final int UNKNOWN_Y = Integer.MIN_VALUE;
    private final KingdomsCore core;

    public ConstructionSystem(KingdomsCore core) {
        this.core = core;
    }

    /** Planeja um prédio em local escolhido automaticamente. Retorna null se não houver terreno. */
    public Building plan(Kingdom k, Blueprint bp, UUID dedicatedTo) {
        Pos site = findSite(k, bp);
        if (site == null) return null;
        Building b = planAt(k, bp, site, true);
        if (b != null) b.dedicatedTo = dedicatedTo;
        return b;
    }

    public Building planAt(Kingdom k, Blueprint bp, Pos origin, boolean force) {
        for (Building o : core.state().buildings.values())
            if (o.status != Building.Status.ABANDONED && o.overlaps(origin, bp, 2)) return null;
        SiteCheck check = core.world().checkSite(origin.x(), origin.z(), bp);
        if (check.kind() == SiteCheck.Kind.BAD && !force) return null;
        int y = check.kind() == SiteCheck.Kind.UNLOADED ? UNKNOWN_Y : check.groundY();
        if (y == UNKNOWN_Y && k.center != null && core.world().isLoaded(k.center.x(), k.center.z())) {
            int sy = core.world().surfaceY(origin.x() + bp.sizeX() / 2, origin.z() + bp.sizeZ() / 2);
            if (sy != Integer.MIN_VALUE) y = sy;
        }
        return create(k, bp, new Pos(origin.x(), y, origin.z()));
    }

    /**
     * Muralha da vila: o lugar é a própria vila (não se procura terreno plano) e a altura de cada coluna
     * já vem na planta, que acompanha o relevo. Recusa se cruzar alguma obra.
     */
    public Building planWall(Kingdom k, Blueprint wall) {
        Pos origin = VillageWall.origin(core, k);
        for (Building o : core.state().buildings.values())
            if (o.status != Building.Status.ABANDONED && o.overlaps(origin, wall, 1)) return null;
        return create(k, wall, origin);
    }

    private Building create(Kingdom k, Blueprint bp, Pos origin) {
        int y = origin.y();
        Building b = new Building();
        b.id = UUID.randomUUID();
        b.kingdomId = k.id;
        b.blueprintId = bp.id();
        b.origin = new Pos(origin.x(), y, origin.z());
        b.variant = core.rng().nextInt(3);
        b.status = Building.Status.UNDER_CONSTRUCTION;
        b.startedTick = core.tick();
        core.state().buildings.put(b.id, b);
        assignBuilder(b);
        core.bus().publish(core.tick(), EventType.BUILDING_STARTED, GameEvent.Severity.INFO, k.id, b.builderId,
                "Obra iniciada: " + bp.displayName() + " em " + b.origin.x() + ", " + b.origin.z(),
                Map.of("building", b.id.toString(), "blueprint", bp.id()));
        return b;
    }

    /** Procura em espiral um terreno dentro do território, sem sobrepor outras obras. */
    public Pos findSite(Kingdom k, Blueprint bp) {
        TerritoryMap t = core.state().territory;
        Pos c = k.center;
        int minR = bp.category() == Blueprint.Category.FARM ? 18 : 10;
        Pos firstUnloaded = null;
        Pos best = null;
        double bestScore = -1;
        int okFound = 0;
        for (int r = minR; r <= 90 && okFound < 6; r += 4) {
            int steps = Math.max(8, r / 2);
            double phase = core.rng().nextDouble() * Math.PI * 2;
            for (int i = 0; i < steps; i++) {
                double a = phase + i * Math.PI * 2 / steps;
                int x = c.x() + (int) Math.round(Math.cos(a) * r) - bp.sizeX() / 2;
                int z = c.z() + (int) Math.round(Math.sin(a) * r) - bp.sizeZ() / 2;
                if (!insideTerritory(t, k.id, x, z, bp)) continue;
                if (overlapsAny(new Pos(x, 0, z), bp) || coversPlaza(k, x, z, bp)) continue;
                SiteCheck check = core.world().checkSite(x, z, bp);
                if (check.kind() == SiteCheck.Kind.UNLOADED) {
                    if (firstUnloaded == null) firstUnloaded = new Pos(x, UNKNOWN_Y, z);
                    continue;
                }
                if (check.kind() == SiteCheck.Kind.BAD) continue;
                double score = check.score() - r * 0.01;
                okFound++;
                if (score > bestScore) {
                    bestScore = score;
                    best = new Pos(x, check.groundY(), z);
                }
            }
        }
        return best != null ? best : firstUnloaded;
    }

    private boolean insideTerritory(TerritoryMap t, UUID owner, int x, int z, Blueprint bp) {
        int[][] corners = {{x - 1, z - 1}, {x + bp.sizeX(), z - 1}, {x - 1, z + bp.sizeZ()}, {x + bp.sizeX(), z + bp.sizeZ()}};
        for (int[] cc : corners) if (!owner.equals(t.ownerAt(new Pos(cc[0], 0, cc[1])))) return false;
        return true;
    }

    private boolean overlapsAny(Pos origin, Blueprint bp) {
        for (Building o : core.state().buildings.values())
            if (o.status != Building.Status.ABANDONED && o.overlaps(origin, bp, 3)) return true;
        return false;
    }

    /** Mantém a praça central (raio 7) livre para os NPCs se reunirem. */
    private boolean coversPlaza(Kingdom k, int x, int z, Blueprint bp) {
        int cx = Math.max(x, Math.min(k.center.x(), x + bp.sizeX()));
        int cz = Math.max(z, Math.min(k.center.z(), z + bp.sizeZ()));
        int dx = cx - k.center.x(), dz = cz - k.center.z();
        return dx * dx + dz * dz < 49;
    }

    // ------------------------------------------------------------------ construtores

    /** Garante pelo menos um construtor na obra (o menos ocupado do reino). */
    public void assignBuilder(Building b) {
        b.builderIds.removeIf(id -> !isBuilder(core.npc(id), b.kingdomId));
        if (b.builderId != null && !b.builderIds.contains(b.builderId) && isBuilder(core.npc(b.builderId), b.kingdomId))
            b.builderIds.add(0, b.builderId);
        if (b.builderIds.isEmpty()) {
            Npc best = null;
            int bestLoad = Integer.MAX_VALUE;
            for (Npc n : core.citizens(b.kingdomId)) {
                if (n.profession != Profession.BUILDER) continue;
                int load = 0;
                for (Building o : core.state().buildings.values())
                    if (o.status == Building.Status.UNDER_CONSTRUCTION && o.hasBuilder(n.id)) load++;
                if (load < bestLoad) {
                    bestLoad = load;
                    best = n;
                }
            }
            if (best != null) b.builderIds.add(best.id);
        }
        b.builderId = b.builderIds.isEmpty() ? null : b.builderIds.get(0);
    }

    /** O construtor com menos obras (para o conselho delegar). */
    public Npc leastBusyBuilder(UUID kingdomId) {
        Npc best = null;
        int bestLoad = Integer.MAX_VALUE;
        for (Npc n : core.citizens(kingdomId)) {
            if (n.profession != Profession.BUILDER || !n.isFree()) continue;
            int load = 0;
            for (Building o : core.state().buildings.values())
                if (o.status == Building.Status.UNDER_CONSTRUCTION && o.hasBuilder(n.id)) load++;
            if (load < bestLoad) {
                bestLoad = load;
                best = n;
            }
        }
        return best;
    }

    private static boolean isBuilder(Npc n, UUID kingdom) {
        return n != null && n.alive && n.profession == Profession.BUILDER && kingdom.equals(n.kingdomId);
    }

    /** Obra em que o construtor está trabalhando agora: a de prazo mais urgente, senão a mais antiga. */
    public Building currentProjectOf(Npc builder) {
        Building cur = null;
        for (Building b : core.state().buildings.values()) {
            if (b.status != Building.Status.UNDER_CONSTRUCTION || !b.hasBuilder(builder.id)) continue;
            if (cur == null || urgency(b) < urgency(cur)) cur = b;
        }
        return cur;
    }

    private static double urgency(Building b) {
        return b.deadlineTick > 0 ? b.deadlineTick : 1e15 + b.startedTick;
    }

    /** Construtores trabalhando AGORA nesta obra. */
    public List<Npc> activeBuilders(Building b) {
        List<Npc> out = new ArrayList<>();
        for (UUID id : b.builderIds) {
            Npc n = core.npc(id);
            if (isBuilder(n, b.kingdomId) && currentProjectOf(n) == b) out.add(n);
        }
        return out;
    }

    public List<Building> projects(UUID kingdomId) {
        List<Building> out = new ArrayList<>();
        for (Building b : core.buildings(kingdomId)) if (b.status == Building.Status.UNDER_CONSTRUCTION) out.add(b);
        out.sort(Comparator.comparingDouble(ConstructionSystem::urgency));
        return out;
    }

    // ------------------------------------------------------------------ tempo: ETA e prazos

    public int remainingSolid(Building b) {
        Blueprint bp = b.blueprint();
        return bp == null ? 0 : bp.solidTotal() - bp.solidBefore(b.progress);
    }

    /** Segundos estimados para terminar; -1 se não há construtor. */
    public double etaSeconds(Building b) {
        int active = activeBuilders(b).size();
        if (active == 0) return -1;
        return remainingSolid(b) / (core.config().builderBlocksPerSecond * active);
    }

    public int neededBuilders(Building b) {
        if (b.deadlineTick <= 0) return 1;
        double secondsLeft = Math.max(1, (b.deadlineTick - core.tick()) / 20.0);
        return Math.max(1, (int) Math.ceil(remainingSolid(b) / (core.config().builderBlocksPerSecond * secondsLeft)));
    }

    /** Define o prazo e já remaneja construtores. Retorna um resumo para o jogador. */
    public String setDeadline(Building b, long ticksFromNow) {
        b.deadlineTick = core.tick() + Math.max(20, ticksFromNow);
        b.overdueWarned = false;
        b.atRiskWarned = false;
        rebalance(b.kingdomId);
        int need = neededBuilders(b), have = activeBuilders(b).size();
        String msg = "Prazo de " + b.blueprint().displayName() + ": " + formatDuration(ticksFromNow / 20.0)
                + ". " + have + " construtor(es) na obra" + (need > have ? ", precisaria de " + need + " — vai atrasar." : ", deve dar tempo.")
                + " ETA " + formatEta(b) + ".";
        core.bus().publish(core.tick(), EventType.BUILDING_DEADLINE_SET, need > have ? GameEvent.Severity.WARN : GameEvent.Severity.INFO,
                b.kingdomId, b.builderId, msg, Map.of("building", b.id.toString()));
        return msg;
    }

    public String formatEta(Building b) {
        double eta = etaSeconds(b);
        if (eta >= 0) return formatDuration(eta);
        if (!b.builderIds.isEmpty()) {
            Npc n = core.npc(b.builderIds.get(0));
            Building cur = n == null ? null : currentProjectOf(n);
            if (n != null && cur != null && cur != b) return "na fila de " + n.name + " (termina «" + cur.blueprint().displayName() + "» antes)";
        }
        return "parada (sem construtor)";
    }

    public static String formatDuration(double seconds) {
        if (seconds < 60) return (int) Math.ceil(seconds) + "s";
        if (seconds < 1200) return (int) (seconds / 60) + "min " + ((int) seconds % 60) + "s";
        double days = seconds / 1200.0;
        if (days < 1) return (int) (seconds / 60) + "min";
        return String.format(Locale.ROOT, "%.1f dia(s)", days);
    }

    /**
     * Converte "30s", "5m", "2min", "1d", "amanha", "hoje" em ticks. 1 dia = 1 dia do Minecraft (20 min).
     * @return -1 se não reconhecido.
     */
    public static long parseDuration(String s) {
        if (s == null) return -1;
        String n = com.kingdomsai.core.common.Text.norm(s).replace(" ", "");
        if (n.startsWith("amanha") || n.equals("tomorrow")) return 24000;
        if (n.equals("hoje") || n.equals("today")) return 12000;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d+(?:[.,]\\d+)?)(s|seg|segundos?|m|min|minutos?|h|horas?|d|dias?)?$").matcher(n);
        if (!m.find()) return -1;
        double v = Double.parseDouble(m.group(1).replace(',', '.'));
        String u = m.group(2) == null ? "m" : m.group(2);
        if (u.startsWith("s")) return (long) (v * 20);
        if (u.startsWith("h")) return (long) (v * 72000);
        if (u.startsWith("d")) return (long) (v * 24000);
        return (long) (v * 1200);
    }

    /** Remaneja construtores: toda obra tem alguém; obras com prazo puxam ajudantes das sem prazo. */
    public void rebalance(UUID kingdomId) {
        List<Building> ps = projects(kingdomId);
        for (Building b : ps) assignBuilder(b);
        for (Building b : ps) {
            if (b.deadlineTick <= 0) continue;
            int need = neededBuilders(b);
            for (Npc n : core.citizens(kingdomId)) {
                if (activeBuilders(b).size() >= need) break;
                if (n.profession != Profession.BUILDER || b.hasBuilder(n.id)) continue;
                Building cur = currentProjectOf(n);
                if (cur != null && cur.deadlineTick > 0 && cur.deadlineTick <= b.deadlineTick) continue;
                b.builderIds.add(n.id);
                n.remember(core.tick(), "Fui chamado para ajudar na obra de " + b.blueprint().displayName().toLowerCase() + " (tem prazo).", 30, b.id, "obra");
            }
        }
    }

    /** Cancela a obra e devolve metade do custo. */
    public String cancel(Building b) {
        b.status = Building.Status.ABANDONED;
        Kingdom k = core.kingdom(b.kingdomId);
        Blueprint bp = b.blueprint();
        if (k != null && bp != null) for (var e : bp.cost().entrySet()) k.add(e.getKey(), e.getValue() / 2.0);
        String msg = "Obra cancelada: " + (bp == null ? "?" : bp.displayName()) + " (metade dos materiais devolvida).";
        core.bus().publish(core.tick(), EventType.BUILDING_CANCELLED, GameEvent.Severity.INFO, b.kingdomId, null, msg);
        return msg;
    }

    private final Map<UUID, Double> carry = new HashMap<>();

    /** Progresso abstrato: cada construtor NÃO materializado avança a sua obra atual por cálculo. */
    public void tickSecond() {
        if (!core.config().constructionEnabled) return;
        if (core.tick() % 200 < 20) for (UUID k : core.state().kingdoms.keySet()) rebalance(k);
        double rate = core.config().builderBlocksPerSecond;
        for (Npc n : core.allAlive()) {
            if (n.profession != Profession.BUILDER || n.materialized) continue;
            Building b = currentProjectOf(n);
            if (b == null) continue;
            double acc = carry.getOrDefault(n.id, 0.0) + rate;
            int add = (int) Math.floor(acc);
            carry.put(n.id, acc - add);
            if (add > 0) advance(b, add);
            checkComplete(b);
        }
        for (Building b : List.copyOf(core.state().buildings.values())) {
            if (b.status != Building.Status.UNDER_CONSTRUCTION) continue;
            if (b.builderIds.isEmpty()) {
                assignBuilder(b);
                if (b.builderIds.isEmpty()) warnNoBuilder(b.kingdomId);
            }
            checkDeadline(b);
        }
    }

    private void checkDeadline(Building b) {
        if (b.deadlineTick <= 0) return;
        String name = b.blueprint().displayName();
        if (core.tick() > b.deadlineTick && !b.overdueWarned) {
            b.overdueWarned = true;
            Kingdom k = core.kingdom(b.kingdomId);
            if (k != null) k.legitimacy = Math.max(0, k.legitimacy - 2);
            for (Npc n : activeBuilders(b))
                n.remember(core.tick(), "Não conseguimos terminar " + name.toLowerCase() + " no prazo do rei.", 55, b.id, "obra", "atraso");
            core.bus().publish(core.tick(), EventType.BUILDING_LATE, GameEvent.Severity.DANGER, b.kingdomId, b.builderId,
                    "Obra atrasada: " + name + " passou do prazo (" + (int) b.percent() + "% pronta).", Map.of("building", b.id.toString()));
        } else if (!b.atRiskWarned && core.tick() < b.deadlineTick) {
            double eta = etaSeconds(b);
            double left = (b.deadlineTick - core.tick()) / 20.0;
            if (eta < 0 || eta > left * 1.05) {
                b.atRiskWarned = true;
                core.bus().publish(core.tick(), EventType.BUILDING_LATE, GameEvent.Severity.WARN, b.kingdomId, b.builderId,
                        name + " vai atrasar: ETA " + formatEta(b) + ", faltam " + formatDuration(left) + ". Precisa de "
                                + neededBuilders(b) + " construtor(es), tem " + activeBuilders(b).size() + ".",
                        Map.of("building", b.id.toString()));
            }
        }
    }

    private final Map<UUID, Long> lastNoBuilderWarning = new HashMap<>();

    private void warnNoBuilder(UUID kingdomId) {
        long last = lastNoBuilderWarning.getOrDefault(kingdomId, -100000L);
        if (core.tick() - last < 20 * 120) return;
        lastNoBuilderWarning.put(kingdomId, core.tick());
        core.bus().publish(core.tick(), EventType.ACTION_REJECTED, GameEvent.Severity.WARN, kingdomId, null,
                "Obras paradas: não há construtores. Use /k assign construtor 1.");
    }

    /** Avança n blocos "reais"; passos de limpeza (AIR) não custam tempo na simulação abstrata. */
    private void advance(Building b, int n) {
        List<Blueprint.Placement> ps = b.blueprint().placements();
        while (n > 0 && b.progress < ps.size()) {
            if (ps.get(b.progress).material() != Material.AIR) n--;
            b.progress++;
        }
    }

    /** Chamado pelo adaptador quando um construtor materializado coloca um bloco. */
    public void stepPhysical(Building b) {
        if (b.status != Building.Status.UNDER_CONSTRUCTION) return;
        b.progress = Math.min(b.blueprint().blockCount(), b.progress + 1);
        checkComplete(b);
    }

    private void checkComplete(Building b) {
        Blueprint bp = b.blueprint();
        if (b.progress < bp.blockCount()) return;
        b.status = Building.Status.COMPLETE;
        b.completedTick = core.tick();
        Kingdom k = core.kingdom(b.kingdomId);
        Npc builder = core.npc(b.builderId);
        if (builder != null) {
            builder.fame += 3;
            builder.remember(core.tick(), "Terminei de construir " + bp.displayName().toLowerCase() + ".", 45, b.id, "obra", "orgulho");
        }
        if (bp.housing() > 0) assignResidents(b);
        core.bus().publish(core.tick(), EventType.BUILDING_COMPLETED, GameEvent.Severity.GOOD, b.kingdomId, b.builderId,
                bp.displayName() + " concluída" + (k != null ? " em " + k.name : "") + ".",
                Map.of("building", b.id.toString(), "blueprint", bp.id()));
        if (bp.id().equals("town_hall") && k != null) core.chronicle("O Salão Real de " + k.name + " foi erguido.");
    }

    private void assignResidents(Building b) {
        int cap = b.blueprint().housing();
        List<Npc> homeless = new ArrayList<>();
        for (Npc n : core.citizens(b.kingdomId)) if (n.homeId == null || core.state().buildings.get(n.homeId) == null) homeless.add(n);
        if (b.dedicatedTo != null) homeless.sort(Comparator.comparing(n -> !n.id.equals(b.dedicatedTo)));
        if (b.blueprintId.equals("barracks")) homeless.sort(Comparator.comparing(n -> !n.profession.isMilitary()));
        for (Npc n : homeless) {
            if (b.residents.size() >= cap) break;
            b.residents.add(n.id);
            n.homeId = b.id;
            boolean dedicated = n.id.equals(b.dedicatedTo);
            n.remember(core.tick(), dedicated ? "O rei mandou construir uma casa só para mim." : "Ganhei um lugar para morar.",
                    dedicated ? 75 : 40, b.id, "casa");
            n.loyalty = Math.min(100, n.loyalty + (dedicated ? 12 : 4));
            if (dedicated) n.fame += 5;
        }
    }

    /** O adaptador informa que a obra precisou de uma altura (área carregou pela primeira vez). */
    public void resolveGround(Building b, int y) {
        if (b.origin.y() == UNKNOWN_Y) b.origin = new Pos(b.origin.x(), y, b.origin.z());
    }
}
