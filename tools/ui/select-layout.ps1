# 版式核对：选曲界面（SongSelectScreen）本地页 + 游玩 HUD 的 AUTO 标记 + 设置页（SettingsScreen）
#
# 这个脚本是 mod 里 client/SongSelectScreen.java / client/PlayScreen.java / client/SettingsScreen.java
# 的「版式副本」：常量、算式一一对应（PAD / HEADER_H / INFO_* / ROW_* / NOTICE_H / COPY_* / BTN_W /
# AUTO_W / FILTER_* / SORT_BTN_W / LOCK_BTN_W / DIFF_BTN_W / DISCLAIM_H …）。
# 它做三件事：
#   1) 按两个分辨率（1280×720 / 854×480）算一遍坐标，打印坐标表；并断言
#      「页签 / 详情面板 / 本地页搜索·排序·筛选那一行 / 列表 / 提示行 / 底部 6 个按钮 /
#      拷芯片按钮 / 信息列 / 页脚「非官方同人项目」声明条 / HUD AUTO 标记」互不重叠，
#      以及声明文字**不被裁掉**（估宽 <= W - 2·PAD）—— 声明文字画在底部那条极淡半透明底条上
#      （断言失败 → 退出码 1）；
#   2) 设置页（SettingsScreen）同样算一遍：返回按钮 / 提示行 / 面板底 / 页脚声明条互不重叠，
#      声明按 0.75 倍字号画也不会被裁；
#   3) 画预览 PNG（输出到 C:\dsh-work\ui-check\，不进仓库）。
#
# 用法：powershell -NoProfile -ExecutionPolicy Bypass -File tools/ui/select-layout.ps1
Add-Type -AssemblyName System.Drawing

$OUT_DIR = "C:\dsh-work\ui-check"
New-Item -ItemType Directory -Force -Path $OUT_DIR | Out-Null

# ---------------- 页脚声明（「非官方同人项目」） ----------------
$DISCLAIM_ZH = "非官方同人项目 · 与鸽游（Pigeon Games）及《Phigros》官方无授权、合作或运营关系"
$DISCLAIM_EN = "Unofficial fan project. Not affiliated with, endorsed by, or operated by Pigeon Games or Phigros."
# MC 默认字体（unicode 页）宽度的保守估法：CJK / 全角标点 9px、空格 4px、其余 ASCII 6px。
# 真实字体不会比这个更宽，所以「估宽 <= 可用宽」就等于「不会被 fit() / 缩字兜底动到」。
function Get-McTextWidth([string]$s) {
  $w = 0
  foreach ($ch in $s.ToCharArray()) {
    $code = [int]$ch
    if ($code -gt 0x2E80) { $w += 9 }        # CJK 汉字 / 全角括号《》、等
    elseif ($code -eq 32) { $w += 4 }        # 空格
    else { $w += 6 }                        # ASCII（含 · 的保守值）
  }
  return $w
}

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
# 页脚「非官方同人项目」声明条（SongSelectScreen.DISCLAIM_H）：底部常驻一条，
# 所以 listBottom 与底部按钮的 y 都整体上移 DISCLAIM_H。
$DISCLAIM_H = 13
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
  $lBottom  = $H - $BOTTOM_H - 4 - $DISCLAIM_H
  $per      = [Math]::Max($PER_MIN, [Math]::Min($PER_MAX, [Math]::Floor(($lBottom - $NOTICE_H - $lTop) / $ROW_STEP)))
  $copyX    = $W - $PAD - 4 - $COPY_W
  $copyRight= $W - $PAD - 4
  $textRight= $copyX - 8
  $infoX    = $PAD + $S + 14
  $noticeY  = $lBottom - $NOTICE_H + 2
  # 页脚声明条：贴屏幕底边，文字在条内垂直居中（13 高放 9px 字 → 上 2px）
  $disclaimY       = $H - $DISCLAIM_H
  $disclaimTextY   = $disclaimY + [Math]::Floor(($DISCLAIM_H - $INFO_TEXT) / 2)
  $disclaimTextBottom = $disclaimTextY + $INFO_TEXT
  $disclaimTextW   = Get-McTextWidth $DISCLAIM_ZH
  $disclaimTextWEn = Get-McTextWidth $DISCLAIM_EN

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
  $bY     = $H - $BOTTOM_H - $DISCLAIM_H

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
    disclaimY = $disclaimY; disclaimTextY = $disclaimTextY
    disclaimTextBottom = $disclaimTextBottom
    disclaimTextW = $disclaimTextW; disclaimTextWEn = $disclaimTextWEn; disclaimAvail = $W - 2 * $PAD
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
  # 11) 页脚「非官方同人项目」声明条：
  #     - 条子在屏幕内、贴底边；文字整行在条内（上不出条、下不出屏）
  #     - 条子顶在底部按钮下面（层次：列表 < 提示行 < 按钮 < 声明）
  #     - 文字不与按钮 / 提示行重叠
  #     - 中/英文估宽都 <= W - 2·PAD（不然 fit() / 缩字兜底会动到它 —— 需求要求「不能被裁掉」）
  Check ($g.disclaimY -ge 0 -and $g.disclaimY -lt $g.H) "声明条 y=$($g.disclaimY) 不在屏幕内"
  Check (($g.disclaimY + $DISCLAIM_H) -eq $g.H) "声明条底没贴住屏幕底（$($g.disclaimY + $DISCLAIM_H) vs $($g.H)）"
  Check ($g.disclaimTextY -ge $g.disclaimY) "声明文字顶 $($g.disclaimTextY) 跑出条顶 $($g.disclaimY)"
  Check ($g.disclaimTextBottom -le $g.H) "声明文字底 $($g.disclaimTextBottom) 超出屏幕高 $($g.H)"
  Check ($g.bBottom -le $g.disclaimY) "底部按钮底 $($g.bBottom) 压到声明条顶 $($g.disclaimY)"
  Check ($g.noticeBottom -le $g.disclaimY) "提示行底 $($g.noticeBottom) 压到声明条 $($g.disclaimY)"
  Check ($g.disclaimTextY -ge $g.bBottom) "声明文字 $($g.disclaimTextY) 压到底部按钮底 $($g.bBottom)"
  Check ($g.disclaimTextW -le $g.disclaimAvail) "声明中文估宽 $($g.disclaimTextW) > 内容区 $($g.disclaimAvail)（会被裁 / 缩字）"
  Check ($g.disclaimTextWEn -le $g.disclaimAvail) "声明英文估宽 $($g.disclaimTextWEn) > 内容区 $($g.disclaimAvail)"

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
  & $line "页脚声明条 y（高）"        ("{0}..{1}（{2}）0x66000000" -f $g.disclaimY, ($g.disclaimY + $DISCLAIM_H), $DISCLAIM_H)
  & $line "  声明文字 y / 估宽"       ("{0}..{1} / 中 {2}px 英 {3}px（可用 {4}px）" -f $g.disclaimTextY, $g.disclaimTextBottom, $g.disclaimTextW, $g.disclaimTextWEn, $g.disclaimAvail)
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

  # 页脚「非官方同人项目」声明条：极淡半透明底 + 居中灰字（和 SongSelectScreen.drawDisclaimer 一致）
  $gr.FillRectangle((New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(102, 0, 0, 0))),
                    [float]0, [float]$g.disclaimY, [float]$W, [float]$DISCLAIM_H)
  $brDisclaim = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 154, 160, 168))
  $dw = $gr.MeasureString($DISCLAIM_ZH, $fSmall).Width
  $gr.DrawString($DISCLAIM_ZH, $fSmall, $brDisclaim, [float](($W - $dw) / 2), [float]($g.disclaimTextY - 2))

  # 边界线：红=详情面板底 / 青=筛选行底 / 绿=列表底 / 蓝=底部按钮顶 / 黄=提示行顶 / 紫=声明条顶
  $gr.DrawLine((New-Object System.Drawing.Pen([System.Drawing.Color]::Red)), 0, [float]$g.dPanelBottom, [float]$W, [float]$g.dPanelBottom)
  $gr.DrawLine((New-Object System.Drawing.Pen([System.Drawing.Color]::Cyan)), 0, [float]$g.filterBottom, [float]$W, [float]$g.filterBottom)
  $gr.DrawLine((New-Object System.Drawing.Pen([System.Drawing.Color]::Lime)), 0, [float]$g.lBottom, [float]$W, [float]$g.lBottom)
  $gr.DrawLine((New-Object System.Drawing.Pen([System.Drawing.Color]::DeepSkyBlue)), 0, [float]$g.bY, [float]$W, [float]$g.bY)
  $gr.DrawLine((New-Object System.Drawing.Pen([System.Drawing.Color]::Gold)), 0, [float]$g.noticeY, [float]$W, [float]$g.noticeY)
  $gr.DrawLine((New-Object System.Drawing.Pen([System.Drawing.Color]::MediumPurple)), 0, [float]$g.disclaimY, [float]$W, [float]$g.disclaimY)

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

# ======================================================================
# 设置页（SettingsScreen）版式：面板 / 行 / 提示行 / [返回] / 页脚声明
# ======================================================================
$S_PANEL_W  = 400
$S_TOP      = 58
$S_ROW_H    = 28
$S_BTN_H    = 18
$S_FOOTER_H = 28
$S_DISCLAIM_H     = 12      # SettingsScreen.DISCLAIM_H
$S_DISCLAIM_SCALE = 0.75    # SettingsScreen.DISCLAIM_SCALE（比选曲界面小一档字号）
$S_DISCLAIM_PAD   = 12      # SettingsScreen.DISCLAIM_PAD

function Get-SettingsGeometry([int]$W, [int]$H) {
  $cx        = [Math]::Floor($W / 2)
  $panelL    = $cx - $S_PANEL_W / 2
  $panelR    = $cx + $S_PANEL_W / 2
  $disclaimY = $H - $S_DISCLAIM_H                 # 页脚声明条顶
  $panelBottom = [Math]::Max(28, $disclaimY)      # 面板底 = 声明条顶（条子在面板外）
  $footerY   = $H - $S_FOOTER_H - $S_DISCLAIM_H   # [返回] 按钮 y
  $btnBottom = $footerY + $S_BTN_H
  $noticeY   = $footerY - 14                      # 提示行（试听 / 偏移 / 切包结果）
  $lastRowTextY = $S_TOP + 6 * $S_ROW_H + 5       # 行 6 的文字 y（最后一行）
  $lastRowTextBottom = $lastRowTextY + 9
  $textH     = 9 * $S_DISCLAIM_SCALE
  $textY     = $disclaimY + [Math]::Round(($S_DISCLAIM_H - $textH) / 2)
  $textW     = Get-McTextWidth $DISCLAIM_ZH
  $textWEn   = Get-McTextWidth $DISCLAIM_EN
  $drawW     = $textW * $S_DISCLAIM_SCALE
  $drawWEn   = $textWEn * $S_DISCLAIM_SCALE
  $avail     = $W - 2 * $S_DISCLAIM_PAD

  [pscustomobject]@{
    W = $W; H = $H
    cx = $cx; panelL = $panelL; panelR = $panelR; panelBottom = $panelBottom
    footerY = $footerY; btnBottom = $btnBottom; noticeY = $noticeY
    lastRowTextY = $lastRowTextY; lastRowTextBottom = $lastRowTextBottom
    disclaimY = $disclaimY; textY = $textY; textBottom = [Math]::Round($textY + $textH)
    textW = $textW; drawW = [Math]::Round($drawW); drawWEn = [Math]::Round($drawWEn); avail = $avail
  }
}

function Test-SettingsLayout($g) {
  $script:__badS = @()
  function CheckS($cond, $msg) { if (-not $cond) { $script:__badS += $msg } }

  CheckS ($g.panelL -ge 0 -and $g.panelR -le $g.W) "设置面板左右越界（$($g.panelL)..$($g.panelR)）"
  CheckS ($g.panelBottom -eq $g.disclaimY) "面板底 $($g.panelBottom) 没让到声明条顶 $($g.disclaimY)"
  CheckS (($g.disclaimY + $S_DISCLAIM_H) -eq $g.H) "设置页声明条底没贴屏幕底"
  CheckS ($g.lastRowTextBottom -le $g.noticeY) "最后一行文字底 $($g.lastRowTextBottom) 压到提示行 $($g.noticeY)"
  CheckS (($g.noticeY + 9) -le $g.footerY) "提示行底 $($g.noticeY + 9) 压到 [返回] 按钮 $($g.footerY)"
  CheckS ($g.btnBottom -le $g.disclaimY) "[返回] 按钮底 $($g.btnBottom) 压到声明条 $($g.disclaimY)"
  CheckS ($g.btnBottom -le $g.panelBottom) "[返回] 按钮跑出面板底 $($g.panelBottom)"
  CheckS ($g.textY -ge $g.disclaimY) "设置页声明文字顶 $($g.textY) 跑出条顶 $($g.disclaimY)"
  CheckS ($g.textBottom -le $g.H) "设置页声明文字底 $($g.textBottom) 超出屏幕 $($g.H)"
  CheckS ($g.textY -ge $g.btnBottom) "设置页声明文字 $($g.textY) 压到 [返回] 按钮底 $($g.btnBottom)"
  CheckS ($g.drawW -le $g.avail) "设置页声明中文缩放后宽 $($g.drawW) > 可用 $($g.avail)"
  CheckS ($g.drawWEn -le $g.avail) "设置页声明英文缩放后宽 $($g.drawWEn) > 可用 $($g.avail)"

  return $script:__badS
}

function Show-SettingsTable($g) {
  Write-Output ""
  Write-Output ("=== 设置页 {0}×{1} ===" -f $g.W, $g.H)
  Write-Output ("  面板 x {0}..{1} / 底 {2}" -f $g.panelL, $g.panelR, $g.panelBottom)
  Write-Output ("  最后一行文字 y {0}..{1}" -f $g.lastRowTextY, $g.lastRowTextBottom)
  Write-Output ("  提示行 y {0}..{1}" -f $g.noticeY, ($g.noticeY + 9))
  Write-Output ("  [返回] y {0}..{1}" -f $g.footerY, $g.btnBottom)
  Write-Output ("  页脚声明条 y {0}..{1}（高 {2}）；文字 y {3}..{4}，{5} 倍字号，估宽 中 {6}px 英 {7}px（可用 {8}px）" -f `
    $g.disclaimY, ($g.disclaimY + $S_DISCLAIM_H), $S_DISCLAIM_H, $g.textY, $g.textBottom, $S_DISCLAIM_SCALE, $g.drawW, $g.drawWEn, $g.avail)
}

function Draw-Settings($g, [string]$outPath) {
  $W = $g.W; $H = $g.H
  $bmp = New-Object System.Drawing.Bitmap($W, $H)
  $gr = [System.Drawing.Graphics]::FromImage($bmp)
  $gr.SmoothingMode = 'AntiAlias'
  $gr.TextRenderingHint = 'ClearTypeGridFit'
  $gr.Clear([System.Drawing.Color]::FromArgb(255, 18, 18, 24))

  $fTitle = New-Object System.Drawing.Font("Microsoft YaHei", 10, [System.Drawing.FontStyle]::Bold)
  $fText  = New-Object System.Drawing.Font("Microsoft YaHei", 8)
  $fSmall = New-Object System.Drawing.Font("Microsoft YaHei", 7)
  $brWhite = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::White)
  $brGray  = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255,138,138,138))
  $brAcc   = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255,180,225,255))
  $brDisclaim = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255,154,160,168))
  $sfC = New-Object System.Drawing.StringFormat
  $sfC.Alignment = 'Center'

  # 面板
  $gr.FillRectangle((New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(192,16,16,20))),
                    [float]$g.panelL, [float]6, [float]($g.panelR - $g.panelL), [float]($g.panelBottom - 6))
  $gr.DrawString("PhiMC 设置", $fTitle, $brWhite, [float]$g.cx, [float]12, $sfC)
  $gr.DrawString("改动立刻保存到 config/phimc/profile.json", $fSmall, $brGray, [float]$g.cx, [float]30, $sfC)

  # 行（左标签 + 右控件，坐标同 SettingsScreen）
  $labelX = $g.panelL + 12
  $rows = @("打击音", "打击音音量", "判定偏移", "（偏移说明）", "材质包", "材质包现状", "respack 目录")
  for ($i = 0; $i -lt $rows.Count; $i++) {
    $ry = $S_TOP + $i * $S_ROW_H
    $gr.DrawString($rows[$i], $(if ($i -eq 3 -or $i -ge 5) { $fSmall } else { $fText }),
                   $(if ($i -eq 3 -or $i -ge 5) { $brGray } else { $brWhite }), [float]$labelX, [float]($ry + 5))
    if ($i -lt 3 -or $i -eq 4) {
      $bx = $g.cx + $(if ($i -eq 1 -or $i -eq 2) { 10 } else { 60 })
      $gr.FillRectangle((New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255,42,42,42))), [float]$bx, [float]$ry, [float]90, [float]$S_BTN_H)
      $gr.DrawRectangle((New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(255,120,140,170))), [float]$bx, [float]$ry, [float]90, [float]$S_BTN_H)
      $gr.DrawString("[控件]", $fSmall, $brWhite, [float]($bx + 45), [float]($ry + 4), $sfC)
    }
  }

  # 提示行 + [返回]
  $gr.DrawString("试听 click（内置）· 音量 80%", $fSmall, $brAcc, [float]$labelX, [float]$g.noticeY)
  $gr.FillRectangle((New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255,42,42,42))), [float]($g.cx - 40), [float]$g.footerY, [float]80, [float]$S_BTN_H)
  $gr.DrawRectangle((New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(255,120,140,170))), [float]($g.cx - 40), [float]$g.footerY, [float]80, [float]$S_BTN_H)
  $gr.DrawString("返回", $fSmall, $brWhite, [float]$g.cx, [float]($g.footerY + 4), $sfC)

  # 页脚声明（0.75 倍字号：预览里用 6pt 字体近似）
  $gr.FillRectangle((New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(102, 0, 0, 0))),
                    [float]0, [float]$g.disclaimY, [float]$W, [float]$S_DISCLAIM_H)
  $fTiny = New-Object System.Drawing.Font("Microsoft YaHei", 6.5)
  $tw = $gr.MeasureString($DISCLAIM_ZH, $fTiny).Width
  $gr.DrawString($DISCLAIM_ZH, $fTiny, $brDisclaim, [float](($W - $tw) / 2), [float]($g.textY - 1))

  # 边界线：蓝=提示行 / 黄=[返回] 顶 / 紫=声明条顶 / 红=面板底
  $gr.DrawLine((New-Object System.Drawing.Pen([System.Drawing.Color]::Gold)), 0, [float]$g.noticeY, [float]$W, [float]$g.noticeY)
  $gr.DrawLine((New-Object System.Drawing.Pen([System.Drawing.Color]::DeepSkyBlue)), 0, [float]$g.footerY, [float]$W, [float]$g.footerY)
  $gr.DrawLine((New-Object System.Drawing.Pen([System.Drawing.Color]::MediumPurple)), 0, [float]$g.disclaimY, [float]$W, [float]$g.disclaimY)

  $bmp.Save($outPath, [System.Drawing.Imaging.ImageFormat]::Png)
  $gr.Dispose(); $bmp.Dispose()
  Write-Output ("  预览: {0}" -f $outPath)
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
    Write-Output "  ✔ 无重叠（页签/详情/筛选行/列表/提示行/底部 6 按钮/拷芯片/信息列/页脚声明/HUD AUTO）"
  }
  Draw-Layout $g (Join-Path $OUT_DIR ("select-layout-{0}x{1}.png" -f $size[0], $size[1]))
  Draw-Hud $g (Join-Path $OUT_DIR ("hud-auto-{0}x{1}.png" -f $size[0], $size[1]))

  # ---- 设置页（SettingsScreen）----
  $s = Get-SettingsGeometry $size[0] $size[1]
  Show-SettingsTable $s
  $errsS = Test-SettingsLayout $s
  if ($errsS.Count -gt 0) {
    $bad += $errsS.Count
    foreach ($e in $errsS) { Write-Output ("  ✗ " + $e) }
  } else {
    Write-Output "  ✔ 无重叠（最后一行/提示行/[返回]/页脚声明条）"
  }
  Draw-Settings $s (Join-Path $OUT_DIR ("settings-layout-{0}x{1}.png" -f $size[0], $size[1]))
}

Write-Output ""
if ($bad -eq 0) { Write-Output "结论: ✔ 两个分辨率都通过" } else { Write-Output "结论: ✗ $bad 处重叠"; exit 1 }
