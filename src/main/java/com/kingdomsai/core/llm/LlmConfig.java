package com.kingdomsai.core.llm;

import java.util.List;
import java.util.Set;

/** Equivalente à seção [ai] do kingdomsai-common.toml. */
public final class LlmConfig {
    public boolean enabled = true;
    /** ollama | openai | claude_code | mock */
    public String provider = "ollama";
    public String model = "qwen2.5:7b";
    /** Modelo pequeno para a vida dos súditos (agenda, conversa, fala com o rei); vazio = o mesmo modelo do conselho. */
    public String npcModel = "";
    public String endpoint = "http://127.0.0.1:11434";
    public String apiKey = "";
    public double temperature = 0.4;
    public int maxContextChars = 6000;
    public int timeoutMs = 15000;
    public int maxRetries = 2;
    public int maxCallsPerMinute = 20;
    /** Chamadas ao modelo em andamento ao mesmo tempo (o resto espera na fila com prioridade). */
    public int maxConcurrentCalls = 3;
    /** Se a LLM falhar, usa o interpretador por regras (o jogo nunca congela). */
    public boolean fallbackToRules = true;
    /** claude_code: o executável do Claude Code (claude, claude.exe, claude.cmd ou o caminho completo). */
    public String claudeCommand = "claude";
    /** claude_code, súditos (agenda, conversa, fala com o rei): haiku (rápido, barato) · sonnet · opus · fable. */
    public String claudeNpcModel = "haiku";
    /** claude_code, conselho e decisões raras (conselho, rei rival, arquiteto, militar, reparo): sonnet por padrão. */
    public String claudeCouncilModel = "sonnet";
    /** claude_code: esforço de raciocínio (low|medium|high|xhigh|max); vazio = padrão do modelo. */
    public String claudeEffort = "";
    /** Teto de gasto estimado por sessão de jogo, em US$; 0 = sem teto. Estourou, a LLM desliga e o jogo segue pelas regras. */
    public double budgetUsd = 0;
    /** claude_code: trava extra por chamada (--max-budget-usd), em US$. */
    public double claudeCallBudgetNpcUsd = 0.05;
    public double claudeCallBudgetCouncilUsd = 0.25;

    public static final List<String> EFFORTS = List.of("", "low", "medium", "high", "xhigh", "max");
    private static final Set<String> NPC_PURPOSES = Set.of("agenda", "conversa", "npc_dialogue", "test");

    public boolean isClaudeCode() {
        return provider != null && (provider.equalsIgnoreCase(ClaudeCodeProvider.NAME) || isApi());
    }

    /** Provider por chave de API da Anthropic (usa os mesmos modelos claude* do Claude Code). */
    public boolean isApi() {
        return provider != null && provider.equalsIgnoreCase(AnthropicApiProvider.NAME);
    }

    /** O Claude Code abre um processo por pergunta: nunca menos de 60 s. */
    public int effectiveTimeoutMs() {
        return provider != null && provider.equalsIgnoreCase(ClaudeCodeProvider.NAME) ? Math.max(timeoutMs, ClaudeCodeProvider.MIN_TIMEOUT_MS) : timeoutMs;
    }

    /** Pedido da vida dos súditos (modelo pequeno) ou decisão rara do conselho (modelo maior)? */
    public static boolean isNpcPurpose(String purpose) {
        return purpose == null || NPC_PURPOSES.contains(purpose);
    }

    /** Modelo para este tipo de pedido: súditos comuns no pequeno, conselho e ordens do rei no maior. */
    public String modelFor(String purpose) {
        boolean small = "agenda".equals(purpose) || "conversa".equals(purpose) || "npc_dialogue".equals(purpose);
        return small && npcModel != null && !npcModel.isBlank() ? npcModel : model;
    }

    /** Claude Code: mesma regra de {@link #modelFor}, com os modelos próprios do Claude. */
    public String claudeModelFor(String purpose) {
        return isNpcPurpose(purpose) ? claudeNpcModel : claudeCouncilModel;
    }

    /** Claude Code: teto por chamada, conforme a finalidade. */
    public double claudeCallBudgetFor(String purpose) {
        return isNpcPurpose(purpose) ? claudeCallBudgetNpcUsd : claudeCallBudgetCouncilUsd;
    }

    /** Modelo em uso, conforme o provider (Claude Code: "súditos/conselho"). */
    public String activeModel() {
        return isClaudeCode() ? claudeNpcModel + "/" + claudeCouncilModel : model;
    }

    public LlmConfig() {}

    /** Cópia rasa (todos os campos são primitivos ou String). */
    public LlmConfig copy() {
        LlmConfig c = new LlmConfig();
        c.enabled = enabled;
        c.provider = provider;
        c.model = model;
        c.npcModel = npcModel;
        c.endpoint = endpoint;
        c.apiKey = apiKey;
        c.temperature = temperature;
        c.maxContextChars = maxContextChars;
        c.timeoutMs = timeoutMs;
        c.maxRetries = maxRetries;
        c.maxCallsPerMinute = maxCallsPerMinute;
        c.maxConcurrentCalls = maxConcurrentCalls;
        c.fallbackToRules = fallbackToRules;
        c.claudeCommand = claudeCommand;
        c.claudeNpcModel = claudeNpcModel;
        c.claudeCouncilModel = claudeCouncilModel;
        c.claudeEffort = claudeEffort;
        c.budgetUsd = budgetUsd;
        c.claudeCallBudgetNpcUsd = claudeCallBudgetNpcUsd;
        c.claudeCallBudgetCouncilUsd = claudeCallBudgetCouncilUsd;
        return c;
    }
}
