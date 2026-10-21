# 版式核对：选曲界面（SongSelectScreen）本地页 + 游玩 HUD 的 AUTO 标记
#
# 这个脚本是 mod 里 client/SongSelectScreen.java / client/PlayScreen.java 的「版式副本」：
# 常量、算式一一对应（PAD / HEADER_H / INFO_* / ROW_* / NOTICE_H / COPY_* / BTN_W / AUTO_W /
# FILTER_* / SORT_BTN_W / LOCK_BTN_W / DIFF_BTN_W …）。
# 它做两件事：
#   1) 按两个分辨率（1280×720 / 854×480）算一遍坐标，打印坐标表；并断言
#      「页签 / 详情面板 / 本地页搜索·排序·筛选那一行 / 列表 / 提示行 / 底部 6 个按钮 /
#      拷芯片按钮 / 信息列 / HUD AUTO 标记」互不重叠（断言失败 → 退出码 1）；
#   2) 画预览 PNG（输出到 C:\dsh-work\ui-check\，不进仓库）。
#
# 用法：powershell -NoProfile -ExecutionPolicy Bypass -File tools/ui/select-layout.ps1
Add-Type -AssemblyName System.Drawing

$OUT_DIR = "C:\dsh-work\ui-check"
New-Item -ItemType Directory -Force -Path $OUT_DIR | Out-Null

# ---------------- 常量（与 SongSelectScreen 一一对应） ----------------
$PAD        = 12
$HEADER_H   = 34
$ROW_H      = 20
$ROW_STEP   = 22
$PER_MAX    = 20
$PER_MIN    = 1
$INFO_STEP  = 12
$INFO_FIRST = 18
$INFO_ROWS  = 8
$INFO_TEXT  = 9
$INFO_PAD   = 4
$LABEL_W    = 34
$BOTTOM_H   = 26
$BTN_W      = 70
$AUTO_W     = 100
$BTN_GAP    = 8
$NOTICE_H   = 12
$COPY_W     = 56
$COPY_H     = 16
# 本地页：搜索 / 排序 / 筛选
$SEARCH_H     = 20          # FILTER_H = SEARCH_H
$FILTER_GAP_T = 8
$FILTER_GAP_L = 6
$SORT_BTN_W   = 84
$LOCK_BTN_W   = 76
$DIFF_BTN_W   = 76
# 顶部页签
$TAB_W = 48
$TAB_H = 18
$TAB_Y = 3

$INFO_NEED = $INFO_FIRST + ($INFO_ROWS - 1) * $INFO_STEP + $INFO_TEXT + $INFO_PAD   # 115

function Get-Geometry([int]$W, [int]$H) {
  $S        = [Math]::Max(48, [Math]::Round($H * 3.0 / 16.0))
  $detailH  = [Math]::Max($S, $INFO_NEED)
  $dTop     = $HEADER_H
  $dBottom  = $dTop + $detailH
  $dPanel   = $dBottom + 6                     # 详情面板 fill 的底边

  # 本地页筛选行（右对齐到 W-PAD）
  $filterY  = $dBottom + $FILTER_GAP_T
  $diffX    = $W - $PAD - $DIFF_BTN_W
  $lockX    = $diffX - $BTN_GAP - $LOCK_BTN_W
  $sortX    = $lockX - $BTN_GAP - $SORT_BTN_W
  $fieldW   = [Math]::Max(40, $sortX - $BTN_GAP - $PAD)
  $filterBottom = $filterY + $SEARCH_H

  $lTop     = $filterBottom + $FILTER_GAP_L
  $lBottom  = $H - $BOTTOM_H - 4
  $per      = [Math]::Max($PER_MIN, [Math]::Min($PER_MAX, [Math]::Floor(($lBottom - $NOTICE_H - $lTop) / $ROW_STEP)))
  $copyX    = $W - $PAD - 4 - $COPY_W
  $copyRight= $W - $PAD - 4
  $textRight= $copyX - 8
  $infoX    = $PAD + $S + 14
  $noticeY  = $lBottom - $NOTICE_H + 2

  # 底部 6 个槽位：5×70 + 自动游玩 100，间距 8，整体居中（窄屏按比例收窄）
  $bw    = $BTN_W
  $autoW = $AUTO_W
  $total = $bw * 5 + $autoW + $BTN_GAP * 5
  $avail = $W - 2 * $PAD
  if ($total -gt $avail) {
    $bw = [Math]::Max(30, [Math]::Floor(($avail - $BTN_GAP * 5) / 6))
    $autoW = $bw
    $total = $bw * 6 + $BTN_GAP * 5
  }
  $x0     = [Math]::Floor($W / 2 - $total / 2)
  $xPrev  = $x0
  $xStart = $xPrev + $bw + $BTN_GAP
  $xAuto  = $xStart + $bw + $BTN_GAP
  $xBack  = $xAuto + $autoW + $BTN_GAP
  $xSet   = $xBack + $bw + $BTN_GAP
  $xNext  = $xSet + $bw + $BTN_GAP
  $bY     = $H - $BOTTOM_H

  # 游玩 HUD 的 AUTO 标记（PlayScreen：margin = max(8, round(W×0.03))，框 = (margin-3, margin-3)）
  $margin    = [Math]::Max(8, [Math]::Round($W * 0.03))
  $autoTextW = 4 * 6
  $hudAutoX0 = $margin - 3
  $hudAutoY0 = $margin - 3
  $hudAutoX1 = $margin + $autoTextW + 4
  $hudAutoY1 = $margin + 12

  [pscustomobject]@{
    W = $W; H = $H
    S = $S; infoNeed = $INFO_NEED; detailH = $detailH
    dTop = $dTop; dBottom = $dBottom; dPanelBottom = $dPanel
    filterY = $filterY; filterBottom = $filterBottom
    fieldW = $fieldW; sortX = $sortX; lockX = $lockX; diffX = $diffX
    lTop = $lTop; lBottom = $lBottom; per = $per
    lastRowBottom = $lTop + ($per - 1) * $ROW_STEP + $ROW_H
    noticeY = $noticeY; noticeBottom = $noticeY + $INFO_TEXT
    infoX = $infoX; infoRight = $W - $PAD
    lastInfoY = $dTop + $INFO_FIRST + ($INFO_ROWS - 1) * $INFO_STEP
    lastInfoBottom = $dTop + $INFO_FIRST + ($INFO_ROWS - 1) * $INFO_STEP + $INFO_TEXT
    coverRight = $PAD + $S; coverBottom = $dTop + $S
    copyX = $copyX; copyRight = $copyRight; textRight = $textRight
    bw = $bw; autoW = $autoW; bTotal = $total
    xPrev = $xPrev; xStart = $xStart; xAuto = $xAuto; xBack = $xBack; xSet = $xSet; xNext = $xNext
    bY = $bY; bBottom = $bY + 20
    hudAutoX0 = $hudAutoX0; hudAutoY0 = $hudAutoY0; hudAutoX1 = $hudAutoX1; hudAutoY1 = $hudAutoY1
    margin = $margin
  }
}

function Test-Layout($g) {
  $script:__bad = @()
  function Check($cond, $msg) { if (-not $cond) { $script:__bad += $msg } }

  # 1) 顶部页签放得下、且不压详情区
  Check ((2 * ($PAD + $TAB_W + 4 + $TAB_W)) -lt $g.W) "页签整行放不下"
  Check (($TAB_Y + $TAB_H) -lt $g.dTop) "页签底 $($TAB_Y + $TAB_H) 压到详情区顶 $($g.dTop)"
  # 2) 详情面板 fill 底 <= 筛选行顶（面板是在 super.render 之后画的，压上去会盖住搜索框）
  Check ($g.dPanelBottom -le $g.filterY) "详情面板底 $($g.dPanelBottom) 压到筛选行 $($g.filterY)"
  # 3) 筛选行四个控件：都在屏幕内、两两相隔 >= BTN_GAP、搜索框 >= 40
  $fx1 = $PAD + $g.fieldW
  Check ($fx1 -le $g.sortX) "搜索框右边界 $fx1 压到「排序」$($g.sortX)"
  Check (($g.sortX + $SORT_BTN_W) -le $g.lockX) "「排序」右边界 $($g.sortX + $SORT_BTN_W) 压到「锁定」$($g.lockX)"
  Check (($g.lockX + $LOCK_BTN_W) -le $g.diffX) "「锁定」右边界 $($g.lockX + $LOCK_BTN_W) 压到「难度」$($g.diffX)"
  Check (($g.diffX + $DIFF_BTN_W) -eq ($g.W - $PAD)) "「难度」右边界没贴住 W-PAD（$($g.diffX + $DIFF_BTN_W) vs $($g.W - $PAD)）"
  Check ($g.fieldW -ge 40) "搜索框太窄（$($g.fieldW) < 40）"
  Check ($g.filterBottom -le $g.lTop) "筛选行底 $($g.filterBottom) 压到列表顶 $($g.lTop)"
  # 4) 列表最后一行不越过「列表底 - 提示行」
  Check ($g.lastRowBottom -le ($g.lBottom - $NOTICE_H)) "最后一行底 $($g.lastRowBottom) 压到提示行（上限 $($g.lBottom - $NOTICE_H)）"
  # 5) 提示行在最后一行下面、列表底之内、底部按钮上面
  Check ($g.noticeY -ge $g.lastRowBottom) "提示行 y=$($g.noticeY) 压到列表最后一行底 $($g.lastRowBottom)"
  Check ($g.noticeBottom -le $g.lBottom) "提示行底 $($g.noticeBottom) 超过列表底 $($g.lBottom)"
  Check ($g.noticeBottom -le $g.bY) "提示行底 $($g.noticeBottom) 压到底部按钮 $($g.bY)"
  # 6) 信息列 8 行都在详情面板里
  Check ($g.lastInfoBottom -le $g.dBottom) "信息列最后一行底 $($g.lastInfoBottom) 超过详情区底 $($g.dBottom)"
  Check ($g.infoX -gt $g.coverRight) "信息列 x=$($g.infoX) 压到曲绘右边 $($g.coverRight)"
  Check (($g.infoX + $LABEL_W) -lt $g.infoRight) "信息列标签列超右边界"
  # 7) 行内「拷芯片」按钮
  Check ($g.copyX -gt ($PAD + 8)) "拷芯片按钮 x=$($g.copyX) 太靠左"
  Check ($g.copyRight -le ($g.W - $PAD)) "拷芯片按钮右边界 $($g.copyRight) 超出内容区"
  Check ((($g.lTop + ($g.per - 1) * $ROW_STEP + ($ROW_H - $COPY_H) / 2) + $COPY_H) -le ($g.lBottom - $NOTICE_H)) "最后一行拷芯片按钮压到提示行"
  # 8) 底部 6 个按钮：都在屏幕内、两两不重叠（间距 >= BTN_GAP）
  $slots = @(
    @{ n = "上一页";   x0 = $g.xPrev;  x1 = $g.xPrev + $g.bw },
    @{ n = "开始";     x0 = $g.xStart; x1 = $g.xStart + $g.bw },
    @{ n = "自动游玩"; x0 = $g.xAuto;  x1 = $g.xAuto + $g.autoW },
    @{ n = "商店/关闭"; x0 = $g.xBack;  x1 = $g.xBack + $g.bw },
    @{ n = "设置";     x0 = $g.xSet;   x1 = $g.xSet + $g.bw },
    @{ n = "下一页";   x0 = $g.xNext;  x1 = $g.xNext + $g.bw }
  )
  Check ($slots[0].x0 -ge $PAD) "底部按钮左边界越界（$($slots[0].x0) < $PAD）"
  Check ($slots[5].x1 -le ($g.W - $PAD)) "底部按钮右边界越界（$($slots[5].x1) > $($g.W - $PAD)）"
  Check ($g.bBottom -le $g.H) "底部按钮超出屏幕高"
  for ($i = 1; $i -lt $slots.Count; $i++) {
    Check ($slots[$i].x0 -ge ($slots[$i-1].x1 + $BTN_GAP)) (("底部按钮重叠：{0} 右 {1} → {2} 左 {3}" -f $slots[$i-1].n, $slots[$i-1].x1, $slots[$i].n, $slots[$i].x0))
  }
  Check ($slots[2].x0 -eq ($slots[1].x1 + $BTN_GAP)) "「自动游玩」没有紧挨「开始」"
  # 9) 曲绘在详情区里
  Check ($g.coverBottom -le $g.dBottom) "曲绘底 $($g.coverBottom) 超过详情区底 $($g.dBottom)"
  # 10) HUD AUTO 标记
  Check ($g.hudAutoX0 -ge 0) "AUTO 标记左越界"
  Check ($g.hudAutoX1 -lt ($g.W / 2)) "AUTO 标记压到居中的连击数字（右边界 $($g.hudAutoX1) ≥ 半屏 $($g.W / 2)）"
  Check ($g.hudAutoY1 -lt ($g.H / 2)) "AUTO 标记进了下半屏（底 $($g.hudAutoY1) ≥ $($g.H / 2)）"
  Check ($g.hudAutoY0 -ge 0) "AUTO 标记上越界"
  Check ($g.hudAutoX1 -lt ($g.W - $g.margin - 42)) "AUTO 标记接近右上角分数（$($g.hudAutoX1)）"

  return $script:__bad
}

function Show-Table($g) {
  Write-Output ""
  Write-Output ("=== {0}×{1} ===" -f $g.W, $g.H)
  # 注意：@() 里的逗号会先被当成数组分隔符，"-f" 会被拆开 → 逐行用变量拼好再输出
  $line = { param($k, $v) Write-Output ("  {0,-34} {1}" -f $k, $v) }
  & $line "顶部页签 y / 两个页签"     ("{0}..{1} / 本地 {2}..{3}，在线 {4}..{5}" -f $TAB_Y, ($TAB_Y + $TAB_H), $PAD, ($PAD + $TAB_W), ($PAD + $TAB_W + 4), ($PAD + $TAB_W + 4 + $TAB_W))
  & $line "曲绘方框 (x,y,w,h)"       ("({0},{1}) {2}×{2}" -f $PAD, $g.dTop, $g.S)
  & $line "信息列 x / 右边界"        ("{0} / {1}" -f $g.infoX, $g.infoRight)
  & $line "信息 8 行 y（难度…最佳）"  ("{0} … {1}（步长 {2}）" -f ($g.dTop + $INFO_FIRST), $g.lastInfoY, $INFO_STEP)
  & $line "详情区 y（dTop…bottom）"   ("{0} … {1}  (infoNeed={2}, S={3})" -f $g.dTop, $g.dBottom, $g.infoNeed, $g.S)
  & $line "详情面板 fill 底"          ("{0}" -f $g.dPanelBottom)
  & $line "筛选行 y（高度）"          ("{0}..{1}（{2}）" -f $g.filterY, $g.filterBottom, $SEARCH_H)
  & $line "  搜索框 x / 宽"          ("{0}..{1}（宽 {2}）" -f $PAD, ($PAD + $g.fieldW), $g.fieldW)
  & $line "  排序：xx"               ("{0}..{1}（宽 {2}）" -f $g.sortX, ($g.sortX + $SORT_BTN_W), $SORT_BTN_W)
  & $line "  锁定：xx"               ("{0}..{1}（宽 {2}）" -f $g.lockX, ($g.lockX + $LOCK_BTN_W), $LOCK_BTN_W)
  & $line "  难度：xx"               ("{0}..{1}（宽 {2}）" -f $g.diffX, ($g.diffX + $DIFF_BTN_W), $DIFF_BTN_W)
  & $line "列表 y（top…bottom）"      ("{0} … {1}" -f $g.lTop, $g.lBottom)
  & $line "每页行数 / 最后一行底"     ("{0} / {1}" -f $g.per, $g.lastRowBottom)
  & $line "拷芯片按钮 x 范围"         ("x={0}..{1}（宽 {2} 高 {3}）" -f $g.copyX, $g.copyRight, $COPY_W, $COPY_H)
  & $line "提示行 y"                 ("{0} … {1}" -f $g.noticeY, $g.noticeBottom)
  & $line "底部按钮 y / 高"           ("{0} … {1}" -f $g.bY, $g.bBottom)
  & $line "  ← 上一页 x"             ("{0}..{1}" -f $g.xPrev, ($g.xPrev + $g.bw))
  & $line "  开始 x"                 ("{0}..{1}" -f $g.xStart, ($g.xStart + $g.bw))
  & $line "  [✓] 自动游玩 x（宽）"    ("{0}..{1}（{2}）" -f $g.xAuto, ($g.xAuto + $g.autoW), $g.autoW)
  & $line "  商店/关闭 x"            ("{0}..{1}" -f $g.xBack, ($g.xBack + $g.bw))
  & $line "  设置 x"                 ("{0}..{1}" -f $g.xSet, ($g.xSet + $g.bw))
  & $line "  下一页 x"               ("{0}..{1}" -f $g.xNext, ($g.xNext + $g.bw))
  & $line "整行宽 / 左起"            ("{0} / {1}（间距 {2}）" -f $g.bTotal, $g.xPrev, $BTN_GAP)
  & $line "HUD AUTO 标记框"          ("({0},{1})..({2},{3})  margin={4}" -f $g.hudAutoX0, $g.hudAutoY0, $g.hudAutoX1, $g.hudAutoY1, $g.margin)
}

function Draw-Layout($g, [string]$outPath) {
  $W = $g.W; $H = $g.H
  $bmp = New-Object System.Drawing.Bitmap($W, $H)
  $gr = [System.Drawing.Graphics]::FromImage($bmp)
  $gr.SmoothingMode = 'AntiAlias'
  $gr.TextRenderingHint = 'ClearTypeGridFit'
  $gr.Clear([System.Drawing.Color]::FromArgb(255, 24, 24, 30))

  $fTitle = New-Object System.Drawing.Font("Microsoft YaHei", 10, [System.Drawing.FontStyle]::Bold)
  $fText  = New-Object System.Drawing.Font("Microsoft YaHei", 8)
  $fSmall = New-Object System.Drawing.Font("Microsoft YaHei", 7)
  $fBig   = New-Object System.Drawing.Font("Microsoft YaHei", 12, [System.Drawing.FontStyle]::Bold)
  $brWhite = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::White)
  $brDim   = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255,154,154,154))
  $brLock  = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255,107,107,107))
  $brAcc   = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255,180,225,255))
  $brGold  = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255,255,236,159))
  $brGreen = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255,124,252,138))
  $sf = New-Object System.Drawing.StringFormat
  $sf.Alignment = 'Center'
  $sfL = New-Object System.Drawing.StringFormat
  $sfL.Alignment = 'Near'

  # 顶部：页签（本地选中）+ 标题 + 副标题
  $gr.FillRectangle((New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255,58,90,138))), [float]$PAD, [float]$TAB_Y, [float]$TAB_W, [float]$TAB_H)
  $gr.DrawRectangle((New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(255,120,140,170))), [float]$PAD, [float]$TAB_Y, [float]$TAB_W, [float]$TAB_H)
  $gr.DrawString("本地", $fSmall, $brWhite, [float]($PAD + $TAB_W/2), [float]($TAB_Y + 4), $sf)
  $tab2 = $PAD + $TAB_W + 4
  $gr.FillRectangle((New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255,42,42,42))), [float]$tab2, [float]$TAB_Y, [float]$TAB_W, [float]$TAB_H)
  $gr.DrawRectangle((New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(255,120,140,170))), [float]$tab2, [float]$TAB_Y, [float]$TAB_W, [float]$TAB_H)
  $gr.DrawString("在线", $fSmall, $brWhite, [float]($tab2 + $TAB_W/2), [float]($TAB_Y + 4), $sf)
  $gr.DrawString("PhiMC 选曲", $fTitle, $brWhite, [float]($W/2), [float]4, $sf)
  $sub = "筛后 3/19 首 · 第 1/2 页 · 未解锁的谱面不能开始 · ↑↓/滚轮/点击切换 · 回车开始 · Esc 返回"
  $gr.DrawString($sub, $fSmall, $brDim, [float]($tab2 + $TAB_W + 8), [float]20, $sfL)

  # 详情面板
  $brPanel = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(112,0,0,0))
  $gr.FillRectangle($brPanel, [float]($PAD-4), [float]($g.dTop-2), [float]($W-2*$PAD+8), [float]($g.dPanelBottom-($g.dTop-2)))
  $gr.DrawRectangle((New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(90,255,255,255))), [float]($PAD-4), [float]($g.dTop-2), [float]($W-2*$PAD+8), [float]($g.dPanelBottom-($g.dTop-2)))

  # 曲绘占位
  $gr.FillRectangle((New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255,58,58,70))), [float]$PAD, [float]$g.dTop, [float]$g.S, [float]$g.S)
  $gr.DrawRectangle((New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(255,90,90,106))), [float]$PAD, [float]$g.dTop, [float]$g.S, [float]$g.S)
  $gr.DrawString("曲绘 $($g.S)×$($g.S)", $fSmall, $brDim, [float]$PAD, [float]($g.dTop + $g.S/2 - 6))

  # 信息列
  $gr.DrawString("Hardsync", $fBig, $brWhite, [float]$g.infoX, [float]($g.dTop - 1))
  $infoRows = @(
    @("难度","AT Lv.16"), @("物量","1739"), @("BPM","200"),
    @("曲师","未知"), @("谱师","Unknown"), @("画师","未知"),
    @("状态","已解锁（播放器）"), @("最佳","S  0098765  98.76%  FC")
  )
  $y = $g.dTop + $INFO_FIRST
  foreach ($r in $infoRows) {
    $gr.DrawString($r[0], $fSmall, $brDim, [float]$g.infoX, [float]$y)
    switch ($r[0]) {
      "状态" { $br = if ($r[1] -like "*未解锁*") { $brLock } else { $brGreen } }
      "最佳" { $br = $brGold }
      "难度" { $br = $brAcc }
      { $_ -in @("曲师","谱师","画师") } { $br = if ($r[1] -eq "未知") { $brLock } else { $brWhite } }
      default { $br = $brWhite }
    }
    $gr.DrawString($r[1], $fText, $br, [float]($g.infoX + $LABEL_W), [float]($y - 1))
    $y += $INFO_STEP
  }

  # ---- 本地页筛选行：搜索框 + 排序 / 锁定 / 难度 ----
  $fy = $g.filterY
  $gr.FillRectangle((New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255,20,20,20))), [float]$PAD, [float]$fy, [float]$g.fieldW, [float]$SEARCH_H)
  $gr.DrawRectangle((New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(255,160,160,160))), [float]$PAD, [float]$fy, [float]$g.fieldW, [float]$SEARCH_H)
  $gr.DrawString("hard（搜索曲名 / 曲师 / 谱师）", $fSmall, $brDim, [float]($PAD + 4), [float]($fy + 4), $sfL)
  $filterBtns = @(
    @{ label = "排序：曲名"; x = $g.sortX; w = $SORT_BTN_W },
    @{ label = "锁定：全部"; x = $g.lockX; w = $LOCK_BTN_W },
    @{ label = "难度：全部"; x = $g.diffX; w = $DIFF_BTN_W }
  )
  foreach ($b in $filterBtns) {
    $gr.FillRectangle((New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255,42,42,42))), [float]$b.x, [float]$fy, [float]$b.w, [float]$SEARCH_H)
    $gr.DrawRectangle((New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(255,120,140,170))), [float]$b.x, [float]$fy, [float]$b.w, [float]$SEARCH_H)
    $gr.DrawString($b.label, $fSmall, $brWhite, [float]($b.x + $b.w/2), [float]($fy + 5), $sf)
  }

  # ---- 列表（筛后 3 首的示意：第 1 行有拷芯片按钮） ----
  $titles = @("Hardsync - AT Lv.16","Hardsync - IN Lv.14","Hardcore Tano*C - HD Lv.12")
  $rows = [Math]::Min($g.per, 6)
  for ($i = 0; $i -lt $rows; $i++) {
    $ry = $g.lTop + $i * $ROW_STEP
    $isSel = ($i -eq 0)
    $owned = ($i -eq 0)
    $brRow = if ($isSel) { New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(138,58,90,138)) }
             else { New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(80,0,0,0)) }
    $gr.FillRectangle($brRow, [float]$PAD, [float]$ry, [float]($W-2*$PAD), [float]$ROW_H)
    if ($isSel) { $gr.FillRectangle($brAcc, [float]$PAD, [float]$ry, [float]3, [float]$ROW_H) }
    $t = "$($i+1). $($titles[$i % $titles.Count])"
    $gr.DrawString($t, $fText, $(if ($isSel) { $brWhite } else { $brDim }), [float]($PAD+8), [float]($ry+4))
    $best = "A  0081234  96.12%"
    $bwTxt = $gr.MeasureString($best, $fSmall).Width
    $gr.DrawString($best, $fSmall, $brDim, [float]($g.textRight - $bwTxt), [float]($ry+5))
    if ($owned) {
      $cy = $ry + ($ROW_H - $COPY_H) / 2
      $gr.FillRectangle((New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255,42,42,42))), [float]$g.copyX, [float]$cy, [float]$COPY_W, [float]$COPY_H)
      $gr.DrawRectangle((New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(255,120,140,170))), [float]$g.copyX, [float]$cy, [float]$COPY_W, [float]$COPY_H)
      $gr.DrawString("拷芯片", $fSmall, $brWhite, [float]($g.copyX + $COPY_W/2), [float]($cy + 3), $sf)
    }
  }

  # 提示行（排序切换后的提示）
  $gr.DrawString("排序：曲名（相同难度按曲名，相同曲名保持添加顺序）", $fSmall, $brAcc, [float]$PAD, [float]$g.noticeY)

  # 底部 6 个按钮
  $btns = @(
    @{ label = "← 上一页"; x = $g.xPrev; w = $g.bw },
    @{ label = "开始";     x = $g.xStart; w = $g.bw },
    @{ label = "[✓] 自动游玩"; x = $g.xAuto; w = $g.autoW },
    @{ label = "关闭";     x = $g.xBack; w = $g.bw },
    @{ label = "设置";     x = $g.xSet; w = $g.bw },
    @{ label = "下一页 →"; x = $g.xNext; w = $g.bw }
  )
  foreach ($b in $btns) {
    $isAuto = $b.label -like "*自动游玩*"
    $pen = if ($isAuto) { New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(255,255,236,159)) }
           else { New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(255,120,140,170)) }
    $gr.FillRectangle((New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255,42,42,42))), [float]$b.x, [float]$g.bY, [float]$b.w, [float]20)
    $gr.DrawRectangle($pen, [float]$b.x, [float]$g.bY, [float]$b.w, [float]20)
    $gr.DrawString($b.label, $fSmall, $(if ($isAuto) { $brGold } else { $brWhite }), [float]($b.x + $b.w/2), [float]($g.bY+5), $sf)
  }

  # 边界线：红=详情面板底 / 青=筛选行底 / 绿=列表底 / 蓝=底部按钮顶 / 黄=提示行顶
  $gr.DrawLine((New-Object System.Drawing.Pen([System.Drawing.Color]::Red)), 0, [float]$g.dPanelBottom, [float]$W, [float]$g.dPanelBottom)
  $gr.DrawLine((New-Object System.Drawing.Pen([System.Drawing.Color]::Cyan)), 0, [float]$g.filterBottom, [float]$W, [float]$g.filterBottom)
  $gr.DrawLine((New-Object System.Drawing.Pen([System.Drawing.Color]::Lime)), 0, [float]$g.lBottom, [float]$W, [float]$g.lBottom)
  $gr.DrawLine((New-Object System.Drawing.Pen([System.Drawing.Color]::DeepSkyBlue)), 0, [float]$g.bY, [float]$W, [float]$g.bY)
  $gr.DrawLine((New-Object System.Drawing.Pen([System.Drawing.Color]::Gold)), 0, [float]$g.noticeY, [float]$W, [float]$g.noticeY)

  $bmp.Save($outPath, [System.Drawing.Imaging.ImageFormat]::Png)
  $gr.Dispose(); $bmp.Dispose()
  Write-Output ("  预览: {0}" -f $outPath)
}

# 游玩界面 HUD（PlayScreen）版式预览：分数（右上）/ 连击（正上居中）/ AUTO（左上）/ 曲名·难度 / 提示行。
function Draw-Hud($g, [string]$outPath) {
  $W = $g.W; $H = $g.H
  $bmp = New-Object System.Drawing.Bitmap($W, $H)
  $gr = [System.Drawing.Graphics]::FromImage($bmp)
  $gr.SmoothingMode = 'AntiAlias'
  $gr.TextRenderingHint = 'ClearTypeGridFit'
  $gr.Clear([System.Drawing.Color]::FromArgb(255, 16, 16, 20))

  $fText  = New-Object System.Drawing.Font("Microsoft YaHei", 8)
  $fSmall = New-Object System.Drawing.Font("Microsoft YaHei", 7)
  $fScore = New-Object System.Drawing.Font("Consolas", 11, [System.Drawing.FontStyle]::Bold)
  $fCombo = New-Object System.Drawing.Font("Consolas", 24, [System.Drawing.FontStyle]::Bold)
  $brWhite = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::White)
  $brDim   = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255,204,204,204))
  $brGold  = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255,255,236,159))
  $brGray  = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(170,170,170))
  $sfC = New-Object System.Drawing.StringFormat
  $sfC.Alignment = 'Center'
  $sfR = New-Object System.Drawing.StringFormat
  $sfR.Alignment = 'Far'

  $gr.DrawString("0098765", $fScore, $brWhite, [float]($W - $g.margin), [float]($g.margin - 2), $sfR)
  $gr.DrawString("PHI", $fText, $brWhite, [float]($W - $g.margin), [float]($g.margin + 22), $sfR)
  $gr.DrawString("1234", $fCombo, $brWhite, [float]($W/2), [float]($g.margin - 6), $sfC)
  $gr.DrawString("COMBO", $fSmall, $brDim, [float]($W/2), [float]($g.margin + 40), $sfC)
  $gr.FillRectangle((New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(144,0,0,0))), [float]$g.hudAutoX0, [float]$g.hudAutoY0, [float]($g.hudAutoX1-$g.hudAutoX0), [float]($g.hudAutoY1-$g.hudAutoY0))
  $gr.DrawRectangle((New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(255,255,236,159))), [float]$g.hudAutoX0, [float]$g.hudAutoY0, [float]($g.hudAutoX1-$g.hudAutoX0), [float]($g.hudAutoY1-$g.hudAutoY0))
  $gr.DrawString("AUTO", $fSmall, $brGold, [float]$g.margin, [float]($g.margin - 1))
  $gr.DrawString("Hardsync", $fText, $brDim, [float]$g.margin, [float]($H - 34))
  $gr.DrawString("AT Lv.16", $fText, $brDim, [float]($W - $g.margin), [float]($H - 34), $sfR)
  $gr.DrawString("任意键=Tap/Hold · 按住=维持 · 空格=甩 · Esc=暂停（自动游玩中，输入被忽略）", $fSmall, $brGray, [float]8, [float]($H - 16))

  $gr.DrawLine((New-Object System.Drawing.Pen([System.Drawing.Color]::Gold)), 0, [float]$g.hudAutoY1, [float]$W, [float]$g.hudAutoY1)
  $gr.DrawLine((New-Object System.Drawing.Pen([System.Drawing.Color]::Lime)), [float]($W/2), 0, [float]($W/2), [float]$H)

  $bmp.Save($outPath, [System.Drawing.Imaging.ImageFormat]::Png)
  $gr.Dispose(); $bmp.Dispose()
  Write-Output ("  HUD 预览: {0}" -f $outPath)
}

$bad = 0
foreach ($size in @(@(1280,720), @(854,480))) {
  $g = Get-Geometry $size[0] $size[1]
  Show-Table $g
  $errs = Test-Layout $g
  if ($errs.Count -gt 0) {
    $bad += $errs.Count
    foreach ($e in $errs) { Write-Output ("  ✗ " + $e) }
  } else {
    Write-Output "  ✔ 无重叠（页签/详情/筛选行/列表/提示行/底部 6 按钮/拷芯片/信息列/HUD AUTO）"
  }
  Draw-Layout $g (Join-Path $OUT_DIR ("select-layout-{0}x{1}.png" -f $size[0], $size[1]))
  Draw-Hud $g (Join-Path $OUT_DIR ("hud-auto-{0}x{1}.png" -f $size[0], $size[1]))
}

Write-Output ""
if ($bad -eq 0) { Write-Output "结论: ✔ 两个分辨率都通过" } else { Write-Output "结论: ✗ $bad 处重叠"; exit 1 }
