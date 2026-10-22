# PhiMC 一键安装：按实例的 MC 版本装对应 jar + 从 Phigros XAPK 导入曲目
#
# 用法：
#   .\install.ps1                                   # 自动找 .minecraft，按 versions\ 里最新实例的 MC 版本选 jar
#   .\install.ps1 -McDir "D:\Games\.minecraft" -McVersion 1.20.1
#   .\install.ps1 -McDir "D:\Games\.minecraft" -Instance "1.21.11-fabric"
#   .\install.ps1 -McDir "D:\Games\.minecraft" -Xapk "D:\Phigros.xapk"
#
# 多版本：dist\ 里每个 MC 版本一个 jar（phimc-<MC>.jar），必须和实例的 MC 版本对上。
# 说明：谱面/音频来自你自己的 Phigros 安装包，脚本不上传任何东西。
param(
    [string]$McDir = "",
    [string]$Instance = "",
    [string]$McVersion = "",
    [string]$Xapk = "",
    [switch]$SkipSongs
)

$ErrorActionPreference = "Stop"
$here = $PSScriptRoot

$Supported = @(
    "1.20", "1.20.1", "1.20.2", "1.20.3", "1.20.4", "1.20.5", "1.20.6",
    "1.21", "1.21.1", "1.21.2", "1.21.3", "1.21.4", "1.21.5",
    "1.21.6", "1.21.7", "1.21.8", "1.21.9", "1.21.10", "1.21.11"
)
$FabricApi = @{
    "1.20"    = "fabric-api-0.83.0+1.20.jar"
    "1.20.1"  = "fabric-api-0.92.12+1.20.1.jar"
    "1.20.2"  = "fabric-api-0.91.6+1.20.2.jar"
    "1.20.3"  = "fabric-api-0.91.1+1.20.3.jar"
    "1.20.4"  = "fabric-api-0.97.3+1.20.4.jar"
    "1.20.5"  = "fabric-api-0.97.8+1.20.5.jar"
    "1.20.6"  = "fabric-api-0.100.8+1.20.6.jar"
    "1.21"    = "fabric-api-0.102.0+1.21.jar"
    "1.21.1"  = "fabric-api-0.116.17+1.21.1.jar"
    "1.21.2"  = "fabric-api-0.106.1+1.21.2.jar"
    "1.21.3"  = "fabric-api-0.114.1+1.21.3.jar"
    "1.21.4"  = "fabric-api-0.119.4+1.21.4.jar"
    "1.21.5"  = "fabric-api-0.128.2+1.21.5.jar"
    "1.21.6"  = "fabric-api-0.128.2+1.21.6.jar"
    "1.21.7"  = "fabric-api-0.129.0+1.21.7.jar"
    "1.21.8"  = "fabric-api-0.136.1+1.21.8.jar"
    "1.21.9"  = "fabric-api-0.134.1+1.21.9.jar"
    "1.21.10" = "fabric-api-0.138.4+1.21.10.jar"
    "1.21.11" = "fabric-api-0.141.6+1.21.11.jar"
}

function Find-McDir {
    $cands = @(
        (Join-Path $env:APPDATA ".minecraft"),
        (Join-Path $env:LOCALAPPDATA "Packages\Microsoft.MinecraftUWP_8wekyb3d8bbwe"),
        "C:\Program Files (x86)\Minecraft Launcher"
    )
    foreach ($c in $cands) { if (Test-Path $c) { return $c } }
    return ""
}

# 读 versions\<实例>\<实例>.json 的 inheritsFrom / id，兜底从目录名里抓版本号
function Get-InstanceMc([string]$root, [string]$ver) {
    $json = Join-Path (Join-Path (Join-Path $root "versions") $ver) ($ver + ".json")
    if (Test-Path $json) {
        $text = Get-Content $json -Raw
        $m = [regex]::Match($text, '"inheritsFrom"\s*:\s*"([^"]+)"')
        if ($m.Success) { return $m.Groups[1].Value }
        $m = [regex]::Match($text, '"id"\s*:\s*"([^"]+)"')
        if ($m.Success -and $m.Groups[1].Value -match '^\d+\.\d+') { return $m.Groups[1].Value }
    }
    $parts = $ver -split '[-_+]'
    for ($i = $parts.Count - 1; $i -ge 0; $i--) {
        if ($parts[$i] -match '^\d+\.\d+(\.\d+)?$') { return $parts[$i] }
    }
    return $null
}

if (-not $McDir) { $McDir = Find-McDir }
if (-not $McDir -or -not (Test-Path $McDir)) {
    Write-Host "没找到 .minecraft，请用 -McDir 指定你的游戏目录" -ForegroundColor Red
    exit 1
}
Write-Host "游戏目录: $McDir"

# 版本隔离：默认装到 versions\<实例>\，没指定实例就用最近改过的那个
if (-not $Instance) {
    $versionsDir = Join-Path $McDir "versions"
    if (Test-Path $versionsDir) {
        $Instance = Get-ChildItem $versionsDir -Directory |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty Name
    }
}
$root = $McDir
if ($Instance) {
    $root = Join-Path (Join-Path $McDir "versions") $Instance
    Write-Host "实例: $Instance（装到 $root）"
} else {
    Write-Host "没找到 versions\ 实例，装到全局 mods\（-Instance 可指定）" -ForegroundColor Yellow
}

if (-not $McVersion) {
    if ($Instance) { $McVersion = Get-InstanceMc $McDir $Instance }
    if (-not $McVersion) { Write-Host "认不出实例的 MC 版本，请用 -McVersion 指定（支持：$($Supported -join ' / ')）" -ForegroundColor Red; exit 1 }
}
if ($Supported -notcontains $McVersion) {
    Write-Host "不支持的 Minecraft 版本：$McVersion（支持：$($Supported -join ' / ')）" -ForegroundColor Red
    Write-Host "不会安装，避免装错版本。" -ForegroundColor Red
    exit 1
}

$jar = Join-Path $here "dist\phimc-$McVersion.jar"
$api = Join-Path $here "..\setup\$($FabricApi[$McVersion])"
if (-not (Test-Path $jar)) { Write-Host "缺少 $jar，先跑 mod\build.ps1" -ForegroundColor Red; exit 1 }

$modsDir = Join-Path $root "mods"
New-Item -ItemType Directory -Force -Path $modsDir | Out-Null
# 清掉别的版本的 phimc jar（两个版本同时加载会失败）
Get-ChildItem $modsDir -Filter "phimc-*.jar" -ErrorAction SilentlyContinue | ForEach-Object {
    if ($_.Name -ne "phimc-$McVersion.jar") { Remove-Item $_.FullName -Force; Write-Host "删除旧版本 jar: $($_.Name)" }
}
Copy-Item $jar $modsDir -Force
Write-Host "已安装 mod -> mods\phimc-$McVersion.jar（Minecraft $McVersion）" -ForegroundColor Green
# 打歌 only 变体（phimcplayer-*.jar）与完整版**只能二选一**：两个 jar 的 class 名完全重复，
# 同时装 loader 会报错（lite 的 fabric.mod.json 里也写了 breaks: phimc）。
Get-ChildItem $modsDir -Filter "phimcplayer-*.jar" -ErrorAction SilentlyContinue | ForEach-Object {
    Remove-Item $_.FullName -Force
    Write-Host "删除打歌 only 变体（与完整版二选一）: $($_.Name)" -ForegroundColor Yellow
}

$apiName = $FabricApi[$McVersion]
if ((Test-Path $api) -and -not (Test-Path (Join-Path $modsDir $apiName))) {
    Copy-Item $api $modsDir -Force
    Write-Host "已安装 Fabric API -> mods\$apiName" -ForegroundColor Green
} elseif (Test-Path (Join-Path $modsDir $apiName)) {
    Write-Host "已存在匹配的 Fabric API：$apiName"
} else {
    Write-Host "没找到 $api，请从 Modrinth 下载 Fabric API（$McVersion）放进 setup\" -ForegroundColor Yellow
}

$songsDir = Join-Path $root "config\phimc\songs"
New-Item -ItemType Directory -Force -Path $songsDir | Out-Null

# 谱面包：把安装器目录下的 *.zip 直接投递进 songs/（zip 里含 info.txt + Chart_XX.json + music.wav）
$zips = Get-ChildItem -Path $here, (Join-Path $here "songs") -Filter "*.zip" -ErrorAction SilentlyContinue
if ($zips) {
    foreach ($z in $zips) {
        Copy-Item $z.FullName $songsDir -Force
        Write-Host "已投递曲目包: $($z.Name)"
    }
}

if ($SkipSongs) { Write-Host "跳过曲目导入"; exit 0 }

if (-not $Xapk) {
    $found = Get-ChildItem -Path $here, (Join-Path $env:USERPROFILE "Downloads"), "." -Filter "*.xapk" -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($found) { $Xapk = $found.FullName }
}
if (-not $Xapk -or -not (Test-Path $Xapk)) {
    Write-Host "没找到 XAPK，用 -Xapk 指定 Phigros 安装包" -ForegroundColor Yellow
    exit 0
}

Write-Host "从 $Xapk 导入曲目（首次约 2-3 分钟，会解出 ~1GB 中间文件到 config\phimc\_phi_work）"
python (Join-Path $here "tools\import_songs.py") $Xapk $songsDir

Write-Host ""
Write-Host "完成。启动游戏后按 P 开始，F9 击打。" -ForegroundColor Green
