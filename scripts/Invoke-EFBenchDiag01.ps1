<# EF-BENCH-DIAG-01: host-side benchmark stall evidence capture.
Runs on the owner's Windows host (Muse sandbox cannot reach ADB).
READ-ONLY against the device: no install/uninstall/clear/kill-server/logcat -c.
The owner taps Run/Cancel on the phone at the printed checkpoints.

Usage:
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Invoke-EFBenchDiag01.ps1 -Serial R5CT604K33V
#>
param(
    [string]$Serial = 'R5CT604K33V',
    [string]$ExpectedModel = 'SM-S908U',
    [string]$Package = 'ai.edgefit.diag',
    [string]$OutRoot = 'C:\Users\Ron\EdgeFit-Backups',
    [int]$SampleSecs = 5,
    [int]$MaxMinutes = 10,
    [string]$AdbPath = ''
)

$ErrorActionPreference = 'Stop'

function Fail($msg) { Write-Host "DIAG BLOCKED -- $msg"; exit 1 }
function Note($msg) { Write-Host "DIAG: $msg" }

function Resolve-Adb($hint) {
    $cands = @()
    if ($hint -ne '') { $cands += $hint }
    if ($env:LOCALAPPDATA -ne $null) {
        $cands += (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe')
    }
    if ($env:ANDROID_HOME -ne $null) {
        $cands += (Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe')
    }
    if ($env:ANDROID_SDK_ROOT -ne $null) {
        $cands += (Join-Path $env:ANDROID_SDK_ROOT 'platform-tools\adb.exe')
    }
    $onPath = (Get-Command adb.exe -ErrorAction SilentlyContinue)
    if ($onPath -ne $null) { $cands += $onPath.Source }
    foreach ($c in $cands) {
        if ($c -eq $null -or $c -eq '') { continue }
        # Never use the ancient system adb; it fights the shared server.
        if ($c.ToLower().StartsWith('c:\windows')) { continue }
        if (Test-Path $c) { return $c }
    }
    return $null
}

$adb = Resolve-Adb $AdbPath
if ($adb -eq $null) { Fail 'no usable SDK adb.exe found (checked LOCALAPPDATA SDK, ANDROID_HOME, PATH)' }
Note "adb: $adb"

# 1. Device identity (bounded, read-only).
$devLine = (& $adb devices -l 2>&1) | Where-Object { $_ -match $Serial } | Select-Object -First 1
if ($devLine -eq $null -or $devLine -notmatch '\sdevice(\s|$)') {
    Fail "device $Serial not listed as 'device' (unauthorized/offline/missing). Got: $devLine"
}
$model = ((& $adb -s $Serial shell getprop ro.product.model 2>&1) | Select-Object -First 1).Trim()
$normModel = $model.ToUpper() -replace '[-_]', ''
$normExpected = $ExpectedModel.ToUpper() -replace '[-_]', ''
if ($normModel -ne $normExpected) { Fail "model mismatch: got '$model', expected '$ExpectedModel'" }
$android = ((& $adb -s $Serial shell getprop ro.build.version.release 2>&1) | Select-Object -First 1).Trim()
Note "device: $Serial model $model android $android"

# 2. Package identity (read-only).
$dump = (& $adb -s $Serial shell dumpsys package $Package 2>&1) | Out-String
if ($dump -notmatch 'versionCode=(\d+)') { Fail "package $Package not installed or dumpsys failed" }
$verCode = $Matches[1]
$verName = ''
if ($dump -match 'versionName=([^\s]+)') { $verName = $Matches[1] }
Note "package: $Package v$verName ($verCode)"

# 3. Evidence directory.
$stamp = (Get-Date).ToUniversalTime().ToString('yyyyMMdd-HHmmss') + 'Z'
$ev = Join-Path $OutRoot "EF-BENCH-DIAG-01-$stamp"
New-Item -ItemType Directory $ev | Out-Null
Note "evidence: $ev"
"device=$Serial model=$model android=$android package=$Package v$verName ($verCode) stamp=$stamp" |
    Out-File (Join-Path $ev 'notes.txt') -Encoding ascii

# 4. Pre-capture fatal snapshot (bounded dump; buffer NOT cleared).
& $adb -s $Serial logcat -d -t 200 '*:E' > (Join-Path $ev 'pre-fatals.txt') 2>&1

# 5. Streaming logcat (owned process; killed at end).
$lcPath = Join-Path $ev 'logcat.txt'
$lc = Start-Process $adb -ArgumentList "-s $Serial logcat -v threadtime" `
    -RedirectStandardOutput $lcPath -NoNewWindow -PassThru
Note 'logcat streaming started'

# 6. Sampler job (owned; stops on stop-file or deadline).
$stopFile = Join-Path $ev 'STOP'
$samplesPath = Join-Path $ev 'samples.txt'
$deadline = (Get-Date).AddMinutes($MaxMinutes)
$job = Start-Job -ScriptBlock {
    param($adbExe, $ser, $pkg, $out, $stop, $end, $every)
    "sampler start $(Get-Date -Format o)" | Out-File $out -Encoding ascii
    $n = 0
    while ((Get-Date) -lt $end -and -not (Test-Path $stop)) {
        $n++
        $pid = ((& $adbExe -s $ser shell pidof $pkg 2>$null) | Select-Object -First 1)
        if ($pid -ne $null -and "$pid".Trim() -ne '') {
            "-- sample $n $(Get-Date -Format o) pid $($pid.Trim())" | Out-File $out -Append -Encoding ascii
            & $adbExe -s $ser shell "ps -T -p $($pid.Trim())" 2>&1 |
                Out-File $out -Append -Encoding ascii
            & $adbExe -s $ser shell dumpsys meminfo $pkg 2>&1 |
                Select-String 'TOTAL PSS|Native Heap|Dalvik Heap' |
                Out-File $out -Append -Encoding ascii
        } else {
            "-- sample $n $(Get-Date -Format o) pid (none)" | Out-File $out -Append -Encoding ascii
        }
        Start-Sleep -Seconds $every
    }
    "sampler end $(Get-Date -Format o)" | Out-File $out -Append -Encoding ascii
} -ArgumentList @($adb, $Serial, $Package, $samplesPath, $stopFile, $deadline, $SampleSecs)
Note "sampler started (every ${SampleSecs}s, failsafe ${MaxMinutes}min)"

try {
    Write-Host ''
    Write-Host 'CHECKPOINT 1: TAP "Run benchmark" on the phone NOW (512 ctx / 4 threads / 64 tokens).'
    Read-Host 'Press ENTER the moment you tapped Run'
    "T0_tapped_run $((Get-Date).ToUniversalTime().ToString('o'))" | Out-File (Join-Path $ev 'markers-wall.txt') -Encoding ascii
    Write-Host ''
    Write-Host 'CHECKPOINT 2: watch the benchmark screen (stage label, token counter, elapsed clock).'
    Write-Host '  - If it completes or fails on its own, note the final status/counts/timings.'
    Write-Host '  - If it sits at "Processing prompt 0/64" for 60 seconds, TAP Cancel once, note how fast it resolves.'
    Read-Host 'Press ENTER when the run reached a terminal result'
    "T1_terminal $((Get-Date).ToUniversalTime().ToString('o'))" | Out-File (Join-Path $ev 'markers-wall.txt') -Append -Encoding ascii
} finally {
    '' | Out-File $stopFile -Encoding ascii
    Wait-Job $job -Timeout 20 | Out-Null
    Receive-Job $job -ErrorAction SilentlyContinue | Out-Null
    Remove-Job $job -Force -ErrorAction SilentlyContinue
    if (-not $lc.HasExited) { Stop-Process -Id $lc.Id -Force -ErrorAction SilentlyContinue }
    Note 'capture stopped; owned processes cleaned up'
}

# 7. Post-capture bounded dumps (read-only; failures tolerated).
& $adb -s $Serial logcat -d -t 300 '*:F' > (Join-Path $ev 'post-fatals.txt') 2>&1
& $adb -s $Serial shell ls -l /data/anr > (Join-Path $ev 'anr-list.txt') 2>&1

# 8. Marker summary (printed for immediate paste-back).
$marks = @()
if (Test-Path $lcPath) {
    $marks = Select-String -Path $lcPath -Pattern 'EdgeFitBench' | Select-Object -ExpandProperty Line
    $marks | Out-File (Join-Path $ev 'edgefit-markers.txt') -Encoding ascii
}
$issues = @()
if (Test-Path $lcPath) {
    $issues = Select-String -Path $lcPath -Pattern 'FATAL EXCEPTION|ANR in|tombstone|SIGABRT|SIGSEGV|Force finishing activity' |
        Select-Object -ExpandProperty Line | Select-Object -First 20
    $issues | Out-File (Join-Path $ev 'issues.txt') -Encoding ascii
}
Write-Host ''
Write-Host "DIAG DONE: $ev"
Write-Host ("EdgeFitBench markers: {0} lines (see edgefit-markers.txt)" -f $marks.Count)
$marks | Select-Object -Last 8 | ForEach-Object { Write-Host "  $_" }
Write-Host ("Crash/ANR lines: {0} (see issues.txt)" -f $issues.Count)
$issues | Select-Object -First 5 | ForEach-Object { Write-Host "  $_" }
exit 0
