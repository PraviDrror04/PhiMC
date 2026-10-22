<#
把 setup/PhiMCSetup.exe 重新打出来（多版本 jar ×2 种版本类型 + 对应版本的 Fabric API + 4 张内置谱面全部内嵌）。

多版本约定：
  * 支持的每个 Minecraft 版本一组：完整版 jar（phimc-<MC>.jar，mod id phimc）
    + 打歌版 jar（phimcplayer-<MC>.jar，mod id phimcplayer）+ Fabric API（fabric-api-<ver>+<MC>.jar）；
  * jar 从 mod/dist/ 里找，找不到再去 mod/versions/<MC>/build/libs/ 找（gradle 产物）；
  * Fabric API jar 放在 setup/ 下（从 Modrinth 下好放进来，文件名必须带 +<MC>）；
  * 内嵌资源名 = 目标文件名（完整版 phimc-<MC>.jar / 打歌版 phimcplayer-<MC>.jar，两者不冲突），
    SetupForm.cs 的 Variants 表按这些名字取；
  * 打包前逐个校验 jar 里的 fabric.mod.json：
      完整版  id = phimc、        打歌版 id = phimcplayer，
      两者 version 都 = $ModVersion、minecraft 依赖都包含这个 MC 版本。
  * 任何一种校验失败都直接 throw（不静默跳过），避免打出「少了一半 jar」的安装器。

前置：
  * mod/build.ps1（gradle 产物）
  * setup/payload/ —— 清洗过的谱面包（tools/repack_charts.py 生成；也可用 -Payload 指到别处，
    没有时自动退到 C:\dsh-work\payload）

用法：
  powershell -ExecutionPolicy Bypass -File setup/build-setup.ps1
  powershell -ExecutionPolicy Bypass -File setup/build-setup.ps1 -Payload D:\somewhere\payload
#>
param(
    [string]$Payload = (Join-Path $PSScriptRoot "payload"),
    [string]$Out     = (Join-Path $PSScriptRoot "PhiMCSetup.exe"),
    [string]$Csc     = "C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe",
    [string]$ModVersion = "0.1.0"
)

$ErrorActionPreference = "Stop"
$src = Join-Path $PSScriptRoot "SetupForm.cs"
$man = Join-Path $PSScriptRoot "app.manifest"
$repoRoot = Split-Path $PSScriptRoot -Parent

# 支持版本表：MC 版本 / 完整版 jar / 打歌版 jar / Fabric API jar。
# 必须与 SetupForm.cs 里的 Variants 表一致（下面会校验）。
$Variants = @(
    @{ Mc = "1.20";    Jar = "phimc-1.20.jar";    Lite = "phimcplayer-1.20.jar";    Api = "fabric-api-0.83.0+1.20.jar" },
    @{ Mc = "1.20.1";  Jar = "phimc-1.20.1.jar";  Lite = "phimcplayer-1.20.1.jar";  Api = "fabric-api-0.92.12+1.20.1.jar" },
    @{ Mc = "1.20.2";  Jar = "phimc-1.20.2.jar";  Lite = "phimcplayer-1.20.2.jar";  Api = "fabric-api-0.91.6+1.20.2.jar" },
    @{ Mc = "1.20.3";  Jar = "phimc-1.20.3.jar";  Lite = "phimcplayer-1.20.3.jar";  Api = "fabric-api-0.91.1+1.20.3.jar" },
    @{ Mc = "1.20.4";  Jar = "phimc-1.20.4.jar";  Lite = "phimcplayer-1.20.4.jar";  Api = "fabric-api-0.97.3+1.20.4.jar" },
    @{ Mc = "1.20.5";  Jar = "phimc-1.20.5.jar";  Lite = "phimcplayer-1.20.5.jar";  Api = "fabric-api-0.97.8+1.20.5.jar" },
    @{ Mc = "1.20.6";  Jar = "phimc-1.20.6.jar";  Lite = "phimcplayer-1.20.6.jar";  Api = "fabric-api-0.100.8+1.20.6.jar" },
    @{ Mc = "1.21";    Jar = "phimc-1.21.jar";    Lite = "phimcplayer-1.21.jar";    Api = "fabric-api-0.102.0+1.21.jar" },
    @{ Mc = "1.21.1";  Jar = "phimc-1.21.1.jar";  Lite = "phimcplayer-1.21.1.jar";  Api = "fabric-api-0.116.17+1.21.1.jar" },
    @{ Mc = "1.21.2";  Jar = "phimc-1.21.2.jar";  Lite = "phimcplayer-1.21.2.jar";  Api = "fabric-api-0.106.1+1.21.2.jar" },
    @{ Mc = "1.21.3";  Jar = "phimc-1.21.3.jar";  Lite = "phimcplayer-1.21.3.jar";  Api = "fabric-api-0.114.1+1.21.3.jar" },
    @{ Mc = "1.21.4";  Jar = "phimc-1.21.4.jar";  Lite = "phimcplayer-1.21.4.jar";  Api = "fabric-api-0.119.4+1.21.4.jar" },
    @{ Mc = "1.21.5";  Jar = "phimc-1.21.5.jar";  Lite = "phimcplayer-1.21.5.jar";  Api = "fabric-api-0.128.2+1.21.5.jar" },
    @{ Mc = "1.21.6";  Jar = "phimc-1.21.6.jar";  Lite = "phimcplayer-1.21.6.jar";  Api = "fabric-api-0.128.2+1.21.6.jar" },
    @{ Mc = "1.21.7";  Jar = "phimc-1.21.7.jar";  Lite = "phimcplayer-1.21.7.jar";  Api = "fabric-api-0.129.0+1.21.7.jar" },
    @{ Mc = "1.21.8";  Jar = "phimc-1.21.8.jar";  Lite = "phimcplayer-1.21.8.jar";  Api = "fabric-api-0.136.1+1.21.8.jar" },
    @{ Mc = "1.21.9";  Jar = "phimc-1.21.9.jar";  Lite = "phimcplayer-1.21.9.jar";  Api = "fabric-api-0.134.1+1.21.9.jar" },
    @{ Mc = "1.21.10"; Jar = "phimc-1.21.10.jar"; Lite = "phimcplayer-1.21.10.jar"; Api = "fabric-api-0.138.4+1.21.10.jar" },
    @{ Mc = "1.21.11"; Jar = "phimc-1.21.11.jar"; Lite = "phimcplayer-1.21.11.jar"; Api = "fabric-api-0.141.6+1.21.11.jar" }
)

if (-not (Test-Path $Csc)) { throw "找不到 csc.exe：$Csc" }

# payload 兜底：仓库里没有（谱面不进仓库），看 dsh-work 下的副本
if (-not (Test-Path $Payload)) {
    $fallback = "C:\dsh-work\payload"
    if (Test-Path $fallback) {
        Write-Host "setup/payload 不存在，改用 $fallback" -ForegroundColor Yellow
        $Payload = $fallback
    } else {
        throw "找不到谱面 payload 目录：$Payload`n先跑：python tools/repack_charts.py `<songs 目录>` `"$Payload`""
    }
}

# 从 SetupForm.cs 里读 BuiltinSongs 表：new string[] { "builtinNN.zip", "目标文件名" }
$formText = Get-Content $src -Raw -Encoding UTF8
$table = [regex]::Matches($formText, 'new string\[\]\s*\{\s*"(builtin\d+\.zip)"\s*,\s*"([^"]+)"\s*\}')
if ($table.Count -eq 0) { throw "SetupForm.cs 里没找到 BuiltinSongs 表" }

# 校验 SetupForm.cs 的 Variants 表与本脚本一致（三种名字都要出现，缺一个就不打）
foreach ($v in $Variants) {
    foreach ($name in @($v.Jar, $v.Lite, $v.Api)) {
        if ($formText.IndexOf($name, [StringComparison]::Ordinal) -lt 0) {
            throw "SetupForm.cs 里没有内嵌资源名 $name（两边版本表不一致）"
        }
    }
}
# 两个 mod id 也要在窗体里出现（打歌版是独立的 mod，不是同一个 id）
foreach ($id in @("phimc", "phimcplayer")) {
    if ($formText.IndexOf('"' + $id + '"', [StringComparison]::Ordinal) -lt 0) {
        throw "SetupForm.cs 里没有 mod id $id（版本类型单选没接上？）"
    }
}

Add-Type -AssemblyName System.IO.Compression.FileSystem | Out-Null

function Get-JarManifest([string]$jarPath) {
    $zip = [System.IO.Compression.ZipFile]::OpenRead($jarPath)
    try {
        $entry = $zip.Entries | Where-Object { $_.FullName -eq 'fabric.mod.json' } | Select-Object -First 1
        if (-not $entry) { throw "$jarPath 里没有 fabric.mod.json" }
        $sr = New-Object System.IO.StreamReader($entry.Open(), [System.Text.Encoding]::UTF8)
        try { return ($sr.ReadToEnd() | ConvertFrom-Json) } finally { $sr.Dispose() }
    } finally { $zip.Dispose() }
}

<#
校验一个 jar 就是「这个 MC 版本 + 这种版本类型」的产物。
  $ExpectId  = phimc（完整版）/ phimcplayer（打歌版）
校验失败一律 throw —— 打歌版少一个版本的 jar 会让安装器在运行时才报错，必须在打包期拦住。
#>
function Assert-Jar([string]$jarPath, [string]$expectId, [string]$mc, [string]$what) {
    $mod = Get-JarManifest $jarPath
    if ($mod.id -ne $expectId) {
        throw ("{0}（{1}）的 mod id 是 '{2}'，期望 {3} —— 打包中止" -f (Split-Path $jarPath -Leaf), $what, $mod.id, $expectId)
    }
    if ($mod.version -ne $ModVersion) {
        throw ("{0}（{1}）的 version={2}，期望 {3} —— 打包中止" -f (Split-Path $jarPath -Leaf), $what, $mod.version, $ModVersion)
    }
    $dep = [string]$mod.depends.minecraft
    if ($dep -notmatch [regex]::Escape($mc)) {
        throw ("{0}（{1}）的 minecraft 依赖是 '{2}'，不包含 {3} —— 打包中止" -f (Split-Path $jarPath -Leaf), $what, $dep, $mc)
    }
    Write-Host ("√ {0}: [{1}] id={2} version={3}  minecraft={4}  ({5:N0} KB)" -f `
        (Split-Path $jarPath -Leaf), $what, $mod.id, $mod.version, $dep, ((Get-Item $jarPath).Length / 1KB)) -ForegroundColor Green
}

$cscArgs = @(
    "/nologo", "/target:winexe", "/platform:anycpu", "/optimize+", "/codepage:65001",
    "/win32manifest:$man",
    "/out:$Out"
)

# ---- mod jar（完整版 + 打歌版）+ 对应 Fabric API ----
foreach ($v in $Variants) {
    # 完整版
    $jar = Join-Path $repoRoot "mod\dist\$($v.Jar)"
    if (-not (Test-Path $jar)) { $jar = Join-Path $repoRoot "mod\versions\$($v.Mc)\build\libs\$($v.Jar)" }
    if (-not (Test-Path $jar)) { throw "缺少 Minecraft $($v.Mc) 的完整版 mod jar：$($v.Jar)`n先跑 mod\build.ps1" }
    Assert-Jar $jar "phimc" $v.Mc "完整版"

    # 打歌版（mod id phimcplayer，独立 jar）
    $lite = Join-Path $repoRoot "mod\dist\$($v.Lite)"
    if (-not (Test-Path $lite)) { $lite = Join-Path $repoRoot "mod\versions\$($v.Mc)\build\libs\$($v.Lite)" }
    if (-not (Test-Path $lite)) { throw "缺少 Minecraft $($v.Mc) 的打歌版 mod jar：$($v.Lite)`n先跑 mod\build.ps1" }
    Assert-Jar $lite "phimcplayer" $v.Mc "打歌版"

    $api = Join-Path $PSScriptRoot $v.Api
    if (-not (Test-Path $api)) { throw "缺少 Fabric API：$($v.Api)`n从 Modrinth 下载 https://modrinth.com/mod/fabric-api/versions?g=$($v.Mc) 放进 setup/" }

    # 内嵌资源名 = mods/ 里的文件名（完整版与打歌版名字天然不冲突）
    $cscArgs += "/resource:$jar,$($v.Jar)"
    $cscArgs += "/resource:$lite,$($v.Lite)"
    $cscArgs += "/resource:$api,$($v.Api)"
}

# ---- 内置谱面 ----
$missing = 0
foreach ($m in $table) {
    $res = $m.Groups[1].Value
    $file = $m.Groups[2].Value
    $path = Join-Path $Payload $file
    if (-not (Test-Path $path)) { Write-Host "缺少谱面：$file" -ForegroundColor Red; $missing++; continue }
    $cscArgs += "/resource:$path,$res"
}
if ($missing -gt 0) { throw "payload 里缺 $missing 个谱面，先重新生成 payload" }

$cscArgs += "/r:System.dll", "/r:System.Drawing.dll", "/r:System.Windows.Forms.dll",
            "/r:System.IO.Compression.dll", "/r:System.IO.Compression.FileSystem.dll",
            $src

$embedMsg = "内嵌：" + $table.Count + " 张谱面 + " + $Variants.Count + " 个版本 x 2 种版本类型（完整版 + 打歌版）mod jar + " + $Variants.Count + " 个 Fabric API"
Write-Host $embedMsg
& $Csc @cscArgs
if ($LASTEXITCODE -ne 0) { throw "csc 失败（exit $LASTEXITCODE）" }
$fi = Get-Item $Out
Write-Host ("完成：" + $fi.FullName + "  " + [math]::Round($fi.Length / 1MB, 1) + " MB  (" + $fi.Length + " 字节)")
