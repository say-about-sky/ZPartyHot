#
#  ZPartyHot - one-shot build
#
#    powershell -ExecutionPolicy Bypass -File build.ps1
#    powershell -ExecutionPolicy Bypass -File build.ps1 -GameRoot <game dir>
#
#  Steps: resolve game root / JDK -> ensure host jar -> run build.bat -> build\ZPartyHot.jar
#
param(
    [string]$GameRoot = "",
    [string]$JdkHome = "",
    [string]$HostJar = "",
    [string]$GameJar = ""
)
$ErrorActionPreference = "Stop"
$proj = $PSScriptRoot

function Fail([string]$m) { Write-Host ("[ERROR] " + $m); exit 1 }
function Get-JdkMajor([string]$jdkDir) {
    $rel = Join-Path $jdkDir "release"
    if (Test-Path -LiteralPath $rel) {
        $m = Select-String -LiteralPath $rel -Pattern 'JAVA_VERSION="(\d+)' | Select-Object -First 1
        if ($m) { return [int]$m.Matches[0].Groups[1].Value }
    }
    return 0
}

if ([string]::IsNullOrEmpty($GameRoot)) {
    if ($env:AOH_GAME_ROOT) { $GameRoot = $env:AOH_GAME_ROOT } else { $GameRoot = Split-Path -Parent $proj }
}
Write-Host "=== game root: $GameRoot"
if ([string]::IsNullOrEmpty($GameJar)) {
    foreach ($p in @((Join-Path $GameRoot "game.jar"), (Join-Path $GameRoot "MyFirstPlugin\libs\game.jar"))) {
        if (Test-Path -LiteralPath $p) { $GameJar = $p; break }
    }
}
if ([string]::IsNullOrEmpty($GameJar) -or -not (Test-Path -LiteralPath $GameJar)) {
    Fail "game.jar not found (pass -GameJar <path> or set AOH_GAME_ROOT)"
}
Write-Host "=== game.jar : $GameJar"

if ([string]::IsNullOrEmpty($JdkHome)) {
    $cands = New-Object System.Collections.ArrayList
    if ($env:JAVA_HOME) { $cands.Add($env:JAVA_HOME) | Out-Null }
    Get-ChildItem "C:\Program Files\Microsoft\jdk-*","C:\Program Files\Java\jdk-*" -Directory -ErrorAction SilentlyContinue |
        ForEach-Object { $cands.Add($_.FullName) | Out-Null }
    foreach ($c in $cands) {
        if (-not (Test-Path -LiteralPath (Join-Path $c "bin\javac.exe"))) { continue }
        $major = Get-JdkMajor $c
        if ($major -ge 11 -and $major -le 17) { $JdkHome = $c; break }
    }
}
if ([string]::IsNullOrEmpty($JdkHome)) { Fail "JDK 11+ not found: set JAVA_HOME or pass -JdkHome" }
Write-Host "=== jdk      : $JdkHome"

function Find-HostJar {
    $hit = @(Get-ChildItem -LiteralPath (Join-Path $GameRoot "HotPluginLoader\build\libs") -Filter "HotPluginLoader-*.jar" -ErrorAction SilentlyContinue |
             Sort-Object LastWriteTime -Descending | Select-Object -First 1)
    if ($hit.Count -gt 0) { return $hit[0].FullName }
    return $null
}
if ([string]::IsNullOrEmpty($HostJar)) { $HostJar = Find-HostJar }
if ([string]::IsNullOrEmpty($HostJar)) {
    $hostPs1 = Join-Path $GameRoot "HotPluginLoader\build.ps1"
    if (Test-Path -LiteralPath $hostPs1) {
        Write-Host "=== host jar missing, building HotPluginLoader (jar only) ..."
        & powershell -NoProfile -ExecutionPolicy Bypass -File $hostPs1 -JarOnly -GameRoot $GameRoot -JdkHome $JdkHome
        $HostJar = Find-HostJar
    }
}
if ([string]::IsNullOrEmpty($HostJar)) { Fail "HotPluginLoader jar not found: build it first (HotPluginLoader\build.bat) or pass -HostJar" }
Write-Host "=== host jar : $HostJar"

$env:AOH_GAME_ROOT = $GameRoot
$env:HOST = $HostJar
$env:GAMEJAR = $GameJar
$env:JAVA_HOME = $JdkHome
Write-Host "=== build.bat"
& cmd /c "`"$proj\build.bat`""
if ($LASTEXITCODE -ne 0) { Fail "build.bat failed" }

$jar = Join-Path $proj "build\ZPartyHot.jar"
if (-not (Test-Path -LiteralPath $jar)) { Fail "build\ZPartyHot.jar not produced" }
Write-Host ""
Write-Host ("[OK] " + $jar + "  (" + (Get-Item -LiteralPath $jar).Length + " bytes)")
Write-Host "Deploy: copy the jar into <game>\hotplugins\jar\ and put zparty.json into hotplugins\resources\ZPartyHot\"
exit 0
