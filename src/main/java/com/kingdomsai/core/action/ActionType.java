package com.kingdomsai.core.action;

import com.kingdomsai.core.npc.Permission;

import java.util.List;

/**
 * Ações primitivas. A LLM (ou o jogador, ou a IA de um reino) combina estas ações;
 * ninguém executa "qualquer coisa imaginável".
 *
 * implemented = false → o Schema Validator rejeita com "not_available_in_this_phase".
 */
public enum ActionType {
    // Implementadas nesta fase
    BUILD(Permission.BUILD, true, List.of("blueprint"),
            "blueprint (id ou 'custom'), amount?, for?, deadline? (ex.: 5m, 1d, amanha); se custom: kind, width, depth, floors, roof, wall, roof_material, chimney"),
    DEADLINE(Permission.BUILD, true, List.of("deadline"), "building? (número da obra ou nome), deadline (5m, 1d, amanha)"),
    CANCEL_BUILD(Permission.BUILD, true, List.of("building"), "building (número da obra ou nome)"),
    RECRUIT(Permission.RECRUIT, true, List.of(), "amount? (sem amount o general/capitão decide quantos), npc? (quem escolhe: general/capitão)"),
    RELEASE(Permission.RECRUIT, true, List.of(), "amount?, profession?"),
    WORK(Permission.ASSIGN_WORK, true, List.of("profession"), "npc? | amount?, profession, from?"),
    PROMOTE(Permission.PROMOTE, true, List.of("npc", "office"), "npc, office"),
    DEMOTE(Permission.PROMOTE, true, List.of("npc"), "npc"),
    TAX(Permission.TAX, true, List.of("level"), "level (0-4 | up | down)"),
    LAW(Permission.LAW, true, List.of("law", "value"), "law (conscription|migration), value (on|off)"),
    CLAIM(Permission.CLAIM, true, List.of(), "amount?"),
    NEGOTIATE(Permission.DIPLOMACY, true, List.of("target"), "target, give?, give_amount?, want?, want_amount?, treaty?"),
    GIVE(Permission.DIPLOMACY, true, List.of("target", "resource", "amount"), "target, resource, amount"),
    DECLARE_WAR(Permission.DECLARE_WAR, true, List.of("target"), "target"),
    MAKE_PEACE(Permission.DIPLOMACY, true, List.of("target"), "target"),
    PATROL(Permission.ASSIGN_WORK, true, List.of("npc"), "npc"),
    GUARD(Permission.ASSIGN_WORK, true, List.of("npc"), "npc"),
    TALK(Permission.TALK, true, List.of(), "npc?, text?"),
    CHAIN(Permission.ASSIGN_WORK, true, List.of(),
            "template (minerar_ferreiro|plantar_colher|lenha|pedra|escrever|ler|carta) OU steps (lista JSON de etapas); npc? (quem adota a rotina), "
                    + "repeat? (true = daqui em diante), amount?, forge? (true = forjar espadas), topic?, title?, to?, text?, name?"),
    STOP_CHAIN(Permission.ASSIGN_WORK, true, List.of(), "chain? (número) | npc? (para a rotina dessa pessoa)"),
    JOB(Permission.ASSIGN_WORK, true, List.of(),
            "npc?, kind (break|dig|tunnel|clear|chop|take|put|craft|give) OU tasks (lista JSON); size? (3x3x3), length?, item?, count?, "
                    + "from?/to? (look=baú na mira do rei | storage=armazém | \"x y z\"), give? (true = entregar ao rei no fim)"),
    CANCEL_JOB(Permission.ASSIGN_WORK, true, List.of(), "job? (número) | npc?"),
    MARK(Permission.CLAIM, true, List.of("kind"),
            "kind (spawn|praca|mina|bosque), x?, y?, z? (padrão: o bloco que o rei mira), remove? (true = apagar o marco)"),
    SUMMON(Permission.TALK, true, List.of("npc"), "npc — a pessoa vem até onde o rei está (x?, z? para outro lugar); "
            + "VÁRIAS pessoas: who (todos | soldados | guardas | tropa | profissão) no lugar de npc, minutes? (quanto esperam, padrão 5)"),
    FOLLOW(Permission.TALK, true, List.of("npc"), "npc, minutes? (padrão 3) — a pessoa acompanha o rei"),
    DISMISS(Permission.TALK, true, List.of("npc"), "npc — dispensa quem foi chamado; volta à rotina"),
    ATTACK(Permission.COMMAND, true, List.of("target"),
            "target (nome do reino | aqui = onde o rei está | \"x z\"), amount? (soldados; sem amount o comandante decide), npc? (comandante), "
                    + "guards? (true = leva os guardas também), no_quarter? (true = sem piedade com os civis; só o rei, pede confirmação)"),
    OCCUPY(Permission.COMMAND, true, List.of("target"), "igual ATTACK: tomar e segurar a terra"),
    RETREAT(Permission.COMMAND, true, List.of(), "campaign? (número) — as tropas voltam para casa"),
    SETTLE(Permission.CLAIM, true, List.of(),
            "amount? (colonos, padrão 3), target? (aqui = onde o rei está | \"x z\" | reino) — gente que vai morar lá e toma a terra além do limite"),
    PURGE(Permission.JUDGE, true, List.of("target"),
            "target (vila = todos os civis do reino | cativos | escravos | nome de alguém) — os guardas matam; só o rei manda, pede confirmação, os guardas podem se recusar"),
    ENSLAVE(Permission.JUDGE, true, List.of("target"), "target (cativos | nome | vila), work? (profissão do trabalho forçado) — só o rei manda; precisa de guardas para vigiar"),
    FREE(Permission.JUDGE, true, List.of("target"), "target (escravos | cativos | nome | todos), home? (true = cativos voltam para a terra natal)"),

    DECREE(Permission.LAW, true, List.of("text"), "text (a lei, em português), effect? (NONE | CURFEW = toque de recolher | PEACE = proibido brigar | FEAST = festa), "
            + "rule? (lei sobre objetos: \"scope=homes|buildings|people tag=bed|door|light|container|glass|gold... color=red material=stone|wood|gold action=replace|remove|confiscate with=blue|wood\")"),
    REVOKE(Permission.LAW, true, List.of("decree"), "decree (número da lei)"),
    PETITION(Permission.LAW, true, List.of("petition", "verdict"), "petition (número), verdict (approve | reject)"),

    // Previstas na arquitetura, chegam em fases futuras
    MOVE(Permission.ASSIGN_WORK, true, List.of("to"),
            "to (aqui | praca | quartel | mina | bosque | spawn | forja | armazem | fazenda | campo | nome de alguém | \"x z\"), "
                    + "who? (soldados | guardas | tropa | todos | profissão | nomes separados por vírgula; sem who vai só npc), npc? (quem lidera), "
                    + "minutes? (quanto tempo ficam lá, padrão 3)"),
    GOAL(Permission.ASSIGN_WORK, true, List.of("goal"),
            "goal (comida | moradia | defesa | madeira | pedra | trabalho | territorio) — o conselheiro avalia o reino e executa os passos em nome do rei"),
    TRAIN(Permission.RECRUIT, true, List.of(),
            "npc? (instrutor: quem o rei mandou; ele escolhe quem treina), amount? (quantos; se faltar soldado ele convoca civis), "
                    + "guards? (true), where? (quartel | praca | campo | aqui | \"x z\"; padrão: quartel ou campo na borda da vila), minutes? (padrão 3)"),
    TRADE(Permission.TRADE, false, List.of(), ""),
    REPAIR(Permission.BUILD, false, List.of(), ""),
    TRAVEL(Permission.WORK, false, List.of(), ""),
    DEFEND(Permission.RECRUIT, true, List.of("where"),
            "where (portoes | torres | quartel | praca | \"x z\"), role? (archer | soldier | reserve), rule? (hold | pursue_near | pursue), minutes? — guarnição: soldados vão ao local e ficam"),
    SCOUT(Permission.RECRUIT, false, List.of(), ""),
    ARREST(Permission.JUDGE, false, List.of(), ""),
    JUDGE(Permission.JUDGE, false, List.of(), ""),
    EXILE(Permission.JUDGE, false, List.of(), ""),
    CONVERT(Permission.PREACH, false, List.of(), ""),
    PREACH(Permission.PREACH, false, List.of(), ""),
    FOUND_SETTLEMENT(Permission.CLAIM, false, List.of(), "");

    public final Permission permission;
    public final boolean implemented;
    public final List<String> requiredParams;
    public final String paramHelp;

    ActionType(Permission permission, boolean implemented, List<String> requiredParams, String paramHelp) {
        this.permission = permission;
        this.implemented = implemented;
        this.requiredParams = requiredParams;
        this.paramHelp = paramHelp;
    }

    public static ActionType parse(String s) {
        if (s == null) return null;
        try {
            return valueOf(s.trim().toUpperCase().replace(' ', '_').replace('-', '_'));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
