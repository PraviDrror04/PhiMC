# 编译 headless 自测 / 探针工具到 out/dev（不碰 Gradle，不碰 Minecraft）
# 用法： pwsh -File tools\chart\build.ps1
$ErrorActionPreference = "Stop"
$root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
Set-Location $root

$javac = "C:\dsh-tools\jdk25\bin\javac.exe"
if (-not (Test-Path $javac)) { $javac = "D:\Java21\jdk-21.0.12.1+1\bin\javac.exe" }

$slf4j = (Get-ChildItem "C:\dsh-gradle-home\caches\modules-2\files-2.1\org.slf4j\slf4j-api" -Recurse -Filter "slf4j-api-2.0.17.jar" -ErrorAction SilentlyContinue | Select-Object -First 1).FullName
if (-not $slf4j) { throw "找不到 slf4j-api jar" }

$cp = "lib\gson-2.11.0.jar;lib\jlayer-1.0.1.4.jar;$slf4j"

$src = @()
$src += Get-ChildItem "core\src\main\java" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$src += @(
  "mod\src\main\java\dev\dsh\phimc\client\SongEntry.java"
)
$src += Get-ChildItem "tools\chart" -Filter *.java | ForEach-Object { $_.FullName }

$out = "out\dev"
New-Item -ItemType Directory -Force -Path $out | Out-Null
& $javac -encoding UTF-8 -nowarn -d $out -cp $cp @src
if ($LASTEXITCODE -ne 0) { throw "javac 失败 ($LASTEXITCODE)" }
Write-Host "编译完成 → $out"
Write-Host "CP = $out;$cp"
