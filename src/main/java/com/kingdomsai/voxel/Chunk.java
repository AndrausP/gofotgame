package com.kingdomsai.voxel;

/** Coluna de 16×16 blocos de largura e {@link #HEIGHT} de altura. Guarda o número do bloco (curto) em cada célula. */
public final class Chunk {
    public static final int SIZE = 16;
    public static final int HEIGHT = 128;

    public final int cx, cz;
    public final short[] blocks = new short[SIZE * SIZE * HEIGHT];
    /** Mudou desde o último envio/salvamento. */
    public boolean dirty;
    /** Diferente do que a geração produz (vai para o salvamento). */
    public boolean modified;
    /** Versão: sobe a cada alteração (o cliente descarta chunks velhos). */
    public int version;

    public Chunk(int cx, int cz) {
        this.cx = cx;
        this.cz = cz;
    }

    public static int index(int lx, int y, int lz) {
        return (y * SIZE + lz) * SIZE + lx;
    }

    public short get(int lx, int y, int lz) {
        return blocks[index(lx, y, lz)];
    }

    public void set(int lx, int y, int lz, short v) {
        blocks[index(lx, y, lz)] = v;
    }
}
