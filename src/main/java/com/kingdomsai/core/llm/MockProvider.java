package com.kingdomsai.core.llm;

import com.kingdomsai.core.KingdomsCore;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.npc.Npc;

import java.util.concurrent.CompletableFuture;

/** MockProvider: testa o jogo inteiro sem IA. Responde com o mesmo JSON que uma LLM real. */
public final class MockProvider implements LlmProvider {
    private final KingdomsCore core;
    private final RuleInterpreter rules;

    public MockProvider(KingdomsCore core) {
        this.core = core;
        this.rules = new RuleInterpreter(core);
    }

    @Override
    public String name() {
        return "regras";
    }

    @Override
    public CompletableFuture<String> complete(LlmRequest request, LlmConfig config) {
        // Executa na thread chamadora (servidor): só lê o estado.
        try {
            return CompletableFuture.completedFuture(interpret(request).toJson());
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    public Plan interpret(LlmRequest request) {
        Kingdom k = core.kingdom(request.kingdomId());
        Npc n = core.npc(request.npcId());
        if (k == null) return new Plan("...", java.util.List.of());
        return rules.interpret(k, n, request.rawPlayerText());
    }
}
