package com.kingdomsai.core.action;

import com.kingdomsai.core.KingdomsCore;
import com.kingdomsai.core.construction.Blueprint;
import com.kingdomsai.core.construction.BlueprintLibrary;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.kingdom.ResourceType;
import com.kingdomsai.core.npc.Npc;
import com.kingdomsai.core.npc.Office;
import com.kingdomsai.core.npc.Profession;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Pipeline: Schema → Permission → World → Resource. Toda decisão (jogador, LLM, IA de reino)
 * passa por aqui antes de virar execução. Retornam null quando aprovam.
 */
public final class Validators {
    private Validators() {}

    public interface Validator {
        ActionResult validate(ActionRequest r, KingdomsCore core);
    }

    /** Ação existe nesta fase? Parâmetros obrigatórios presentes e bem formados? */
    public static final Validator SCHEMA = (r, core) -> {
        if (r.type() == null) return ActionResult.reject("unknown_action", "Ação desconhecida.");
        if (!r.type().implemented)
            return ActionResult.reject("not_available_in_this_phase", r.type() + " ainda não existe nesta versão do mod.");
        for (String p : r.type().requiredParams)
            if (r.param(p) == null || r.param(p).isBlank())
                return ActionResult.reject("missing_param", r.type() + " exige o parâmetro '" + p + "' (" + r.type().paramHelp + ").");
        int amount = r.intParam("amount", 1);
        if (r.type() != ActionType.GIVE && (amount < 1 || amount > 20)) return ActionResult.reject("invalid_param", "amount deve estar entre 1 e 20.");
        switch (r.type()) {
            case BUILD -> {
                if (isProgram(r)) return null;
                if (r.param("deadline") != null && com.kingdomsai.core.construction.ConstructionSystem.parseDuration(r.param("deadline")) < 0)
                    return ActionResult.reject("invalid_param", "Prazo inválido: " + r.param("deadline") + " (use 30s, 5m, 2h, 1d ou amanha).");
                if (com.kingdomsai.core.construction.VillageWall.isWallRequest(r.param("blueprint"))) {
                    int h = r.intParam("height", 4);
                    if (h < 3 || h > 6) return ActionResult.reject("invalid_param", "A muralha tem de 3 a 6 blocos de altura.");
                    if (amount != 1) return ActionResult.reject("invalid_param", "A vila só tem uma muralha.");
                    return null;
                }
                if (isCustom(r)) {
                    try {
                        com.kingdomsai.core.construction.ParametricBlueprints.spec(r.params());
                    } catch (IllegalArgumentException e) {
                        return ActionResult.reject("invalid_param", e.getMessage());
                    }
                    if (amount > 5) return ActionResult.reject("invalid_param", "No máximo 5 construções por ordem.");
                    return null;
                }
                if (BlueprintLibrary.find(r.param("blueprint")) == null)
                    return ActionResult.reject("invalid_param", "Planta desconhecida: " + r.param("blueprint")
                            + ". Disponíveis: " + String.join(", ", BlueprintLibrary.all().stream().map(Blueprint::id).toList()) + ".");
                if (amount > 5) return ActionResult.reject("invalid_param", "No máximo 5 construções por ordem.");
            }
            case DEADLINE -> {
                if (com.kingdomsai.core.construction.ConstructionSystem.parseDuration(r.param("deadline")) < 0)
                    return ActionResult.reject("invalid_param", "Prazo inválido: " + r.param("deadline") + " (use 30s, 5m, 2h, 1d ou amanha).");
            }
            case WORK -> {
                if (Profession.parse(r.param("profession")) == null)
                    return ActionResult.reject("invalid_param", "Profissão desconhecida: " + r.param("profession") + ".");
                if (r.param("from") != null && Profession.parse(r.param("from")) == null)
                    return ActionResult.reject("invalid_param", "Profissão de origem desconhecida: " + r.param("from") + ".");
            }
            case RELEASE -> {
                if (r.param("profession") != null && Profession.parse(r.param("profession")) == null)
                    return ActionResult.reject("invalid_param", "Profissão desconhecida: " + r.param("profession") + ".");
            }
            case PROMOTE -> {
                Office o = Office.parse(r.param("office"));
                if (o == null) return ActionResult.reject("invalid_param", "Cargo desconhecido: " + r.param("office") + ".");
                if (o == Office.KING) return ActionResult.reject("invalid_param", "Só pode haver um rei.");
            }
            case TAX -> {
                String v = r.param("level").trim().toLowerCase();
                if (!v.matches("[0-4]|up|down|\\+1|-1|aumentar|baixar|reduzir"))
                    return ActionResult.reject("invalid_param", "level deve ser 0-4, up ou down.");
            }
            case LAW -> {
                String law = r.param("law").toLowerCase();
                if (!law.startsWith("consc") && !law.startsWith("serv") && !law.startsWith("migr"))
                    return ActionResult.reject("invalid_param", "Leis disponíveis: conscription, migration.");
            }
            case DECREE -> {
                String tx = r.param("text").trim();
                if (tx.length() < 6 || tx.length() > 200) return ActionResult.reject("invalid_param", "A lei deve ter entre 6 e 200 caracteres.");
                String ef = r.param("effect");
                if (ef != null && !ef.isBlank() && !ef.trim().toUpperCase().matches("NONE|CURFEW|PEACE|FEAST"))
                    return ActionResult.reject("invalid_param", "effect deve ser NONE, CURFEW, PEACE ou FEAST.");
                String rule = r.param("rule");
                if (rule != null && !rule.isBlank()) {
                    try {
                        com.kingdomsai.core.law.PropertyRule.parse(rule);
                    } catch (IllegalArgumentException e) {
                        return ActionResult.reject("invalid_param", "rule inválida: " + e.getMessage());
                    }
                }
            }
            case REVOKE, PETITION -> {
                String key = r.type() == ActionType.REVOKE ? "decree" : "petition";
                if (!r.param(key).trim().matches("#?\\d+")) return ActionResult.reject("invalid_param", key + " deve ser um número.");
                if (r.type() == ActionType.PETITION && !r.param("verdict").trim().toLowerCase().matches("approve|reject|aprovar|rejeitar"))
                    return ActionResult.reject("invalid_param", "verdict deve ser approve ou reject.");
            }
            case GIVE -> {
                if (ResourceType.parse(r.param("resource")) == null)
                    return ActionResult.reject("invalid_param", "Recurso desconhecido: " + r.param("resource") + ".");
                if (r.intParam("amount", 0) < 1 || r.intParam("amount", 0) > 5000)
                    return ActionResult.reject("invalid_param", "Quantidade inválida.");
            }
            case MARK -> {
                if (com.kingdomsai.core.kingdom.Marker.parse(r.param("kind")) == null)
                    return ActionResult.reject("invalid_param", "Marco desconhecido: " + r.param("kind") + " (use spawn, praca, mina ou bosque).");
            }
            case JOB -> {
                try {
                    com.kingdomsai.core.skill.JobPlanner.specs(r.params());
                } catch (IllegalArgumentException e) {
                    return ActionResult.reject("invalid_param", e.getMessage());
                }
            }
            case CHAIN -> {
                try {
                    com.kingdomsai.core.work.ChainTemplates.spec(r.params(), core, core.kingdom(r.kingdomId()));
                } catch (IllegalArgumentException e) {
                    return ActionResult.reject("invalid_param", e.getMessage());
                }
            }
            case ENSLAVE -> {
                if (r.param("work") != null && Profession.parse(r.param("work")) == null)
                    return ActionResult.reject("invalid_param", "Profissão desconhecida para o trabalho forçado: " + r.param("work") + ".");
            }
            case NEGOTIATE -> {
                if (r.param("treaty") == null && (ResourceType.parse(r.param("give")) == null || ResourceType.parse(r.param("want")) == null))
                    return ActionResult.reject("missing_param", "NEGOTIATE exige treaty=<tipo> ou give/give_amount/want/want_amount.");
            }
            default -> {
            }
        }
        return null;
    };

    /** BUILD blueprint=program: obra composta pelo arquiteto (catedral, mosteiro, fortaleza). */
    public static boolean isProgram(ActionRequest r) {
        return r.type() == ActionType.BUILD && ("program".equalsIgnoreCase(r.param("blueprint")) || isWatchtowers(r));
    }

    public static boolean isWatchtowers(ActionRequest r) {
        return r.type() == ActionType.BUILD && "watchtowers".equalsIgnoreCase(r.param("blueprint"));
    }

    public static boolean isCustom(ActionRequest r) {
        String b = r.param("blueprint");
        if (b == null) return false;
        String n = com.kingdomsai.core.common.Text.norm(b);
        return n.equals("custom") || n.equals("projeto") || n.equals("personalizado") || n.equals("parametric");
    }

    /** O ator tem autoridade? Um camponês não declara guerra. */
    public static final Validator PERMISSION = (r, core) -> {
        Kingdom k = core.kingdom(r.kingdomId());
        if (k == null) return ActionResult.reject("no_kingdom", "Você não governa um reino.");
        switch (r.actorKind()) {
            case PLAYER -> {
                if (r.actorId() == null || !r.actorId().equals(k.rulerPlayer))
                    return ActionResult.reject("permission_denied", "Só o governante de " + k.name + " pode ordenar isso.");
            }
            case DIRECTOR -> {
                if (rulerOnly(r))
                    return ActionResult.reject("permission_denied", "Massacre e escravidão só por ordem direta de um rei jogador.");
                if (needsKing(r.type()))
                    return ActionResult.reject("permission_denied", "Ordem que depende da posição de um rei jogador.");
                if (k.isPlayerKingdom())
                    return ActionResult.reject("permission_denied", "A IA não governa o reino do jogador.");
            }
            case NPC -> {
                if (rulerOnly(r))
                    return ActionResult.reject("permission_denied", "Só o rei pode ordenar isso (matar, escravizar, atacar sem piedade) — nenhum conselheiro decide por ele.");
                if (needsKing(r.type()))
                    return ActionResult.reject("permission_denied", "Só o rei dá essa ordem: ela depende de onde ele está e do que ele mira.");
                Npc n = core.npc(r.actorId());
                if (n == null || !n.alive || !k.id.equals(n.kingdomId))
                    return ActionResult.reject("permission_denied", "NPC inválido para este reino.");
                if (!n.office.allows(r.type().permission))
                    return ActionResult.reject("permission_denied", n.displayName() + " não tem autoridade para " + r.type() + ".");
            }
        }
        return null;
    };

    /** O mundo permite? Alvos existem, limites respeitados. */
    public static final Validator WORLD = (r, core) -> {
        Kingdom k = core.kingdom(r.kingdomId());
        switch (r.type()) {
            case BUILD -> {
                if (!core.config().constructionEnabled) return ActionResult.reject("disabled", "Construção desativada na config.");
                if (core.construction().projects(k.id).size() + r.intParam("amount", 1) > 8)
                    return ActionResult.reject("too_many_projects", "Já existem obras demais em andamento (máx. 8).");
                if (r.param("for") != null && core.findNpc(k.id, r.param("for")) == null)
                    return ActionResult.reject("not_found", "Não encontrei " + r.param("for") + " no reino.");
                if (com.kingdomsai.core.construction.VillageWall.isWallRequest(r.param("blueprint"))) {
                    var existing = com.kingdomsai.core.construction.VillageWall.existing(core, k);
                    if (existing != null)
                        return ActionResult.reject("already_exists", "A vila já tem " + existing.blueprint().displayName().toLowerCase()
                                + (existing.isComplete() ? "." : " em obra (" + (int) existing.percent() + "%)."));
                }
            }
            case DEADLINE, CANCEL_BUILD -> {
                if (ActionSystem.findProject(core, k, r.param("building")) == null)
                    return ActionResult.reject("not_found", "Não encontrei a obra " + (r.param("building") == null ? "" : r.param("building"))
                            + ". Veja /k projects (use o número da obra).");
            }
            case PROMOTE, DEMOTE, PATROL, GUARD -> {
                Npc n = core.findNpc(k.id, r.param("npc"));
                if (n == null) return ActionResult.reject("not_found", "Não encontrei " + r.param("npc") + " em " + k.name + ".");
                if (n.office == Office.KING) return ActionResult.reject("invalid_target", "Não é possível alterar o rei.");
                if (!n.isFree()) return ActionResult.reject("not_free", n.name + " é " + n.freedom.display.toLowerCase() + ": liberte antes (FREE).");
            }
            case WORK -> {
                if (r.param("npc") != null) {
                    Npc n = core.findNpc(k.id, r.param("npc"));
                    if (n == null) return ActionResult.reject("not_found", "Não encontrei " + r.param("npc") + " em " + k.name + ".");
                    if (n.office == Office.KING) return ActionResult.reject("invalid_target", "O rei não muda de profissão.");
                    if (n.freedom == com.kingdomsai.core.npc.Freedom.CAPTIVE)
                        return ActionResult.reject("not_free", n.name + " é cativo: liberte (FREE) ou ponha para trabalhar à força (ENSLAVE).");
                }
            }
            case RECRUIT -> {
                int candidates = 0;
                for (Npc n : core.citizens(k.id))
                    if (!n.profession.isMilitary() && n.office == Office.NONE && n.isFree() && !core.warfare().atWar(n)) candidates++;
                if (candidates == 0) return ActionResult.reject("not_enough_people", "Não há civis livres para convocar.");
                if (r.param("amount") != null && candidates < r.intParam("amount", 1))
                    return ActionResult.reject("not_enough_people", "Só há " + candidates + " civis livres para convocar.");
                if (r.param("npc") != null && core.findNpc(k.id, r.param("npc")) == null)
                    return ActionResult.reject("not_found", "Não encontrei " + r.param("npc") + " para comandar a convocação.");
            }
            case ATTACK, OCCUPY, SETTLE -> {
                Target t = attackTarget(core, k, r);
                if (t.error() != null) return ActionResult.reject("invalid_target", t.error());
                if (r.type() != ActionType.SETTLE && r.actorKind() != ActionRequest.ActorKind.PLAYER && t.owner() != null) {
                    var bound = core.diplomacy().binding(k.id, t.owner().id);
                    if (bound != null)
                        return ActionResult.reject("treaty_in_force", "O " + bound.type.display.toLowerCase() + " com " + t.owner().name
                                + " ainda vale: a IA não o rompe atacando.");
                }
                if (r.type() == ActionType.SETTLE) {
                    if (settlers(core, k, r.intParam("amount", 3)).isEmpty())
                        return ActionResult.reject("not_enough_people", "Não há civis livres para mandar como colonos.");
                    break;
                }
                if (r.param("npc") != null && core.findNpc(k.id, r.param("npc")) == null)
                    return ActionResult.reject("not_found", "Não encontrei " + r.param("npc") + " para comandar.");
                if (core.warfare().available(k, "true".equalsIgnoreCase(r.param("guards"))).isEmpty())
                    return ActionResult.reject("no_troops", "Não há soldados em casa para mandar"
                            + (core.count(k.id, Profession.GUARD) > 0 && !"true".equalsIgnoreCase(r.param("guards")) ? " (os guardas ficam na vila; diga \"leve os guardas\")" : "")
                            + ". Convoque primeiro (RECRUIT): não custa ouro, custa comida.");
            }
            case GOAL -> {
                if (com.kingdomsai.core.ai.KingdomDirector.goalOf(r.param("goal")) == null)
                    return ActionResult.reject("invalid_param", "Objetivo desconhecido: " + r.param("goal") + " (comida, moradia, defesa, madeira, pedra, trabalho, territorio).");
                if (r.actorKind() != ActionRequest.ActorKind.PLAYER)
                    return ActionResult.reject("permission_denied", "Só o rei delega objetivos ao conselho.");
            }
            case TRAIN -> {
                Npc inst = trainer(core, k, r);
                if (inst == null) return ActionResult.reject("not_found", r.param("npc") != null ? "Não encontrei " + r.param("npc") + "."
                        : "Não há ninguém para instruir a tropa: nomeie um capitão ou fale com um soldado.");
                if (!inst.isFree()) return ActionResult.reject("not_free", inst.name + " não é livre para comandar.");
                Target t = place(core, k, r, r.param("where") == null ? "campo" : r.param("where"));
                if (t.error() != null) return ActionResult.reject("invalid_target", t.error());
            }
            case MOVE -> {
                Target t = place(core, k, r, r.param("to"));
                if (t.error() != null) return ActionResult.reject("invalid_target", t.error());
                if (movers(core, k, r).isEmpty())
                    return ActionResult.reject("not_found", "Não encontrei quem deve ir" + (r.param("who") != null ? " (\"" + r.param("who") + "\")" : "") + ".");
            }
            case RETREAT -> {
                var c = core.warfare().find(k.id, r.param("campaign"));
                if (c == null || !c.live()) return ActionResult.reject("not_found", "Não há tropa fora de casa" + (r.param("campaign") != null ? " com esse número" : "") + ".");
            }
            case PURGE -> {
                List<Npc> victims = purgeVictims(core, k, r);
                if (victims.isEmpty()) return ActionResult.reject("not_found", "Não encontrei quem: " + r.param("target") + ".");
                if (core.warfare().executors(k, victims).isEmpty())
                    return ActionResult.reject("no_executors", "Não há guardas nem soldados em casa para cumprir a ordem (estão em campanha?).");
            }
            case ENSLAVE -> {
                List<Npc> victims = enslaveVictims(core, k, r);
                if (victims.isEmpty()) return ActionResult.reject("not_found", "Ninguém para escravizar em \"" + r.param("target") + "\" (já escravizados ou não encontrados).");
                if (core.warfare().executors(k, victims).isEmpty())
                    return ActionResult.reject("no_executors", "Sem guardas ou soldados em casa não há quem obrigue nem quem vigie.");
            }
            case FREE -> {
                if (freeTargets(core, k, r).isEmpty())
                    return ActionResult.reject("not_found", "Não há ninguém preso ou escravizado" + (r.param("target") != null ? " em \"" + r.param("target") + "\"" : "") + ".");
            }
            case RELEASE -> {
                if (core.count(k.id, Profession.SOLDIER) < r.intParam("amount", 1))
                    return ActionResult.reject("not_enough_people", "Só há " + core.count(k.id, Profession.SOLDIER) + " soldados.");
            }
            case CHAIN -> {
                var v = com.kingdomsai.core.work.ChainValidator.validate(core, k,
                        com.kingdomsai.core.work.ChainTemplates.spec(r.params(), core, k));
                if (!v.ok()) return ActionResult.reject("chain_invalid", "A cadeia não fecha: " + String.join(" ", v.errors()));
            }
            case SUMMON, FOLLOW, DISMISS -> {
                Npc n = core.findNpc(k.id, r.param("npc"));
                if (n == null) return ActionResult.reject("not_found", "Não encontrei " + r.param("npc") + " em " + k.name + ".");
                if (n.office == Office.KING) return ActionResult.reject("invalid_target", "O rei não é chamado: ele chama.");
                if (r.type() == ActionType.DISMISS) {
                    if (!core.scheduler().isSummoned(n)) return ActionResult.reject("invalid_target", n.name + " não foi chamado.");
                    break;
                }
                com.kingdomsai.core.common.Pos to = summonTarget(core, r);
                if (to == null) return ActionResult.reject("unknown_position", "Não sei onde Vossa Majestade está agora.");
                if (n.pos != null && n.pos.distXZ(to) > com.kingdomsai.core.ai.NpcScheduler.MAX_SUMMON_DISTANCE)
                    return ActionResult.reject("too_far", n.name + " está a " + (int) n.pos.distXZ(to) + " blocos — longe demais para atender ao chamado (máx. "
                            + com.kingdomsai.core.ai.NpcScheduler.MAX_SUMMON_DISTANCE + ").");
            }
            case MARK -> {
                if ("true".equals(r.param("remove"))) break;
                com.kingdomsai.core.common.Pos p = markTarget(core, r);
                if (p == null) return ActionResult.reject("unknown_position", "Mire no chão (ou informe x y z) para eu saber onde marcar.");
                if (!k.id.equals(core.state().territory.ownerAt(p)))
                    return ActionResult.reject("outside_territory", "Esse ponto fica fora do território de " + k.name + ". Reivindique a região antes (CLAIM).");
                var m = com.kingdomsai.core.kingdom.Marker.parse(r.param("kind"));
                String unsafe = unsafeStanding(core, p);
                if (unsafe != null && (m == com.kingdomsai.core.kingdom.Marker.SPAWN || m == com.kingdomsai.core.kingdom.Marker.GATHER))
                    return ActionResult.reject("unsafe", "Não dá para marcar " + m.display.toLowerCase() + " aí: " + unsafe + ".");
            }
            case JOB -> {
                var plan = com.kingdomsai.core.skill.JobPlanner.plan(core, k, r.actorId(), r.params());
                if (!plan.ok()) return ActionResult.reject("job_invalid", String.join(" ", plan.errors()));
            }
            case CANCEL_JOB -> {
                var j = core.skills().find(k.id, r.param("job") != null ? r.param("job") : r.param("npc"));
                if (j == null || !j.status.live()) return ActionResult.reject("not_found", "Não há ordem em andamento" + (r.param("npc") != null ? " para " + r.param("npc") : "") + ".");
            }
            case STOP_CHAIN -> {
                if (findChain(core, k, r) == null)
                    return ActionResult.reject("not_found", "Não encontrei essa cadeia" + (r.param("npc") != null ? " para " + r.param("npc") : "") + ". Veja /k chains.");
            }
            case CLAIM -> {
                if (core.state().territory.claimableFrontier(k.id, k.center).isEmpty())
                    return ActionResult.reject("no_frontier", "Não há células livres na fronteira. Para crescer: colonos (SETTLE) ou tropas (ATTACK).");
                int free = core.warfare().freeClaims(k);
                if (free < r.intParam("amount", 1))
                    return ActionResult.reject("claim_limit", "O reino só sustenta " + core.warfare().claimLimit(k) + " células por reivindicação ("
                            + com.kingdomsai.core.military.MilitarySystem.CLAIM_BASE + " + 2 por morador livre + 3 por militar); restam " + free
                            + ". Além disso a terra se TOMA: mande colonos (SETTLE) ou tropas (ATTACK) — inclusive em terra de outro reino.");
            }
            case NEGOTIATE, GIVE, DECLARE_WAR, MAKE_PEACE -> {
                Kingdom t = core.findKingdom(r.param("target"));
                if (t == null) return ActionResult.reject("not_found", "Reino desconhecido: " + r.param("target") + ".");
                if (t.id.equals(k.id)) return ActionResult.reject("invalid_target", "Não dá para negociar consigo mesmo.");
                if (r.type() == ActionType.DECLARE_WAR && core.diplomacy().link(k.id, t.id).state == com.kingdomsai.core.diplomacy.Diplomacy.State.WAR)
                    return ActionResult.reject("invalid_target", "Já estamos em guerra com " + t.name + ".");
                if (r.type() == ActionType.DECLARE_WAR && r.actorKind() != ActionRequest.ActorKind.PLAYER) {
                    var bound = core.diplomacy().binding(k.id, t.id);
                    if (bound != null)
                        return ActionResult.reject("treaty_in_force", "O " + bound.type.display.toLowerCase() + " com " + t.name
                                + " ainda vale (até o dia " + (bound.expiresTick / 24000 + 1) + "): só o rei jogador pode rompê-lo.");
                }
                if (r.type() == ActionType.NEGOTIATE && r.param("treaty") != null
                        && com.kingdomsai.core.diplomacy.Diplomacy.TreatyType.parse(r.param("treaty")) == null)
                    return ActionResult.reject("invalid_param", "Tratado desconhecido: " + r.param("treaty") + ".");
            }
            default -> {
            }
        }
        return null;
    };

    /** Matar, escravizar e atacar sem piedade: só o próprio rei, nunca um NPC/IA de reino. */
    static boolean rulerOnly(ActionRequest r) {
        return r.type() == ActionType.PURGE || r.type() == ActionType.ENSLAVE
                || (r.type() == ActionType.ATTACK || r.type() == ActionType.OCCUPY) && "true".equalsIgnoreCase(r.param("no_quarter"));
    }

    /** Alvo de ataque/colonização: ponto e dono da terra (null = livre), ou o erro. */
    public record Target(com.kingdomsai.core.common.Pos pos, Kingdom owner, String error) {}

    public static Target attackTarget(KingdomsCore core, Kingdom k, ActionRequest r) {
        String raw = r.param("target");
        String t = raw == null ? "" : com.kingdomsai.core.common.Text.norm(raw);
        com.kingdomsai.core.common.Pos pos;
        if (t.isBlank() || t.matches("(aqui|ali|la|ca|look|mira|here|aqui mesmo|para ca|pra ca|onde estou|onde eu estou|onde o rei esta)")) {
            if (r.actorKind() != ActionRequest.ActorKind.PLAYER)
                return new Target(null, null, "\"Aqui\" depende de onde o rei está: diga o nome do reino ou x z.");
            KingdomsCore.Look look = core.playerLook(r.actorId());
            pos = look != null && look.block() != null ? look.block() : core.playerPos(r.actorId());
            if (pos == null) return new Target(null, null, "Não sei onde Vossa Majestade está agora.");
        } else if (t.matches("-?\\d+\\s+-?\\d+(\\s+-?\\d+)?")) {
            String[] p = t.split("\\s+");
            int x = Integer.parseInt(p[0]), z = Integer.parseInt(p[p.length - 1]);
            int y = p.length == 3 ? Integer.parseInt(p[1]) : core.world().surfaceY(x, z);
            pos = new com.kingdomsai.core.common.Pos(x, y == Integer.MIN_VALUE ? k.center.y() : y, z);
        } else {
            Kingdom o = core.findKingdom(raw);
            if (o == null) return new Target(null, null, "Não conheço esse lugar ou reino: " + raw + ". Use o nome do reino, \"aqui\" ou x z.");
            if (o == k) return new Target(null, null, "Esse é o nosso próprio reino.");
            if (core.population(o.id) == 0 && core.state().territory.countOwned(o.id) == 0)
                return new Target(null, null, o.name + " não existe mais.");
            pos = objective(core, k, o, r);
        }
        if (pos.distXZ(k.center) > 3000) return new Target(null, null, "Longe demais (" + (int) pos.distXZ(k.center) + " blocos; máx. 3000).");
        var cell = core.state().territory.cellAt(pos);
        Kingdom owner = cell == null || cell.owner == null ? null : core.kingdom(cell.owner);
        if (owner == k) return new Target(null, null, "Essa terra já é nossa. Aponte para terra livre ou de outro reino.");
        return new Target(pos, owner, null);
    }

    /** O comandante escolhe o objetivo: a vila, se a tropa dá conta; senão a célula de fronteira mais perto de casa. */
    private static com.kingdomsai.core.common.Pos objective(KingdomsCore core, Kingdom k, Kingdom o, ActionRequest r) {
        String obj = r.param("objective") == null ? "" : com.kingdomsai.core.common.Text.norm(r.param("objective"));
        var war = core.warfare();
        double force = 0;
        for (Npc n : war.available(k, "true".equalsIgnoreCase(r.param("guards")))) force += war.power(n, k);
        boolean village = obj.matches(".*(vila|capital|centro|cidade|tudo).*")
                || !obj.matches(".*(fronteira|borda).*") && force >= war.defenseAt(o, o.center) * 1.2;
        if (village) return o.center;
        var t = core.state().territory;
        com.kingdomsai.core.common.Pos best = o.center;
        double bestD = Double.MAX_VALUE;
        for (var c : t.cells.values()) {
            if (!o.id.equals(c.owner)) continue;
            com.kingdomsai.core.common.Pos p = new com.kingdomsai.core.common.Pos(c.cx * t.cellSize + t.cellSize / 2, o.center.y(), c.cz * t.cellSize + t.cellSize / 2);
            double d = p.distXZ(k.center);
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    /** Instrutor do treino: quem o rei mandou; senão general/capitão; senão o soldado mais experiente. */
    public static Npc trainer(KingdomsCore core, Kingdom k, ActionRequest r) {
        if (r.param("npc") != null && !r.param("npc").isBlank()) return core.findNpc(k.id, r.param("npc"));
        return core.warfare().commander(k, null);
    }

    /**
     * Lugar de uma ordem de ir/treinar: aqui (onde o rei está/mira), praça, quartel, mina, bosque, spawn, forja, armazém, fazenda,
     * campo (de treino: quartel ou a borda da vila), o nome de alguém ou "x z".
     */
    public static Target place(KingdomsCore core, Kingdom k, ActionRequest r, String where) {
        String w = com.kingdomsai.core.common.Text.norm(where == null ? "" : where).replaceAll("^(o|a|na|no|para o|para a|pra|ao|ate o|ate a)\\s+", "");
        if (w.isBlank()) return new Target(null, null, "Para onde? (aqui, praça, quartel, mina, bosque, campo ou x z)");
        com.kingdomsai.core.common.Pos p = null;
        if (w.matches("(aqui|ca|la|ali|onde estou|onde eu estou|perto de mim|comigo|here)")) {
            if (r.actorKind() != ActionRequest.ActorKind.PLAYER) return new Target(null, null, "\"Aqui\" depende de onde o rei está.");
            KingdomsCore.Look look = core.playerLook(r.actorId());
            p = look != null && look.block() != null ? look.block().offset(0, 1, 0) : core.playerPos(r.actorId());
            if (p == null) return new Target(null, null, "Não sei onde Vossa Majestade está agora.");
        } else if (w.matches("-?\\d+\\s+-?\\d+(\\s+-?\\d+)?")) {
            String[] s = w.split("\\s+");
            int x = Integer.parseInt(s[0]), z = Integer.parseInt(s[s.length - 1]);
            int y = s.length == 3 ? Integer.parseInt(s[1]) : core.world().surfaceY(x, z);
            p = new com.kingdomsai.core.common.Pos(x, y == Integer.MIN_VALUE ? k.center.y() : y, z);
        } else if (w.matches("(praca|centro|salao|salao real|vila|aldeia)")) p = k.marker(com.kingdomsai.core.kingdom.Marker.GATHER, k.center);
        else if (w.matches("(spawn|entrada|chegada)")) p = k.spawnPoint();
        else if (w.matches("(mina|pedreira)")) {
            p = k.markers.get(com.kingdomsai.core.kingdom.Marker.MINE);
            if (p == null) return new Target(null, null, "O reino não tem mina marcada: use a Bandeira (\"marque aqui como mina\").");
        } else if (w.matches("(bosque|floresta|mata)")) {
            p = k.markers.get(com.kingdomsai.core.kingdom.Marker.FOREST);
            if (p == null) return new Target(null, null, "O reino não tem bosque marcado: use a Bandeira (\"marque aqui como bosque\").");
        } else if (w.matches("(quartel|caserna|campo|campo de treino|treino|patio)")) {
            com.kingdomsai.core.construction.Building b = completed(core, k, "barracks");
            if (b != null) p = b.entrance();
            else if (w.startsWith("quartel") || w.startsWith("casern")) return new Target(null, null, "O reino ainda não tem quartel. Use \"campo\" (borda da vila) ou construa um.");
            else {
                var vb = com.kingdomsai.core.construction.VillageWall.bounds(core, k);
                p = vb.center(k.center.y()).offset(vb.radius() + 8, 0, 0); // campo aberto na borda leste da vila
                int y = core.world().surfaceY(p.x(), p.z());
                if (y != Integer.MIN_VALUE) p = new com.kingdomsai.core.common.Pos(p.x(), y, p.z());
            }
        } else if (w.matches("(forja|ferraria|armazem|deposito|fazenda|lavoura|biblioteca)")) {
            String id = w.startsWith("forj") || w.startsWith("ferr") ? "smithy" : w.startsWith("armaz") || w.startsWith("depos") ? "storage"
                    : w.startsWith("bibli") ? "library" : "farm";
            com.kingdomsai.core.construction.Building b = completed(core, k, id);
            if (b == null) return new Target(null, null, "O reino não tem " + where + " pronta.");
            p = b.entrance();
        } else {
            Npc n = core.findNpc(k.id, where);
            if (n == null || n.pos == null) return new Target(null, null, "Não conheço o lugar \"" + where + "\" (aqui, praça, quartel, mina, bosque, campo ou x z).");
            p = n.pos;
        }
        if (p.distXZ(k.center) > 3000) return new Target(null, null, "Longe demais.");
        return new Target(p, null, null);
    }

    private static com.kingdomsai.core.construction.Building completed(KingdomsCore core, Kingdom k, String id) {
        for (var b : core.buildings(k.id)) if (b.isComplete() && b.blueprintId.equals(id) && b.origin.y() != Integer.MIN_VALUE) return b;
        return null;
    }

    /** Quem vai: o grupo pedido (soldados, guardas, tropa, todos, profissão, nomes) e/ou o líder. */
    public static List<Npc> movers(KingdomsCore core, Kingdom k, ActionRequest r) {
        List<Npc> out = new java.util.ArrayList<>();
        Npc lead = r.param("npc") == null ? null : core.findNpc(k.id, r.param("npc"));
        if (lead != null) out.add(lead);
        String who = r.param("who") == null ? "" : com.kingdomsai.core.common.Text.norm(r.param("who"));
        if (!who.isBlank()) {
            Profession prof = Profession.parse(who.replaceAll("s$", ""));
            boolean soldiers = who.matches(".*(soldad|tropa|exercito|militar|homens).*"), guards = who.matches(".*(guarda|tropa|exercito|militar).*");
            boolean all = who.matches(".*(todos|todo mundo|todas|povo|geral).*");
            for (Npc n : core.citizens(k.id)) {
                if (out.contains(n) || n.office == Office.KING || !n.isFree() || core.warfare().atWar(n)) continue;
                if (all || soldiers && n.profession == Profession.SOLDIER || guards && n.profession == Profession.GUARD
                        || prof != null && n.profession == prof) out.add(n);
            }
            if (out.size() <= (lead == null ? 0 : 1))
                for (String name : who.split("\\s*(,| e )\\s*")) {
                    Npc n = core.findNpc(k.id, name);
                    if (n != null && !out.contains(n)) out.add(n);
                }
        }
        out.removeIf(n -> n.office == Office.KING);
        return out.size() > 40 ? out.subList(0, 40) : out;
    }

    /** Colonos: civis livres sem cargo; camponeses primeiro, depois das profissões com mais gente. */
    public static List<Npc> settlers(KingdomsCore core, Kingdom k, int amount) {
        List<Npc> pool = new java.util.ArrayList<>();
        Map<Profession, Integer> per = new EnumMap<>(Profession.class);
        for (Npc n : core.citizens(k.id)) per.merge(n.profession, 1, Integer::sum);
        for (Npc n : core.citizens(k.id))
            if (n.isFree() && !n.profession.isMilitary() && n.office == Office.NONE && !core.warfare().atWar(n) && n.jobId == null
                    && !core.scheduler().isSummoned(n)) pool.add(n);
        pool.sort(java.util.Comparator.comparingInt((Npc n) -> n.profession == Profession.PEASANT ? 0 : 1)
                .thenComparingInt(n -> -per.getOrDefault(n.profession, 0))
                .thenComparingInt(n -> -n.trait(com.kingdomsai.core.npc.Trait.COURAGE)));
        return new java.util.ArrayList<>(pool.subList(0, Math.min(amount, pool.size())));
    }

    public static List<Npc> purgeVictims(KingdomsCore core, Kingdom k, ActionRequest r) {
        return core.warfare().targets(k, r.param("target"));
    }

    public static List<Npc> enslaveVictims(KingdomsCore core, Kingdom k, ActionRequest r) {
        List<Npc> out = new java.util.ArrayList<>();
        for (Npc n : core.warfare().targets(k, r.param("target")))
            if (n.freedom != com.kingdomsai.core.npc.Freedom.ENSLAVED && n.office != Office.KING) out.add(n);
        return out;
    }

    public static Profession forcedWork(KingdomsCore core, Kingdom k, ActionRequest r) {
        Profession p = r.param("work") == null ? null : Profession.parse(r.param("work"));
        if (p != null && p != Profession.SOLDIER && p != Profession.GUARD) return p;
        return core.count(k.id, Profession.MINER) < 2 ? Profession.MINER : Profession.FARMER;
    }

    public static List<Npc> freeTargets(KingdomsCore core, Kingdom k, ActionRequest r) {
        String t = com.kingdomsai.core.common.Text.norm(r.param("target") == null ? "todos" : r.param("target"));
        List<Npc> out = new java.util.ArrayList<>();
        if (t.matches(".*\\b(todos|todas|geral|everyone|all)\\b.*")) {
            for (Npc n : core.citizens(k.id)) if (!n.isFree()) out.add(n);
            return out;
        }
        for (Npc n : core.warfare().targets(k, r.param("target"))) if (!n.isFree()) out.add(n);
        return out;
    }

    /** Palavras que a LLM e a CLI usam para "várias pessoas" no lugar de um nome. */
    private static final String GROUP_WORDS = "(todos|todas|todo mundo|toda a gente|povo|geral|moradores|suditos|subditos|aldeoes|gente|"
            + "soldados|guardas|tropa|tropas|exercito|militares|homens|everyone|everybody|all)";

    /**
     * Chamar VÁRIAS pessoas (who = todos | soldados | guardas | tropa | profissão, ou npc = "todos") não é um SUMMON de um nome só:
     * vira um MOVE para onde o rei está (ou x z) com o mesmo grupo. Assim chat, CLI e LLM fazem exatamente a mesma coisa, e
     * "vou chamar todos" nunca fica só na fala.
     */
    public static ActionRequest groupCall(KingdomsCore core, ActionRequest r) {
        if (r.type() != ActionType.SUMMON) return r;
        Kingdom k = core.kingdom(r.kingdomId());
        String who = r.param("who") == null ? "" : r.param("who").trim();
        String npc = r.param("npc") == null ? "" : r.param("npc").trim();
        boolean npcIsGroup = false;
        if (who.isBlank() && !npc.isBlank()) {
            String n = com.kingdomsai.core.common.Text.norm(npc);
            npcIsGroup = n.matches(GROUP_WORDS)
                    || k != null && core.findNpc(k.id, npc) == null && Profession.parse(n.replaceAll("s$", "")) != null;
            if (!npcIsGroup) return r; // um nome só: SUMMON normal
            who = npc;
        } else if (who.isBlank()) return r;
        Map<String, String> p = new java.util.HashMap<>(r.params());
        if (npcIsGroup) p.remove("npc"); // com "who" explícito, "npc" (se for alguém de verdade) vai à frente
        p.put("who", who);
        p.put("to", r.param("x") != null && r.param("z") != null ? r.param("x").trim() + " " + r.param("z").trim() : "aqui");
        p.putIfAbsent("minutes", "5");
        return new ActionRequest(r.kingdomId(), r.actorId(), r.actorKind(), ActionType.MOVE, p, r.source());
    }

    /** Ordens presas à posição/mira do rei: quebrar, marcar, chamar. */
    static boolean needsKing(ActionType t) {
        return t == ActionType.JOB || t == ActionType.MARK || t == ActionType.SUMMON || t == ActionType.FOLLOW;
    }

    /** Ponto do marco: x y z explícitos, senão em cima do bloco que o rei mira, senão onde ele está. */
    public static com.kingdomsai.core.common.Pos markTarget(KingdomsCore core, ActionRequest r) {
        if (r.param("x") != null && r.param("y") != null && r.param("z") != null) {
            try {
                return new com.kingdomsai.core.common.Pos(Integer.parseInt(r.param("x").trim()), Integer.parseInt(r.param("y").trim()),
                        Integer.parseInt(r.param("z").trim()));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (r.actorKind() != ActionRequest.ActorKind.PLAYER) return null;
        KingdomsCore.Look look = core.playerLook(r.actorId());
        if (look != null && look.block() != null) return look.block().offset(0, 1, 0);
        return core.playerPos(r.actorId());
    }

    /** Dá para ficar de pé aqui? (chão firme, sem água/lava, 2 blocos livres). null = seguro ou mundo desconhecido. */
    public static String unsafeStanding(KingdomsCore core, com.kingdomsai.core.common.Pos p) {
        var port = core.physical();
        var ground = port.block(p.offset(0, -1, 0));
        if (ground == com.kingdomsai.core.port.PhysicalPort.BlockInfo.UNKNOWN) return null;
        var feet = port.block(p);
        var head = port.block(p.offset(0, 1, 0));
        if (ground.fluid() || feet.fluid()) return "é água/lava";
        if (ground.air()) return "não há chão firme embaixo";
        if (!feet.air() || !head.air()) return "não há espaço para ficar de pé (2 blocos livres)";
        return null;
    }

    /** Para onde o NPC vai: x/z explícitos ou a última posição conhecida do rei. */
    public static com.kingdomsai.core.common.Pos summonTarget(KingdomsCore core, ActionRequest r) {
        if (r.param("x") != null && r.param("z") != null) {
            try {
                int x = Integer.parseInt(r.param("x").trim()), z = Integer.parseInt(r.param("z").trim());
                int y = r.param("y") == null ? core.world().surfaceY(x, z) : Integer.parseInt(r.param("y").trim());
                return new com.kingdomsai.core.common.Pos(x, y == Integer.MIN_VALUE ? 64 : y, z);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return r.actorKind() == ActionRequest.ActorKind.PLAYER ? core.playerPos(r.actorId()) : null;
    }

    public static com.kingdomsai.core.work.WorkChain findChain(KingdomsCore core, Kingdom k, ActionRequest r) {
        String ref = r.param("chain") != null ? r.param("chain") : r.param("npc");
        var c = core.work().find(k.id, ref);
        return c != null && c.live() ? c : null;
    }

    /** Há recursos? A IA nunca inventa recursos. */
    public static final Validator RESOURCES = (r, core) -> {
        Kingdom k = core.kingdom(r.kingdomId());
        Map<ResourceType, Integer> cost = cost(r, core);
        if (!k.has(cost)) {
            StringBuilder sb = new StringBuilder();
            for (var e : cost.entrySet())
                if (k.get(e.getKey()) < e.getValue())
                    sb.append(e.getKey().display).append(' ').append((int) k.get(e.getKey())).append('/').append(e.getValue()).append(", ");
            return ActionResult.reject("insufficient_resources", "Recursos insuficientes: " + sb.substring(0, sb.length() - 2) + ".");
        }
        return null;
    };

    public static Map<ResourceType, Integer> cost(ActionRequest r, KingdomsCore core) {
        Map<ResourceType, Integer> c = new EnumMap<>(ResourceType.class);
        int amount = r.intParam("amount", 1);
        switch (r.type()) {
            case BUILD -> {
                if (isWatchtowers(r)) {
                    c.put(ResourceType.STONE, 40 * Math.max(1, r.intParam("amount", 4)));
                    break;
                }
                if (isProgram(r)) {
                    var res = com.kingdomsai.core.construction.program.ProgramCompiler.compile(
                            com.kingdomsai.core.construction.program.DefaultPrograms.forText(r.param("text") == null ? r.param("kind") : r.param("text")));
                    if (res.ok()) c.putAll(res.blueprint().cost());
                    break;
                }
                if (com.kingdomsai.core.construction.VillageWall.isWallRequest(r.param("blueprint"))) {
                    Blueprint wall = com.kingdomsai.core.construction.VillageWall.generate(core, core.kingdom(r.kingdomId()), r.intParam("height", 4));
                    c.putAll(wall.cost());
                    break;
                }
                Blueprint bp = isCustom(r)
                        ? com.kingdomsai.core.construction.ParametricBlueprints.generate(com.kingdomsai.core.construction.ParametricBlueprints.spec(r.params()))
                        : BlueprintLibrary.find(r.param("blueprint"));
                for (var e : bp.cost().entrySet()) c.put(e.getKey(), e.getValue() * amount);
            }
            // RECRUIT e CLAIM não custam ouro: o exército come (EconomySystem) e a terra livre tem limite (MilitarySystem.claimLimit).
            case GIVE -> c.put(ResourceType.parse(r.param("resource")), r.intParam("amount", 0));
            case NEGOTIATE -> {
                ResourceType give = ResourceType.parse(r.param("give"));
                if (give != null && r.param("treaty") == null) c.put(give, r.intParam("give_amount", 0));
            }
            default -> {
            }
        }
        return c;
    }
}
