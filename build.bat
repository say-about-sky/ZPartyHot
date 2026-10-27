@echo off
rem ============================================================
rem  ZPartyHot - hot plugin build (javac + jar, no gradle, no bundled deps)
rem  in : src\main\java, src\main\resources
rem  out: build\ZPartyHot.jar  (plugin.json inside; host converts it to dex)
rem  dep: ..\MyFirstPlugin\libs\game.jar
rem       ..\HotPluginLoader\build\libs\HotPluginLoader-0.2.0.jar
rem ============================================================
setlocal
set "HERE=%~dp0"
rem --- game root / JDK: override with AOH_GAME_ROOT / JAVA_HOME ---
if defined AOH_GAME_ROOT set "GAME=%AOH_GAME_ROOT%"
if not defined GAME for %%I in ("%~dp0..") do set "GAME=%%~fI"
if defined JAVA_HOME set "JDK=%JAVA_HOME%\bin"
if not defined JDK for /d %%D in ("C:\Program Files\Microsoft\jdk-*") do set "JDK=%%D\bin"
if not defined JDK for /d %%D in ("C:\Program Files\Java\jdk-*") do set "JDK=%%D\bin"
set "HOST=%GAME%\HotPluginLoader\build\libs\HotPluginLoader-0.2.0.jar"
set "GAMEJAR=%GAME%\MyFirstPlugin\libs\game.jar"

if not exist "%JDK%\javac.exe" ( echo [ERR] JDK11 not found & exit /b 1 )
if not exist "%GAMEJAR%" ( echo [ERR] missing game.jar & exit /b 1 )
if not exist "%HOST%" ( echo [ERR] missing HotPluginLoader jar & exit /b 1 )

if exist "%HERE%build" rmdir /s /q "%HERE%build"
mkdir "%HERE%build\classes"

dir /s /b "%HERE%src\main\java\*.java" > "%TEMP%\zpartyhot-src.txt"
"%JDK%\javac.exe" -encoding UTF-8 --release 8 -nowarn -cp "%GAMEJAR%;%HOST%" -d "%HERE%build\classes" @"%TEMP%\zpartyhot-src.txt"
if errorlevel 1 ( echo [ERR] javac failed & exit /b 1 )

copy /y "%HERE%src\main\resources\plugin.json" "%HERE%build\classes\plugin.json" >nul
"%JDK%\jar.exe" cf "%HERE%build\ZPartyHot.jar" -C "%HERE%build\classes" .
if errorlevel 1 ( echo [ERR] jar failed & exit /b 1 )

echo.
echo done: %HERE%build\ZPartyHot.jar
endlocal