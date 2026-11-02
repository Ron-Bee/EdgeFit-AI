<#
.SYNOPSIS
  EF-ANDROID-RECOVERY-INSTALL-01 one-pass owner-side verification and
  safe in-place install. Every gate stops hard on the first failure.

.DESCRIPTION
  Runs, in order: source safety gates, Gradle compile/tests/assemble,
  APK identity + signing validation, physical device identity,
  installed-vs-candidate certificate match, baseline evidence
  inventory, a verified binary-safe run-as backup of app-private
  sessions and evidence, in-place `adb install -r` (ONLY when every
  gate passes), launch/health checks, and post-install + cold-relaunch
  preservation verification. Never uninstalls, clears data,
  downgrades, or retries an incompatible install.

  With -SkipInstall, reconciles an interrupted run instead of
  updating: verifies the installed artifact against the candidate
  and validates preservation against the prior run's baseline.
  Never installs in that mode.

  Reports VERDICT: PASS or VERDICT: BLOCKED and exits 0 / 1.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts/Invoke-EFRecoveryInstall01.ps1 -Serial <SERIAL>
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,
    [string]$ApkPath = 'android/app/build/outputs/apk/debug/app-debug.apk',
    [string]$GradlePath = 'C:\Users\Ron\.gradle\wrapper\dists\gradle-8.9-bin\90cnw93cvbtalezasaz0blq0a\gradle-8.9\bin\gradle.bat',
    [string]$ExpectedBranch = 'feature/ef-android-global-01-2026-11-01',
    [string]$ExpectedAppId = 'ai.edgefit.diag',
    [string]$ExpectedModel = 'SM-S908U',
    [string]$ExpectedCertSha256 = '',
    [int]$ExpectedUser = 0,
    [string]$ReportDir = '',
    [switch]$SkipBuild,
    [switch]$SkipInstall,
    [string]$PriorReportDir = ''
)

$ErrorActionPreference = 'Stop'
$script:failures = @()

# Run from the repo root regardless of the caller's CWD: this
# script lives in <repo>/scripts/ and every path below is relative.
$script:repoRoot = Split-Path $PSScriptRoot -Parent
Push-Location $script:repoRoot
. (Join-Path $PSScriptRoot 'EFRecoveryLib.ps1')

function Step([string]$name) {
    Write-Host ''
    Write-Host "=== $name ===" -ForegroundColor Cyan
}

function Fail([string]$msg) {
    Write-Host ''
    Write-Host "VERDICT: BLOCKED -- $msg" -ForegroundColor Red
    if ($script:reportFile -and (Test-Path (Split-Path $script:reportFile))) {
        Add-Content $script:reportFile "VERDICT: BLOCKED -- $msg"
    }
    exit 1
}

function Note([string]$msg) {
    Write-Host $msg
    if ($script:reportFile) { Add-Content $script:reportFile $msg }
}

function Run-Step([string]$label, [scriptblock]$cmd) {
    Write-Host "-- $label"
    & $cmd
    if ($LASTEXITCODE -ne 0) { Fail "$label exited $LASTEXITCODE" }
}

# apksigner invocation that also works when only apksigner.jar is
# present (via java -jar). $LASTEXITCODE still reflects the tool.
function Invoke-Signer([string[]]$SignerArgs) {
    if ($script:signerIsJar) {
        return & $script:javaExe -jar $script:signerPath @SignerArgs 2>&1
    }
    return & $script:signerPath @SignerArgs 2>&1
}

# Any unexpected terminating fault (including a native tool's
# stderr, which Windows PowerShell 5.1 raises as terminating even
# past redirections) becomes an honest BLOCKED verdict: never a
# crash without one, never a false PASS.
trap { Fail "unexpected fault: $($_.Exception.Message)" }

# ---------- 0. repo + report dir ----------

Step '0. Repository baseline'
$branch = (git branch --show-current 2>&1)
if ($branch -ne $ExpectedBranch) {
    Fail "branch is '$branch', expected '$ExpectedBranch'"
}
$head = (git rev-parse HEAD)
Note "branch: $branch HEAD: $head"
$stamp = (Get-Date).ToUniversalTime().ToString('yyyyMMdd-HHmmssZ')
if ([string]::IsNullOrWhiteSpace($ReportDir)) {
    $ReportDir = "C:\Users\Ron\EdgeFit-Backups\EF-RECOVERY-INSTALL-01-$stamp"
}
New-Item -ItemType Directory -Force -Path $ReportDir | Out-Null
$script:reportFile = Join-Path $ReportDir 'report.txt'
Note "report: $($script:reportFile)"
git status --short | Out-File (Join-Path $ReportDir 'git-status.txt')

# ---------- 1. source safety gates ----------

Step '1. Source safety gates (no build yet)'
$script:pinNorm = ''
if ($ExpectedCertSha256 -ne '') {
    $script:pinNorm =
        ($ExpectedCertSha256.ToUpper() -replace '[^0-9A-F]', '')
    if ($script:pinNorm.Length -ne 64) {
        Fail '-ExpectedCertSha256 must be 64 hex chars'
    }
    Note 'cert pin: supplied (candidate + installed must match it)'
}
$consent = Get-Content android/app/src/main/java/ai/edgefit/diag/GlobalContract.kt -Raw
if ($consent -match 'http://') { Fail 'plaintext http:// found in GlobalContract.kt' }
Note 'https-only: OK (source)'
# EF-ANDROID-CONSENT-INSTALL-GATE-01: the consent flag may ONLY
# be true under the reviewed Collection A approval. Verify the
# source flag/version/scopes/SHA citation plus the live backend
# approval record (A yes, B no) and the byte-true policy hash.
$consentBranch = 'implement/ef-global-01-2026-10-30'
$consentVersion = 'ef-consent-01'
$consentSha = 'B83E78FBF78AD0FBE4E77CFBBCF37709519A4305A45D86FFCCFF6C72E78CDB21'
& git fetch origin $consentBranch 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { Fail "consent evidence fetch failed for $consentBranch" }
$approvalDoc = (& git show "origin/${consentBranch}:docs/ef-consent-01-approval.md" 2>&1) -join "`n"
$policyTmp = Join-Path $ReportDir 'ef-consent-01-policy.bin'
$bin = Invoke-NativeBinary 'git' "cat-file -p origin/${consentBranch}:docs/ef-consent-01-policy.md" $policyTmp 60
if ($bin.Exit -ne 0 -or $bin.Bytes -le 0) { Fail 'consent policy blob unreadable from backend branch' }
$blobSha = (Get-FileHash $policyTmp -Algorithm SHA256).Hash
$consentWhy = ''
if (-not (Test-EFConsentActivation $consent $consentVersion $consentSha $approvalDoc $blobSha ([ref]$consentWhy))) {
    Fail "consent activation rejected: $consentWhy"
}
Note "consent: APPROVED $consentVersion (backend-verified A, B unapproved)"

# ---------- 2. build ----------

if ($SkipInstall) {
    # Reconcile mode must compare the device against the exact
    # candidate bytes from the interrupted run: rebuilding here
    # could only invalidate that comparison, never strengthen it.
    $SkipBuild = $true
    Note 'build: SKIPPED (reconcile mode validates the existing candidate)'
}
if (-not $SkipBuild) {
    Step '2. Gradle compile + tests + assemble'
    if (-not (Test-Path $GradlePath)) { Fail "gradle not found: $GradlePath" }
    Push-Location android
    try {
        Run-Step ':app:compileDebugKotlin' {
            & $GradlePath :app:compileDebugKotlin --console=plain
        }
        Run-Step ':app:testDebugUnitTest' {
            & $GradlePath :app:testDebugUnitTest --console=plain
        }
        Run-Step ':app:assembleDebug' {
            & $GradlePath :app:assembleDebug --console=plain
        }
    }
    finally { Pop-Location }
    Note 'build: PASS'
}
else { Note 'build: SKIPPED (APK gates still apply)' }

# ---------- 3. candidate APK ----------

Step '3. Candidate APK identity'
if (-not (Test-Path $ApkPath)) { Fail "APK missing: $ApkPath" }
$candSha = (Get-FileHash $ApkPath -Algorithm SHA256).Hash
Note "apk: $ApkPath"
Note "apk sha256: $candSha"
$sdkRoots = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT,
    "$env:LOCALAPPDATA\Android\Sdk") | Where-Object { $_ }
$bt = $sdkRoots | ForEach-Object {
    Get-ChildItem "$_\build-tools" -Directory -ErrorAction SilentlyContinue
} | Sort-Object Name -Descending | Select-Object -First 1
if (-not $bt) { Fail 'no Android build-tools found under SDK roots' }
$aapt = Join-Path $bt.FullName 'aapt.exe'
if (-not (Test-Path $aapt)) { Fail "aapt missing: $aapt" }
$script:signerPath = Join-Path $bt.FullName 'apksigner.bat'
$script:signerIsJar = $false
if (-not (Test-Path $script:signerPath)) {
    $script:signerPath = Join-Path $bt.FullName 'apksigner.jar'
    $script:signerIsJar = $true
}
if (-not (Test-Path $script:signerPath)) {
    Fail "no apksigner in $($bt.FullName)"
}
if ($script:signerIsJar) {
    $script:javaExe = $null
    if (($env:JAVA_HOME -ne '') -and
        (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
        $script:javaExe = "$env:JAVA_HOME\bin\java.exe"
    }
    else {
        $jc = Get-Command java -ErrorAction SilentlyContinue
        if ($jc) { $script:javaExe = $jc.Source }
    }
    if (-not $script:javaExe) {
        Fail 'only apksigner.jar present and no java found for -jar'
    }
}
$badging = & $aapt dump badging $ApkPath
if ($LASTEXITCODE -ne 0) { Fail 'aapt dump badging failed' }
$pkgLine = $badging | Where-Object { $_ -match "^package:" } | Select-Object -First 1
if ($pkgLine -notmatch "name='([^']+)' versionCode='(\d+)' versionName='([^']+)'") {
    Fail 'aapt package line unparseable'
}
$candId = $Matches[1]; $candVer = [int]$Matches[2]; $candName = $Matches[3]
Note "candidate: $candId v$candVer ($candName)"
if ($candId -ne $ExpectedAppId) {
    Fail "candidate appId '$candId' != '$ExpectedAppId' (variant mix-up?)"
}
$certs = Invoke-Signer @('verify', '--print-certs',
    '--min-sdk-version', '26', $ApkPath)
if ($LASTEXITCODE -ne 0) { Fail 'apksigner verify failed on candidate' }
$candCert = ($certs | Where-Object { $_ -match 'SHA-256 digest: (\S+)' } |
    ForEach-Object { $Matches[1] } | Select-Object -First 1)
if (-not $candCert) { Fail 'candidate certificate digest unreadable' }
Note "candidate cert sha256: $candCert"
if ($script:pinNorm -ne '') {
    $candNorm = ($candCert.ToUpper() -replace '[^0-9A-F]', '')
    if ($candNorm -ne $script:pinNorm) {
        Fail 'candidate cert does not match -ExpectedCertSha256 pin'
    }
    Note 'candidate cert: PIN MATCH'
}

# ---------- 4. device identity ----------

Step '4. Physical device identity'
$adb = (Get-Command adb -ErrorAction SilentlyContinue)
if (-not $adb) {
    $pt = $sdkRoots | ForEach-Object {
        Join-Path $_ 'platform-tools\adb.exe'
    } | Where-Object { Test-Path $_ } | Select-Object -First 1
    if (-not $pt) { Fail 'adb not found' }
    $adb = $pt
}
else { $adb = $adb.Source }
$script:adbExe = $adb
& $adb -s $Serial wait-for-device 2>&1 | Out-Null
$state = (& $adb -s $Serial get-state 2>&1) | Select-Object -First 1
if ($state -ne 'device') { Fail "device state '$state' (need 'device')" }
$model = (& $adb -s $Serial shell getprop ro.product.model 2>&1) |
    Select-Object -First 1
if ("$model".Trim() -ne $ExpectedModel) {
    Fail "model '$("$model".Trim())' != '$ExpectedModel'"
}
$userRaw = ((& $adb -s $Serial shell am get-current-user 2>&1) |
    Select-Object -First 1)
$userNum = -1
if (($LASTEXITCODE -ne 0) -or
    (-not [int]::TryParse("$userRaw".Trim(), [ref]$userNum))) {
    Fail "android user unreadable: $userRaw"
}
if ($userNum -ne $ExpectedUser) {
    Fail "android user '$userNum' != '$ExpectedUser'"
}
Note "device: $Serial $("$model".Trim()) user $userNum state $state"
$pmList = Invoke-ToolText $adb @('-s', $Serial, 'shell', 'pm', 'list',
    'packages', '--user', "$ExpectedUser", 'ai.edgefit.diag')
$pkgInfo = Get-EdgeFitPackages $pmList.Lines $ExpectedAppId
$foundNames = @()
if ($pkgInfo.Found) { $foundNames += $ExpectedAppId }
$foundNames += $pkgInfo.Siblings
if ($foundNames.Count -eq 0) { $foundNames += '(none)' }
Note "installed edgefit packages (user $ExpectedUser): $($foundNames -join ', ')"
foreach ($diag in ($pkgInfo.Diagnostics | Select-Object -First 3)) {
    Note "package query diagnostic: $diag"
}
foreach ($sib in $pkgInfo.Siblings) {
    $sibLs = Invoke-ToolText $adb @('-s', $Serial, 'shell',
        "run-as $sib ls files/sessions")
    if ($sibLs.Exit -eq 0) {
        $sibN = @($sibLs.Lines | ForEach-Object { "$_".Trim() } |
            Where-Object { $_ -match '\.json$' }).Count
        Note "sibling $sib sessions: $sibN (separate identity; untouched)"
    }
    else { Note "sibling $sib sessions: unreadable (noted, untouched)" }
}
if ($pkgInfo.Siblings -contains 'ai.edgefit.diag.bench') {
    Note 'WARNING: benchmark variant also installed; confirm target!'
}

# ---------- 5. installed package + cert match ----------

Step '5. Installed package and signing match'
$dump = & $adb -s $Serial shell dumpsys package $ExpectedAppId
if ($LASTEXITCODE -ne 0 -or -not ($dump -match 'versionCode=')) {
    Fail "$ExpectedAppId not installed (refusing fresh-install path)"
}
$instVer = $null
$instName = '?'
foreach ($dl in $dump) {
    if ($dl -match 'versionCode=(\d+)') { $instVer = [int]$Matches[1] }
    if ($dl -match 'versionName=([^\s]+)') { $instName = $Matches[1] }
    if (($null -ne $instVer) -and ($instName -ne '?')) { break }
}
if ($null -eq $instVer) { Fail 'installed versionCode unreadable' }
Note "installed: $ExpectedAppId v$instVer ($instName)"
if ($candVer -lt $instVer) {
    Fail "downgrade v$instVer -> v$candVer refused"
}
$firstInstall = ($dump | Where-Object { $_ -match 'firstInstallTime=' } |
    Select-Object -First 1)
$lastUpdate = ($dump | Where-Object { $_ -match 'lastUpdateTime=' } |
    Select-Object -First 1)
Note "installed: $($firstInstall.Trim()) / $($lastUpdate.Trim())"
$pmPath = (& $adb -s $Serial shell pm path $ExpectedAppId) |
    Select-Object -First 1
if ($pmPath -notmatch 'package:(.+)') { Fail 'installed APK path unreadable' }
$deviceApk = $Matches[1].Trim()
$pulledApk = Join-Path $ReportDir 'installed-base.apk'
$pull = Invoke-ToolText $adb @('-s', $Serial, 'pull', $deviceApk, $pulledApk)
if ($pull.Exit -ne 0) { Fail 'could not pull installed APK for cert check' }
$instCerts = Invoke-Signer @('verify', '--print-certs',
    '--min-sdk-version', '26', $pulledApk)
if ($LASTEXITCODE -ne 0) { Fail 'apksigner verify failed on installed APK' }
$instCert = ($instCerts | Where-Object { $_ -match 'SHA-256 digest: (\S+)' } |
    ForEach-Object { $Matches[1] } | Select-Object -First 1)
if (-not $instCert) { Fail 'installed certificate digest unreadable' }
Note "installed cert sha256: $instCert"
if ($script:pinNorm -ne '') {
    $instNorm = ($instCert.ToUpper() -replace '[^0-9A-F]', '')
    if ($instNorm -ne $script:pinNorm) {
        Fail 'installed cert does not match -ExpectedCertSha256 pin'
    }
    Note 'installed cert: PIN MATCH'
}
if ($instCert -ne $candCert) {
    Fail 'signing mismatch: candidate cert != installed cert'
}
Note 'signing: MATCH'

# ---------- 6. baseline evidence inventory ----------

Step '6. Baseline evidence inventory'
$tarExe = Get-Command tar -ErrorAction SilentlyContinue
if (-not $tarExe) { Fail 'tar.exe not found (needed for backup verification)' }
if ($SkipInstall) {
    if ([string]::IsNullOrWhiteSpace($PriorReportDir)) {
        Fail '-SkipInstall requires -PriorReportDir pointing at the interrupted run'
    }
    if (-not (Test-Path $PriorReportDir)) {
        Fail "prior report dir missing: $PriorReportDir"
    }
    Note "reconcile mode: loading baseline from $PriorReportDir"
    $priorSessionsFile = Join-Path $PriorReportDir 'baseline-sessions.txt'
    $priorEvidenceFile = Join-Path $PriorReportDir 'baseline-evidence.txt'
    $priorManifestFile = Join-Path $PriorReportDir 'MANIFEST.sha256'
    $priorReportFile = Join-Path $PriorReportDir 'report.txt'
    foreach ($req in @($priorSessionsFile, $priorEvidenceFile,
        $priorManifestFile, $priorReportFile)) {
        if (-not (Test-Path $req)) {
            Fail "prior report incomplete, missing: $req"
        }
    }
    $manifestText = (Get-Content $priorManifestFile -Raw).Trim()
    if ($manifestText -notmatch '^([0-9A-Fa-f]{64})\s+(\S+)$') {
        Fail 'prior MANIFEST unparseable'
    }
    $priorTarPath = Join-Path $PriorReportDir $Matches[2]
    if (-not (Test-Path $priorTarPath)) {
        Fail "prior backup missing: $priorTarPath"
    }
    if ((Get-FileHash $priorTarPath -Algorithm SHA256).Hash -ne
        $Matches[1].ToUpper()) {
        Fail 'prior backup hash mismatch (evidence drift since the backup)'
    }
    Note 'prior backup hash: VERIFIED'
    $priorReport = Get-Content $priorReportFile -Raw
    $priorMembers = @('files/sessions', 'files/evidence',
        'files/sessions-quarantine')
    $priorHadMarker = $priorReport -match 'baseline install_id:'
    if ($priorHadMarker) { $priorMembers += 'files/install.json' }
    $priorSes = @(Get-Content $priorSessionsFile |
        ForEach-Object { "$_".Trim() } | Where-Object { $_ -ne '' })
    $priorEv = @(Get-Content $priorEvidenceFile |
        ForEach-Object { "$_".Trim() } | Where-Object { $_ -ne '' })
    $priorCheck = Test-BackupArchive $tarExe.Source $priorTarPath `
        $priorMembers $priorSes $priorEv
    if (-not $priorCheck.Ok) {
        Fail "prior backup does not match its baseline: $($priorCheck.Reason)"
    }
    Note "prior backup re-verified: sessions $($priorCheck.Sessions.Count) evidence $($priorCheck.Evidence.Count) entries $($priorCheck.Entries)"
    $baseSessions = $priorSes
    $baseEvidence = $priorEv
    $baseQuar = $priorCheck.Quarantine
    $baseTomb = @($baseEvidence | Where-Object { $_ -like '*.withdraw.json' })
    $baseInstallId = $null
    if ($priorHadMarker) {
        $priorMarkerTmp = Join-Path $ReportDir 'prior-install.json'
        $extract = Invoke-NativeBinary $tarExe.Source `
            "-xOf `"$priorTarPath`" files/install.json" $priorMarkerTmp 60
        $priorMarkerText = ''
        if ($extract.Exit -eq 0) {
            $priorMarkerText = [IO.File]::ReadAllText($priorMarkerTmp)
        }
        if ($priorMarkerText -match '"install_id"\s*:\s*"([^"]+)"') {
            $baseInstallId = $Matches[1]
        }
        else { Fail 'prior install marker unreadable (evidence incomplete)' }
    }
    Copy-Item $priorSessionsFile (Join-Path $ReportDir 'prior-baseline-sessions.txt')
    Copy-Item $priorEvidenceFile (Join-Path $ReportDir 'prior-baseline-evidence.txt')
    Copy-Item $priorManifestFile (Join-Path $ReportDir 'prior-MANIFEST.sha256')
    Note "baseline sessions: $($baseSessions.Count) evidence: $($baseEvidence.Count) quarantine: $($baseQuar.Count) (from prior run)"
    if ($baseInstallId) {
        Note "baseline install_id: $($baseInstallId.Substring(0, [Math]::Min(8, $baseInstallId.Length)))... (from prior backup)"
    }
    else { Note 'baseline install.json: absent (pre-marker build)' }
    Note "baseline withdrawal tombstones: $($baseTomb.Count) (from prior run)"
}
else {
    $baseSessions = Get-AppList $adb $Serial $ExpectedAppId 'sessions'
    $baseEvidence = Get-AppList $adb $Serial $ExpectedAppId 'evidence'
    $baseQuar = Get-AppList $adb $Serial $ExpectedAppId 'sessions-quarantine'
    Note "baseline sessions: $($baseSessions.Count)"
    Note "baseline evidence: $($baseEvidence.Count)"
    Note "baseline quarantine: $($baseQuar.Count)"
    $baseSessions | Out-File (Join-Path $ReportDir 'baseline-sessions.txt')
    $baseEvidence | Out-File (Join-Path $ReportDir 'baseline-evidence.txt')
    $installJson = & $adb -s $Serial shell "run-as $ExpectedAppId cat files/install.json" 2>&1
    $baseInstallId = $null
    if ($LASTEXITCODE -eq 0 -and ($installJson -match '"install_id"\s*:\s*"([^"]+)"')) {
        $baseInstallId = $Matches[1]
        Note "baseline install_id: $($baseInstallId.Substring(0, [Math]::Min(8, $baseInstallId.Length)))..."
    }
    else { Note 'baseline install.json: absent (pre-marker build)' }
    $baseTomb = @($baseEvidence | Where-Object { $_ -like '*.withdraw.json' })
    Note "baseline withdrawal tombstones: $($baseTomb.Count)"
}

# ---------- 6b. verified backup (read-only on device) ----------

Step '6b. Verified device backup (read-only on device)'
$backupTar = Join-Path $ReportDir 'app-data-backup.tar'
# Request only members proven present by the baseline inventory: a
# missing member would make tar diagnose on stderr, and no
# diagnostic text may ever reach the archive stream.
$tarMembers = @('files/sessions', 'files/evidence',
    'files/sessions-quarantine')
if ($baseInstallId) { $tarMembers += 'files/install.json' }
$memberArgs = $tarMembers -join ' '
$backup = Invoke-NativeBinary $script:adbExe "-s $Serial exec-out run-as $ExpectedAppId tar -c $memberArgs" $backupTar 120
if ($backup.Exit -ne 0) {
    $tail = "$($backup.Stderr)".Trim()
    if ($tail.Length -gt 500) { $tail = $tail.Substring(0, 500) + '...' }
    Fail "device backup command failed (exit $($backup.Exit)): $tail"
}
$check = Test-BackupArchive $tarExe.Source $backupTar $tarMembers $baseSessions $baseEvidence
if (-not $check.Ok) { Fail "backup verification failed: $($check.Reason)" }
$tarHash = (Get-FileHash $backupTar -Algorithm SHA256).Hash
Set-Content (Join-Path $ReportDir 'MANIFEST.sha256') "$tarHash  app-data-backup.tar"
Note "backup: $backupTar"
Note "backup sha256: $tarHash"
Note "backup sessions: $($check.Sessions.Count) evidence: $($check.Evidence.Count) entries: $($check.Entries)"
Note 'backup excludes key material by design (Meta/Global secrets stay on device)'

# ---------- 7. install or reconcile ----------

if ($SkipInstall) {
    Step '7. Reconcile installed candidate (NO reinstall)'
    $instSha = (Get-FileHash $pulledApk -Algorithm SHA256).Hash
    Note "installed artifact sha256: $instSha"
    if ($instSha -ne $candSha) {
        Fail 'installed artifact differs from candidate: an update may be needed, but this run will not install. Re-run without -SkipInstall only after owner review.'
    }
    Note 'installed bytes IDENTICAL to candidate (update already present)'
}
else {
    # RECONCILE-NO-INSTALL: the update below runs only when
    # -SkipInstall is absent; the branch above never installs.
    Step '7. In-place install (all gates passed)'
    $instResult = Invoke-ToolText $adb @('-s', $Serial, 'install',
        '-r', $ApkPath)
    $instText = ($instResult.Lines -join "`n")
    Note $instText.Trim()
    $instVerdict = Get-InstallResult $instResult.Exit $instResult.Lines
    if ($instVerdict.Status -eq 'Success') {
        Note 'install result: SUCCESS (Android confirmed)'
    }
    elseif ($instVerdict.Status -eq 'Failed') {
        Fail "install failed; NOT retrying with uninstall/clear: $($instVerdict.Detail)"
    }
    else {
        Fail "install result indeterminate; reconcile device before any retry: $($instVerdict.Detail)"
    }
}

# ---------- 8. post-install validation ----------

Step '8. Post-install validation'
$postSessions = Get-AppList $adb $Serial $ExpectedAppId 'sessions'
$postEvidence = Get-AppList $adb $Serial $ExpectedAppId 'evidence'
$postQuar = Get-AppList $adb $Serial $ExpectedAppId 'sessions-quarantine'
$postSessions | Out-File (Join-Path $ReportDir 'postinstall-sessions.txt')
$missing = @($baseSessions | Where-Object { $postSessions -notcontains $_ })
$vanished = @($baseEvidence | Where-Object {
    $_ -notlike '*.withdraw.json' -and $postEvidence -notcontains $_ })
$postTomb = @($postEvidence | Where-Object { $_ -like '*.withdraw.json' })
$lostTomb = @($baseTomb | Where-Object { $postTomb -notcontains $_ })
$missingQuar = @($baseQuar | Where-Object { $postQuar -notcontains $_ })
Note "post sessions: $($postSessions.Count) (missing: $($missing.Count))"
Note "post evidence: $($postEvidence.Count) (vanished: $($vanished.Count))"
Note "post tombstones: $($postTomb.Count) (lost: $($lostTomb.Count))"
Note "post quarantine: $($postQuar.Count) (missing: $($missingQuar.Count))"
if ($missing.Count -gt 0) {
    Fail "session files missing after install: $($missing -join ', ')"
}
if ($vanished.Count -gt 0) {
    Fail "evidence files missing after install: $($vanished -join ', ')"
}
if ($lostTomb.Count -gt 0) {
    Fail "withdrawal tombstones lost: $($lostTomb -join ', ')"
}
if ($missingQuar.Count -gt 0) {
    Fail "quarantine files missing after install: $($missingQuar -join ', ')"
}
$tokenProbe = Invoke-ToolText $adb @('-s', $Serial, 'shell',
    "run-as $ExpectedAppId ls files/global_upload_token.bin")
if ($tokenProbe.Exit -eq 0) {
    Note 'device holds a Global upload token: uploads remain gated by approved-policy (Collection A) plus explicit per-batch tap (source-verified)'
}
else {
    Note 'device holds no Global upload token: cloud upload impossible without provisioning'
}
$postJson = & $adb -s $Serial shell "run-as $ExpectedAppId cat files/install.json" 2>&1
if ($baseInstallId -and ($postJson -match '"install_id"\s*:\s*"([^"]+)"')) {
    if ($Matches[1] -ne $baseInstallId) {
        Fail 'install_id changed: data was replaced, not updated'
    }
    Note 'install_id: PRESERVED (true in-place update)'
}
elseif ($baseInstallId) { Fail 'install.json unreadable after install' }
else { Note 'install.json: created on first launch of marker build' }

Step '9. Launch, health, cold relaunch'
& $adb -s $Serial shell monkey -p $ExpectedAppId -c android.intent.category.LAUNCHER 1 | Out-Null
Start-Sleep -Seconds 8
$pid1 = (& $adb -s $Serial shell pidof $ExpectedAppId 2>$null) |
    Select-Object -First 1
if (($LASTEXITCODE -ne 0) -or (-not $pid1) -or (-not "$pid1".Trim())) {
    Fail 'app not running after launch'
}
Note "running pid: $($pid1.Trim())"
$markerCheck = Invoke-ToolText $adb @('-s', $Serial, 'shell',
    "run-as $ExpectedAppId cat files/install.json")
$markerId = $null
if (($markerCheck.Exit -eq 0) -and
    (($markerCheck.Lines -join "`n") -match '"install_id"\s*:\s*"([^"]+)"')) {
    $markerId = $Matches[1]
    Note "post-launch install_id: $($markerId.Substring(0, [Math]::Min(8, $markerId.Length)))... (marker build confirmed running)"
    if ($baseInstallId -and ($markerId -ne $baseInstallId)) {
        Fail 'install_id changed across validation (identity reset?)'
    }
}
else {
    Fail 'install.json missing after launch (marker build cannot be confirmed)'
}
& $adb -s $Serial shell am force-stop $ExpectedAppId
& $adb -s $Serial shell monkey -p $ExpectedAppId -c android.intent.category.LAUNCHER 1 | Out-Null
Start-Sleep -Seconds 8
$pid2 = (& $adb -s $Serial shell pidof $ExpectedAppId 2>$null) |
    Select-Object -First 1
if (($LASTEXITCODE -ne 0) -or (-not $pid2) -or (-not "$pid2".Trim())) {
    Fail 'app not running after cold relaunch'
}
if ("$pid2".Trim() -eq "$pid1".Trim()) {
    Fail 'relaunch was not cold (pid unchanged after force-stop)'
}
$coldSessions = Get-AppList $adb $Serial $ExpectedAppId 'sessions'
$coldMissing = @($baseSessions | Where-Object { $coldSessions -notcontains $_ })
if ($coldMissing.Count -gt 0) {
    Fail "sessions missing after cold relaunch: $($coldMissing -join ', ')"
}
Note "cold relaunch: pid $($pid2.Trim()) (was $($pid1.Trim())), sessions stable ($($coldSessions.Count))"
$fatals = & $adb -s $Serial logcat -d -t 400 2>&1 |
    Where-Object { $_ -match 'FATAL EXCEPTION' -and $_ -match 'edgefit' }
if ($fatals) { Fail "fatal exceptions in logcat: $($fatals -join ' | ')" }
Note 'logcat: no EdgeFit fatals in tail'

Write-Host ''
Write-Host 'VERDICT: PASS' -ForegroundColor Green
Note 'VERDICT: PASS'
exit 0
