# Kingdoms AI em Godot (mundo voxel)

O Core Java (sociedade, economia, ordens, petições, leis) roda sem Minecraft como `com.kingdomsai.bridge.BridgeMain`, junto com o mundo voxel (`com.kingdomsai.voxel`). O Godot só desenha e pede: fala com a ponte por JSON em linhas, num socket local (`127.0.0.1:7777`).

## Rodar

1. JDK 21 e `gson.jar` (o `jogar.bat` espera `%USERPROFILE%\tools\jdk21\...` e `%USERPROFILE%\tools\lib\gson.jar`; mude por variável de ambiente).
2. Godot 4.7 **com o módulo voxel**: baixe `godot.windows.editor.x86_64.exe` em github.com/Zylann/godot_voxel/releases (tag v1.7) e ponha em `%USERPROFILE%\tools\gv\`.
3. `godot\jogar.bat` — compila a ponte, sobe (salvando em `%USERPROFILE%\kingdomsai-saves\mundo1`) e abre o Godot.

Com LLM local: `set KINGDOMS_LLM_ARGS=--llm ollama --model llama3.1:8b --npc-model gemma3:1b` antes do `jogar.bat`. Sem isso o jogo usa o interpretador por regras.

## Controles

WASD move o marcador do rei · Q/E ou botão direito gira · roda do mouse aproxima · **Olhar**: clique num súdito para ver a ficha e `@frase` para falar com ele · **Quebrar/Colocar**: clique no bloco · **Chamar todos** · 1x/5x/20x · **Chuva/Sol/Fogo/Explodir/Água**: ferramentas do rei no marcador · a caixa de texto aceita qualquer comando do Core (`build casa 2`, `order proíbo camas vermelhas, troquem por azul`, `status`, `decrees`...).

## Protocolo

Cliente → servidor: `cmd {line}` · `player {x,y,z}` · `view {x,z,r}` · `call_all` · `speed {mult}` · `break {x,y,z}` · `place {x,y,z,block}` · `weather {w}` · `ignite` · `explode {x,y,z,r}` · `water`.
Servidor → cliente: `hello` (tabela de blocos, spawn) · `chunk {cx,cz,d}` (16×16×128, 16 bits, gzip+base64, já na ordem do `VoxelBuffer`) · `blocks {b:[[x,y,z,id]]}` · `state` (~4 Hz: NPCs com corpo, obras, clima) · `fx` · `reply/async/error {lines}`.

## Testes

- `./gradlew voxelTest` (ou `VoxelSuite`): geração, mundo que se mexe (fogo, chuva, desabamento, explosão, inundação, desgaste, reparo, ruína), salvar/carregar, leis, ponte pela rede.
- `bash godot/smoke.sh`: o Godot de verdade (headless) contra a ponte; confere chunks e "chamar todos".
- Manuais: `com.kingdomsai.live.LlmLiveCheck` (Ollama) e `PerfCheck` (centenas de súditos).
