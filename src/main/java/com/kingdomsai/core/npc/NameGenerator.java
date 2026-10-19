package com.kingdomsai.core.npc;

import java.util.Random;
import java.util.Set;

public final class NameGenerator {
    private static final String[] MALE = {
            "Aldren", "Marcus", "Tobias", "Edric", "Halvard", "Roderick", "Bram", "Cedric", "Doran", "Fendrel",
            "Gareth", "Osric", "Leoric", "Matthias", "Bernard", "Anselm", "Godwin", "Hugo", "Ivo", "Jorah",
            "Kael", "Lucan", "Merek", "Nolan", "Orrin", "Percival", "Quentin", "Rowan", "Silas", "Theron",
            "Ulric", "Valen", "Wystan", "Benedito", "Afonso", "Rui", "Vasco", "Tomé", "Gonçalo", "Lourenço"};
    private static final String[] FEMALE = {
            "Elena", "Isolde", "Maren", "Brienne", "Rosalind", "Ysolde", "Adela", "Beatriz", "Catarina", "Desma",
            "Elowen", "Freya", "Gwen", "Helga", "Ingrid", "Joana", "Leonor", "Matilde", "Nerys", "Oriana",
            "Petra", "Rhea", "Sabine", "Teodora", "Urraca", "Viviane", "Wynne", "Yara", "Inês", "Branca"};
    private static final String[] KINGDOM_PREFIX = {"Nort", "Val", "Eld", "Gris", "Mor", "Ash", "Brun", "Kael", "Ost", "Ruv", "Sul", "Dar"};
    private static final String[] KINGDOM_SUFFIX = {"hold", "mar", "gard", "heim", "vale", "fell", "mark", "burgo", "ória", "ância"};

    private NameGenerator() {}

    public static String person(Random r, boolean female, Set<String> taken) {
        String[] pool = female ? FEMALE : MALE;
        for (int i = 0; i < 30; i++) {
            String n = pool[r.nextInt(pool.length)];
            if (!taken.contains(n.toLowerCase())) return n;
        }
        // Todos os nomes usados: acrescenta um sufixo.
        String base = pool[r.nextInt(pool.length)];
        int k = 2;
        while (taken.contains((base + " " + roman(k)).toLowerCase())) k++;
        return base + " " + roman(k);
    }

    public static String kingdom(Random r) {
        return KINGDOM_PREFIX[r.nextInt(KINGDOM_PREFIX.length)] + KINGDOM_SUFFIX[r.nextInt(KINGDOM_SUFFIX.length)];
    }

    private static String roman(int n) {
        String[] r = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return n < r.length ? r[n] : String.valueOf(n);
    }
}
