package com.kingdomsai.core.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.kingdomsai.core.action.ActionType;

import java.util.List;

/**
 * JSON Schemas das respostas da LLM, um por finalidade. São constantes montadas pelo código (nunca texto do jogador),
 * compactas e sem aspas/espaços soltos — o Claude Code recebe o schema como argumento de linha de comando.
 * O schema só é a 1ª camada: {@link Plan#parse} e os Validators do jogo continuam decidindo o que vale.
 */
public final class Schemas {
    private Schemas() {}

    /** Ações que a LLM pode propor ao rei (as implementadas; TALK fica de fora, como no catálogo do prompt). */
    public static List<String> kingActionTypes() {
        return java.util.Arrays.stream(ActionType.values()).filter(t -> t.implemented && t != ActionType.TALK).map(Enum::name).toList();
    }

    /**
     * Schema do {@link Plan} para a finalidade. {@code canPropose=false} = a resposta só fala (rei estrangeiro).
     * Finalidades desconhecidas ganham o schema genérico (tipo de ação livre).
     */
    public static String forPurpose(String purpose, boolean canPropose) {
        List<String> types = switch (purpose == null ? "" : purpose) {
            case "agenda" -> List.of("LIFE");
            case "conversa" -> List.of("LINE");
            case "npc_dialogue", "council", "rival_king", "military", "repair" -> kingActionTypes();
            default -> null;
        };
        return plan(types, canPropose);
    }

    /** {"reply":string,"actions":[{"type":enum,"params":{}}]} — types == null deixa o tipo livre. */
    public static String plan(List<String> types, boolean canPropose) {
        JsonObject type = new JsonObject();
        type.addProperty("type", "string");
        if (types != null) {
            JsonArray en = new JsonArray();
            types.forEach(en::add);
            type.add("enum", en);
        }
        JsonObject params = new JsonObject();
        params.addProperty("type", "object");
        JsonObject itemProps = new JsonObject();
        itemProps.add("type", type);
        itemProps.add("params", params);
        JsonObject item = new JsonObject();
        item.addProperty("type", "object");
        item.addProperty("additionalProperties", false);
        item.add("properties", itemProps);
        item.add("required", strings("type", "params"));

        JsonObject actions = new JsonObject();
        actions.addProperty("type", "array");
        actions.addProperty("maxItems", canPropose ? Plan.MAX_ACTIONS : 0);
        if (canPropose) actions.add("items", item);

        JsonObject reply = new JsonObject();
        reply.addProperty("type", "string");
        reply.addProperty("maxLength", 400);

        JsonObject props = new JsonObject();
        props.add("reply", reply);
        props.add("actions", actions);
        JsonObject root = new JsonObject();
        root.addProperty("type", "object");
        root.addProperty("additionalProperties", false);
        root.add("properties", props);
        root.add("required", strings("reply", "actions"));
        return root.toString();
    }

    private static JsonArray strings(String... s) {
        JsonArray a = new JsonArray();
        for (String x : s) a.add(x);
        return a;
    }

    /** O schema vai como argumento de linha de comando: só caracteres seguros, nada que o cmd.exe interprete. */
    public static boolean safeForCommandLine(String schema) {
        return schema != null && !schema.isBlank() && schema.length() <= 6000 && !schema.matches("(?s).*[&|<>^%!\r\n].*");
    }
}
