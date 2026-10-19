package com.kingdomsai.core.npc;

import com.kingdomsai.core.common.Text;

import java.util.EnumSet;
import java.util.Set;

import static com.kingdomsai.core.npc.Permission.*;

/** Cargos administrativos. Cada cargo concede permissões — a IA nunca ganha poderes que o personagem não possui. */
public enum Office {
    NONE("Sem cargo", EnumSet.of(TALK, WORK)),
    KING("Rei", EnumSet.allOf(Permission.class)),
    CHANCELLOR("Chanceler", EnumSet.of(TALK, WORK, DIPLOMACY, TRADE, LAW), "chanceler", "chancellor"),
    TREASURER("Tesoureiro", EnumSet.of(TALK, WORK, TAX, TRADE), "tesoureiro", "treasurer"),
    GENERAL("General", EnumSet.of(TALK, WORK, RECRUIT, COMMAND), "general"),
    CAPTAIN("Capitão", EnumSet.of(TALK, WORK, RECRUIT, COMMAND), "capitao", "captain"),
    GOVERNOR("Governador", EnumSet.of(TALK, WORK, BUILD, ASSIGN_WORK, CLAIM), "governador", "governor"),
    ARCHITECT("Arquiteto", EnumSet.of(TALK, WORK, BUILD), "arquiteto", "architect"),
    PRIEST("Sumo Sacerdote", EnumSet.of(TALK, WORK, PREACH), "sumo sacerdote", "sacerdote", "high priest"),
    JUDGE("Juiz", EnumSet.of(TALK, WORK, Permission.JUDGE), "juiz", "judge"),
    SPYMASTER("Mestre-espião", EnumSet.of(TALK, WORK), "mestre-espiao", "mestre espiao", "espiao", "spymaster"),
    ADVISOR("Conselheiro", EnumSet.of(TALK, WORK), "conselheiro", "advisor");

    public final String display;
    public final Set<Permission> permissions;
    private final String[] aliases;

    Office(String display, Set<Permission> permissions, String... aliases) {
        this.display = display;
        this.permissions = permissions;
        this.aliases = aliases;
    }

    public boolean allows(Permission p) {
        return permissions.contains(p);
    }

    public static Office parse(String s) {
        String n = Text.norm(s);
        for (Office o : values()) {
            if (o.name().equalsIgnoreCase(n) || Text.norm(o.display).equals(n)) return o;
            for (String a : o.aliases) if (a.equals(n)) return o;
        }
        return null;
    }
}
