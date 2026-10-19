@echo off
rem Sobe a ponte (Core Java + mundo voxel, sem Minecraft) e abre o Godot (com o modulo voxel) nela.
rem Precisa de: JDK 21+ (JAVA_HOME ou java no PATH) e o Godot 4.7 com godot_voxel (github.com/Zylann/godot_voxel/releases, "godot.windows.editor.x86_64.exe").
rem Variaveis opcionais: JAVA_HOME, GODOT_EXE, KINGDOMS_SAVE, KINGDOMS_LLM_ARGS
rem   ex.: set KINGDOMS_LLM_ARGS=--llm ollama --model llama3.1:8b --npc-model gemma3:1b
setlocal
set ROOT=%~dp0..
if "%GODOT_EXE%"=="" set GODOT_EXE=%USERPROFILE%\tools\gv\godot.windows.editor.x86_64.exe
if "%KINGDOMS_SAVE%"=="" set KINGDOMS_SAVE=%USERPROFILE%\kingdomsai-saves\mundo1
pushd "%ROOT%"
call gradlew.bat --console=plain -q bridgeJar || (popd & exit /b 1)
popd
set JAVA=java
if not "%JAVA_HOME%"=="" set JAVA=%JAVA_HOME%\bin\java
start "ponte Kingdoms" "%JAVA%" -Dstdout.encoding=UTF-8 -jar "%ROOT%\build\bridge\kingdoms-bridge.jar" --save "%KINGDOMS_SAVE%" %KINGDOMS_LLM_ARGS%
ping -n 5 127.0.0.1 >nul
"%GODOT_EXE%" --path "%~dp0"
