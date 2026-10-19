package com.kingdomsai.core.life;

import com.kingdomsai.core.KingdomsCore;
import com.kingdomsai.core.common.Text;
import com.kingdomsai.core.diplomacy.Diplomacy;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.npc.*;

import java.util.*;

/**
 * Conversa entre dois súditos, por regras (sem LLM): o assunto sai do que eles vivem — um boato que um sabe e o outro não,
 * fome/cansaço/casa, o reino (guerra, fome, impostos, rei cruel), o trabalho de cada um, amizade, rivalidade, namoro, fé.
 * O tom muda com a personalidade (coragem, lealdade, agressividade, ganância) e o gênero de quem fala.
 */
public final class Talk {
    public enum Topic { GOSSIP, NEEDS, KINGDOM, WORK, FRIENDSHIP, ARGUMENT, ROMANCE, FAITH, SMALLTALK }

    public record Line(UUID speaker, String text) {}

    /** O roteiro da conversa e o que ela muda (boato passado adiante, briga, namoro). */
    public static final class Script {
        public Topic topic = Topic.SMALLTALK;
        public final List<Line> lines = new ArrayList<>();
        /** Lembrança que A conta para B (vira boato na memória de B). */
        public Memory gossip;
        public boolean argument, romance;

        void say(Npc who, String text) {
            lines.add(new Line(who.id, text));
        }
    }

    private Talk() {}

    public static Script compose(KingdomsCore core, Npc a, Npc b, Random rng) {
        Kingdom k = core.kingdom(a.kingdomId);
        Relation ab = a.relationTo(b.id), ba = b.relationTo(a.id);
        Memory gossip = gossipFor(core, a, b);
        List<Topic> options = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        boolean peace = k != null && core.civic().has(k, com.kingdomsai.core.civic.Decree.PEACE) && a.trait(Trait.AGGRESSION) <= 85;
        if (!peace && (ab.rivalry > 60 || a.trait(Trait.AGGRESSION) > 70 && ab.rivalry > 40)) add(options, weights, Topic.ARGUMENT, 3.0);
        if (ab.affection > 75 && ba.affection > 70 && a.partnerId == null && b.partnerId == null && ab.trust > 55)
            add(options, weights, Topic.ROMANCE, 2.5);
        if (a.partnerId != null && a.partnerId.equals(b.id)) add(options, weights, Topic.ROMANCE, 1.5);
        if (gossip != null) add(options, weights, Topic.GOSSIP, 2.5);
        if (a.hunger < 40 || a.energy < 25 || a.health < 50 || Places.home(core, a) == null && k != null && core.population(k.id) > core.housingCapacity(k.id))
            add(options, weights, Topic.NEEDS, 1.5);
        if (k != null && (core.diplomacy().atWar(k.id) || k.famine || k.infamy > 30 || k.laws.taxLevel >= 3 || k.stability < 40))
            add(options, weights, Topic.KINGDOM, 1.6);
        else if (k != null) add(options, weights, Topic.KINGDOM, 0.4);
        if (a.trait(Trait.RELIGIOSITY) > 65 && b.trait(Trait.RELIGIOSITY) > 55) add(options, weights, Topic.FAITH, 1.0);
        add(options, weights, Topic.WORK, a.profession == b.profession ? 1.4 : 0.7);
        if (ab.affection > 60) add(options, weights, Topic.FRIENDSHIP, 1.0);
        add(options, weights, Topic.SMALLTALK, 0.8);

        double total = weights.stream().mapToDouble(Double::doubleValue).sum(), r = rng.nextDouble() * total;
        Topic topic = options.get(options.size() - 1);
        for (int i = 0; i < options.size(); i++) {
            r -= weights.get(i);
            if (r <= 0) {
                topic = options.get(i);
                break;
            }
        }
        Script s = new Script();
        s.topic = topic;
        switch (topic) {
            case GOSSIP -> gossip(s, a, b, gossip, rng);
            case NEEDS -> needs(core, s, a, b, k, rng);
            case KINGDOM -> kingdom(core, s, a, b, k, rng);
            case WORK -> work(core, s, a, b, rng);
            case FRIENDSHIP -> {
                s.say(a, pick(rng, "Bom te ver, " + b.name + "!", "Olha quem apareceu! Como vai, " + b.name + "?", "E aí, " + b.name + ", tudo certo?"));
                s.say(b, pick(rng, "Digo o mesmo, " + g(b, "amigo", "amiga") + "!", "Tudo na paz. E você?", "Melhor agora!"));
                if (rng.nextBoolean()) {
                    s.say(a, pick(rng, "Vamos nos ver mais tarde na praça?", "Qualquer dia te pago uma caneca na taverna."));
                    s.say(b, pick(rng, "Claro!", "Combinado.", "Vou cobrar, hein!"));
                }
            }
            case ARGUMENT -> {
                s.argument = true;
                s.say(a, pick(rng, "Você de novo, " + b.name + "?", "Ainda está me devendo aquela, " + b.name + ".", "Não gosto do jeito que você me olha."));
                if (b.trait(Trait.AGGRESSION) > 60) {
                    s.say(b, pick(rng, "Algum problema comigo?", "Cuida da sua vida!", "Repete isso se tiver coragem."));
                    s.say(a, pick(rng, "Vários.", "Não me provoca.", "Um dia a gente resolve isso."));
                    s.say(b, pick(rng, "Quando quiser!", "Vai embora daqui.", "Hunf."));
                } else {
                    s.say(b, pick(rng, "Não quero confusão.", "Me deixa em paz, por favor.", "Hoje não."));
                }
            }
            case ROMANCE -> {
                s.romance = true;
                boolean couple = a.partnerId != null && a.partnerId.equals(b.id);
                if (couple) {
                    s.say(a, pick(rng, "Senti sua falta hoje.", "Como foi o seu dia, meu bem?", "Vamos pra casa juntos?"));
                    s.say(b, pick(rng, "Eu também, " + a.name + ".", "Cansativo, mas agora melhorou.", "Vamos sim."));
                } else {
                    s.say(a, pick(rng, "Você está muito " + g(b, "bonito", "bonita") + " hoje, " + b.name + ".", "Sempre gosto de conversar com você.",
                            "Pensei em você o dia todo..."));
                    s.say(b, ba.affection > 80 ? pick(rng, "Só hoje? (risos)", "Eu também gosto, " + a.name + ".", "Você me deixa sem graça...")
                            : pick(rng, "Obrigad" + g(b, "o", "a") + "...", "Que gentileza."));
                    if (ba.affection > 80) {
                        s.say(a, pick(rng, "Quer caminhar comigo depois?", "Podia te acompanhar até em casa."));
                        s.say(b, pick(rng, "Eu adoraria.", "Quero sim."));
                    }
                }
            }
            case FAITH -> {
                s.say(a, pick(rng, "Que os deuses protejam o reino.", "Rezei por todos nós hoje.", "Precisamos de fé nesses tempos."));
                s.say(b, pick(rng, "Amém. Vou à capela mais tarde.", "Que assim seja.", "A fé nos mantém de pé."));
            }
            case SMALLTALK -> smalltalk(core, s, a, b, rng);
        }
        if (s.lines.size() < 4 && rng.nextInt(3) == 0) {
            s.say(a, pick(rng, "Bom, vou indo.", "Preciso voltar ao que estava fazendo.", "Até mais!"));
            s.say(b, pick(rng, "Até mais!", "Vai com Deus.", "Até!"));
        }
        return s;
    }

    // ------------------------------------------------------------------ assuntos

    /** Uma lembrança forte e recente de A que B ainda não sabe (o boato que corre a vila). */
    static Memory gossipFor(KingdomsCore core, Npc a, Npc b) {
        Memory best = null;
        long now = core.tick();
        for (Memory m : a.memories) {
            if (m.importance() < 35 || now - m.tick() > 72000 || m.tags().contains("privado")) continue;
            if (b.id.equals(m.about())) continue; // não fofoca da pessoa para ela mesma
            String c = core(m.text());
            boolean known = false;
            for (Memory o : b.memories)
                if (core(o.text()).equals(c)) {
                    known = true;
                    break;
                }
            if (known) continue;
            if (best == null || m.importance() > best.importance() || m.importance() == best.importance() && m.tick() > best.tick()) best = m;
        }
        return best;
    }

    /** O fato sem o "Fulano me contou:" (para não contar o mesmo boato duas vezes). */
    public static String core(String text) {
        int i = text.indexOf(" me contou: ");
        String c = i >= 0 ? text.substring(i + 12) : text;
        return c.replaceAll("^\"|\"$", "");
    }

    private static void gossip(Script s, Npc a, Npc b, Memory m, Random rng) {
        s.gossip = m;
        String fact = core(m.text());
        boolean rumor = m.text().contains(" me contou: ");
        String teller = rumor ? m.text().substring(0, m.text().indexOf(" me contou: ")) : null;
        s.say(a, rumor ? pick(rng, teller + " me contou: \"" + fact + "\"", "Ouvi dizer, da boca de " + teller + ": \"" + fact + "\"")
                : pick(rng, "Sabe o que aconteceu? " + fact, "Preciso te contar: " + fact, "Você não vai acreditar: " + fact));
        String t = Text.norm(fact);
        String reply;
        if (t.matches(".*\\b(morreu|morte|luto|falta)\\b.*")) reply = pick(rng, "Que tristeza... que descanse em paz.", "Meus pêsames.");
        else if (t.matches(".*\\b(zumbi|esqueleto|aranha|monstro|lobo|creeper|atacad\\w*|bruxa)\\b.*"))
            reply = b.trait(Trait.COURAGE) < 40 ? pick(rng, "Credo! Não saio mais de casa à noite.", "Que medo... vou trancar a porta.")
                    : pick(rng, "Se aparecer de novo, a gente enfrenta.", "Os guardas precisam saber disso.");
        else if (t.matches(".*\\b(massacr\\w*|execut\\w*|escraviz\\w*|matou|matar)\\b.*"))
            reply = pick(rng, "Que horror... melhor nem comentar isso alto.", "Deus nos proteja de um rei assim.");
        else if (t.matches(".*\\brei\\b.*") && t.matches(".*\\b(mandou|ordem|ordenou|bateu)\\b.*"))
            reply = t.contains("bateu") ? pick(rng, "O rei fez isso?! Que vergonha.", "Ninguém está seguro então...")
                    : b.loyalty >= 60 ? pick(rng, "Ordem do rei é ordem.", "O rei sabe o que faz.") : pick(rng, "Esse rei só sabe mandar...", "Hum. Sempre sobra pra gente.");
        else if (t.matches(".*\\b(casa|morar)\\b.*")) reply = pick(rng, "Que bom! Todo mundo merece um teto.", "Fico feliz por você.");
        else if (t.matches(".*\\b(guerra|ataque|invad\\w*|batalha)\\b.*"))
            reply = b.trait(Trait.COURAGE) > 60 ? pick(rng, "Que venham!", "Estamos prontos.") : pick(rng, "Tenho medo por nossas famílias.", "Tomara que acabe logo.");
        else if (t.matches(".*\\b(juntos|namor\\w*|casad\\w*)\\b.*")) reply = pick(rng, "Que bonito! Formam um belo par.", "Já era hora!");
        else reply = pick(rng, "Não diga!", "Sério? Não sabia disso.", "Hum... faz sentido.", "Vou ficar de olho.", "Quem diria!");
        s.say(b, reply);
    }

    private static void needs(KingdomsCore core, Script s, Npc a, Npc b, Kingdom k, Random rng) {
        if (a.hunger < 40) {
            s.say(a, pick(rng, "Estou com uma fome danada.", "A barriga não para de roncar."));
            s.say(b, k != null && k.famine ? pick(rng, "Todo mundo está. O celeiro está quase vazio.", "Nem me fale, a comida está racionada.")
                    : pick(rng, "Passa no armazém, ainda tem pão.", "Já está na hora de comer mesmo."));
        } else if (a.health < 50) {
            s.say(a, "Ainda estou " + g(a, "machucado", "machucada") + "...");
            s.say(b, pick(rng, "Se cuida. Descansa um pouco.", "Melhoras! Não força."));
        } else if (a.energy < 25) {
            s.say(a, pick(rng, "Trabalhei o dia todo, estou " + g(a, "morto", "morta") + " de cansaço.", "Não aguento mais, preciso dormir."));
            s.say(b, pick(rng, "Vai descansar, amanhã tem mais.", "Ninguém é de ferro."));
        } else {
            s.say(a, pick(rng, "Ainda durmo ao relento, sem casa.", "Queria tanto ter uma casa..."));
            s.say(b, pick(rng, "O rei precisa mandar construir mais casas.", "Paciência, os construtores estão trabalhando."));
        }
    }

    private static void kingdom(KingdomsCore core, Script s, Npc a, Npc b, Kingdom k, Random rng) {
        String enemy = null;
        for (Diplomacy.Link l : core.state().links.values())
            if (l.state == Diplomacy.State.WAR && (k.id.equals(l.a) || k.id.equals(l.b))) {
                Kingdom e = core.kingdom(k.id.equals(l.a) ? l.b : l.a);
                if (e != null) enemy = e.name;
            }
        if (enemy != null) {
            s.say(a, pick(rng, "Dizem que estamos em guerra com " + enemy + ".", "Ouvi que " + enemy + " quer nossas terras."));
            s.say(b, b.trait(Trait.COURAGE) > 60 ? pick(rng, "Que venham! Nós damos conta.", "Nossos soldados vão mostrar quem manda.")
                    : pick(rng, "Tenho medo por nossas famílias.", "Tomara que a guerra não chegue aqui."));
        } else if (k.famine) {
            s.say(a, pick(rng, "O celeiro está vazio... como vamos passar o inverno?", "Tem gente indo embora por causa da fome."));
            s.say(b, pick(rng, "Se não plantarem mais, vai faltar pra todo mundo.", "Precisamos de mais fazendeiros."));
        } else if (k.infamy > 30) {
            s.say(a, pick(rng, "O rei anda cruel. Cuidado com o que fala.", "Depois do que aconteceu, tenho medo do rei."));
            s.say(b, b.loyalty >= 65 ? pick(rng, "Ele faz o que precisa.", "Não fale assim do rei.") : pick(rng, "Falo baixo, mas penso o mesmo.", "Shh... as paredes têm ouvidos."));
        } else if (k.laws.taxLevel >= 3) {
            s.say(a, pick(rng, "Os impostos estão pesados demais.", "Mal sobra uma moeda depois do imposto."));
            s.say(b, b.trait(Trait.GREED) > 60 ? pick(rng, "Nem me fale.", "Um absurdo!") : pick(rng, "É o preço de ter um reino seguro.", "Pelo menos as obras andam."));
        } else if (k.stability < 40) {
            s.say(a, pick(rng, "O reino anda agitado. Tem gente falando em ir embora.", "As coisas não andam bem por aqui."));
            s.say(b, b.loyalty >= 50 ? pick(rng, "Eu fico. Isso aqui ainda é nosso lar.", "Vai melhorar.") : pick(rng, "Eu também penso nisso...", "Se piorar, eu vou."));
        } else {
            int pop = core.population(k.id);
            s.say(a, pick(rng, "O reino está crescendo, já somos " + pop + ".", "Gosto de morar em " + k.name + "."));
            s.say(b, pick(rng, "Dá gosto de ver.", "Que continue assim."));
        }
    }

    private static void work(KingdomsCore core, Script s, Npc a, Npc b, Random rng) {
        String line = switch (a.profession) {
            case LUMBERJACK -> pick(rng, "O bosque está ficando ralo de tanto que cortei.", "Hoje derrubei umas árvores bem grossas.", "Meu machado já está cego.");
            case MINER -> pick(rng, "Achei um veio de ferro na galeria hoje.", "Lá embaixo é escuro, ainda bem que tenho tochas.", "A picareta não aguenta muito mais.");
            case FARMER -> pick(rng, "O trigo está crescendo bem.", "Arei a terra toda hoje, minhas costas doem.", "Se chover, a colheita vai ser boa.");
            case BUILDER -> pick(rng, "Falta pouco para terminar a obra.", "Pedra pesada, mas a casa vai ficar firme.");
            case BLACKSMITH -> pick(rng, "A forja está quente o dia todo.", "Forjei ferramentas novas para o armazém.");
            case GUARD -> pick(rng, "A ronda está tranquila.", "Fico de olho na borda da vila, pode dormir sossegad" + g(b, "o", "a") + ".");
            case SOLDIER -> pick(rng, "O treino hoje foi puxado.", "Minha espada está afiada, se precisarem.");
            case MERCHANT -> pick(rng, "Os negócios andam bem.", "Todo mundo quer comprar e ninguém quer pagar.");
            case PRIEST -> pick(rng, "Rezei pelo reino hoje.", "Muita gente veio se confessar.");
            case SCHOLAR -> pick(rng, "Estou lendo um livro fascinante.", "Anotei tudo o que aconteceu na vila esta semana.");
            default -> pick(rng, "Ajudei um pouco de tudo hoje.", "Carreguei muita coisa hoje.");
        };
        if (a.jobId != null && core.state().jobs.containsKey(a.jobId) && core.state().jobs.get(a.jobId).produced > 0)
            line += " Já levei " + core.state().jobs.get(a.jobId).produced + " ao armazém.";
        s.say(a, line);
        s.say(b, a.profession == b.profession ? pick(rng, "Nem me fale, comigo é igual.", "Cada dia mais trabalho.") :
                pick(rng, "Bom trabalho.", "Cada um faz sua parte.", "Isso é bom para o reino."));
    }

    private static void smalltalk(KingdomsCore core, Script s, Npc a, Npc b, Random rng) {
        long t = Math.floorMod(core.world().dayTime(), 24000L);
        if (t >= 23000 || t < 4000) {
            s.say(a, "Bom dia, " + b.name + "!");
            s.say(b, pick(rng, "Bom dia! Dormiu bem?", "Bom dia! Que cedo, hein."));
            s.say(a, pick(rng, "Como uma pedra.", "Mais ou menos..."));
        } else if (t < 9000) {
            s.say(a, pick(rng, "Que dia bonito.", "Que sol forte hoje."));
            s.say(b, pick(rng, "Bom para secar o trigo.", "Melhor que chuva."));
        } else if (t < 13000) {
            s.say(a, pick(rng, "Que dia longo...", "Finalmente a tarde está acabando."));
            s.say(b, pick(rng, "Nem me fale. Ainda bem que acabou.", "Hora de descansar."));
        } else {
            s.say(a, "Ainda " + g(a, "acordado", "acordada") + ", " + b.name + "?");
            s.say(b, pick(rng, "Sem sono hoje.", "Já estou indo deitar."));
        }
    }

    // ------------------------------------------------------------------ util

    private static void add(List<Topic> o, List<Double> w, Topic t, double weight) {
        o.add(t);
        w.add(weight);
    }

    static String pick(Random rng, String... options) {
        return options[rng.nextInt(options.length)];
    }

    /** Concordância de gênero de quem é descrito. */
    static String g(Npc n, String masc, String fem) {
        return n.female ? fem : masc;
    }
}
