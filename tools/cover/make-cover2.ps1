$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$work  = "C:\Users\Administrator\Documents\deepseek-harness\默认工作区\mcphigros\docs\cover"
$shot  = Join-Path $work 'src-gameplay.png'
$fish  = Join-Path $work 'src-fish.png'
$out   = Join-Path $work 'phimc-cover-final.png'

$W = 1280; $H = 720
$bmp = New-Object System.Drawing.Bitmap($W, $H, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = 'AntiAlias'
$g.InterpolationMode = 'HighQualityBicubic'
$g.PixelOffsetMode = 'HighQuality'
$g.TextRenderingHint = 'AntiAliasGridFit'

# ---------- 背景：真实打歌截图，铺满 ----------
$bg = [System.Drawing.Image]::FromFile($shot)
$sc = [Math]::Max($W / $bg.Width, $H / $bg.Height)
$bw = [int]($bg.Width * $sc); $bh = [int]($bg.Height * $sc)
$g.DrawImage($bg, [int](($W - $bw) / 2), [int](($H - $bh) / 2), $bw, $bh)
$bg.Dispose()

# 整体压暗
$b = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(30, 0, 0, 0))
$g.FillRectangle($b, 0, 0, $W, $H); $b.Dispose()

# 左侧深色渐变（给标题留出对比）
$rect = New-Object System.Drawing.Rectangle(0, 0, $W, $H)
$c1 = [System.Drawing.Color]::FromArgb(232, 8, 10, 20)
$c2 = [System.Drawing.Color]::FromArgb(0, 8, 10, 20)
$lg = New-Object System.Drawing.Drawing2D.LinearGradientBrush($rect, $c1, $c2, 0.0)
$cb = New-Object System.Drawing.Drawing2D.ColorBlend
$cb.Colors = @($c1, [System.Drawing.Color]::FromArgb(190, 8, 10, 20), $c2)
$cb.Positions = @(0.0, 0.42, 1.0)
$lg.InterpolationColors = $cb
$g.FillRectangle($lg, 0, 0, $W, $H); $lg.Dispose()

# ---------- 大肥鱼：抠白底 ----------
$fs   = [System.Drawing.Bitmap]::FromFile($fish)
$fw   = [int]$fs.Width; $fh = [int]$fs.Height
$cut  = New-Object System.Drawing.Bitmap($fw, $fh, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
for ($y = 0; $y -lt $fh; $y++) { for ($x = 0; $x -lt $fw; $x++) { $cut.SetPixel($x, $y, $fs.GetPixel($x, $y)) } }
$fs.Dispose()

$mask = [bool[,]]::new($fw, $fh)
$q = New-Object System.Collections.Generic.Queue[object]
for ($i = 0; $i -lt $fw; $i++) { $q.Enqueue([int[]]@($i, 0)); $q.Enqueue([int[]]@($i, ($fh - 1))) }
for ($i = 0; $i -lt $fh; $i++) { $q.Enqueue([int[]]@(0, $i)); $q.Enqueue([int[]]@(($fw - 1), $i)) }
while ($q.Count -gt 0) {
    $p  = $q.Dequeue(); $px = [int]$p[0]; $py = [int]$p[1]
    if ($px -lt 0 -or $py -lt 0 -or $px -ge $fw -or $py -ge $fh) { continue }
    if ($mask[$px, $py]) { continue }
    $c = $cut.GetPixel($px, $py)
    if ($c.R -lt 238 -or $c.G -lt 238 -or $c.B -lt 238) { continue }
    $mask[$px, $py] = $true
    $q.Enqueue([int[]]@(($px + 1), $py)); $q.Enqueue([int[]]@(($px - 1), $py))
    $q.Enqueue([int[]]@($px, ($py + 1))); $q.Enqueue([int[]]@($px, ($py - 1)))
}
for ($y = 0; $y -lt $fh; $y++) { for ($x = 0; $x -lt $fw; $x++) { if ($mask[$x, $y]) { $cut.SetPixel($x, $y, [System.Drawing.Color]::FromArgb(0, 0, 0, 0)) } } }

$edgeMask = [bool[,]]::new($fw, $fh)
for ($y = 1; $y -lt $fh - 1; $y++) {
  for ($x = 1; $x -lt $fw - 1; $x++) {
    if ($mask[$x, $y]) { continue }
    if ($mask[($x-1),$y] -or $mask[($x+1),$y] -or $mask[$x,($y-1)] -or $mask[$x,($y+1)]) { $edgeMask[$x, $y] = $true }
  }
}
for ($y = 0; $y -lt $fh; $y++) {
  for ($x = 0; $x -lt $fw; $x++) {
    if ($edgeMask[$x, $y]) { $cc = $cut.GetPixel($x, $y); $cut.SetPixel($x, $y, [System.Drawing.Color]::FromArgb(150, $cc.R, $cc.G, $cc.B)) }
  }
}
# 左边缘羽化，消除生硬竖切
$fadeW = 110
for ($y = 0; $y -lt $fh; $y++) {
  for ($x = 0; $x -lt $fadeW; $x++) {
    $c2 = $cut.GetPixel($x, $y)
    if ($c2.A -eq 0) { continue }
    $k = $x / [double]$fadeW
    $na = [int]($c2.A * $k * $k)
    $cut.SetPixel($x, $y, [System.Drawing.Color]::FromArgb($na, $c2.R, $c2.G, $c2.B))
  }
}
$fishH = 640
$fishW = [int]($fw * ($fishH / $fh))
$fx = $W - $fishW - 10
$fy = $H - $fishH + 8
$sh = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(80, 0, 0, 0))
$g.FillEllipse($sh, $fx + 90, ($fy + $fishH - 64), ($fishW - 180), 56); $sh.Dispose()
$g.DrawImage($cut, $fx, $fy, $fishW, $fishH)
$cut.Dispose()

# ---------- 文字 ----------
function Draw-Stroke {
    param($g, [string]$text, [float]$x, [float]$y, [string]$family, [float]$size, $fill, $stroke, [float]$sw)
    $fam  = [System.Drawing.FontFamily]::new($family)
    $gp   = New-Object System.Drawing.Drawing2D.GraphicsPath
    $fmt  = [System.Drawing.StringFormat]::GenericTypographic
    $pt   = New-Object System.Drawing.PointF($x, $y)
    $gp.AddString($text, $fam, [int][System.Drawing.FontStyle]::Bold, $size, $pt, $fmt)
    $pen  = New-Object System.Drawing.Pen($stroke, $sw)
    $pen.LineJoin = [System.Drawing.Drawing2D.LineJoin]::Round
    $g.DrawPath($pen, $gp)
    $br   = New-Object System.Drawing.SolidBrush($fill)
    $g.FillPath($br, $gp)
    $br.Dispose(); $pen.Dispose(); $gp.Dispose(); $fam.Dispose()
}

$white  = [System.Drawing.Color]::White
$cyan   = [System.Drawing.Color]::FromArgb(255, 96, 214, 255)
$pink   = [System.Drawing.Color]::FromArgb(255, 255, 130, 190)
$stroke = [System.Drawing.Color]::FromArgb(255, 10, 12, 22)

Draw-Stroke $g "在 MC 里玩" 56 120 "Microsoft YaHei" 112 $white $stroke 13
Draw-Stroke $g "Phigros？"   50 258 "Microsoft YaHei" 162 $cyan  $stroke 15

# 副标题
Draw-Stroke $g "19 个版本 · 一键安装 · 自制谱" 60 492 "Microsoft YaHei" 36 ([System.Drawing.Color]::FromArgb(240,232,238,248)) $stroke 6

# 左下角署名
$fnt = New-Object System.Drawing.Font("Microsoft YaHei", 26, [System.Drawing.FontStyle]::Bold)
$br2 = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(220,255,255,255))
$g.DrawString("PhiMC", $fnt, $br2, 64, 592)
$br2.Dispose(); $fnt.Dispose()

$g.Dispose()
$bmp.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
"OK: $out"
"size: " + [math]::Round((Get-Item $out).Length/1KB) + " KB"
