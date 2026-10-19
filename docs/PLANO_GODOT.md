# Plano: jogo em Godot com as mecânicas do Kingdoms AI

Objetivo: um jogo próprio, no Godot, onde uma sociedade de NPCs movidos por regras e LLM cria relações, leis e conflitos num mundo gerado que pode ser destruído e se deteriora. O Core do mod (Java puro, sem nada de Minecraft, com 479 testes) já resolve a parte difícil — sociedade, economia, ordens, petições e leis — e fala com o mundo por interfaces; o Godot só precisa ocupar o lugar do adaptador do Minecraft.

## 1. Decisão principal: o que fazer com o Core

Recomendação: opção A no protótipo; portar para C# (B) só se o jogo se provar divertido. Os testes do Java viram a especificação do port.

| Opção | Como funciona | Prós | Contras |
| --- | --- | --- | --- |
| A. Core Java como processo à parte | O Godot conversa com o Core por socket local/JSON | Reaproveita tudo hoje, com os 479 testes; protótipo em semanas | Embute uma JVM; a conversa entre os dois tem custo e latência |
| B. Portar o Core para C# (Godot .NET) | Reescrita do Core dentro do Godot | Um só executável, mais leve, mais fácil de distribuir | Reescrita grande; só vale depois de validar o jogo |
| C. GDScript | Reescrita em GDScript | Nenhum relevante | Lento e difícil de testar para uma simulação desse tamanho |

## 2. Mundo gerado, destrutível e que se deteriora

- **Representação:** chunks (por exemplo 32³), cada voxel com material e integridade.
- **Motor:** começar com o módulo Zylann `godot_voxel` (terreno, chunks e malhas já resolvidos). Custo: compilar o Godot com o módulo.
- **Geração:** ruído em camadas (relevo, bioma, rios, minérios, vegetação) com semente determinística. Reinos rivais nascem de pontos escolhidos pela geração.
- **Destruição:** qualquer voxel quebra e cai, com suporte estrutural simples (sem apoio, desmorona). Fogo, inundação e explosão são regras sobre voxels.
- **Deterioração:** cada voxel perde integridade com idade, clima, fogo e falta de reparo; o resultado são ruínas e mato por cima.
- **Reparo:** vira tarefa dos NPCs, no mesmo sistema de obras e ordens do Core.
- **Desempenho:** só chunks perto do jogador são simulados em detalhe; o resto roda abstrato, como o Core já faz com trabalho longe da vista.

## 3. Liberdade máxima: leis como regras sobre propriedades

Em vez de ações fixas, cada objeto tem propriedades (material, cor, dono, valor, estado) e uma lei vira uma regra sobre propriedades. A LLM só escreve a regra, nunca altera o estado direto.

- Exemplo: "ninguém com cama vermelha" = para todo objeto com tag `cama` e cor `vermelho` em casa de súdito, trocar.
- O Core compara a regra com o mundo e cria a tarefa de cumprir; quem desobedece perde lealdade e quem cumpre ganha um pouco.
- Vale para outras regras do mesmo tipo: casas só de madeira, nada de ouro fora do baú.
- Mantém o princípio atual: tudo passa pelo validador de ações. A LLM combina um vocabulário pequeno (construir, coletar, mover, trocar, proibir, punir), não inventa ações novas.
- Hoje no mod, uma lei em texto livre só chega aos súditos como conhecimento; apenas toque de recolher, paz e festa têm efeito mecânico. O Godot generaliza isso.

## 4. Sociedade e LLM

- **Pensamento em camadas:** regras sempre; LLM para líderes, conflitos e leis, com orçamento por minuto.
- **Modelos locais:** servidor `llama.cpp` ou Ollama ao lado do jogo; modelo pequeno para NPCs comuns, maior para o conselho. A viabilidade só se sabe medindo no hardware do jogador.
- **Memória e relações:** o que já existe (memórias, afeto, rivalidade, boatos, petições, decretos) passa direto.
- **Escala:** meta de centenas de NPCs. Os longe da câmera viram simulação de grupo (produção e eventos agregados); quem está perto ou envolvido num evento acorda com tudo detalhado.

## 5. Etapas do projeto

Cada etapa só começa com a anterior funcionando; a última é uma decisão, não uma entrega.

1. **Provar a ponte:** Godot mostra terreno e NPCs; o Core Java comanda. Entrega: "chame todos aqui" funcionando no Godot. **Feito** (`godot/`, `com.kingdomsai.bridge`, `BridgeSelfTest`): relevo, súditos e obras aparecem e o botão chama todos (a etapa 2 trocou a malha de altura pelo mundo voxel).
2. **Mundo vivo:** geração, quebrar e colocar voxels, obras dos NPCs, fome e trabalho. **Feito** (`com.kingdomsai.voxel`: gerador por ruído, `VoxelPhysicalPort`, `VoxelBuilder`, `NpcMover` com A*; o Godot desenha o mundo com `godot_voxel`; o rei quebra/coloca blocos; salvar/carregar em `WorldSave`).
3. **Deterioração e clima:** integridade, fogo, chuva, ruínas e reparo. **Feito** (`WorldSim`, `RepairCrew`, `Fluids`): integridade por bloco construído, desgaste com chuva, ruína e mato, fogo que se espalha e a chuva apaga, desabamento sem apoio, explosão, inundação com nível, reparo como trabalho de súditos que gasta material do reino.
4. **Sociedade:** petições e leis por propriedade, relações, política entre reinos. **Feito** (`core/law`: `PropertyRule`, `Props`, `LawEnforcer`, `LawText`, tarefa `REPLACE`): a lei vira regra sobre propriedades ("nenhuma cama vermelha nas casas", "casas só de madeira", "ouro só no baú"); o povo cumpre, recusa ou é castigado; petições, relações e diplomacia já eram do Core; reinos rivais nascem em terra firme escolhida pela geração.
5. **LLM local e desempenho:** servidor embutido, orçamento e simulação por LOD. **Feito, menos o servidor embutido** (usa o Ollama do PC): dois modelos (`npcModel` para os súditos, `model` para o conselho), orçamento por minuto e fallback para regras já existiam; LOD por área carregada (`LoadArea`); medido: 310 súditos com corpo e caminho real custam 0,25 ms por tick.
6. **Decidir o port para C#:** só se o jogo já for divertido. **Em aberto, de propósito.**

### O que ainda falta (honesto)

- Servidor de LLM embutido no jogo (hoje usa o Ollama que já estiver rodando).
- Fauna e monstros (o `PhysicalPort.threatsNear` existe, mas o mundo voxel não gera criaturas): hoje o perigo vem do fogo, da água, de explosões e da guerra entre reinos.
- Cavernas, biomas variados e mais vegetação na geração; humanoides com pele própria, sons e efeitos de quebra.
- Salvar a lista de fogos/chuva em andamento (ao carregar, o clima recomeça e os fogos apagam).
- Jogar de fato: a verificação foi por testes (80 no Java, mais o teste de fumaça com o Godot de verdade) e capturas de tela; falta uma sessão longa de jogo para achar o que só o jogador sente.

## 6. Riscos e fatia vertical inicial

Fatia vertical: uma vila, 20 NPCs, uma lei e uma ruína, antes de expandir qualquer coisa.

- **Voxel 3D:** pathfinding, desempenho e salvamento de mundo. Para reduzir, dá para validar a sociedade primeiro em 2D de cima (tilemap com paredes e telhados) e subir para voxel depois; a simulação não muda.
- **Custo e latência da LLM:** por isso o orçamento e as camadas.
- **Escopo:** "liberdade máxima" sem limite vira projeto sem fim; a fatia vertical é o limite.
