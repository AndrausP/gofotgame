#!/bin/bash
# Teste de ponta a ponta: sobe a ponte (mundo voxel) numa porta livre e roda o Godot de verdade (headless) contra ela.
# Confere que os chunks chegam na ordem certa e que "chamar todos" faz os súditos andarem até o rei.
# Variáveis: JAVA_HOME, GSON_JAR, GODOT_EXE, CLASSES (pasta com as classes compiladas; padrão build/coretmp)
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"   # caminhos relativos: o Godot e o Java do Windows não entendem /c/...
JAVA_HOME=${JAVA_HOME:-$HOME/tools/jdk21/jdk-21.0.12.1+1}
GSON_JAR=${GSON_JAR:-$HOME/tools/lib/gson.jar}
GODOT_EXE=${GODOT_EXE:-$HOME/tools/gv/godot.windows.editor.x86_64.exe}
CLASSES=${CLASSES:-build/coretmp}
PORT=${PORT:-7793}
export MSYS_NO_PATHCONV=1
"$JAVA_HOME/bin/java" -Dstdout.encoding=UTF-8 -cp "$CLASSES;$GSON_JAR" com.kingdomsai.bridge.BridgeMain --port "$PORT" > build/smoke-bridge.log 2>&1 &
BRIDGE=$!
trap 'kill $BRIDGE 2>/dev/null' EXIT
sleep 4
"$GODOT_EXE" --headless --path godot --import --quit > /dev/null 2>&1 || true
"$GODOT_EXE" --headless --path godot -- --smoke --port "$PORT" 2>&1 | grep -E "SMOKE|SCRIPT ERROR" | tee build/smoke.log
grep -q "SMOKE OK" build/smoke.log
