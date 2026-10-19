#!/bin/bash
# Teste de ponta a ponta: sobe a ponte (mundo voxel) numa porta livre e roda o Godot de verdade (headless) contra ela.
# Confere que os chunks chegam na ordem certa e que "chamar todos" faz os súditos andarem até o rei.
# Variáveis: JAVA_HOME, GODOT_EXE
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"   # caminhos relativos: o Godot e o Java do Windows não entendem /c/...
JAVA=${JAVA_HOME:+$JAVA_HOME/bin/}java
GODOT_EXE=${GODOT_EXE:-$HOME/tools/gv/godot.windows.editor.x86_64.exe}
PORT=${PORT:-7793}
export MSYS_NO_PATHCONV=1
mkdir -p build
./gradlew --console=plain -q bridgeJar
"$JAVA" -Dstdout.encoding=UTF-8 -jar build/bridge/kingdoms-bridge.jar --port "$PORT" > build/smoke-bridge.log 2>&1 &
BRIDGE=$!
trap 'kill $BRIDGE 2>/dev/null' EXIT
sleep 4
"$GODOT_EXE" --headless --path godot --import --quit > /dev/null 2>&1 || true
"$GODOT_EXE" --headless --path godot -- --smoke --port "$PORT" 2>&1 | grep -E "SMOKE|SCRIPT ERROR" | tee build/smoke.log
grep -q "SMOKE OK" build/smoke.log
