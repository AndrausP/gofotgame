package com.kingdomsai.core.port;

import com.kingdomsai.core.common.Pos;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Mãos dos NPCs no mundo físico: quebrar blocos, abrir baús, fabricar itens. O Core decide e valida
 * (o que pode ser quebrado, de quem é o baú, se a receita fecha); o adaptador só executa e responde
 * perguntas sobre o mundo. Itens são ids de texto ("minecraft:iron_pickaxe").
 */
public interface PhysicalPort {

    /**
     * O que há num bloco, do ponto de vista de quem vai quebrá-lo.
     *
     * @param tool      ferramenta certa (pickaxe|axe|shovel|hoe) ou null
     * @param needsTool sem a ferramenta certa o bloco não dá nada (pedra sem picareta)
     * @param drop      o que cai quebrando com a ferramenta certa (id) ou null
     */
    record BlockInfo(String id, boolean air, boolean breakable, double hardness, boolean blockEntity, boolean nearFluid,
                     boolean fluid, boolean log, boolean leaves, String tool, boolean needsTool, String drop) {
        public static final BlockInfo UNKNOWN = new BlockInfo("?", false, false, -1, false, false, false, false, false, null, false, null);
    }

    enum Station { NONE, CRAFTING_TABLE, FURNACE }

    /**
     * @param ingredients uma lista por casa da grade; cada casa aceita qualquer um dos ids da lista
     * @param station     NONE = cabe na grade 2×2 do inventário
     */
    record Recipe(String output, int count, List<List<String>> ingredients, Station station) {}

    enum Anim { SWING, CRACK, CHEST_OPEN, CHEST_CLOSE, CRAFT, PLANT, GIVE }

    boolean isLoaded(Pos p);

    BlockInfo block(Pos p);

    /** Quebra de verdade (efeitos, som) e devolve o que caiu, já na mão do NPC. Vazio se não deu. */
    Map<String, Integer> breakBlock(UUID npc, Pos p, String tool);

    /** Coloca um bloco simples (muda de árvore). */
    boolean place(UUID npc, Pos p, String blockId);

    /** Conteúdo de um baú/barril/fornalha; null se não for um contêiner. */
    Map<String, Integer> container(Pos p);

    /** Tira até n itens (id exato) do contêiner; devolve quantos tirou. */
    int take(UUID npc, Pos chest, String item, int n);

    /** Guarda itens; devolve o que não coube. */
    Map<String, Integer> put(UUID npc, Pos chest, Map<String, Integer> items);

    /** Receitas (bancada/inventário/fornalha) que produzem este item; vazio se não houver. */
    List<Recipe> recipes(String item);

    boolean itemExists(String item);

    /** Bloco com este id mais perto de p (raio em blocos); null se não houver. */
    Pos findNear(Pos p, String blockId, int radius);

    /** Entrega na mão do jogador (o que não couber cai aos pés dele). */
    boolean give(UUID npc, UUID player, Map<String, Integer> items);

    /** Efeito visual/sonoro (braço, rachadura no bloco, tampa do baú...). */
    void animate(UUID npc, Pos at, Anim anim, int stage);

    /**
     * Quebrar aqui é permitido para este jogador? (claims de outros mods, proteção do spawn do servidor,
     * borda do mundo). O súdito age em nome do rei: o que o rei não pode quebrar, ele também não.
     */
    boolean mayBreak(UUID player, Pos p);

    /** Solta itens no chão (ex.: mochila de um súdito que morreu). */
    void drop(Pos p, Map<String, Integer> items);

    /** O chunk já é mantido carregado por outro motivo (/forceload do jogador, outro mod)? */
    boolean chunkForced(int chunkX, int chunkZ);

    /** Mantém (ou solta) um chunk carregado e funcionando mesmo sem jogador por perto. */
    void forceChunk(int chunkX, int chunkZ, boolean on);

    /** Algo hostil que um súdito pode ver (zumbi, esqueleto, aranha...): id do tipo ("minecraft:zombie") e onde está. */
    record Sighting(String kind, Pos pos) {}

    /** Monstros num raio (percepção dos súditos materializados). Sem mundo físico: nada. */
    default List<Sighting> threatsNear(Pos p, int radius) {
        return List.of();
    }

    /** Plantação: crescimento 0..100 (100 = madura, pronta para colher) ou -1 se o bloco não é plantação. */
    default int growth(Pos p) {
        return -1;
    }

    /** Ara a terra com a enxada: terra/grama com ar em cima vira terra arada. */
    default boolean till(UUID npc, Pos p) {
        return false;
    }

    /** Sem mundo físico (testes de outras partes, servidor sem o adaptador). */
    PhysicalPort NONE = new PhysicalPort() {
        public boolean isLoaded(Pos p) {
            return false;
        }

        public BlockInfo block(Pos p) {
            return BlockInfo.UNKNOWN;
        }

        public Map<String, Integer> breakBlock(UUID npc, Pos p, String tool) {
            return Map.of();
        }

        public boolean place(UUID npc, Pos p, String blockId) {
            return false;
        }

        public Map<String, Integer> container(Pos p) {
            return null;
        }

        public int take(UUID npc, Pos chest, String item, int n) {
            return 0;
        }

        public Map<String, Integer> put(UUID npc, Pos chest, Map<String, Integer> items) {
            return items;
        }

        public List<Recipe> recipes(String item) {
            return List.of();
        }

        public boolean itemExists(String item) {
            return false;
        }

        public Pos findNear(Pos p, String blockId, int radius) {
            return null;
        }

        public boolean give(UUID npc, UUID player, Map<String, Integer> items) {
            return false;
        }

        public void animate(UUID npc, Pos at, Anim anim, int stage) {
        }

        public boolean chunkForced(int chunkX, int chunkZ) {
            return false;
        }

        public boolean mayBreak(UUID player, Pos p) {
            return true;
        }

        public void drop(Pos p, Map<String, Integer> items) {
        }

        public void forceChunk(int chunkX, int chunkZ, boolean on) {
        }
    };
}
