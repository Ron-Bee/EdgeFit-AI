# EdgeFit AI EF-SAFE-INSTALL-01 self-test.
# Black-box: writes mock adb/aapt/apksigner shims to temp, invokes the
# preflight tool as a child process per case, asserts exit codes and
# output. No real device, no network, no repo writes outside temp.
# Exit 0 = all pass, 1 = failure.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File scripts/Test-EFSafeInstall01.ps1

$ErrorActionPreference = "Stop"

$SCRIPT:HERE = $PSScriptRoot
$SCRIPT:ROOT = Split-Path $SCRIPT:HERE -Parent
$SCRIPT:TOOL = Join-Path $SCRIPT:HERE "Confirm-EFSafeInstall01.ps1"
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

function New-TempDir {
    $root = Join-Path ([System.IO.Path]::GetTempPath()) "ef-safeinst-test"
    if (-not (Test-Path $root)) { [void](New-Item -ItemType Directory $root) }
    $dir = Join-Path $root ([System.Guid]::NewGuid().ToString("N"))
    [void](New-Item -ItemType Directory $dir)
    return $dir
}

$ADB_MOCK = @'
$log = $env:EF_MOCK_LOG
Add-Content $log ("adb " + ($args -join " "))
$joined = $args -join " "
if ($joined -match "^devices\s*$") {
    if ($env:EF_MOCK_DEVICE -eq "missing") { Write-Output "List of devices attached"; exit 0 }
    if ($env:EF_MOCK_DEVICE -eq "unauth") {
        Write-Output "List of devices attached"; Write-Output "R5CT604K33V`tunauthorized"; exit 0
    }
    Write-Output "List of devices attached"; Write-Output "R5CT604K33V`tdevice"; exit 0
}
if ($joined -match "getprop ro.product.model") { Write-Output $env:EF_MOCK_MODEL; exit 0 }
if ($joined -match "get-current-user") { Write-Output $env:EF_MOCK_USER; exit 0 }
if ($joined -match "pm list packages") {
    foreach ($p in ($env:EF_MOCK_PACKAGES -split ";")) {
        if ($p.Trim() -ne "") { Write-Output ("package:" + $p.Trim()) }
    }
    exit 0
}
if ($joined -match "dumpsys package") {
    Write-Output "Packages:"
    Write-Output ("  versionCode=" + $env:EF_MOCK_VERCODE + " minSdk=26 targetSdk=35 versionName=0.2.0")
    exit 0
}
if ($joined -match "run-as (\S+) ls -1 files/sessions") {
    if ($env:EF_MOCK_RUNAS_FAIL -eq "1") { Write-Output "run-as: Package is not debuggable"; exit 1 }
    foreach ($s in ($env:EF_MOCK_SESSIONS -split ";")) {
        if ($s.Trim() -ne "") { Write-Output $s.Trim() }
    }
    exit 0
}
if ($joined -match "run-as (\S+) ls -1 files/evidence") {
    if ($env:EF_MOCK_RUNAS_FAIL -eq "1") { Write-Output "run-as: Package is not debuggable"; exit 1 }
    foreach ($s in ($env:EF_MOCK_EVIDENCE -split ";")) {
        if ($s.Trim() -ne "") { Write-Output $s.Trim() }
    }
    exit 0
}
Write-Output ("mock-adb: no rule for: " + $joined); exit 3
'@

$AAPT_MOCK = @'
$log = $env:EF_MOCK_LOG
Add-Content $log ("aapt " + ($args -join " "))
if ($env:EF_MOCK_AAPT_BAD -eq "1") { Write-Output "not a valid badging dump"; exit 0 }
Write-Output ("package: name='" + $env:EF_MOCK_APKPKG + "' versionCode='2' versionName='0.2.0' platformBuildVersionName='35'")
exit 0
'@

$SIG_MOCK = @'
$log = $env:EF_MOCK_LOG
Add-Content $log ("apksigner " + ($args -join " "))
if ($env:EF_MOCK_CERT_BAD -eq "1") { Write-Output "no certificates found"; exit 0 }
Write-Output ("Signer #1 certificate SHA-256 digest: " + $env:EF_MOCK_CERT)
exit 0
'@

$CERT = "8A6A125C6A93194632FCD9407AFE02166E32BD8D6E83D37DE7877F3A3C908D3B"

function Reset-MockEnv {
    $env:EF_MOCK_DEVICE = "ok"
    $env:EF_MOCK_MODEL = "SM-S908U"
    $env:EF_MOCK_USER = "0"
    $env:EF_MOCK_PACKAGES = "ai.edgefit.diag.bench"
    $env:EF_MOCK_VERCODE = "2"
    $env:EF_MOCK_SESSIONS = "SES-aaaaaaaaaaaaaaaa.json;SES-bbbbbbbbbbbbbbbb.json"
    $env:EF_MOCK_EVIDENCE = "BENCH-20261031T030944Z-603a5d78.json"
    $env:EF_MOCK_APKPKG = "ai.edgefit.diag.bench"
    $env:EF_MOCK_CERT = $CERT
    $env:EF_MOCK_RUNAS_FAIL = "0"
    $env:EF_MOCK_AAPT_BAD = "0"
    $env:EF_MOCK_CERT_BAD = "0"
}

function New-BackupDir([string]$Parent, [bool]$Tamper) {
    $dir = Join-Path $Parent "backup"
    [void](New-Item -ItemType Directory $dir)
    $f1 = Join-Path $dir "SES-aaaaaaaaaaaaaaaa.json"
    $f2 = Join-Path $dir "BENCH-20261031T030944Z-603a5d78.json"
    Set-Content $f1 '{"session_id":"SES-aaaaaaaaaaaaaaaa","synthetic_fixture":true}'
    Set-Content $f2 '{"run_id":"BENCH-20261031T030944Z-603a5d78","synthetic_fixture":true}'
    $h1 = (Get-FileHash $f1 -Algorithm SHA256).Hash
    $h2 = (Get-FileHash $f2 -Algorithm SHA256).Hash
    if ($Tamper) { $h2 = "00" + $h2.Substring(2) }
    Set-Content (Join-Path $dir "MANIFEST.sha256") @(
        "$h1  SES-aaaaaaaaaaaaaaaa.json",
        "$h2  BENCH-20261031T030944Z-603a5d78.json"
    )
    return $dir
}

function Invoke-Preflight([string]$CaseDir, [string]$ExtraArgs,
    [string]$ExpectedPkg = "ai.edgefit.diag.bench",
    [string]$Operation = "InstallUpdate", [string]$AdbOverride = "") {
    $env:EF_MOCK_LOG = Join-Path $CaseDir "mock.log"
    Add-Content $env:EF_MOCK_LOG "--- preflight invocation ---"
    $apk = Join-Path $CaseDir "fixture.apk"
    Set-Content $apk "synthetic apk bytes (content ignored; aapt is mocked)"
    # -File form (temp paths contain no spaces): preserves tool exit codes,
    # unlike -Command "& ..." which collapses nonzero exits to 1.
    $adbMock = $AdbOverride
    if ($adbMock -eq "") { $adbMock = Join-Path $CaseDir "adb-mock.ps1" }
    $argList = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
        $SCRIPT:TOOL, "-Serial", "R5CT604K33V", "-ExpectedModel", "SM-S908U",
        "-User", "0", "-ApkPath", $apk, "-ExpectedPackage", $ExpectedPkg,
        "-ExpectedCertSha256", $CERT, "-IntendedOperation", $Operation,
        "-AdbPath", $adbMock,
        "-AaptPath", (Join-Path $CaseDir "aapt-mock.ps1"),
        "-ApksignerPath", (Join-Path $CaseDir "sig-mock.ps1")) +
        ($ExtraArgs -split "\s+" | Where-Object { $_ -ne "" } |
            ForEach-Object { $_.Trim("'") })
    $out = & powershell.exe @argList 2>&1 | Out-String
    return @{ Exit = $LASTEXITCODE; Text = $out }
}

# --- setup: one temp root, shims shared across cases ---

$Tmp = New-TempDir
Set-Content (Join-Path $Tmp "adb-mock.ps1") $ADB_MOCK
Set-Content (Join-Path $Tmp "aapt-mock.ps1") $AAPT_MOCK
Set-Content (Join-Path $Tmp "sig-mock.ps1") $SIG_MOCK
$GoodBackup = New-BackupDir $Tmp $false

Write-Output "EF-SAFE-INSTALL-01 SELF-TEST"

# 1. happy path: full PASS with verified backup
Reset-MockEnv
$r = Invoke-Preflight $Tmp "-BackupDir '$GoodBackup'"
Assert-True ($r.Exit -eq 0 -and $r.Text -match "RESULT: PASS") "happy.pass"
Assert-True ($r.Text -match "post-install checks") "happy.postinstall"

# 2. base-vs-benchmark substitution refused
Reset-MockEnv
$env:EF_MOCK_APKPKG = "ai.edgefit.diag"
$r = Invoke-Preflight $Tmp "-BackupDir '$GoodBackup'"
Assert-True ($r.Exit -eq 1 -and $r.Text -match "identity mismatch") "apk-mismatch.blocked"

# 3. missing device
Reset-MockEnv
$env:EF_MOCK_DEVICE = "missing"
$r = Invoke-Preflight $Tmp "-BackupDir '$GoodBackup'"
Assert-True ($r.Exit -eq 1 -and $r.Text -match "not listed") "nodevice.blocked"

# 4. unauthorized device
Reset-MockEnv
$env:EF_MOCK_DEVICE = "unauth"
$r = Invoke-Preflight $Tmp "-BackupDir '$GoodBackup'"
Assert-True ($r.Exit -eq 1 -and $r.Text -match "not in 'device' state") "unauth.blocked"

# 5. wrong Android user
Reset-MockEnv
$env:EF_MOCK_USER = "150"
$r = Invoke-Preflight $Tmp "-BackupDir '$GoodBackup'"
Assert-True ($r.Exit -eq 1 -and $r.Text -match "user mismatch") "user.blocked"

# 6. signing mismatch
Reset-MockEnv
$env:EF_MOCK_CERT = "00" + $CERT.Substring(2)
$r = Invoke-Preflight $Tmp "-BackupDir '$GoodBackup'"
Assert-True ($r.Exit -eq 1 -and $r.Text -match "signing certificate mismatch") "signing.blocked"

# 7a. missing backup on data-bearing install
Reset-MockEnv
$r = Invoke-Preflight $Tmp ""
Assert-True ($r.Exit -eq 1 -and $r.Text -match "requires -BackupDir") "nobackup.blocked"

# 7b. tampered backup manifest
Reset-MockEnv
$BadBackup = New-BackupDir (Join-Path $Tmp "badcase") $true
$r = Invoke-Preflight $Tmp "-BackupDir '$BadBackup'"
Assert-True ($r.Exit -eq 1 -and $r.Text -match "backup verification failed") "badbackup.blocked"

# 8. unreadable session inventory
Reset-MockEnv
$env:EF_MOCK_RUNAS_FAIL = "1"
$r = Invoke-Preflight $Tmp "-BackupDir '$GoodBackup'"
Assert-True ($r.Exit -eq 1 -and $r.Text -match "cannot inventory") "noinventory.blocked"

# 9. corrupt aapt output
Reset-MockEnv
$env:EF_MOCK_AAPT_BAD = "1"
$r = Invoke-Preflight $Tmp "-BackupDir '$GoodBackup'"
Assert-True ($r.Exit -eq 1 -and $r.Text -match "could not parse APK") "badaapt.blocked"

# 10a. sibling identity holds data, unacknowledged
Reset-MockEnv
$env:EF_MOCK_PACKAGES = "ai.edgefit.diag.bench;ai.edgefit.diag"
$r = Invoke-Preflight $Tmp "-BackupDir '$GoodBackup'"
Assert-True ($r.Exit -eq 1 -and $r.Text -match "holds session data") "sibling.blocked"

# 10b. new-identity install with acknowledgment passes (non-destructive)
Reset-MockEnv
$env:EF_MOCK_PACKAGES = "ai.edgefit.diag.bench"
$env:EF_MOCK_SESSIONS = ""
$env:EF_MOCK_EVIDENCE = ""
$env:EF_MOCK_APKPKG = "ai.edgefit.diag"
$env:EF_MOCK_PACKAGES = "ai.edgefit.diag.bench"
$r = Invoke-Preflight $Tmp "-AllowNewIdentity" "ai.edgefit.diag" "NewIdentityInstall"
Assert-True ($r.Exit -eq 0 -and $r.Text -match "RESULT: PASS") "newidentity.pass"

# 11a. destructive op without authorization
Reset-MockEnv
$r = Invoke-Preflight $Tmp "-BackupDir '$GoodBackup'" "ai.edgefit.diag.bench" "Uninstall"
Assert-True ($r.Exit -eq 1 -and $r.Text -match "requires -AuthorizeDestructive") "destructive-noauth.blocked"

# 11b. authorized destructive op passes gates but executes nothing
Reset-MockEnv
$r = Invoke-Preflight $Tmp "-BackupDir '$GoodBackup' -AuthorizeDestructive" "ai.edgefit.diag.bench" "Uninstall"
Assert-True ($r.Exit -eq 0 -and $r.Text -match "RESULT: PASS") "destructive-auth.pass"

# 12. missing tool is ERROR (not silent pass)
Reset-MockEnv
$r = Invoke-Preflight $Tmp "" "ai.edgefit.diag.bench" "InstallUpdate" "Z:\nope\adb.exe"
Assert-True ($r.Exit -eq 2 -and $r.Text -match "ERROR") "missingtool.error"

# 13. zero destructive invocations across the whole run
$allLogs = Get-ChildItem $Tmp -Filter "mock.log" -Recurse -ErrorAction SilentlyContinue
$mutating = @()
foreach ($log in $allLogs) {
    $mutating += @(Select-String -Path $log.FullName -Pattern "\s(install|uninstall|clear|rm)\s" |
        Where-Object { $_.Line -notmatch "pm list|dumpsys|getprop|get-current-user|run-as" })
}
Assert-True ($mutating.Count -eq 0) "zero-mutation"

Remove-Item $Tmp -Recurse -Force -ErrorAction SilentlyContinue

Write-Output ""
if ($SCRIPT:Fail -eq 0) {
    Write-Output "EF-SAFE-INSTALL-01 SELF-TEST: $($SCRIPT:Pass) passed, 0 failed"
    exit 0
} else {
    Write-Output "EF-SAFE-INSTALL-01 SELF-TEST: $($SCRIPT:Pass) passed, $($SCRIPT:Fail) failed"
    foreach ($f in $SCRIPT:Failures) { Write-Output "  failed: $f" }
    exit 1
}
