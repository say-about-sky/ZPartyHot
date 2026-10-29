@echo off
rem ============================================================
rem  ZPartyHot - thin wrapper around Gradle
rem      gradlew.bat shadowJar  -> build\ZPartyHot.jar
rem  Paths: AOH_GAME_ROOT (game folder) / -PgameRoot= / -PgameJar= / -PhostJar=
rem ============================================================
setlocal
call "%~dp0gradlew.bat" shadowJar %*
set "RC=%ERRORLEVEL%"
pause
exit /b %RC%
