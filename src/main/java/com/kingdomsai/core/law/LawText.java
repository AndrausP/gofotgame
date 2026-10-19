package com.kingdomsai.core.law;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Leitor de frases de lei em português (modo regras, sem LLM): "proibido ter cama vermelha", "as casas só de madeira",
 * "ninguém anda com ouro". Devolve a regra no formato do {@link PropertyRule} ou null se a frase não fala de objetos.
 * Com uma LLM de verdade, é ela quem escreve a regra; isto é o equivalente determinístico e testável.
 */
public final class LawText {
    private LawText() {}

    private static final Map<String, String> NOUNS = new LinkedHashMap<>();

    static {
        for (String w : new String[]{"cama", "camas", "leito", "leitos"}) NOUNS.put(w, "bed");
        for (String w : new String[]{"porta", "portas"}) NOUNS.put(w, "door");
        for (String w : new String[]{"tocha", "tochas", "lampada", "lampadas"}) NOUNS.put(w, "light");
        for (String w : new String[]{"bau", "baus"}) NOUNS.put(w, "container");
        for (String w : new String[]{"vidro", "vidros", "janela", "janelas"}) NOUNS.put(w, "glass");
        for (String w : new String[]{"tapete", "tapetes", "la"}) NOUNS.put(w, "textile");
        for (String w : new String[]{"bandeira", "bandeiras", "estandarte", "estandartes"}) NOUNS.put(w, "banner");
        for (String w : new String[]{"cerca", "cercas"}) NOUNS.put(w, "fence");
    }

    private static final Pattern PROHIBIT = Pattern.compile(
            "\\b(proib\\w*|ninguem (?:pode|deve|vai)|nao (?:pode|podem|quero|permito)|nenhum\\w*|banid\\w*|chega de|acabe? com|fora de lei|vedad\\w*|fica proibid\\w*)\\b");

    /** @param t texto já normalizado (minúsculo, sem acento) */
    public static String rule(String t) {
        if (t == null) return null;
        // 1) casas só de madeira / de pedra
        if (t.matches(".*\\b(casas?|moradias?|construcoes|predios?)\\b.*\\b(so|somente|apenas|exclusivamente)\\b.*\\bmadeira\\b.*")
                || t.matches(".*\\b(proib\\w*|nao)\\b.*\\b(casas?|moradias?)\\b.*\\b(de )?pedra\\b.*")) {
            return "scope=homes material=stone action=replace with=wood";
        }
        // 2) ouro com o povo
        if (t.matches(".*\\bouro\\b.*") && (t.matches(".*\\b(anda\\w*|ande|carreg\\w+|ter|porta\\w*|guard\\w+|esconde\\w*)\\b.*") || PROHIBIT.matcher(t).find())
                && t.matches(".*\\b(fora d[oe]s? bau\\w*|com ele|com o povo|ninguem|ninguem anda|carreg\\w+|andar com)\\b.*")) {
            return "scope=people tag=gold action=confiscate";
        }
        // 3) objeto (e cor) proibido nas casas
        if (!PROHIBIT.matcher(t).find()) return null;
        String tag = null;
        for (var e : NOUNS.entrySet()) {
            if (t.matches(".*\\b" + e.getKey() + "\\b.*")) {
                tag = e.getValue();
                break;
            }
        }
        if (tag == null) return null;
        String color = firstColor(t);
        if (color == null) return "scope=homes tag=" + tag + " action=remove";
        String with = null;
        Matcher m = Pattern.compile("\\b(?:troque\\w*|trocar|substitu\\w+|mude\\w*|pintem?|por)\\b[^.]{0,30}?\\b(?:por|para|de|em)\\s+(?:\\w+\\s+)?(" + colorWords() + ")\\b").matcher(t);
        if (m.find()) with = Props.colorOf(m.group(1));
        if (with == null || with.equals(color)) with = defaultAlternative(color);
        return "scope=homes tag=" + tag + " color=" + color + " action=replace with=" + with;
    }

    private static String colorWords() {
        return "vermelh\\w*|azul(?: claro)?|azuis|verdes?|amarel\\w*|branc\\w*|pret\\w*|rosas?|rox\\w*|laranjas?|marrons?|cinzas?|ciano|lima|magenta";
    }

    private static String firstColor(String t) {
        Matcher m = Pattern.compile("\\b(" + colorWords() + ")\\b").matcher(t);
        if (!m.find()) return null;
        String w = m.group(1);
        if (w.startsWith("azuis")) w = "azul";
        else if (w.startsWith("vermelh")) w = "vermelho";
        else if (w.startsWith("amarel")) w = "amarelo";
        else if (w.startsWith("branc")) w = "branco";
        else if (w.startsWith("pret")) w = "preto";
        else if (w.startsWith("rox")) w = "roxo";
        return Props.colorOf(w);
    }

    private static String defaultAlternative(String color) {
        return switch (color) {
            case "red" -> "blue";
            case "blue" -> "white";
            case "black" -> "gray";
            default -> "white";
        };
    }
}
