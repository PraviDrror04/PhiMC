# 构建 PhiMC 的多版本 Fabric jar（1.20 ~ 1.21.11 全系，共 19 个）。
#
# 为什么需要这个脚本：
#   1) Gradle 在「路径含中文」时加载 native-platform.dll 会失败，
#      所以先把工程同步到一个纯 ASCII 工作目录再编译，编完把 jar 拷回来；
#   2) 现在是 Stonecutter 单源码多版本（mod/versions/<MC 版本>/），
#      一次要按节点分别 build，产物分别在 versions/<ver>/build/libs/ 下。
#
# 工具链（Stonecutter 0.9.8 要求 Gradle 9；fabric-loom 1.19.2 要求 JDK 25）：
#   Gradle  C:\dsh-tools\gradle-9.8.2
#   JDK     C:\dsh-tools\jdk25      （编译目标由各版本决定：1.20~1.20.4→17，1.20.5+→21）
#
# 用法：
#   .\build.ps1                       # 编全部 19 个版本
#   .\build.ps1 -Versions 1.20.1      # 只编一个
param(
    [string]$WorkDir = "C:\dsh-work\phimc",
    [string]$Gradle = "C:\dsh-tools\gradle-9.8.2\bin\gradle.bat",
    [string]$JavaHome = "C:\dsh-tools\jdk25",
    [string]$GradleUserHome = "C:\dsh-gradle-home",
    [string[]]$Versions = @(
        "1.20", "1.20.1", "1.20.2", "1.20.3", "1.20.4", "1.20.5", "1.20.6",
        "1.21", "1.21.1", "1.21.2", "1.21.3", "1.21.4", "1.21.5",
        "1.21.6", "1.21.7", "1.21.8", "1.21.9", "1.21.10", "1.21.11"
    )
)

$ErrorActionPreference = "Stop"
$here = $PSScriptRoot
$projectRoot = Split-Path $here -Parent

Write-Host "== 同步到 $WorkDir =="
robocopy $projectRoot $WorkDir /MIR /NFL /NDL /NJH /NJS /XD out .gradle build fixtures dist | Out-Null

if ($JavaHome -and (Test-Path $JavaHome)) { $env:JAVA_HOME = $JavaHome }
if ($GradleUserHome) { $env:GRADLE_USER_HOME = $GradleUserHome }

New-Item -ItemType Directory -Force -Path "$here\dist" | Out-Null

$failed = @()
foreach ($v in $Versions) {
    Write-Host "== 编译 Minecraft $v（首次会下载 MC + 映射，约 5-15 分钟）==" -ForegroundColor Cyan
    & $Gradle -p "$WorkDir\mod" ":$v`:build" --no-daemon --console=plain
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Minecraft $v 编译失败，退出码 $LASTEXITCODE" -ForegroundColor Red
        $failed += $v
        continue
    }
    $libs = "$WorkDir\mod\versions\$v\build\libs"
    $jar = Join-Path $libs "phimc-$v.jar"
    if (-not (Test-Path $jar)) {
        Write-Host "Minecraft $v 没有产物：$jar" -ForegroundColor Red
        $failed += $v
        continue
    }
    Copy-Item $jar "$here\dist\" -Force
    Write-Host ("产物: {0} ({1:N0} KB)" -f (Split-Path $jar -Leaf), ((Get-Item $jar).Length / 1KB)) -ForegroundColor Green
}

if ($failed.Count -gt 0) { exit 1 }
