# EF-DEVICE-ENROLL-A01 self-test: drives Prepare-EdgeFitManualDevice.ps1
# with mock adb/aapt/apksigner under -DryRun (zero mutations).
# Exit 0 = all pass, 1 = failure.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File scripts/Test-EFManualDevicePrep01.ps1

$ErrorActionPreference = "Stop"

$SCRIPT:Pass = 0
$SCRIPT:Fail = 0
$SCRIPT:Failures = New-Object System.Collections.ArrayList

function Assert-True([bool]$Cond, [string]$Name) {
    if ($Cond) {
        $SCRIPT:Pass++
        Write-Output "  PASS $Name"
    } else {
        $SCRIPT:Fail++
        [void]$SCRIPT:Failures.Add($Name)
        Write-Output "  FAIL $Name"
    }
}

$repo = Split-Path $PSScriptRoot -Parent
$wrapper = Join-Path $PSScriptRoot 'Prepare-EdgeFitManualDevice.ps1'
$mockAdb = Join-Path $PSScriptRoot 'test/mock-adb.ps1'
$mockAapt = Join-Path $PSScriptRoot 'test/mock-aapt.ps1'
$mockSigner = Join-Path $PSScriptRoot 'test/mock-apksigner.ps1'

$work = Join-Path ([System.IO.Path]::GetTempPath()) ('ef-manualprep-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory $work | Out-Null
try {
    $fakeModel = Join-Path $work 'model.gguf'
    'fake-model-bytes' | Out-File $fakeModel -Encoding ascii -NoNewline
    $mSize = (Get-Item $fakeModel).Length
    $mSha = (Get-FileHash $fakeModel -Algorithm SHA256).Hash
    $fakeApk = Join-Path $work 'app-debug.apk'
    'fake-apk-bytes' | Out-File $fakeApk -Encoding ascii -NoNewline
    $aSha = (Get-FileHash $fakeApk -Algorithm SHA256).Hash

    function Invoke-Wrapper($ExtraArgs, $Scenario, $ModelState, $LogName) {
        $env:MOCK_ADB = $Scenario
        $env:MOCK_MODEL_STATE = $ModelState
        $env:MOCK_MODEL_SIZE = "$mSize"
        $env:MOCK_MODEL_SHA = $mSha
        $log = Join-Path $work $LogName
        if (Test-Path $log) { Remove-Item $log -Force }
        $env:MOCK_LOG = $log
        $base = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $wrapper,
            '-AdbPath', $mockAdb, '-AaptPath', $mockAapt, '-ApksignerPath', $mockSigner,
            '-ApkPath', $fakeApk, '-ApkSha256', $aSha,
            '-ModelPath', $fakeModel, '-ModelSizeBytes', "$mSize", '-ModelSha256', $mSha,
            '-DryRun')
        $allArgs = $base + $ExtraArgs
        $out = & powershell @allArgs 2>&1 | Out-String
        return @{ Exit = $LASTEXITCODE; Text = $out; Log = $log }
    }

    function Clear-MockEnv {
        Remove-Item Env:\MOCK_ADB -ErrorAction SilentlyContinue
        Remove-Item Env:\MOCK_MODEL_STATE -ErrorAction SilentlyContinue
        Remove-Item Env:\MOCK_MODEL_SIZE -ErrorAction SilentlyContinue
        Remove-Item Env:\MOCK_MODEL_SHA -ErrorAction SilentlyContinue
        Remove-Item Env:\MOCK_LOG -ErrorAction SilentlyContinue
        Remove-Item Env:\MOCK_CERT -ErrorAction SilentlyContinue
    }

    # 1. Fresh installation plans a base-only fresh install (implicit single device).
    $r = Invoke-Wrapper @() 'fresh' 'absent' 'c1.log'
    Assert-True (($r.Exit -eq 0) -and ($r.Text -match 'PLAN: INSTALL-FRESH') -and ($r.Text -notmatch '\.bench')) 'fresh installation plans base-only install'
    Clear-MockEnv

    # 2. Existing installation delegates to the safe installer; handoff present.
    $r = Invoke-Wrapper @('-Serial', 'R5CT604K33V') 'existing' 'absent' 'c2.log'
    Assert-True (($r.Exit -eq 0) -and ($r.Text -match 'PLAN: DELEGATE-INSTALLER') -and
        ($r.Text -match 'Invoke-EFRecoveryInstall01\.ps1') -and ($r.Text -match 'DEVICE_READY_FOR_OWNER_MANUAL_TEST') -and
        ($r.Text -match 'ctx 512 / threads 4 / predict 64')) 'existing installation delegates with handoff'
    Assert-True (($r.Text -notmatch 'uninstall') -and ($r.Text -notmatch 'pm clear') -and ($r.Text -notmatch 'install -d')) 'existing path never destructive'
    Clear-MockEnv

    # 3. Historical .bench is detected, untouched, and never the target.
    $r = Invoke-Wrapper @() 'bench' 'absent' 'c3.log'
    Assert-True (($r.Exit -eq 0) -and ($r.Text -match 'HISTORICAL ai\.edgefit\.diag\.bench') -and
        ($r.Text -match 'PLAN: INSTALL-FRESH') -and ($r.Text -notmatch 'PLAN: [^\n]*\.bench')) 'historical .bench detected and untouched'
    Clear-MockEnv

    # 4. Signature conflict blocks.
    $env:MOCK_CERT = '00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF'
    $r = Invoke-Wrapper @() 'existing' 'absent' 'c4.log'
    Assert-True (($r.Exit -ne 0) -and ($r.Text -match 'certificate')) 'signature conflict blocks'
    Clear-MockEnv

    # 5. Wrong ABI blocks.
    $r = Invoke-Wrapper @() 'wrongabi' 'absent' 'c5.log'
    Assert-True (($r.Exit -ne 0) -and ($r.Text -match 'arm64-v8a')) 'wrong ABI blocks'
    Clear-MockEnv

    # 6. Insufficient storage blocks model delivery.
    $r = Invoke-Wrapper @() 'lowstorage' 'absent' 'c6.log'
    Assert-True (($r.Exit -ne 0) -and ($r.Text -match '2 GB')) 'insufficient storage blocks'
    Clear-MockEnv

    # 7. Unauthorized device blocks.
    $r = Invoke-Wrapper @() 'unauthorized' 'absent' 'c7.log'
    Assert-True (($r.Exit -ne 0) -and ($r.Text -match 'unauthorized')) 'unauthorized device blocks'
    Clear-MockEnv

    # 8. Multiple devices require explicit -Serial.
    $r = Invoke-Wrapper @() 'multidev' 'absent' 'c8a.log'
    Assert-True (($r.Exit -ne 0) -and ($r.Text -match 'ambiguous')) 'multiple devices block without serial'
    Clear-MockEnv
    $r = Invoke-Wrapper @('-Serial', 'R5CT604K33V') 'multidev' 'absent' 'c8b.log'
    Assert-True (($r.Exit -eq 0) -and ($r.Text -match 'device: R5CT604K33V')) 'explicit serial resolves one device'
    Clear-MockEnv

    # 9. Model hash mismatch blocks before any device touch.
    $env:MOCK_ADB = 'existing'
    $env:MOCK_MODEL_STATE = 'absent'
    $log9 = Join-Path $work 'c9.log'
    if (Test-Path $log9) { Remove-Item $log9 -Force }
    $env:MOCK_LOG = $log9
    $out9 = & powershell -NoProfile -ExecutionPolicy Bypass -File $wrapper `
        -AdbPath $mockAdb -AaptPath $mockAapt -ApksignerPath $mockSigner `
        -ApkPath $fakeApk -ApkSha256 $aSha `
        -ModelPath $fakeModel -ModelSizeBytes "$mSize" -ModelSha256 ('0' * 64) `
        -DryRun 2>&1 | Out-String
    $touched = (Test-Path $log9) -and ((Get-Item $log9).Length -gt 0)
    Assert-True (($LASTEXITCODE -ne 0) -and ($out9 -match 'SHA-256 mismatch') -and (-not $touched)) 'model mismatch blocks pre-device'
    Clear-MockEnv

    # 10. Existing token is reported present, never read or reset.
    $r = Invoke-Wrapper @('-Serial', 'R5CT604K33V') 'token' 'absent' 'c10.log'
    Assert-True (($r.Exit -eq 0) -and ($r.Text -match 'token: present') -and ($r.Text -notmatch 'uninstall|pm clear|keystore.*reset')) 'token preserved and reported'
    Clear-MockEnv

    # 11. Identical device model is reused, never re-pushed.
    $r = Invoke-Wrapper @() 'existing' 'present' 'c11.log'
    $adbCalls = ''
    if (Test-Path $r.Log) { $adbCalls = Get-Content $r.Log -Raw }
    Assert-True (($r.Exit -eq 0) -and ($r.Text -match 'SKIP-MODEL-IDENTICAL') -and ($adbCalls -notmatch 'push')) 'identical model reused'
    Clear-MockEnv
} finally {
    Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item Env:\MOCK_ADB -ErrorAction SilentlyContinue
    Remove-Item Env:\MOCK_MODEL_STATE -ErrorAction SilentlyContinue
    Remove-Item Env:\MOCK_MODEL_SIZE -ErrorAction SilentlyContinue
    Remove-Item Env:\MOCK_MODEL_SHA -ErrorAction SilentlyContinue
    Remove-Item Env:\MOCK_LOG -ErrorAction SilentlyContinue
    Remove-Item Env:\MOCK_CERT -ErrorAction SilentlyContinue
}

Write-Output ("PASS={0} FAIL={1}" -f $SCRIPT:Pass, $SCRIPT:Fail)
if ($SCRIPT:Fail -gt 0) {
    Write-Output ("failures: " + ($SCRIPT:Failures -join '; '))
    exit 1
}
exit 0
