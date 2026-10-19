package com.kingdomsai.core.construction.program;

import com.kingdomsai.core.common.Text;
import com.kingdomsai.core.construction.program.BuildingProgram.Module;

/** Programas-padrão por tipo de pedido ("catedral", "torre", "fortaleza"...). Usados sem LLM e como último recurso do arquiteto. */
public final class DefaultPrograms {
    private DefaultPrograms() {}

    /** O pedido fala de uma obra grande composta (e não de uma casa da biblioteca)? */
    public static boolean isProgramRequest(String text) {
        String k = kind(text);
        return k != null && !k.equals("torre");
    }

    public static String kind(String text) {
        String n = Text.norm(text == null ? "" : text);
        if (n.matches(".*(catedral|basilica|abadia).*")) return "catedral";
        if (n.matches(".*(mosteiro|convento|claustro).*")) return "mosteiro";
        if (n.matches(".*(fortaleza|castelo|forte ).*")) return "fortaleza";
        if (n.matches(".*(torre de vigia|atalaia|torre alta|torre).*")) return "torre";
        return null;
    }

    public static Style style(String text, Style fallback) {
        Style s = null;
        String n = Text.norm(text == null ? "" : text);
        if (n.contains("gotic")) s = Style.GOTHIC;
        else if (n.contains("romanic") || n.contains("romanesc")) s = Style.ROMANESQUE;
        else if (n.contains("rustic")) s = Style.RUSTIC;
        else if (n.contains("fortaleza") || n.contains("militar")) s = Style.FORTRESS;
        return s == null ? fallback : s;
    }

    public static BuildingProgram forText(String text) {
        String k = kind(text);
        if (k == null) k = "torre";
        return switch (k) {
            case "catedral" -> cathedral(style(text, Style.GOTHIC));
            case "mosteiro" -> monastery(style(text, Style.ROMANESQUE));
            case "fortaleza" -> fortress(style(text, Style.FORTRESS));
            default -> tower(style(text, Style.FORTRESS));
        };
    }

    private static Module m(String id, String type, String attach, String side) {
        Module x = new Module();
        x.id = id;
        x.type = type;
        x.attach = attach;
        x.side = side;
        return x;
    }

    private static BuildingProgram base(Style st, String name, int w, int d) {
        BuildingProgram p = new BuildingProgram();
        p.style = st.name().toLowerCase();
        p.name = name;
        p.site.width = w;
        p.site.depth = d;
        return p;
    }

    public static BuildingProgram cathedral(Style st) {
        BuildingProgram p = base(st, "Catedral", 48, 60);
        Module nave = m("nave", "hall", "", "");
        nave.w = 12;
        nave.d = 30;
        nave.h = 12;
        nave.roof = "vault";
        p.modules.add(nave);
        for (String[] t : new String[][]{{"t1", "front-left"}, {"t2", "front-right"}}) {
            Module x = m(t[0], "tower", "nave", t[1]);
            x.w = 6;
            x.floors = 4;
            x.roof = "cone";
            p.modules.add(x);
        }
        Module ap = m("abside", "apse", "nave", "back");
        ap.r = 6;
        p.modules.add(ap);
        Module bu = m("contrafortes", "buttress_row", "nave", "");
        bu.spacing = 4;
        p.modules.add(bu);
        Module wi = m("vitrais", "window_band", "nave", "");
        wi.height = 6;
        wi.spacing = 4;
        p.modules.add(wi);
        return p;
    }

    public static BuildingProgram monastery(Style st) {
        BuildingProgram p = base(st, "Mosteiro", 48, 40);
        Module nave = m("nave", "hall", "", "");
        nave.w = 9;
        nave.d = 20;
        nave.h = 8;
        nave.roof = "gable";
        p.modules.add(nave);
        Module cl = m("claustro", "cloister", "nave", "right");
        cl.w = 15;
        cl.d = 15;
        p.modules.add(cl);
        Module t = m("campanario", "tower", "nave", "front-left");
        t.w = 5;
        t.floors = 3;
        t.roof = "cone";
        p.modules.add(t);
        Module wi = m("janelas", "window_band", "nave", "");
        wi.height = 3;
        p.modules.add(wi);
        return p;
    }

    public static BuildingProgram fortress(Style st) {
        BuildingProgram p = base(st, "Fortaleza", 48, 48);
        Module keep = m("torre-menagem", "hall", "", "");
        keep.w = 11;
        keep.d = 11;
        keep.h = 9;
        keep.roof = "flat";
        p.modules.add(keep);
        for (String[] t : new String[][]{{"t1", "front-left"}, {"t2", "front-right"}, {"t3", "back-left"}, {"t4", "back-right"}}) {
            Module x = m(t[0], "tower", "torre-menagem", t[1]);
            x.w = 5;
            x.floors = 3;
            x.roof = "flat";
            p.modules.add(x);
        }
        return p;
    }

    public static BuildingProgram tower(Style st) {
        BuildingProgram p = base(st, "Torre de vigia", 12, 12);
        Module t = m("torre", "tower", "", "");
        t.w = 6;
        t.floors = 4;
        t.roof = st == Style.FORTRESS ? "flat" : "cone";
        p.modules.add(t);
        return p;
    }
}
