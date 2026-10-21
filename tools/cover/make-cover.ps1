$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$shots = "C:\dsh-work\rpeprobe\shots"
$shot  = "C:\Users\Administrator\.dsh\attachments\v1\objects\90\902d926cb21bd88121f1e149f2f666fcebb547ba9156c0e47cf80d257b1a5ecf"
$fish  = "C:\Users\Administrator\.dsh\attachments\v1\objects\b4\b4a18d5d4d0c62db0fe2a22ae16f32f68de4cd649eaaaad0313397065efbade2"
$out   = "C:\Users\Administrator\Documents\deepseek-harness\默认工作区\mcphigros\docs\cover\cover-1280x720.png"
New-Item -ItemType Directory -Force -Path (Split-Path $out) | Out-Null

$W = 1280; $H = 720
$bmp = New-Object System.Drawing.Bitmap($W, $H, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = 'AntiAlias'
$g.InterpolationMode = 'HighQualityBicubic'
$g.TextRenderingHint = 'AntiAliasGridFit'

# ---------- 1. 背景：谱面截图，按比例裁切铺满 ----------
$bg = [System.Drawing.Image]::FromFile($shot)
$scale = [Math]::Max($W / $bg.Width, $H / $bg.Height)
$bw = [int]($bg.Width * $scale); $bh = [int]($bg.Height * $scale)
$bx = [int](($W - $bw) / 2); $by = [int](($H - $bh) / 2)
$g.DrawImage($bg, $bx, $by, $bw, $bh)
$bg.Dispose()

# 压暗 + 左侧渐变，让文字清楚
$dark = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(90, 0, 0, 0))
$g.FillRectangle($dark, 0, 0, $W, $H)
$dark.Dispose()

$rect = New-Object System.Drawing.Rectangle(0, 0, $W, $H)
$grad = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
    $rect,
    [System.Drawing.Color]::FromArgb(235, 8, 10, 22),
    [System.Drawing.Color]::FromArgb(0, 8, 10, 22),
    [System.Drawing.Drawing2D.LinearGradientMode]::Horizontal)
$grad.Dispose()

# 左→右渐变（左黑右透）
$c1 = [System.Drawing.Color]::FromArgb(240, 10, 12, 24)
$c2 = [System.Drawing.Color]::FromArgb(30, 10, 12, 24)
$lg = New-Object System.Drawing.Drawing2D.LinearGradientBrush($rect, $c1, $c2, 0.0)
$blend = New-Object System.Drawing.Drawing2D.ColorBlend
$blend.Colors = @($c1, [System.Drawing.Color]::FromArgb(200,10,12,24), $c2)
$blend.Positions = @(0.0, 0.45, 1.0)
$lg.InterpolationColors = $blend
$g.FillRectangle($lg, 0, 0, $W, $H)
$lg.Dispose()

# ---------- 2. 大肥鱼：去掉纯白背景 ----------
$fs = [System.Drawing.Bitmap]::FromFile($fish)
$fw = [int]$fs.Width; $fh = [int]$fs.Height
$cut = New-Object System.Drawing.Bitmap($fw, $fh, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
for ($y = 0; $y -lt $fh; $y++) {
    for ($x = 0; $x -lt $fw; $x++) { $cut.SetPixel($x, $y, $fs.GetPixel($x, $y)) }
}

$mask = [bool[,]]::new($fw, $fh)
$q = New-Object System.Collections.Generic.Queue[object]
$edge = [int]([Math]::Max($fw, $fh))
for ($i = 0; $i -lt $fw; $i++) { $q.Enqueue([int[]]@($i, 0)); $q.Enqueue([int[]]@($i, ($fh - 1))) }
for ($i = 0; $i -lt $fh; $i++) { $q.Enqueue([int[]]@(0, $i)); $q.Enqueue([int[]]@(($fw - 1), $i)) }
while ($q.Count -gt 0) {
    $p = $q.Dequeue()
    $px = [int]$p[0]; $py = [int]$p[1]
    if ($px -lt 0 -or $py -lt 0 -or $px -ge $fw -or $py -ge $fh) { continue }
    if ($mask[$px, $py]) { continue }
    $c = $cut.GetPixel($px, $py)
    if ($c.R -lt 240 -or $c.G -lt 240 -or $c.B -lt 240) { continue }
    $mask[$px, $py] = $true
    $q.Enqueue([int[]]@(($px + 1), $py)); $q.Enqueue([int[]]@(($px - 1), $py))
    $q.Enqueue([int[]]@($px, ($py + 1))); $q.Enqueue([int[]]@($px, ($py - 1)))
}
for ($y = 0; $y -lt $fh; $y++) {
    for ($x = 0; $x -lt $fw; $x++) {
        if ($mask[$x, $y]) { $cut.SetPixel($x, $y, [System.Drawing.Color]::FromArgb(0, 0, 0, 0)) }
    }
}

# 右侧放大 + 投影
$fishH = 560
$fishW = [int]($fw * ($fishH / $fh))
$fx = [int]($W - $fishW + 40); $fy = [int]($H - $fishH + 10)
$shadow = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(70, 0, 0, 0))
$g.FillEllipse($shadow, $fx + 60, ($fy + $fishH - 70), ($fishW - 120), 70)
$g.DrawImage($cut, $fx, $fy, $fishW, $fishH)
$cut.Dispose(); $fs.Dispose(); $shadow.Dispose()

# ---------- 3. 文字 ----------
function Draw-TextWithStroke {
    param($g, $text, $x, $y, $family, $size, $fillColor, $strokeColor, $strokeWidth)
    $gp = New-Object System.Drawing.Drawing2D.GraphicsPath
    $fmt = [System.Drawing.StringFormat]::GenericTypographic
    $gp.AddString($text, (New-Object System.Drawing.FontFamily($family)),
        [int][System.Drawing.FontStyle]::Bold, $size,
        (New-Object System.Drawing.PointF($x, $y)), $fmt)
    $pen = New-Object System.Drawing.Pen($strokeColor, $strokeWidth)
    $pen.LineJoin = 'Round'
    $g.DrawPath($pen, $gp)
    $br = New-Object System.Drawing.SolidBrush($fillColor)
    $g.FillPath($br, $gp)
    $br.Dispose(); $pen.Dispose(); $gp.Dispose()
}

$white = [System.Drawing.Color]::White
$stroke = [System.Drawing.Color]::FromArgb(255, 12, 14, 26)
$accent = [System.Drawing.Color]::FromArgb(255, 108, 216, 255)

Draw-TextWithStroke $g "在 MC 里玩" 62 150 "Microsoft YaHei" 116 $white $stroke 12
Draw-TextWithStroke $g "Phigros？" 56 292 "Microsoft YaHei" 168 $accent $stroke 14

# 副标题
Draw-TextWithStroke $g "19 个版本 · 自制谱 · 一键安装" 66 500 "Microsoft YaHei" 42 ([System.Drawing.Color]::FromArgb(235,230,235,245)) $stroke 7

# 右下角标签
$tagFont = New-Object System.Drawing.Font("Microsoft YaHei", 30, [System.Drawing.FontStyle]::Bold)
$tagBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(235,255,255,255))
$g.DrawString("PhiMC", $tagFont, $tagBrush, 66, 600)
$tagBrush.Dispose(); $tagFont.Dispose()

$g.Dispose()
$bmp.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
"已生成: $out"
(Get-Item $out).Length
