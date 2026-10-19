package com.kingdomsai.core;

import com.kingdomsai.core.llm.*;

import java.util.concurrent.TimeUnit;

/** F7: provider por chave de API contra um servidor HTTP falso (sem rede). */
public final class ApiProviderSelfTest {
    private static int passed, failed;

    public static void main(String[] args) throws Exception {
        int[] r = run();
        System.out.println("\n" + r[0] + " passaram, " + r[1] + " falharam.");
        System.exit(r[1] == 0 ? 0 : 1);
    }

    public static int[] run() throws Exception {
        passed = failed = 0;
        System.out.println("\n# Provider por chave de API");
        final String[] seen = new String[3];
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/messages", ex -> {
            seen[0] = ex.getRequestHeaders().getFirst("x-api-key");
            seen[1] = ex.getRequestHeaders().getFirst("anthropic-version");
            seen[2] = new String(ex.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            boolean bad = "bad".equals(seen[0]);
            byte[] out = (bad ? "{\"error\":{\"message\":\"invalid x-api-key\"}}"
                    : "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"reply\\\":\\\"ok\\\",\\\"actions\\\":[]}\"}],\"usage\":{\"input_tokens\":1000,\"output_tokens\":100,\"cache_read_input_tokens\":2000,\"cache_creation_input_tokens\":500}}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ex.sendResponseHeaders(bad ? 401 : 200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        try {
            LlmConfig cfg = new LlmConfig();
            cfg.provider = "anthropic_api";
            cfg.endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
            cfg.apiKey = "sk-test";
            AnthropicApiProvider p = new AnthropicApiProvider();
            ProviderResult r = p.completeDetailed(new LlmRequest("agenda", "SISTEMA FIXO", "pergunta", null, null, ""), cfg).get(10, TimeUnit.SECONDS);
            check("resposta lida e entregue ao Plan", Plan.parse(r.text()).reply().equals("ok"));
            check("chave e versão vão nos cabeçalhos", "sk-test".equals(seen[0]) && "2023-06-01".equals(seen[1]));
            check("modelo da finalidade (haiku → claude-haiku-5-5) e cache_control no sistema", seen[2].contains("claude-haiku-5-5") && seen[2].contains("cache_control") && seen[2].contains("SISTEMA FIXO"));
            double expected = (1000 * 0.10 + 100 * 0.50 + 500 * 0.10 * 1.25 + 2000 * 0.10 * 0.1) / 1e6;
            check("custo calculado dos tokens (incl. cache)", Math.abs(r.usage().costUsd() - expected) < 1e-12 && r.usage().cacheReadTokens() == 2000);
            cfg.apiKey = "bad";
            String err;
            try {
                p.completeDetailed(new LlmRequest("agenda", "s", "u", null, null, ""), cfg).get(10, TimeUnit.SECONDS);
                err = "";
            } catch (Exception e) {
                err = String.valueOf(e.getCause() == null ? e : e.getCause().getMessage());
            }
            check("401 vira mensagem clara", err.contains("Chave de API recusada"));
            LlmConfig none = cfg.copy();
            none.apiKey = "";
            check("sem chave: erro claro (ou usa ANTHROPIC_API_KEY)", System.getenv("ANTHROPIC_API_KEY") != null || failsWith(p, none, "Chave de API ausente"));
        } finally {
            server.stop(0);
        }
        return new int[]{passed, failed};
    }

    private static boolean failsWith(AnthropicApiProvider p, LlmConfig c, String frag) {
        try {
            p.completeDetailed(new LlmRequest("agenda", "s", "u", null, null, ""), c).get(5, TimeUnit.SECONDS);
            return false;
        } catch (Exception e) {
            return String.valueOf(e.getCause() == null ? e.getMessage() : e.getCause().getMessage()).contains(frag);
        }
    }

    private static void check(String name, boolean ok) {
        if (ok) passed++;
        else failed++;
        System.out.println((ok ? "  [OK]   " : "  [FAIL] ") + name);
    }
}
