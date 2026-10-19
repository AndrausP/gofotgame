package com.kingdomsai.core.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Provedores HTTP. Usa HttpURLConnection (java.base) numa thread própria — nada roda na thread do servidor
 * e não depende do módulo java.net.http no ambiente modular do NeoForge.
 */
public final class HttpProviders {
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "KingdomsAI-LLM");
        t.setDaemon(true);
        return t;
    });

    private HttpProviders() {}

    private static JsonArray messages(LlmRequest r) {
        JsonArray msgs = new JsonArray();
        JsonObject sys = new JsonObject();
        sys.addProperty("role", "system");
        sys.addProperty("content", r.system());
        msgs.add(sys);
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", r.user());
        msgs.add(user);
        return msgs;
    }

    private static String trimSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static CompletableFuture<String> post(String url, String body, String apiKey, int timeoutMs) {
        return CompletableFuture.supplyAsync(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) URI.create(url).toURL().openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(Math.min(3000, timeoutMs));
                c.setReadTimeout(timeoutMs);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                if (apiKey != null && !apiKey.isBlank()) c.setRequestProperty("Authorization", "Bearer " + apiKey);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
                int code = c.getResponseCode();
                InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
                String resp = in == null ? "" : readAll(in);
                if (code != 200) throw new IllegalStateException("HTTP " + code + ": " + resp);
                return resp;
            } catch (IOException e) {
                throw new IllegalStateException(e.getClass().getSimpleName() + ": " + e.getMessage(), e);
            } finally {
                if (c != null) c.disconnect();
            }
        }, POOL);
    }

    public static CompletableFuture<String> get(String url, String apiKey, int timeoutMs) {
        return CompletableFuture.supplyAsync(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) URI.create(url).toURL().openConnection();
                c.setConnectTimeout(Math.min(3000, timeoutMs));
                c.setReadTimeout(timeoutMs);
                if (apiKey != null && !apiKey.isBlank()) c.setRequestProperty("Authorization", "Bearer " + apiKey);
                int code = c.getResponseCode();
                InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
                String resp = in == null ? "" : readAll(in);
                if (code != 200) throw new IllegalStateException("HTTP " + code);
                return resp;
            } catch (IOException e) {
                throw new IllegalStateException(e.getClass().getSimpleName() + ": " + e.getMessage(), e);
            } finally {
                if (c != null) c.disconnect();
            }
        }, POOL);
    }

    /** Lista os modelos disponíveis no servidor (Ollama: /api/tags · OpenAI: /v1/models). */
    public static CompletableFuture<java.util.List<String>> listModels(LlmConfig cfg) {
        String base = trimSlash(cfg.endpoint);
        boolean ollama = cfg.provider.equalsIgnoreCase("ollama");
        String url = ollama ? base + "/api/tags" : (base.endsWith("/v1") ? base + "/models" : base + "/v1/models");
        return get(url, ollama ? null : cfg.apiKey, Math.min(cfg.timeoutMs, 5000)).thenApply(resp -> {
            java.util.List<String> out = new java.util.ArrayList<>();
            JsonObject o = JsonParser.parseString(resp).getAsJsonObject();
            JsonArray arr = ollama ? o.getAsJsonArray("models") : o.getAsJsonArray("data");
            if (arr != null) for (var e : arr) {
                JsonObject m = e.getAsJsonObject();
                out.add(m.has("name") ? m.get("name").getAsString() : m.get("id").getAsString());
            }
            return out;
        });
    }

    private static String readAll(InputStream in) throws IOException {
        try (in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    /** Janela de contexto para o pedido: ~3 caracteres por token + 1024 para a resposta, em múltiplos de 2048 (4k a 32k). */
    public static int contextWindow(LlmRequest request) {
        int chars = (request.system() == null ? 0 : request.system().length()) + (request.user() == null ? 0 : request.user().length());
        int tokens = chars / 3 + 1024;
        int rounded = ((tokens + 2047) / 2048) * 2048;
        return Math.max(4096, Math.min(32768, rounded));
    }

    /** Ollama local: POST /api/chat com format=json. */
    public static final class Ollama implements LlmProvider {
        @Override
        public String name() {
            return "ollama";
        }

        @Override
        public CompletableFuture<String> complete(LlmRequest request, LlmConfig cfg) {
            JsonObject body = new JsonObject();
            body.addProperty("model", cfg.modelFor(request.purpose()));
            body.add("messages", messages(request));
            body.addProperty("stream", false);
            body.addProperty("format", "json");
            JsonObject options = new JsonObject();
            options.addProperty("temperature", cfg.temperature);
            // Sem num_ctx o Ollama usa a janela padrão (2–4 mil tokens) e CORTA o começo do prompt em silêncio —
            // justamente as regras do sistema. Pede o que o prompt precisa (+ espaço para a resposta).
            options.addProperty("num_ctx", contextWindow(request));
            body.add("options", options);
            return post(trimSlash(cfg.endpoint) + "/api/chat", body.toString(), null, cfg.timeoutMs).thenApply(resp ->
                    JsonParser.parseString(resp).getAsJsonObject().getAsJsonObject("message").get("content").getAsString());
        }
    }

    /** Qualquer servidor compatível com OpenAI (/v1/chat/completions): LM Studio, vLLM, OpenAI etc. */
    public static final class OpenAiCompatible implements LlmProvider {
        @Override
        public String name() {
            return "openai";
        }

        @Override
        public CompletableFuture<String> complete(LlmRequest request, LlmConfig cfg) {
            JsonObject body = new JsonObject();
            body.addProperty("model", cfg.modelFor(request.purpose()));
            body.add("messages", messages(request));
            body.addProperty("temperature", cfg.temperature);
            JsonObject fmt = new JsonObject();
            fmt.addProperty("type", "json_object");
            body.add("response_format", fmt);
            String base = trimSlash(cfg.endpoint);
            String url = base.endsWith("/v1") ? base + "/chat/completions" : base + "/v1/chat/completions";
            return post(url, body.toString(), cfg.apiKey, cfg.timeoutMs).thenApply(resp ->
                    JsonParser.parseString(resp).getAsJsonObject().getAsJsonArray("choices").get(0).getAsJsonObject()
                            .getAsJsonObject("message").get("content").getAsString());
        }
    }
}
