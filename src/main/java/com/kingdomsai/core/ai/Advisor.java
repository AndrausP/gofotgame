package com.kingdomsai.core.ai;

import com.kingdomsai.core.KingdomsCore;
import com.kingdomsai.core.common.Text;
import com.kingdomsai.core.construction.Building;
import com.kingdomsai.core.diplomacy.Diplomacy;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.kingdom.ResourceType;
import com.kingdomsai.core.npc.Npc;
import com.kingdomsai.core.npc.Profession;

import java.util.*;

/**
 * Conselheiro determinístico. Responde "por que o reino está perdendo dinheiro?" com base nos números.
 * Funciona sem LLM; quando a LLM está ligada, estes fatos viram o contexto dela.
 */
public final class Advisor {
    private final KingdomsCore core;

    public Advisor(KingdomsCore core) {
        this.core = core;
    }

    public List<String> facts(Kingdom k) {
        List<String> f = new ArrayList<>();
        Map<ResourceType, Double> d = core.economy().projected(k);
        int pop = core.population(k.id);
        f.add("População " + pop + ", moradias " + core.housingCapacity(k.id) + ".");
        StringBuilder res = new StringBuilder("Estoques: ");
        for (ResourceType r : ResourceType.values())
            res.append(r.display).append(' ').append(Text.fmt(k.get(r))).append(" (").append(d.get(r) >= 0 ? "+" : "").append(Text.fmt(d.get(r))).append("/tick), ");
        f.add(res.substring(0, res.length() - 2) + ".");
        StringBuilder jobs = new StringBuilder("Profissões: ");
        for (Profession p : Profession.values()) {
            int c = core.count(k.id, p);
            if (c > 0) jobs.append(p.display).append(' ').append(c).append(", ");
        }
        f.add(jobs.substring(0, jobs.length() - 2) + ".");
        f.add("Estabilidade " + (int) k.stability + ", moral " + (int) k.morale + ", impostos nível " + k.laws.taxLevel + ".");
        List<Building> proj = core.construction().projects(k.id);
        if (!proj.isEmpty()) {
            StringBuilder pb = new StringBuilder("Obras: ");
            for (Building b : proj) pb.append(b.blueprint().displayName()).append(' ').append((int) b.percent()).append("%, ");
            f.add(pb.substring(0, pb.length() - 2) + ".");
        }
        for (Kingdom o : core.state().kingdoms.values()) {
            if (o == k) continue;
            Diplomacy.Attitude att = core.diplomacy().attitude(o.id, k.id);
            f.add(o.name + ": " + core.diplomacy().link(k.id, o.id).state.display + ", " + att.label()
                    + ", militares " + core.military(o.id) + ", população " + core.population(o.id) + ".");
        }
        return f;
    }

    public String answer(Kingdom k, String question) {
        String q = Text.norm(question);
        if (q.contains("comida") || q.contains("fome") || q.contains("food") || q.contains("aliment")) return food(k);
        if (q.contains("dinheiro") || q.contains("ouro") || q.contains("tesour") || q.contains("money") || q.contains("gold")) return gold(k);
        if (q.contains("estabil") || q.contains("revolt") || q.contains("felic") || q.contains("moral")) return stability(k);
        if (q.contains("exerc") || q.contains("soldad") || q.contains("militar") || q.contains("defes")) return military(k);
        if (q.contains("casa") || q.contains("morad") || q.contains("popul")) return housing(k);
        if (q.contains("vizinh") || q.contains("reino") || q.contains("diplom") || q.contains("guerra") || q.contains("inimig")) return neighbors(k);
        return explain(k);
    }

    public String explain(Kingdom k) {
        List<String> issues = new ArrayList<>();
        double ft = core.economy().foodTicksLeft(k);
        if (k.famine || ft < 40) issues.add(food(k));
        int pop = core.population(k.id), cap = core.housingCapacity(k.id);
        if (pop > cap) issues.add(housing(k));
        if (k.stability < 50) issues.add(stability(k));
        if (core.economy().projected(k).get(ResourceType.GOLD) < 0) issues.add(gold(k));
        if (issues.isEmpty()) return "Majestade, o reino está estável. Sugiro expandir moradias e preparar defesas antes que os vizinhos cresçam.";
        return String.join(" ", issues);
    }

    private String food(Kingdom k) {
        Map<ResourceType, Double> d = core.economy().projected(k);
        double perTick = d.get(ResourceType.FOOD);
        int farmers = core.count(k.id, Profession.FARMER);
        int pop = core.population(k.id);
        int farms = core.completedOf(k.id, "farm");
        if (perTick >= 0)
            return "A comida está estável (+" + Text.fmt(perTick) + " por ciclo). Temos " + farmers + " fazendeiros para " + pop + " pessoas.";
        double minutes = k.get(ResourceType.FOOD) / -perTick * core.config().economicTickSeconds / 60.0;
        StringBuilder sb = new StringBuilder("Consumimos mais do que produzimos (" + Text.fmt(perTick) + " por ciclo); o estoque dura ~"
                + Text.fmt(minutes) + " min. Temos só " + farmers + " fazendeiros para " + pop + " bocas");
        int military = core.military(k.id);
        if (military > 2) sb.append(", e ").append(military).append(" militares comem sem produzir");
        sb.append(". ");
        if (farms == 0) sb.append("Construir uma fazenda aumenta em 60% a produção de até 3 fazendeiros. ");
        int needed = (int) Math.ceil(-perTick / 2.7);
        sb.append("Recomendo mover ").append(Math.max(1, needed)).append(" pessoa(s) para a lavoura.");
        return sb.toString();
    }

    private String gold(Kingdom k) {
        Map<ResourceType, Double> d = core.economy().projected(k);
        int mil = core.military(k.id);
        return "O tesouro varia " + Text.fmt(d.get(ResourceType.GOLD)) + " por ciclo. Impostos nível " + k.laws.taxLevel
                + " rendem ~" + Text.fmt(core.population(k.id) * k.laws.taxLevel * 0.12) + "; cada militar custa 0,4 de manutenção ("
                + mil + " militares). Mercadores geram ouro: temos " + core.count(k.id, Profession.MERCHANT) + ".";
    }

    private String stability(Kingdom k) {
        List<String> causes = new ArrayList<>();
        if (k.famine) causes.add("a fome");
        if (k.shortageWarned) causes.add("o medo da escassez");
        if (k.laws.taxLevel >= 3) causes.add("impostos altos");
        int homeless = core.population(k.id) - core.housingCapacity(k.id);
        if (homeless > 0) causes.add(homeless + " pessoas sem casa");
        if (k.laws.conscription) causes.add("o serviço militar obrigatório");
        if (causes.isEmpty()) causes.add("nada grave no momento");
        return "A estabilidade está em " + (int) k.stability + " e a moral em " + (int) k.morale + ". O que pesa: " + String.join(", ", causes) + ".";
    }

    private String military(Kingdom k) {
        StringBuilder sb = new StringBuilder("Temos " + core.count(k.id, Profession.SOLDIER) + " soldados e " + core.count(k.id, Profession.GUARD)
                + " guardas, com " + Text.fmt(k.get(ResourceType.WEAPONS)) + " armas no estoque. ");
        for (Kingdom o : core.state().kingdoms.values()) {
            if (o == k) continue;
            sb.append(o.name).append(" tem ").append(core.military(o.id)).append(" militares. ");
        }
        return sb.toString().trim();
    }

    private String housing(Kingdom k) {
        int pop = core.population(k.id), cap = core.housingCapacity(k.id);
        long pending = core.construction().projects(k.id).stream().filter(b -> b.blueprint().housing() > 0).count();
        return "Há " + pop + " moradores para " + cap + " vagas. " + (pending > 0 ? pending + " moradia(s) em obra. " : "")
                + (pop >= cap ? "Sem casas novas a população não cresce; uma casa pequena abriga 3 (30 madeira, 10 pedra)." : "Ainda há espaço para crescer.");
    }

    private String neighbors(Kingdom k) {
        StringBuilder sb = new StringBuilder();
        for (Kingdom o : core.state().kingdoms.values()) {
            if (o == k) continue;
            Diplomacy.Attitude att = core.diplomacy().attitude(o.id, k.id);
            sb.append(o.name).append(" (").append(o.personality.summary()).append(") ").append(att.label())
                    .append(" — hostilidade ").append((int) att.hostility).append(", confiança ").append((int) att.trust)
                    .append(", medo ").append((int) att.fear).append(". ");
        }
        return sb.isEmpty() ? "Não conhecemos outros reinos ainda." : sb.toString().trim();
    }

    /** Conselheiro do reino (NPC com cargo de conselheiro, se existir). */
    public Npc advisorNpc(Kingdom k) {
        for (Npc n : core.citizens(k.id)) if (n.office == com.kingdomsai.core.npc.Office.ADVISOR) return n;
        for (Npc n : core.citizens(k.id)) if (n.office != com.kingdomsai.core.npc.Office.NONE) return n;
        return null;
    }
}
