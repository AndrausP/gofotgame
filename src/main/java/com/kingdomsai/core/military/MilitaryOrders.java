package com.kingdomsai.core.military;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kingdomsai.core.KingdomsCore;
import com.kingdomsai.core.action.ActionRequest;
import com.kingdomsai.core.action.ActionResult;
import com.kingdomsai.core.action.ActionType;
import com.kingdomsai.core.construction.Building;
import com.kingdomsai.core.kingdom.Kingdom;

import java.util.*;

/**
 * Ordem militar composta (F6.3): a LLM (finalidade {@code military}) só decompõe "muralha + arqueiros + reação" numa lista de
 * ações já existentes com dependências ({@code after}); quem respeita a ordem é este executor, em código. Ciclo ou id
 * inexistente = rejeição. Cada passo passa pelo pipeline normal do ActionSystem.
 */
public final class MilitaryOrders {
    public static final Set<String> TYPES = Set.of("BUILD", "RECRUIT", "TRAIN", "DEFEND", "PATROL");

    public static final class Step {
        public String id;
        public ActionType type;
        public Map<String, String> params = new LinkedHashMap<>();
        public List<String> after = new ArrayList<>();
        public UUID building;
        /** WAITING | DONE | FAILED */
        public String status = "WAITING";
        public String result = "";
    }

    public static final class Plan {
        public UUID kingdom;
        public UUID actor;
        public List<Step> steps = new ArrayList<>();
        public String reason = "";

        public boolean finished() {
            return steps.stream().noneMatch(s -> s.status.equals("WAITING"));
        }
    }

    private final KingdomsCore core;
    private final List<Plan> plans = new ArrayList<>();

    public MilitaryOrders(KingdomsCore core) {
        this.core = core;
    }

    public List<Plan> plans() {
        return plans;
    }

    /** Lê e valida o JSON {"plan":[{"id","type","params","after"}],"reason"}. Lança IllegalArgumentException com o motivo. */
    public static Plan parse(String json) {
        JsonObject o;
        try {
            int a = json.indexOf('{'), b = json.lastIndexOf('}');
            o = JsonParser.parseString(json.substring(a, b + 1)).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("JSON inválido");
        }
        Plan p = new Plan();
        p.reason = o.has("reason") ? o.get("reason").getAsString() : "";
        JsonArray arr = o.has("plan") && o.get("plan").isJsonArray() ? o.getAsJsonArray("plan") : new JsonArray();
        if (arr.isEmpty() || arr.size() > 12) throw new IllegalArgumentException("o plano precisa de 1 a 12 passos");
        Set<String> ids = new HashSet<>();
        for (JsonElement e : arr) {
            JsonObject s = e.getAsJsonObject();
            Step st = new Step();
            st.id = s.has("id") ? s.get("id").getAsString() : "";
            String type = s.has("type") ? s.get("type").getAsString().toUpperCase() : "";
            if (st.id.isBlank() || !ids.add(st.id)) throw new IllegalArgumentException("id vazio ou repetido: " + st.id);
            if (!TYPES.contains(type)) throw new IllegalArgumentException("ação fora da lista militar: " + type);
            st.type = ActionType.parse(type);
            if (s.has("params") && s.get("params").isJsonObject())
                for (var en : s.getAsJsonObject("params").entrySet()) st.params.put(en.getKey(), en.getValue().isJsonPrimitive() ? en.getValue().getAsString() : en.getValue().toString());
            if (s.has("after") && s.get("after").isJsonArray()) for (JsonElement x : s.getAsJsonArray("after")) st.after.add(x.getAsString());
            p.steps.add(st);
        }
        for (Step s : p.steps) for (String d : s.after) if (!ids.contains(d)) throw new IllegalArgumentException("passo " + s.id + " depende de " + d + ", que não existe");
        // ciclo? (ordenação topológica)
        Set<String> done = new HashSet<>();
        boolean progress = true;
        while (progress && done.size() < p.steps.size()) {
            progress = false;
            for (Step s : p.steps) if (!done.contains(s.id) && done.containsAll(s.after)) {
                done.add(s.id);
                progress = true;
            }
        }
        if (done.size() < p.steps.size()) throw new IllegalArgumentException("o plano tem dependência circular");
        return p;
    }

    /** Plano-padrão sem LLM: muralha + recrutar + treinar + guarnições + reserva. */
    public static String defaultPlanJson() {
        return "{\"plan\":[{\"id\":\"a\",\"type\":\"BUILD\",\"params\":{\"blueprint\":\"muralha\"}},{\"id\":\"b\",\"type\":\"RECRUIT\",\"params\":{\"amount\":\"6\"}},"
                + "{\"id\":\"c\",\"type\":\"TRAIN\",\"params\":{\"amount\":\"6\"},\"after\":[\"b\"]},"
                + "{\"id\":\"d\",\"type\":\"DEFEND\",\"params\":{\"where\":\"portoes\",\"role\":\"soldier\",\"rule\":\"hold\"},\"after\":[\"a\",\"c\"]},"
                + "{\"id\":\"e\",\"type\":\"DEFEND\",\"params\":{\"where\":\"quartel\",\"role\":\"reserve\",\"rule\":\"pursue\"},\"after\":[\"c\"]}],\"reason\":\"Muralha antes da guarnição; reserva no quartel.\"}";
    }

    public Plan submit(Kingdom k, UUID actor, String json) {
        Plan p = parse(json);
        p.kingdom = k.id;
        p.actor = actor;
        plans.add(p);
        tick();
        return p;
    }

    /** Roda o que já pode rodar (dependências cumpridas). Chamado a cada tick estratégico. */
    public void tick() {
        for (Plan p : plans) {
            Kingdom k = core.kingdom(p.kingdom);
            if (k == null) continue;
            for (Step s : p.steps) {
                if (!s.status.equals("WAITING")) continue;
                if (s.building != null) { // BUILD em andamento
                    Building b = core.state().buildings.get(s.building);
                    if (b == null || b.status == Building.Status.ABANDONED) fail(s, "obra perdida");
                    else if (b.isComplete()) s.status = "DONE";
                    continue;
                }
                boolean ready = true;
                for (String d : s.after) for (Step x : p.steps) if (x.id.equals(d) && !x.status.equals("DONE")) ready = false;
                boolean blocked = false;
                for (String d : s.after) for (Step x : p.steps) if (x.id.equals(d) && x.status.equals("FAILED")) blocked = true;
                if (blocked) {
                    fail(s, "depende de um passo que falhou");
                    continue;
                }
                if (!ready) continue;
                run(k, p, s);
            }
        }
        plans.removeIf(Plan::finished);
    }

    private void run(Kingdom k, Plan p, Step s) {
        String[] kv = new String[s.params.size() * 2];
        int i = 0;
        for (var e : s.params.entrySet()) {
            kv[i++] = e.getKey();
            kv[i++] = e.getValue();
        }
        ActionResult r = core.actions().execute(ActionRequest.of(k.id, p.actor, p.actor == null ? ActionRequest.ActorKind.DIRECTOR : ActionRequest.ActorKind.PLAYER,
                s.type, ActionRequest.Source.LLM, kv));
        s.result = r.message() == null ? "" : r.message();
        if (!r.ok()) {
            fail(s, r.code() + ": " + s.result);
            return;
        }
        if (s.type == ActionType.BUILD) { // concluído quando a obra mais recente do reino termina
            Building last = null;
            for (Building b : core.buildings(k.id)) if (last == null || b.startedTick >= last.startedTick) last = b;
            if (last != null && !last.isComplete()) {
                s.building = last.id;
                return;
            }
        }
        s.status = "DONE";
    }

    private void fail(Step s, String why) {
        s.status = "FAILED";
        s.result = why;
    }
}
