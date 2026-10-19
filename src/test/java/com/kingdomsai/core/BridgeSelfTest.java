package com.kingdomsai.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kingdomsai.bridge.BridgeServer;

import java.io.*;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

/** A ponte Godot↔Core pela rede: relevo, estado, comando e "chame todos aqui". */
public final class BridgeSelfTest {
    private static int passed, failed;

    public static void main(String[] args) throws Exception {
        int[] r = run();
        System.out.println("\n" + r[0] + " passaram, " + r[1] + " falharam.");
        System.exit(r[1] == 0 ? 0 : 1);
    }

    public static int[] run() throws Exception {
        passed = failed = 0;
        BridgeServer bridge = new BridgeServer();
        bridge.found("Reino de Teste");
        int port = bridge.start(0);
        try (Socket s = new Socket(InetAddress.getLoopbackAddress(), port)) {
            s.setSoTimeout(5000);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));

            JsonObject hello = next(in, "hello");
            check("hello traz a tabela de blocos", hello.getAsJsonArray("blocks").size() > 30 && hello.get("chunk").getAsInt() == 16);
            check("hello diz onde nasce o reino", hello.getAsJsonArray("spawn").size() == 3);

            send(out, "{\"op\":\"view\",\"x\":0,\"z\":0,\"r\":2}");
            Set<String> got = new HashSet<>();
            JsonObject firstChunk = null;
            while (got.size() < 25) {
                JsonObject ch = next(in, "chunk");
                got.add(ch.get("cx").getAsInt() + ":" + ch.get("cz").getAsInt());
                if (firstChunk == null) firstChunk = ch;
            }
            check("view manda os 25 chunks do raio 2", got.size() == 25 && got.contains("0:0") && got.contains("-2:2"));
            check("o primeiro chunk é o mais perto (0,0)", firstChunk.get("cx").getAsInt() == 0 && firstChunk.get("cz").getAsInt() == 0);
            byte[] raw = new java.util.zip.GZIPInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(firstChunk.get("d").getAsString()))).readAllBytes();
            check("chunk descompacta para 128×16×16 blocos de 16 bits", raw.length == 128 * 16 * 16 * 2);
            // índice da seção 4 (y 64..79), célula x=3,z=5,y=2 → 4*4096 + (2 + 16*(3 + 16*5))
            int idx = (4 * 4096 + 2 + 16 * (3 + 16 * 5)) * 2;
            int num = (raw[idx] & 0xFF) | ((raw[idx + 1] & 0xFF) << 8);
            check("o bloco do chunk bate com o mundo do servidor", num == bridge.host().world.get(3, 66, 5).num());

            JsonObject st = next(in, "state");
            check("estado tem reino do jogador", st.getAsJsonArray("kingdoms").size() >= 1 && st.getAsJsonArray("kingdoms").get(0).getAsJsonObject().get("player").getAsBoolean());
            check("estado lista os 10 súditos", countOf(st.getAsJsonArray("npcs"), st.getAsJsonArray("kingdoms").get(0).getAsJsonObject().get("id").getAsString()) >= 10);
            check("estado lista obras com tamanho", st.getAsJsonArray("buildings").size() >= 1 && st.getAsJsonArray("buildings").get(0).getAsJsonObject().has("bp"));

            send(out, "{\"op\":\"cmd\",\"line\":\"status\"}");
            JsonObject reply = next(in, "reply");
            check("cmd devolve linhas da CLI", reply.getAsJsonArray("lines").size() > 0);

            send(out, "{\"op\":\"cmd\",\"line\":\"call Fantasma\"}");
            reply = next(in, "reply");
            check("comando ruim volta como ✗", reply.getAsJsonArray("lines").get(0).getAsString().startsWith("✗"));

            int gy = bridge.host().world.groundY(8, 8);
            send(out, "{\"op\":\"break\",\"x\":8,\"y\":" + (gy - 1) + ",\"z\":8}");
            JsonObject diff = next(in, "blocks");
            check("quebrar um bloco devolve o diff aos clientes", diff.getAsJsonArray("b").get(0).getAsJsonArray().get(3).getAsInt() == 0);
            send(out, "{\"op\":\"place\",\"x\":8,\"y\":" + (gy - 1) + ",\"z\":8,\"block\":\"minecraft:cobblestone\"}");
            diff = next(in, "blocks");
            check("colocar um bloco chega como diff", diff.getAsJsonArray("b").get(0).getAsJsonArray().get(3).getAsInt() == com.kingdomsai.voxel.Blocks.COBBLE.num());
            send(out, "{\"op\":\"player\",\"x\":60,\"y\":72,\"z\":60}");
            send(out, "{\"op\":\"speed\",\"mult\":10}");
            send(out, "{\"op\":\"call_all\"}");
            reply = next(in, "reply");
            check("call_all responde sucesso", reply.getAsJsonArray("lines").get(0).getAsString().startsWith("✓"));

            double first = avgDist(next(in, "state"), 60, 60);
            double last = first;
            long deadline = System.currentTimeMillis() + 15000;
            while (System.currentTimeMillis() < deadline) {
                last = avgDist(next(in, "state"), 60, 60);
                if (last < 12) break;
            }
            System.out.println("   distância média ao chamado: " + (int) first + " → " + (int) last);
            check("súditos se aproximam de quem chamou", last < first - 10 || last < 12);

            send(out, "nao é json");
            check("JSON inválido não derruba a ponte", next(in, "error").getAsJsonArray("lines").size() == 1);
        } finally {
            bridge.close();
        }
        return new int[]{passed, failed};
    }

    private static int countOf(JsonArray npcs, String kingdom) {
        int c = 0;
        for (var e : npcs) if (e.getAsJsonObject().get("k").getAsString().equals(kingdom)) c++;
        return c;
    }

    private static double avgDist(JsonObject st, int x, int z) {
        String k = st.getAsJsonArray("kingdoms").get(0).getAsJsonObject().get("id").getAsString();
        double sum = 0;
        int n = 0;
        for (var e : st.getAsJsonArray("npcs")) {
            JsonObject o = e.getAsJsonObject();
            if (!o.get("k").getAsString().equals(k)) continue;
            sum += Math.hypot(o.get("x").getAsInt() - x, o.get("z").getAsInt() - z);
            n++;
        }
        return n == 0 ? 1e9 : sum / n;
    }

    private static void send(BufferedWriter out, String line) throws IOException {
        out.write(line);
        out.write('\n');
        out.flush();
    }

    /** Lê até chegar uma mensagem com esta op (estados intermediários são descartados). */
    private static JsonObject next(BufferedReader in, String op) throws IOException {
        for (int i = 0; i < 400; i++) {
            String line = in.readLine();
            if (line == null) throw new EOFException("ponte fechou");
            JsonObject o = JsonParser.parseString(line).getAsJsonObject();
            if (o.get("op").getAsString().equals(op)) return o;
        }
        throw new IOException("não veio " + op);
    }

    private static void check(String name, boolean ok) {
        if (ok) passed++;
        else failed++;
        System.out.println((ok ? "  [OK]   " : "  [FAIL] ") + name);
    }
}
