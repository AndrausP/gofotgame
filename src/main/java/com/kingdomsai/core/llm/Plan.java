package com.kingdomsai.core.llm;

import com.google.gson.*;
import com.kingdomsai.core.action.ActionType;

import java.util.*;

/** Saída estruturada da LLM: uma fala + ações primitivas propostas (ainda não validadas). */
public record Plan(String reply, List<PlannedAction> actions) {

    public record PlannedAction(ActionType type, String rawType, Map<String, String> params) {}

    public static final int MAX_ACTIONS = 5;

    /** Parser tolerante: aceita texto em volta, pega o primeiro objeto JSON. Lança JsonParseException se inválido. */
    public static Plan parse(String raw) {
        if (raw == null) throw new JsonParseException("vazio");
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) throw new JsonParseException("sem objeto JSON");
        JsonObject o = JsonParser.parseString(raw.substring(start, end + 1)).getAsJsonObject();
        String reply = o.has("reply") && !o.get("reply").isJsonNull() ? o.get("reply").getAsString() : "";
        List<PlannedAction> actions = new ArrayList<>();
        if (o.has("actions") && o.get("actions").isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray("actions")) {
                if (!e.isJsonObject() || actions.size() >= MAX_ACTIONS) continue;
                JsonObject a = e.getAsJsonObject();
                String type = a.has("type") ? a.get("type").getAsString() : "";
                Map<String, String> params = new LinkedHashMap<>();
                if (a.has("params") && a.get("params").isJsonObject())
                    for (var p : a.getAsJsonObject("params").entrySet())
                        params.put(p.getKey(), p.getValue().isJsonPrimitive() ? p.getValue().getAsString() : p.getValue().toString());
                actions.add(new PlannedAction(ActionType.parse(type), type, params));
            }
        }
        return new Plan(reply, actions);
    }

    public String toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("reply", reply);
        JsonArray arr = new JsonArray();
        for (PlannedAction a : actions) {
            JsonObject j = new JsonObject();
            j.addProperty("type", a.type() == null ? a.rawType() : a.type().name());
            JsonObject p = new JsonObject();
            a.params().forEach(p::addProperty);
            j.add("params", p);
            arr.add(j);
        }
        o.add("actions", arr);
        return o.toString();
    }
}
