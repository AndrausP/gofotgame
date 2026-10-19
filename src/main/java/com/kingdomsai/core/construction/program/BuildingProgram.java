package com.kingdomsai.core.construction.program;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.util.ArrayList;
import java.util.List;

/**
 * Programa arquitetônico: o que a LLM (ou as regras) descreve e o compilador transforma em blocos. Campos públicos e
 * simples para o Gson ler direto do JSON da LLM; nada aqui é confiável até passar pelo {@link ProgramCompiler}.
 */
public final class BuildingProgram {
    /** Limites duros (validados no compilador): ninguém pede uma obra maior que isto. */
    public static final int MAX_SITE = 64, MAX_HEIGHT = 40, MAX_MODULES = 24, MAX_BLOCKS = 14000;

    public String style = "";
    public String name = "";
    public Site site = new Site();
    public List<Module> modules = new ArrayList<>();
    public String notes = "";

    /** Terreno disponível (largura em x, profundidade em z) e para onde a fachada olha. */
    public static final class Site {
        public int width, depth;
        public String facing = "south";
    }

    /**
     * Um módulo. Campos que não se aplicam ao tipo ficam 0/vazio. {@code attach} + {@code side} dizem onde ele encosta;
     * o único módulo sem {@code attach} é a raiz.
     */
    public static final class Module {
        public String id = "";
        public String type = "";
        /** Largura (x), profundidade (z) e altura útil (paredes) em blocos. */
        public int w, d, h;
        /** Raio (torre redonda, abside). */
        public int r;
        public int floors;
        /** gable | vault | flat | cone */
        public String roof = "";
        public String attach = "";
        /** front | back | left | right | front-left | front-right | back-left | back-right */
        public String side = "";
        /** Espaçamento (contrafortes, janelas). */
        public int spacing;
        /** Altura da faixa de janelas, da escada ou do vão da ponte. */
        public int height;
        /** Comprimento (muralha, ponte, escada). */
        public int length;
        /** x ou z: direção do trecho de muralha / ponte. */
        public String axis = "";
        public String material = "";

        public Module() {}

        public ModuleType moduleType() {
            return ModuleType.parse(type);
        }
    }

    private static final Gson GSON = new Gson();

    /** Lê o JSON (o primeiro objeto do texto). Lança JsonParseException se não for um programa. */
    public static BuildingProgram parse(String json) {
        if (json == null) throw new JsonParseException("vazio");
        int a = json.indexOf('{'), b = json.lastIndexOf('}');
        if (a < 0 || b <= a) throw new JsonParseException("sem objeto JSON");
        BuildingProgram p = GSON.fromJson(json.substring(a, b + 1), BuildingProgram.class);
        if (p == null) throw new JsonParseException("programa vazio");
        if (p.modules == null) p.modules = new ArrayList<>();
        if (p.site == null) p.site = new Site();
        for (Module m : p.modules) if (m == null) throw new JsonParseException("módulo nulo");
        return p;
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    public Module module(String id) {
        for (Module m : modules) if (m.id != null && m.id.equals(id)) return m;
        return null;
    }

    public Module root() {
        for (Module m : modules) if (m.attach == null || m.attach.isBlank()) return m;
        return null;
    }
}
