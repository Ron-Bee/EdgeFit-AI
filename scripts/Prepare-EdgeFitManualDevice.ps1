<# EF-DEVICE-ENROLL-A01: repeatable manual-test device preparation.
Runs on the owner's Windows host (Muse sandbox cannot reach ADB).
Prepares ONE phone for OWNER-MANUAL benchmarking:
  ADB detect, approved-APK verify/install (non-destructive),
  model delivery + SHA-256 verify, readiness checks, handoff.
NEVER starts benchmarks, uploads sessions, or creates credentials.
PowerShell 5.1 compatible.

Usage:
  powershell -NoProfile -ExecutionPolicy Bypass `
    -File scripts/Prepare-EdgeFitManualDevice.ps1 -Serial R5CT604K33V
#>
[CmdletBinding()]
param(
    [string]$Serial = '',
    [string]$ApkPath = 'android/app/build/outputs/apk/debug/app-debug.apk',
    [string]$ApkSha256 = 'FD31B5467235D9EE6EDBCEF1914AF63B105509FC5A35A8AD2865AC84CE913081',
    [string]$ExpectedAppId = 'ai.edgefit.diag',
    [string]$ExpectedCertSha256 = '7D5631EA34DAAAC3B5257C2302B6DC82EEA81A0D545E0E2E097412436CC3BAC4',
    [int]$ExpectedVersionCode = 2,
    [string]$ModelPath = 'C:\Users\Ron\EdgeFit-Models\Llama-3.2-1B-Instruct-Q4_K_M.gguf',
    [long]$ModelSizeBytes = 807694464,
    [string]$ModelSha256 = '6f85a640a97cf2bf5b8e764087b1e83da0fdb51d7c9fab7d0fece9385611df83',
    [string]$ModelDeviceDir = '/sdcard/Download/EdgeFit',
    [string]$AdbPath = '',
    [string]$AaptPath = '',
    [string]$ApksignerPath = '',
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$script:repoRoot = Split-Path $PSScriptRoot -Parent
Push-Location $script:repoRoot
. (Join-Path $PSScriptRoot 'EFRecoveryLib.ps1')

function Fail([string]$msg) {
    Write-Host ''
    Write-Host "DEVICE_PREP: BLOCKED -- $msg"
    exit 1
}
function Note([string]$msg) { Write-Host "DEVICE_PREP: $msg" }
function Plan([string]$msg) { Write-Host "PLAN: $msg" }

function Resolve-Tool([string]$Hint, [scriptblock]$FindDefault, [string]$Name) {
    if ($Hint -ne '' -and (Test-Path $Hint)) { return $Hint }
    # SDK probing is lazy (and fault-tolerant): never touch host tool
    # paths when an explicit hint was supplied.
    $cands = @()
    try { $cands = @(& $FindDefault) } catch { $cands = @() }
    foreach ($c in $cands) {
        if ($c -ne $null -and $c -ne '' -and (Test-Path $c)) { return $c }
    }
    $onPath = (Get-Command $Name -ErrorAction SilentlyContinue)
    if ($onPath -ne $null -and -not $onPath.Source.ToLower().StartsWith('c:\windows')) {
        return $onPath.Source
    }
    return $null
}

function Find-SdkTool([string]$RelPath) {
    if ($env:LOCALAPPDATA -eq $null) { return $null }
    $p = Join-Path (Join-Path $env:LOCALAPPDATA 'Android\Sdk') $RelPath
    if (Test-Path $p) { return $p }
    return $null
}

function Find-BuildTool([string]$FileName) {
    if ($env:LOCALAPPDATA -eq $null) { return $null }
    $bt = Join-Path (Join-Path $env:LOCALAPPDATA 'Android\Sdk') 'build-tools'
    if (-not (Test-Path $bt)) { return $null }
    $hit = Get-ChildItem $bt -Directory -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending |
        ForEach-Object { Join-Path $_.FullName $FileName } |
        Where-Object { Test-Path $_ } | Select-Object -First 1
    return $hit
}

trap { Fail "unexpected fault: $($_.Exception.Message)" }

# ---------- 0. Host model first (fail before touching any device) ----------
if (-not (Test-Path $ModelPath)) { Fail "host model missing: $ModelPath" }
$modelFile = Get-Item $ModelPath
if ($modelFile.Length -ne $ModelSizeBytes) {
    Fail ("host model size {0}, expected {1}" -f $modelFile.Length, $ModelSizeBytes)
}
$modelHash = (Get-FileHash $ModelPath -Algorithm SHA256).Hash.ToUpper()
if ($modelHash -ne $ModelSha256.ToUpper()) { Fail 'host model SHA-256 mismatch' }
Note ("host model OK: {0} bytes {1}" -f $modelFile.Length, $modelHash.Substring(0, 12))

# ---------- 1. Approved APK pinned ----------
$apkFull = $ApkPath
if (-not [System.IO.Path]::IsPathRooted($apkFull)) {
    $apkFull = Join-Path $script:repoRoot $ApkPath
}
if (-not (Test-Path $apkFull)) { Fail "approved APK missing: $apkFull (refusing rebuild; supply the pinned artifact)" }
$apkHash = (Get-FileHash $apkFull -Algorithm SHA256).Hash.ToUpper()
if ($apkHash -ne $ApkSha256.ToUpper()) {
    Fail 'APK is not the approved pinned build (SHA mismatch); refusing to install an unreviewed artifact'
}
Note ("approved APK OK: {0}" -f $apkHash.Substring(0, 12))

# ---------- 2. Tooling (SDK only; never C:\Windows\adb.exe) ----------
$adb = Resolve-Tool $AdbPath { Find-SdkTool 'platform-tools\adb.exe' } 'adb.exe'
if ($adb -eq $null) { Fail 'no usable SDK adb.exe found' }
$aapt = Resolve-Tool $AaptPath { Find-BuildTool 'aapt.exe' } 'aapt.exe'
if ($aapt -eq $null) { Fail 'no usable SDK aapt found (fresh-install metadata checks require it)' }
$apksigner = Resolve-Tool $ApksignerPath { Find-BuildTool 'apksigner.bat' } 'apksigner.bat'
if ($apksigner -eq $null) { Fail 'no usable SDK apksigner found' }
Note "tools: adb + aapt + apksigner resolved"

# ---------- 3. Device resolution ----------
$devs = Invoke-ToolText $adb @('devices', '-l')
if ($devs.Exit -ne 0) { Fail 'adb devices failed' }
$attached = @($devs.Lines | Where-Object { $_ -match '^\S+\s+(device|unauthorized|offline)' } |
    ForEach-Object { @{ Line = $_; Serial = ($_ -split '\s+')[0]; State = ($_ -split '\s+')[1] } })
if ($Serial -ne '') {
    $match = @($attached | Where-Object { $_['Serial'] -eq $Serial })
    if ($match.Count -eq 0) { Fail "serial $Serial not attached" }
    if ($match[0]['State'] -ne 'device') { Fail "serial $Serial state is $($match[0]['State']) (need authorized 'device')" }
    $dev = $match[0]['Serial']
} else {
    $ready = @($attached | Where-Object { $_['State'] -eq 'device' })
    if ($attached.Count -eq 0) { Fail 'no ADB devices attached' }
    if ($ready.Count -ne 1 -or $attached.Count -ne 1) {
        Fail ("refusing ambiguous device set (pass -Serial): " + (($attached | ForEach-Object { $_['Serial'] + ':' + $_['State'] }) -join ', '))
    }
    $dev = $ready[0]['Serial']
}
Note "device: $dev"

function AdbShell([string]$Cmd) {
    $r = Invoke-ToolText $adb @('-s', $dev, 'shell', $Cmd)
    if ($r.Exit -ne 0) { Fail "adb shell failed: $Cmd" }
    return ($r.Lines -join "`n").Trim()
}

# ---------- 4. Hardware / OS / storage readiness ----------
$model = AdbShell 'getprop ro.product.model'
$sdk = AdbShell 'getprop ro.build.version.sdk'
$abi = AdbShell 'getprop ro.product.cpu.abi'
$abis = AdbShell 'getprop ro.product.cpu.abilist'
if ($abis -notmatch 'arm64-v8a') { Fail "device ABIs [$abis] lack arm64-v8a (approved APK is arm64-only)" }
if ([int]$sdk -lt 26) { Fail "device SDK $sdk below minSdk 26" }
$memKb = 0
$memLine = AdbShell 'grep MemTotal /proc/meminfo'
if ($memLine -match '(\d+)') { $memKb = [long]$Matches[1] }
$sdcardFree = 0
$dfLine = AdbShell 'df /sdcard'
foreach ($ln in ($dfLine -split "`n")) {
    # Toybox columns: Filesystem 1K-blocks Used Available Use% Mounted.
    # Split into fields (device names like dm-9 contain digits, so a
    # bare number match would misalign); Available is field 4.
    if ($ln -match '/sdcard\s*$') {
        $fields = @($ln -split '\s+' | Where-Object { $_ -ne '' })
        if ($fields.Count -ge 6 -and $fields[3] -match '^\d+$') {
            $sdcardFree = [long]$fields[3] * 1024
        }
    }
}
$dataFree = 0
$dataLine = AdbShell 'df /data'
foreach ($ln in ($dataLine -split "`n")) {
    if ($ln -match '/data\s*$') {
        $fields = @($ln -split '\s+' | Where-Object { $_ -ne '' })
        if ($fields.Count -ge 6 -and $fields[3] -match '^\d+$') {
            $dataFree = [long]$fields[3] * 1024
        }
    }
}
if ($dataFree -le 0) { Fail 'cannot determine /data free space' }
if ($dataFree -lt 500MB) { Fail "/data free space critically low" }
$thermal = 'unknown'
try {
    $t = Invoke-ToolText $adb @('-s', $dev, 'shell', 'dumpsys', 'thermalservice')
    if ($t.Exit -eq 0) {
        $m = [regex]::Match(($t.Lines -join "`n"), '(?im)(throttling|severity)[^\n]{0,80}')
        if ($m.Success) { $thermal = $m.Value.Trim() }
    }
} catch { $thermal = 'unknown' }
Note ("hardware: {0} sdk {1} abi {2}; mem {3:N0} MB; sdcard free {4:N0} MB; data free {5:N0} MB; thermal: {6}" -f $model, $sdk, $abi, ($memKb / 1024), ($sdcardFree / 1MB), ($dataFree / 1MB), $thermal)

# ---------- 5. APK identity (aapt + apksigner, pinned) ----------
$badging = Invoke-ToolText $aapt @('dump', 'badging', $apkFull)
if ($badging.Exit -ne 0) { Fail 'aapt dump badging failed on approved APK' }
$badge = $badging.Lines -join "`n"
$pkg = ''; $vcode = 0; $vname = ''; $minSdk = 0
if ($badge -match "package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'") {
    $pkg = $Matches[1]; $vcode = [int]$Matches[2]; $vname = $Matches[3]
}
if ($badge -match "sdkVersion:'(\d+)'") { $minSdk = [int]$Matches[1] }
if ($pkg -ne $ExpectedAppId) { Fail "APK package '$pkg' is not $ExpectedAppId (variant mix-up refused)" }
if ($vcode -ne $ExpectedVersionCode) { Fail "APK versionCode $vcode, expected $ExpectedVersionCode" }
if ($minSdk -gt [int]$sdk) { Fail "APK minSdk $minSdk exceeds device SDK $sdk" }
$verify = Invoke-ToolText $apksigner @('verify', '--print-certs', '--min-sdk-version', '26', $apkFull)
if ($verify.Exit -ne 0) { Fail 'apksigner verify failed on approved APK' }
$certLine = $verify.Lines | Where-Object { $_ -match 'SHA-256 digest: (\S+)' } |
    ForEach-Object { $Matches[1] } | Select-Object -First 1
if ([string]::IsNullOrEmpty($certLine)) { Fail 'APK certificate digest unreadable' }
$certNorm = ($certLine.ToUpper() -replace '[^0-9A-F]', '')
if ($certNorm -ne ($ExpectedCertSha256.ToUpper() -replace '[^0-9A-F]', '')) {
    Fail 'APK signing certificate does not match the pinned identity'
}
Note "APK identity: $pkg v$vcode ($vname), cert PIN MATCH"

# ---------- 6. Installed-package triage (never confuse .bench) ----------
$pkgs = Invoke-ToolText $adb @('-s', $dev, 'shell', 'cmd', 'package', 'list', 'packages', '--user', '0')
if ($pkgs.Exit -ne 0) { Fail 'package list failed' }
$hasBase = ($pkgs.Lines | Where-Object { $_ -eq "package:$ExpectedAppId" } | Measure-Object).Count -gt 0
$hasBench = ($pkgs.Lines | Where-Object { $_ -eq "package:${ExpectedAppId}.bench" } | Measure-Object).Count -gt 0
if ($hasBench) {
    Note 'HISTORICAL ai.edgefit.diag.bench present: left untouched; all operations target ai.edgefit.diag only'
}

# ---------- 7. Model delivery (reuse verified copy; never duplicate) ----------
$modelName = Split-Path $ModelPath -Leaf
$deviceModel = "$ModelDeviceDir/$modelName"
$needPush = $true
$st = Invoke-ToolText $adb @('-s', $dev, 'shell', 'stat', '-c', '%s', $deviceModel)
if ($st.Exit -eq 0) {
    $dsize = -1
    if (($st.Lines -join ' ') -match '^\s*(\d+)\s*$') {
        $dsize = [long]$Matches[1]
    }
    if ($dsize -eq $ModelSizeBytes) {
        $sum = Invoke-ToolText $adb @('-s', $dev, 'shell', 'sha256sum', $deviceModel)
        if ($sum.Exit -eq 0 -and (($sum.Lines -join ' ').ToUpper() -match $ModelSha256.ToUpper())) {
            $needPush = $false
            Note 'device model identical and verified; no push needed'
        } else {
            Note 'device model differs; will replace'
        }
    } else {
        Note 'device model size differs; will replace'
    }
}
if ($needPush) {
    if ($sdcardFree -le 0) { Fail 'cannot determine /sdcard free space' }
    if ($sdcardFree -lt 2GB) { Fail 'insufficient /sdcard free space for model delivery (need 2 GB headroom)' }
    $mkdir = Invoke-ToolText $adb @('-s', $dev, 'shell', 'mkdir', '-p', $ModelDeviceDir)
    if ($mkdir.Exit -ne 0) { Fail 'cannot create device model dir' }
    if ($DryRun) {
        Plan "PUSH-MODEL $ModelPath -> $deviceModel"
    } else {
        $push = Invoke-ToolText $adb @('-s', $dev, 'push', $ModelPath, $deviceModel)
        if ($push.Exit -ne 0) { Fail 'model push failed' }
        $sum = Invoke-ToolText $adb @('-s', $dev, 'shell', 'sha256sum', $deviceModel)
        if ($sum.Exit -ne 0 -or (($sum.Lines -join ' ').ToUpper() -notmatch $ModelSha256.ToUpper())) {
            Fail 'device-side model hash mismatch after push'
        }
        Note 'model pushed and device-verified'
    }
} elseif ($DryRun) {
    Plan 'SKIP-MODEL-IDENTICAL (device copy verified)'
}

# ---------- 8. Install: fresh path here, existing path via safe installer ----------
if (-not $hasBase) {
    if ($DryRun) {
        Plan "INSTALL-FRESH $ExpectedAppId from pinned APK (plain adb install, then launch + verify)"
    } else {
        $ins = Invoke-ToolText $adb @('-s', $dev, 'install', $apkFull)
        if ($ins.Exit -ne 0 -or (($ins.Lines -join "`n") -notmatch 'Success')) {
            Fail 'fresh install failed (no uninstall/clear was attempted)'
        }
        $run = Invoke-ToolText $adb @('-s', $dev, 'shell', 'monkey', '-p', $ExpectedAppId, '-c', 'android.intent.category.LAUNCHER', '1')
        if ($run.Exit -ne 0) { Fail 'fresh install launch failed' }
        $chk = Invoke-ToolText $adb @('-s', $dev, 'shell', 'cmd', 'package', 'list', 'packages', '--user', '0')
        if (($chk.Lines | Where-Object { $_ -eq "package:$ExpectedAppId" } | Measure-Object).Count -eq 0) {
            Fail 'fresh install verification failed'
        }
        Note 'fresh install: SUCCESS (records: none pre-existing by definition)'
    }
    $continuity = 'FRESH INSTALL (no prior sessions on this device)'
} else {
    if ($DryRun) {
        Plan "DELEGATE-INSTALLER Invoke-EFRecoveryInstall01.ps1 -Serial $dev (backup-first in-place update; preserves sessions/identity/token)"
    } else {
        & powershell -NoProfile -ExecutionPolicy Bypass `
            -File (Join-Path $PSScriptRoot 'Invoke-EFRecoveryInstall01.ps1') `
            -Serial $dev -ApkPath $ApkPath -ExpectedAppId $ExpectedAppId `
            -ExpectedModel $model -ExpectedCertSha256 $ExpectedCertSha256 `
            -ExpectedUser 0 -SkipBuild
        if ($LASTEXITCODE -ne 0) { Fail "safe installer blocked (exit $LASTEXITCODE); device untouched by wrapper" }
        Note 'existing installation updated in place via safe installer'
    }
    $continuity = 'IN-PLACE UPDATE (sessions/identity/token preserved by installer gates)'
}

# ---------- 9. Global connection status (read-only; never the secret) ----------
$tokenPresent = 'unknown'
$tok = Invoke-ToolText $adb @('-s', $dev, 'shell', "run-as $ExpectedAppId ls files")
if ($tok.Exit -eq 0) {
    $tokenPresent = 'absent'
    foreach ($f in $tok.Lines) {
        if ($f -match '(?i)global|token|vault') { $tokenPresent = 'present (name only; content never read)'; break }
    }
}
$originState = 'unknown (configure in EdgeFit Global Connection if absent)'

Write-Host ''
Write-Host 'DEVICE_READY_FOR_OWNER_MANUAL_TEST'
Write-Host "device: $dev / $model / sdk $sdk / $abi"
Write-Host "apk: $pkg v$vcode ($vname) sha256 $($apkHash.Substring(0, 16))..."
Write-Host "package: $ExpectedAppId ($continuity)"
Write-Host "bench variant: $(if ($hasBench) { 'ai.edgefit.diag.bench PRESENT, untouched' } else { 'absent' })"
Write-Host "model: $deviceModel ($ModelSizeBytes bytes, sha256 verified)"
Write-Host ("storage/ram: sdcard free {0:N0} MB; data free {1:N0} MB; mem {2:N0} MB; thermal: {3}" -f ($sdcardFree / 1MB), ($dataFree / 1MB), ($memKb / 1024), $thermal)
Write-Host "global token: $tokenPresent; origin: $originState"
Write-Host ''
Write-Host 'MANUAL BENCHMARK STEPS (Config A: ctx 512 / threads 4 / predict 64, workload edgefit-prompt-v1):'
Write-Host ' 1. Open EdgeFit AI; open Benchmark.'
Write-Host ' 2. Pick Llama-3.2-1B-Instruct-Q4_K_M.gguf from Download/EdgeFit.'
Write-Host ' 3. Confirm 512 / 4 / 64. Tap Run. Observe prompt -> 64/64 + throughput.'
Write-Host ' 4. Save the result (history keeps it for contribution).'
Write-Host ' 5. Contribution history -> Open run -> Contribute -> review payload -> Approve and upload (deliberate tap only).'
Write-Host 'Wrapper never taps Run or Approve and upload.'
exit 0
