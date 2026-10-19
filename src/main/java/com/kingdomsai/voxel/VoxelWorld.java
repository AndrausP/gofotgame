package com.kingdomsai.voxel;

import com.kingdomsai.core.common.Pos;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * O mundo: chunks gerados sob demanda, alterações registradas para a rede, baús e plantações.
 * Pertence à thread da simulação (a ponte nunca mexe nele de fora).
 */
public final class VoxelWorld {
    /** Metade do lado do mundo jogável, em blocos (fora disso é "não carregado"). */
    public static final int LIMIT = 4096;

    public record Change(int x, int y, int z, int block) {}

    private final WorldGen gen;
    private final Map<Long, Chunk> chunks = new ConcurrentHashMap<>();
    private final List<Change> changes = new ArrayList<>();
    private final Map<Pos, Map<String, Integer>> containers = new HashMap<>();
    private final Map<Pos, Integer> crops = new HashMap<>();
    /** Inteireza 0..100 de cada bloco que já se desgastou (ausente = 100). */
    private final Map<Pos, Float> integrity = new HashMap<>();

    public VoxelWorld(long seed) {
        this.gen = new WorldGen(seed);
    }

    public WorldGen gen() {
        return gen;
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }

    public Chunk chunk(int cx, int cz) {
        long k = key(cx, cz);
        Chunk c = chunks.get(k);
        if (c == null) {
            c = new Chunk(cx, cz);
            gen.generate(c);
            c.dirty = true;
            Chunk prev = chunks.putIfAbsent(k, c);
            if (prev != null) c = prev;
        }
        return c;
    }

    /** Carregamento de salvamento: troca o chunk gerado pelo salvo. */
    public void restoreChunk(int cx, int cz, short[] data) {
        Chunk c = new Chunk(cx, cz);
        System.arraycopy(data, 0, c.blocks, 0, data.length);
        c.modified = true;
        c.dirty = true;
        chunks.put(key(cx, cz), c);
    }

    public boolean hasChunk(int cx, int cz) {
        return chunks.containsKey(key(cx, cz));
    }

    public Collection<Chunk> loadedChunks() {
        return chunks.values();
    }

    public boolean inBounds(int x, int z) {
        return Math.abs(x) < LIMIT && Math.abs(z) < LIMIT;
    }

    public BlockType get(int x, int y, int z) {
        if (y < 0) return Blocks.BEDROCK;
        if (y >= Chunk.HEIGHT) return Blocks.AIR;
        Chunk c = chunk(Math.floorDiv(x, Chunk.SIZE), Math.floorDiv(z, Chunk.SIZE));
        return Blocks.byNum(c.get(Math.floorMod(x, Chunk.SIZE), y, Math.floorMod(z, Chunk.SIZE)));
    }

    public BlockType get(Pos p) {
        return get(p.x(), p.y(), p.z());
    }

    /** Avisado a cada mudança de bloco (a água olha ao redor). */
    public java.util.function.Consumer<Pos> changeListener;

    /** Avisado quando um bloco construído e sólido deixa de ser sólido (o desabamento verifica o que ficou sem apoio). */
    public java.util.function.Consumer<Pos> removalListener;

    /** Quando verdadeiro, set() não zera a inteireza (o desgaste troca o bloco por uma versão mais gasta). */
    public boolean keepIntegrity;

    /** Muda um bloco, registra a alteração e limpa o que dependia do bloco antigo (baú, plantação, desgaste). */
    public boolean set(int x, int y, int z, BlockType t) {
        if (y < 0 || y >= Chunk.HEIGHT) return false;
        Chunk c = chunk(Math.floorDiv(x, Chunk.SIZE), Math.floorDiv(z, Chunk.SIZE));
        int lx = Math.floorMod(x, Chunk.SIZE), lz = Math.floorMod(z, Chunk.SIZE);
        short oldNum = c.get(lx, y, lz);
        if (oldNum == t.num()) return false;
        BlockType old = Blocks.byNum(oldNum);
        boolean wasBuiltSolid = old.solid() && removalListener != null && integrity.containsKey(new Pos(x, y, z));
        c.set(lx, y, lz, (short) t.num());
        c.dirty = true;
        c.modified = true;
        c.version++;
        changes.add(new Change(x, y, z, t.num()));
        Pos p = new Pos(x, y, z);
        if (!t.container()) containers.remove(p);
        if (t != Blocks.WHEAT) crops.remove(p);
        if (!keepIntegrity) integrity.remove(p);
        if (wasBuiltSolid && !t.solid()) removalListener.accept(p);
        if (changeListener != null) changeListener.accept(p);
        return true;
    }

    public boolean set(Pos p, BlockType t) {
        return set(p.x(), p.y(), p.z(), t);
    }

    public List<Change> drainChanges() {
        if (changes.isEmpty()) return List.of();
        List<Change> out = new ArrayList<>(changes);
        changes.clear();
        return out;
    }

    /** Y do bloco mais alto que é sólido (inclui troncos e folhas); -1 se a coluna é vazia. */
    public int topSolid(int x, int z) {
        for (int y = Chunk.HEIGHT - 1; y >= 0; y--) {
            BlockType t = get(x, y, z);
            if (t.solid() || t.fluid()) return y;
        }
        return -1;
    }

    /** Primeiro ar acima do chão natural (ignora troncos, folhas, plantas); água conta como chão (o sítio será ruim). */
    public int groundY(int x, int z) {
        int y = topSolid(x, z);
        for (int i = 0; i < 24 && y > 0; i++) {
            BlockType t = get(x, y, z);
            if (t.fluid()) break;
            if (t.log() || t.leaves() || !t.solid()) y--;
            else break;
        }
        return y + 1;
    }

    public Map<String, Integer> container(Pos p) {
        if (!get(p).container()) return null;
        return containers.computeIfAbsent(p, k -> new TreeMap<>());
    }

    public int growth(Pos p) {
        return get(p) == Blocks.WHEAT ? crops.getOrDefault(p, 0) : -1;
    }

    public void setGrowth(Pos p, int g) {
        crops.put(p, Math.max(0, Math.min(100, g)));
    }

    public Map<Pos, Integer> crops() {
        return crops;
    }

    /** Bloco feito por gente (obra, rei, reparo): entra na conta de desgaste com inteireza 100. */
    public void markBuilt(Pos p) {
        integrity.put(p, 100f);
    }

    public boolean isBuilt(Pos p) {
        return integrity.containsKey(p);
    }

    public Map<Pos, Float> integrity() {
        return integrity;
    }

    public Map<Pos, Map<String, Integer>> containers() {
        return containers;
    }
}
