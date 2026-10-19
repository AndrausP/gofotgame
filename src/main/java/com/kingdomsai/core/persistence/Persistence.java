package com.kingdomsai.core.persistence;

import com.google.gson.*;
import com.kingdomsai.core.WorldState;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/**
 * Save em JSON versionado (data/kingdomsai.json dentro da pasta do mundo).
 * Escrita atômica (arquivo temporário + move) e backup do save anterior.
 */
public final class Persistence {
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .enableComplexMapKeySerialization()
            .serializeSpecialFloatingPointValues()
            .create();

    private Persistence() {}

    public static void save(WorldState state, Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            GSON.toJson(state, w);
        }
        if (Files.exists(file)) Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static WorldState load(Path file) throws IOException {
        if (!Files.exists(file)) return null;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
            Migrations.migrate(root);
            WorldState s = GSON.fromJson(root, WorldState.class);
            Migrations.repair(s);
            return s;
        } catch (JsonParseException | IllegalStateException e) {
            Path bak = file.resolveSibling(file.getFileName() + ".bak");
            if (Files.exists(bak) && !file.equals(bak)) {
                System.err.println("[KingdomsAI] save corrompido, usando backup: " + e.getMessage());
                return load(bak);
            }
            throw new IOException("Save inválido: " + e.getMessage(), e);
        }
    }

    public static String toJson(Object o) {
        return GSON.toJson(o);
    }

    public static <T> T fromJson(String json, Class<T> type) {
        return GSON.fromJson(json, type);
    }
}
