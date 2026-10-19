@echo off
rem Sobe a ponte (Core Java + mundo voxel, sem Minecraft) e abre o Godot (com o modulo voxel) nela.
rem Precisa de: JDK 21, gson.jar e o Godot 4.7 com godot_voxel (github.com/Zylann/godot_voxel/releases, "godot.windows.editor.x86_64.exe").
rem Variaveis opcionais: JAVA_HOME, GSON_JAR, GODOT_EXE, KINGDOMS_SAVE, KINGDOMS_LLM_ARGS
rem   ex.: set KINGDOMS_LLM_ARGS=--llm ollama --model llama3.1:8b --npc-model gemma3:1b
setlocal
set ROOT=%~dp0..
if "%JAVA_HOME%"=="" set JAVA_HOME=%USERPROFILE%\tools\jdk21\jdk-21.0.12.1+1
if "%GSON_JAR%"=="" set GSON_JAR=%USERPROFILE%\tools\lib\gson.jar
if "%GODOT_EXE%"=="" set GODOT_EXE=%USERPROFILE%\tools\gv\godot.windows.editor.x86_64.exe
if "%KINGDOMS_SAVE%"=="" set KINGDOMS_SAVE=%USERPROFILE%\kingdomsai-saves\mundo1
set OUT=%ROOT%\build\bridge
if not exist "%OUT%" mkdir "%OUT%"
dir /s /b "%ROOT%\src\main\java\com\kingdomsai\core\*.java" "%ROOT%\src\main\java\com\kingdomsai\bridge\*.java" "%ROOT%\src\main\java\com\kingdomsai\voxel\*.java" > "%OUT%\sources.txt"
"%JAVA_HOME%\bin\javac" --release 21 -encoding UTF-8 -nowarn -d "%OUT%" -cp "%GSON_JAR%" @"%OUT%\sources.txt" || exit /b 1
start "ponte Kingdoms" "%JAVA_HOME%\bin\java" -Dstdout.encoding=UTF-8 -cp "%OUT%;%GSON_JAR%" com.kingdomsai.bridge.BridgeMain --save "%KINGDOMS_SAVE%" %KINGDOMS_LLM_ARGS%
ping -n 5 127.0.0.1 >nul
"%GODOT_EXE%" --path "%~dp0"
