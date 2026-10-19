package com.kingdomsai.core.work;

import com.kingdomsai.core.KingdomsCore;
import com.kingdomsai.core.ai.NpcScheduler;
import com.kingdomsai.core.common.Pos;
import com.kingdomsai.core.common.Text;
import com.kingdomsai.core.construction.Building;
import com.kingdomsai.core.event.EventType;
import com.kingdomsai.core.event.GameEvent;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.kingdom.ResourceType;
import com.kingdomsai.core.llm.ContextBuilder;
import com.kingdomsai.core.npc.*;

import java.util.*;

/**
 * Executa as cadeias de trabalho, um segundo por vez.
 *
 * O NPC vai fisicamente até o lugar de cada etapa (o {@link NpcScheduler} pede o destino a este sistema),
 * trabalha o tempo da etapa, carrega os itens na mão e os deixa no baú do prédio. Os papéis se encontram
 * pelos baús: o ferreiro espera o ferro que o minerador traz.
 *
 * Quebra x espera: esperar insumo é normal (WAITING). Quebrar (BROKEN) é perder uma peça da cadeia
 * (pessoa, prédio). A cada {@link #RETRY_SECONDS} a cadeia tenta se reconstruir — acha um substituto da
 * mesma profissão, espera o prédio ficar pronto — e retoma exatamente da etapa onde parou.
 */
public final class WorkSystem {
    public static final long GROW_TICKS = 20L * 60 * 4;
    public static final int RETRY_SECONDS = 15;
    public static final int BOTTLENECK_SECONDS = 120;

    private final KingdomsCore core;

    public WorkSystem(KingdomsCore core) {
        this.core = core;
        // Pessoas e prédios que somem: confere as cadeias já, sem esperar o próximo segundo.
        core.bus().subscribe(EventType.NPC_DIED, e -> checkAll());
        core.bus().subscribe(EventType.NPC_LEFT, e -> checkAll());
        core.bus().subscribe(EventType.NPC_PROFESSION_CHANGED, e -> checkAll());
        core.bus().subscribe(EventType.BUILDING_CANCELLED, e -> checkAll());
        // Fazenda nova vem com um saco de sementes.
        core.bus().subscribe(EventType.BUILDING_COMPLETED, e -> {
            for (Building b : core.state().buildings.values())
                if (b.blueprintId.equals("farm") && b.isComplete() && !b.seeded) {
                    b.seeded = true;
                    b.inventory.merge(Item.SEEDS, 4, Integer::sum);
                }
        });
    }

    // ------------------------------------------------------------------ ciclo de vida

    /** Cria a cadeia já validada. As pessoas adotam a nova rotina (largam a anterior). */
    public WorkChain start(Kingdom k, WorkChain c, String orderText) {
        c.id = UUID.randomUUID();
        c.kingdomId = k.id;
        c.number = ++core.state().chainCounter;
        c.createdTick = core.tick();
        c.orderText = Text.truncate(orderText == null ? "" : orderText, 120);
        c.status = WorkChain.Status.ACTIVE;
        core.state().chains.put(c.id, c);
        for (WorkChain.Role r : c.roles.values()) {
            Npc n = core.npc(r.npcId);
            if (n == null) continue;
            bind(c, r, n);
            n.remember(core.tick(), "O rei me deu uma nova rotina: «" + c.name + "»" + (c.repeat ? ". Farei isso daqui em diante." : "."),
                    60, null, "trabalho", "ordem");
        }
        c.addLog(core.tick(), "Iniciada: " + c.steps.size() + " etapas, " + c.roles.size() + " pessoa(s).");
        core.bus().publish(core.tick(), EventType.CHAIN_STARTED, GameEvent.Severity.INFO, k.id, null,
                "Cadeia #" + c.number + " «" + c.name + "» começou (" + names(c) + ").", Map.of("chain", c.id.toString()));
        return c;
    }

    private void bind(WorkChain c, WorkChain.Role r, Npc n) {
        if (n.dutyChainId != null && !n.dutyChainId.equals(c.id)) {
            WorkChain old = core.state().chains.get(n.dutyChainId);
            if (old != null && old.live()) {
                old.addLog(core.tick(), n.name + " foi remanejado para «" + c.name + "».");
                n.dutyChainId = null;
                // rotina de uma pessoa só: a nova ordem substitui a antiga. Com mais gente, a antiga quebra e procura substituto.
                if (old.roles.size() == 1) stop(old, "substituída por «" + c.name + "»");
                else breakChain(old, n.name + " foi remanejado para outra tarefa");
            }
        }
        n.dutyChainId = c.id;
        r.npcId = n.id;
        r.state = WorkChain.DutyState.MOVING;
        r.waitingSince = 0;
    }

    public String stop(WorkChain c, String why) {
        if (!c.live()) return "A cadeia #" + c.number + " já está " + c.status.display + ".";
        c.status = WorkChain.Status.STOPPED;
        release(c);
        c.addLog(core.tick(), "Encerrada: " + why);
        core.bus().publish(core.tick(), EventType.CHAIN_STOPPED, GameEvent.Severity.INFO, c.kingdomId, null,
                "Cadeia #" + c.number + " «" + c.name + "» encerrada" + (why.isBlank() ? "." : ": " + why), Map.of("chain", c.id.toString()));
        return "Cadeia #" + c.number + " «" + c.name + "» encerrada. As pessoas voltaram às suas rotinas.";
    }

    /** Retomada manual (/k chain N resume): tenta reconstruir agora. */
    public String resume(WorkChain c) {
        if (c.status == WorkChain.Status.ACTIVE) return "A cadeia #" + c.number + " já está ativa.";
        if (c.status != WorkChain.Status.BROKEN) return "A cadeia #" + c.number + " está " + c.status.display + " e não pode ser retomada.";
        String why = tryRepair(c);
        return why == null ? "Cadeia #" + c.number + " retomada de onde parou." : "Ainda não dá para retomar: " + why;
    }

    private void release(WorkChain c) {
        for (WorkChain.Role r : c.roles.values()) {
            Npc n = core.npc(r.npcId);
            if (n != null && c.id.equals(n.dutyChainId)) {
                n.dutyChainId = null;
                n.onDuty = false;
                n.heldItem = "";
            }
        }
    }

    // ------------------------------------------------------------------ tick

    public void tickSecond() {
        for (Npc n : core.state().npcs.values()) n.onDuty = false;
        for (WorkChain c : List.copyOf(core.state().chains.values())) {
            if (c.status == WorkChain.Status.ACTIVE) tick(c);
            else if (c.status == WorkChain.Status.BROKEN && core.tick() - c.lastRetryTick >= 20L * RETRY_SECONDS) {
                c.lastRetryTick = core.tick();
                tryRepair(c);
            }
        }
    }

    private void checkAll() {
        for (WorkChain c : List.copyOf(core.state().chains.values())) {
            if (c.status != WorkChain.Status.ACTIVE) continue;
            String why = brokenPiece(c);
            if (why != null) breakChain(c, why);
        }
    }

    private void tick(WorkChain c) {
        String why = brokenPiece(c);
        if (why != null) {
            breakChain(c, why);
            return;
        }
        Kingdom k = core.kingdom(c.kingdomId);
        boolean allDone = true;
        for (WorkChain.Role r : c.roles.values()) {
            if (r.state != WorkChain.DutyState.DONE) {
                tickRole(c, k, r);
                if (c.status != WorkChain.Status.ACTIVE) return;
            }
            if (r.state != WorkChain.DutyState.DONE) allDone = false;
        }
        if (allDone) {
            c.status = WorkChain.Status.DONE;
            release(c);
            c.addLog(core.tick(), "Concluída.");
            core.bus().publish(core.tick(), EventType.CHAIN_COMPLETED, GameEvent.Severity.GOOD, c.kingdomId, null,
                    "Cadeia #" + c.number + " «" + c.name + "» concluída.", Map.of("chain", c.id.toString()));
        }
    }

    private void tickRole(WorkChain c, Kingdom k, WorkChain.Role r) {
        Npc n = core.npc(r.npcId);
        WorkChain.Step s = c.current(r);
        if (n == null || s == null) return;
        if (n.jobId != null && core.state().jobs.containsKey(n.jobId) && core.state().jobs.get(n.jobId).status.live()) {
            r.state = WorkChain.DutyState.RESTING;
            r.status = "cumprindo uma ordem do rei";
            return;
        }
        n.onDuty = true;
        n.heldItem = heldFor(n, s);
        n.currentTask = "⛓ " + c.name + ": " + s.describe() + progressText(r, s);
        if (n.activity.leisure()) {
            r.state = WorkChain.DutyState.RESTING;
            r.status = n.activity.display;
            if (n.activity == NpcActivity.SUMMONED) n.currentTask = "Atendendo ao rei (rotina «" + c.name + "» em pausa)";
            return;
        }
        Target t = target(c, k, s, n);
        if (t == null) {
            r.state = WorkChain.DutyState.WAITING;
            r.status = s.place == Place.RECIPIENT ? "procurando " + s.targetName : "esperando " + s.place.a() + (s.place.masculine ? " ficar pronto" : " ficar pronta");
            return;
        }
        if (t.pos != null && (n.pos == null || n.pos.distXZ(t.pos) > t.radius + 3)) {
            r.state = WorkChain.DutyState.MOVING;
            r.status = "indo para " + t.label;
            return;
        }
        String wait = blocker(c, k, r, s, n, t);
        if (wait != null) {
            if (r.state != WorkChain.DutyState.WAITING) r.waitingSince = core.tick();
            r.state = WorkChain.DutyState.WAITING;
            r.status = wait;
            if (!r.bottleneckWarned && core.tick() - r.waitingSince > 20L * BOTTLENECK_SECONDS) {
                r.bottleneckWarned = true;
                c.addLog(core.tick(), n.name + " está parado: " + wait + ".");
                core.bus().publish(core.tick(), EventType.CHAIN_BOTTLENECK, GameEvent.Severity.WARN, c.kingdomId, n.id,
                        "Cadeia #" + c.number + ": " + n.name + " " + wait + " há " + BOTTLENECK_SECONDS / 60 + " min.", Map.of("chain", c.id.toString()));
            }
            return;
        }
        r.bottleneckWarned = false;
        r.state = WorkChain.DutyState.WORKING;
        r.status = s.type.display;
        double skill = (s.type.professions.isEmpty() || s.type.professions.contains(n.profession) ? 1.0 : 0.6)
                * (0.85 + n.trait(Trait.DISCIPLINE) / 400.0) * (n.hunger < 20 ? 0.6 : 1.0);
        r.progress += skill;
        int duration = duration(s);
        if (r.progress < duration) return;
        r.progress = 0;
        if (s.type.perUnit) {
            applyUnit(c, k, r, s, n);
            r.unitsDone++;
            if (r.unitsDone < s.amount) return;
        } else applyWhole(c, k, r, s, n, t);
        advance(c, r, n);
    }

    private int duration(WorkChain.Step s) {
        if (s.type == StepType.WRITE && "letter".equals(s.kind)) return 20;
        return s.type.seconds;
    }

    private void advance(WorkChain c, WorkChain.Role r, Npc n) {
        r.unitsDone = 0;
        r.progress = 0;
        r.cursor++;
        r.state = WorkChain.DutyState.MOVING;
        r.status = "";
        List<Integer> idx = c.stepsOf(r.name);
        if (r.cursor < idx.size()) return;
        if (!c.repeat) {
            r.state = WorkChain.DutyState.DONE;
            r.status = "terminou";
            n.dutyChainId = null;
            n.heldItem = "";
            return;
        }
        r.cursor = 0;
        // o ciclo "fecha" quando o papel dono da última etapa dá a volta
        if (c.steps.get(c.steps.size() - 1).role.equals(r.name)) {
            c.cycles++;
            String out = c.cycleOutput.isEmpty() ? "" : " Guardado: " + ChainValidator.summary(c.cycleOutput) + ".";
            c.addLog(core.tick(), "Ciclo " + c.cycles + " completo." + out);
            StringBuilder data = new StringBuilder();
            for (var e : c.cycleOutput.entrySet()) data.append(data.length() == 0 ? "" : ";").append(e.getKey().name()).append('=').append(e.getValue());
            core.bus().publish(core.tick(), EventType.CHAIN_CYCLE_COMPLETED, GameEvent.Severity.INFO, c.kingdomId, n.id,
                    "Cadeia #" + c.number + " «" + c.name + "»: ciclo " + c.cycles + " completo." + out,
                    Map.of("chain", c.id.toString(), "out", data.toString()));
            c.cycleOutput.clear();
        }
    }

    // ------------------------------------------------------------------ etapas

    /** Por que a etapa ainda não pode andar (espera, não quebra). null = pode trabalhar. */
    private String blocker(WorkChain c, Kingdom k, WorkChain.Role r, WorkChain.Step s, Npc n, Target t) {
        int inHand = n.carrying.values().stream().mapToInt(Integer::intValue).sum();
        switch (s.type) {
            case MINE, CHOP -> {
                if (inHand >= ChainValidator.CARRY_CAPACITY) return "está com as mãos cheias";
            }
            case PLANT -> {
                if (t.building.cropPlantedTick > 0) return null; // já plantado: a etapa passa direto
                if (t.building.inventory.getOrDefault(Item.SEEDS, 0) <= 0 && k.get(ResourceType.FOOD) < 2)
                    return "esperando sementes (o celeiro está vazio)";
            }
            case HARVEST -> {
                Building farm = t.building;
                if (farm.cropPlantedTick <= 0) return "esperando alguém plantar";
                long age = core.tick() - farm.cropPlantedTick;
                if (age < GROW_TICKS) return "esperando o trigo amadurecer (" + (int) (100 * age / GROW_TICKS) + "%)";
            }
            case DELIVER, STORE -> {
                if (s.item == null ? inHand == 0 : n.carrying.getOrDefault(s.item, 0) < Math.max(1, s.amount))
                    return "sem " + (s.item == null ? "nada" : s.item.display.toLowerCase()) + " na mão";
            }
            case PICKUP -> {
                int have = t.building.inventory.getOrDefault(s.item, 0);
                if (have < s.amount) return "esperando " + s.item.display.toLowerCase() + " " + s.place.na()
                        + " (" + have + "/" + s.amount + ")";
            }
            case SMELT -> {
                Building f = t.building;
                if (f.inventory.getOrDefault(Item.RAW_IRON, 0) < 1) return "esperando ferro bruto na forja";
                if (r.unitsDone % 2 == 0 && f.inventory.getOrDefault(Item.COAL, 0) < 1) return "esperando carvão na forja";
            }
            case FORGE -> {
                if (n.carrying.getOrDefault(Item.IRON_INGOT, 0) < 2) return "sem barras de ferro na mão";
            }
            case READ -> {
                if (pickBook(k, n, s) == null)
                    return c.steps.stream().anyMatch(x -> x.type == StepType.WRITE) ? "esperando um livro novo ser escrito" : null;
            }
            default -> {
            }
        }
        return null;
    }

    private void applyUnit(WorkChain c, Kingdom k, WorkChain.Role r, WorkChain.Step s, Npc n) {
        switch (s.type) {
            case MINE, CHOP -> n.carrying.merge(s.item, 1, Integer::sum);
            case SMELT -> {
                Building f = ChainValidator.findBuilding(core, k, Place.SMITHY, true);
                if (r.unitsDone % 2 == 0) f.inventory.merge(Item.COAL, -1, Integer::sum);
                f.inventory.merge(Item.RAW_IRON, -1, Integer::sum);
                n.carrying.merge(Item.IRON_INGOT, 1, Integer::sum);
            }
            case FORGE -> {
                n.carrying.merge(Item.IRON_INGOT, -2, Integer::sum);
                n.carrying.merge(Item.SWORD, 1, Integer::sum);
            }
            default -> {
            }
        }
        clean(n.carrying);
    }

    private void applyWhole(WorkChain c, Kingdom k, WorkChain.Role r, WorkChain.Step s, Npc n, Target t) {
        switch (s.type) {
            case PLANT -> {
                Building farm = t.building;
                if (farm.cropPlantedTick > 0) return;
                if (farm.inventory.getOrDefault(Item.SEEDS, 0) > 0) farm.inventory.merge(Item.SEEDS, -1, Integer::sum);
                else k.add(ResourceType.FOOD, -2);
                farm.cropPlantedTick = core.tick();
                c.addLog(core.tick(), n.name + " plantou a fazenda.");
            }
            case HARVEST -> {
                Building farm = t.building;
                farm.cropPlantedTick = 0;
                farm.inventory.merge(Item.SEEDS, 2, Integer::sum);
                int yield = (int) Math.round(ChainValidator.HARVEST_YIELD * (n.profession == Profession.FARMER ? 1.0 : 0.7));
                n.carrying.merge(Item.WHEAT, yield, Integer::sum);
                c.addLog(core.tick(), n.name + " colheu " + yield + " de trigo.");
            }
            case DELIVER -> {
                if (s.place == Place.RECIPIENT) deliverLetter(c, k, r, s, n);
                else {
                    Map<Item, Integer> moved = ChainValidator.take(n.carrying, s.item, s.amount);
                    if (moved != null) for (var e : moved.entrySet()) t.building.inventory.merge(e.getKey(), e.getValue(), Integer::sum);
                }
            }
            case PICKUP -> {
                t.building.inventory.merge(s.item, -s.amount, Integer::sum);
                n.carrying.merge(s.item, s.amount, Integer::sum);
            }
            case STORE -> {
                Map<Item, Integer> moved = ChainValidator.take(n.carrying, s.item, s.amount);
                if (moved != null) for (var e : moved.entrySet()) {
                    t.building.inventory.merge(e.getKey(), e.getValue(), Integer::sum);
                    if (e.getKey().resource != null) {
                        // o baú do armazém é o estoque do reino: o recurso entra no tesouro e o item sai do baú
                        k.add(e.getKey().resource, e.getValue() * e.getKey().resourcePerUnit);
                        t.building.inventory.merge(e.getKey(), -e.getValue(), Integer::sum);
                    }
                    c.cycleOutput.merge(e.getKey(), e.getValue(), Integer::sum);
                }
            }
            case WRITE -> write(c, k, r, s, n, t);
            case READ -> read(c, k, s, n);
            default -> {
            }
        }
        clean(n.carrying);
        if (t.building != null) clean(t.building.inventory);
    }

    // ------------------------------------------------------------------ livros e cartas

    private void write(WorkChain c, Kingdom k, WorkChain.Role r, WorkChain.Step s, Npc n, Target t) {
        Document d = new Document();
        d.id = UUID.randomUUID();
        d.kingdomId = k.id;
        d.authorId = n.id;
        d.authorName = n.name;
        d.tick = core.tick();
        if ("letter".equals(s.kind)) {
            d.kind = Document.Kind.LETTER;
            d.recipientId = s.targetNpc;
            d.title = "Carta de " + n.name + " para " + s.targetName;
            String msg = !s.text.isBlank() ? s.text : !s.topic.isBlank() ? "Escrevo sobre " + s.topic + "." : "Envio minhas saudações.";
            d.text = "Prezado(a) " + s.targetName + ",\n\n" + capitalize(msg) + "\n\n"
                    + (n.trait(Trait.RELIGIOSITY) > 70 ? "Que os deuses o(a) guardem.\n" : "Com estima,\n") + n.name + ", " + n.title().toLowerCase() + ".";
            d.facts.add(n.name + " me escreveu: " + Text.truncate(msg, 120));
            r.heldDoc = d.id;
            n.carrying.merge(Item.LETTER, 1, Integer::sum);
        } else {
            d.kind = Document.Kind.BOOK;
            d.libraryId = t.building == null ? null : t.building.id;
            composeBook(d, k, n, s.topic);
            n.fame += 2;
            n.literate = true;
        }
        core.state().documents.put(d.id, d);
        n.remember(core.tick(), "Escrevi «" + d.title + "».", 55, null, "escrita", d.kind == Document.Kind.BOOK ? "livro" : "carta");
        c.addLog(core.tick(), n.name + " escreveu «" + d.title + "».");
        core.bus().publish(core.tick(), EventType.DOCUMENT_WRITTEN, GameEvent.Severity.GOOD, k.id, n.id,
                n.name + " escreveu «" + d.title + "»" + (d.kind == Document.Kind.BOOK ? " — está na biblioteca." : "."),
                Map.of("document", d.id.toString()));
    }

    /** O livro nasce das memórias do autor e da crônica do reino — cada autor escreve um livro diferente. */
    private void composeBook(Document d, Kingdom k, Npc author, String topic) {
        boolean chronicle = topic.isBlank() || Text.norm(topic).matches(".*(cronica|historia|reino|chronicle).*");
        d.title = chronicle ? "Crônica de " + k.name : "Sobre " + topic;
        StringBuilder sb = new StringBuilder();
        sb.append("«").append(d.title).append("»\nPor ").append(author.name).append(", ").append(author.title().toLowerCase())
                .append(" de ").append(k.name).append(", no dia ").append(core.day()).append(".\n\n");
        List<String> facts = new ArrayList<>();
        if (chronicle) {
            List<String> ch = core.state().chronicle;
            for (int i = Math.max(0, ch.size() - 4); i < ch.size(); i++) facts.add(ch.get(i));
        }
        for (Memory m : ContextBuilder.relevantMemories(author, topic.isBlank() ? "reino rei trabalho" : topic, 4))
            if (m.importance() >= 30 && !m.tags().contains("ordem") && !m.tags().contains("conversa") && !m.text().startsWith("Escrevi"))
                facts.add("Lembro: " + m.text());
        facts.addAll(observations(k, Text.norm(topic)));
        if (facts.isEmpty()) facts.add("Os dias em " + k.name + " seguem em paz sob o rei " + k.rulerName + ".");
        for (String f : facts) sb.append("  ").append(f).append('\n');
        sb.append('\n').append(author.trait(Trait.HONESTY) < 30 ? "(Algumas passagens parecem exageradas.)" : "Registrado com fidelidade.");
        d.text = sb.toString();
        d.facts.addAll(facts.subList(0, Math.min(3, facts.size())));
    }

    /** O que um escriba observaria no reino sobre o assunto (sem números de painel). */
    private List<String> observations(Kingdom k, String topic) {
        List<String> out = new ArrayList<>();
        int pop = core.population(k.id);
        boolean all = topic.isBlank() || topic.matches(".*(cronica|historia|reino).*");
        if (all || topic.matches(".*(ferro|mina|minera|forja|ferreiro|metal|espada|arma).*")) {
            out.add(k.name + " tem " + core.count(k.id, Profession.MINER) + " minerador(es) e " + core.count(k.id, Profession.BLACKSMITH)
                    + " ferreiro(s); há " + (int) k.get(ResourceType.IRON) + " barras de ferro no tesouro.");
        }
        if (all || topic.matches(".*(comida|fazenda|trigo|colheita|lavoura|fome|plant).*"))
            out.add(core.count(k.id, Profession.FARMER) + " fazendeiro(s) alimentam " + pop + " almas"
                    + (k.famine ? ", mas a fome castiga o povo." : "; os celeiros têm " + (int) k.get(ResourceType.FOOD) + " medidas de comida."));
        if (all || topic.matches(".*(guerra|exercito|soldad|defesa|guarda).*"))
            out.add("O exército conta " + core.military(k.id) + " homens e mulheres em armas.");
        for (WorkChain c : chains(k.id)) {
            boolean related = all || Arrays.stream(Text.norm(c.name).split("\\s+")).anyMatch(w -> w.length() > 3 && topic.contains(w))
                    || c.steps.stream().anyMatch(s -> s.item != null && topic.contains(Text.norm(s.item.display).split(" ")[0]));
            if (related && c.cycles > 0) out.add("O trabalho «" + c.name + "» já se cumpriu " + c.cycles + " vez(es).");
        }
        if (out.isEmpty()) out.add("Em " + k.name + " vivem " + pop + " almas sob o rei " + k.rulerName + ".");
        return out;
    }

    private void deliverLetter(WorkChain c, Kingdom k, WorkChain.Role r, WorkChain.Step s, Npc n) {
        Document d = r.heldDoc == null ? null : core.state().documents.get(r.heldDoc);
        Npc to = core.npc(s.targetNpc);
        n.carrying.merge(Item.LETTER, -1, Integer::sum);
        r.heldDoc = null;
        if (d == null || to == null) return;
        d.delivered = true;
        d.readers.add(to.id);
        boolean reads = ChainValidator.canRead(to);
        String body = Text.truncate(d.text.replace('\n', ' ').replaceAll("\\s+", " "), 160);
        to.remember(core.tick(), reads ? "Recebi uma carta de " + d.authorName + ": «" + body + "»"
                : "Recebi uma carta de " + d.authorName + "; não sei ler, então " + n.name + " a leu para mim: «" + body + "»", 55, n.id, "carta");
        to.relationTo(n.id).adjust(4, 3, 0, 0, 2);
        c.addLog(core.tick(), n.name + " entregou a carta a " + to.name + ".");
        core.bus().publish(core.tick(), EventType.LETTER_DELIVERED, GameEvent.Severity.INFO, k.id, to.id,
                n.name + " entregou «" + d.title + "» a " + to.name + ".", Map.of("document", d.id.toString()));
    }

    private void read(WorkChain c, Kingdom k, WorkChain.Step s, Npc n) {
        Document d = pickBook(k, n, s);
        if (d == null) {
            c.addLog(core.tick(), n.name + " não achou livro novo para ler.");
            return;
        }
        d.readers.add(n.id);
        n.literate = true;
        n.remember(core.tick(), "Li «" + d.title + "», de " + d.authorName + ".", 45, d.authorId, "leitura", "livro");
        for (String f : d.facts.subList(0, Math.min(2, d.facts.size())))
            n.remember(core.tick(), "Aprendi lendo «" + d.title + "»: " + Text.truncate(f, 120), 35, null, "leitura", "saber");
        if (n.trait(Trait.CURIOSITY) > 60) n.fame += 1;
        c.addLog(core.tick(), n.name + " leu «" + d.title + "».");
        core.bus().publish(core.tick(), EventType.BOOK_READ, GameEvent.Severity.INFO, k.id, n.id,
                n.name + " leu «" + d.title + "».", Map.of("document", d.id.toString()));
    }

    private Document pickBook(Kingdom k, Npc n, WorkChain.Step s) {
        Document best = null;
        for (Document d : core.state().documents.values()) {
            if (d.kind != Document.Kind.BOOK || !k.id.equals(d.kingdomId)) continue;
            if (!s.title.isBlank()) {
                if (Text.norm(d.title).contains(Text.norm(s.title))) return d;
                continue;
            }
            if (d.readers.contains(n.id) || n.id.equals(d.authorId)) continue;
            if (best == null || d.tick > best.tick) best = d;
        }
        return best;
    }

    // ------------------------------------------------------------------ quebra e retomada

    /** O que impede a cadeia de existir agora (pessoa ou prédio). null = inteira. */
    private String brokenPiece(WorkChain c) {
        Kingdom k = core.kingdom(c.kingdomId);
        if (k == null) return "o reino não existe mais";
        for (WorkChain.Role r : c.roles.values()) {
            if (r.state == WorkChain.DutyState.DONE) continue;
            Npc n = core.npc(r.npcId);
            if (n == null || !n.alive) return (n == null ? "o " + r.name : n.name) + " morreu";
            if (!k.id.equals(n.kingdomId)) return n.name + " deixou o reino";
            if (!c.id.equals(n.dutyChainId)) return n.name + " foi remanejado para outra tarefa";
            if (r.profession != null && n.profession != r.profession && needsProfession(c, r))
                return n.name + " deixou de ser " + r.profession.display.toLowerCase();
            WorkChain.Step s = c.current(r);
            if (s != null && s.place != null && s.place.isBuilding() && ChainValidator.findBuilding(core, k, s.place, false) == null)
                return "não há mais " + s.place.display.toLowerCase() + " no reino";
            if (s != null && s.place == Place.RECIPIENT) {
                Npc to = core.npc(s.targetNpc);
                if (to == null || !to.alive) return "o destinatário " + s.targetName + " morreu";
            }
        }
        return null;
    }

    private static boolean needsProfession(WorkChain c, WorkChain.Role r) {
        for (int i : c.stepsOf(r.name)) if (c.steps.get(i).type.skilled) return true;
        return false;
    }

    private void breakChain(WorkChain c, String why) {
        if (c.status != WorkChain.Status.ACTIVE) return;
        c.status = WorkChain.Status.BROKEN;
        c.brokenReason = why;
        c.brokenTick = core.tick();
        c.lastRetryTick = core.tick();
        StringBuilder where = new StringBuilder();
        for (WorkChain.Role r : c.roles.values()) {
            if (r.state == WorkChain.DutyState.DONE) continue;
            where.append(where.length() == 0 ? "" : ", ").append(r.name).append(" na etapa ").append(c.globalIndex(r));
            Npc n = core.npc(r.npcId);
            if (n != null) n.onDuty = false;
        }
        c.addLog(core.tick(), "QUEBROU: " + why + ". Parou em: " + where + ".");
        core.bus().publish(core.tick(), EventType.CHAIN_BROKEN, GameEvent.Severity.WARN, c.kingdomId, null,
                "Cadeia #" + c.number + " «" + c.name + "» quebrou: " + why + ". Tentarei retomar de onde parou.",
                Map.of("chain", c.id.toString()));
    }

    /** Tenta remontar a cadeia: substitutos para quem sumiu, prédios prontos. null = retomada. */
    private String tryRepair(WorkChain c) {
        Kingdom k = core.kingdom(c.kingdomId);
        if (k == null) return "o reino não existe mais";
        Set<UUID> taken = new HashSet<>();
        for (WorkChain.Role r : c.roles.values()) if (r.npcId != null) taken.add(r.npcId);
        for (WorkChain.Role r : c.roles.values()) {
            if (r.state == WorkChain.DutyState.DONE) continue;
            Npc n = core.npc(r.npcId);
            boolean ok = n != null && n.alive && k.id.equals(n.kingdomId)
                    && (n.dutyChainId == null || n.dutyChainId.equals(c.id))
                    && !(r.profession != null && n.profession != r.profession && needsProfession(c, r));
            if (ok) continue;
            Npc sub = substitute(c, k, r, taken);
            if (sub == null) {
                String why = r.profession != null ? "não há " + r.profession.display.toLowerCase() + " livre para o papel de " + r.name
                        : "ninguém pode assumir o papel de " + r.name;
                noteRetry(c, why);
                return why;
            }
            String before = n == null ? r.name : n.name;
            if (n != null && c.id.equals(n.dutyChainId)) n.dutyChainId = null;
            // o que estava na mão de quem saiu se perde (morte) ou fica com ele (remanejado)
            bind(c, r, sub);
            taken.add(sub.id);
            sub.remember(core.tick(), "Assumi o lugar de " + before + " em «" + c.name + "».", 50, null, "trabalho");
            c.addLog(core.tick(), sub.name + " assumiu o papel de " + r.name + " (antes: " + before + ").");
        }
        for (WorkChain.Step s : c.steps)
            if (s.place != null && s.place.isBuilding() && ChainValidator.findBuilding(core, k, s.place, false) == null) {
                String why = "falta " + s.place.a() + " (construa de novo)";
                noteRetry(c, why);
                return why;
            }
        c.status = WorkChain.Status.ACTIVE;
        c.brokenReason = "";
        StringBuilder where = new StringBuilder();
        for (WorkChain.Role r : c.roles.values()) {
            if (r.state == WorkChain.DutyState.DONE) continue;
            r.state = WorkChain.DutyState.MOVING;
            r.waitingSince = 0;
            where.append(where.length() == 0 ? "" : ", ").append(r.name).append(" na etapa ").append(c.globalIndex(r));
        }
        c.addLog(core.tick(), "Retomada de onde parou: " + where + ".");
        core.bus().publish(core.tick(), EventType.CHAIN_RESUMED, GameEvent.Severity.GOOD, c.kingdomId, null,
                "Cadeia #" + c.number + " «" + c.name + "» retomada (" + where + ").", Map.of("chain", c.id.toString()));
        return null;
    }

    private void noteRetry(WorkChain c, String why) {
        if (!why.equals(c.brokenReason)) {
            c.brokenReason = why;
            c.addLog(core.tick(), "Ainda parada: " + why + ".");
        }
    }

    private Npc substitute(WorkChain c, Kingdom k, WorkChain.Role r, Set<UUID> taken) {
        List<WorkChain.Step> steps = new ArrayList<>();
        for (int i : c.stepsOf(r.name)) steps.add(c.steps.get(i));
        Npc best = null;
        for (Npc n : core.citizens(k.id)) {
            if (taken.contains(n.id) || n.office == Office.KING) continue;
            if (r.profession != null && n.profession != r.profession) continue;
            if (steps.stream().anyMatch(s -> s.type.literacy == StepType.Literacy.READ) && !ChainValidator.canRead(n)) continue;
            if (steps.stream().anyMatch(s -> s.type.literacy == StepType.Literacy.WRITE) && !ChainValidator.canWrite(n)) continue;
            WorkChain busy = n.dutyChainId == null ? null : core.state().chains.get(n.dutyChainId);
            if (busy != null && busy.live() && busy != c) continue;
            if (r.profession == null && n.profession.isMilitary()) continue;
            best = n;
            break;
        }
        return best;
    }

    // ------------------------------------------------------------------ onde fica cada etapa

    private record Target(Pos pos, double radius, String label, Building building) {}

    private Target target(WorkChain c, Kingdom k, WorkChain.Step s, Npc n) {
        if (s.place == null) return new Target(null, 4, "onde estiver", null);
        return switch (s.place) {
            case MINE -> new Target(natureSpot(k, 0), 6, "a mina", null);
            case FOREST -> new Target(natureSpot(k, 1), 7, "o bosque", null);
            case RECIPIENT -> {
                Npc to = core.npc(s.targetNpc);
                yield to == null || to.pos == null ? null : new Target(to.pos, 2, s.targetName, null);
            }
            default -> {
                Building b = ChainValidator.findBuilding(core, k, s.place, true);
                if (b == null) yield null;
                double rad = Math.max(b.blueprint().sizeX(), b.blueprint().sizeZ()) / 2.0 + 1;
                yield new Target(b.centerPos(), rad, s.place.a(), b);
            }
        };
    }

    /** Mina e bosque do reino: lugares fixos, em direções diferentes a partir do centro. */
    public Pos natureSpot(Kingdom k, int which) {
        // o rei marcou a mina/o bosque com a Bandeira do Reino
        Pos marked = k.markers.get(which == 0 ? com.kingdomsai.core.kingdom.Marker.MINE : com.kingdomsai.core.kingdom.Marker.FOREST);
        if (marked != null) return marked;
        int h = Math.abs(k.id.hashCode());
        double a = (h % 360) * Math.PI / 180.0 + which * 2.1;
        int dist = which == 0 ? 26 : 30;
        return k.center.offset((int) (Math.cos(a) * dist), 0, (int) (Math.sin(a) * dist));
    }

    /** Para o NpcScheduler: se o NPC cumpre uma cadeia ativa, o destino é o lugar da etapa atual. */
    public NpcScheduler.Intent intentFor(Npc n) {
        if (n.dutyChainId == null) return null;
        WorkChain c = core.state().chains.get(n.dutyChainId);
        if (c == null || c.status != WorkChain.Status.ACTIVE) return null;
        Kingdom k = core.kingdom(c.kingdomId);
        for (WorkChain.Role r : c.roles.values()) {
            if (!n.id.equals(r.npcId) || r.state == WorkChain.DutyState.DONE) continue;
            WorkChain.Step s = c.current(r);
            if (s == null) return null;
            Target t = target(c, k, s, n);
            if (t == null) return null;
            n.currentTask = "⛓ " + c.name + ": " + s.describe() + progressText(r, s);
            return new NpcScheduler.Intent(NpcActivity.WORK, t.pos == null ? n.pos : t.pos, Math.max(1, t.radius));
        }
        return null;
    }

    // ------------------------------------------------------------------ consultas

    public List<WorkChain> chains(UUID kingdomId) {
        List<WorkChain> out = new ArrayList<>();
        for (WorkChain c : core.state().chains.values()) if (kingdomId.equals(c.kingdomId)) out.add(c);
        out.sort(Comparator.comparingInt(c -> c.number));
        return out;
    }

    public WorkChain find(UUID kingdomId, String ref) {
        List<WorkChain> list = chains(kingdomId);
        if (ref == null || ref.isBlank()) {
            for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).live()) return list.get(i);
            return null;
        }
        String n = Text.norm(ref).replace("#", "");
        for (WorkChain c : list) if (String.valueOf(c.number).equals(n)) return c;
        for (int i = list.size() - 1; i >= 0; i--) if (Text.norm(list.get(i).name).contains(n) && list.get(i).live()) return list.get(i);
        Npc npc = core.findNpc(kingdomId, ref);
        if (npc != null && npc.dutyChainId != null) return core.state().chains.get(npc.dutyChainId);
        return null;
    }

    /** Linhas para a CLI/Manager. */
    public List<String> describe(WorkChain c) {
        List<String> out = new ArrayList<>();
        out.add("# Cadeia #" + c.number + " «" + c.name + "» — " + c.status.display + (c.repeat ? " · contínua" : " · tarefa única")
                + " · ciclos " + c.cycles);
        if (c.status == WorkChain.Status.BROKEN) out.add("⚠ Quebrada: " + c.brokenReason + " (nova tentativa a cada " + RETRY_SECONDS + " s; /k chain " + c.number + " resume)");
        for (int i = 0; i < c.steps.size(); i++) {
            WorkChain.Step s = c.steps.get(i);
            WorkChain.Role r = c.roles.get(s.role);
            Npc n = r == null ? null : core.npc(r.npcId);
            boolean here = r != null && r.state != WorkChain.DutyState.DONE && c.globalIndex(r) == i + 1;
            String mark = here ? (r.state == WorkChain.DutyState.WAITING ? "⏳" : "▶") : " ";
            out.add(mark + " " + (i + 1) + ". " + (n == null ? s.role : n.name) + " — " + s.describe()
                    + (here ? " · " + r.state.display + (r.status.isBlank() ? "" : ": " + r.status) : ""));
        }
        for (WorkChain.Role r : c.roles.values()) {
            Npc n = core.npc(r.npcId);
            if (n != null && !n.carrying.isEmpty()) out.add("Na mão de " + n.name + ": " + ChainValidator.summary(n.carrying));
        }
        Kingdom k = core.kingdom(c.kingdomId);
        if (k != null) {
            Set<Place> places = EnumSet.noneOf(Place.class);
            for (WorkChain.Step s : c.steps) if (s.place != null && s.place.isBuilding()) places.add(s.place);
            for (Place p : places) {
                Building b = ChainValidator.findBuilding(core, k, p, true);
                if (b != null && !b.inventory.isEmpty()) out.add("Baú " + p.da() + ": " + ChainValidator.summary(b.inventory));
            }
        }
        for (int i = Math.max(0, c.log.size() - 4); i < c.log.size(); i++) out.add("  " + c.log.get(i));
        return out;
    }

    // ------------------------------------------------------------------ util

    private static String progressText(WorkChain.Role r, WorkChain.Step s) {
        return s.type.perUnit && s.amount > 1 ? " (" + r.unitsDone + "/" + s.amount + ")" : "";
    }

    private static String heldFor(Npc n, WorkChain.Step s) {
        if (!s.type.tool.isEmpty() && (s.type != StepType.MINE || s.item != Item.COAL)) return s.type.tool;
        if (s.type == StepType.MINE) return "pickaxe";
        if (s.item != null && n.carrying.getOrDefault(s.item, 0) > 0) return s.item.name().toLowerCase(Locale.ROOT);
        for (var e : n.carrying.entrySet()) if (e.getValue() > 0) return e.getKey().name().toLowerCase(Locale.ROOT);
        return "";
    }

    private String names(WorkChain c) {
        List<String> out = new ArrayList<>();
        for (WorkChain.Role r : c.roles.values()) {
            Npc n = core.npc(r.npcId);
            if (n != null) out.add(n.name + " como " + r.name);
        }
        return String.join(", ", out);
    }

    private static void clean(Map<Item, Integer> m) {
        m.values().removeIf(v -> v == null || v <= 0);
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
