package com.kingdomsai.core.llm;

import com.kingdomsai.core.KingdomsCore;
import com.kingdomsai.core.action.ActionRequest;
import com.kingdomsai.core.action.ActionResult;
import com.kingdomsai.core.common.Text;
import com.kingdomsai.core.event.EventType;
import com.kingdomsai.core.event.GameEvent;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.npc.IntelligenceLevel;
import com.kingdomsai.core.npc.Npc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Conversas e ordens em linguagem natural.
 * Fluxo: ContextBuilder → LlmGateway (ou regras) → Plan → ActionSystem (validação) → resposta.
 */
public final class DialogueService {
    public record Reply(String speaker, String text, List<String> actionLines, String provider, boolean fallback, String note) {}

    private final KingdomsCore core;

    public DialogueService(KingdomsCore core) {
        this.core = core;
    }

    /** "Confirmo"/"desisto" para a ordem irreversível que está esperando (massacre, ataque sem piedade). */
    private static final String CONFIRM = "(sim,? )?(eu )?(confirmo|confirmado|confirma|confirmar|pode cumprir|cumpram|cumpra a ordem|cumpram a ordem|executem a ordem|e uma ordem|faca isso|facam isso)( majestade)?[.!]*";
    private static final String ABORT = "(nao,? )?(desisto|desista|cancele a ordem|cancela a ordem|cancelem a ordem|esquece|esqueca|esquecam|aborte|abortem|nao facam|nao faca)( isso)?[.!]*";
    /** Ordens explícitas do rei que não dependem do humor do modelo: se a IA não as propôs, as regras completam. */
    private static final java.util.Set<com.kingdomsai.core.action.ActionType> EXPLICIT = java.util.EnumSet.of(
            com.kingdomsai.core.action.ActionType.ATTACK, com.kingdomsai.core.action.ActionType.OCCUPY, com.kingdomsai.core.action.ActionType.RETREAT,
            com.kingdomsai.core.action.ActionType.SETTLE, com.kingdomsai.core.action.ActionType.PURGE, com.kingdomsai.core.action.ActionType.ENSLAVE,
            com.kingdomsai.core.action.ActionType.FREE, com.kingdomsai.core.action.ActionType.RECRUIT);

    /** Trata "confirmo"/"desisto" sem chamar a IA. true = tratado. */
    public boolean handlePending(UUID playerId, String speaker, String text, Consumer<Reply> callback) {
        var p = core.actions().pending(playerId);
        if (p == null) return false;
        String t = Text.norm(text).replaceAll("^(majestade|senhor|ok)[,! ]+", "");
        boolean yes = t.matches(CONFIRM), no = t.matches(ABORT);
        if (!yes && !no) return false;
        String type = p.request().type().name();
        ActionResult r = yes ? core.actions().confirm(playerId) : core.actions().abort(playerId);
        String said = !yes ? "Como quiser, Majestade. Ninguém fará nada." : r.ok() ? "Está feito, Majestade." : "Majestade... não foi possível.";
        callback.accept(new Reply(speaker, said, resultLines(type, r), "regras", false, ""));
        return true;
    }

    /** Junta à resposta da IA as ordens explícitas de guerra que as regras reconhecem e a IA deixou de fora. */
    private Plan withExplicitOrders(Kingdom k, Npc speaker, String text, Plan plan) {
        Plan rules;
        try {
            rules = new RuleInterpreter(core).interpret(k, speaker, text);
        } catch (RuntimeException e) {
            return plan;
        }
        List<Plan.PlannedAction> merged = new ArrayList<>(plan.actions());
        boolean changed = false;
        for (Plan.PlannedAction a : rules.actions()) {
            if (isGroupCall(a)) { // "chame todos aqui": a fala do NPC não basta; sem a ação o chamado não acontece
                boolean already = plan.actions().stream().anyMatch(x -> x.type() == com.kingdomsai.core.action.ActionType.MOVE
                        || x.type() == com.kingdomsai.core.action.ActionType.SUMMON && (x.params().get("who") != null || isGroupName(x.params().get("npc"))));
                if (!already) {
                    merged.removeIf(x -> x.type() == com.kingdomsai.core.action.ActionType.SUMMON); // um nome só é parte do grupo
                    merged.add(a);
                    changed = true;
                }
                continue;
            }
            if (a.type() == null || !EXPLICIT.contains(a.type())) continue;
            boolean present = plan.actions().stream().anyMatch(x -> x.type() == a.type()
                    || (x.type() == com.kingdomsai.core.action.ActionType.ATTACK || x.type() == com.kingdomsai.core.action.ActionType.OCCUPY)
                    && (a.type() == com.kingdomsai.core.action.ActionType.ATTACK || a.type() == com.kingdomsai.core.action.ActionType.OCCUPY));
            if (!present) {
                merged.add(a);
                changed = true;
            }
        }
        return changed ? new Plan(plan.reply(), merged) : plan;
    }

    private static boolean isGroupCall(Plan.PlannedAction a) {
        return a.type() == com.kingdomsai.core.action.ActionType.MOVE && "true".equals(a.params().get("call"));
    }

    private static boolean isGroupName(String npc) {
        return npc != null && Text.norm(npc).matches("(todos|todas|todo mundo|povo|moradores|suditos|subditos|aldeoes|gente|soldados|guardas|tropa|tropas|exercito|militares|homens|everyone|all)");
    }

    /**
     * Ordem que não virou ação: sem isto o NPC "responde que vai fazer" e o rei acha que foi feito. O aviso diz a verdade:
     * foi só conversa, nada aconteceu.
     */
    private static List<String> warnIfOnlyTalk(List<String> lines, Plan plan, String text) {
        if (!plan.actions().isEmpty() || !RuleInterpreter.looksLikeOrder(text)) return lines;
        List<String> out = new ArrayList<>(lines);
        out.add("⚠ Isso não virou ação — nada foi feito; a resposta acima foi só conversa. Diga de outro jeito "
                + "(ex.: \"chame todos aqui\", \"soldados, vão para a praça\", \"construam uma casa\") ou veja /k help.");
        return out;
    }

    public void talk(UUID playerId, String playerName, Npc npc, String text, Consumer<Reply> callback) {
        Kingdom pk = core.kingdomOfPlayer(playerId);
        boolean own = pk != null && pk.id.equals(npc.kingdomId);
        if (own && handlePending(playerId, npc.name, text, callback)) return;
        npc.level = npc.level.atLeast(IntelligenceLevel.CONTEXTUAL);
        npc.remember(core.tick(), (own ? "O rei" : "O rei estrangeiro " + playerName) + " falou comigo: \"" + Text.truncate(text, 80) + "\"",
                own ? 40 : 55, playerId, "rei", "conversa");
        npc.relationTo(playerId).adjust(1, 1, 0, 0, 1);
        core.bus().publish(core.tick(), EventType.NPC_TALKED, GameEvent.Severity.INFO, npc.kingdomId, npc.id,
                playerName + " conversou com " + npc.name + ".", Map.of("player", playerId.toString()));
        if (own && com.kingdomsai.core.construction.program.DefaultPrograms.isProgramRequest(text)) {
            core.architect().design(pk, text, o -> callback.accept(new Reply(npc.name, o.ok() ? "Sim, Majestade, vou projetar isso." : "Majestade...",
                    List.of((o.ok() ? "✓ BUILD: " : "✗ BUILD: ") + o.message()), o.source(), false, "")));
            return;
        }
        LlmRequest req = core.contextBuilder().npcDialogue(pk, npc, text);
        npc.lastLlmCall = "npc_dialogue @" + core.tick();
        UUID kingdomForActions = own ? pk.id : null;
        core.llm().submit(req).thenAcceptAsync(res -> {
            Plan plan = kingdomForActions == null || res.fallback() || "regras".equals(res.provider()) || "mock".equals(res.provider())
                    ? res.plan() : withExplicitOrders(pk, npc, text, res.plan());
            List<String> lines = kingdomForActions == null ? List.of() : warnIfOnlyTalk(execute(kingdomForActions, playerId, plan, npc, text), plan, text);
            npc.level = npc.level.atLeast(IntelligenceLevel.CONTEXTUAL);
            npc.lastDecision = res.plan().toJson();
            callback.accept(new Reply(npc.name, res.plan().reply(), lines, res.provider(), res.fallback(), res.note()));
        }, core.mainThread()).exceptionally(err -> {
            core.mainThread().execute(() -> callback.accept(new Reply(npc.name, "...", List.of("Erro: " + err.getMessage()), "erro", true, "")));
            return null;
        });
    }

    public void order(UUID playerId, String text, Consumer<Reply> callback) {
        Kingdom k = core.kingdomOfPlayer(playerId);
        if (k == null) {
            callback.accept(new Reply("Sistema", "Você ainda não governa um reino. Use /kingdom found <nome>.", List.of(), "", false, ""));
            return;
        }
        Npc adv = core.advisor().advisorNpc(k);
        if (handlePending(playerId, adv != null ? adv.name + " (Conselheiro)" : "Conselho", text, callback)) return;
        if (Text.norm(text).matches(".*muralha.*(arqueir|soldad|guarni|reserva|portoes).*")) { // ordem militar composta (plano-padrão; passos validados)
            try {
                var plan = core.militaryOrders().submit(k, playerId, com.kingdomsai.core.military.MilitaryOrders.defaultPlanJson());
                List<String> lines = new ArrayList<>();
                for (var st : plan.steps) lines.add("• " + st.id + " " + st.type + (st.after.isEmpty() ? "" : " (depois de " + String.join(",", st.after) + ")") + ": " + st.status);
                callback.accept(new Reply(adv != null ? adv.name + " (Conselheiro)" : "Conselho", "Plano militar em andamento, Majestade.", lines, "regras", false, ""));
            } catch (IllegalArgumentException e) {
                callback.accept(new Reply("Conselho", "Não foi possível.", List.of("✗ " + e.getMessage()), "regras", false, ""));
            }
            return;
        }
        if (com.kingdomsai.core.construction.program.DefaultPrograms.isProgramRequest(text)) {
            core.architect().design(k, text, o -> callback.accept(new Reply(adv != null ? adv.name + " (Conselheiro)" : "Conselho",
                    o.ok() ? "Projeto aprovado, Majestade." : "Não foi possível.", List.of((o.ok() ? "✓ BUILD: " : "✗ BUILD: ") + o.message()), o.source(), false, "")));
            return;
        }
        LlmRequest req = core.contextBuilder().councilOrder(k, text);
        core.llm().submit(req).thenAcceptAsync(res -> {
            Plan plan = res.fallback() || "mock".equals(res.provider()) ? res.plan() : withExplicitOrders(k, null, text, res.plan());
            List<String> lines = warnIfOnlyTalk(execute(k.id, playerId, plan, null, text), plan, text);
            if (adv != null) adv.lastDecision = res.plan().toJson();
            callback.accept(new Reply(adv != null ? adv.name + " (Conselheiro)" : "Conselho", res.plan().reply(), lines,
                    res.provider(), res.fallback(), res.note()));
        }, core.mainThread()).exceptionally(err -> {
            core.mainThread().execute(() -> callback.accept(new Reply("Conselho", "...", List.of("Erro: " + err.getMessage()), "erro", true, "")));
            return null;
        });
    }

    /**
     * @param listener NPC com quem o rei está falando: ordens de rotina sem "npc" são para ele
     *                 ("se eu falar para ele, ele adota a postura").
     */
    private List<String> execute(UUID kingdomId, UUID playerId, Plan plan, Npc listener, String orderText) {
        List<String> lines = new ArrayList<>();
        boolean council = listener == null || listener.office == com.kingdomsai.core.npc.Office.ADVISOR;
        List<String> delegated = new ArrayList<>();
        for (Plan.PlannedAction a : plan.actions()) {
            if (a.type() == null) {
                if (!council) continue; // modelo pequeno inventando ação na conversa com um súdito: o Schema Validator descarta em silêncio
                lines.add("✗ " + a.rawType() + " — ação desconhecida (rejeitada pelo Schema Validator)");
                continue;
            }
            Map<String, String> params = new java.util.LinkedHashMap<>(a.params());
            if (a.type() == com.kingdomsai.core.action.ActionType.CHAIN || a.type() == com.kingdomsai.core.action.ActionType.STOP_CHAIN
                    || a.type() == com.kingdomsai.core.action.ActionType.JOB || a.type() == com.kingdomsai.core.action.ActionType.CANCEL_JOB) {
                if (listener != null && listener.office != com.kingdomsai.core.npc.Office.ADVISOR && params.get("npc") == null && params.get("chain") == null)
                    params.put("npc", listener.name);
                params.putIfAbsent("order", Text.truncate(orderText, 120));
            }
            var t = a.type();
            // falando com o general/capitão: ele é quem decide quem vai
            if ((t == com.kingdomsai.core.action.ActionType.ATTACK || t == com.kingdomsai.core.action.ActionType.OCCUPY
                    || t == com.kingdomsai.core.action.ActionType.RECRUIT) && listener != null && params.get("npc") == null
                    && listener.office.allows(com.kingdomsai.core.npc.Permission.COMMAND) && listener.office != com.kingdomsai.core.npc.Office.KING)
                params.put("npc", listener.name);
            if (t == com.kingdomsai.core.action.ActionType.ATTACK || t == com.kingdomsai.core.action.ActionType.OCCUPY
                    || t == com.kingdomsai.core.action.ActionType.SETTLE || t == com.kingdomsai.core.action.ActionType.TRAIN
                    || t == com.kingdomsai.core.action.ActionType.MOVE) params.putIfAbsent("order", Text.truncate(orderText, 120));
            // quem recebe a ordem é quem cumpre: o instrutor do treino, quem lidera o grupo, o construtor que vai à obra
            boolean doer = listener != null && listener.office != com.kingdomsai.core.npc.Office.ADVISOR && listener.office != com.kingdomsai.core.npc.Office.KING;
            if ((t == com.kingdomsai.core.action.ActionType.TRAIN || t == com.kingdomsai.core.action.ActionType.MOVE) && doer && params.get("npc") == null
                    && !"true".equals(params.get("call"))) // chamado de grupo: todos vêm até o rei, ninguém lidera
                params.put("npc", listener.name);
            if (t == com.kingdomsai.core.action.ActionType.BUILD && doer && listener.profession == com.kingdomsai.core.npc.Profession.BUILDER
                    && params.get("builder") == null) params.put("builder", listener.name);
            if ((t == com.kingdomsai.core.action.ActionType.SUMMON || t == com.kingdomsai.core.action.ActionType.FOLLOW
                    || t == com.kingdomsai.core.action.ActionType.DISMISS) && listener != null && params.get("npc") == null && params.get("who") == null)
                params.put("npc", listener.name);
            // o conselho delega: a obra vai para o construtor com menos serviço
            if (council && t == com.kingdomsai.core.action.ActionType.BUILD && params.get("builder") == null) {
                Npc b = core.construction().leastBusyBuilder(kingdomId);
                if (b != null) params.put("builder", b.name);
            }
            ActionResult r = core.actions().execute(new ActionRequest(kingdomId, playerId, ActionRequest.ActorKind.PLAYER,
                    a.type(), params, ActionRequest.Source.LLM));
            boolean chatter = !council && !r.ok() && ("missing_param".equals(r.code()) || "invalid_param".equals(r.code()));
            if (!chatter) lines.addAll(resultLines(a.type().name(), r)); // ação malformada de papo com súdito: não polui o chat do rei
            if (r.ok()) {
                String who = delegate(t, params, r.message());
                if (who != null) delegated.add(who);
            }
        }
        if (council && delegated.size() >= 1 && plan.actions().size() >= 2 || council && delegated.stream().anyMatch(d -> !d.startsWith("o conselho")))
            lines.add("↳ Delegação: " + String.join("; ", delegated) + ".");
        return lines;
    }

    /** Quem ficou com a tarefa (para o conselho explicar a delegação). */
    private static String delegate(com.kingdomsai.core.action.ActionType t, Map<String, String> params, String msg) {
        String m = msg == null ? "" : msg;
        java.util.regex.Matcher mm;
        String who = params.get("builder") != null ? params.get("builder") : null;
        String task;
        switch (t) {
            case BUILD -> {
                mm = java.util.regex.Pattern.compile("\\d+x (.+?) planejada").matcher(m);
                task = "obra (" + (mm.find() ? mm.group(1) : params.getOrDefault("name", params.getOrDefault("blueprint", "?"))) + ")";
            }
            case JOB -> {
                mm = java.util.regex.Pattern.compile("para ([^:]+):\\n\\s+1\\. ([^\\n]+)").matcher(m);
                if (mm.find()) {
                    who = mm.group(1);
                    task = mm.group(2);
                } else task = "trabalho com as mãos";
            }
            case TRAIN -> {
                mm = java.util.regex.Pattern.compile("Treino #\\d+: ([^(]+?) \\(").matcher(m);
                if (mm.find()) who = mm.group(1);
                task = "treinar a tropa";
            }
            case RECRUIT -> {
                mm = java.util.regex.Pattern.compile("^([^(]+?) \\([^)]*\\) decidiu").matcher(m);
                if (mm.find()) who = mm.group(1);
                task = "convocar";
            }
            case ATTACK, OCCUPY -> {
                mm = java.util.regex.Pattern.compile("— ([^(]+?) \\(").matcher(m);
                if (mm.find()) who = mm.group(1);
                task = "comandar o ataque";
            }
            case MOVE -> {
                who = params.get("npc");
                task = "levar o grupo";
            }
            case CHAIN -> task = "rotina de trabalho";
            case GOAL -> {
                who = "o conselho";
                task = "cuidar de " + params.get("goal");
            }
            default -> {
                return null;
            }
        }
        return (who == null ? "o conselho" : who.trim()) + " → " + task;
    }

    /** "✓ TIPO: mensagem" — mensagens de várias linhas (planos de cadeia) viram várias linhas do chat. */
    public static List<String> resultLines(String type, ActionResult r) {
        List<String> out = new ArrayList<>();
        String[] parts = (r.message() == null ? "" : r.message()).split("\n");
        out.add((r.ok() ? "✓ " : "✗ ") + type + (r.ok() ? ": " + parts[0] : " [" + r.code() + "]: " + parts[0]));
        for (int i = 1; i < parts.length; i++) out.add(parts[i]);
        return out;
    }
}
