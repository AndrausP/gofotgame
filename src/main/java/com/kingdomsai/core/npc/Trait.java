package com.kingdomsai.core.npc;

public enum Trait {
    COURAGE("Coragem"),
    AMBITION("Ambição"),
    LOYALTY("Lealdade"),
    GREED("Ganância"),
    AGGRESSION("Agressividade"),
    RELIGIOSITY("Religiosidade"),
    HONESTY("Honestidade"),
    CURIOSITY("Curiosidade"),
    SOCIABILITY("Sociabilidade"),
    DISCIPLINE("Disciplina");

    public final String display;

    Trait(String display) {
        this.display = display;
    }
}
