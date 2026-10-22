# 版式核对：选曲界面「在线」页签（Phira）
#
# 和 tools/ui/select-layout.ps1 同一个套路：常量 / 算式与
# mod 的 client/SongSelectScreen.java 一一对应（TAB_* / SEARCH_* / ONLINE_* / downloadX / DISCLAIM_* …）。
# 做两件事：
#   1) 按 1280×720 / 854×480 算一遍在线页坐标，打印坐标表，并断言
#      「页签 / 副标题 / 搜索栏 / 列表 / 状态行 / 版权行 / 底部按钮 / 行内下载按钮 /
#       页脚「非官方同人项目」声明条」互不重叠，且声明文字不被裁掉（断言失败 → 退出码 1）；
#   2) 画两张 PNG 预览到 C:\dsh-work\ui-check\（不进仓库）。
#
# 用法：powershell -NoProfile -ExecutionPolicy Bypass -File tools/ui/online-layout.ps1
Add-Type -AssemblyName System.Drawing

$OUT_DIR = "C:\dsh-work\ui-check"
New-Item -ItemType Directory -Force -Path $OUT_DIR | Out-Null

# ---------------- 页脚声明（和选曲界面 / 设置页同一句） ----------------
$DISCLAIM_ZH = "非官方同人项目 · 与鸽游（Pigeon Games）及《Phigros》官方无授权、合作或运营关系"
$DISCLAIM_EN = "Unofficial fan project. Not affiliated with, endorsed by, or operated by Pigeon Games or Phigros."
function Get-McTextWidth([string]$s) {
  $w = 0
  foreach ($ch in $s.ToCharArray()) {
    $code = [int]$ch
    if ($code -gt 0x2E80) { $w += 9 } elseif ($code -eq 32) { $w += 4 } else { $w += 6 }
  }
  return $w
}

# ---------------- 常量（与 SongSelectScreen 一一对应） ----------------
$PAD        = 12
$HEADER_H   = 34
$TAB_W      = 48
$TAB_H      = 18
$TAB_GAP    = 4
$SEARCH_H   = 20
$SEARCH_BTN = 56
$ORDER_BTN  = 84
$STABLE_BTN = 84
$BTN_GAP    = 8
$ROW_H      = 24
$ROW_STEP   = 26
$THUMB      = 20
$NOTICE_H   = 24          # 在线页底部两条（状态 + 版权）
$LIST_GAP   = 6
$PAGE_MAX   = 30
$BOTTOM_H   = 26
$COPY_W     = 56
$COPY_H     = 16
$BTN_W      = 70
$AUTO_W     = 100
$DISCLAIM_H = 13          # 页脚「非官方同人项目」声明条（SongSelectScreen.DISCLAIM_H）

$fails = 0
function Check($ok, $what) {
  if ($ok) { Write-Output ("  OK   " + $what) }
  else { Write-Output ("  FAIL " + $what); $script:fails++ }
}

function Get-Geometry([int]$W, [int]$H) {
  $localX  = $PAD
  $onlineX = $PAD + $TAB_W + $TAB_GAP
  $tabsEnd = $onlineX + $TAB_W
  $tabY    = 3
  $searchY = $HEADER_H + 2
  $fieldW  = [Math]::Max(40, $W - 2*$PAD - $SEARCH_BTN - $ORDER_BTN - $STABLE_BTN - 3*$BTN_GAP)
  $searchX = $PAD + $fieldW + $BTN_GAP
  $orderX  = $searchX + $SEARCH_BTN + $BTN_GAP
  $stableX = $orderX + $ORDER_BTN + $BTN_GAP
  $listTop = $searchY + $SEARCH_H + $LIST_GAP
  $lBottom = $H - $BOTTOM_H - 4 - $DISCLAIM_H
  $per     = [Math]::Max(1, [Math]::Min($PAGE_MAX, [Math]::Floor(($lBottom - $NOTICE_H - $listTop) / $ROW_STEP)))
  $lastRowBottom = $listTop + ($per - 1) * $ROW_STEP + $ROW_H
  $statusY = $lBottom - $NOTICE_H + 2
  $legalY  = $statusY + 11
  $bY      = $H - $BOTTOM_H - $DISCLAIM_H
  $disclaimY     = $H - $DISCLAIM_H
  $disclaimTextY = $disclaimY + [Math]::Floor(($DISCLAIM_H - 9) / 2)
  $disclaimW     = Get-McTextWidth $DISCLAIM_ZH
  $disclaimWEn   = Get-McTextWidth $DISCLAIM_EN
  $disclaimAvail = $W - 2 * $PAD
  $dlX     = $W - $PAD - 4 - $COPY_W
  $textRight = $dlX - 8
  $thumbX  = $PAD + 4
  $textX   = $thumbX + $THUMB + 6
  $subX    = $tabsEnd + 8
  $subW    = $W - $PAD - $subX

  # 底部 6 个槽位（在线页签下「自动游玩」隐藏，但槽位仍占位）
  $bw = $BTN_W; $autoW = $AUTO_W
  $total = $bw * 5 + $autoW + $BTN_GAP * 5
  $avail = $W - 2 * $PAD
  if ($total -gt $avail) {
    $bw = [Math]::Max(30, [Math]::Floor(($avail - $BTN_GAP * 5) / 6))
    $autoW = $bw
    $total = $bw * 6 + $BTN_GAP * 5
  }
  $x0 = [Math]::Floor($W / 2) - [Math]::Floor($total / 2)
  $xPrev = $x0
  $xStart = $xPrev + $bw + $BTN_GAP
  $xAuto = $xStart + $bw + $BTN_GAP
  $xBack = $xAuto + $autoW + $BTN_GAP
  $xSet = $xBack + $bw + $BTN_GAP
  $xNext = $xSet + $bw + $BTN_GAP

  return [pscustomobject]@{
    W = $W; H = $H
    localX = $localX; onlineX = $onlineX; tabsEnd = $tabsEnd; tabY = $tabY
    searchY = $searchY; fieldW = $fieldW; searchX = $searchX; orderX = $orderX; stableX = $stableX
    listTop = $listTop; lBottom = $lBottom; per = $per; lastRowBottom = $lastRowBottom
    statusY = $statusY; legalY = $legalY; bY = $bY
    disclaimY = $disclaimY; disclaimTextY = $disclaimTextY
    disclaimW = $disclaimW; disclaimWEn = $disclaimWEn; disclaimAvail = $disclaimAvail
    dlX = $dlX; textRight = $textRight; thumbX = $thumbX; textX = $textX
    subX = $subX; subW = $subW
    bw = $bw; autoW = $autoW
    xPrev = $xPrev; xStart = $xStart; xAuto = $xAuto; xBack = $xBack; xSet = $xSet; xNext = $xNext
  }
}

function Assert-Geometry($g) {
  $W = $g.W; $H = $g.H
  Write-Output ("  尺寸 {0}×{1}：每页 {2} 行，列表 {3}..{4}，状态行 y={5}，版权行 y={6}，底部按钮 y={7}" -f `
    $W, $H, $g.per, $g.listTop, $g.lastRowBottom, $g.statusY, $g.legalY, $g.bY)
  Write-Output ("    页签 ({0},3,{1}×{2}) / ({3},3,{1}×{2})  右边界 {4}" -f $g.localX, $TAB_W, $TAB_H, $g.onlineX, $g.tabsEnd)
  Write-Output ("    搜索框 ({0},{1},{2}×{3})  搜索 ({4},{1},{5}×{3})  排序 ({6},{1},{7}×{3})  仅上架 ({8},{1},{9}×{3})" -f `
    $PAD, $g.searchY, $g.fieldW, $SEARCH_H, $g.searchX, $SEARCH_BTN, $g.orderX, $ORDER_BTN, $g.stableX, $STABLE_BTN)
  Write-Output ("    下载槽位 x={0}..{1}  文字右边界 {2}  缩略图 x={3}..{4}  文字 x={5} 起" -f `
    $g.dlX, ($g.dlX + $COPY_W), $g.textRight, $g.thumbX, ($g.thumbX + $THUMB), $g.textX)
  Write-Output ("    副标题 x={0} 宽 {1}" -f $g.subX, $g.subW)
  Write-Output ("    页脚声明条 y={0}..{1}  文字 y={2}  估宽 中 {3}px 英 {4}px（可用 {5}px）" -f `
    $g.disclaimY, ($g.disclaimY + $DISCLAIM_H), $g.disclaimTextY, $g.disclaimW, $g.disclaimWEn, $g.disclaimAvail)

  Check ($g.tabsEnd -lt $g.subX) ("页签右边界 {0} < 副标题左 {1}" -f $g.tabsEnd, $g.subX)
  Check ($g.searchY -gt ($g.tabY + $TAB_H - 1)) ("搜索栏在页签下面（{0} > {1}）" -f $g.searchY, ($g.tabY + $TAB_H))
  Check ($g.listTop -eq ($g.searchY + $SEARCH_H + $LIST_GAP)) ("列表顶 = 搜索栏底 + {0}" -f $LIST_GAP)
  Check ($g.lastRowBottom -le $g.statusY) ("最后一行底 {0} <= 状态行 {1}" -f $g.lastRowBottom, $g.statusY)
  Check ($g.statusY + 9 -le $g.legalY) "状态行不压版权行"
  Check ($g.legalY + 9 -le $g.bY) ("版权行底 {0} <= 底部按钮 {1}" -f ($g.legalY + 9), $g.bY)
  Check ($g.dlX -gt ($g.textX + 40)) ("文字区宽度 > 40px（{0}）" -f ($g.dlX - $g.textX))
  Check (($g.searchX + $SEARCH_BTN + $BTN_GAP) -eq $g.orderX) ("搜索 / 排序 按钮间距 = {0}" -f $BTN_GAP)
  Check (($g.stableX - $g.orderX) -eq ($ORDER_BTN + $BTN_GAP)) ("排序 / 仅上架 按钮间距 = {0}" -f $BTN_GAP)
  Check (($g.stableX + $STABLE_BTN) -eq ($W - $PAD)) ("仅上架按钮右边界贴 {0}" -f ($W - $PAD))
  Check ($g.stableX -gt ($g.searchX + $SEARCH_BTN)) "仅上架按钮在搜索按钮右边"
  Check ($g.per -ge 1 -and $g.per -le $PAGE_MAX) ("每页行数在 1..{0}" -f $PAGE_MAX)
  Check ($g.listTop -lt $g.lBottom) "列表有正高度"
  Check (($g.xNext + $g.bw) -le ($W - $PAD + 1)) ("底部按钮不溢出（右 {0}）" -f ($g.xNext + $g.bw))
  # 页脚声明条：贴屏幕底、在底部按钮下面、文字整行在条内、中英文估宽都放得下
  Check (($g.disclaimY + $DISCLAIM_H) -eq $H) ("声明条底 {0} 贴住屏幕底 {1}" -f ($g.disclaimY + $DISCLAIM_H), $H)
  Check ($g.bY + 20 -le $g.disclaimY) ("底部按钮底 {0} <= 声明条顶 {1}" -f ($g.bY + 20), $g.disclaimY)
  Check ($g.disclaimTextY -ge $g.disclaimY) ("声明文字顶 {0} 在条内（条顶 {1}）" -f $g.disclaimTextY, $g.disclaimY)
  Check (($g.disclaimTextY + 9) -le $H) ("声明文字底 {0} <= 屏幕高 {1}" -f ($g.disclaimTextY + 9), $H)
  Check ($g.disclaimTextY -ge $g.bY + 20) ("声明文字不压底部按钮（{0} vs {1}）" -f $g.disclaimTextY, ($g.bY + 20))
  Check ($g.disclaimW -le $g.disclaimAvail) ("声明中文估宽 {0} <= 内容区 {1}" -f $g.disclaimW, $g.disclaimAvail)
  Check ($g.disclaimWEn -le $g.disclaimAvail) ("声明英文估宽 {0} <= 内容区 {1}" -f $g.disclaimWEn, $g.disclaimAvail)
}

function Draw-Online($g, [string]$outPath) {
  $W = $g.W; $H = $g.H
  $bmp = New-Object System.Drawing.Bitmap($W, $H)
  $gr = [System.Drawing.Graphics]::FromImage($bmp)
  $gr.SmoothingMode = 'AntiAlias'
  $gr.TextRenderingHint = 'ClearTypeGridFit'
  $gr.Clear([System.Drawing.Color]::FromArgb(255, 16, 16, 20))

  $fTitle = New-Object System.Drawing.Font("Microsoft YaHei", 10, [System.Drawing.FontStyle]::Bold)
  $fText  = New-Object System.Drawing.Font("Microsoft YaHei", 8)
  $fSmall = New-Object System.Drawing.Font("Microsoft YaHei", 7)
  $brWhite = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::White)
  $brText  = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255,228,228,228))
  $brDim   = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255,154,154,154))
  $brAcc   = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255,180,225,255))
  $brGray  = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255,107,107,107))
  $brRed   = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255,255,138,138))
  $sfC = New-Object System.Drawing.StringFormat; $sfC.Alignment = 'Center'

  # 顶栏
  $gr.DrawString("PhiMC 选曲", $fTitle, $brWhite, [float]($W/2), 4, $sfC)
  $gr.DrawString("Phira 在线谱面 · 共 632 首（只显示稳定且已审核）· 第 1/32 页 · 回车下载选中 · Esc 返回",
                 $fSmall, $brDim, [float]$g.subX, 20)
  # 页签（在线 = 当前，灰）
  $gr.FillRectangle((New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255,42,42,42))), [float]$g.localX, [float]$g.tabY, [float]$TAB_W, [float]$TAB_H)
  $gr.DrawRectangle((New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(255,120,140,170))), [float]$g.localX, [float]$g.tabY, [float]$TAB_W, [float]$TAB_H)
  $gr.DrawString("本地", $fSmall, $brWhite, [float]($g.localX + $TAB_W/2), [float]($g.tabY+3), $sfC)
  $gr.FillRectangle((New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255,28,28,28))), [float]$g.onlineX, [float]$g.tabY, [float]$TAB_W, [float]$TAB_H)
  $gr.DrawRectangle((New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(255,90,90,90))), [float]$g.onlineX, [float]$g.tabY, [float]$TAB_W, [float]$TAB_H)
  $gr.DrawString("在线", $fSmall, $brGray, [float]($g.onlineX + $TAB_W/2), [float]($g.tabY+3), $sfC)

  # 搜索栏
  $gr.FillRectangle((New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255,0,0,0))), [float]$PAD, [float]$g.searchY, [float]$g.fieldW, [float]$SEARCH_H)
  $gr.DrawRectangle((New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(255,122,122,122))), [float]$PAD, [float]$g.searchY, [float]$g.fieldW, [float]$SEARCH_H)
  $gr.DrawString("R.I.P.", $fSmall, $brWhite, [float]($PAD+4), [float]($g.searchY+6))
  foreach ($b in @(@{t="搜索"; x=$g.searchX; w=$SEARCH_BTN}, @{t="排序：评分"; x=$g.orderX; w=$ORDER_BTN}, @{t="[✓] 仅上架"; x=$g.stableX; w=$STABLE_BTN})) {
    $gr.FillRectangle((New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255,42,42,42))), [float]$b.x, [float]$g.searchY, [float]$b.w, [float]$SEARCH_H)
    $gr.DrawRectangle((New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(255,120,140,170))), [float]$b.x, [float]$g.searchY, [float]$b.w, [float]$SEARCH_H)
    $gr.DrawString($b.t, $fSmall, $brWhite, [float]($b.x + $b.w/2), [float]($g.searchY+6), $sfC)
  }

  # 列表
  $titles = @("R.I.P.","RE Aoharu","big song (self insert)","Kaleidoscope","Leave it to Fate","evergod",
              "Crush Alcohol","打上花火","PANDORA PARADOXXX","Verruckt","Re End of a Dream","Dead Soul")
  $metas = @("IN Lv.14","IN Lv.15","MY LIFETIME Lv.16","IN Lv.13","IN Lv.15","AT Lv.16",
             "IN Lv.16","IN Lv.14","AT Lv.17","IN Lv.16","IN Lv.15","IN Lv.13")
  $charters = @("W.H.C.","NC Vigour","INTERИET","Pois0n","him","Sei","企鹅","FLAG","Calepam","Rain","NceS","某谱师")
  for ($i = 0; $i -lt $g.per; $i++) {
    $ry = $g.listTop + $i * $ROW_STEP
    $isSel = ($i -eq 0)
    $brRow = if ($isSel) { New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(138,58,90,138)) }
             else { New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(80,0,0,0)) }
    $gr.FillRectangle($brRow, [float]$PAD, [float]$ry, [float]($W-2*$PAD), [float]$ROW_H)
    if ($isSel) { $gr.FillRectangle($brAcc, [float]$PAD, [float]$ry, [float]3, [float]$ROW_H) }
    # 缩略图占位（真机上是 20×20 的曲绘）
    $gr.FillRectangle((New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255,58,58,70))), [float]$g.thumbX, [float]($ry+2), [float]$THUMB, [float]$THUMB)
    $gr.DrawRectangle((New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(255,90,90,106))), [float]$g.thumbX, [float]($ry+2), [float]$THUMB, [float]$THUMB)
    $gr.DrawString("曲", $fSmall, $brGray, [float]($g.thumbX + $THUMB/2), [float]($ry+6), $sfC)
    $ty = $ry + ($ROW_H - 9)/2 + 1
    $title = $titles[$i % $titles.Count]
    # 每 6 行模拟一条「未上架」：曲名灰一档 + 元信息后挂 [未上架]
    $unpub = ($i % 6 -eq 5)
    $brTitle = if ($unpub) { $brGray } elseif ($isSel) { $brWhite } else { $brText }
    $gr.DrawString($title, $fText, $brTitle, [float]$g.textX, [float]$ty)
    $tw = $gr.MeasureString($title, $fText).Width
    if (($g.textX + $tw + 10) -lt ($g.textRight - 40)) {
      $meta = "· $($metas[$i % $metas.Count])  · 评分 0.96  · $($charters[$i % $charters.Count])"
      $brMeta = if ($unpub) { $brGray } else { $brDim }
      $gr.DrawString($meta, $fSmall, $brMeta, [float]($g.textX + $tw + 10), [float]($ty + 1))
      if ($unpub) {
        $mw = $gr.MeasureString($meta, $fSmall).Width
        $gr.DrawString(" [未上架]", $fSmall, $brGray, [float]($g.textX + $tw + 10 + $mw), [float]($ty + 1))
      }
    }
    # 右侧状态（第 2 行显示下载中，第 3 行显示失败）
    if ($i -eq 1) {
      $st = "下载中 61%"; $sw = $gr.MeasureString($st, $fSmall).Width
      $gr.DrawString($st, $fSmall, $brAcc, [float]($g.textRight - $sw), [float]($ty + 1))
    } elseif ($i -eq 2) {
      $st = "失败"; $sw = $gr.MeasureString($st, $fSmall).Width
      $gr.DrawString($st, $fSmall, $brRed, [float]($g.textRight - $sw), [float]($ty + 1))
    }
    # 下载按钮
    $dlY = $ry + ($ROW_H - $COPY_H)/2
    $label = if ($i -eq 1) { "下载中" } elseif ($i -eq 3) { "已下载" } else { "下载" }
    $gr.FillRectangle((New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255,42,42,42))), [float]$g.dlX, [float]$dlY, [float]$COPY_W, [float]$COPY_H)
    $penColor = if ($label -eq "下载") { [System.Drawing.Color]::FromArgb(255,120,140,170) } else { [System.Drawing.Color]::FromArgb(255,90,90,90) }
    $gr.DrawRectangle((New-Object System.Drawing.Pen($penColor)), [float]$g.dlX, [float]$dlY, [float]$COPY_W, [float]$COPY_H)
    $brLabel = if ($label -eq "下载") { $brWhite } else { $brGray }
    $gr.DrawString($label, $fSmall, $brLabel, [float]($g.dlX + $COPY_W/2), [float]($dlY+3), $sfC)
  }

  # 状态行 + 版权行
  $gr.DrawString("下载中：RE Aoharu 61%（4.2 MB / 6.9 MB）", $fSmall, $brAcc, [float]$PAD, [float]$g.statusY)
  $gr.DrawString("谱面来自 Phira 社区，版权归原作者所有（只下载到本地，不打包进 mod）", $fSmall, $brGray, [float]$PAD, [float]$g.legalY)

  # 底部按钮（在线页签：开始/自动游玩不可用，翻页可用）
  $btns = @(
    @{ label = "← 上一页"; x = $g.xPrev; w = $g.bw; on = $true },
    @{ label = "开始";     x = $g.xStart; w = $g.bw; on = $false },
    @{ label = "[ ] 自动游玩"; x = $g.xAuto; w = $g.autoW; on = $false; hide = $true },
    @{ label = "关闭";     x = $g.xBack; w = $g.bw; on = $true },
    @{ label = "设置";     x = $g.xSet; w = $g.bw; on = $true },
    @{ label = "下一页 →"; x = $g.xNext; w = $g.bw; on = $true }
  )
  foreach ($b in $btns) {
    if ($b.hide) { continue }   # 在线页签把「自动游玩」整个藏掉
    $pen = if ($b.on) { New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(255,120,140,170)) }
           else { New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(255,80,80,80)) }
    $gr.FillRectangle((New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255,42,42,42))), [float]$b.x, [float]$g.bY, [float]$b.w, [float]20)
    $gr.DrawRectangle($pen, [float]$b.x, [float]$g.bY, [float]$b.w, [float]20)
    $gr.DrawString($b.label, $fSmall, $(if ($b.on) { $brWhite } else { $brGray }), [float]($b.x + $b.w/2), [float]($g.bY+5), $sfC)
  }

  # 页脚「非官方同人项目」声明条（两个页签都常驻）
  $gr.FillRectangle((New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(102, 0, 0, 0))),
                    [float]0, [float]$g.disclaimY, [float]$W, [float]$DISCLAIM_H)
  $brDisclaim = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255,154,160,168))
  $dw = $gr.MeasureString($DISCLAIM_ZH, $fSmall).Width
  $gr.DrawString($DISCLAIM_ZH, $fSmall, $brDisclaim, [float](($W - $dw) / 2), [float]($g.disclaimTextY - 2))

  # 边界线：红=搜索栏底 / 绿=列表底 / 黄=状态行 / 蓝=底部按钮顶
  $gr.DrawLine((New-Object System.Drawing.Pen([System.Drawing.Color]::Red)), 0, [float]($g.searchY + $SEARCH_H), [float]$W, [float]($g.searchY + $SEARCH_H))
  $gr.DrawLine((New-Object System.Drawing.Pen([System.Drawing.Color]::Lime)), 0, [float]$g.lBottom, [float]$W, [float]$g.lBottom)
  $gr.DrawLine((New-Object System.Drawing.Pen([System.Drawing.Color]::Gold)), 0, [float]$g.statusY, [float]$W, [float]$g.statusY)
  $gr.DrawLine((New-Object System.Drawing.Pen([System.Drawing.Color]::DeepSkyBlue)), 0, [float]$g.bY, [float]$W, [float]$g.bY)

  $bmp.Save($outPath, [System.Drawing.Imaging.ImageFormat]::Png)
  $gr.Dispose(); $bmp.Dispose()
  Write-Output ("  预览: {0}" -f $outPath)
}

foreach ($size in @(@(1280,720), @(854,480))) {
  $W = $size[0]; $H = $size[1]
  Write-Output ("== 在线页 {0}×{1} ==" -f $W, $H)
  $g = Get-Geometry $W $H
  Assert-Geometry $g
  Draw-Online $g (Join-Path $OUT_DIR ("online-{0}x{1}.png" -f $W, $H))
}

Write-Output ""
if ($fails -eq 0) { Write-Output "全部通过 ✓" } else { Write-Output ("有 {0} 项失败 ✗" -f $fails); exit 1 }
