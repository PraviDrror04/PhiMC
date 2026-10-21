<#
headless 数据文件校验：解出 19 个版本的 jar，逐项检查「该版本原版认不认」——

  1) 配方目录名：<=1.20.6 用 data/phimc/recipes/，>=1.21 用 data/phimc/recipe/
  2) 配方 result 字段：<=1.20.4 只有 item，>=1.20.5 只有 id
  3) 战利品表目录名：<=1.20.6 用 loot_tables/，>=1.21 用 loot_table/；结构为 chest + pools/rolls + item/empty 条目
  4) 物品模型：assets/phimc/models/item/*.json（4 个）永远都要有；
     >=1.21.4 还必须有 assets/phimc/items/*.json（物品模型定义），且指向存在的 phimc:item/xxx

用法：
  powershell -ExecutionPolicy Bypass -File tools/validate-data.ps1
  powershell -ExecutionPolicy Bypass -File tools/validate-data.ps1 -Dist "C:\path\to\jars"

退出码：0 = 19 个版本全部 OK；1 = 有 FAIL（或版本数不对）。
#>
param(
    [string]$Dist = (Join-Path (Split-Path $PSScriptRoot -Parent) "mod\dist")
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.IO.Compression.FileSystem | Out-Null

$versions = @(
    "1.20", "1.20.1", "1.20.2", "1.20.3", "1.20.4", "1.20.5", "1.20.6",
    "1.21", "1.21.1", "1.21.2", "1.21.3", "1.21.4", "1.21.5",
    "1.21.6", "1.21.7", "1.21.8", "1.21.9", "1.21.10", "1.21.11"
)

function VerCmp([string]$a, [string]$b) {
    # "1.20.4" vs "1.21" 这样的比较（只处理 MC 数字版本）
    $pa = $a.Split('.') | ForEach-Object { [int]$_ }
    $pb = $b.Split('.') | ForEach-Object { [int]$_ }
    for ($i = 0; $i -lt [Math]::Max($pa.Count, $pb.Count); $i++) {
        $x = if ($i -lt $pa.Count) { $pa[$i] } else { 0 }
        $y = if ($i -lt $pb.Count) { $pb[$i] } else { 0 }
        if ($x -ne $y) { return [Math]::Sign($x - $y) }
    }
    return 0
}

function Read-Entry($zip, [string]$name) {
    $e = $zip.Entries | Where-Object { $_.FullName -eq $name } | Select-Object -First 1
    if (-not $e) { return $null }
    $sr = New-Object System.IO.StreamReader($e.Open(), [System.Text.Encoding]::UTF8)
    try { return $sr.ReadToEnd() } finally { $sr.Dispose() }
}

function Has-Entry($zip, [string]$name) {
    return [bool]($zip.Entries | Where-Object { $_.FullName -eq $name } | Select-Object -First 1)
}

$rows = @()
$failures = 0
foreach ($v in $versions) {
    $jar = Join-Path $Dist "phimc-$v.jar"
    $problems = New-Object System.Collections.Generic.List[string]
    $recipePath = "-"; $resultField = "-"; $lootPath = "-"; $models = "-"

    if (-not (Test-Path $jar)) {
        $rows += [pscustomobject]@{ 版本=$v; 配方路径="(缺 jar)"; result=""; 战利品表=""; 模型=""; 判定="FAIL" ; 说明="jar 不存在" }
        $failures++
        continue
    }
    $zip = [System.IO.Compression.ZipFile]::OpenRead($jar)
    try {
        $needPlural = (VerCmp $v "1.21") -lt 0
        $needItemField = (VerCmp $v "1.20.5") -lt 0

        # ---- 1) 配方目录 + 2) result 字段 ----
        $wantRecipe = if ($needPlural) { "recipes" } else { "recipe" }
        $badRecipe = if ($needPlural) { "recipe" } else { "recipes" }
        if (Has-Entry $zip "data/phimc/$wantRecipe/tablet.json") {
            $recipePath = "${wantRecipe}/"
        } else {
            $problems.Add("配方不在 data/phimc/$wantRecipe/")
        }
        if (Has-Entry $zip "data/phimc/$badRecipe/tablet.json") {
            $problems.Add("混入了错误的目录 data/phimc/$badRecipe/")
        }
        foreach ($r in @("tablet", "blank_chip")) {
            $txt = Read-Entry $zip "data/phimc/$wantRecipe/$r.json"
            if ($null -eq $txt) { $problems.Add("缺配方 $r.json"); continue }
            $json = $txt | ConvertFrom-Json
            $props = $json.result.PSObject.Properties.Name
            $hasItem = $props -contains "item"
            $hasId = $props -contains "id"
            if ($needItemField) {
                if (-not $hasItem) { $problems.Add("${r}: result 缺 item 字段") }
                if ($hasId) { $problems.Add("${r}: result 不该有 id 字段") }
                if ($resultField -eq "-") { $resultField = "item" }
            } else {
                if (-not $hasId) { $problems.Add("${r}: result 缺 id 字段") }
                if ($hasItem) { $problems.Add("${r}: result 不该有 item 字段") }
                if ($resultField -eq "-") { $resultField = "id" }
            }
            if ($json.type -ne "minecraft:crafting_shaped") { $problems.Add("${r}: type 不是 crafting_shaped") }
            if (-not $json.pattern -or -not $json.key) { $problems.Add("${r}: 缺 pattern/key") }
        }

        # ---- 3) 战利品表目录 + 结构 ----
        $wantLoot = if ($needPlural) { "loot_tables" } else { "loot_table" }
        $badLoot = if ($needPlural) { "loot_table" } else { "loot_tables" }
        if (Has-Entry $zip "data/phimc/$wantLoot/chests/chart_chip.json") {
            $lootPath = "${wantLoot}/"
        } else {
            $problems.Add("战利品表不在 data/phimc/$wantLoot/")
        }
        if (Has-Entry $zip "data/phimc/$badLoot/chests/chart_chip.json") {
            $problems.Add("混入了错误的战利品目录 $badLoot/")
        }
        foreach ($lt in @(@("chart_chip", 15), @("chart_chip_rare", 3))) {
            $txt = Read-Entry $zip "data/phimc/$wantLoot/chests/$($lt[0]).json"
            if ($null -eq $txt) { $problems.Add("缺战利品表 $($lt[0]).json"); continue }
            $json = $txt | ConvertFrom-Json
            if ($json.type -ne "minecraft:chest") { $problems.Add("$($lt[0]): type 不是 chest") }
            if (-not $json.pools -or $json.pools.Count -ne 1) { $problems.Add("$($lt[0]): pools 不是 1 个"); continue }
            $pool = $json.pools[0]
            if ($pool.rolls -ne 1) { $problems.Add("$($lt[0]): rolls 不是 1") }
            $itemEntry = $pool.entries | Where-Object { $_.type -eq "minecraft:item" } | Select-Object -First 1
            $emptyEntry = $pool.entries | Where-Object { $_.type -eq "minecraft:empty" } | Select-Object -First 1
            if (-not $itemEntry) { $problems.Add("$($lt[0]): 没有 minecraft:item 条目") }
            elseif ($itemEntry.name -ne "phimc:chart_chip") { $problems.Add("$($lt[0]): 条目 name 不是 phimc:chart_chip（实际 $($itemEntry.name)）") }
            if ($itemEntry -and $itemEntry.weight -ne 1) { $problems.Add("$($lt[0]): 芯片 weight 不是 1") }
            if (-not $emptyEntry) { $problems.Add("$($lt[0]): 没有 minecraft:empty 条目") }
            elseif ($emptyEntry.weight -ne $lt[1]) { $problems.Add("$($lt[0]): empty weight 应为 $($lt[1])，实际 $($emptyEntry.weight)") }
        }

        # ---- 4) 模型 / 物品模型定义 ----
        $modelCount = 0
        foreach ($m in @("tablet", "blank_chip", "chart_chip", "chart_player")) {
            $txt = Read-Entry $zip "assets/phimc/models/item/$m.json"
            if ($null -eq $txt) { $problems.Add("缺模型 models/item/$m.json"); continue }
            $json = $txt | ConvertFrom-Json
            if (-not $json.parent) { $problems.Add("${m}: 模型缺 parent") }
            if (-not $json.textures.layer0) { $problems.Add("${m}: 模型缺 textures.layer0") }
            $modelCount++
        }
        $needModelDef = (VerCmp $v "1.21.4") -ge 0
        $defCount = 0
        foreach ($m in @("tablet", "blank_chip", "chart_chip", "chart_player")) {
            $defTxt = Read-Entry $zip "assets/phimc/items/$m.json"
            if ($needModelDef) {
                if ($null -eq $defTxt) { $problems.Add("缺物品模型定义 items/$m.json（1.21.4+ 必需）"); continue }
                $def = $defTxt | ConvertFrom-Json
                if ($def.model.type -ne "minecraft:model") { $problems.Add("items/${m}: model.type 不是 minecraft:model") }
                $target = [string]$def.model.model
                if ($target -ne "phimc:item/$m") { $problems.Add("items/${m}: model 指向 $target，期望 phimc:item/$m") }
                $defCount++
            } elseif ($null -ne $defTxt) {
                $problems.Add("<=1.21.3 不该有 items/$m.json 定义")
            }
        }
        $models = "models=$modelCount/4"
        if ($needModelDef) { $models += " defs=$defCount/4" }

        # fabric.mod.json 自检（数据文件与被依赖无关，只确认 jar 完整）
        if (-not (Has-Entry $zip "fabric.mod.json")) { $problems.Add("缺 fabric.mod.json") }
    } finally { $zip.Dispose() }

    $ok = $problems.Count -eq 0
    if (-not $ok) { $failures++ }
    $rows += [pscustomobject]@{
        版本 = $v
        配方路径 = "data/phimc/$recipePath"
        result = $resultField
        战利品表 = "data/phimc/$lootPath"
        模型 = $models
        判定 = $(if ($ok) { "OK" } else { "FAIL" })
        说明 = $(if ($ok) { "" } else { ($problems -join "；") })
    }
}

$rows | Format-Table -AutoSize | Out-String -Width 200 | Write-Host
$okCount = ($rows | Where-Object { $_.判定 -eq "OK" }).Count
Write-Host ("结果：{0}/{1} 个版本 OK" -f $okCount, $versions.Count)
if ($failures -gt 0) { exit 1 }
