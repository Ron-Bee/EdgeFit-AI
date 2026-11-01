# Renders EdgeFit Aero icon rasters from the master geometry.
# Geometry matches PROVENANCE.md (108-unit adaptive canvas).
# Usage: powershell -File render-icon.ps1
Add-Type -AssemblyName System.Drawing

$Root = $PSScriptRoot
$ResDir = (Resolve-Path (Join-Path $Root '..\..\..\res')).Path

$Abyss   = [System.Drawing.ColorTranslator]::FromHtml("#071D3B")
$Deep    = [System.Drawing.ColorTranslator]::FromHtml("#081427")
$Azure   = [System.Drawing.ColorTranslator]::FromHtml("#1265F5")
$Sky     = [System.Drawing.ColorTranslator]::FromHtml("#49BBFF")
$Violet  = [System.Drawing.ColorTranslator]::FromHtml("#8054F8")
$Mist    = [System.Drawing.ColorTranslator]::FromHtml("#F3F7FE")
$DockBd  = [System.Drawing.ColorTranslator]::FromHtml("#2A4A7A")
$SecText = [System.Drawing.ColorTranslator]::FromHtml("#A9BCD8")

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
    # Background wash (abyss top -> deep bottom).
    $bg = [System.Drawing.Drawing2D.LinearGradientBrush]::new(
        [System.Drawing.PointF]::new(0, 0),
        [System.Drawing.PointF]::new(0, $px), $Abyss, $Deep)
    $g.FillRectangle($bg, 0, 0, $px, $px)
    $bg.Dispose()
    # Soft halo behind the mark (concentric washes).
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
    # Bars: vertical sky->azure gradient over the art band.
    $bar = [System.Drawing.Drawing2D.LinearGradientBrush]::new(
        [System.Drawing.PointF]::new(0, [float](33 * $s)),
        [System.Drawing.PointF]::new(0, [float](76 * $s)), $Sky, $Azure)
    Fill-Stadium $g $bar (35*$s) (33*$s) (10*$s) (43*$s)   # spine
    Fill-Stadium $g $bar (35*$s) (33*$s) (38*$s) (10*$s)   # top
    Fill-Stadium $g $bar (35*$s) (66*$s) (38*$s) (10*$s)   # bottom
    $bar.Dispose()
    $vb = New-Object System.Drawing.SolidBrush($Violet)
    Fill-Stadium $g $vb (45*$s) (48*$s) (20*$s) (10*$s)    # mid bar
    $vb.Dispose()
    $mb = New-Object System.Drawing.SolidBrush($Mist)
    $g.FillEllipse($mb, 55*$s, 59*$s, 6*$s, 6*$s)          # counter dot
    $mb.Dispose()
    $g.Dispose()
    return $bmp
}

function Resize-Bitmap($src, $dstPx) {
    $dst = New-Object System.Drawing.Bitmap($dstPx, $dstPx)
    $g = [System.Drawing.Graphics]::FromImage($dst)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.DrawImage($src, 0, 0, $dstPx, $dstPx)
    $g.Dispose()
    return $dst
}

function New-RoundIcon($square) {
    $px = $square.Width
    $dst = New-Object System.Drawing.Bitmap($px, $px)
    $g = [System.Drawing.Graphics]::FromImage($dst)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $path.AddEllipse(0, 0, $px, $px)
    $g.SetClip($path)
    $g.DrawImage($square, 0, 0)
    $g.Dispose(); $path.Dispose()
    return $dst
}

function New-RoundedPath($x, $y, $w, $h, $r) {
    $p = New-Object System.Drawing.Drawing2D.GraphicsPath
    $d = 2 * $r
    $p.AddArc($x, $y, $d, $d, 180, 90)
    $p.AddArc($x + $w - $d, $y, $d, $d, 270, 90)
    $p.AddArc($x + $w - $d, $y + $h - $d, $d, $d, 0, 90)
    $p.AddArc($x, $y + $h - $d, $d, $d, 90, 90)
    $p.CloseFigure()
    return $p
}

# Legacy mipmap rasters (4x supersampled).
$dims = @{ "mdpi" = 48; "hdpi" = 72; "xhdpi" = 96; "xxhdpi" = 144; "xxxhdpi" = 192 }
foreach ($d in $dims.Keys) {
    $px = $dims[$d]
    $dir = Join-Path $ResDir ("mipmap-" + $d)
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $big = New-IconSquare ($px * 4)
    $sq = Resize-Bitmap $big $px
    $big.Dispose()
    $sq.Save((Join-Path $dir "ic_launcher.png"), [System.Drawing.Imaging.ImageFormat]::Png)
    $rd = New-RoundIcon $sq
    $rd.Save((Join-Path $dir "ic_launcher_round.png"), [System.Drawing.Imaging.ImageFormat]::Png)
    $sq.Dispose(); $rd.Dispose()
    Write-Output ("mipmap-" + $d + " " + $px + "px done")
}

# GitHub square export (1024, full bleed).
$gh = New-IconSquare 1024
$ghDir = Join-Path $Root "export\github"
New-Item -ItemType Directory -Force -Path $ghDir | Out-Null
$gh.Save((Join-Path $ghDir "edgefit-aero-1024.png"), [System.Drawing.Imaging.ImageFormat]::Png)
$gh.Dispose()
Write-Output "github 1024 done"

# Presentation export (2048 canvas, rounded icon + lockup).
$P = 2048
$pres = New-Object System.Drawing.Bitmap($P, $P)
$pg = [System.Drawing.Graphics]::FromImage($pres)
$pg.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$pg.Clear($Deep)
$iconPx = 1344
$ix = ($P - $iconPx) / 2
$iy = 180
$icon = New-IconSquare $iconPx
$clip = New-RoundedPath $ix $iy $iconPx $iconPx 300
$pg.SetClip($clip)
$pg.DrawImage($icon, $ix, $iy)
$pg.ResetClip()
$pen = New-Object System.Drawing.Pen($DockBd, 4)
$pg.DrawPath($pen, $clip)
$clip.Dispose(); $pen.Dispose(); $icon.Dispose()
function Draw-Centered($text, $font, $brush, $y) {
    $sz = $pg.MeasureString($text, $font)
    $pg.DrawString($text, $font, $brush, ($P - $sz.Width) / 2, $y)
}
$f1 = New-Object System.Drawing.Font("Arial", 130, [System.Drawing.FontStyle]::Bold)
$f2 = New-Object System.Drawing.Font("Arial", 52)
$b1 = New-Object System.Drawing.SolidBrush($Mist)
$b2 = New-Object System.Drawing.SolidBrush($SecText)
Draw-Centered "EDGEFIT" $f1 $b1 1590
Draw-Centered "MEASURE WHAT MATTERS" $f2 $b2 1810
$f1.Dispose(); $f2.Dispose(); $b1.Dispose(); $b2.Dispose()
$pg.Dispose()
$prDir = Join-Path $Root "export\presentation"
New-Item -ItemType Directory -Force -Path $prDir | Out-Null
$pres.Save((Join-Path $prDir "edgefit-aero-presentation-2048.png"), [System.Drawing.Imaging.ImageFormat]::Png)
$pres.Dispose()
Write-Output "presentation 2048 done"
