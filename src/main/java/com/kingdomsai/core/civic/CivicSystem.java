package com.kingdomsai.core.civic;

import com.kingdomsai.core.KingdomsCore;
import com.kingdomsai.core.action.ActionRequest;
import com.kingdomsai.core.action.ActionResult;
import com.kingdomsai.core.action.ActionType;
import com.kingdomsai.core.common.Text;
import com.kingdomsai.core.event.EventType;
import com.kingdomsai.core.event.GameEvent;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.kingdom.ResourceType;
import com.kingdomsai.core.life.Places;
import com.kingdomsai.core.npc.Freedom;
import com.kingdomsai.core.npc.Npc;
import com.kingdomsai.core.npc.Profession;
import com.kingdomsai.core.npc.Trait;

import java.util.*;

/**
 * Vontade própria: os súditos notam o que está errado no reino e PEDEM — petições que viram ações reais —
 * ou propõem decretos. O conselho decide sozinho (modo "council"), o rei decide (modo "king") ou nada
 * acontece (modo "off"). Tudo o que muda o reino passa por ActionSystem.execute; aqui só se decide e se registra.
 */
public final class CivicSystem {
    public static final int MAX_PENDING = 4;
    public static final long DELIBERATE_TICKS = 20L * 60;      // o conselho "pensa" 1 min
    public static final long COUNCIL_GAP_TICKS = 20L * 90;     // no máximo uma decisão a cada 90 s
    public static final long KING_WAIT_TICKS = 20L * 60 * 8;   // o rei demora demais: vira "ignorado"
    public static final long NPC_COOLDOWN_TICKS = 20L * 60 * 8;
    public static final long KIND_COOLDOWN_TICKS = 20L * 60 * 10;
    public static final int MAX_ACTIVE_DECREES = 12;

    private final KingdomsCore core;
    private final Map<UUID, Long> npcLast = new HashMap<>();
    private final Map<UUID, Long> councilLast = new HashMap<>();
    private final Map<UUID, Integer> cursor = new HashMap<>();

    public CivicSystem(KingdomsCore core) {
        this.core = core;
    }

    // ------------------------------------------------------------------ consulta

    public List<Petition> petitions(UUID kid) {
        List<Petition> out = new ArrayList<>();
        for (Petition p : core.state().petitions.values()) if (p.kingdomId.equals(kid)) out.add(p);
        out.sort(Comparator.comparingInt(p -> p.number));
        return out;
    }

    public List<Petition> pending(UUID kid) {
        return petitions(kid).stream().filter(p -> p.status == Petition.Status.PENDING).toList();
    }

    public Petition find(UUID kid, int number) {
        for (Petition p : petitions(kid)) if (p.number == number) return p;
        return null;
    }

    public List<Decree> decrees(Kingdom k) {
        return k.decrees;
    }

    public Decree decree(Kingdom k, int number) {
        for (Decree d : k.decrees) if (d.number == number) return d;
        return null;
    }

    public boolean has(Kingdom k, String effect) {
        if (k == null || k.decrees == null) return false;
        for (Decree d : k.decrees) if (d.active && d.effect.equals(effect)) return true;
        return false;
    }

    /** Linha para os prompts: as leis em vigor e o que está em votação. */
    public String lawsLine(Kingdom k) {
        if (k == null || k.decrees == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Decree d : k.decrees) if (d.active) sb.append(sb.length() == 0 ? "" : " | ").append("#").append(d.number).append(" ").append(d.text);
        return sb.length() == 0 ? "" : "Leis em vigor: " + sb;
    }

    // ------------------------------------------------------------------ tick

    public void tickSecond() {
        long sec = core.tick() / 20;
        for (Kingdom k : core.state().kingdoms.values()) {
            if (!k.isPlayerKingdom() || "off".equals(k.civic)) continue;
            expire(k);
            if (sec % 5 == 0) notice(k);
            if ("council".equals(k.civic) && sec % 3 == 0) council(k);
        }
    }

    private void expire(Kingdom k) {
        for (Petition p : pending(k.id)) {
            if (core.tick() - p.filedTick < KING_WAIT_TICKS) continue;
            p.status = Petition.Status.EXPIRED;
            p.decidedTick = core.tick();
            p.verdict = "ninguém respondeu";
            Npc a = core.npc(p.authorId);
            if (a != null && a.alive) {
                a.mood = Text.clamp(a.mood - 4, 0, 100);
                a.loyalty = (int) Text.clamp(a.loyalty - 1, 0, 100);
                a.remember(core.tick(), "Pedi ao reino (" + p.text + ") e ninguém me respondeu.", 4, null, "peticao");
            }
            event(EventType.PETITION_DECIDED, GameEvent.Severity.INFO, k, p.authorId,
                    "Petição #" + p.number + " ficou sem resposta: " + p.text);
        }
    }

    /** Um súdito por vez olha em volta e, se tiver motivo, abre uma petição. */
    private void notice(Kingdom k) {
        List<Npc> people = new ArrayList<>();
        for (Npc n : core.citizens(k.id)) if (n.alive && n.freedom == Freedom.FREE) people.add(n);
        if (people.isEmpty() || pending(k.id).size() >= MAX_PENDING) return;
        int i = cursor.merge(k.id, 1, Integer::sum) % people.size();
        Npc n = people.get(i);
        if (core.tick() - npcLast.getOrDefault(n.id, -NPC_COOLDOWN_TICKS) < NPC_COOLDOWN_TICKS) return;
        if (core.life().asleep(n, core.tick())) return;
        String kind = grievance(k, n);
        if (kind == null || blocked(k, kind)) return;
        npcLast.put(n.id, core.tick());
        file(k, n, kind);
    }

    private boolean blocked(Kingdom k, String kind) {
        for (Petition p : petitions(k.id)) {
            if (!p.kind.equals(kind)) continue;
            if (p.status == Petition.Status.PENDING) return true;
            if (core.tick() - p.decidedTick < KIND_COOLDOWN_TICKS && p.status != Petition.Status.APPROVED) return true;
            if (core.tick() - p.decidedTick < KIND_COOLDOWN_TICKS / 2) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ queixas

    /** Qual o maior incômodo deste súdito agora (null = está tudo bem)? Vale também para contar apoiadores. */
    public String grievance(Kingdom k, Npc n) {
        for (String kind : KINDS) if (feels(k, n, kind)) return kind;
        return null;
    }

    private static final String[] KINDS = {"FOOD", "HOUSING", "TAX_DOWN", "NO_CONSCRIPTION", "GUARDS", "PEACE", "CURFEW",
            "FEAST", "CHAPEL", "LIBRARY", "TAVERN"};

    public boolean feels(Kingdom k, Npc n, String kind) {
        int pop = Math.max(1, core.population(k.id));
        switch (kind) {
            case "FOOD":
                return n.hunger < 40 || k.get(ResourceType.FOOD) < pop * 2.0 && n.mood < 70;
            case "HOUSING":
                return n.homeId == null && pop > core.housingCapacity(k.id) && n.mood < 75;
            case "TAX_DOWN":
                return k.laws.taxLevel >= 3 && n.mood < 55;
            case "NO_CONSCRIPTION":
                return k.laws.conscription && n.trait(Trait.COURAGE) < 45 && n.mood < 60;
            case "GUARDS": {
                int guards = core.count(k.id, Profession.GUARD);
                return guards < Math.max(1, pop / 8) && n.fear > 20;
            }
            case "PEACE":
                return recentFights(n) >= 2 && n.trait(Trait.DISCIPLINE) > 45;
            case "CURFEW":
                return n.fear > 40 && n.mood < 55;
            case "FEAST":
                return k.get(ResourceType.FOOD) > pop * 8.0 && n.mood < 55 && k.morale < 60;
            case "CHAPEL":
                return n.trait(Trait.RELIGIOSITY) > 65 && pop >= 4 && Places.church(core, k) == null;
            case "LIBRARY":
                return n.trait(Trait.CURIOSITY) > 70 && n.literate && pop >= 4 && Places.library(core, k) == null;
            case "TAVERN":
                return n.trait(Trait.SOCIABILITY) > 70 && n.social < 40 && pop >= 5 && Places.tavern(core, k) == null;
            default:
                return false;
        }
    }

    private int recentFights(Npc n) {
        int c = 0;
        for (var m : n.memories) if (m.tags().contains("briga") && core.tick() - m.tick() < 24000L * 2) c++;
        return c;
    }

    private boolean projectExists(Kingdom k, String blueprint) {
        for (var b : core.construction().projects(k.id)) if (blueprint.equals(b.blueprintId)) return true;
        return false;
    }

    // ------------------------------------------------------------------ abrir petição

    public Petition file(Kingdom k, Npc n, String kind) {
        Petition p = new Petition();
        p.kind = kind;
        p.minSupport = switch (kind) {
            case "FOOD", "HOUSING" -> 1;
            default -> 2;
        };
        switch (kind) {
            case "FOOD" -> { p.text = "Garantir comida para todos"; p.reason = "passamos fome"; p.actionType = "GOAL"; p.params.put("goal", "comida"); }
            case "HOUSING" -> {
                if (projectExists(k, "house_small")) return null;
                p.text = "Construir uma casa"; p.reason = "tem gente sem teto"; p.actionType = "BUILD"; p.params.put("blueprint", "house_small");
            }
            case "TAX_DOWN" -> { p.text = "Baixar os impostos"; p.reason = "o povo não aguenta mais"; p.actionType = "TAX"; p.params.put("level", "down"); }
            case "NO_CONSCRIPTION" -> { p.text = "Acabar com o serviço militar obrigatório"; p.reason = "ninguém quer ser arrastado para a guerra"; p.actionType = "LAW"; p.params.put("law", "conscription"); p.params.put("value", "off"); }
            case "GUARDS" -> { p.text = "Pôr mais um guarda na vila"; p.reason = "estou com medo"; p.actionType = "WORK"; p.params.put("profession", "GUARD"); p.params.put("amount", "1"); p.params.put("from", "PEASANT"); }
            case "CHAPEL" -> {
                if (projectExists(k, "custom")) return null;
                p.text = "Erguer uma capela"; p.reason = "precisamos de um lugar para rezar"; p.actionType = "BUILD";
                p.params.put("blueprint", "custom"); p.params.put("kind", "capela"); p.params.put("name", "Capela");
            }
            case "TAVERN" -> {
                if (projectExists(k, "custom")) return null;
                p.text = "Abrir uma taverna"; p.reason = "falta um lugar para conversar"; p.actionType = "BUILD";
                p.params.put("blueprint", "custom"); p.params.put("kind", "taverna"); p.params.put("name", "Taverna");
            }
            case "LIBRARY" -> {
                if (projectExists(k, "library")) return null;
                p.text = "Construir uma biblioteca"; p.reason = "quero ler e aprender"; p.actionType = "BUILD"; p.params.put("blueprint", "library");
            }
            case "PEACE" -> { p.text = "Fica proibido brigar na vila; quem brigar responde ao rei."; p.reason = "as brigas não param"; p.effect = Decree.PEACE; }
            case "CURFEW" -> { p.text = "Toque de recolher: todos em casa ao anoitecer."; p.reason = "a noite anda perigosa"; p.effect = Decree.CURFEW; }
            case "FEAST" -> { p.text = "Uma festa para todo o povo."; p.reason = "há fartura e o ânimo está baixo"; p.effect = Decree.FEAST; }
            default -> { return null; }
        }
        if (p.actionType.isEmpty()) p.actionType = "DECREE";
        return register(k, n, p);
    }

    /** Petição livre (vinda da LLM): decreto com texto próprio. */
    public Petition fileDecree(Kingdom k, Npc n, String text, String effect, String why) {
        text = clean(text, 160);
        if (text.length() < 6 || pending(k.id).size() >= MAX_PENDING) return null;
        Petition p = new Petition();
        p.kind = "DECREE";
        p.text = text;
        p.reason = clean(why, 120);
        p.effect = Decree.parseEffect(effect);
        if (p.effect.equals(Decree.NONE)) p.effect = Decree.guessEffect(text);
        p.actionType = "DECREE";
        p.minSupport = 2;
        for (Petition o : pending(k.id)) if (Text.norm(o.text).equals(Text.norm(text))) return null;
        return register(k, n, p);
    }

    private Petition register(Kingdom k, Npc n, Petition p) {
        p.kingdomId = k.id;
        p.authorId = n.id;
        p.authorName = n.name;
        p.number = ++core.state().petitionCounter;
        p.filedTick = core.tick();
        core.state().petitions.put(p.id, p);
        n.remember(core.tick(), "Pedi ao reino: " + p.text + ".", 5, null, "peticao");
        event(EventType.PETITION_FILED, GameEvent.Severity.INFO, k, n.id,
                "«" + n.name + "» pede: " + p.text + " (" + p.reason + ") — petição #" + p.number + ".");
        return p;
    }

    static String clean(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("[\\p{Cntrl}]+", " ").replaceAll("\\s+", " ").trim();
        return t.length() > max ? t.substring(0, max).trim() : t;
    }

    // ------------------------------------------------------------------ conselho

    private void council(Kingdom k) {
        if (core.tick() - councilLast.getOrDefault(k.id, -COUNCIL_GAP_TICKS) < COUNCIL_GAP_TICKS) return;
        for (Petition p : pending(k.id)) {
            if (core.tick() - p.filedTick < DELIBERATE_TICKS) continue;
            councilLast.put(k.id, core.tick());
            int support = supporters(k, p).size();
            if (support < p.minSupport) {
                resolve(k, p, false, "o conselho não viu apoio suficiente (" + support + "/" + p.minSupport + ")");
            } else {
                String why = tryExecute(k, p, true);
                if (why != null) resolve(k, p, false, why);
            }
            return;
        }
    }

    public List<Npc> supporters(Kingdom k, Petition p) {
        List<Npc> out = new ArrayList<>();
        for (Npc m : core.citizens(k.id)) {
            if (!m.alive || m.freedom != Freedom.FREE) continue;
            if (m.id.equals(p.authorId)) { out.add(m); continue; }
            boolean agrees = !"DECREE".equals(p.kind) && feels(k, m, p.kind);
            if (!agrees && p.effect != null && !p.effect.equals(Decree.NONE) && !p.kind.equals("DECREE")) agrees = feels(k, m, p.kind);
            Npc a = core.npc(p.authorId);
            if (!agrees && a != null) {
                var r = m.relations.get(a.id);
                agrees = r != null && r.affection >= 50;
            }
            if (agrees) out.add(m);
        }
        return out;
    }

    // ------------------------------------------------------------------ decisão

    /** Aprova (ou rejeita) uma petição pendente. Retorna a mensagem para o jogador. */
    public ActionResult decide(Kingdom k, int number, boolean approve, String verdict) {
        Petition p = find(k.id, number);
        if (p == null) return ActionResult.reject("not_found", "Não existe a petição #" + number + ".");
        if (p.status != Petition.Status.PENDING) return ActionResult.reject("already_decided", "A petição #" + number + " já foi decidida (" + p.status + ").");
        if (!approve) {
            resolve(k, p, false, verdict == null || verdict.isBlank() ? "o rei recusou" : verdict);
            return ActionResult.ok("Petição #" + number + " rejeitada.");
        }
        String why = tryExecute(k, p, false);
        if (why != null) {
            resolve(k, p, false, why);
            return ActionResult.reject("cannot_do", "Não foi possível atender a petição #" + number + ": " + why);
        }
        return ActionResult.ok("Petição #" + number + " aprovada: " + p.text + ".");
    }

    /** Executa a ação da petição pelo ActionSystem. Retorna null se deu certo, ou o motivo da falha. */
    private String tryExecute(Kingdom k, Petition p, boolean byCouncil) {
        ActionRequest req;
        if ("DECREE".equals(p.actionType)) {
            req = ActionRequest.of(k.id, k.rulerPlayer, ActionRequest.ActorKind.PLAYER, ActionType.DECREE, ActionRequest.Source.DIRECTOR,
                    "text", p.text, "effect", p.effect, "author", p.authorName, "author_id", String.valueOf(p.authorId), "petition", String.valueOf(p.number));
        } else {
            ActionType t = ActionType.valueOf(p.actionType);
            List<String> kv = new ArrayList<>();
            for (var e : p.params.entrySet()) { kv.add(e.getKey()); kv.add(e.getValue()); }
            req = ActionRequest.of(k.id, k.rulerPlayer, ActionRequest.ActorKind.PLAYER, t, ActionRequest.Source.DIRECTOR, kv.toArray(new String[0]));
        }
        ActionResult bad = core.actions().validate(req);
        if (bad != null) return bad.message();
        ActionResult res = core.actions().execute(req);
        if (!res.ok()) return res.message();
        resolve(k, p, true, byCouncil ? "o conselho aprovou em nome do rei" : "o rei aprovou");
        return null;
    }

    private void resolve(Kingdom k, Petition p, boolean approved, String verdict) {
        p.status = approved ? Petition.Status.APPROVED : Petition.Status.REJECTED;
        p.decidedTick = core.tick();
        p.verdict = verdict;
        Npc a = core.npc(p.authorId);
        if (a != null && a.alive) {
            if (approved) {
                a.mood = Text.clamp(a.mood + 8, 0, 100);
                a.loyalty = (int) Text.clamp(a.loyalty + 3, 0, 100);
                a.fame = (int) Text.clamp(a.fame + 1, 0, 100);
                a.remember(core.tick(), "Meu pedido foi atendido: " + p.text + ".", 6, null, "peticao");
            } else {
                a.mood = Text.clamp(a.mood - 5, 0, 100);
                a.loyalty = (int) Text.clamp(a.loyalty - 2, 0, 100);
                a.remember(core.tick(), "Meu pedido foi negado (" + p.text + "): " + verdict + ".", 5, null, "peticao");
            }
        }
        event(EventType.PETITION_DECIDED, approved ? GameEvent.Severity.GOOD : GameEvent.Severity.INFO, k, p.authorId,
                "Petição #" + p.number + (approved ? " aprovada" : " rejeitada") + " — " + p.text + " (" + verdict + ").");
    }

    // ------------------------------------------------------------------ decretos

    /** Promulga um decreto (chamado por ActionSystem.DECREE). */
    public ActionResult enact(Kingdom k, String text, String effect, String authorName, UUID authorId, int viaPetition) {
        return enact(k, text, effect, authorName, authorId, viaPetition, null);
    }

    public ActionResult enact(Kingdom k, String text, String effect, String authorName, UUID authorId, int viaPetition, String ruleSpec) {
        com.kingdomsai.core.law.PropertyRule rule = null;
        if (ruleSpec != null && !ruleSpec.isBlank()) {
            try {
                rule = com.kingdomsai.core.law.PropertyRule.parse(ruleSpec);
            } catch (IllegalArgumentException e) {
                return ActionResult.reject("invalid_param", "Regra inválida: " + e.getMessage());
            }
        }
        text = clean(text, 160);
        effect = Decree.parseEffect(effect);
        if (effect.equals(Decree.NONE)) effect = Decree.guessEffect(text);
        for (Decree d : k.decrees)
            if (d.active && (Text.norm(d.text).equals(Text.norm(text)) || rule != null && d.rule != null && d.rule.spec().equals(rule.spec()) || !effect.equals(Decree.NONE) && d.effect.equals(effect)))
                return ActionResult.reject("duplicate", "Já existe uma lei assim em vigor (#" + d.number + ").");
        int active = 0;
        for (Decree d : k.decrees) if (d.active) active++;
        if (active >= MAX_ACTIVE_DECREES) return ActionResult.reject("too_many", "Leis demais em vigor (" + MAX_ACTIVE_DECREES + "). Revogue alguma.");
        int pop = Math.max(1, core.population(k.id));
        if (effect.equals(Decree.FEAST) && k.get(ResourceType.FOOD) < pop * 6.0)
            return ActionResult.reject("no_food", "Falta comida para uma festa (precisa de " + (pop * 6) + ").");
        Decree d = new Decree();
        d.number = ++k.decreeCounter;
        d.text = text;
        d.effect = effect;
        d.authorName = authorName == null || authorName.isBlank() ? (k.rulerName == null ? "o rei" : k.rulerName) : authorName;
        d.authorId = authorId;
        d.enactedTick = core.tick();
        d.viaPetition = viaPetition;
        d.rule = rule;
        k.decrees.add(d);
        String extra = "";
        if (effect.equals(Decree.FEAST)) {
            k.add(ResourceType.FOOD, -pop * 3.0);
            k.morale = Text.clamp(k.morale + 8, 0, 100);
            k.stability = Text.clamp(k.stability + 2, 0, 100);
            for (Npc n : core.citizens(k.id)) if (n.alive) n.mood = Text.clamp(n.mood + 12, 0, 100);
            d.active = false;
            extra = " Houve festa; o povo está mais animado.";
        }
        reactions(k, d);
        event(EventType.DECREE_ENACTED, GameEvent.Severity.INFO, k, authorId, "Lei #" + d.number + ": " + d.text + extra);
        return ActionResult.ok("Lei #" + d.number + " promulgada: " + d.text + extra);
    }

    public ActionResult revoke(Kingdom k, int number) {
        Decree d = decree(k, number);
        if (d == null) return ActionResult.reject("not_found", "Não existe a lei #" + number + ".");
        if (!d.active) return ActionResult.reject("not_active", "A lei #" + number + " já não vale.");
        d.active = false;
        for (Npc n : core.citizens(k.id)) if (n.alive)
            n.remember(core.tick(), "O rei revogou a lei: " + d.text, 3, null, "lei");
        event(EventType.DECREE_REVOKED, GameEvent.Severity.INFO, k, null, "Lei #" + d.number + " revogada: " + d.text);
        return ActionResult.ok("Lei #" + d.number + " revogada.");
    }

    /** Cada súdito reage a uma lei nova conforme o seu temperamento. */
    private void reactions(Kingdom k, Decree d) {
        for (Npc n : core.citizens(k.id)) {
            if (!n.alive) continue;
            n.remember(core.tick(), "Nova lei do reino: " + d.text, 4, null, "lei");
            int discipline = n.trait(Trait.DISCIPLINE), aggression = n.trait(Trait.AGGRESSION);
            if (d.effect.equals(Decree.FEAST)) continue;
            if (discipline >= 55) { n.loyalty = (int) Text.clamp(n.loyalty + 1, 0, 100); }
            else if (discipline < 35 || aggression > 70) { n.mood = Text.clamp(n.mood - 2, 0, 100); }
        }
    }

    // ------------------------------------------------------------------ eventos

    private void event(EventType t, GameEvent.Severity sev, Kingdom k, UUID actor, String msg) {
        core.bus().publish(core.tick(), t, sev, k.id, actor, msg);
    }
}
