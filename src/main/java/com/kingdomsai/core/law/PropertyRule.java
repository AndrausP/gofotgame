package com.kingdomsai.core.law;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Uma lei como regra sobre propriedades: "nas casas, nenhuma cama vermelha; troque por azul".
 * A LLM só escreve esta regra (um texto curto de chave=valor); quem compara com o mundo e cria as tarefas é o {@link LawEnforcer}.
 *
 * Sintaxe: {@code scope=homes tag=bed color=red action=replace with=light_blue}
 * <ul>
 *   <li>scope: homes (casas com morador) | buildings (todas as obras prontas) | people (o que os súditos carregam)</li>
 *   <li>tag / color / material: o que casa com a regra (qualquer subconjunto; ao menos um)</li>
 *   <li>action: replace (troca por outra cor/material) | remove (tira) | confiscate (só people: vai para o armazém do reino)</li>
 *   <li>with: cor ou material da troca (obrigatório em replace)</li>
 * </ul>
 */
public final class PropertyRule {
    public static final String HOMES = "homes", BUILDINGS = "buildings", PEOPLE = "people";
    public static final String REPLACE = "replace", REMOVE = "remove", CONFISCATE = "confiscate";

    public String scope = HOMES;
    public String tag;
    public String color;
    public String material;
    public String action = REPLACE;
    public String with;

    public PropertyRule() {}

    /** @throws IllegalArgumentException com a mensagem que vai para quem escreveu a regra */
    public static PropertyRule parse(String spec) {
        if (spec == null || spec.isBlank()) throw new IllegalArgumentException("regra vazia");
        Map<String, String> kv = new LinkedHashMap<>();
        for (String tok : spec.trim().split("[\\s;]+")) {
            int eq = tok.indexOf('=');
            if (eq <= 0 || eq == tok.length() - 1) throw new IllegalArgumentException("trecho inválido: \"" + tok + "\" (use chave=valor)");
            kv.put(tok.substring(0, eq).toLowerCase(), tok.substring(eq + 1).toLowerCase());
        }
        PropertyRule r = new PropertyRule();
        for (var e : kv.entrySet()) {
            String v = e.getValue();
            switch (e.getKey()) {
                case "scope" -> {
                    if (!v.matches("homes|buildings|people")) throw new IllegalArgumentException("scope deve ser homes, buildings ou people");
                    r.scope = v;
                }
                case "tag" -> r.tag = v;
                case "color" -> {
                    r.color = Props.colorOf(v);
                    if (r.color == null) throw new IllegalArgumentException("cor desconhecida: " + v);
                }
                case "material" -> r.material = v;
                case "action" -> {
                    if (!v.matches("replace|remove|confiscate")) throw new IllegalArgumentException("action deve ser replace, remove ou confiscate");
                    r.action = v;
                }
                case "with" -> r.with = v;
                default -> throw new IllegalArgumentException("chave desconhecida: " + e.getKey());
            }
        }
        if (r.tag == null && r.color == null && r.material == null) throw new IllegalArgumentException("diga o que proibir (tag, color ou material)");
        if (r.action.equals(CONFISCATE) && !r.scope.equals(PEOPLE)) throw new IllegalArgumentException("confiscate só vale para scope=people");
        if (r.scope.equals(PEOPLE) && !r.action.equals(CONFISCATE)) r.action = CONFISCATE;
        if (r.action.equals(REPLACE)) {
            if (r.with == null) throw new IllegalArgumentException("replace precisa de with=<cor|material>");
            if (Props.colorOf(r.with) != null) r.with = Props.colorOf(r.with);
        }
        return r;
    }

    public String spec() {
        StringBuilder sb = new StringBuilder("scope=").append(scope);
        if (tag != null) sb.append(" tag=").append(tag);
        if (color != null) sb.append(" color=").append(color);
        if (material != null) sb.append(" material=").append(material);
        sb.append(" action=").append(action);
        if (with != null) sb.append(" with=").append(with);
        return sb.toString();
    }

    public boolean matches(Props p) {
        if (p.base().equals("air") || p.base().isEmpty()) return false;
        if (tag != null && !tag.equals(p.tag()) && !tag.equals(p.material()) && !tag.equals(p.base())) return false;
        if (color != null && !color.equals(p.color())) return false;
        return material == null || material.equals(p.material());
    }

    /** Como ficaria este bloco depois de cumprir a regra; null se não há como trocar (a regra não vai insistir). */
    public String replacement(String blockId) {
        if (!action.equals(REPLACE) || with == null) return null;
        String ns = blockId.contains(":") ? blockId.substring(0, blockId.indexOf(':') + 1) : "minecraft:";
        Props p = Props.of(blockId);
        if (Props.colorOf(with) != null) {
            if (p.color() == null) return null;
            return ns + with + "_" + p.base();
        }
        if (with.equals("wood") && "stone".equals(p.material())) {
            if (p.base().contains("brick")) return p.base().contains("stone") ? ns + "spruce_planks" : ns + "dark_oak_planks";
            return ns + "oak_planks";
        }
        if (with.equals("stone") && "wood".equals(p.material()) && p.tag().equals("planks")) return ns + "cobblestone";
        return null;
    }
}
