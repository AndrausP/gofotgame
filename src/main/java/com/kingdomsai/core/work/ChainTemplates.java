package com.kingdomsai.core.work;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.kingdomsai.core.KingdomsCore;
import com.kingdomsai.core.common.Text;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.npc.Npc;
import com.kingdomsai.core.npc.Profession;

import java.util.*;

/**
 * Converte os parâmetros da ação CHAIN em {@link ChainSpec}: um modelo pronto (template=minerar_ferreiro...)
 * ou etapas livres em JSON (steps=[{"role":"minerador","type":"MINE","params":{...}}]) geradas pela LLM.
 */
public final class ChainTemplates {
    private ChainTemplates() {}

    public static final Map<String, String> TEMPLATES = new LinkedHashMap<>();

    static {
        TEMPLATES.put("minerar_ferreiro", "minerador minera ferro e carvão → entrega na forja → ferreiro funde (e forja, se forge=true) → guarda no armazém");
        TEMPLATES.put("plantar_colher", "fazendeiro planta → espera amadurecer → colhe → guarda o trigo no armazém");
        TEMPLATES.put("lenha", "lenhador corta lenha → guarda no armazém");
        TEMPLATES.put("pedra", "minerador extrai pedra → guarda no armazém");
        TEMPLATES.put("escrever", "escriba escreve um livro na biblioteca (topic=assunto)");
        TEMPLATES.put("ler", "alguém alfabetizado lê um livro da biblioteca (title=opcional)");
        TEMPLATES.put("carta", "escriba escreve uma carta (to=destinatário, text=mensagem) e a entrega em mãos");
    }

    /** @throws IllegalArgumentException parâmetros inválidos (vira invalid_param no Schema Validator). */
    public static ChainSpec spec(Map<String, String> p, KingdomsCore core, Kingdom k) {
        ChainSpec s;
        String steps = p.get("steps");
        if (steps != null && !steps.isBlank()) s = fromJson(steps, p.get("roles"));
        else {
            String t = canonical(p.get("template"));
            if (t == null)
                throw new IllegalArgumentException("CHAIN precisa de template (" + String.join(", ", TEMPLATES.keySet()) + ") ou steps (JSON).");
            s = template(t, p);
        }
        if (p.get("name") != null && !p.get("name").isBlank()) s.name = Text.truncate(p.get("name").trim(), 40);
        if (p.get("repeat") != null) s.repeat = truthy(p.get("repeat"));
        for (String role : roleNames(s)) if (p.get(role) != null && !p.get(role).isBlank()) s.roles.put(role, p.get(role));
        bindNpc(s, p.get("npc"), core, k);
        return s;
    }

    public static String canonical(String t) {
        if (t == null) return null;
        String n = Text.norm(t).replace(' ', '_').replace('-', '_');
        if (TEMPLATES.containsKey(n)) return n;
        if (n.matches("ferro|forja|mina_forja|mine_to_smith|minerar|ferro_para_forja|minerio|espadas|armas")) return "minerar_ferreiro";
        if (n.matches("fazenda|colheita|plantar|colher|farm|farm_cycle|lavoura|trigo")) return "plantar_colher";
        if (n.matches("lumber|madeira|cortar_lenha|toras")) return "lenha";
        if (n.matches("stone|pedreira|pedras")) return "pedra";
        if (n.matches("livro|write|write_book|cronica|escrita")) return "escrever";
        if (n.matches("read|leitura|ler_livro|estudar")) return "ler";
        if (n.matches("letter|cartas|mensagem")) return "carta";
        return null;
    }

    private static ChainSpec template(String t, Map<String, String> p) {
        ChainSpec s = new ChainSpec();
        int amount = parseAmount(p.get("amount"), -1);
        switch (t) {
            case "minerar_ferreiro" -> {
                int ore = amount > 0 ? amount : 8;
                boolean forge = truthy(p.get("forge")) || Text.norm(String.valueOf(p.get("product"))).matches(".*(espad|arma|sword|weapon).*");
                s.name = forge ? "Ferro e espadas" : "Ferro para o reino";
                s.step("minerador", "MINE", "item", "raw_iron", "amount", String.valueOf(ore))
                        .step("minerador", "MINE", "item", "coal", "amount", String.valueOf((ore + 1) / 2))
                        .step("minerador", "DELIVER", "item", "raw_iron", "to", "smithy")
                        .step("minerador", "DELIVER", "item", "coal", "to", "smithy")
                        .step("ferreiro", "SMELT", "amount", String.valueOf(ore));
                if (forge) s.step("ferreiro", "FORGE", "amount", String.valueOf(ore / 2));
                s.step("ferreiro", "STORE");
            }
            case "plantar_colher" -> {
                s.name = "Ciclo da lavoura";
                s.step("fazendeiro", "PLANT").step("fazendeiro", "HARVEST").step("fazendeiro", "STORE", "item", "wheat");
            }
            case "lenha" -> {
                s.name = "Lenha para o armazém";
                s.step("lenhador", "CHOP", "amount", String.valueOf(amount > 0 ? amount : 12)).step("lenhador", "STORE");
            }
            case "pedra" -> {
                s.name = "Pedra para o armazém";
                s.step("minerador", "MINE", "item", "stone", "amount", String.valueOf(amount > 0 ? amount : 12)).step("minerador", "STORE");
            }
            case "escrever" -> {
                String topic = clean(p.get("topic"));
                s.name = topic.isBlank() ? "Escrever a crônica" : "Escrever sobre " + Text.truncate(topic, 24);
                s.repeat = false;
                s.step("escriba", "WRITE", "kind", "book", "topic", topic);
            }
            case "ler" -> {
                s.name = "Leitura na biblioteca";
                s.repeat = false;
                s.step("leitor", "READ", "title", clean(p.get("title")));
            }
            case "carta" -> {
                String to = clean(p.get("to"));
                if (to.isBlank()) throw new IllegalArgumentException("Carta precisa de destinatário: to=<nome>.");
                s.name = "Carta para " + to;
                s.repeat = false;
                s.step("escriba", "WRITE", "kind", "letter", "to", to, "text", clean(p.get("text")), "topic", clean(p.get("topic")))
                        .step("escriba", "DELIVER", "item", "letter", "to", to);
            }
            default -> throw new IllegalArgumentException("Modelo desconhecido: " + t);
        }
        return s;
    }

    private static ChainSpec fromJson(String json, String rolesJson) {
        ChainSpec s = new ChainSpec();
        try {
            JsonElement root = JsonParser.parseString(json);
            Iterable<JsonElement> arr;
            if (root.isJsonArray()) arr = root.getAsJsonArray();
            else if (root.isJsonObject() && root.getAsJsonObject().has("steps")) {
                JsonObject o = root.getAsJsonObject();
                if (o.has("name")) s.name = Text.truncate(o.get("name").getAsString(), 40);
                if (o.has("repeat")) s.repeat = truthy(o.get("repeat").getAsString());
                if (o.has("roles") && o.get("roles").isJsonObject())
                    for (var e : o.getAsJsonObject("roles").entrySet()) s.roles.put(Text.norm(e.getKey()), e.getValue().getAsString());
                arr = o.getAsJsonArray("steps");
            } else throw new IllegalArgumentException("steps deve ser uma lista JSON de etapas.");
            for (JsonElement e : arr) {
                if (!e.isJsonObject()) continue;
                JsonObject o = e.getAsJsonObject();
                ChainSpec.StepSpec st = new ChainSpec.StepSpec();
                st.role = o.has("role") ? Text.norm(o.get("role").getAsString()) : "npc";
                st.type = o.has("type") ? o.get("type").getAsString() : o.has("do") ? o.get("do").getAsString() : "";
                if (o.has("params") && o.get("params").isJsonObject())
                    for (var pe : o.getAsJsonObject("params").entrySet())
                        st.params.put(pe.getKey(), pe.getValue().isJsonPrimitive() ? pe.getValue().getAsString() : pe.getValue().toString());
                for (var pe : o.entrySet())
                    if (!pe.getKey().matches("role|type|do|params") && pe.getValue().isJsonPrimitive())
                        st.params.put(pe.getKey(), pe.getValue().getAsString());
                s.steps.add(st);
            }
            if (rolesJson != null && !rolesJson.isBlank()) {
                JsonElement r = JsonParser.parseString(rolesJson);
                if (r.isJsonObject()) for (var re : r.getAsJsonObject().entrySet()) s.roles.put(Text.norm(re.getKey()), re.getValue().getAsString());
            }
        } catch (JsonParseException | IllegalStateException | UnsupportedOperationException e) {
            throw new IllegalArgumentException("steps não é um JSON válido: " + e.getMessage());
        }
        if (s.steps.isEmpty()) throw new IllegalArgumentException("A cadeia não tem etapas.");
        if (s.name.isBlank()) s.name = "Cadeia personalizada";
        return s;
    }

    /**
     * "npc=Aldren": o NPC com quem o rei falou assume o papel que combina com a profissão dele
     * (o minerador vira o minerador da cadeia); se nenhum combina, assume o primeiro papel sem dono.
     */
    private static void bindNpc(ChainSpec s, String npcName, KingdomsCore core, Kingdom k) {
        if (npcName == null || npcName.isBlank() || k == null) return;
        if (s.roles.containsValue(npcName)) return;
        Npc n = core.findNpc(k.id, npcName);
        List<String> roles = roleNames(s);
        String target = null;
        if (n != null)
            for (String r : roles) if (!s.roles.containsKey(r) && Profession.parse(r) == n.profession) target = r;
        if (target == null) for (String r : roles) if (!s.roles.containsKey(r)) {
            target = r;
            break;
        }
        if (target != null) s.roles.put(target, npcName);
    }

    public static List<String> roleNames(ChainSpec s) {
        List<String> out = new ArrayList<>();
        for (ChainSpec.StepSpec st : s.steps) {
            String r = st.role == null || st.role.isBlank() ? "npc" : st.role;
            if (!out.contains(r)) out.add(r);
        }
        return out;
    }

    static boolean truthy(String v) {
        if (v == null) return false;
        return Text.norm(v).matches("true|sim|1|on|yes|s|sempre|forge");
    }

    static int parseAmount(String v, int def) {
        if (v == null || v.isBlank()) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String clean(String v) {
        return v == null ? "" : v.trim();
    }
}
