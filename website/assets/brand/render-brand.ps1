# Renders EdgeFit Aero brand derivatives from the master icon geometry.
# Geometry + palette are byte-faithful to
# android/app/src/main/assets/aero/icon (PROVENANCE.md, render-icon.ps1):
# 108-unit canvas, abyss #071D3B -> deep #081427 wash, halo, sky->azure
# gradient bars, violet #8054F8 mid-bar, mist #F3F7FE counter dot.
# Usage: powershell -File render-brand.ps1 [-OutDir <dir>]
param([string]$OutDir = $PSScriptRoot)
Add-Type -AssemblyName System.Drawing

$Abyss  = [System.Drawing.ColorTranslator]::FromHtml("#071D3B")
$Deep   = [System.Drawing.ColorTranslator]::FromHtml("#081427")
$Azure  = [System.Drawing.ColorTranslator]::FromHtml("#1265F5")
$Sky    = [System.Drawing.ColorTranslator]::FromHtml("#49BBFF")
$Violet = [System.Drawing.ColorTranslator]::FromHtml("#8054F8")
$Mist   = [System.Drawing.ColorTranslator]::FromHtml("#F3F7FE")
$SecTx  = [System.Drawing.ColorTranslator]::FromHtml("#A9BCD8")

function Fill-Stadium($g, $brush, $x, $y, $w, $h) {
    if ($w -ge $h) {
        $r = $h / 2
        $g.FillRectangle($brush, $x + $r, $y, $w - $h, $h)
        $g.FillEllipse($brush, $x, $y, $h, $h)
        $g.FillEllipse($brush, $x + $w - $h, $y, $h, $h)
    } else {
        $r = $w / 2
        $g.FillRectangle($brush, $x, $y + $r, $w, $h - $w)
        $g.FillEllipse($brush, $x, $y, $w, $w)
        $g.FillEllipse($brush, $x, $y + $h - $w, $w, $w)
    }
}

function New-IconSquare($px) {
    $bmp = New-Object System.Drawing.Bitmap($px, $px)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $s = $px / 108.0
    $bg = [System.Drawing.Drawing2D.LinearGradientBrush]::new(
        [System.Drawing.PointF]::new(0, 0),
        [System.Drawing.PointF]::new(0, $px), $Abyss, $Deep)
    $g.FillRectangle($bg, 0, 0, $px, $px)
    $bg.Dispose()
    $cx = 54 * $s; $cy = 54 * $s; $R = 34 * $s
    for ($i = 40; $i -ge 1; $i--) {
        $f = $i / 40.0
        $a = [int](110 * (1 - $f) * (1 - $f) + 6 * (1 - $f))
        if ($a -lt 1) { continue }
        $c = [System.Drawing.Color]::FromArgb($a, $Azure.R, $Azure.G, $Azure.B)
        $b = New-Object System.Drawing.SolidBrush($c)
        $r = $R * $f
        $g.FillEllipse($b, $cx - $r, $cy - $r, 2 * $r, 2 * $r)
        $b.Dispose()
    }
    $bar = [System.Drawing.Drawing2D.LinearGradientBrush]::new(
        [System.Drawing.PointF]::new(0, [float](33 * $s)),
        [System.Drawing.PointF]::new(0, [float](76 * $s)), $Sky, $Azure)
    Fill-Stadium $g $bar (35*$s) (33*$s) (10*$s) (43*$s)
    Fill-Stadium $g $bar (35*$s) (33*$s) (38*$s) (10*$s)
    Fill-Stadium $g $bar (35*$s) (66*$s) (38*$s) (10*$s)
    $bar.Dispose()
    $vb = New-Object System.Drawing.SolidBrush($Violet)
    Fill-Stadium $g $vb (45*$s) (48*$s) (20*$s) (10*$s)
    $vb.Dispose()
    $mb = New-Object System.Drawing.SolidBrush($Mist)
    $g.FillEllipse($mb, 55*$s, 59*$s, 6*$s, 6*$s)
    $mb.Dispose()
    $g.Dispose()
    return $bmp
}

function Save-Png($bmp, $path) {
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
}

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

# Favicons + touch icons (4x supersampled, bicubic downscale).
foreach ($spec in @(@("favicon-16.png",16),@("favicon-32.png",32),
    @("apple-touch-icon.png",180),@("icon-192.png",192),
    @("icon-512.png",512),@("icon-64.png",64))) {
    $name, $px = $spec
    $big = New-IconSquare ($px * 4)
    $dst = New-Object System.Drawing.Bitmap($px, $px)
    $g = [System.Drawing.Graphics]::FromImage($dst)
    $g.InterpolationMode =
        [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.DrawImage($big, 0, 0, $px, $px)
    $g.Dispose(); $big.Dispose()
    Save-Png $dst (Join-Path $OutDir $name)
    Write-Output "$name done"
}

# Wide hero banner 1600x800: gradient wash + glow + icon + lockup.
$W = 1600; $H = 800
$hero = New-Object System.Drawing.Bitmap($W, $H)
$hg = [System.Drawing.Graphics]::FromImage($hero)
$hg.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$wash = [System.Drawing.Drawing2D.LinearGradientBrush]::new(
    [System.Drawing.PointF]::new(0, 0),
    [System.Drawing.PointF]::new(0, $H), $Abyss, $Deep)
$hg.FillRectangle($wash, 0, 0, $W, $H)
$wash.Dispose()
# Soft azure glow, upper-left of the icon seat.
for ($i = 30; $i -ge 1; $i--) {
    $f = $i / 30.0
    $a = [int](70 * (1 - $f) * (1 - $f))
    if ($a -lt 1) { continue }
    $c = [System.Drawing.Color]::FromArgb($a, $Azure.R, $Azure.G, $Azure.B)
    $b = New-Object System.Drawing.SolidBrush($c)
    $r = 420 * $f
    $hg.FillEllipse($b, 330 - $r, 400 - $r, 2 * $r, 2 * $r)
    $b.Dispose()
}
$iconPx = 420
$icon = New-IconSquare $iconPx
$hg.DrawImage($icon, 120, 190)
$icon.Dispose()
function Draw-Text($text, $font, $brush, $x, $y) {
    $hg.DrawString($text, $font, $brush, $x, $y)
}
function New-FitFont($text, $family, $startPx, $style, $maxW) {
    $px = $startPx
    while ($px -gt 8) {
        $f = New-Object System.Drawing.Font($family, $px, $style)
        $w = $hg.MeasureString($text, $f).Width
        if ($w -le $maxW) { return $f }
        $f.Dispose()
        $px -= 2
    }
    return (New-Object System.Drawing.Font($family, 8, $style))
}
$fTitle = New-FitFont "EdgeFit AI" "Arial" 104 `
    ([System.Drawing.FontStyle]::Bold) 900
$fSub = New-FitFont "Hardware-Aware AI. Measured on Your Device." `
    "Arial" 40 ([System.Drawing.FontStyle]::Bold) 900
$fTag = New-Object System.Drawing.Font("Arial", 28)
$bMist = New-Object System.Drawing.SolidBrush($Mist)
$bSky = New-Object System.Drawing.SolidBrush($Sky)
$bSec = New-Object System.Drawing.SolidBrush($SecTx)
Draw-Text "EdgeFit AI" $fTitle $bMist 620 200
Draw-Text "Hardware-Aware AI. Measured on Your Device." $fSub $bSky 624 380
Draw-Text "Predict local LLM performance with Meta Muse Spark," $fTag $bSec 626 500
Draw-Text "benchmark real Android hardware, and compare results" $fTag $bSec 626 550
Draw-Text "against verifiable evidence." $fTag $bSec 626 600
$fTitle.Dispose(); $fSub.Dispose(); $fTag.Dispose()
$bMist.Dispose(); $bSky.Dispose(); $bSec.Dispose()
$hg.Dispose()
Save-Png $hero (Join-Path $OutDir "edgefit-hero-1600.png")
Write-Output "edgefit-hero-1600.png done"

# Social card 1200x630: reuse hero composition, tighter crop.
$hero2 = New-Object System.Drawing.Bitmap(1200, 630)
$h2 = [System.Drawing.Graphics]::FromImage($hero2)
$h2.InterpolationMode =
    [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$srcHero = [System.Drawing.Bitmap]::FromFile(
    (Join-Path $OutDir "edgefit-hero-1600.png"))
$h2.DrawImage($srcHero, 0, 0, 1200, 630)
$h2.Dispose(); $srcHero.Dispose()
Save-Png $hero2 (Join-Path $OutDir "edgefit-og-1200.png")
Write-Output "edgefit-og-1200.png done"
