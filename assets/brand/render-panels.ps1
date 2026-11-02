# Renders EdgeFit GitHub presentation panels from the authentic captures.
# Originals under assets/screenshots/2026-11-01/ are NEVER modified;
# outputs go to assets/screenshots/presentation/.
# Per-shot transform: uniform status-bar + nav-bar crop (system UI only,
# no app pixels touched), aspect-preserving scale into a 1600x900 Aero
# card (abyss/deep gradient, azure glow, rounded phone frame, title).
# Usage: powershell -File render-panels.ps1 (run from repo root)
Add-Type -AssemblyName System.Drawing

$Abyss  = [System.Drawing.ColorTranslator]::FromHtml("#071D3B")
$Deep   = [System.Drawing.ColorTranslator]::FromHtml("#081427")
$Azure  = [System.Drawing.ColorTranslator]::FromHtml("#1265F5")
$Sky    = [System.Drawing.ColorTranslator]::FromHtml("#49BBFF")
$Violet = [System.Drawing.ColorTranslator]::FromHtml("#8054F8")
$Mist   = [System.Drawing.ColorTranslator]::FromHtml("#F3F7FE")
$SecTx  = [System.Drawing.ColorTranslator]::FromHtml("#A9BCD8")
$Disab  = [System.Drawing.ColorTranslator]::FromHtml("#5F7191")
$DockBd = [System.Drawing.ColorTranslator]::FromHtml("#2A4A7A")

$Shots = @(
  @("edgefit-ss-01-home-overview.png", "edgefit-panel-01-home.png",
    "Home Dashboard",
    "Live device telemetry, staged model, and next action in one view."),
  @("edgefit-ss-02-device-profile.png", "edgefit-panel-02-device.png",
    "Device Profile",
    "Measured hardware: CPU, RAM, thermal state, Android level."),
  @("edgefit-ss-04-model-recommendations.png", "edgefit-panel-04-recommend.png",
    "Model Recommendations",
    "Curated GGUF candidates with explicit unverified estimates."),
  @("edgefit-ss-06-model-catalog.png", "edgefit-panel-06-catalog.png",
    "Model Catalog",
    "Licensed candidates with sizes, sources, and suitability notes."),
  @("edgefit-ss-09-benchmark-running.png", "edgefit-panel-09-running.png",
    "Live Benchmark",
    "Real llama.cpp inference with provisional throughput and chart."),
  @("edgefit-ss-10-benchmark-results.png", "edgefit-panel-10-results.png",
    "Measured Results",
    "Completed 64/64 run: throughput, timings, and conditions."),
  @("edgefit-ss-12-session-history.png", "edgefit-panel-12-sessions.png",
    "Session History",
    "Durable prediction-to-measurement records with deltas."),
  @("edgefit-ss-13-muse-spark-prediction.png", "edgefit-panel-13-predict.png",
    "Muse Spark Prediction",
    "Throughput estimate with confidence, rationale, and evidence."),
  @("edgefit-ss-14-muse-prediction-vs-measurement.png", "edgefit-panel-14-compare.png",
    "Prediction vs Measured",
    "Host-computed error: generation and prefill deltas."),
  @("edgefit-ss-22-agent-proposal-review.png", "edgefit-panel-22-agent.png",
    "Agent Proposal Review",
    "Independent host review with explicit approve or reject."),
  @("edgefit-ss-25-sessions-repeat-series-compare.png", "edgefit-panel-25-repeat.png",
    "Repeat-Series Compare",
    "Same-config repeats with reference deltas.")
)

# System-UI crop (measured on 1080x2316 captures): status bar 0..96,
# gesture nav bar 2156..2316. App pixels untouched.
$CropTop = 96; $CropBottom = 160

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

function New-FitFont($g, $text, $family, $startPx, $style, $maxW) {
    $px = $startPx
    while ($px -gt 8) {
        $f = New-Object System.Drawing.Font($family, $px, $style)
        if ($g.MeasureString($text, $f).Width -le $maxW) { return $f }
        $f.Dispose(); $px -= 2
    }
    return (New-Object System.Drawing.Font($family, 8, $style))
}

function Split-Lines($g, $text, $font, $maxW) {
    $words = $text -split ' '; $lines = @(); $cur = ''
    foreach ($w in $words) {
        $try = if ($cur -eq '') { $w } else { "$cur $w" }
        if ($g.MeasureString($try, $font).Width -le $maxW) { $cur = $try }
        else { $lines += $cur; $cur = $w }
    }
    if ($cur -ne '') { $lines += $cur }
    return $lines
}

$SrcDir = 'assets/screenshots/2026-11-01'
$DstDir = 'assets/screenshots/presentation'
New-Item -ItemType Directory -Force -Path $DstDir | Out-Null

foreach ($s in $Shots) {
    $src, $dst, $title, $desc = $s
    $W = 1600; $H = 900
    $bmp = New-Object System.Drawing.Bitmap($W, $H)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.InterpolationMode =
        [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    # Background wash.
    $wash = [System.Drawing.Drawing2D.LinearGradientBrush]::new(
        [System.Drawing.PointF]::new(0, 0),
        [System.Drawing.PointF]::new(0, $H), $Abyss, $Deep)
    $g.FillRectangle($wash, 0, 0, $W, $H)
    $wash.Dispose()
    # Ambient azure glow behind the phone seat.
    for ($i = 30; $i -ge 1; $i--) {
        $f = $i / 30.0
        $a = [int](60 * (1 - $f) * (1 - $f))
        if ($a -lt 1) { continue }
        $c = [System.Drawing.Color]::FromArgb($a, $Azure.R, $Azure.G, $Azure.B)
        $b = New-Object System.Drawing.SolidBrush($c)
        $r = 400 * $f
        $g.FillEllipse($b, 400 - $r, 450 - $r, 2 * $r, 2 * $r)
        $b.Dispose()
    }
    # Phone image: crop system UI, scale to fit 780px height.
    $shot = [System.Drawing.Bitmap]::FromFile(
        (Join-Path $SrcDir $src))
    $cw = $shot.Width
    $ch = $shot.Height - $CropTop - $CropBottom
    $ph = 780
    $pw = [int]($ph * $cw / $ch)
    $px = 180; $py = 60
    $clip = New-RoundedPath $px $py $pw $ph 44
    $state = $g.Save()
    $g.SetClip($clip)
    $g.DrawImage($shot,
        [System.Drawing.Rectangle]::new($px, $py, $pw, $ph),
        0, $CropTop, $cw, $ch,
        [System.Drawing.GraphicsUnit]::Pixel)
    $g.Restore($state)
    $pen = New-Object System.Drawing.Pen($DockBd, 3)
    $g.DrawPath($pen, $clip)
    $clip.Dispose(); $pen.Dispose(); $shot.Dispose()
    # Title (auto-fit) + gradient rule + description.
    $tx = 700
    $fTitle = New-FitFont $g $title "Arial" 56 `
        ([System.Drawing.FontStyle]::Bold) 800
    $bMist = New-Object System.Drawing.SolidBrush($Mist)
    $g.DrawString($title, $fTitle, $bMist, $tx, 290)
    $rule = [System.Drawing.Drawing2D.LinearGradientBrush]::new(
        [System.Drawing.PointF]::new($tx, 0),
        [System.Drawing.PointF]::new($tx + 420, 0), $Sky, $Violet)
    $rb = New-Object System.Drawing.SolidBrush($Sky)
    $g.FillRectangle($rule, $tx, 372, 420, 6)
    $rule.Dispose(); $rb.Dispose()
    $fDesc = New-Object System.Drawing.Font("Arial", 30)
    $bSec = New-Object System.Drawing.SolidBrush($SecTx)
    $y = 410
    foreach ($ln in (Split-Lines $g $desc $fDesc 800)) {
        $g.DrawString($ln, $fDesc, $bSec, $tx, $y)
        $y += 48
    }
    $tagText = "PRESENTATION CROP - UNALTERED ORIGINAL LINKED BELOW"
    $fTag = New-FitFont $g $tagText "Arial" 22 `
        ([System.Drawing.FontStyle]::Regular) 800
    $bDis = New-Object System.Drawing.SolidBrush($Disab)
    $g.DrawString($tagText,
        $fTag, $bDis, $tx, $y + 24)
    $fTitle.Dispose(); $fDesc.Dispose(); $fTag.Dispose()
    $bMist.Dispose(); $bSec.Dispose(); $bDis.Dispose()
    $g.Dispose()
    $bmp.Save((Join-Path $DstDir $dst),
        [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Write-Output "$dst done"
}
