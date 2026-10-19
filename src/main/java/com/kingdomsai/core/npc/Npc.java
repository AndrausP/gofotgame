package com.kingdomsai.core.npc;

import com.kingdomsai.core.common.Pos;
import com.kingdomsai.core.common.Text;

import java.util.*;

/**
 * Estado persistente de um NPC. Existe mesmo quando não há entidade no mundo (LOD):
 * a entidade do Minecraft é só a "materialização" deste registro perto do jogador.
 */
public final class Npc {
    public UUID id;
    public String name;
    public boolean female;
    public int skin;
    public UUID kingdomId;
    public Profession profession = Profession.PEASANT;
    public Office office = Office.NONE;
    public IntelligenceLevel level = IntelligenceLevel.BOT;
    public Map<Trait, Integer> traits = new EnumMap<>(Trait.class);
    public boolean alive = true;
    public long bornTick;

    /** Última posição conhecida (atualizada pela entidade quando materializada). */
    public Pos pos;
    public UUID homeId;

    /** Necessidades 0..100 (100 = satisfeito). */
    public double hunger = 80;
    public double energy = 80;

    /** Lealdade ao governante do seu reino. */
    public int loyalty = 60;
    /** Fama — sobe com feitos; NPCs famosos viram personagens importantes. */
    public int fame = 0;

    public Map<UUID, Relation> relations = new HashMap<>();
    public List<Memory> memories = new ArrayList<>();

    public NpcActivity activity = NpcActivity.IDLE;
    public UUID workBuildingId;
    /** Pedido/tarefa atual em texto curto, para debug e contexto da LLM. */
    public String currentTask = "";
    public String lastDecision = "";
    public String lastLlmCall = "";

    public transient boolean materialized;

    // --- cadeias de trabalho (core/work)
    /** Cadeia que este NPC cumpre como rotina; null = rotina da profissão. */
    public UUID dutyChainId;
    /** O que o NPC leva na mão entre uma etapa e outra. */
    public Map<com.kingdomsai.core.work.Item, Integer> carrying = new EnumMap<>(com.kingdomsai.core.work.Item.class);
    /** Ferramenta/item a mostrar na mão (o adaptador traduz: pickaxe, hoe, book, raw_iron...). */
    public String heldItem = "";
    /** Mochila de itens reais do Minecraft (ordens físicas): id → quantidade. */
    public Map<String, Integer> bag = new TreeMap<>();
    /** Ordem física em andamento (quebrar, baú, fabricar...). */
    public UUID jobId;
    /** Aprendeu a ler (lendo ou escrevendo livros). */
    public boolean literate;
    /** Livre, cativo de guerra ou escravizado (v3). */
    public Freedom freedom = Freedom.FREE;
    /** Campanha militar/colonização de que participa (v3). */
    public UUID campaignId;
    /** Desgaste da ferramenta em uso de cada tipo de item ("minecraft:iron_axe" → 37 usos) (v5). */
    public Map<String, Integer> wear = new TreeMap<>();
    /** Equipamento vestido: head/chest/legs/feet/offhand → item (v5). */
    public Map<String, String> gear = new TreeMap<>();
    /** Experiência por habilidade (lenhar, minerar, plantar, colher, construir): sobe trabalhando e acelera o trabalho (v5). */
    public Map<String, Integer> skillXp = new TreeMap<>();
    /** Arma do arsenal do reino na mão ("iron_sword") ou "" (desarmado: espada de madeira) (v4). */
    public String equipped = "";
    /** Reino de onde veio (cativos de guerra) — para devolvê-los se forem libertados (v3). */
    public UUID originKingdomId;
    // --- vida (core/life, v6): necessidades além de fome/energia, humor, saúde, laços e o que decidiu fazer
    /** Vontade de companhia (100 = satisfeito; cai sozinho, sobe conversando). */
    public double social = 70;
    /** Saúde 0..100 (fome extrema e ferimentos tiram; comer e dormir recuperam). 0 = morre. */
    public double health = 100;
    /** Humor 0..100 (necessidades, casa, amigos, luto, reino); muda o ritmo de trabalho e o tom da conversa. */
    public double mood = 60;
    /** Medo 0..100 (viu monstro, foi atacado, guerra); passa com o tempo. */
    public double fear = 0;
    /** Companheiro(a): dividem a casa e o fim de tarde. */
    public UUID partnerId;
    /** O que decidiu fazer agora (fora das ordens): comer, descansar, visitar, rezar... */
    public com.kingdomsai.core.life.Intention intention = new com.kingdomsai.core.life.Intention();
    /** Objetivo pessoal de longo prazo ("ficar amigo de Bruna", "aprender a ler", "ter uma casa"). */
    public String goal = "";
    /** Últimas falas ouvidas/ditas (para o rei ver e para a IA lembrar). */
    public List<String> recentTalk = new ArrayList<>();
    /** Quando comeu pela última vez (tick). */
    public long lastMealTick;
    /** Conversando com (null = ninguém). */
    public transient UUID talkingWith;
    /** Fugindo de uma ameaça até este tick, a partir deste ponto. */
    public transient long fleeUntil;
    public transient Pos fleeFrom;
    /** Itens que ele derrubou (mochila cheia) e vai recolher se passar perto. */
    public transient Map<String, Integer> spilled = new TreeMap<>();

    public void heard(String line) {
        if (recentTalk == null) recentTalk = new ArrayList<>();
        recentTalk.add(line);
        while (recentTalk.size() > 8) recentTalk.remove(0);
    }

    /** Trabalhando numa cadeia ativa neste segundo (a economia abstrata não conta em dobro). */
    public transient boolean onDuty;

    // --- chamado do rei (não é salvo: ao recarregar o mundo, cada um volta à rotina)
    public transient Pos summonTarget;
    public transient UUID summonedBy;
    public transient long summonUntil;
    public transient boolean following;
    public transient boolean summonArrived;

    public Npc() {}

    public int trait(Trait t) {
        return traits.getOrDefault(t, 50);
    }

    public Relation relationTo(UUID other) {
        return relations.computeIfAbsent(other, k -> new Relation());
    }

    public String title() {
        if (freedom == Freedom.CAPTIVE) return "Cativo";
        if (freedom == Freedom.ENSLAVED) return profession.display + ", escravizado";
        if (office != Office.NONE) return office.display;
        return profession.display;
    }

    /** Nível da habilidade (0..10): raiz da experiência. */
    public int skillLevel(String skill) {
        return Math.min(10, (int) Math.sqrt(skillXp.getOrDefault(skill, 0) / 10.0));
    }

    public boolean isFree() {
        return freedom == null || freedom == Freedom.FREE;
    }

    public String displayName() {
        return name + " (" + title() + ")";
    }

    public void remember(long tick, String text, int importance, UUID about, String... tags) {
        memories.add(new Memory(UUID.randomUUID(), tick, text, Text.clamp(importance, 0, 100), Set.of(tags), about));
        // Memória seletiva: mantém as mais importantes/recentes.
        if (memories.size() > 40) {
            memories.sort(Comparator.comparingDouble(m -> -(m.importance() + m.tick() / 24000.0)));
            memories = new ArrayList<>(memories.subList(0, 30));
            memories.sort(Comparator.comparingLong(Memory::tick));
        }
    }

    /** Resumo de personalidade em linguagem natural (não manda os números brutos para a LLM). */
    public String personalitySummary() {
        List<String> strong = new ArrayList<>();
        List<String> weak = new ArrayList<>();
        for (Trait t : Trait.values()) {
            int v = trait(t);
            if (v >= 75) strong.add(adjective(t, true));
            else if (v <= 25) weak.add(adjective(t, false));
        }
        StringBuilder sb = new StringBuilder(name).append(" é ");
        if (strong.isEmpty() && weak.isEmpty()) sb.append("uma pessoa equilibrada");
        else {
            List<String> all = new ArrayList<>(strong);
            all.addAll(weak);
            sb.append(String.join(", ", all));
        }
        return sb.append('.').toString();
    }

    private String adjective(Trait t, boolean high) {
        String a = switch (t) {
            case COURAGE -> high ? "corajoso" : "medroso";
            case AMBITION -> high ? "ambicioso" : "sem ambição";
            case LOYALTY -> high ? "extremamente leal" : "pouco leal";
            case GREED -> high ? "ganancioso" : "desapegado";
            case AGGRESSION -> high ? "agressivo" : "pacífico";
            case RELIGIOSITY -> high ? "muito religioso" : "cético";
            case HONESTY -> high ? "honesto" : "mentiroso";
            case CURIOSITY -> high ? "curioso" : "desinteressado";
            case SOCIABILITY -> high ? "sociável" : "reservado";
            case DISCIPLINE -> high ? "disciplinado" : "indisciplinado";
        };
        if (female) {
            a = a.replace("corajoso", "corajosa").replace("medroso", "medrosa").replace("ambicioso", "ambiciosa")
                    .replace("ganancioso", "gananciosa").replace("agressivo", "agressiva").replace("pacífico", "pacífica")
                    .replace("religioso", "religiosa").replace("cético", "cética").replace("mentiroso", "mentirosa")
                    .replace("curioso", "curiosa").replace("desinteressado", "desinteressada").replace("reservado", "reservada")
                    .replace("disciplinado", "disciplinada").replace("indisciplinado", "indisciplinada").replace("desapegado", "desapegada")
                    .replace("honesto", "honesta").replace("medroso", "medrosa");
        }
        return a;
    }
}
