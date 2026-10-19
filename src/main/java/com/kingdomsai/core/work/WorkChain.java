package com.kingdomsai.core.work;

import com.kingdomsai.core.npc.Profession;

import java.util.*;

/**
 * Uma cadeia de trabalho já validada e em execução (é salva no mundo).
 *
 * Cada papel (minerador, ferreiro...) tem o próprio cursor e roda em paralelo: os papéis se acoplam pelos
 * baús dos prédios. Quando o ferreiro chega à forja antes do ferro, ele ESPERA (WAITING) — isso não é quebra.
 * Quebra (BROKEN) é quando a cadeia não tem como continuar: alguém morreu, mudou de profissão, o prédio sumiu.
 * Uma cadeia quebrada guarda os cursores e é retomada do ponto onde parou assim que o problema some.
 */
public final class WorkChain {
    public enum Status {
        ACTIVE("ativa"), BROKEN("quebrada"), DONE("concluída"), STOPPED("encerrada");

        public final String display;

        Status(String display) {
            this.display = display;
        }
    }

    public enum DutyState {
        MOVING("a caminho"), WORKING("trabalhando"), WAITING("esperando"), RESTING("descansando"), DONE("terminou");

        public final String display;

        DutyState(String display) {
            this.display = display;
        }
    }

    public UUID id;
    public UUID kingdomId;
    /** Número curto para a CLI (/k chain 3). */
    public int number;
    public String name = "";
    /** true = postura permanente ("dali para frente"); false = tarefa única. */
    public boolean repeat = true;
    public Status status = Status.ACTIVE;
    public long createdTick;
    public String orderText = "";
    public List<Step> steps = new ArrayList<>();
    public Map<String, Role> roles = new LinkedHashMap<>();
    public int cycles;
    public String brokenReason = "";
    public long brokenTick;
    public long lastRetryTick;
    /** O que entrou no armazém no ciclo atual (para a mensagem de ciclo concluído). */
    public Map<Item, Integer> cycleOutput = new EnumMap<>(Item.class);
    public List<String> log = new ArrayList<>();

    public static final class Step {
        public String role;
        public StepType type;
        public Item item;
        /** 0 = "tudo o que estiver na mão" (DELIVER/STORE). */
        public int amount;
        public Place place;
        /** Destinatário de carta. */
        public UUID targetNpc;
        public String targetName = "";
        public String kind = "";
        public String topic = "";
        public String text = "";
        public String title = "";

        public String describe() {
            String what = item == null ? "tudo o que tiver na mão" : (amount > 0 ? amount + " " : "") + item.display.toLowerCase();
            String s = switch (type) {
                case MINE -> "minerar " + amount + " " + item.display.toLowerCase() + " na mina";
                case CHOP -> "cortar " + amount + " toras no bosque";
                case PLANT -> "plantar a fazenda";
                case HARVEST -> "colher o trigo";
                case SMELT -> "fundir " + amount + " barras de ferro na forja";
                case FORGE -> "forjar " + amount + " espadas";
                case DELIVER -> "entregar " + what + (place == Place.RECIPIENT ? " a " + targetName : " " + place.na());
                case PICKUP -> "pegar " + what + " " + place.na();
                case STORE -> "guardar " + what + " no baú " + (place == null ? "" : place.da());
                case WRITE -> "letter".equals(kind) ? "escrever uma carta para " + targetName + (topic.isBlank() ? "" : " sobre " + topic)
                        : "escrever um livro" + (topic.isBlank() ? " (crônica do reino)" : " sobre " + topic);
                case READ -> "ler " + (title.isBlank() ? "um livro novo" : "«" + title + "»") + " na biblioteca";
            };
            return s.replaceAll("\\s+", " ").trim();
        }
    }

    public static final class Role {
        public String name;
        public UUID npcId;
        /** Profissão exigida pelo papel (para achar substituto quando a pessoa some). */
        public Profession profession;
        /** Índice dentro de {@link #stepsOf(String)}. */
        public int cursor;
        public double progress;
        public int unitsDone;
        public DutyState state = DutyState.MOVING;
        public String status = "";
        public long waitingSince;
        public boolean bottleneckWarned;
        public UUID heldDoc;
    }

    /** Índices (em steps) das etapas deste papel, na ordem. */
    public List<Integer> stepsOf(String role) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) if (steps.get(i).role.equals(role)) out.add(i);
        return out;
    }

    public Step current(Role r) {
        List<Integer> idx = stepsOf(r.name);
        if (idx.isEmpty() || r.cursor >= idx.size()) return null;
        return steps.get(idx.get(r.cursor));
    }

    /** Número global (1-based) da etapa atual do papel, para mensagens. */
    public int globalIndex(Role r) {
        List<Integer> idx = stepsOf(r.name);
        return idx.isEmpty() || r.cursor >= idx.size() ? steps.size() : idx.get(r.cursor) + 1;
    }

    public void addLog(long tick, String line) {
        log.add("[dia " + (tick / 24000 + 1) + "] " + line);
        if (log.size() > 20) log.remove(0);
    }

    public boolean live() {
        return status == Status.ACTIVE || status == Status.BROKEN;
    }
}
