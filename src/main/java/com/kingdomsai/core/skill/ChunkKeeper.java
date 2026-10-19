package com.kingdomsai.core.skill;

import com.kingdomsai.core.KingdomsCore;
import com.kingdomsai.core.common.Pos;
import com.kingdomsai.core.port.PhysicalPort;

import java.util.*;

/**
 * "O súdito trabalha mesmo com o rei longe": mantém carregados só os chunks onde há uma ordem com as mãos
 * em andamento (a etapa atual), com limite global. Quando a ordem acaba, solta.
 *
 * Os chunks que este mod segurou ficam anotados no save ({@code WorldState.forcedChunks}): depois de
 * reiniciar, o que não tiver mais ordem é solto. Chunk que o jogador já mantinha com /forceload nunca
 * é tocado (não assumimos a posse dele).
 */
public final class ChunkKeeper {
    private final KingdomsCore core;
    private final Set<UUID> starved = new HashSet<>();

    public ChunkKeeper(KingdomsCore core) {
        this.core = core;
    }

    public void sync() {
        PhysicalPort port = core.physical();
        Set<String> desired = new LinkedHashSet<>();
        starved.clear();
        if (core.config().keepOrderChunksLoaded) {
            List<PhysicalJob> live = new ArrayList<>();
            // trabalho contínuo (lenhador/mina/fazenda) não segura chunk: longe do rei ele é simulado (LOD)
            for (PhysicalJob j : core.state().jobs.values()) if (j.status.live() && !j.continuous) live.add(j);
            live.sort(Comparator.comparingInt(j -> j.number)); // quem pediu primeiro tem prioridade
            for (PhysicalJob j : live) {
                Set<String> need = chunksFor(j);
                int extra = 0;
                for (String c : need) if (!desired.contains(c)) extra++;
                if (desired.size() + extra > core.config().maxForcedChunks) {
                    starved.add(j.id);
                    continue;
                }
                desired.addAll(need);
            }
        }
        Set<String> owned = core.state().forcedChunks;
        for (String c : desired) {
            if (owned.contains(c)) continue;
            int[] xz = parse(c);
            if (port.chunkForced(xz[0], xz[1])) continue; // já está carregado por outro motivo
            port.forceChunk(xz[0], xz[1], true);
            owned.add(c);
        }
        for (String c : new ArrayList<>(owned)) {
            if (desired.contains(c)) continue;
            int[] xz = parse(c);
            port.forceChunk(xz[0], xz[1], false);
            owned.remove(c);
        }
    }

    /** Chunks da etapa atual (área a quebrar, baú, bancada). */
    public static Set<String> chunksFor(PhysicalJob j) {
        Set<String> out = new LinkedHashSet<>();
        PhysicalJob.Task t = j.current();
        if (t == null) return out;
        switch (t.kind) {
            case BREAK, CHOP -> {
                for (Pos p : t.blocks) out.add(key(p));
            }
            default -> {
                if (t.at != null) out.add(key(t.at));
            }
        }
        return out;
    }

    public boolean starved(UUID job) {
        return starved.contains(job);
    }

    public static String key(Pos p) {
        return Math.floorDiv(p.x(), 16) + ":" + Math.floorDiv(p.z(), 16);
    }

    static int[] parse(String key) {
        String[] v = key.split(":");
        return new int[]{Integer.parseInt(v[0]), Integer.parseInt(v[1])};
    }
}
