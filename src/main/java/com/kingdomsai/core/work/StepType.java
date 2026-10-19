package com.kingdomsai.core.work;

import com.kingdomsai.core.common.Text;
import com.kingdomsai.core.npc.Profession;

import java.util.EnumSet;
import java.util.Set;

/**
 * Etapas primitivas de uma cadeia de trabalho. A IA monta cadeias combinando estas etapas;
 * o {@link ChainValidator} confere se a sequência fecha (quem produz o que outra etapa consome).
 *
 * skilled = só quem tem a profissão consegue (fundir ferro exige ferreiro); nos outros casos
 * qualquer um faz, mais devagar.
 */
public enum StepType {
    MINE("minerar", Place.MINE, 6, true, EnumSet.of(Profession.MINER), false, Literacy.NONE, "pickaxe",
            "item (raw_iron|stone|coal), amount"),
    CHOP("cortar lenha", Place.FOREST, 4, true, EnumSet.of(Profession.LUMBERJACK), false, Literacy.NONE, "axe", "amount"),
    PLANT("plantar", Place.FARM, 20, false, EnumSet.of(Profession.FARMER, Profession.PEASANT), false, Literacy.NONE, "hoe", ""),
    HARVEST("colher", Place.FARM, 15, false, EnumSet.of(Profession.FARMER, Profession.PEASANT), false, Literacy.NONE, "hoe", ""),
    DELIVER("entregar", null, 3, false, EnumSet.noneOf(Profession.class), false, Literacy.NONE, "",
            "item, amount? (padrão: tudo na mão), to (smithy|storage|farm|library ou nome do NPC para cartas)"),
    PICKUP("pegar", null, 3, false, EnumSet.noneOf(Profession.class), false, Literacy.NONE, "", "item, amount, from (prédio)"),
    STORE("guardar no baú", Place.STORAGE, 3, false, EnumSet.noneOf(Profession.class), false, Literacy.NONE, "",
            "item?, amount? (padrão: tudo na mão) — vira estoque do reino"),
    SMELT("fundir ferro", Place.SMITHY, 8, true, EnumSet.of(Profession.BLACKSMITH), true, Literacy.NONE, "ingot",
            "amount (barras; cada barra gasta 1 ferro bruto, cada carvão funde 2)"),
    FORGE("forjar espadas", Place.SMITHY, 15, true, EnumSet.of(Profession.BLACKSMITH), true, Literacy.NONE, "sword",
            "amount (cada espada gasta 2 barras na mão)"),
    WRITE("escrever", Place.LIBRARY, 60, false, EnumSet.of(Profession.SCHOLAR, Profession.PRIEST), false, Literacy.WRITE, "book",
            "kind (book|letter), topic?, to? (carta: destinatário), text? (carta: mensagem)"),
    READ("ler", Place.LIBRARY, 45, false, EnumSet.of(Profession.SCHOLAR, Profession.PRIEST), false, Literacy.READ, "book",
            "title? (senão o primeiro livro ainda não lido)");

    public enum Literacy { NONE, READ, WRITE }

    public final String display;
    public final Place defaultPlace;
    public final int seconds;
    /** true = seconds é por unidade (amount). */
    public final boolean perUnit;
    public final Set<Profession> professions;
    public final boolean skilled;
    public final Literacy literacy;
    /** Ferramenta mostrada na mão do NPC (o adaptador traduz). */
    public final String tool;
    public final String paramHelp;

    StepType(String display, Place defaultPlace, int seconds, boolean perUnit, Set<Profession> professions, boolean skilled,
             Literacy literacy, String tool, String paramHelp) {
        this.display = display;
        this.defaultPlace = defaultPlace;
        this.seconds = seconds;
        this.perUnit = perUnit;
        this.professions = professions;
        this.skilled = skilled;
        this.literacy = literacy;
        this.tool = tool;
        this.paramHelp = paramHelp;
    }

    public static StepType parse(String s) {
        if (s == null) return null;
        String n = Text.norm(s).replace(' ', '_').replace('-', '_');
        for (StepType t : values()) if (t.name().equalsIgnoreCase(n) || Text.norm(t.display).replace(' ', '_').equals(n)) return t;
        return switch (n) {
            case "minerar", "minere", "cavar", "extrair" -> MINE;
            case "cortar", "lenhar", "derrubar" -> CHOP;
            case "plante", "semear" -> PLANT;
            case "colha", "colheita" -> HARVEST;
            case "entregue", "levar", "leve", "deliver_to" -> DELIVER;
            case "pegue", "buscar", "retirar" -> PICKUP;
            case "guardar", "guarde", "estocar", "armazenar" -> STORE;
            case "fundir", "derreter", "derreta", "funda" -> SMELT;
            case "forjar", "forje" -> FORGE;
            case "escreva" -> WRITE;
            case "leia" -> READ;
            default -> null;
        };
    }
}
