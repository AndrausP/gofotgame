package com.kingdomsai.core.life;

import com.kingdomsai.core.KingdomsCore;
import com.kingdomsai.core.common.Pos;
import com.kingdomsai.core.common.Text;
import com.kingdomsai.core.construction.Building;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.kingdom.Marker;
import com.kingdomsai.core.npc.Npc;

/** Os lugares da vida na vila: casa, praça, capela, taverna, biblioteca (construções prontas do reino). */
public final class Places {
    private Places() {}

    public static Building home(KingdomsCore core, Npc n) {
        if (n.homeId == null) return null;
        Building b = core.state().buildings.get(n.homeId);
        return b != null && b.isComplete() && b.origin != null && b.origin.y() != Integer.MIN_VALUE ? b : null;
    }

    /** Casa, ou (sem casa) o acampamento no spawn do reino, cada um num canto. */
    public static Pos homePos(KingdomsCore core, Kingdom k, Npc n) {
        Building b = home(core, n);
        if (b != null) return b.centerPos();
        int h = Math.abs(n.id.hashCode());
        return k.spawnPoint().offset((h % 9) - 4, 0, (h / 9 % 9) - 4);
    }

    public static Pos plaza(Kingdom k, Npc n) {
        int h = Math.abs(n.id.hashCode());
        return k.marker(Marker.GATHER, k.center).offset((h % 11) - 5, 0, (h / 11 % 7) - 3);
    }

    public static Building church(KingdomsCore core, Kingdom k) {
        return named(core, k, "capela|igreja|templo|santuario|catedral|chapel|church|temple");
    }

    public static Building tavern(KingdomsCore core, Kingdom k) {
        return named(core, k, "taverna|estalagem|pousada|bar|tavern|inn");
    }

    public static Building library(KingdomsCore core, Kingdom k) {
        for (Building b : core.buildings(k.id))
            if (ready(b) && b.blueprintId.equals("library")) return b;
        return named(core, k, "biblioteca|escola|library");
    }

    private static Building named(KingdomsCore core, Kingdom k, String regex) {
        for (Building b : core.buildings(k.id)) {
            if (!ready(b) || b.blueprint() == null) continue;
            String name = Text.norm(b.blueprint().displayName() + " " + b.blueprintId);
            if (name.matches(".*\\b(" + regex + ")\\b.*")) return b;
        }
        return null;
    }

    private static boolean ready(Building b) {
        return b.isComplete() && b.origin != null && b.origin.y() != Integer.MIN_VALUE;
    }
}
