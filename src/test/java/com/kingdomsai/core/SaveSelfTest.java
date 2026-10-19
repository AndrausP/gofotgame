package com.kingdomsai.core;

import com.kingdomsai.core.cli.CommandService;
import com.kingdomsai.core.common.Pos;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.voxel.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Salvar e carregar o jogo inteiro: Core + mundo voxel. */
public final class SaveSelfTest {
    private static int passed, failed;

    public static void main(String[] args) throws Exception {
        int[] r = run();
        System.out.println("\n" + r[0] + " passaram, " + r[1] + " falharam.");
        System.exit(r[1] == 0 ? 0 : 1);
    }

    public static int[] run() throws Exception {
        passed = failed = 0;
        System.out.println("\n# Salvar e carregar o mundo");
        CoreConfig cfg = new CoreConfig();
        cfg.rivalKingdoms = 0;
        cfg.aiKingdomsEnabled = false;
        VoxelHost a = new VoxelHost(77, new WorldState(), cfg);
        UUID player = UUID.randomUUID();
        CommandService cli = new CommandService(a.core);
        cli.setNotifier((p, l) -> {});
        int g = a.world.groundY(0, 0);
        Pos king = new Pos(0, g, 0);
        a.core.updatePlayerPos(player, king);
        a.area.setViewer(player, king);
        cli.execute(player, "Andraus", king, "found Reino Salvo");
        Kingdom k = a.core.kingdomOfPlayer(player);
        k.civic = "off";
        for (int i = 0; i < 20 * 90; i++) a.step();
        Pos mark = new Pos(30, a.world.groundY(30, 30), 30);
        a.world.set(mark, Blocks.CHEST);
        a.world.markBuilt(mark);
        a.world.container(mark).put("minecraft:bread", 7);
        a.world.set(mark.offset(1, 0, 0), Blocks.GOLD_ORE);
        a.world.set(mark.offset(2, 0, 0), Blocks.FARMLAND);
        a.world.set(mark.offset(2, 1, 0), Blocks.WHEAT);
        a.world.setGrowth(mark.offset(2, 1, 0), 42);
        int pop = a.core.population(k.id), buildings = a.core.buildings(k.id).size();
        long tick = a.core.tick();
        int built = a.world.integrity().size();

        Path dir = Files.createTempDirectory("kingdoms-save");
        WorldSave.save(a, dir);
        check("salvou os dois arquivos", WorldSave.exists(dir));
        long size = Files.size(dir.resolve("world.bin"));
        System.out.println("   world.bin: " + size / 1024 + " KB, " + a.world.loadedChunks().stream().filter(c -> c.modified).count() + " chunks editados de " + a.world.loadedChunks().size());

        VoxelHost b = WorldSave.load(dir, cfg);
        Kingdom kb = b.core.kingdomOfPlayer(player);
        check("o reino volta com nome e povo", kb != null && kb.name.equals("Reino Salvo") && b.core.population(kb.id) == pop);
        check("as obras voltam", b.core.buildings(kb.id).size() == buildings);
        check("o tempo do jogo volta", b.core.tick() == tick);
        check("bloco editado volta", b.world.get(mark) == Blocks.CHEST && b.world.get(mark.offset(1, 0, 0)) == Blocks.GOLD_ORE);
        check("conteúdo do baú volta", b.world.container(mark) != null && b.world.container(mark).getOrDefault("minecraft:bread", 0) == 7);
        check("plantação volta com o crescimento", b.world.growth(mark.offset(2, 1, 0)) == 42);
        check("inteireza dos blocos volta (" + b.world.integrity().size() + ")", b.world.integrity().size() == built);
        boolean same = true;
        VoxelWorld fresh = new VoxelWorld(77);
        for (int cx = 10; cx < 14; cx++)
            for (int cz = -14; cz < -10; cz++)
                if (!Arrays.equals(fresh.chunk(cx, cz).blocks, b.world.chunk(cx, cz).blocks)) same = false;
        check("chunks intocados são regenerados da semente, idênticos", same);
        // o jogo continua
        b.core.updatePlayerPos(player, king);
        b.area.setViewer(player, king);
        for (int i = 0; i < 20 * 30; i++) b.step();
        int bodies = 0;
        for (var n : b.core.citizens(kb.id)) if (n.materialized) bodies++;
        check("o jogo carregado continua rodando (" + bodies + " corpos em cena)", bodies >= 5 && b.core.tick() == tick + 600);
        try (var s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
        return new int[]{passed, failed};
    }

    private static void check(String name, boolean ok) {
        if (ok) passed++;
        else failed++;
        System.out.println((ok ? "  [OK]   " : "  [FAIL] ") + name);
    }
}
