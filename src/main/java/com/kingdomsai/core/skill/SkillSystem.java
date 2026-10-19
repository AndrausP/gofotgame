package com.kingdomsai.core.skill;

import com.kingdomsai.core.KingdomsCore;
import com.kingdomsai.core.ai.NpcScheduler;
import com.kingdomsai.core.common.Pos;
import com.kingdomsai.core.common.Text;
import com.kingdomsai.core.construction.Building;
import com.kingdomsai.core.event.EventType;
import com.kingdomsai.core.event.GameEvent;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.npc.Npc;
import com.kingdomsai.core.npc.NpcActivity;
import com.kingdomsai.core.npc.Profession;
import com.kingdomsai.core.port.PhysicalPort;
import com.kingdomsai.core.port.PhysicalPort.BlockInfo;
import com.kingdomsai.core.port.PhysicalPort.Recipe;
import com.kingdomsai.core.work.ChainValidator;
import com.kingdomsai.core.work.Place;

import java.util.*;
import java.util.function.Predicate;

/**
 * Executa as ordens físicas, um segundo por vez, como um jogador faria: vai até o bloco, bate com a
 * ferramenta (o bloco racha), junta o que caiu na mochila, abre o baú, fabrica na bancada, entrega ao rei.
 *
 * Regras de jogo:
 * - ordem direta do rei passa na frente da rotina e do sono; um chamado (tecla G) só PAUSA a ordem;
 * - tudo é revalidado na hora: se alguém colocou um baú ou começou uma obra ali, o bloco é poupado;
 * - bloco que não dá para alcançar em {@link #BLOCK_TIMEOUT} s é pulado (não trava a ordem);
 * - mochila cheia → esvazia no baú do armazém e volta; sem armazém, espera e depois desiste;
 * - longe do rei a área descarrega: a ordem espera (não falha) até ele voltar.
 */
public final class SkillSystem {
    public static final int BLOCK_TIMEOUT = 20;
    public static final int MOVE_TIMEOUT = 120;
    public static final int WAIT_FAIL = 90;
    /** Além disso o rei está "longe" para receber em mãos: o súdito guarda no armazém. */
    public static final int GIVE_RANGE = 96;

    private final KingdomsCore core;
    private final ChunkKeeper chunks;
    private boolean chunksDirty = true;

    public SkillSystem(KingdomsCore core) {
        this.core = core;
        this.chunks = new ChunkKeeper(core);
        core.bus().subscribe(EventType.NPC_DIED, e -> {
            for (PhysicalJob j : core.state().jobs.values())
                if (j.status.live() && j.npcId.equals(e.actorId())) fail(j, "quem fazia morreu");
            // a mochila não some: cai no chão onde ele morreu (como a de um jogador)
            Npc dead = core.npc(e.actorId());
            if (dead != null && !dead.bag.isEmpty() && dead.pos != null && port().isLoaded(dead.pos)) {
                port().drop(dead.pos, new TreeMap<>(dead.bag));
                core.bus().publish(core.tick(), EventType.ITEMS_DROPPED, GameEvent.Severity.WARN, dead.kingdomId, dead.id,
                        "A mochila de " + dead.name + " caiu em " + dead.pos + " (" + summary(dead.bag) + ").");
                dead.bag.clear();
            }
        });
    }

    private PhysicalPort port() {
        return core.physical();
    }

    // ------------------------------------------------------------------ ciclo de vida

    public PhysicalJob start(Kingdom k, PhysicalJob job, String orderText) {
        Npc n = core.npc(job.npcId);
        if (n.jobId != null) {
            PhysicalJob old = core.state().jobs.get(n.jobId);
            if (old != null && old.status.live()) cancel(old, "substituída por uma nova ordem");
        }
        job.id = com.kingdomsai.core.common.Ids.next();
        job.number = ++core.state().jobCounter;
        job.createdTick = core.tick();
        job.stateSince = core.tick();
        job.status = PhysicalJob.Status.ACTIVE;
        core.state().jobs.put(job.id, job);
        chunksDirty = true;
        n.jobId = job.id;
        n.remember(core.tick(), "O rei me mandou " + job.name + ".", 45, null, "ordem", "trabalho");
        job.addLog(core.tick(), "Ordem: " + Text.truncate(orderText == null ? job.name : orderText, 80));
        core.bus().publish(core.tick(), EventType.JOB_STARTED, GameEvent.Severity.INFO, k.id, n.id,
                n.name + " começou: " + job.name + ".", Map.of("job", job.id.toString()));
        return job;
    }

    public String cancel(PhysicalJob j, String why) {
        if (!j.status.live()) return "A ordem #" + j.number + " já está " + j.status.display + ".";
        j.status = PhysicalJob.Status.CANCELLED;
        j.reason = why;
        release(j);
        j.addLog(core.tick(), "Cancelada: " + why);
        Npc n = core.npc(j.npcId);
        return "Ordem #" + j.number + " cancelada" + (n == null ? "." : "; " + n.name + " guarda o que juntou (" + summary(n.bag) + ").");
    }

    private void release(PhysicalJob j) {
        chunksDirty = true;
        Npc n = core.npc(j.npcId);
        if (n != null && j.id.equals(n.jobId)) {
            n.jobId = null;
            n.heldItem = "";
        }
    }

    private void finish(PhysicalJob j, Npc n) {
        j.status = PhysicalJob.Status.DONE;
        j.reason = "";
        release(j);
        String got = j.gained.isEmpty() ? "" : " Rendeu: " + summary(j.gained) + ".";
        j.addLog(core.tick(), "Concluída." + got);
        n.remember(core.tick(), "Cumpri a ordem do rei: " + j.name + "." + got, 45, null, "trabalho");
        core.bus().publish(core.tick(), EventType.JOB_DONE, GameEvent.Severity.GOOD, j.kingdomId, n.id,
                n.name + " terminou: " + j.name + "." + got + (j.note.isBlank() ? "" : " " + j.note), Map.of("job", j.id.toString()));
    }

    private void fail(PhysicalJob j, String why) {
        if (!j.status.live()) return;
        j.status = PhysicalJob.Status.FAILED;
        j.reason = why;
        release(j);
        j.addLog(core.tick(), "Falhou: " + why);
        Npc n = core.npc(j.npcId);
        core.bus().publish(core.tick(), EventType.JOB_FAILED, GameEvent.Severity.WARN, j.kingdomId, j.npcId,
                (n == null ? "A ordem" : n.name) + " não conseguiu " + j.name + ": " + why + ".", Map.of("job", j.id.toString()));
    }

    /** Espera com motivo; failAfter < 0 = espera para sempre (ex.: área descarregada). */
    private void waitFor(PhysicalJob j, String why, int failAfter) {
        if (j.status != PhysicalJob.Status.WAITING || !why.equals(j.reason)) {
            j.status = PhysicalJob.Status.WAITING;
            j.reason = why;
            j.stateSince = core.tick();
        } else if (failAfter >= 0 && core.tick() - j.stateSince > 20L * failAfter) fail(j, why);
    }

    private void active(PhysicalJob j, String what) {
        if (j.status != PhysicalJob.Status.ACTIVE) j.stateSince = core.tick();
        j.status = PhysicalJob.Status.ACTIVE;
        j.reason = what;
    }

    // ------------------------------------------------------------------ tick

    /** Blocos quebrados neste segundo por todos os súditos (limite por tick contra lag e grief em massa). */
    private int breaksThisSecond;

    public void tickSecond() {
        breaksThisSecond = 0;
        // chunks primeiro: a etapa de agora precisa do mundo carregado mesmo com o rei longe
        if (chunksDirty || core.tick() % 100 == 0) {
            chunks.sync();
            chunksDirty = false;
        }
        for (PhysicalJob j : List.copyOf(core.state().jobs.values())) {
            if (!j.status.live()) continue;
            int cursor = j.cursor;
            try {
                tick(j);
            } catch (RuntimeException e) {
                fail(j, "erro inesperado (" + e.getClass().getSimpleName() + ")");
            }
            if (j.cursor != cursor) chunksDirty = true; // etapa nova, chunks novos
        }
    }

    public ChunkKeeper chunks() {
        return chunks;
    }

    /** Área da etapa descarregada: por quê, e se dá para esperar. */
    private void unloaded(PhysicalJob j) {
        if (!core.config().keepOrderChunksLoaded)
            waitFor(j, "a área descarregou (Vossa Majestade se afastou; manter carregado está desligado)", -1);
        else if (chunks.starved(j.id))
            waitFor(j, "na fila: limite de " + core.config().maxForcedChunks + " chunks carregados à distância", -1);
        else {
            chunksDirty = true;
            waitFor(j, "carregando a área", -1);
        }
    }

    private void tick(PhysicalJob j) {
        Npc n = core.npc(j.npcId);
        if (n == null || !n.alive) {
            fail(j, "quem fazia não existe mais");
            return;
        }
        if (!j.id.equals(n.jobId)) {
            fail(j, n.name + " recebeu outra ordem");
            return;
        }
        if (core.scheduler().isSummoned(n)) {
            j.status = PhysicalJob.Status.PAUSED;
            j.reason = "atendendo ao chamado do rei";
            return;
        }
        Kingdom kk = core.kingdom(j.kingdomId);
        if (j.continuous) {
            n.onDuty = true; // produz de verdade: a economia não conta de novo
            if (!continuousStep(j, n, kk)) return;
        }
        PhysicalJob.Task t = j.current();
        if (t == null) {
            finish(j, n);
            return;
        }
        n.onDuty = true;
        meal(j, n, kk);
        n.heldItem = held(n, t);
        n.currentTask = "⚒ " + t.label + (t.total > 1 ? " (" + t.done + "/" + t.total + ")" : t.kind == PhysicalJob.Kind.CRAFT ? " (" + t.done + "/" + t.count + ")" : "");
        Kingdom k = core.kingdom(j.kingdomId);
        boolean taskDone = switch (t.kind) {
            case BREAK, CHOP -> breaking(j, t, n, k);
            case TAKE -> take(j, t, n);
            case PUT -> put(j, t, n);
            case CRAFT -> craft(j, t, n);
            case GIVE -> give(j, t, n);
            case PLANT -> plant(j, t, n);
            case RESUPPLY -> resupply(j, t, n, k);
            case STORE -> store(j, t, n, k);
            case FARM -> farm(j, t, n, k);
            case REPLACE -> replace(j, t, n, k);
        };
        if (taskDone) {
            j.addLog(core.tick(), "✓ " + t.label + (t.kind == PhysicalJob.Kind.BREAK || t.kind == PhysicalJob.Kind.CHOP ? " (" + t.done + " quebrados)" : ""));
            j.cursor++;
            t.progress = 0;
            if (j.current() == null && !j.continuous) finish(j, n);
        }
    }

    // ------------------------------------------------------------------ tarefas

    private boolean breaking(PhysicalJob j, PhysicalJob.Task t, Npc n, Kingdom k) {
        String skill = t.kind == PhysicalJob.Kind.CHOP ? "lenhar" : n.profession == Profession.MINER ? "minerar" : "cavar";
        double budget = speed(n, t.kind) * (1 + 0.05 * n.skillLevel(skill));
        for (int guard = 0; guard < 6; guard++) {
            if (t.blocks.isEmpty()) return true;
            Pos p = t.blocks.get(0);
            if (!port().isLoaded(p)) {
                unloaded(j);
                return false;
            }
            BlockInfo info = port().block(p);
            // revalida na hora: algo pode ter mudado desde a ordem (bloco, construção, claim de outro mod, território)
            java.util.UUID owner = core.state().territory.ownerAt(p);
            if (info.air() || info.fluid() || info.blockEntity() || info.nearFluid() || !info.breakable()
                    || JobPlanner.protectedBy(core, p) != null || !port().mayBreak(j.orderedBy, p) || owner != null && !owner.equals(j.kingdomId)) {
                t.blocks.remove(0);
                t.progress = 0;
                continue;
            }
            if (!inReach(n, p, 5)) {
                // a caminhada até o local (mina/bosque longe do armazém) não conta: o "não alcanço" só corre perto do bloco
                boolean near = n.pos != null && n.pos.distXZ(p) <= 6;
                if (!p.equals(t.targetBlock)) {
                    t.targetBlock = p;
                    t.targetSince = core.tick();
                    t.nearSince = -1;
                }
                if (near && t.nearSince < 0) t.nearSince = core.tick();
                if (core.tick() - (near ? t.nearSince : t.targetSince) > 20L * (near ? BLOCK_TIMEOUT : MOVE_TIMEOUT)) {
                    j.addLog(core.tick(), "Não alcancei " + ItemNames.display(info.id()) + " em " + p + " — pulei.");
                    t.blocks.remove(0);
                    t.progress = 0;
                    continue;
                }
                active(j, "a caminho do bloco");
                return false;
            }
            if (Inventory.full(n)) {
                tripToStorage(j, k, "Mochila cheia (" + Inventory.slotsUsed(n.bag) + "/" + Inventory.SLOTS + "): indo guardar no armazém.");
                return false;
            }
            if (breaksThisSecond >= core.config().maxBreaksPerSecond) {
                active(j, "esperando a vez (limite de blocos por segundo)");
                return false;
            }
            active(j, "quebrando");
            t.targetBlock = p;
            t.targetSince = core.tick();
            String tool = info.tool() == null ? null : Inventory.bestTool(n, info.tool());
            boolean hasTool = info.tool() == null || tool != null;
            n.heldItem = tool != null ? tool : "";
            double cost = Math.max(0.15, info.hardness()) * (tool != null ? 1.0 / Inventory.speedFactor(tool) : hasTool ? 1.0 : info.needsTool() ? 5.0 : 2.0);
            port().animate(n.id, p, PhysicalPort.Anim.SWING, 0);
            if (t.progress + budget < cost) {
                t.progress += budget;
                port().animate(n.id, p, PhysicalPort.Anim.CRACK, (int) Math.min(9, t.progress / cost * 10));
                return false;
            }
            budget -= cost - t.progress;
            t.progress = 0;
            Map<String, Integer> drops = port().breakBlock(n.id, p, tool);
            breaksThisSecond++;
            collect(j, n, p, drops);
            t.blocks.remove(0);
            t.done++;
            n.skillXp.merge(skill, 1, Integer::sum);
            if (tool != null && Inventory.wear(n, tool)) toolBroke(j, n, k, tool, info.tool());
            if (budget <= 0.05) return t.blocks.isEmpty();
        }
        return t.blocks.isEmpty();
    }

    private boolean take(PhysicalJob j, PhysicalJob.Task t, Npc n) {
        if (!reachOrWalk(j, t, n, t.at, 3)) return false;
        if (!port().isLoaded(t.at)) {
            unloaded(j);
            return false;
        }
        if (t.progress++ < 1) {
            port().animate(n.id, t.at, PhysicalPort.Anim.CHEST_OPEN, 0);
            return false;
        }
        Map<String, Integer> c = port().container(t.at);
        if (c == null) {
            fail(j, "o baú em " + t.at + " sumiu");
            return false;
        }
        Predicate<String> m = ItemNames.matcher(t.item);
        int want = t.count <= 0 ? Integer.MAX_VALUE : t.count - t.done;
        int got = 0;
        for (var e : new ArrayList<>(c.entrySet())) {
            if (got >= want) break;
            if (!m.test(e.getKey())) continue;
            int room = Inventory.room(n, e.getKey());
            int n2 = port().take(n.id, t.at, e.getKey(), Math.min(Math.min(want - got, e.getValue()), room));
            if (n2 > 0) n.bag.merge(e.getKey(), n2, Integer::sum);
            got += n2;
        }
        t.done += got;
        port().animate(n.id, t.at, PhysicalPort.Anim.CHEST_CLOSE, 0);
        if (t.done == 0) {
            t.progress = 0;
            waitFor(j, "o baú não tem " + ItemNames.display(t.item), WAIT_FAIL);
            return false;
        }
        if (t.count > 0 && t.done < t.count) j.addLog(core.tick(), "Só havia " + t.done + " de " + ItemNames.display(t.item) + ".");
        return true;
    }

    private boolean put(PhysicalJob j, PhysicalJob.Task t, Npc n) {
        if (!reachOrWalk(j, t, n, t.at, 3)) return false;
        if (!port().isLoaded(t.at)) {
            unloaded(j);
            return false;
        }
        if (t.progress++ < 1) {
            port().animate(n.id, t.at, PhysicalPort.Anim.CHEST_OPEN, 0);
            return false;
        }
        if (port().container(t.at) == null) {
            fail(j, "o baú em " + t.at + " sumiu");
            return false;
        }
        Predicate<String> m = ItemNames.matcher(t.item);
        Map<String, Integer> items = new TreeMap<>();
        int left = t.count <= 0 ? Integer.MAX_VALUE : t.count;
        for (var e : n.bag.entrySet()) {
            if (left <= 0) break;
            if (!m.test(e.getKey()) || isTool(e.getKey()) && "#all".equals(t.item)) continue;
            int v = Math.min(left, e.getValue());
            items.put(e.getKey(), v);
            left -= v;
        }
        Map<String, Integer> rest = port().put(n.id, t.at, items);
        for (var e : items.entrySet()) n.bag.merge(e.getKey(), -(e.getValue() - rest.getOrDefault(e.getKey(), 0)), Integer::sum);
        n.bag.values().removeIf(v -> v <= 0);
        port().animate(n.id, t.at, PhysicalPort.Anim.CHEST_CLOSE, 0);
        int left2 = rest.values().stream().mapToInt(Integer::intValue).sum();
        if (left2 > 0) j.addLog(core.tick(), "Baú cheio: " + left2 + " item(ns) ficaram na mochila.");
        return true;
    }

    private boolean craft(PhysicalJob j, PhysicalJob.Task t, Npc n) {
        if (t.at != null && !reachOrWalk(j, t, n, t.at, 3)) return false;
        if (t.at != null && !port().isLoaded(t.at)) {
            unloaded(j);
            return false;
        }
        if (t.done >= t.count) return true;
        boolean furnace = "FURNACE".equals(t.block);
        int seconds = furnace ? 5 : ItemNames.smithing(t.item) ? 4 : 2;
        if (t.at != null) {
            BlockInfo st = port().block(t.at);
            String need = furnace ? "furnace" : "crafting_table";
            if (!st.id().endsWith(need)) {
                fail(j, (furnace ? "a fornalha" : "a bancada") + " em " + t.at + " sumiu");
                return false;
            }
        }
        Recipe r = runnable(t.item, n, furnace, t.done);
        if (r == null) {
            waitFor(j, "faltam ingredientes para " + ItemNames.display(t.item), WAIT_FAIL);
            return false;
        }
        active(j, "fabricando");
        port().animate(n.id, t.at == null ? n.pos : t.at, PhysicalPort.Anim.SWING, 0);
        if (++t.progress < seconds) return false;
        t.progress = 0;
        for (List<String> slot : r.ingredients()) if (!slot.isEmpty()) take1(n, slot);
        if (furnace && t.done % 8 == 0) take1(n, List.of("minecraft:coal", "minecraft:charcoal"));
        n.bag.merge(t.item, r.count(), Integer::sum);
        j.gained.merge(t.item, r.count(), Integer::sum);
        t.done++;
        port().animate(n.id, t.at == null ? n.pos : t.at, PhysicalPort.Anim.CRAFT, 0);
        return t.done >= t.count;
    }

    /** Receita que dá para fazer agora com o que está na mochila. */
    private Recipe runnable(String item, Npc n, boolean furnace, int done) {
        for (Recipe r : port().recipes(item)) {
            if ((r.station() == PhysicalPort.Station.FURNACE) != furnace) continue;
            Map<String, Integer> sim = new TreeMap<>(n.bag);
            boolean ok = true;
            List<List<String>> slots = new ArrayList<>(r.ingredients());
            if (furnace && done % 8 == 0) slots.add(List.of("minecraft:coal", "minecraft:charcoal"));
            for (List<String> slot : slots) {
                if (slot.isEmpty()) continue;
                String found = null;
                for (String o : slot) if (sim.getOrDefault(o, 0) > 0) {
                    found = o;
                    break;
                }
                if (found == null) {
                    ok = false;
                    break;
                }
                sim.merge(found, -1, Integer::sum);
            }
            if (ok) return r;
        }
        return null;
    }

    private static void take1(Npc n, List<String> options) {
        for (String o : options)
            if (n.bag.getOrDefault(o, 0) > 0) {
                n.bag.merge(o, -1, Integer::sum);
                if (n.bag.get(o) <= 0) n.bag.remove(o);
                return;
            }
    }

    private boolean give(PhysicalJob j, PhysicalJob.Task t, Npc n) {
        Pos king = core.playerPos(j.orderedBy);
        boolean away = king == null || !core.isOnline(j.orderedBy) || n.pos == null || n.pos.distXZ(king) > GIVE_RANGE;
        // Rei longe ou fora do jogo: não sai atrás dele pelo mundo — guarda no armazém e avisa onde.
        if (away || t.at != null) return giveToStorage(j, t, n);
        if (!reachOrWalk(j, t, n, king, 3)) return false;
        Predicate<String> m = ItemNames.matcher(t.item);
        Map<String, Integer> items = new TreeMap<>();
        int left = t.count <= 0 ? Integer.MAX_VALUE : t.count;
        for (var e : n.bag.entrySet()) {
            if (left <= 0) break;
            if (!m.test(e.getKey())) continue;
            int v = Math.min(left, e.getValue());
            items.put(e.getKey(), v);
            left -= v;
        }
        if (items.isEmpty()) {
            fail(j, "não há " + ItemNames.display(t.item) + " na mochila para entregar");
            return false;
        }
        if (!port().give(n.id, j.orderedBy, items)) {
            waitFor(j, "não consegui entregar (Vossa Majestade está longe?)", WAIT_FAIL);
            return false;
        }
        for (var e : items.entrySet()) n.bag.merge(e.getKey(), -e.getValue(), Integer::sum);
        n.bag.values().removeIf(v -> v <= 0);
        port().animate(n.id, king, PhysicalPort.Anim.GIVE, 0);
        j.addLog(core.tick(), "Entregou " + summary(items) + " ao rei.");
        return true;
    }

    private boolean giveToStorage(PhysicalJob j, PhysicalJob.Task t, Npc n) {
        Kingdom k = core.kingdom(j.kingdomId);
        if (t.at == null) {
            t.at = storageChest(k);
            if (t.at == null) {
                j.note = "Vossa Majestade estava longe e não há armazém: " + n.name + " guardou consigo (" + summary(itemsFor(n, t)) + ").";
                j.addLog(core.tick(), j.note);
                return true;
            }
            j.addLog(core.tick(), "Vossa Majestade está longe: vou guardar no armazém.");
        }
        if (!reachOrWalk(j, t, n, t.at, 3)) return false;
        if (!port().isLoaded(t.at)) {
            unloaded(j);
            return false;
        }
        if (port().container(t.at) == null) {
            fail(j, "o baú do armazém sumiu");
            return false;
        }
        Map<String, Integer> items = itemsFor(n, t);
        port().animate(n.id, t.at, PhysicalPort.Anim.CHEST_OPEN, 0);
        Map<String, Integer> rest = port().put(n.id, t.at, items);
        for (var e : items.entrySet()) n.bag.merge(e.getKey(), -(e.getValue() - rest.getOrDefault(e.getKey(), 0)), Integer::sum);
        n.bag.values().removeIf(v -> v <= 0);
        port().animate(n.id, t.at, PhysicalPort.Anim.CHEST_CLOSE, 0);
        j.note = "Vossa Majestade estava longe: " + summary(items) + " ficou no baú do armazém em " + t.at + ".";
        j.addLog(core.tick(), j.note);
        return true;
    }

    private static Map<String, Integer> itemsFor(Npc n, PhysicalJob.Task t) {
        Predicate<String> m = ItemNames.matcher(t.item);
        Map<String, Integer> items = new TreeMap<>();
        int left = t.count <= 0 ? Integer.MAX_VALUE : t.count;
        for (var e : n.bag.entrySet()) {
            if (left <= 0) break;
            if (!m.test(e.getKey())) continue;
            int v = Math.min(left, e.getValue());
            items.put(e.getKey(), v);
            left -= v;
        }
        return items;
    }

    /** Cumpre uma lei sobre objetos: anda até o bloco, tira o proibido e põe o que a lei manda (ou deixa vazio). */
    private boolean replace(PhysicalJob j, PhysicalJob.Task t, Npc n, Kingdom k) {
        Pos at = t.at;
        if (at == null) return true;
        if (!port().isLoaded(at)) {
            unloaded(j);
            return false;
        }
        if (!reachOrWalk(j, t, n, at, 4)) return false;
        BlockInfo info = port().block(at);
        if (!info.id().equals(t.item)) {
            j.addLog(core.tick(), "O objeto em " + at + " já tinha mudado; nada a fazer.");
            return true;
        }
        port().animate(n.id, at, PhysicalPort.Anim.SWING, 0);
        collect(j, n, at, port().breakBlock(n.id, at, null));
        if (t.block != null && !t.block.isEmpty() && !port().place(n.id, at, t.block)) {
            port().place(n.id, at, t.item); // não coube: devolve o original
            j.addLog(core.tick(), "Não consegui pôr " + ItemNames.display(t.block) + " em " + at + ".");
            return true;
        }
        if (k != null) {
            com.kingdomsai.core.kingdom.ResourceType rt = "stone".equals(com.kingdomsai.core.law.Props.of(t.block == null || t.block.isEmpty() ? t.item : t.block).material())
                    ? com.kingdomsai.core.kingdom.ResourceType.STONE : com.kingdomsai.core.kingdom.ResourceType.WOOD;
            k.add(rt, -0.5);
        }
        n.loyalty = Math.min(100, n.loyalty + 1);
        n.remember(core.tick(), "Cumpri a lei #" + t.count + ": " + t.label + ".", 20, null, "lei", "obediencia");
        core.bus().publish(core.tick(), EventType.LAW_ENFORCED, GameEvent.Severity.GOOD, j.kingdomId, n.id,
                n.name + " cumpriu a lei #" + t.count + ": " + t.label + ".", Map.of("decree", String.valueOf(t.count)));
        return true;
    }

    private boolean plant(PhysicalJob j, PhysicalJob.Task t, Npc n) {
        if (n.bag.getOrDefault(t.block, 0) <= 0) {
            boolean torch = t.block.endsWith("torch");
            Kingdom k = core.kingdom(j.kingdomId);
            if (torch && k != null && Kit.stock(k, t.block) > 0) {
                tripToStorage(j, k, "Acabaram as tochas: indo buscar no armazém (a galeria não fica no escuro).");
                return false;
            }
            j.addLog(core.tick(), torch ? "Sem tochas, nem no armazém: a galeria ficou escura em " + t.at + "."
                    : "Nenhuma muda caiu das folhas e não há na mochila; não deu para replantar.");
            return true;
        }
        if (!reachOrWalk(j, t, n, t.at, 4)) return false;
        if (!port().isLoaded(t.at)) {
            unloaded(j);
            return false;
        }
        if (port().block(t.at).air() && port().place(n.id, t.at, t.block)) {
            n.bag.merge(t.block, -1, Integer::sum);
            n.bag.values().removeIf(v -> v <= 0);
            port().animate(n.id, t.at, PhysicalPort.Anim.PLANT, 0);
            j.addLog(core.tick(), t.block.endsWith("torch") ? "Pôs uma tocha em " + t.at + "." : "Replantou " + ItemNames.display(t.block) + ".");
        }
        return true;
    }

    // ------------------------------------------------------------------ mochila, ferramentas, comida, armazém

    /** Itens que caíram vão para a mochila; o que não couber cai no chão ao lado (e ele vai esvaziar). */
    private void collect(PhysicalJob j, Npc n, Pos at, Map<String, Integer> drops) {
        Map<String, Integer> spill = new TreeMap<>();
        for (var e : drops.entrySet()) {
            int fit = Math.min(e.getValue(), Inventory.room(n, e.getKey()));
            if (fit > 0) n.bag.merge(e.getKey(), fit, Integer::sum);
            if (fit < e.getValue()) spill.put(e.getKey(), e.getValue() - fit);
            j.gained.merge(e.getKey(), e.getValue(), Integer::sum);
        }
        if (!spill.isEmpty()) {
            port().drop(at, spill);
            spill.forEach((id, c) -> n.spilled.merge(id, c, Integer::sum)); // recolhe se passar perto com espaço
            j.addLog(core.tick(), "Mochila cheia: " + summary(spill) + " ficou no chão.");
        }
    }

    /** Ferramenta quebrou: troca pela reserva; sem reserva, volta ao armazém buscar outra (se houver). */
    private void toolBroke(PhysicalJob j, Npc n, Kingdom k, String tool, String type) {
        String next = Inventory.bestTool(n, type);
        core.bus().publish(core.tick(), EventType.TOOL_BROKEN, GameEvent.Severity.INFO, j.kingdomId, n.id,
                ItemNames.display(tool) + " de " + n.name + " quebrou" + (next != null ? "; pegou a reserva (" + ItemNames.display(next) + ")." : "."));
        if (next != null) {
            j.addLog(core.tick(), ItemNames.display(tool) + " quebrou: trocou pela reserva.");
            return;
        }
        if (k != null && JobPlanner.kingdomHasTool(k, type)) {
            j.addLog(core.tick(), ItemNames.display(tool) + " quebrou e não há reserva: indo ao armazém buscar outra.");
            j.tasks.add(j.cursor, JobPlanner.resupplyTask(JobPlanner.storagePoint(core, k), List.of(type)));
        } else j.addLog(core.tick(), ItemNames.display(tool) + " quebrou e o armazém não tem outra: segue na mão (devagar). O ferreiro repõe.");
    }

    /** Ida ao armazém: guardar o que juntou e reabastecer o kit; depois volta ao serviço (a tarefa atual continua). */
    private void tripToStorage(PhysicalJob j, Kingdom k, String why) {
        if (k == null) return;
        PhysicalJob.Task cur = j.current();
        if (cur != null && (cur.kind == PhysicalJob.Kind.STORE || cur.kind == PhysicalJob.Kind.RESUPPLY)) return;
        Pos at = JobPlanner.storagePoint(core, k);
        j.tasks.add(j.cursor, JobPlanner.resupplyTask(at, List.of()));
        j.tasks.add(j.cursor, JobPlanner.storeTask(at));
        j.addLog(core.tick(), why);
    }

    /** Trabalhando dá fome: come da ração quando precisa; sem ração e com fome, volta ao armazém. */
    private void meal(PhysicalJob j, Npc n, Kingdom k) {
        if ((core.tick() / 20) % 30 != 0) return; // fome e cansaço correm na vida (LifeSystem); aqui ele come da ração
        if (n.hunger < 60) {
            String ate = Inventory.eat(n);
            if (ate != null) j.addLog(core.tick(), "Comeu " + ItemNames.display(ate) + " da ração.");
            else if (n.hunger < 30) {
                if (hasFood(k)) tripToStorage(j, k, "Sem ração e com fome: indo ao armazém.");
                else if (j.log.isEmpty() || !j.log.get(j.log.size() - 1).contains("trabalhando com fome"))
                    j.addLog(core.tick(), "Sem ração e o armazém sem comida: trabalhando com fome (mais devagar).");
            }
        }
    }

    /** O armazém tem algo de comer para a ração? */
    private static boolean hasFood(Kingdom k) {
        if (k == null) return false;
        if (Kit.stock(k, "minecraft:bread") > 0) return true;
        for (var e : k.goods.entrySet()) if (e.getValue() > 0 && Inventory.nutrition(e.getKey()) > 0) return true;
        return false;
    }

    /** Anda até o armazém (ou, com a área descarregada, leva o tempo do caminho) — no armazém o mundo não precisa estar à vista. */
    private boolean atStorage(PhysicalJob j, PhysicalJob.Task t, Npc n) {
        Pos at = t.at;
        if (at == null) return true;
        boolean visible = port().isLoaded(at) && n.materialized;
        if (visible) return reachOrWalk(j, t, n, at, 3);
        if (inReach(n, at, 3)) return true;
        double dist = n.pos == null ? 0 : n.pos.distXZ(at);
        t.progress += ABSTRACT_WALK_SPEED;
        active(j, "a caminho do armazém");
        if (t.progress * 1.0 < dist) return false;
        n.pos = at;
        t.progress = 0;
        return true;
    }

    /** Blocos por segundo andando fora da vista (igual ao NpcScheduler). */
    private static final double ABSTRACT_WALK_SPEED = 4.0;

    private boolean resupply(PhysicalJob j, PhysicalJob.Task t, Npc n, Kingdom k) {
        if (!atStorage(j, t, n)) return false;
        if (port().isLoaded(t.at) && port().container(t.at) != null) port().animate(n.id, t.at, PhysicalPort.Anim.CHEST_OPEN, 0);
        Map<String, Integer> moved = new TreeMap<>();
        // ferramenta de outro serviço (o fazendeiro mandado cortar árvore precisa de machado)
        if (t.item != null && !t.item.isBlank())
            for (String type : t.item.split(",")) {
                if (type.isBlank() || Inventory.bestTool(n, type) != null) continue;
                String best = null;
                for (var e : k.goods.entrySet())
                    if (e.getValue() > 0 && type.equals(Inventory.toolType(e.getKey())) && (best == null || Inventory.tier(e.getKey()) > Inventory.tier(best))) best = e.getKey();
                if (best != null) {
                    k.goods.merge(best, -1, Integer::sum);
                    k.goods.values().removeIf(v -> v <= 0);
                    n.bag.merge(best, 1, Integer::sum);
                    moved.merge(best, 1, Integer::sum);
                }
            }
        Kit.Trip trip = Kit.resupply(k, n);
        trip.moved().forEach((id, c) -> moved.merge(id, c, Integer::sum));
        core.treasury().sync(k);
        if (port().isLoaded(t.at) && port().container(t.at) != null) port().animate(n.id, t.at, PhysicalPort.Anim.CHEST_CLOSE, 0);
        j.addLog(core.tick(), moved.isEmpty() ? "Nada a pegar no armazém." : "Pegou no armazém: " + summary(moved) + ".");
        if (!trip.lacking().isEmpty()) j.addLog(core.tick(), "Faltou no armazém: " + String.join(", ", trip.lacking()) + ".");
        return true;
    }

    private boolean store(PhysicalJob j, PhysicalJob.Task t, Npc n, Kingdom k) {
        if (!atStorage(j, t, n)) return false;
        if (port().isLoaded(t.at) && port().container(t.at) != null) port().animate(n.id, t.at, PhysicalPort.Anim.CHEST_OPEN, 0);
        Kit.Trip trip = Kit.deposit(k, n);
        core.treasury().sync(k);
        if (port().isLoaded(t.at) && port().container(t.at) != null) port().animate(n.id, t.at, PhysicalPort.Anim.CHEST_CLOSE, 0);
        if (trip.nothing()) return true;
        int units = produce(j, trip.moved());
        j.produced += units;
        j.trips++;
        j.addLog(core.tick(), "Guardou no armazém: " + summary(trip.moved()) + ".");
        core.bus().publish(core.tick(), EventType.STORAGE_TRIP, GameEvent.Severity.INFO, j.kingdomId, n.id,
                n.name + " guardou no armazém: " + summary(trip.moved()) + (j.continuous ? " (produção: " + j.produced + (j.quota > 0 ? "/" + j.quota : "") + ")" : "") + ".");
        return true;
    }

    /** Quanto do que foi guardado conta como produção do serviço (toras, pedra, minério, colheita). */
    private static int produce(PhysicalJob j, Map<String, Integer> moved) {
        int u = 0;
        for (var e : moved.entrySet()) {
            String id = e.getKey();
            boolean counts = switch (j.labor) {
                case "wood" -> id.endsWith("_log") || id.endsWith("_wood");
                case "stone" -> id.equals("minecraft:cobblestone") || id.equals("minecraft:cobbled_deepslate") || id.endsWith("stone");
                case "ore" -> id.startsWith("minecraft:raw_") || id.equals("minecraft:coal") || id.endsWith("_ore");
                case "farm" -> id.equals("minecraft:wheat") || id.equals("minecraft:carrot") || id.equals("minecraft:potato") || id.equals("minecraft:beetroot");
                default -> true;
            };
            if (counts) u += e.getValue();
        }
        return u;
    }

    /** Lavoura: colhe o maduro e replanta; vazio com terra arada → planta; terra → ara com a enxada e planta. */
    private boolean farm(PhysicalJob j, PhysicalJob.Task t, Npc n, Kingdom k) {
        if (t.blocks.isEmpty()) return true;
        Pos p = t.blocks.get(0);
        if (!reachOrWalk(j, t, n, p, 4)) return false;
        if (!port().isLoaded(p)) {
            unloaded(j);
            return false;
        }
        // revalida na hora: terra do reino, proteção de outro mod, limite de blocos por segundo
        java.util.UUID owner = core.state().territory.ownerAt(p);
        if (owner != null && !owner.equals(j.kingdomId) || !port().mayBreak(j.orderedBy, p)) {
            t.blocks.remove(0);
            return t.blocks.isEmpty();
        }
        if (Inventory.full(n)) {
            tripToStorage(j, k, "Mochila cheia de colheita: indo ao armazém.");
            return false;
        }
        if (breaksThisSecond >= core.config().maxBreaksPerSecond) {
            active(j, "esperando a vez (limite de blocos por segundo)");
            return false;
        }
        active(j, "na lavoura");
        String seed = "minecraft:wheat_seeds";
        int g = port().growth(p);
        if (g >= 100) {
            collect(j, n, p, port().breakBlock(n.id, p, null));
            breaksThisSecond++;
            n.skillXp.merge("colher", 1, Integer::sum);
            port().animate(n.id, p, PhysicalPort.Anim.SWING, 0);
        }
        BlockInfo here = port().block(p), below = port().block(p.offset(0, -1, 0));
        if (here.air() && below.id().matches(".*:(dirt|grass_block|coarse_dirt)")) {
            String hoe = Inventory.bestTool(n, "hoe");
            if (hoe == null) j.addLog(core.tick(), "Sem enxada: não deu para arar em " + p.offset(0, -1, 0) + ".");
            else if (port().till(n.id, p.offset(0, -1, 0))) {
                n.heldItem = hoe;
                if (Inventory.wear(n, hoe)) toolBroke(j, n, k, hoe, "hoe");
                below = port().block(p.offset(0, -1, 0));
            }
        }
        if (here.air() && below.id().endsWith("farmland")) {
            if (n.bag.getOrDefault(seed, 0) > 0 && port().place(n.id, p, t.block == null ? "minecraft:wheat" : t.block)) {
                n.bag.merge(seed, -1, Integer::sum);
                n.bag.values().removeIf(v -> v <= 0);
                n.skillXp.merge("plantar", 1, Integer::sum);
                n.heldItem = seed;
                port().animate(n.id, p, PhysicalPort.Anim.PLANT, 0);
            } else if (n.bag.getOrDefault(seed, 0) <= 0) {
                if (k != null && Kit.stock(k, seed) > 0) {
                    tripToStorage(j, k, "Acabaram as sementes: indo buscar no armazém.");
                    return false;
                }
                j.addLog(core.tick(), "Sem sementes no armazém: canteiro vazio em " + p + ".");
            }
        }
        t.blocks.remove(0);
        t.done++;
        return t.blocks.isEmpty();
    }

    // ------------------------------------------------------------------ trabalho contínuo

    private static boolean night(long dayTime) {
        long t = Math.floorMod(dayTime, 24000L);
        return t >= 12600 && t < 23400;
    }

    /**
     * Trabalho contínuo: à noite guarda o que juntou e dorme; de dia planeja o próximo lote (árvore, galeria, canteiros),
     * cumpre a meta e, com a área descarregada (rei longe), segue simulado no Core. @return true se a tarefa atual deve rodar.
     */
    private boolean continuousStep(PhysicalJob j, Npc n, Kingdom k) {
        if (k == null) return true;
        if (j.quota > 0 && j.produced >= j.quota && j.current() == null) {
            j.note = "Meta cumprida: " + j.produced + " no armazém.";
            finish(j, n);
            return false;
        }
        if (night(core.world().dayTime())) {
            boolean carrying = !Kit.surplus(n).isEmpty();
            PhysicalJob.Task cur = j.current();
            if (carrying && (cur == null || cur.kind != PhysicalJob.Kind.STORE)) {
                // fim do turno: guarda o que juntou antes de dormir
                j.tasks.add(j.cursor, JobPlanner.storeTask(JobPlanner.storagePoint(core, k)));
                return true;
            }
            if (!carrying || cur == null || cur.kind != PhysicalJob.Kind.STORE) {
                j.status = PhysicalJob.Status.PAUSED;
                j.reason = "noite: dormindo, volta ao trabalho de manhã";
                n.heldItem = "";
                return false;
            }
            return true;
        }
        if (j.status == PhysicalJob.Status.PAUSED && j.reason.startsWith("noite")) {
            j.status = PhysicalJob.Status.ACTIVE;
            j.reason = "";
            j.addLog(core.tick(), "Amanheceu: de volta ao trabalho.");
        }
        // longe de tudo (área descarregada e sem chunk forçado): trabalho simulado, mesmas regras de mochila/ferramenta/comida
        if (j.site != null && !port().isLoaded(j.site) && (j.current() == null || isWorkTask(j.current()))) {
            abstractWork(j, n, k);
            return false;
        }
        if (j.current() != null) return true;
        if (j.quota > 0 && j.produced + produce(j, Kit.surplus(n)) >= j.quota) {
            j.tasks.add(JobPlanner.storeTask(JobPlanner.storagePoint(core, k)));
            j.addLog(core.tick(), "Juntou o bastante para a meta: levando ao armazém.");
            return true;
        }
        if (core.tick() < j.nextPlanTick) return false;
        List<PhysicalJob.Task> next = JobPlanner.nextBatch(core, k, j, n);
        if (next.isEmpty()) {
            String why = JobPlanner.idleReason(core, k, j, n);
            j.nextPlanTick = core.tick() + 20L * 30;
            waitFor(j, (why.isBlank() ? "nada a fazer no local" : why) + " — tenta de novo em 30 s", -1);
            return false;
        }
        j.tasks.addAll(next);
        j.status = PhysicalJob.Status.ACTIVE;
        return true;
    }

    private static boolean isWorkTask(PhysicalJob.Task t) {
        return t.kind == PhysicalJob.Kind.BREAK || t.kind == PhysicalJob.Kind.CHOP || t.kind == PhysicalJob.Kind.FARM || t.kind == PhysicalJob.Kind.PLANT;
    }

    /**
     * Área descarregada: o súdito segue trabalhando "fora da vista" — rende no ritmo do serviço, gasta a ferramenta,
     * come da ração e, cheio, leva ao armazém (o estoque do reino recebe). O mundo não muda enquanto ninguém olha (LOD).
     */
    private void abstractWork(PhysicalJob j, Npc n, Kingdom k) {
        meal(j, n, k);
        j.status = PhysicalJob.Status.ACTIVE;
        j.reason = "trabalhando longe da vista (simulado)";
        // tarefas físicas pendentes ficam para quando a área carregar de novo
        while (j.current() != null && isWorkTask(j.current())) j.tasks.remove(j.cursor);
        boolean quotaReady = j.quota > 0 && j.produced + produce(j, Kit.surplus(n)) >= j.quota;
        if (Inventory.full(n) || Inventory.foodCount(n) == 0 && n.hunger < 30 && hasFood(k) || quotaReady) {
            Pos at = JobPlanner.storagePoint(core, k);
            Kit.Trip trip = Kit.deposit(k, n);
            j.produced += produce(j, trip.moved());
            j.trips++;
            Kit.resupply(k, n);
            core.treasury().sync(k);
            n.pos = j.site;
            if (!trip.nothing()) j.addLog(core.tick(), "Levou ao armazém (longe da vista): " + summary(trip.moved()) + ".");
            if (at != null) j.abstractProgress = -Math.min(120, at.distXZ(j.site) / ABSTRACT_WALK_SPEED * 2); // ida e volta
            return;
        }
        String type = switch (j.labor) {
            case "wood" -> "axe";
            case "farm" -> "hoe";
            default -> "pickaxe";
        };
        String tool = Inventory.bestTool(n, type);
        String skill = switch (j.labor) {
            case "wood" -> "lenhar";
            case "farm" -> "colher";
            default -> "minerar";
        };
        // segundos por item (com ferramenta de ferro): tora 8 s, pedregulho 3 s, minério 12 s, trigo 6 s
        double secondsPerItem = switch (j.labor) {
            case "wood" -> 8;
            case "stone" -> 3;
            case "ore" -> 12;
            default -> 6;
        };
        double rate = (tool != null ? Inventory.speedFactor(tool) : 0.25) * (1 + 0.05 * n.skillLevel(skill)) * (n.hunger < 20 ? 0.6 : 1.0);
        j.abstractProgress += rate / secondsPerItem;
        while (j.abstractProgress >= 1) {
            j.abstractProgress -= 1;
            String item = switch (j.labor) {
                case "wood" -> "minecraft:oak_log";
                case "stone" -> "minecraft:cobblestone";
                case "ore" -> j.oreId.contains("coal") ? "minecraft:coal" : j.oreId.contains("copper") ? "minecraft:raw_copper"
                        : j.oreId.contains("gold") ? "minecraft:raw_gold" : "minecraft:raw_iron";
                default -> "minecraft:wheat";
            };
            if (!j.labor.equals("wood") && !j.labor.equals("farm") && tool == null) continue; // pedra/minério sem picareta não rende
            if (Inventory.room(n, item) <= 0) break;
            n.bag.merge(item, 1, Integer::sum);
            j.gained.merge(item, 1, Integer::sum);
            n.skillXp.merge(skill, 1, Integer::sum);
            if (j.labor.equals("ore") && Inventory.room(n, "minecraft:cobblestone") >= 2) n.bag.merge("minecraft:cobblestone", 2, Integer::sum); // a galeria até o veio
            if (j.labor.equals("farm")) n.bag.merge("minecraft:wheat_seeds", 1, Integer::sum);
            if (tool != null && Inventory.wear(n, tool)) {
                String next = Inventory.bestTool(n, type);
                j.addLog(core.tick(), ItemNames.display(tool) + " quebrou (longe da vista)" + (next != null ? ": pegou a reserva." : "."));
                if (next == null && JobPlanner.kingdomHasTool(k, type)) {
                    Kit.resupply(k, n);
                    core.treasury().sync(k);
                }
                tool = next;
            }
        }
    }

    // ------------------------------------------------------------------ movimento

    private boolean reachOrWalk(PhysicalJob j, PhysicalJob.Task t, Npc n, Pos target, double reach) {
        if (inReach(n, target, reach)) return true;
        if (!target.equals(t.targetBlock)) {
            t.targetBlock = target;
            t.targetSince = core.tick();
        } else if (core.tick() - t.targetSince > 20L * MOVE_TIMEOUT) {
            fail(j, "não consegui chegar a " + target);
            return false;
        }
        active(j, "a caminho");
        return false;
    }

    private static boolean inReach(Npc n, Pos p, double reach) {
        if (n.pos == null) return false;
        double dx = n.pos.x() - p.x(), dy = (n.pos.y() + 1) - p.y(), dz = n.pos.z() - p.z();
        return dx * dx + dy * dy + dz * dz <= reach * reach + 1;
    }

    /** Para o NpcScheduler: destino da tarefa atual (ordem direta passa na frente da rotina e do sono). */
    public NpcScheduler.Intent intentFor(Npc n) {
        if (n.jobId == null) return null;
        PhysicalJob j = core.state().jobs.get(n.jobId);
        if (j == null || !j.status.live() || j.status == PhysicalJob.Status.PAUSED) return null;
        PhysicalJob.Task t = j.current();
        if (t == null) return null;
        Pos target = switch (t.kind) {
            case BREAK, CHOP, FARM -> t.blocks.isEmpty() ? null : t.blocks.get(0);
            case GIVE -> t.at != null ? t.at : core.playerPos(j.orderedBy);
            default -> t.at;
        };
        if (target == null) return null;
        return new NpcScheduler.Intent(NpcActivity.WORK, target, t.kind == PhysicalJob.Kind.GIVE ? 2 : 1);
    }

    // ------------------------------------------------------------------ consultas

    public List<PhysicalJob> jobs(UUID kingdomId) {
        List<PhysicalJob> out = new ArrayList<>();
        for (PhysicalJob j : core.state().jobs.values()) if (kingdomId.equals(j.kingdomId)) out.add(j);
        out.sort(Comparator.comparingInt(j -> j.number));
        return out;
    }

    public PhysicalJob find(UUID kingdomId, String ref) {
        List<PhysicalJob> list = jobs(kingdomId);
        if (ref == null || ref.isBlank()) {
            for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).status.live()) return list.get(i);
            return null;
        }
        String r = Text.norm(ref).replace("#", "");
        for (PhysicalJob j : list) if (String.valueOf(j.number).equals(r)) return j;
        Npc n = core.findNpc(kingdomId, ref);
        if (n != null && n.jobId != null) return core.state().jobs.get(n.jobId);
        return null;
    }

    public List<String> describe(PhysicalJob j) {
        List<String> out = new ArrayList<>();
        Npc n = core.npc(j.npcId);
        out.add("# Ordem #" + j.number + " — " + (n == null ? "?" : n.name) + " · " + j.status.display + (j.reason.isBlank() ? "" : ": " + j.reason));
        for (int i = 0; i < j.tasks.size(); i++) {
            PhysicalJob.Task t = j.tasks.get(i);
            String mark = i < j.cursor ? "✓" : i == j.cursor && j.status.live() ? "▶" : " ";
            String prog = t.total > 1 ? " " + t.done + "/" + t.total : t.kind == PhysicalJob.Kind.CRAFT ? " " + t.done + "/" + t.count : "";
            out.add(mark + " " + (i + 1) + ". " + t.label + prog + (t.auto ? " (planejado)" : ""));
        }
        if (n != null && !n.bag.isEmpty()) out.add("Mochila de " + n.name + ": " + summary(n.bag));
        if (!j.gained.isEmpty()) out.add("Rendeu até agora: " + summary(j.gained));
        for (int i = Math.max(0, j.log.size() - 4); i < j.log.size(); i++) out.add("  " + j.log.get(i));
        return out;
    }

    // ------------------------------------------------------------------ util

    private double speed(Npc n, PhysicalJob.Kind kind) {
        double base = switch (n.profession) {
            case MINER -> kind == PhysicalJob.Kind.BREAK ? 2.0 : 1.0;
            case LUMBERJACK -> kind == PhysicalJob.Kind.CHOP ? 2.0 : 1.0;
            case BUILDER -> 1.5;
            default -> 1.0;
        };
        return base * (n.hunger < 20 ? 0.6 : 1.0) * com.kingdomsai.core.life.LifeSystem.workFactor(n);
    }

    private Pos storageChest(Kingdom k) {
        if (k == null) return null;
        Building b = ChainValidator.findBuilding(core, k, Place.STORAGE, true);
        if (b == null) return null;
        Pos c = port().findNear(b.centerPos(), "minecraft:chest", 6);
        return c != null ? c : port().findNear(b.centerPos(), "minecraft:barrel", 6);
    }


    private static boolean isTool(String id) {
        return id.matches(".*_(pickaxe|axe|shovel|hoe|sword)");
    }

    /** O que ele leva na mão nesta etapa: a ferramenta DE VERDADE da mochila (ou nada). */
    private String held(Npc n, PhysicalJob.Task t) {
        return switch (t.kind) {
            case BREAK -> {
                String type = t.blocks.isEmpty() ? null : port().block(t.blocks.get(0)).tool();
                String tool = type == null ? null : Inventory.bestTool(n, type);
                yield tool == null ? "" : tool;
            }
            case CHOP -> Objects.requireNonNullElse(Inventory.bestTool(n, "axe"), "");
            case FARM -> Objects.requireNonNullElse(Inventory.bestTool(n, "hoe"), n.bag.containsKey("minecraft:wheat_seeds") ? "minecraft:wheat_seeds" : "");
            case CRAFT -> t.item;
            case GIVE -> t.item != null && !t.item.startsWith("#") ? t.item
                    : n.bag.keySet().stream().findFirst().orElse("");
            case PLANT -> t.block;
            default -> "";
        };
    }

    public static String summary(Map<String, Integer> items) {
        List<String> parts = new ArrayList<>();
        items.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(6)
                .forEach(e -> parts.add(e.getValue() + " " + ItemNames.display(e.getKey())));
        if (items.size() > 6) parts.add("…");
        return parts.isEmpty() ? "nada" : String.join(", ", parts);
    }
}
