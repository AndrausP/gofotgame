package com.kingdomsai.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kingdomsai.core.KingdomsCore;
import com.kingdomsai.core.action.ActionType;
import com.kingdomsai.core.cli.CommandService;
import com.kingdomsai.core.common.Pos;
import com.kingdomsai.core.construction.Building;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.npc.Npc;
import com.kingdomsai.voxel.*;

import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.GZIPOutputStream;

/**
 * Ponte Godot ↔ Core: o Godot só desenha e manda pedidos; o mundo voxel e a sociedade vivem aqui (VoxelHost).
 * NDJSON num socket local.
 *
 * Cliente → servidor: cmd {line} · player {x,y,z} · view {x,z,r} (chunks ao redor) · call_all · speed {mult}
 *                     break {x,y,z} · place {x,y,z,block}
 *                     ai_status · ai_config {provider,npc_model,council_model,budget_usd,calls_per_minute,endpoint,effort} · ai_test · ai_login
 * Servidor → cliente: hello (tabela de blocos) · chunk {cx,cz,d} · blocks {b:[[x,y,z,id]...]} · state (~4 Hz)
 *                     fx {...} · reply/async/error {lines} · ai_status/ai_test/ai_login (respostas do painel de IA)
 *
 * O Core é de uma thread só: leitores de socket só enfileiram; quem executa é o laço de tick.
 */
public final class BridgeServer {
    public static final int DEFAULT_PORT = 7777;
    private static final int CHUNKS_PER_TICK = 3;

    private final Gson gson = new Gson();
    private final VoxelHost host;
    private final CommandService cli;
    private final AiPanel ai;
    private final UUID player = UUID.fromString("00000000-0000-0000-0000-00000000a11d");
    private final String playerName = "Andraus";
    private final Queue<Runnable> inbox = new ConcurrentLinkedQueue<>();
    private final List<Client> clients = new CopyOnWriteArrayList<>();
    private final List<Map<String, Object>> pendingFx = new ArrayList<>();
    private volatile int speed = 1;
    private volatile boolean running = true;
    private Pos playerPos = new Pos(0, 72, 0);
    private ServerSocket server;
    private java.nio.file.Path saveDir;
    private long autosaveEvery;

    public BridgeServer() {
        this(new VoxelHost(20240601L));
    }

    public BridgeServer(VoxelHost host) {
        this.host = host;
        host.core.setMainThread(inbox::add);
        cli = new CommandService(host.core);
        ai = new AiPanel(host.core);
        cli.setNotifier((p, lines) -> broadcast(lines("async", lines)));
        host.physical.onFx(f -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("op", "fx");
            m.put("npc", f.npc() == null ? null : f.npc().toString());
            m.put("x", f.at().x());
            m.put("y", f.at().y());
            m.put("z", f.at().z());
            m.put("anim", f.anim().name());
            m.put("stage", f.stage());
            pendingFx.add(m);
        });
        host.area.setViewer(player, playerPos);
    }

    public void setAutosave(java.nio.file.Path dir, long everyTicks) {
        saveDir = dir;
        ai.setSaveDir(dir);
        autosaveEvery = everyTicks;
    }

    /** Salva agora (o laço de ticks e o salvamento nunca rodam juntos: o desligamento espera o tick atual). */
    public synchronized void saveNow() throws IOException {
        if (saveDir != null) WorldSave.save(host, saveDir);
    }

    /** Mundo novo: apaga o save, grava a semente/nome pedidos em novo.json e encerra (código 42); o script de jogo sobe a ponte de novo. */
    private void newWorld(Client c, JsonObject msg) {
        if (saveDir == null || !c.isLoopback()) {
            c.send(lines("error", List.of("✗ Mundo novo precisa de --save e de conexão local.")));
            return;
        }
        try {
            long seed = msg.has("seed") && !msg.get("seed").getAsString().isBlank() ? Long.parseLong(msg.get("seed").getAsString().trim()) : System.nanoTime() & 0xFFFFFFFFL;
            String name = msg.has("name") ? msg.get("name").getAsString().replaceAll("[\\r\\n\"\\\\]", " ").trim() : "";
            if (name.isEmpty()) name = "Reino Novo";
            if (java.nio.file.Files.isDirectory(saveDir))
                try (var st = java.nio.file.Files.walk(saveDir)) {
                    for (var p : st.sorted(java.util.Comparator.reverseOrder()).toList()) if (!p.equals(saveDir) && !p.getFileName().toString().equals(AiPanel.FILE)) java.nio.file.Files.deleteIfExists(p);
                }
            java.nio.file.Files.createDirectories(saveDir);
            java.nio.file.Files.writeString(saveDir.resolve("novo.json"), "{\"seed\":" + seed + ",\"name\":\"" + name + "\"}");
            running = false;
            Runtime.getRuntime().halt(42);
        } catch (IOException | RuntimeException e) {
            c.send(lines("error", List.of("✗ Não consegui criar o mundo: " + e.getMessage())));
        }
    }

    public VoxelHost host() {
        return host;
    }

    public KingdomsCore core() {
        return host.core;
    }

    public AiPanel ai() {
        return ai;
    }

    public CommandService cli() {
        return cli;
    }

    public Kingdom found(String name) {
        int g = host.world.groundY(0, 0);
        playerPos = new Pos(0, g, 0);
        host.core.updatePlayerPos(player, playerPos);
        host.area.setViewer(player, playerPos);
        cli.execute(player, playerName, playerPos, "found " + name);
        return host.core.kingdomOfPlayer(player);
    }

    /** Sobe o servidor e roda o laço de ticks até close(). Bloqueia. */
    public void run(int port) throws IOException {
        server = new ServerSocket(port, 8, InetAddress.getLoopbackAddress());
        System.out.println("bridge: ouvindo em 127.0.0.1:" + server.getLocalPort());
        Thread acceptor = new Thread(this::acceptLoop, "bridge-accept");
        acceptor.setDaemon(true);
        acceptor.start();
        loop();
    }

    /** Sobe em segundo plano (testes). Devolve a porta aberta. */
    public int start(int port) throws IOException {
        server = new ServerSocket(port, 8, InetAddress.getLoopbackAddress());
        Thread acceptor = new Thread(this::acceptLoop, "bridge-accept");
        acceptor.setDaemon(true);
        acceptor.start();
        Thread ticker = new Thread(this::loop, "bridge-tick");
        ticker.setDaemon(true);
        ticker.start();
        return server.getLocalPort();
    }

    public void close() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        for (Client c : clients) c.close();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = server.accept();
                s.setTcpNoDelay(true);
                Client c = new Client(s);
                clients.add(c);
                Thread t = new Thread(() -> readLoop(c), "bridge-client");
                t.setDaemon(true);
                t.start();
                inbox.add(() -> c.send(hello()));
            } catch (IOException e) {
                if (running) System.out.println("bridge: accept falhou: " + e.getMessage());
            }
        }
    }

    private void readLoop(Client c) {
        try {
            String line;
            while ((line = c.in.readLine()) != null) {
                final String l = line;
                inbox.add(() -> handle(c, l));
            }
        } catch (IOException ignored) {
        } finally {
            clients.remove(c);
            c.close();
        }
    }

    private void loop() {
        long next = System.nanoTime();
        long frame = 0;
        while (running) {
            Runnable r;
            while ((r = inbox.poll()) != null) {
                try {
                    r.run();
                } catch (RuntimeException e) {
                    System.out.println("bridge: erro num pedido: " + e);
                    e.printStackTrace(System.out);
                }
            }
            synchronized (this) {
                for (int i = 0; i < speed; i++) host.step();
                if (saveDir != null && host.core.tick() % autosaveEvery < speed) {
                    try {
                        WorldSave.save(host, saveDir);
                    } catch (IOException e) {
                        System.out.println("bridge: autosave falhou: " + e);
                    }
                }
            }
            if (!clients.isEmpty()) {
                publishBlocks();
                for (Client c : clients) streamChunks(c);
                if (!pendingFx.isEmpty()) {
                    for (Map<String, Object> fx : pendingFx) broadcast(fx);
                }
                if (++frame % 5 == 0) broadcast(stateJson());
            } else host.world.drainChanges();
            pendingFx.clear();
            next += 50_000_000L;
            long wait = next - System.nanoTime();
            if (wait > 0) {
                try {
                    Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L));
                } catch (InterruptedException e) {
                    return;
                }
            } else if (wait < -500_000_000L) {
                next = System.nanoTime();
            }
        }
    }

    private void handle(Client c, String raw) {
        JsonObject msg;
        try {
            msg = JsonParser.parseString(raw).getAsJsonObject();
        } catch (RuntimeException e) {
            c.send(lines("error", List.of("✗ JSON inválido")));
            return;
        }
        String op = msg.has("op") ? msg.get("op").getAsString() : "";
        switch (op) {
            case "cmd" -> c.send(lines("reply", cli.execute(player, playerName, playerPos, msg.get("line").getAsString())));
            case "player" -> {
                playerPos = new Pos(msg.get("x").getAsInt(), msg.get("y").getAsInt(), msg.get("z").getAsInt());
                host.core.updatePlayerPos(player, playerPos);
                host.area.setViewer(player, playerPos);
            }
            case "view" -> c.setView(msg.get("x").getAsInt(), msg.get("z").getAsInt(), msg.get("r").getAsInt());
            case "call_all" -> c.send(lines("reply", callAll()));
            case "menu_info" -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("op", "menu_info");
                m.put("saved", saveDir != null && WorldSave.exists(saveDir));
                m.put("can_save", saveDir != null);
                Kingdom mine = host.core.kingdomOfPlayer(player);
                m.put("name", mine == null ? "" : mine.name);
                m.put("day", host.core.day());
                c.send(m);
            }
            case "save_now" -> {
                String res;
                try {
                    saveNow();
                    res = saveDir == null ? "⚠ Sem pasta de save (rode com --save)." : "✓ Jogo salvo.";
                } catch (IOException e) {
                    res = "✗ Falha ao salvar: " + e.getMessage();
                }
                c.send(lines("reply", List.of(res)));
            }
            case "new_world" -> newWorld(c, msg);
            case "ai_status" -> c.send(ai.status());
            case "ai_config" -> c.send(ai.configure(msg));
            case "ai_test" -> ai.test().whenComplete((r, err) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("op", "ai_test");
                m.put("text", err == null ? r : "✗ " + err.getMessage());
                m.put("ok", err == null && r != null && r.startsWith("✓"));
                inbox.add(() -> c.send(m)); // a resposta chega depois: volta pela thread do laço
            });
            case "ai_login" -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("op", "ai_login");
                m.put("text", ai.login(c.isLoopback()));
                c.send(m);
            }
            case "speed" -> speed = Math.max(1, Math.min(20, msg.get("mult").getAsInt()));
            case "break" -> c.send(lines("reply", playerBreak(new Pos(msg.get("x").getAsInt(), msg.get("y").getAsInt(), msg.get("z").getAsInt()))));
            case "place" -> c.send(lines("reply", playerPlace(new Pos(msg.get("x").getAsInt(), msg.get("y").getAsInt(), msg.get("z").getAsInt()),
                    msg.get("block").getAsString())));
            case "weather" -> {
                host.sim.setWeather(WorldSim.Weather.valueOf(msg.get("w").getAsString().toUpperCase()), 20L * 120);
                c.send(lines("reply", List.of("✓ Clima: " + host.sim.weather())));
            }
            case "ignite" -> {
                Pos at = new Pos(msg.get("x").getAsInt(), msg.get("y").getAsInt(), msg.get("z").getAsInt());
                boolean ok = host.sim.ignite(at) || host.sim.ignite(at.offset(0, 1, 0));
                c.send(lines("reply", List.of(ok ? "✓ Fogo aceso." : "⚠ Nada inflamável ali.")));
            }
            case "water" -> {
                Pos at = new Pos(msg.get("x").getAsInt(), msg.get("y").getAsInt(), msg.get("z").getAsInt());
                host.fluids.source(at);
                c.send(lines("reply", List.of("✓ Água solta.")));
            }
            case "explode" -> {
                Pos at = new Pos(msg.get("x").getAsInt(), msg.get("y").getAsInt(), msg.get("z").getAsInt());
                int n = host.sim.explode(at, msg.has("r") ? msg.get("r").getAsInt() : 4, 1.0);
                c.send(lines("reply", List.of("✓ Explosão: " + n + " blocos.")));
            }
            default -> c.send(lines("error", List.of("✗ op desconhecida: " + op)));
        }
    }

    // ---- mãos do rei no mundo (o rei age direto, como o jogador no Minecraft)

    List<String> playerBreak(Pos p) {
        if (!host.physical.mayBreak(player, p)) return List.of("✗ Não dá para quebrar aí.");
        BlockType t = host.world.get(p);
        if (!t.breakable()) return List.of("✗ " + t.id() + " não quebra.");
        Map<String, Integer> drops = host.physical.breakBlock(null, p, "minecraft:iron_pickaxe");
        host.physical.give(null, player, drops);
        return List.of();
    }

    List<String> playerPlace(Pos p, String block) {
        BlockType t = Blocks.of(block);
        if (t.air() || Palette.forbidden(t.id())) return List.of("✗ Bloco inválido.");
        BlockType cur = host.world.get(p);
        if (!cur.air() && cur.solid()) return List.of("✗ Já há um bloco aí.");
        host.world.set(p, t);
        if (t.container()) host.world.container(p);
        return List.of();
    }

    /** "Chame todos aqui": uma ação SUMMON validada por NPC do reino do jogador. */
    List<String> callAll() {
        Kingdom k = host.core.kingdomOfPlayer(player);
        if (k == null) return List.of("✗ Funde um reino primeiro (found <nome>).");
        int ok = 0, bad = 0;
        for (Npc n : host.core.citizens(k.id)) {
            if (cli.managerAction(player, ActionType.SUMMON, "npc", n.name).ok()) ok++;
            else bad++;
        }
        return List.of((bad == 0 ? "✓ " : "⚠ ") + ok + " súditos a caminho" + (bad > 0 ? "; " + bad + " não puderam vir" : ""));
    }

    // ---- chunks e blocos

    /** 8 seções 16³ em ordem y-seção, cada uma na ordem do VoxelBuffer (índice = y + 16·(x + 16·z)), 16 bits LE; gzip + base64. */
    public static String encodeChunk(Chunk c) {
        byte[] raw = new byte[Chunk.HEIGHT * Chunk.SIZE * Chunk.SIZE * 2];
        int o = 0;
        for (int sec = 0; sec < Chunk.HEIGHT / 16; sec++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++)
                    for (int y = 0; y < 16; y++) {
                        short v = c.get(x, sec * 16 + y, z);
                        raw[o++] = (byte) (v & 0xFF);
                        raw[o++] = (byte) ((v >> 8) & 0xFF);
                    }
        ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(raw);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Base64.getEncoder().encodeToString(bos.toByteArray());
    }

    private void streamChunks(Client c) {
        int sent = 0;
        while (sent < CHUNKS_PER_TICK && !c.pending.isEmpty()) {
            long k = c.pending.pollFirst();
            if (c.sent.contains(k)) continue;
            int cx = (int) (k >> 32), cz = (int) k;
            Chunk ch = host.world.chunk(cx, cz);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("op", "chunk");
            m.put("cx", cx);
            m.put("cz", cz);
            m.put("d", encodeChunk(ch));
            c.send(m);
            c.sent.add(k);
            sent++;
        }
    }

    private void publishBlocks() {
        List<VoxelWorld.Change> ch = host.world.drainChanges();
        if (ch.isEmpty()) return;
        for (Client c : clients) {
            List<int[]> mine = new ArrayList<>();
            for (VoxelWorld.Change b : ch) {
                long k = Client.key(Math.floorDiv(b.x(), Chunk.SIZE), Math.floorDiv(b.z(), Chunk.SIZE));
                if (c.sent.contains(k)) mine.add(new int[]{b.x(), b.y(), b.z(), b.block()});
            }
            if (mine.isEmpty()) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("op", "blocks");
            m.put("b", mine);
            c.send(m);
        }
    }

    // ---- JSON

    private Map<String, Object> lines(String op, List<String> lines) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("op", op);
        m.put("lines", lines);
        return m;
    }

    private Map<String, Object> hello() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("op", "hello");
        m.put("player", player.toString());
        m.put("tps", 20);
        m.put("seed", host.world.gen().seed());
        m.put("chunk", Chunk.SIZE);
        m.put("height", Chunk.HEIGHT);
        List<Object> table = new ArrayList<>();
        for (BlockType t : Blocks.all()) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("n", t.num());
            o.put("id", t.id());
            o.put("c", t.color());
            o.put("solid", t.solid());
            o.put("fluid", t.fluid());
            o.put("tr", t.translucent());
            table.add(o);
        }
        m.put("blocks", table);
        Kingdom k = host.core.kingdomOfPlayer(player);
        if (k != null && k.center != null) {
            m.put("spawn", List.of(k.center.x(), host.world.groundY(k.center.x(), k.center.z()), k.center.z()));
        } else {
            m.put("spawn", List.of(0, host.world.groundY(0, 0), 0));
        }
        return m;
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private Map<String, Object> stateJson() {
        KingdomsCore core = host.core;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("op", "state");
        m.put("tick", core.tick());
        m.put("day", core.day());
        m.put("time", (core.tick() + 6000) % 24000L);
        m.put("weather", host.sim.weather().name());
        m.put("fires", host.sim.fires().size());
        m.put("flowing", host.fluids.flowing());
        List<int[]> fl = new ArrayList<>();
        for (Pos f : host.sim.fires()) {
            if (fl.size() >= 40) break;
            fl.add(new int[]{f.x(), f.y(), f.z()});
        }
        m.put("firePos", fl);
        Kingdom mine = core.kingdomOfPlayer(player);
        if (mine != null) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("name", mine.name);
            for (var rt : com.kingdomsai.core.kingdom.ResourceType.values()) r.put(rt.name().toLowerCase(), Math.round(mine.get(rt)));
            r.put("pop", core.population(mine.id));
            r.put("housing", core.housingCapacity(mine.id));
            r.put("stability", Math.round(mine.stability));
            r.put("morale", Math.round(mine.morale));
            r.put("army", core.military(mine.id));
            m.put("realm", r);
        }
        int dmg = 0;
        for (var set : host.sim.damage().values()) dmg += set.size();
        m.put("damage", dmg);
        List<Object> ks = new ArrayList<>();
        for (Kingdom k : core.state().kingdoms.values()) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", k.id.toString());
            o.put("name", k.name);
            o.put("color", k.color);
            o.put("player", k.isPlayerKingdom());
            if (k.center != null) {
                o.put("x", k.center.x());
                o.put("z", k.center.z());
            }
            ks.add(o);
        }
        m.put("kingdoms", ks);
        List<Object> ns = new ArrayList<>();
        for (Npc n : core.allAlive()) {
            if (n.pos == null) continue;
            NpcMover.Walker w = host.mover.walker(n.id);
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", n.id.toString());
            o.put("name", n.name);
            o.put("k", n.kingdomId.toString());
            o.put("prof", n.profession.name());
            o.put("act", n.activity.name());
            o.put("x", w != null ? round1(w.x) : n.pos.x() + 0.5);
            o.put("y", w != null ? round1(w.y) : n.pos.y());
            o.put("z", w != null ? round1(w.z) : n.pos.z() + 0.5);
            if (w != null) o.put("yaw", round1(w.yaw));
            o.put("body", w != null);
            o.put("task", n.currentTask);
            o.put("held", n.heldItem);
            ns.add(o);
        }
        m.put("npcs", ns);
        List<Object> bs = new ArrayList<>();
        for (Kingdom k : core.state().kingdoms.values()) {
            for (Building b : core.buildings(k.id)) {
                if (b.origin == null) continue;
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("id", b.id.toString());
                o.put("k", k.id.toString());
                o.put("bp", b.blueprintId);
                o.put("x", b.origin.x());
                o.put("y", b.origin.y());
                o.put("z", b.origin.z());
                o.put("status", b.status.name());
                o.put("pct", b.percent());
                bs.add(o);
            }
        }
        m.put("buildings", bs);
        return m;
    }

    private void broadcast(Map<String, Object> msg) {
        String json = gson.toJson(msg);
        for (Client c : clients) c.sendRaw(json);
    }

    private final class Client {
        final Socket socket;
        final BufferedReader in;
        final BufferedWriter out;
        final Set<Long> sent = new HashSet<>();
        final ArrayDeque<Long> pending = new ArrayDeque<>();

        Client(Socket s) throws IOException {
            socket = s;
            in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
        }

        static long key(int cx, int cz) {
            return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
        }

        /** Chunks num raio ao redor do ponto, do mais perto para o mais longe, que o cliente ainda não tem. */
        void setView(int x, int z, int r) {
            int cx0 = Math.floorDiv(x, Chunk.SIZE), cz0 = Math.floorDiv(z, Chunk.SIZE);
            r = Math.max(1, Math.min(24, r));
            // o Godot descarta blocos fora do alcance do viewer: quem saiu da janela é reenviado se o rei voltar
            final int rr = r;
            sent.removeIf(key -> Math.max(Math.abs((int) (key >> 32) - cx0), Math.abs((int) (long) key - cz0)) > rr + 1);
            List<long[]> list = new ArrayList<>();
            for (int dz = -r; dz <= r; dz++)
                for (int dx = -r; dx <= r; dx++) {
                    int cx = cx0 + dx, cz = cz0 + dz;
                    if (!host.world.inBounds(cx * Chunk.SIZE, cz * Chunk.SIZE)) continue;
                    long k = key(cx, cz);
                    if (!sent.contains(k)) list.add(new long[]{k, (long) dx * dx + (long) dz * dz});
                }
            list.sort(Comparator.comparingLong(a -> a[1]));
            pending.clear();
            for (long[] e : list) pending.add(e[0]);
        }

        void send(Map<String, Object> msg) {
            sendRaw(gson.toJson(msg));
        }

        boolean isLoopback() {
            return socket.getInetAddress() != null && socket.getInetAddress().isLoopbackAddress();
        }

        synchronized void sendRaw(String json) {
            try {
                out.write(json);
                out.write('\n');
                out.flush();
            } catch (IOException e) {
                clients.remove(this);
                close();
            }
        }

        void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
