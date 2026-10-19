package com.kingdomsai.core.action;

import com.kingdomsai.core.KingdomsCore;
import com.kingdomsai.core.common.Text;
import com.kingdomsai.core.construction.Blueprint;
import com.kingdomsai.core.construction.BlueprintLibrary;
import com.kingdomsai.core.construction.Building;
import com.kingdomsai.core.diplomacy.Diplomacy;
import com.kingdomsai.core.event.EventType;
import com.kingdomsai.core.event.GameEvent;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.kingdom.ResourceType;
import com.kingdomsai.core.npc.*;

import java.util.*;

/**
 * "A IA decide o que quer fazer. O jogo decide se pode fazer. O Action System decide como fazer."
 */
public final class ActionSystem {
    private final KingdomsCore core;
    private final List<Validators.Validator> pipeline = List.of(
            Validators.SCHEMA, Validators.PERMISSION, Validators.WORLD, Validators.RESOURCES);

    /** Ordem irreversível esperando o "confirmo" do rei (60 s). Nunca vem pronta da IA: só o rei confirma. */
    public record Pending(ActionRequest request, long expiresTick, String summary) {}

    private final Map<UUID, Pending> pending = new HashMap<>();

    public ActionSystem(KingdomsCore core) {
        this.core = core;
    }

    /** Massacre/execução e ataque "sem piedade" pedem confirmação explícita do rei. */
    public static boolean needsConfirmation(ActionRequest r) {
        return r.type() == ActionType.PURGE
                || (r.type() == ActionType.ATTACK || r.type() == ActionType.OCCUPY) && "true".equalsIgnoreCase(r.param("no_quarter"));
    }

    public Pending pending(UUID actor) {
        Pending p = actor == null ? null : pending.get(actor);
        if (p != null && core.tick() > p.expiresTick()) {
            pending.remove(actor);
            return null;
        }
        return p;
    }

    /** "Confirmo": executa a ordem guardada, revalidando tudo (o mundo pode ter mudado nesses segundos). */
    public ActionResult confirm(UUID actor) {
        Pending p = pending(actor);
        if (p == null) return ActionResult.reject("nothing_pending", "Não há ordem esperando confirmação.");
        pending.remove(actor);
        return execute(p.request(), true);
    }

    public ActionResult abort(UUID actor) {
        Pending p = pending.remove(actor);
        if (p == null) return ActionResult.reject("nothing_pending", "Não há ordem esperando confirmação.");
        return ActionResult.ok("Ordem desfeita: " + p.request().type() + ". Ninguém foi ferido.");
    }

    public ActionResult validate(ActionRequest r) {
        for (Validators.Validator v : pipeline) {
            ActionResult rej = v.validate(r, core);
            if (rej != null) return rej;
        }
        return null;
    }

    public ActionResult execute(ActionRequest r) {
        return execute(r, false);
    }

    private ActionResult execute(ActionRequest asked, boolean confirmed) {
        ActionRequest r = Validators.groupCall(core, asked); // "chame todos" = chamado de grupo (MOVE), não SUMMON de um nome só
        ActionResult rej = validate(r);
        if (rej != null) {
            if (r.source() != ActionRequest.Source.DIRECTOR)
                core.bus().publish(core.tick(), EventType.ACTION_REJECTED, GameEvent.Severity.INFO, r.kingdomId(), r.actorId(),
                        r.type() + " rejeitada: " + rej.code(), Map.of("code", rej.code(), "source", r.source().name()));
            return rej;
        }
        Kingdom k = core.kingdom(r.kingdomId());
        if (needsConfirmation(r) && !confirmed) {
            String summary = confirmationSummary(k, r);
            pending.put(r.actorId(), new Pending(r, core.tick() + 20L * 60, summary));
            return ActionResult.reject("needs_confirmation", summary
                    + " Para cumprir, diga \"confirmo\" (ou /k confirmar, ou o botão Confirmar do Manager); para desistir, \"desisto\". Vale por 60 s.");
        }
        ActionResult res;
        try {
            res = switch (r.type()) {
                case BUILD -> build(k, r);
                case DEADLINE -> ActionResult.ok(core.construction().setDeadline(findProject(core, k, r.param("building")),
                        com.kingdomsai.core.construction.ConstructionSystem.parseDuration(r.param("deadline"))));
                case CANCEL_BUILD -> ActionResult.ok(core.construction().cancel(findProject(core, k, r.param("building"))));
                case RECRUIT -> recruit(k, r);
                case RELEASE -> release(k, r.intParam("amount", 1), r.param("profession"));
                case WORK -> work(k, r);
                case PROMOTE -> promote(k, core.findNpc(k.id, r.param("npc")), Office.parse(r.param("office")));
                case DEMOTE -> demote(k, core.findNpc(k.id, r.param("npc")));
                case PATROL, GUARD -> setProfession(k, core.findNpc(k.id, r.param("npc")), Profession.GUARD);
                case TAX -> tax(k, r.param("level"));
                case LAW -> law(k, r.param("law"), r.param("value"));
                case CLAIM -> claim(k, r.intParam("amount", 1));
                case NEGOTIATE -> negotiate(k, r);
                case GIVE -> give(k, r);
                case DECLARE_WAR -> ActionResult.ok(core.diplomacy().declareWar(k, core.findKingdom(r.param("target"))));
                case MAKE_PEACE -> {
                    String why = core.diplomacy().proposePeace(k, core.findKingdom(r.param("target")));
                    yield why == null ? ActionResult.ok("Paz aceita.") : ActionResult.reject("refused", why);
                }
                case TALK -> ActionResult.ok("");
                case CHAIN -> chain(k, r);
                case JOB -> job(k, r);
                case MARK -> mark(k, r);
                case CANCEL_JOB -> ActionResult.ok(core.skills().cancel(core.skills().find(k.id, r.param("job") != null ? r.param("job") : r.param("npc")), "ordem do rei"));
                case SUMMON, FOLLOW -> summon(k, r);
                case DISMISS -> {
                    Npc n = core.findNpc(k.id, r.param("npc"));
                    core.scheduler().dismiss(n);
                    yield ActionResult.ok(n.name + " foi dispensado e voltou à rotina.");
                }
                case STOP_CHAIN -> ActionResult.ok(core.work().stop(Validators.findChain(core, k, r), "ordem do rei"));
                case ATTACK, OCCUPY -> attack(k, r);
                case TRAIN -> train(k, r);
                case GOAL -> {
                    List<String> steps = core.director().pursue(k, com.kingdomsai.core.ai.KingdomDirector.goalOf(r.param("goal")), r.actorId());
                    yield ActionResult.ok("O conselho cuidou de " + r.param("goal") + ":\n   " + String.join("\n   ", steps));
                }
                case DECREE -> {
                    UUID aid = null;
                    try { aid = r.param("author_id") == null ? null : UUID.fromString(r.param("author_id")); } catch (IllegalArgumentException ignored) { }
                    yield core.civic().enact(k, r.param("text"), r.param("effect"), r.param("author"), aid, r.intParam("petition", 0), r.param("rule"));
                }
                case REVOKE -> core.civic().revoke(k, Integer.parseInt(r.param("decree").replace("#", "").trim()));
                case PETITION -> core.civic().decide(k, Integer.parseInt(r.param("petition").replace("#", "").trim()),
                        r.param("verdict").trim().toLowerCase().startsWith("a"), null);
                case MOVE -> move(k, r);
                case RETREAT -> ActionResult.ok(core.warfare().retreat(core.warfare().find(k.id, r.param("campaign"))));
                case SETTLE -> settle(k, r);
                case PURGE -> {
                    String msg = core.warfare().purge(k, Validators.purgeVictims(core, k, r));
                    yield msg.startsWith("MOTIM") ? ActionResult.reject("mutiny", msg) : ActionResult.ok(msg);
                }
                case ENSLAVE -> {
                    String msg = core.warfare().enslave(k, Validators.enslaveVictims(core, k, r), Validators.forcedWork(core, k, r));
                    yield msg.startsWith("MOTIM") ? ActionResult.reject("mutiny", msg) : ActionResult.ok(msg);
                }
                case FREE -> ActionResult.ok(core.warfare().free(k, Validators.freeTargets(core, k, r),
                        "true".equalsIgnoreCase(r.param("home"))));
                default -> ActionResult.reject("not_available_in_this_phase", r.type() + " ainda não foi implementada.");
            };
        } catch (RuntimeException ex) {
            res = ActionResult.reject("execution_error", "Falha ao executar " + r.type() + ": " + ex.getMessage());
        }
        if (res.ok() && r.actorKind() == ActionRequest.ActorKind.PLAYER && r.type() != ActionType.TALK)
            core.bus().publish(core.tick(), EventType.PLAYER_ORDER, GameEvent.Severity.INFO, k.id, r.actorId(),
                    "Ordem real: " + r.type() + " — " + res.message(), Map.of("source", r.source().name()));
        return res;
    }

    // ----------------------------------------------------------------- handlers

    /** Obra por referência: null = a mais recente; número = posição em /k projects; texto = nome/planta. */
    public static Building findProject(KingdomsCore core, Kingdom k, String ref) {
        List<Building> ps = core.construction().projects(k.id);
        if (ps.isEmpty()) return null;
        if (ref == null || ref.isBlank()) return ps.stream().max(Comparator.comparingLong(b -> b.startedTick)).orElse(null);
        String n = Text.norm(ref).replace("#", "");
        if (n.matches("\\d+")) {
            int i = Integer.parseInt(n) - 1;
            return i >= 0 && i < ps.size() ? ps.get(i) : null;
        }
        for (Building b : ps) {
            if (b.blueprintId.equals(n) || Text.norm(b.blueprint().displayName()).contains(n)) return b;
            Blueprint found = BlueprintLibrary.find(n);
            if (found != null && found.id().equals(b.blueprintId)) return b;
        }
        return null;
    }

    private ActionResult build(Kingdom k, ActionRequest r) {
        if (com.kingdomsai.core.construction.VillageWall.isWallRequest(r.param("blueprint"))) return buildWall(k, r);
        var spec = Validators.isCustom(r) ? com.kingdomsai.core.construction.ParametricBlueprints.spec(r.params()) : null;
        Blueprint bp = spec != null ? core.registerSpec(spec) : BlueprintLibrary.find(r.param("blueprint"));
        List<String> adjusted = spec == null ? List.of() : com.kingdomsai.core.construction.ParametricBlueprints.adjustments(r.params(), spec);
        Npc builder = r.param("builder") == null ? null : core.findNpc(k.id, r.param("builder"));
        long deadline = r.param("deadline") == null ? -1 : com.kingdomsai.core.construction.ConstructionSystem.parseDuration(r.param("deadline"));
        List<Building> planned = new ArrayList<>();
        int amount = r.intParam("amount", 1);
        Npc forNpc = r.param("for") == null ? null : core.findNpc(k.id, r.param("for"));
        int started = 0;
        for (int i = 0; i < amount; i++) {
            Building b = core.construction().plan(k, bp, forNpc == null ? null : forNpc.id);
            if (b == null) break;
            k.pay(bp.cost());
            if (builder != null && builder.profession == Profession.BUILDER) { // quem recebeu a ordem é quem vai à obra
                b.builderIds.remove(builder.id);
                b.builderIds.add(0, builder.id);
                b.builderId = builder.id;
                builder.remember(core.tick(), "O rei me mandou construir " + bp.displayName() + ".", 45, null, "obra");
            }
            planned.add(b);
            started++;
        }
        if (started == 0) return ActionResult.reject("no_site", "Não encontrei terreno livre e plano dentro do território para " + bp.displayName() + ". Expanda o território (CLAIM).");
        boolean hasBuilder = core.count(k.id, Profession.BUILDER) > 0;
        String msg = started + "x " + bp.displayName() + " planejada(s)" + (forNpc != null ? " para " + forNpc.name : "")
                + (builder != null && builder.profession == Profession.BUILDER ? " — " + builder.name + " vai construir" : "")
                + (spec != null ? " [" + bp.sizeX() + "×" + bp.sizeZ() + ", " + spec.floors() + " andar(es), custo " + costText(bp.cost()) + "]" : "")
                + (started < amount ? " (só havia terreno para " + started + ")" : "") + "."
                + (adjusted.isEmpty() ? "" : " Ajustei: " + String.join("; ", adjusted) + ".");
        if (!hasBuilder) msg += " Atenção: não há construtores — use /k assign construtor 1.";
        if (deadline > 0) for (Building b : planned) msg += " " + core.construction().setDeadline(b, deadline);
        else if (hasBuilder && !planned.isEmpty()) msg += " ETA " + core.construction().formatEta(planned.get(0)) + ".";
        return ActionResult.ok(msg);
    }

    private static String costText(Map<ResourceType, Integer> cost) {
        List<String> parts = new ArrayList<>();
        for (var e : cost.entrySet()) parts.add(e.getValue() + " " + e.getKey().display.toLowerCase());
        return String.join(", ", parts);
    }

    private ActionResult mark(Kingdom k, ActionRequest r) {
        var m = com.kingdomsai.core.kingdom.Marker.parse(r.param("kind"));
        if ("true".equals(r.param("remove"))) {
            boolean had = k.markers.remove(m) != null;
            return ActionResult.ok(had ? m.display + " desmarcado: os súditos voltam ao padrão." : m.display + " não estava marcado.");
        }
        com.kingdomsai.core.common.Pos p = Validators.markTarget(core, r);
        k.markers.put(m, p);
        core.bus().publish(core.tick(), EventType.MARKER_SET, GameEvent.Severity.INFO, k.id, r.actorId(),
                m.display + " marcado em " + p + ".", Map.of("kind", m.name(), "x", String.valueOf(p.x()), "y", String.valueOf(p.y()), "z", String.valueOf(p.z())));
        String effect = switch (m) {
            case SPAWN -> "Novos moradores chegam aqui e Vossa Majestade renasce aqui.";
            case GATHER -> "Os súditos se reúnem aqui no fim da tarde.";
            case MINE -> "Mineradores (rotina e cadeias) trabalham aqui.";
            case FOREST -> "Lenhadores (rotina e cadeias) trabalham aqui.";
        };
        return ActionResult.ok(m.display + " marcado em " + p + ". " + effect);
    }

    /** Ordem física já aprovada: começa e mostra o plano (incluindo as tarefas que o planejador acrescentou). */
    private ActionResult job(Kingdom k, ActionRequest r) {
        var plan = com.kingdomsai.core.skill.JobPlanner.plan(core, k, r.actorId(), r.params());
        var j = core.skills().start(k, plan.job(), r.param("order"));
        Npc n = core.npc(j.npcId);
        StringBuilder sb = new StringBuilder("Ordem #" + j.number + " para " + n.name + ":");
        for (String line : plan.lines()) sb.append("\n   ").append(line);
        for (String w : plan.warnings()) sb.append("\n⚠ ").append(w);
        return ActionResult.ok(sb.toString());
    }

    private ActionResult summon(Kingdom k, ActionRequest r) {
        Npc n = core.findNpc(k.id, r.param("npc"));
        com.kingdomsai.core.common.Pos to = Validators.summonTarget(core, r);
        boolean follow = r.type() == ActionType.FOLLOW;
        int seconds = follow ? 60 * Math.max(1, Math.min(30, r.intParam("minutes", 3))) : com.kingdomsai.core.ai.NpcScheduler.SUMMON_SECONDS;
        core.scheduler().summon(n, r.actorId(), to, follow, seconds);
        int dist = n.pos == null ? 0 : (int) n.pos.distXZ(to);
        core.bus().publish(core.tick(), EventType.NPC_SUMMONED, GameEvent.Severity.INFO, k.id, n.id,
                n.name + (follow ? " vai acompanhar o rei." : " foi chamado pelo rei."));
        if (follow) return ActionResult.ok(n.name + " vai acompanhar Vossa Majestade por " + seconds / 60 + " min" + (dist > 6 ? " (está a " + dist + " blocos)." : "."));
        return ActionResult.ok(n.name + (dist <= 4 ? " já está aqui." : " está vindo (" + dist + " blocos, ~" + Math.max(1, dist / 4) + " s)."));
    }

    /** Muralha sob medida: o jogo mede a vila agora e cerca tudo o que já foi construído. */
    private ActionResult buildWall(Kingdom k, ActionRequest r) {
        var bounds = com.kingdomsai.core.construction.VillageWall.bounds(core, k);
        Blueprint wall = com.kingdomsai.core.construction.VillageWall.generate(core, k, r.intParam("height", 4));
        Building b = core.construction().planWall(k, wall);
        if (b == null) return ActionResult.reject("no_site", "O traçado da muralha cruza uma obra em andamento. Termine-a ou cancele antes.");
        core.registerSaved(wall);
        k.pay(wall.cost());
        long deadline = r.param("deadline") == null ? -1 : com.kingdomsai.core.construction.ConstructionSystem.parseDuration(r.param("deadline"));
        StringBuilder msg = new StringBuilder(wall.displayName() + " planejada ao redor de tudo o que já existe: x " + bounds.x0() + "…" + bounds.x1()
                + ", z " + bounds.z0() + "…" + bounds.z1() + " (" + bounds.perimeter() + " blocos de contorno, 2 portões, "
                + wall.cost().getOrDefault(ResourceType.STONE, 0) + " de pedra).");
        int outside = 0;
        for (int[] c : new int[][]{{bounds.x0(), bounds.z0()}, {bounds.x1(), bounds.z0()}, {bounds.x0(), bounds.z1()}, {bounds.x1(), bounds.z1()}})
            if (!k.id.equals(core.state().territory.ownerAt(new com.kingdomsai.core.common.Pos(c[0], 0, c[1])))) outside++;
        if (outside > 0) msg.append(" ⚠ Parte do traçado sai do território (").append(outside).append(" canto(s)) — reivindique mais terras (CLAIM).");
        if (core.count(k.id, Profession.BUILDER) == 0) msg.append(" Atenção: não há construtores.");
        else if (deadline > 0) msg.append(' ').append(core.construction().setDeadline(b, deadline));
        else msg.append(" ETA ").append(core.construction().formatEta(b)).append('.');
        return ActionResult.ok(msg.toString());
    }

    /** Cadeia já aprovada pelos validadores: cria e explica o plano (com os avisos). */
    private ActionResult chain(Kingdom k, ActionRequest r) {
        var spec = com.kingdomsai.core.work.ChainTemplates.spec(r.params(), core, k);
        var v = com.kingdomsai.core.work.ChainValidator.validate(core, k, spec);
        var c = core.work().start(k, v.chain(), r.param("order") != null ? r.param("order") : spec.name);
        StringBuilder sb = new StringBuilder("Cadeia #" + c.number + " «" + c.name + "» " + (c.repeat ? "(rotina contínua)" : "(tarefa única)") + ":");
        for (String line : v.plan()) sb.append("\n   ").append(line);
        for (String w : v.warnings()) sb.append("\n⚠ ").append(w);
        return ActionResult.ok(sb.toString());
    }

    /**
     * Convocação sem ouro: o exército custa comida. Sem número, quem decide é o general/capitão (ou o soldado mais experiente),
     * olhando a maior ameaça; ele também escolhe quem vai (camponeses primeiro, depois os mais corajosos).
     */
    private ActionResult recruit(Kingdom k, ActionRequest r) {
        Npc decider = core.warfare().commander(k, r.param("npc"));
        boolean decided = r.param("amount") == null || r.param("amount").isBlank();
        int amount = decided ? (decider == null ? 1 : core.warfare().recruitDecision(k)) : r.intParam("amount", 1);
        List<Npc> drafted = draft(k, amount);
        List<String> names = drafted.stream().map(n -> n.name).toList();
        core.bus().publish(core.tick(), EventType.ARMY_RECRUITED, GameEvent.Severity.INFO, k.id, null,
                names.size() + " recrutado(s) em " + k.name + ": " + String.join(", ", names) + ".");
        double food = core.military(k.id) * (com.kingdomsai.core.military.MilitarySystem.SOLDIER_FOOD - 1);
        long armed = drafted.stream().filter(n -> !n.equipped.isEmpty()).count();
        String muster = muster(k, decider, drafted);
        return ActionResult.ok((decided && decider != null ? decider.displayName() + " decidiu convocar " + names.size() + ". " : "")
                + "Recrutados: " + String.join(", ", names) + (armed < drafted.size() ? " (" + (drafted.size() - armed)
                + " sem espada: o arsenal está vazio — o ferreiro precisa forjar)" : " (espadas do arsenal)") + ". " + muster
                + " O exército não custa ouro, custa comida: ~" + Text.fmt(food) + " a mais por ciclo para " + core.military(k.id) + " militares.");
    }

    /** Convoca: camponeses primeiro, depois os mais corajosos; cada um pega uma espada do arsenal, se houver. */
    private List<Npc> draft(Kingdom k, int amount) {
        List<Npc> civ = new ArrayList<>();
        for (Npc n : core.citizens(k.id))
            if (!n.profession.isMilitary() && n.office == Office.NONE && n.isFree() && !core.warfare().atWar(n)) civ.add(n);
        // quem convoca é competente: não leva o último lenhador/minerador/fazendeiro (a economia pararia)
        civ.sort(Comparator.comparingInt((Npc n) -> expendability(k, n) + (isLastOf(k, n) ? 100 : 0)
                        + (n.profession != Profession.PEASANT && core.count(k.id, n.profession) <= 1 ? 50 : 0))
                .thenComparing(n -> -n.trait(Trait.COURAGE)));
        List<Npc> out = new ArrayList<>();
        for (Npc n : civ) {
            if (out.size() >= amount) break;
            if (isLastOf(k, n) && civ.size() - civ.indexOf(n) > amount - out.size()) continue; // reavalia: nunca o último construtor/ferreiro se houver outro
            Profession before = n.profession;
            n.profession = Profession.SOLDIER;
            n.remember(core.tick(), "Fui recrutado para o exército (antes era " + before.display.toLowerCase() + ").", 55, null, "exército");
            if (k.get(ResourceType.WEAPONS) >= 1) {
                k.add(ResourceType.WEAPONS, -1);
                n.equipped = "iron_sword";
            }
            com.kingdomsai.core.skill.Kit.deposit(k, n);  // ferramentas do ofício antigo voltam ao armazém
            com.kingdomsai.core.skill.Kit.resupply(k, n); // escudo, armadura e suprimentos do arsenal
            out.add(n);
        }
        return out;
    }

    /** Os convocados se apresentam: andam até quem os chamou (ou o quartel/praça) e ficam 1 minuto em forma. */
    private String muster(Kingdom k, Npc decider, List<Npc> drafted) {
        if (drafted.isEmpty()) return "";
        Npc lead = decider != null && decider.isFree() && !core.warfare().atWar(decider) && decider.office != Office.KING ? decider : null;
        Building barracks = null;
        for (Building b : core.buildings(k.id)) if (b.isComplete() && b.blueprintId.equals("barracks") && b.origin.y() != Integer.MIN_VALUE) barracks = b;
        com.kingdomsai.core.common.Pos at = barracks != null ? barracks.entrance()
                : lead != null && lead.pos != null ? lead.pos : k.marker(com.kingdomsai.core.kingdom.Marker.GATHER, k.center);
        core.warfare().assemble(k, com.kingdomsai.core.military.Campaign.Kind.MOVE, lead, drafted, at, 60, "apresentar-se");
        return "Eles vão se apresentar" + (lead != null ? " a " + lead.name : "") + (barracks != null ? " no quartel." : lead != null ? "." : " na praça.");
    }

    /** Treino: o instrutor escolhe quem treina (e convoca se faltar gente), leva ao campo e treinam em formação. */
    private ActionResult train(Kingdom k, ActionRequest r) {
        var war = core.warfare();
        Npc inst = Validators.trainer(core, k, r);
        boolean guards = "true".equalsIgnoreCase(r.param("guards"));
        List<Npc> troop = new ArrayList<>(war.available(k, guards));
        troop.remove(inst);
        Integer amount = r.param("amount") == null || r.param("amount").isBlank() ? null : r.intParam("amount", 1);
        List<Npc> drafted = List.of();
        if (amount != null && troop.size() > amount) troop = new ArrayList<>(troop.subList(0, amount));
        int want = amount != null ? amount : Math.max(troop.size(), 3);
        if (troop.size() < want) {
            drafted = draft(k, want - troop.size());
            troop.addAll(drafted);
        }
        if (troop.isEmpty()) return ActionResult.reject("not_enough_people", "Não há soldados nem civis livres para treinar.");
        Validators.Target t = Validators.place(core, k, r, r.param("where") == null ? "campo" : r.param("where"));
        int minutes = Math.max(1, Math.min(20, r.intParam("minutes", 3)));
        var c = war.assemble(k, com.kingdomsai.core.military.Campaign.Kind.TRAIN, inst, troop, t.pos(), minutes * 60, r.param("order"));
        if (!drafted.isEmpty())
            core.bus().publish(core.tick(), EventType.ARMY_RECRUITED, GameEvent.Severity.INFO, k.id, inst.id,
                    inst.name + " convocou " + drafted.size() + " para treinar: " + com.kingdomsai.core.military.MilitarySystem.names(drafted) + ".");
        return ActionResult.ok("Treino #" + c.number + ": " + inst.displayName() + " escolheu " + com.kingdomsai.core.military.MilitarySystem.names(troop)
                + (drafted.isEmpty() ? "" : " (convocou " + com.kingdomsai.core.military.MilitarySystem.names(drafted) + ")")
                + " e leva todos para (" + t.pos().x() + ", " + t.pos().z() + "), chegam em ~" + (c.arriveTick - core.tick()) / 20
                + " s; treinam " + minutes + " min em formação (disciplina e coragem sobem).");
    }

    /** "Vão para a praça", "reúna a tropa no quartel", "fiquem aqui": vão de verdade e esperam lá. */
    private ActionResult move(Kingdom k, ActionRequest r) {
        Validators.Target t = Validators.place(core, k, r, r.param("to"));
        List<Npc> who = Validators.movers(core, k, r);
        Npc lead = r.param("npc") == null ? null : core.findNpc(k.id, r.param("npc"));
        String w = Text.norm(r.param("minutes") == null ? "" : r.param("minutes"));
        int minutes = w.matches(".*(ate eu mandar|sempre|indefinid).*") ? 20 : Math.max(1, Math.min(20, r.intParam("minutes", 3)));
        List<Npc> rest = new ArrayList<>(who);
        rest.remove(lead);
        var c = core.warfare().assemble(k, com.kingdomsai.core.military.Campaign.Kind.MOVE, lead, rest, t.pos(), minutes * 60, r.param("order"));
        return ActionResult.ok(com.kingdomsai.core.military.MilitarySystem.names(who) + (who.size() == 1 ? " vai" : " vão") + " para ("
                + t.pos().x() + ", " + t.pos().z() + ")" + (lead != null && who.size() > 1 ? " com " + lead.name + " à frente" : "")
                + ", chegam em ~" + (c.arriveTick - core.tick()) / 20 + " s e ficam " + minutes + " min.");
    }

    private ActionResult release(Kingdom k, int amount, String professionName) {
        Profession to = professionName == null ? Profession.FARMER : Profession.parse(professionName);
        List<Npc> soldiers = new ArrayList<>();
        for (Npc n : core.citizens(k.id)) if (n.profession == Profession.SOLDIER) soldiers.add(n);
        soldiers.sort(Comparator.comparingInt(n -> n.fame));
        List<String> names = new ArrayList<>();
        for (int i = 0; i < amount && i < soldiers.size(); i++) {
            Npc n = soldiers.get(i);
            n.profession = to;
            if (!n.equipped.isEmpty()) { // devolve a espada ao arsenal
                k.add(ResourceType.WEAPONS, 1);
                n.equipped = "";
            }
            core.warfare().leaveDrill(n);
            n.remember(core.tick(), "Fui liberado do exército para trabalhar como " + to.display.toLowerCase() + ".", 40, null, "exército");
            names.add(n.name);
        }
        core.bus().publish(core.tick(), EventType.ARMY_RELEASED, GameEvent.Severity.INFO, k.id, null,
                names.size() + " soldado(s) liberado(s) para " + to.display + ".");
        return ActionResult.ok(String.join(", ", names) + " agora trabalham como " + to.display.toLowerCase() + ".");
    }

    private ActionResult work(Kingdom k, ActionRequest r) {
        Profession to = Profession.parse(r.param("profession"));
        if (r.param("npc") != null) return setProfession(k, core.findNpc(k.id, r.param("npc")), to);
        int amount = r.intParam("amount", 1);
        Profession from = r.param("from") == null ? null : Profession.parse(r.param("from"));
        List<Npc> pool = new ArrayList<>();
        for (Npc n : core.citizens(k.id))
            if (n.profession != to && n.office == Office.NONE && n.freedom != Freedom.CAPTIVE && !core.warfare().atWar(n)
                    && (from == null ? !n.profession.isMilitary() : n.profession == from)) pool.add(n);
        if (pool.isEmpty())
            return ActionResult.reject("not_enough_people", "Ninguém disponível" + (from != null ? " entre " + from.display.toLowerCase() + "s" : "") + ".");
        // Quem sai primeiro: camponeses; quem fica por último: o último construtor/ferreiro do reino.
        pool.sort(Comparator.comparingInt((Npc n) -> expendability(k, n)).thenComparingInt(n -> n.fame));
        if (from == null) pool.removeIf(n -> isLastOf(k, n));
        if (pool.isEmpty()) return ActionResult.reject("not_enough_people", "Só restam pessoas em funções essenciais (último construtor/ferreiro).");
        List<String> names = new ArrayList<>();
        for (Npc n : pool) {
            if (names.size() >= amount) break;
            if (from == null && isLastOf(k, n)) continue; // reavalia a cada troca: nunca leva o último construtor/ferreiro
            setProfession(k, n, to);
            names.add(n.name);
        }
        if (names.isEmpty()) return ActionResult.reject("not_enough_people", "Só restam pessoas em funções essenciais (último construtor/ferreiro).");
        return ActionResult.ok(String.join(", ", names) + " agora trabalha(m) como " + to.display.toLowerCase() + ".");
    }

    private static final List<Profession> EXPENDABLE_ORDER = List.of(Profession.PEASANT, Profession.SCHOLAR, Profession.MERCHANT,
            Profession.LUMBERJACK, Profession.MINER, Profession.PRIEST, Profession.FARMER, Profession.GUARD, Profession.SOLDIER,
            Profession.BLACKSMITH, Profession.BUILDER);

    private int expendability(Kingdom k, Npc n) {
        return EXPENDABLE_ORDER.indexOf(n.profession);
    }

    /** Último construtor ou ferreiro: remover trava o reino. */
    private boolean isLastOf(Kingdom k, Npc n) {
        return (n.profession == Profession.BUILDER || n.profession == Profession.BLACKSMITH) && core.count(k.id, n.profession) <= 1;
    }

    private ActionResult setProfession(Kingdom k, Npc n, Profession to) {
        Profession before = n.profession;
        if (before == to) return ActionResult.ok(n.name + " já é " + to.display.toLowerCase() + ".");
        n.profession = to;
        n.remember(core.tick(), "Passei a trabalhar como " + to.display.toLowerCase() + " (antes: " + before.display.toLowerCase() + ").", 35, null, "trabalho");
        core.bus().publish(core.tick(), EventType.NPC_PROFESSION_CHANGED, GameEvent.Severity.INFO, k.id, n.id,
                n.name + ": " + before.display + " → " + to.display + ".");
        // troca de ofício = troca de kit: devolve as ferramentas do antigo e pega as do novo no armazém
        com.kingdomsai.core.skill.Kit.deposit(k, n);
        var trip = com.kingdomsai.core.skill.Kit.resupply(k, n);
        core.treasury().sync(k);
        return ActionResult.ok(n.name + " agora é " + to.display.toLowerCase() + "."
                + (trip.nothing() ? "" : " Pegou no armazém: " + com.kingdomsai.core.skill.SkillSystem.summary(trip.moved()) + ".")
                + (trip.lacking().isEmpty() ? "" : " ⚠ Falta no armazém: " + String.join(", ", trip.lacking()) + " — o ferreiro precisa fazer."));
    }

    private ActionResult promote(Kingdom k, Npc n, Office office) {
        for (Npc other : core.citizens(k.id))
            if (other.office == office && office != Office.NONE && other != n) {
                other.office = Office.NONE;
                other.remember(core.tick(), "Perdi o cargo de " + office.display + " para " + n.name + ".", 80, n.id, "cargo", "humilhação");
                other.relationTo(n.id).adjust(-15, -5, 0, 30, -20);
                other.loyalty = Math.max(0, other.loyalty - 15);
            }
        n.office = office;
        n.level = n.level.atLeast(IntelligenceLevel.IMPORTANT);
        n.fame += 10;
        n.loyalty = Math.min(100, n.loyalty + 10);
        n.remember(core.tick(), "O rei me nomeou " + office.display + ".", 90, null, "cargo", "honra");
        core.bus().publish(core.tick(), EventType.NPC_PROMOTED, GameEvent.Severity.GOOD, k.id, n.id,
                n.name + " foi nomeado(a) " + office.display + ".");
        core.chronicle(n.name + " foi nomeado(a) " + office.display + " de " + k.name + ".");
        return ActionResult.ok(n.name + " agora é " + office.display + ".");
    }

    private ActionResult demote(Kingdom k, Npc n) {
        if (n.office == Office.NONE) return ActionResult.ok(n.name + " não tem cargo.");
        Office before = n.office;
        n.office = Office.NONE;
        n.loyalty = Math.max(0, n.loyalty - 20);
        n.remember(core.tick(), "O rei me tirou do cargo de " + before.display + ".", 85, null, "cargo", "humilhação");
        core.bus().publish(core.tick(), EventType.NPC_PROMOTED, GameEvent.Severity.WARN, k.id, n.id,
                n.name + " perdeu o cargo de " + before.display + ".");
        return ActionResult.ok(n.name + " deixou de ser " + before.display + ".");
    }

    private ActionResult tax(Kingdom k, String level) {
        String v = level.trim().toLowerCase();
        int before = k.laws.taxLevel;
        int lvl = switch (v) {
            case "up", "+1", "aumentar" -> before + 1;
            case "down", "-1", "baixar", "reduzir" -> before - 1;
            default -> Integer.parseInt(v);
        };
        k.laws.taxLevel = Text.clamp(lvl, 0, 4);
        if (k.laws.taxLevel > before) k.legitimacy = Text.clamp(k.legitimacy - 3, 0, 100);
        String[] names = {"isento", "baixo", "normal", "alto", "extorsivo"};
        core.bus().publish(core.tick(), EventType.LAW_CHANGED, GameEvent.Severity.INFO, k.id, null,
                "Impostos: " + names[before] + " → " + names[k.laws.taxLevel] + ".");
        return ActionResult.ok("Impostos agora: " + names[k.laws.taxLevel] + ".");
    }

    private ActionResult law(Kingdom k, String law, String value) {
        boolean on = value != null && (value.equalsIgnoreCase("on") || value.equalsIgnoreCase("true") || value.equalsIgnoreCase("sim") || value.equals("1"));
        String l = law.toLowerCase();
        String msg;
        if (l.startsWith("consc") || l.startsWith("serv")) {
            k.laws.conscription = on;
            msg = "Serviço militar obrigatório " + (on ? "instituído" : "abolido") + ".";
        } else {
            k.laws.openMigration = on;
            msg = "Imigração " + (on ? "aberta" : "fechada") + ".";
        }
        core.bus().publish(core.tick(), EventType.LAW_CHANGED, GameEvent.Severity.INFO, k.id, null, msg);
        return ActionResult.ok(msg);
    }

    private ActionResult claim(Kingdom k, int amount) {
        int done = 0;
        for (int[] c : core.state().territory.claimableFrontier(k.id, k.center)) {
            if (done >= amount) break;
            core.state().territory.claim(c[0], c[1], k.id, 40, core.tick());
            done++;
        }
        core.bus().publish(core.tick(), EventType.TERRITORY_CLAIMED, GameEvent.Severity.INFO, k.id, null,
                k.name + " reivindicou " + done + " nova(s) região(ões).");
        return ActionResult.ok(done + " célula(s) reivindicada(s) (terra livre: não custa nada). Território: " + core.state().territory.countOwned(k.id)
                + "/" + core.warfare().claimLimit(k) + " células (" + String.format(Locale.ROOT, "%.2f", core.state().territory.areaKm2(k.id)) + " km²).");
    }

    private String confirmationSummary(Kingdom k, ActionRequest r) {
        if (r.type() == ActionType.PURGE) return "⚠ " + core.warfare().describePurge(k, Validators.purgeVictims(core, k, r));
        Validators.Target t = Validators.attackTarget(core, k, r);
        return "⚠ Ataque SEM PIEDADE contra " + (t.owner() == null ? "terra livre" : t.owner().name)
                + ": se a vila cair, ninguém será poupado (em vez de virar cativo).";
    }

    /** Ataque/ocupação: o comandante escolhe quem vai e calcula a força; ataque sem guerra declarada declara na hora, com desonra. */
    private ActionResult attack(Kingdom k, ActionRequest r) {
        var war = core.warfare();
        Validators.Target t = Validators.attackTarget(core, k, r);
        Npc cmd = war.commander(k, r.param("npc"));
        Integer amount = r.param("amount") == null || r.param("amount").isBlank() ? null : r.intParam("amount", 1);
        List<Npc> troops = war.chooseTroops(k, t.owner(), t.pos(), amount, "true".equalsIgnoreCase(r.param("guards")));
        if (cmd != null && cmd.isFree() && cmd.campaignId == null && cmd.office != Office.KING && !troops.contains(cmd)
                && (cmd.office == Office.GENERAL || cmd.office == Office.CAPTAIN)) troops.add(0, cmd); // quem comanda vai junto
        String surprise = "";
        if (t.owner() != null && core.diplomacy().link(k.id, t.owner().id).state != Diplomacy.State.WAR) {
            core.diplomacy().declareWar(k, t.owner());
            k.honor = Text.clamp(k.honor - 15, 0, 100);
            k.legitimacy = Text.clamp(k.legitimacy - 3, 0, 100);
            surprise = " ⚠ Ataque sem declaração: a guerra contra " + t.owner().name + " foi declarada agora (desonra: os vizinhos confiarão menos).";
        }
        double force = 0;
        for (Npc n : troops) force += war.power(n, k);
        double def = war.defenseAt(t.owner(), t.pos());
        var c = war.launch(k, com.kingdomsai.core.military.Campaign.Kind.ATTACK, cmd, troops, t.owner(), t.pos(),
                "true".equalsIgnoreCase(r.param("no_quarter")), !surprise.isEmpty(), r.param("order"));
        return ActionResult.ok("Tropa #" + c.number + (cmd != null ? " — " + cmd.displayName() + " escolheu: " : ": ")
                + com.kingdomsai.core.military.MilitarySystem.names(troops) + ". Marcha para " + (t.owner() == null ? "terra livre" : t.owner().name)
                + " (" + t.pos().x() + ", " + t.pos().z() + "), chega em ~" + (c.arriveTick - core.tick()) / 20 + " s. "
                + String.format(Locale.ROOT, "Força %.1f × defesa estimada %.1f.", force, def)
                + (force < def ? " ⚠ O comandante avisa: estamos em desvantagem." : "")
                + " Em campanha cada soldado come " + Text.fmt(com.kingdomsai.core.military.MilitarySystem.SOLDIER_FOOD
                + com.kingdomsai.core.military.MilitarySystem.CAMPAIGN_EXTRA_FOOD) + " por ciclo." + surprise);
    }

    /** Colonos: gente que vai morar lá e toma a terra — além do limite de reivindicação. */
    private ActionResult settle(Kingdom k, ActionRequest r) {
        Validators.Target t = Validators.attackTarget(core, k, r);
        List<Npc> settlers = Validators.settlers(core, k, r.intParam("amount", 3));
        var c = core.warfare().launch(k, com.kingdomsai.core.military.Campaign.Kind.SETTLE, null, settlers, t.owner(), t.pos(), false, false,
                r.param("order"));
        return ActionResult.ok("Colonos #" + c.number + ": " + com.kingdomsai.core.military.MilitarySystem.names(settlers) + " partem para "
                + (t.owner() == null ? "terra livre" : "terra de " + t.owner().name) + " (" + t.pos().x() + ", " + t.pos().z() + "), chegam em ~"
                + (c.arriveTick - core.tick()) / 20 + " s." + (t.owner() != null ? " ⚠ Se houver guardas de " + t.owner().name + " por lá, serão expulsos: mande tropas." : ""));
    }

    private ActionResult negotiate(Kingdom k, ActionRequest r) {
        Kingdom t = core.findKingdom(r.param("target"));
        if (r.param("treaty") != null) {
            Diplomacy.TreatyType type = Diplomacy.TreatyType.parse(r.param("treaty"));
            String why = core.diplomacy().proposeTreaty(k, t, type);
            return why == null ? ActionResult.ok(t.name + " aceitou: " + type.display + ".") : ActionResult.reject("refused", why);
        }
        ResourceType give = ResourceType.parse(r.param("give"));
        ResourceType want = ResourceType.parse(r.param("want"));
        int ga = r.intParam("give_amount", 0), wa = r.intParam("want_amount", 0);
        if (ga <= 0 || wa <= 0) return ActionResult.reject("invalid_param", "Quantidades devem ser positivas.");
        String why = core.diplomacy().trade(k, t, give, ga, want, wa);
        return why == null ? ActionResult.ok("Troca aceita por " + t.name + ".") : ActionResult.reject("refused", why);
    }

    private ActionResult give(Kingdom k, ActionRequest r) {
        Kingdom t = core.findKingdom(r.param("target"));
        ResourceType res = ResourceType.parse(r.param("resource"));
        int amount = r.intParam("amount", 0);
        core.diplomacy().gift(k, t, res, amount);
        return ActionResult.ok("Presente entregue a " + t.name + ".");
    }
}
