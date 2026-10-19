#!/bin/bash
# Sobe a ponte (Core Java + mundo voxel, sem Minecraft) e abre o Godot (com o modulo voxel) nela.
# Precisa de: JDK 21+ e o Godot 4.7 com godot_voxel (github.com/Zylann/godot_voxel/releases).
# Variaveis opcionais: JAVA_HOME, GODOT_EXE, KINGDOMS_SAVE, KINGDOMS_LLM_ARGS
#   ex.: KINGDOMS_LLM_ARGS="--llm ollama --model llama3.1:8b --npc-model gemma3:1b" bash godot/jogar.sh
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
JAVA=${JAVA_HOME:+$JAVA_HOME/bin/}java
if [ -z "$GODOT_EXE" ]; then
  case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) GODOT_EXE=$HOME/tools/gv/godot.windows.editor.x86_64.exe ;;
    Darwin) GODOT_EXE=$HOME/tools/gv/godot.macos.editor.universal ;;
    *) GODOT_EXE=$HOME/tools/gv/godot.linuxbsd.editor.x86_64 ;;
  esac
fi
KINGDOMS_SAVE=${KINGDOMS_SAVE:-$HOME/kingdomsai-saves/mundo1}
./gradlew --console=plain -q bridgeJar
"$JAVA" -Dstdout.encoding=UTF-8 -jar build/bridge/kingdoms-bridge.jar --save "$KINGDOMS_SAVE" $KINGDOMS_LLM_ARGS &
BRIDGE=$!
trap 'kill $BRIDGE 2>/dev/null' EXIT
sleep 4
"$GODOT_EXE" --path godot
