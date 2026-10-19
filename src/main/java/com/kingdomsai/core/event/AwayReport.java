package com.kingdomsai.core.event;

import com.kingdomsai.core.KingdomsCore;
import com.kingdomsai.core.common.Pos;
import com.kingdomsai.core.common.Text;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.kingdom.ResourceType;
import com.kingdomsai.core.skill.PhysicalJob;
import com.kingdomsai.core.work.WorkChain;

import java.util.*;

/**
 * "Enquanto Vossa Majestade esteve fora…": quando o rei volta para a vila (ou entra no jogo), recebe um
 * resumo do que os súditos fizeram — ciclos das rotinas, ordens concluídas, obras, mortes, estoques.
 * Feito a partir do log de eventos, que é salvo junto com o mundo.
 */
public final class AwayReport {
    /** Só conta como "fora" quem ficou longe pelo menos isso. */
    public static final long MIN_AWAY_TICKS = 20L * 120;
    public static final int FAR = 160;
    public static final int NEAR = 96;

    private final KingdomsCore core;
    private final Map<UUID, Long> awaySince = new HashMap<>();
    private final Map<UUID, Map<ResourceType, Double>> stockAtLeave = new HashMap<>();
    private final Map<UUID, List<String>> last = new HashMap<>();

    public AwayReport(KingdomsCore core) {
        this.core = core;
    }

    /** A cada segundo, para cada rei no mundo. @return o relatório quando ele acaba de voltar; senão vazio. */
    public List<String> tick(UUID player, Pos pos) {
        Kingdom k = core.kingdomOfPlayer(player);
        if (k == null || pos == null) return List.of();
        core.state().playerLastSeen.put(player, core.tick());
        double d = pos.distXZ(k.center);
        if (d > FAR) {
            if (!awaySince.containsKey(player)) {
                awaySince.put(player, core.tick());
                stockAtLeave.put(player, new EnumMap<>(k.stock));
            }
            return List.of();
        }
        if (d <= NEAR && awaySince.containsKey(player)) {
            long since = awaySince.remove(player);
            Map<ResourceType, Double> before = stockAtLeave.remove(player);
            if (core.tick() - since >= MIN_AWAY_TICKS) return remember(player, build(k, since, before, "Enquanto Vossa Majestade esteve fora"));
        }
        return List.of();
    }

    /** Ao entrar no mundo: o que aconteceu desde a última vez que o rei foi visto (servidor que ficou ligado). */
    public List<String> onLogin(UUID player) {
        Kingdom k = core.kingdomOfPlayer(player);
        Long seen = core.state().playerLastSeen.get(player);
        awaySince.remove(player);
        if (k == null || seen == null || core.tick() - seen < MIN_AWAY_TICKS) return List.of();
        return remember(player, build(k, seen, null, "Enquanto Vossa Majestade esteve ausente"));
    }

    /** /k report: o último relatório, ou um resumo dos últimos 10 minutos. */
    public List<String> latest(UUID player) {
        Kingdom k = core.kingdomOfPlayer(player);
        if (k == null) return List.of("✗ Você não governa um reino.");
        List<String> l = last.get(player);
        return l != null ? l : build(k, Math.max(0, core.tick() - 20L * 600), null, "Nos últimos 10 minutos");
    }

    private List<String> remember(UUID player, List<String> lines) {
        last.put(player, lines);
        return lines;
    }

    public List<String> build(Kingdom k, long since, Map<ResourceType, Double> before, String title) {
        List<GameEvent> evs = core.bus().log().recent(1500, e -> k.id.equals(e.kingdomId()) && e.tick() >= since);
        List<String> out = new ArrayList<>();
        long minutes = Math.max(1, (core.tick() - since) / 20 / 60);
        out.add("# " + title + " (" + minutes + " min)");

        // rotinas: ciclos e o que guardaram
        Map<String, int[]> cycles = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> stored = new LinkedHashMap<>();
        List<String> done = new ArrayList<>(), bad = new ArrayList<>(), built = new ArrayList<>(), other = new ArrayList<>();
        for (GameEvent e : evs) {
            switch (e.type()) {
                case CHAIN_CYCLE_COMPLETED -> {
                    WorkChain c = chain(e.data("chain"));
                    String name = c == null ? "rotina" : "#" + c.number + " «" + c.name + "»";
                    cycles.computeIfAbsent(name, x -> new int[1])[0]++;
                    String outStr = e.data("out");
                    if (outStr != null && !outStr.isBlank())
                        for (String part : outStr.split(";")) {
                            String[] kv = part.split("=");
                            if (kv.length == 2) stored.computeIfAbsent(name, x -> new TreeMap<>()).merge(kv[0], Integer.parseInt(kv[1]), Integer::sum);
                        }
                }
                case JOB_DONE, CHAIN_COMPLETED, DOCUMENT_WRITTEN, LETTER_DELIVERED, BOOK_READ -> done.add(e.message());
                case JOB_FAILED, CHAIN_BROKEN, NPC_DIED, FAMINE_STARTED, WAR_DECLARED, BUILDING_LATE -> bad.add(e.message());
                case BUILDING_COMPLETED -> built.add(e.message());
                case CHAIN_RESUMED, TRADE_COMPLETED, POPULATION_GROWTH, NPC_PROMOTED, TERRITORY_CLAIMED -> other.add(e.message());
                default -> {
                }
            }
        }
        for (var e : cycles.entrySet()) {
            Map<String, Integer> st = stored.get(e.getKey());
            out.add("⛓ " + e.getKey() + ": " + e.getValue()[0] + " ciclo(s)" + (st == null ? "." : " — guardou " + items(st) + "."));
        }
        for (String s : tail(done, 5)) out.add("✓ " + s);
        if (!built.isEmpty()) out.add("✓ " + built.size() + " obra(s) concluída(s): " + String.join("; ", tail(built, 3)));
        for (String s : tail(other, 3)) out.add("  " + s);
        for (String s : tail(bad, 5)) out.add("⚠ " + s);
        // o que ainda está em andamento
        for (PhysicalJob j : core.skills().jobs(k.id))
            if (j.status.live()) {
                PhysicalJob.Task t = j.current();
                out.add("⚒ Ordem #" + j.number + " " + j.status.display + (t == null ? "" : ": " + t.label
                        + (t.total > 1 ? " (" + t.done + "/" + t.total + ")" : "")) + (j.reason.isBlank() ? "" : " — " + j.reason));
            }
        for (WorkChain c : core.work().chains(k.id))
            if (c.status == WorkChain.Status.BROKEN) out.add("⚠ Rotina #" + c.number + " «" + c.name + "» parada: " + c.brokenReason + ".");
        if (before != null) {
            StringBuilder sb = new StringBuilder("Estoques: ");
            boolean any = false;
            for (ResourceType r : ResourceType.values()) {
                double d = k.get(r) - before.getOrDefault(r, 0.0);
                if (Math.abs(d) < 1) continue;
                sb.append(r.display).append(' ').append(d > 0 ? "+" : "").append(Text.fmt(d)).append(" · ");
                any = true;
            }
            if (any) out.add(sb.substring(0, sb.length() - 3));
        }
        if (out.size() == 1) out.add("Tudo calmo: nada de novo no reino.");
        return out;
    }

    private WorkChain chain(String id) {
        if (id == null) return null;
        try {
            return core.state().chains.get(UUID.fromString(id));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String items(Map<String, Integer> m) {
        List<String> parts = new ArrayList<>();
        for (var e : m.entrySet()) {
            var item = com.kingdomsai.core.work.Item.parse(e.getKey());
            parts.add(e.getValue() + " " + (item == null ? e.getKey() : item.display.toLowerCase()));
        }
        return String.join(", ", parts);
    }

    private static List<String> tail(List<String> l, int n) {
        return l.size() <= n ? l : l.subList(l.size() - n, l.size());
    }
}
