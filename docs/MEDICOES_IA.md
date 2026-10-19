# Medições da IA (Claude Code como provider)

Medido em **19/10/2026**, Windows 11, Claude Code **2.1.303**, login de assinatura (o custo é a *estimativa do cliente*: `total_cost_usd`).
Ferramenta: `src/test/java/com/kingdomsai/live/ClaudeCodeBench.java` (manual, faz chamadas reais):

```
./gradlew compileLiveJava
java -cp build/classes/java/live;build/classes/java/main;gson.jar com.kingdomsai.live.ClaudeCodeBench 20 [finalidades] [paralelo]
# variáveis opcionais: BENCH_NPC_MODEL, BENCH_COUNCIL_MODEL, BENCH_EFFORT
```

Cada finalidade faz 20 chamadas **sequenciais** com contexto real do `ContextBuilder`/`LifeSystem` num mundo de teste (14 súditos).
A latência é de relógio (processo novo por pergunta, API incluída). As duas rodadas foram feitas com o PC ocupado (compilações em paralelo): a latência tem ruído de ±30 %.

## Rodada 1 — antes da F3.2 (prompt de sistema com o nome do súdito no começo)

| finalidade | modelo | p50 (s) | p95 (s) | custo médio (US$) | entrada (tok) | saída (tok) | cache criado | cache lido | JSON válido | falhas |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| npc_dialogue | haiku | 4.4 | 6.5 | 0.0009 | 2 | 347 | 3562 | 4755 | 20/20 | 0 |
| council | sonnet | 4.5 | 7.3 | 0.0064 | 2 | 224 | 856 | 6798 | 20/20 | 0 |
| agenda | haiku | 6.2 | 7.9 | 0.0004 | 2 | 515 | 438 | 1336 | 20/20 | 0 |
| conversa | haiku | 7.0 | 9.0 | 0.0007 | 2 | 1035 | 749 | 790 | 20/20 | 0 |

Gasto do bench: US$ 0,1665.

## Rodada 2 — depois da F3.2 (prompt de sistema e schema estáticos por finalidade)

| finalidade | modelo | p50 (s) | p95 (s) | custo médio (US$) | entrada (tok) | saída (tok) | cache criado | cache lido | JSON válido | falhas |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| npc_dialogue | haiku | 4.5 | 6.0 | 0.0004 | 2 | 333 | 1010 | 7339 | 20/20 | 0 |
| council | sonnet | 7.0 | 12.0 | 0.0064 | 2 | 229 | 865 | 6808 | 20/20 | 0 |
| agenda | haiku | 5.6 | 6.9 | 0.0004 | 2 | 547 | 439 | 1337 | 20/20 | 0 |
| conversa | haiku | 7.3 | 8.8 | 0.0006 | 2 | 979 | 549 | 986 | 20/20 | 0 |

Gasto do bench: US$ 0,1574.

### O que mudou e o que não mudou

- **`npc_dialogue`**: o custo médio caiu de US$ 0,0009 para US$ 0,0004 (−55 %) e o cache criado por chamada de 3.562 para 1.010 tokens. Era a única finalidade cujo prompt de sistema tinha o nome/cargo/reino do súdito *antes* das regras; agora isso vai para `WORLD DATA` e o prefixo é igual para todos os súditos.
- **`council`, `agenda`, `conversa`**: já tinham prompt de sistema estático; o custo ficou igual e `cache lido > 0` desde a 2ª chamada (o cache de 1 h do CLI funciona: `ephemeral_1h_input_tokens`). O p50 do conselho piorou na rodada 2, mas com 20 amostras e o PC ocupado não dá para atribuir à mudança.
- **JSON válido em 80/80 chamadas** nas duas rodadas, com `--json-schema` (o CLI devolve `structured_output`).
- **Custo por hora de jogo** (estimativa, 100 % de chamadas pelo CLI): 8 chamadas/min de haiku ≈ 8 × 60 × 0,0005 ≈ **US$ 0,24/h** (a tabela do plano previa US$ 1,50/h com o custo antigo de US$ 0,0031/chamada).

## Por que a latência não cai (achados, F3.3)

1. **O tempo é dominado pelo raciocínio do modelo.** Para uma resposta de ~100 tokens de JSON, a saída cobrada é de 330–1.000 tokens: o resto são `thinking_tokens` (ex.: 251 de 332 em `claude -p` sem schema). Segundo a documentação das variáveis de ambiente, **Haiku 5.5, Sonnet 5.5 e Opus 5.5 não aceitam desligar o raciocínio** (`MAX_THINKING_TOKENS=0` não tem efeito neles; medido: continuou com ~200 tokens de raciocínio). `--effort low` também não reduziu de forma mensurável (agenda: 441 vs 473 tokens de saída; conversa: 774 vs 741).
2. **Pré-aquecer processos não ajuda.** Testado: abrir o processo e só escrever na entrada padrão depois de 1,2 s ou 2,5 s deixa o tempo *depois do stdin* igual (~2,3 s); com 5 s o CLI desiste de esperar e fecha a entrada ("pipe sendo fechado"). A inicialização não corre em paralelo com a espera pela entrada. **O pool de processos pré-aquecidos não foi implementado.**
3. **Processo persistente com `--input-format stream-json`**: medido com 4 perguntas seguidas, cada turno levou 1,5–1,9 s contra ~2,3 s do processo novo (≈ −0,5 a −0,8 s), mas o histórico acumula (o cache lido sobe a cada turno), mistura NPCs na mesma conversa e custa mais com o tempo. Ganho pequeno para risco grande: **não implementado**.
4. `--exclude-dynamic-system-prompt-sections` só vale sem `--system-prompt`/`--system-prompt-file`; medido igual ao padrão (2ª chamada leu 1.871 tokens de cache nos dois casos).
5. A concorrência é limitada pelo `LlmGateway` (padrão `maxConcurrentCalls = 3`, configurável): a fila é única, com prioridade (fala do rei > conselho/rival > arquiteto > agenda > conversa entre súditos), pedidos de fundo idênticos são fundidos e os que vencem na fila (agenda 90 s, conversa 12 s de jogo) são descartados sem chamar o modelo.

Conclusão prática: latência de 4–7 s por chamada é intrínseca a este caminho (CLI + raciocínio obrigatório). A mitigação no jogo é a fala imediata por regras ("Vou pensar, majestade…") e a fila com prioridade — ou, na F7, o provider por chave de API, onde dá para medir o efeito de `effort`/saída estruturada direto na API.

## Conselho do rei rival (medição única, F4)

Uma chamada real de `rival_king` (Sonnet, contexto de 1,6 mil caracteres, schema do rei rival): 6,7 s, US$ 0,0146, JSON válido; a decisão foi coerente (postura defensiva por 7 dias, `RECRUIT` executado, `NEGOTIATE` de aliança recusado pelas regras, mensagem ao jogador).
