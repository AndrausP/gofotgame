package com.kingdomsai.voxel;

import com.kingdomsai.core.CoreConfig;
import com.kingdomsai.core.WorldState;
import com.kingdomsai.core.common.Pos;
import com.kingdomsai.core.persistence.Persistence;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Salvamento do jogo: o estado do Core (JSON) e o mundo voxel (só o que mudou desde a geração: chunks editados,
 * baús, plantações, inteireza). O resto é regenerado da semente, então o arquivo cresce com a história, não com o mapa.
 */
public final class WorldSave {
    private static final int MAGIC = 0x4B494E47, VERSION = 1;

    private WorldSave() {}

    public static boolean exists(Path dir) {
        return Files.exists(dir.resolve("core.json")) && Files.exists(dir.resolve("world.bin"));
    }

    public static void save(VoxelHost host, Path dir) throws IOException {
        Files.createDirectories(dir);
        Path coreTmp = dir.resolve("core.json.tmp"), worldTmp = dir.resolve("world.bin.tmp");
        Persistence.save(host.core.snapshotForSave(), coreTmp);
        writeWorld(host.world, worldTmp);
        Files.move(coreTmp, dir.resolve("core.json"), StandardCopyOption.REPLACE_EXISTING);
        Files.move(worldTmp, dir.resolve("world.bin"), StandardCopyOption.REPLACE_EXISTING);
    }

    public static VoxelHost load(Path dir, CoreConfig cfg) throws IOException {
        WorldState state = Persistence.load(dir.resolve("core.json"));
        long seed = readSeed(dir.resolve("world.bin"));
        VoxelHost host = new VoxelHost(seed, state, cfg);
        readWorld(host.world, dir.resolve("world.bin"));
        return host;
    }

    private static void writeWorld(VoxelWorld w, Path file) throws IOException {
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(file))))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeLong(w.gen().seed());
            List<BlockType> table = Blocks.all();
            out.writeInt(table.size());
            for (BlockType t : table) out.writeUTF(t.id());
            List<Chunk> mod = new ArrayList<>();
            for (Chunk c : w.loadedChunks()) if (c.modified) mod.add(c);
            out.writeInt(mod.size());
            for (Chunk c : mod) {
                out.writeInt(c.cx);
                out.writeInt(c.cz);
                int runs = 0;
                for (int i = 0; i < c.blocks.length; i++) if (i == 0 || c.blocks[i] != c.blocks[i - 1]) runs++;
                out.writeInt(runs);
                int i = 0;
                while (i < c.blocks.length) {
                    int j = i;
                    while (j < c.blocks.length && c.blocks[j] == c.blocks[i]) j++;
                    out.writeShort(c.blocks[i]);
                    out.writeInt(j - i);
                    i = j;
                }
            }
            out.writeInt(w.containers().size());
            for (var e : w.containers().entrySet()) {
                writePos(out, e.getKey());
                out.writeInt(e.getValue().size());
                for (var it : e.getValue().entrySet()) {
                    out.writeUTF(it.getKey());
                    out.writeInt(it.getValue());
                }
            }
            out.writeInt(w.crops().size());
            for (var e : w.crops().entrySet()) {
                writePos(out, e.getKey());
                out.writeInt(e.getValue());
            }
            out.writeInt(w.integrity().size());
            for (var e : w.integrity().entrySet()) {
                writePos(out, e.getKey());
                out.writeFloat(e.getValue());
            }
        }
    }

    private static long readSeed(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new BufferedInputStream(Files.newInputStream(file))))) {
            if (in.readInt() != MAGIC) throw new IOException("arquivo de mundo inválido");
            in.readInt();
            return in.readLong();
        }
    }

    private static void readWorld(VoxelWorld w, Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new BufferedInputStream(Files.newInputStream(file))))) {
            if (in.readInt() != MAGIC) throw new IOException("arquivo de mundo inválido");
            int version = in.readInt();
            if (version > VERSION) throw new IOException("mundo salvo por uma versão mais nova");
            in.readLong();
            int n = in.readInt();
            short[] remap = new short[n];
            for (int i = 0; i < n; i++) remap[i] = (short) Blocks.of(in.readUTF()).num();
            int chunks = in.readInt();
            for (int k = 0; k < chunks; k++) {
                int cx = in.readInt(), cz = in.readInt(), runs = in.readInt();
                short[] data = new short[Chunk.SIZE * Chunk.SIZE * Chunk.HEIGHT];
                int pos = 0;
                for (int r = 0; r < runs; r++) {
                    short v = remap[in.readShort() & 0xFFFF];
                    int len = in.readInt();
                    Arrays.fill(data, pos, pos + len, v);
                    pos += len;
                }
                w.restoreChunk(cx, cz, data);
            }
            int cont = in.readInt();
            for (int i = 0; i < cont; i++) {
                Pos p = readPos(in);
                Map<String, Integer> items = new TreeMap<>();
                int m = in.readInt();
                for (int j = 0; j < m; j++) items.put(in.readUTF(), in.readInt());
                w.containers().put(p, items);
            }
            int crops = in.readInt();
            for (int i = 0; i < crops; i++) w.crops().put(readPos(in), in.readInt());
            int ig = in.readInt();
            for (int i = 0; i < ig; i++) w.integrity().put(readPos(in), in.readFloat());
        }
    }

    private static void writePos(DataOutputStream out, Pos p) throws IOException {
        out.writeInt(p.x());
        out.writeInt(p.y());
        out.writeInt(p.z());
    }

    private static Pos readPos(DataInputStream in) throws IOException {
        return new Pos(in.readInt(), in.readInt(), in.readInt());
    }
}
