package com.kingdomsai.core.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Messages API da Anthropic direto (chave do jogador, sem SDK). Para jogo distribuído: não depende do login de ninguém.
 * Cache de prompt: o prompt de sistema (estático por finalidade) vai como bloco com {@code cache_control}. A saída é
 * lida com o parser tolerante do {@link Plan}; a chave vem de {@code ANTHROPIC_API_KEY} (ou do painel) e nunca é salva.
 */
public final class AnthropicApiProvider implements LlmProvider {
    public static final String NAME = "anthropic_api";
    public static final String DEFAULT_ENDPOINT = "https://api.anthropic.com";
    private static final String VERSION = "2023-06-01";
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Override
    public String name() {
        return NAME;
    }

    /** Modelo da API para a finalidade (apelidos do jogo → ids). */
    public static String modelId(String alias) {
        return switch (alias == null ? "" : alias) {
            case "haiku" -> "claude-haiku-5-5";
            case "sonnet" -> "claude-sonnet-5-5";
            case "opus" -> "claude-opus-5-5";
            case "fable" -> "claude-fable-5-1";
            default -> alias;
        };
    }

    /** US$ por milhão de tokens (entrada, saída) da tabela pública; escrita de cache 2× a entrada, leitura 0,1× (haiku) ou 0,05×. */
    static double[] price(String model) {
        if (model.contains("haiku")) return new double[]{0.10, 0.50, 0.1};
        if (model.contains("opus")) return new double[]{4, 20, 0.05};
        return new double[]{2, 10, 0.05};
    }

    @Override
    public CompletableFuture<String> complete(LlmRequest request, LlmConfig cfg) {
        return completeDetailed(request, cfg).thenApply(ProviderResult::text);
    }

    @Override
    public CompletableFuture<ProviderResult> completeDetailed(LlmRequest request, LlmConfig cfg) {
        String key = cfg.apiKey != null && !cfg.apiKey.isBlank() ? cfg.apiKey : System.getenv("ANTHROPIC_API_KEY");
        if (key == null || key.isBlank()) return CompletableFuture.failedFuture(new IllegalStateException("Chave de API ausente: defina ANTHROPIC_API_KEY."));
        String model = modelId(cfg.claudeModelFor(request.purpose()));
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("max_tokens", LlmConfig.isNpcPurpose(request.purpose()) ? 600 : 1500);
        JsonArray sys = new JsonArray();
        JsonObject block = new JsonObject();
        block.addProperty("type", "text");
        block.addProperty("text", request.system() == null ? "" : request.system());
        JsonObject cc = new JsonObject();
        cc.addProperty("type", "ephemeral");
        block.add("cache_control", cc);
        sys.add(block);
        body.add("system", sys);
        JsonArray msgs = new JsonArray();
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", request.user() == null ? "" : request.user());
        msgs.add(user);
        body.add("messages", msgs);
        String base = cfg.endpoint != null && cfg.endpoint.startsWith("http") && !cfg.endpoint.contains("11434") ? cfg.endpoint : DEFAULT_ENDPOINT;
        HttpRequest req = HttpRequest.newBuilder(URI.create(base.replaceAll("/+$", "") + "/v1/messages")).timeout(Duration.ofMillis(cfg.effectiveTimeoutMs()))
                .header("content-type", "application/json").header("x-api-key", key).header("anthropic-version", VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        long t0 = System.currentTimeMillis();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(resp -> parse(resp.statusCode(), resp.body(), model, System.currentTimeMillis() - t0));
    }

    static ProviderResult parse(int status, String body, String model, long ms) {
        if (status / 100 != 2) {
            String msg = body;
            try {
                msg = JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("error").get("message").getAsString();
            } catch (RuntimeException ignored) {
            }
            throw new IllegalStateException(status == 401 ? "Chave de API recusada (401)." : status == 429 ? "Limite de uso da API (429): " + msg : "API " + status + ": " + msg);
        }
        JsonObject o = JsonParser.parseString(body).getAsJsonObject();
        StringBuilder text = new StringBuilder();
        for (var c : o.getAsJsonArray("content")) {
            JsonObject b = c.getAsJsonObject();
            if ("text".equals(b.get("type").getAsString())) text.append(b.get("text").getAsString());
        }
        JsonObject u = o.has("usage") ? o.getAsJsonObject("usage") : new JsonObject();
        long in = n(u, "input_tokens"), out = n(u, "output_tokens"), read = n(u, "cache_read_input_tokens"), create = n(u, "cache_creation_input_tokens");
        double[] p = price(model.toLowerCase(Locale.ROOT));
        double cost = (in * p[0] + out * p[1] + create * p[0] * 1.25 + read * p[0] * p[2]) / 1e6;
        return new ProviderResult(Text(text.toString()), new ProviderResult.Usage(cost, in, out, read, create, ms, model));
    }

    private static String Text(String s) {
        String t = s.strip();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            t = nl < 0 ? t.substring(3) : t.substring(nl + 1);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
        }
        return t.strip();
    }

    private static long n(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsLong() : 0;
    }
}
