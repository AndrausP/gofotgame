package com.kingdomsai.core.diplomacy;

import com.kingdomsai.core.KingdomsCore;
import com.kingdomsai.core.common.Text;
import com.kingdomsai.core.diplomacy.Diplomacy.*;
import com.kingdomsai.core.economy.EconomySystem;
import com.kingdomsai.core.event.EventType;
import com.kingdomsai.core.event.GameEvent;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.kingdom.ResourceType;

import java.util.*;

/**
 * Diplomacia simples (MVP): atitudes (confiança, medo, respeito, hostilidade), estado da relação,
 * tratados, comércio e presentes. Os outros reinos lembram o que você fez.
 */
public final class DiplomacySystem {
    private final KingdomsCore core;
    private final Map<UUID, Integer> lastMilitary = new HashMap<>();

    public DiplomacySystem(KingdomsCore core) {
        this.core = core;
    }

    public Link link(UUID a, UUID b) {
        return core.state().links.computeIfAbsent(Diplomacy.pairKey(a, b), k -> {
            Link l = new Link();
            l.a = a;
            l.b = b;
            return l;
        });
    }

    public Attitude attitude(UUID from, UUID to) {
        return core.state().attitudes.computeIfAbsent(Diplomacy.dirKey(from, to), k -> new Attitude());
    }

    public boolean atWar(UUID k) {
        for (Link l : core.state().links.values())
            if (l.state == State.WAR && (k.equals(l.a) || k.equals(l.b))) return true;
        return false;
    }

    public List<Treaty> treaties(UUID a, UUID b) {
        List<Treaty> out = new ArrayList<>();
        for (Treaty t : core.state().treaties) if (t.involves(a) && t.involves(b)) out.add(t);
        return out;
    }

    public boolean hasTreaty(UUID a, UUID b, TreatyType type) {
        for (Treaty t : treaties(a, b)) if (t.type == type) return true;
        return false;
    }

    private long lastTreatyDay = -1;

    public void tick() {
        List<Kingdom> ks = new ArrayList<>(core.state().kingdoms.values());
        core.state().treaties.removeIf(t -> t.expiresTick > 0 && t.expiresTick < core.tick());
        long day = core.tick() / 24000L;
        if (day != lastTreatyDay) {
            if (lastTreatyDay >= 0) enforceTreaties();
            lastTreatyDay = day;
        }
        for (Kingdom x : ks) {
            int mil = core.military(x.id);
            int prev = lastMilitary.getOrDefault(x.id, mil);
            lastMilitary.put(x.id, mil);
            for (Kingdom y : ks) {
                if (x == y) continue;
                update(x, y, mil - prev);
            }
        }
        for (int i = 0; i < ks.size(); i++)
            for (int j = i + 1; j < ks.size(); j++) updateState(ks.get(i), ks.get(j));
    }

    /** Atualiza a atitude de y em relação a x (y observa x). */
    private void update(Kingdom x, Kingdom y, int militaryGrowthOfX) {
        Attitude att = attitude(y.id, x.id);
        Link l = link(x.id, y.id);
        boolean touching = core.state().territory.bordersTouch(x.id, y.id);
        double dist = x.center.distXZ(y.center);
        if (!l.contact && (touching || dist < 450)) {
            l.contact = true;
            if (x.isPlayerKingdom())
                core.bus().publish(core.tick(), EventType.BORDER_CONTACT, GameEvent.Severity.INFO, x.id, null,
                        "Contato estabelecido com " + y.name + " (" + y.personality.summary() + ").",
                        Map.of("other", y.id.toString()));
        }
        int milX = core.military(x.id), milY = core.military(y.id);
        double fearTarget = Text.clamp(50.0 * (milX + 1) / (milY + 1) - 10, 0, 100);
        att.fear += (fearTarget - att.fear) * 0.2;
        double hostDrift = (y.personality.militarism - 50) / 100.0 + (touching ? 0.8 : 0) - y.personality.diplomacy / 120.0
                + (y.personality.expansionism > 65 && touching ? 0.5 : 0)
                - (hasTreaty(x.id, y.id, TreatyType.TRADE_AGREEMENT) ? 0.8 : 0)
                - (hasTreaty(x.id, y.id, TreatyType.NON_AGGRESSION) ? 0.5 : 0)
                + (militaryGrowthOfX > 0 ? militaryGrowthOfX * 1.5 : 0)
                + (100 - x.honor) / 200.0;
        att.hostility = Text.clamp(att.hostility + hostDrift, 0, 100);
        att.respect = Text.clamp(att.respect + ((core.population(x.id) - core.population(y.id)) * 0.05) + (x.honor - 50) / 100.0, 0, 100);
        if (l.state == State.PEACE && att.hostility < 40) att.trust = Text.clamp(att.trust + 0.3, 0, 100);
        att.tradeDependence = Text.clamp(att.tradeDependence - 0.2, 0, 100);

        if (militaryGrowthOfX >= 2 && x.isPlayerKingdom() && att.hostility > 45) {
            core.bus().publish(core.tick(), EventType.MILITARY_BUILDUP, GameEvent.Severity.WARN, x.id, null,
                    "Diplomatas relatam: " + y.name + " observa o crescimento do seu exército com desconfiança.",
                    Map.of("other", y.id.toString()));
        }
    }

    private void updateState(Kingdom a, Kingdom b) {
        Link l = link(a.id, b.id);
        if (l.state == State.WAR) return; // guerra só termina por ação (MAKE_PEACE)
        double h = Math.max(attitude(a.id, b.id).hostility, attitude(b.id, a.id).hostility);
        State next = l.state;
        if (l.state == State.ARMISTICE) {
            if (core.tick() - l.stateSince > 24000) next = State.PEACE;
        } else if (h > 78) next = State.HOSTILE;
        else if (h > 58) next = State.TENSION;
        else if (h < 50) next = State.PEACE;
        else if (l.state == State.HOSTILE && h < 70) next = State.TENSION;
        if (next != l.state) setState(l, a, b, next);
    }

    public void setState(Link l, Kingdom a, Kingdom b, State next) {
        State prev = l.state;
        l.state = next;
        l.stateSince = core.tick();
        GameEvent.Severity sev = next.ordinal() > prev.ordinal() && next != State.ARMISTICE ? GameEvent.Severity.WARN : GameEvent.Severity.GOOD;
        Map<String, String> data = Map.of("a", a.id.toString(), "b", b.id.toString(), "state", next.name());
        String msg = "Relação " + a.name + " ↔ " + b.name + ": " + prev.display + " → " + next.display;
        core.bus().publish(core.tick(), EventType.DIPLOMACY_CHANGED, sev, a.id, null, msg, data);
        core.bus().publish(core.tick(), EventType.DIPLOMACY_CHANGED, sev, b.id, null, msg, data);
    }

    // ------------------------------------------------------------ ações diplomáticas

    public String declareWar(Kingdom from, Kingdom to) {
        Link l = link(from.id, to.id);
        int broken = 0;
        for (Iterator<Treaty> it = core.state().treaties.iterator(); it.hasNext(); ) {
            Treaty t = it.next();
            if (t.involves(from.id) && t.involves(to.id)) {
                it.remove();
                broken++;
                publishBroken(t, from, to, "guerra declarada por " + from.name);
            }
        }
        if (broken > 0) {
            from.reliability = Text.clamp(from.reliability - 15 * broken, 0, 100);
            for (Kingdom k : core.state().kingdoms.values())
                if (k != from) attitude(k.id, from.id).trust = Text.clamp(attitude(k.id, from.id).trust - 8 * broken, 0, 100);
        }
        setState(l, from, to, State.WAR);
        Attitude att = attitude(to.id, from.id);
        att.hostility = 100;
        att.trust = 0;
        core.bus().publish(core.tick(), EventType.WAR_DECLARED, GameEvent.Severity.DANGER, from.id, null,
                from.name + " declarou guerra a " + to.name + "!", Map.of("target", to.id.toString(), "from", from.id.toString(), "to", to.id.toString()));
        core.chronicle(from.name + " declarou guerra a " + to.name + ".");
        return "Guerra declarada contra " + to.name + (broken > 0 ? " (" + broken + " tratado(s) rompido(s) — sua confiabilidade caiu)" : "")
                + ". Batalhas chegam na Fase 9 (Military); por enquanto a guerra afeta moral, diplomacia e o recrutamento inimigo.";
    }

    /** Retorna null se aceito, ou o motivo da recusa. */
    public String proposePeace(Kingdom from, Kingdom to) {
        Link l = link(from.id, to.id);
        if (l.state != State.WAR && l.state != State.HOSTILE) return to.name + " não está em guerra com você.";
        Attitude att = attitude(to.id, from.id);
        boolean weaker = core.military(to.id) < core.military(from.id);
        if (att.hostility > 85 && !weaker && to.personality.militarism > 60)
            return to.name + " recusou a paz: \"Ainda não acabou.\"";
        setState(l, from, to, State.ARMISTICE);
        att.hostility = Math.min(att.hostility, 55);
        core.bus().publish(core.tick(), EventType.PEACE_MADE, GameEvent.Severity.GOOD, from.id, null,
                "Armistício assinado entre " + from.name + " e " + to.name + ".",
                Map.of("from", from.id.toString(), "to", to.id.toString()));
        core.chronicle("Armistício entre " + from.name + " e " + to.name + ".");
        return null;
    }

    public String proposeTreaty(Kingdom from, Kingdom to, TreatyType type) {
        if (hasTreaty(from.id, to.id, type)) return "Já existe " + type.display + " com " + to.name + ".";
        Attitude att = attitude(to.id, from.id);
        Link l = link(from.id, to.id);
        if (l.state == State.WAR) return to.name + " não negocia tratados durante a guerra.";
        boolean accept = switch (type) {
            case NON_AGGRESSION -> att.hostility < 60 && att.trust > 25 || att.fear > 60;
            case TRADE_AGREEMENT -> to.personality.commerce > 40 && att.trust > 35 && att.hostility < 55;
            case DEFENSIVE_ALLIANCE -> att.trust > 65 && att.hostility < 25;
            case OPEN_BORDERS -> att.trust > 50 && to.personality.tolerance > 40;
            case TRIBUTE -> att.fear > 70;
            case DYNASTIC_MARRIAGE -> att.trust > 60 && att.hostility < 30 && to.personality.diplomacy > 40;
        };
        if (!accept) {
            core.bus().publish(core.tick(), EventType.TRADE_REFUSED, GameEvent.Severity.INFO, from.id, null,
                    to.name + " recusou " + type.display + " (confiança " + (int) att.trust + ", hostilidade " + (int) att.hostility + ").",
                    Map.of("from", from.id.toString(), "to", to.id.toString(), "treaty", type.name()));
            return to.name + " recusou " + type.display + ". Confiança " + (int) att.trust + ", hostilidade " + (int) att.hostility
                    + ", medo " + (int) att.fear + ".";
        }
        signTreaty(from, to, type);
        return null;
    }

    /** Registra o tratado (sem avaliar aceitação: quem decidiu foi o alvo ou o jogador). Dura 10 dias de jogo. */
    public Treaty signTreaty(Kingdom from, Kingdom to, TreatyType type) {
        Attitude att = attitude(to.id, from.id);
        Treaty t = new Treaty();
        t.id = com.kingdomsai.core.common.Ids.next();
        t.type = type;
        t.a = from.id;
        t.b = to.id;
        t.startTick = core.tick();
        t.expiresTick = core.tick() + 24000L * 10;
        core.state().treaties.add(t);
        att.trust = Text.clamp(att.trust + 5, 0, 100);
        att.hostility = Text.clamp(att.hostility - 8, 0, 100);
        core.bus().publish(core.tick(), EventType.TREATY_SIGNED, GameEvent.Severity.GOOD, from.id, null,
                type.display + " assinado com " + to.name + " (10 dias).",
                Map.of("from", from.id.toString(), "to", to.id.toString(), "treaty", type.name()));
        core.chronicle(type.display + " entre " + from.name + " e " + to.name + ".");
        return t;
    }

    /** Verificação diária dos termos: tributo é pago por b; sem pagar, violação — três violações rompem o tratado. Casamento aproxima os reinos. */
    private void enforceTreaties() {
        for (Iterator<Treaty> it = core.state().treaties.iterator(); it.hasNext(); ) {
            Treaty t = it.next();
            Kingdom a = core.kingdom(t.a), b = core.kingdom(t.b);
            if (a == null || b == null) {
                it.remove();
                continue;
            }
            if (t.type == TreatyType.TRIBUTE) {
                double gold = Math.max(5, Math.floor(b.get(ResourceType.GOLD) * 0.05));
                if (b.get(ResourceType.GOLD) >= gold) {
                    b.add(ResourceType.GOLD, -gold);
                    a.add(ResourceType.GOLD, gold);
                    t.note = "último tributo: " + (int) gold + " de ouro";
                } else {
                    t.violations++;
                    if (t.violations >= 3) {
                        it.remove();
                        b.reliability = Text.clamp(b.reliability - 10, 0, 100);
                        attitude(a.id, b.id).hostility = Text.clamp(attitude(a.id, b.id).hostility + 25, 0, 100);
                        publishBroken(t, b, a, b.name + " deixou de pagar o tributo");
                    }
                }
            } else if (t.type == TreatyType.DYNASTIC_MARRIAGE) {
                Attitude x = attitude(a.id, b.id), y = attitude(b.id, a.id);
                x.trust = Text.clamp(x.trust + 1, 0, 100);
                y.trust = Text.clamp(y.trust + 1, 0, 100);
                x.hostility = Text.clamp(x.hostility - 1, 0, 100);
                y.hostility = Text.clamp(y.hostility - 1, 0, 100);
            }
        }
    }

    private void publishBroken(Treaty t, Kingdom breaker, Kingdom other, String why) {
        core.bus().publish(core.tick(), EventType.TREATY_BROKEN, GameEvent.Severity.DANGER, breaker.id, null,
                t.type.display + " entre " + breaker.name + " e " + other.name + " rompido: " + why + ".",
                Map.of("from", breaker.id.toString(), "to", other.id.toString(), "treaty", t.type.name()));
        core.chronicle(t.type.display + " entre " + breaker.name + " e " + other.name + " foi rompido (" + why + ").");
    }

    // ------------------------------------------------------------ ofertas dos reis de IA ao jogador

    public static final long OFFER_LIFETIME_TICKS = 24000L * 3;

    /** Um rei de IA faz uma proposta ao jogador. Devolve a oferta (ou null se já há uma igual pendente). */
    public Offer makeOffer(Kingdom from, Kingdom to, String treaty, ResourceType give, int giveAmount, ResourceType want, int wantAmount, String message) {
        String treatyName = "";
        if (treaty != null && !treaty.isBlank()) {
            TreatyType type = TreatyType.parse(treaty);
            if (type == null || hasTreaty(from.id, to.id, type)) return null;
            treatyName = type.name();
        } else if (give == null || want == null || giveAmount <= 0 || wantAmount <= 0) return null;
        for (Offer o : core.state().offers)
            if (o.status.equals("PENDING") && o.from.equals(from.id) && o.to.equals(to.id)) return null; // uma por vez por reino
        Offer o = new Offer();
        o.number = ++core.state().offerCounter;
        o.from = from.id;
        o.to = to.id;
        o.treaty = treatyName;
        o.give = give == null ? "" : give.name();
        o.want = want == null ? "" : want.name();
        o.giveAmount = giveAmount;
        o.wantAmount = wantAmount;
        o.message = message == null ? "" : message;
        o.createdTick = core.tick();
        o.expiresTick = core.tick() + OFFER_LIFETIME_TICKS;
        core.state().offers.add(o);
        core.bus().publish(core.tick(), EventType.OFFER_MADE, GameEvent.Severity.INFO, to.id, null,
                from.name + " propõe: " + describe(o) + (o.message.isBlank() ? "" : " — «" + o.message + "»") + "  (/k offers)",
                Map.of("from", from.id.toString(), "to", to.id.toString(), "offer", String.valueOf(o.number)));
        return o;
    }

    public String describe(Offer o) {
        if (!o.treaty.isEmpty()) return TreatyType.valueOf(o.treaty).display;
        return "dá " + o.giveAmount + " " + ResourceType.parse(o.give).display + " por " + o.wantAmount + " " + ResourceType.parse(o.want).display;
    }

    /** Ofertas pendentes para este reino (as vencidas viram EXPIRED). */
    public List<Offer> offersFor(UUID to) {
        List<Offer> out = new ArrayList<>();
        for (Offer o : core.state().offers) {
            if (o.status.equals("PENDING") && o.expiresTick < core.tick()) o.status = "EXPIRED";
            if (o.status.equals("PENDING") && o.to.equals(to)) out.add(o);
        }
        return out;
    }

    /** Aceita a oferta. Retorna null se feito, senão o motivo. */
    public String acceptOffer(Kingdom player, int number) {
        Offer o = findOffer(player, number);
        if (o == null) return "Não há oferta pendente nº " + number + ".";
        Kingdom from = core.kingdom(o.from);
        if (from == null) return "Quem fez a oferta não existe mais.";
        if (link(from.id, player.id).state == State.WAR) return "Em guerra com " + from.name + " não há oferta que valha.";
        if (!o.treaty.isEmpty()) signTreaty(from, player, TreatyType.valueOf(o.treaty));
        else {
            ResourceType give = ResourceType.parse(o.give), want = ResourceType.parse(o.want);
            if (from.get(give) < o.giveAmount) return from.name + " já não tem " + o.giveAmount + " de " + give.display + ".";
            if (player.get(want) < o.wantAmount) return "Você não tem " + o.wantAmount + " de " + want.display + " para essa troca.";
            executeTrade(from, player, give, o.giveAmount, want, o.wantAmount);
        }
        o.status = "ACCEPTED";
        return null;
    }

    /** Recusa a oferta (o reino de IA lembra: um pouco menos de confiança). */
    public String refuseOffer(Kingdom player, int number) {
        Offer o = findOffer(player, number);
        if (o == null) return "Não há oferta pendente nº " + number + ".";
        o.status = "REFUSED";
        Attitude att = attitude(o.from, player.id);
        att.trust = Text.clamp(att.trust - 2, 0, 100);
        return null;
    }

    private Offer findOffer(Kingdom player, int number) {
        for (Offer o : offersFor(player.id)) if (o.number == number) return o;
        return null;
    }

    /** Há tratado que proíbe um lado de atacar o outro? (não agressão, aliança defensiva, casamento) */
    public Treaty binding(UUID from, UUID to) {
        for (Treaty t : treaties(from, to))
            if (t.type == TreatyType.NON_AGGRESSION || t.type == TreatyType.DEFENSIVE_ALLIANCE || t.type == TreatyType.DYNASTIC_MARRIAGE) return t;
        return null;
    }

    /** Troca de recursos. Retorna null se aceita, senão o motivo. */
    public String trade(Kingdom from, Kingdom to, ResourceType give, int giveAmt, ResourceType want, int wantAmt) {
        Attitude att = attitude(to.id, from.id);
        if (link(from.id, to.id).state == State.WAR) return to.name + " não comercia com inimigos.";
        if (to.get(want) < wantAmt) return to.name + " não tem " + wantAmt + " de " + want.display + ".";
        double offered = EconomySystem.VALUE.get(give) * giveAmt;
        double asked = EconomySystem.VALUE.get(want) * wantAmt;
        double demand = 1.35 - att.trust / 100.0 * 0.5 - to.personality.commerce / 100.0 * 0.2 + att.hostility / 100.0 * 0.5;
        if (hasTreaty(from.id, to.id, TreatyType.TRADE_AGREEMENT)) demand -= 0.15;
        if (offered < asked * demand) {
            core.bus().publish(core.tick(), EventType.TRADE_REFUSED, GameEvent.Severity.INFO, from.id, null,
                    to.name + " recusou a troca (quer ~" + (int) Math.ceil(asked * demand / EconomySystem.VALUE.get(give)) + " " + give.display + ").",
                    Map.of("from", from.id.toString(), "to", to.id.toString(), "give", give.name(), "want", want.name()));
            return to.name + " achou a oferta baixa. Pediriam cerca de " + (int) Math.ceil(asked * demand / EconomySystem.VALUE.get(give))
                    + " de " + give.display + ".";
        }
        executeTrade(from, to, give, giveAmt, want, wantAmt);
        return null;
    }

    /** Faz a troca (sem avaliar aceitação). Quem chama já conferiu que o parceiro tem o que a troca pede. */
    public void executeTrade(Kingdom from, Kingdom to, ResourceType give, int giveAmt, ResourceType want, int wantAmt) {
        Attitude att = attitude(to.id, from.id);
        from.add(give, -giveAmt);
        to.add(give, giveAmt);
        to.add(want, -wantAmt);
        from.add(want, wantAmt);
        att.trust = Text.clamp(att.trust + 3, 0, 100);
        att.tradeDependence = Text.clamp(att.tradeDependence + 4, 0, 100);
        att.hostility = Text.clamp(att.hostility - 2, 0, 100);
        core.bus().publish(core.tick(), EventType.TRADE_COMPLETED, GameEvent.Severity.GOOD, from.id, null,
                "Comércio com " + to.name + ": " + giveAmt + " " + give.display + " → " + wantAmt + " " + want.display + ".",
                Map.of("from", from.id.toString(), "to", to.id.toString()));
    }

    public void gift(Kingdom from, Kingdom to, ResourceType r, int amount) {
        from.add(r, -amount);
        to.add(r, amount);
        Attitude att = attitude(to.id, from.id);
        double v = EconomySystem.VALUE.get(r) * amount;
        att.trust = Text.clamp(att.trust + v / 25.0, 0, 100);
        att.hostility = Text.clamp(att.hostility - v / 20.0, 0, 100);
        att.respect = Text.clamp(att.respect + v / 60.0, 0, 100);
        core.bus().publish(core.tick(), EventType.GIFT_SENT, GameEvent.Severity.GOOD, from.id, null,
                "Presente enviado a " + to.name + ": " + amount + " " + r.display + ".",
                Map.of("from", from.id.toString(), "to", to.id.toString()));
    }
}
