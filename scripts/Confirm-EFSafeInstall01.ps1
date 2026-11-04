# EdgeFit AI EF-SAFE-INSTALL-01 installation preflight.
# Fail-closed, READ-ONLY gate for APK operations on data-bearing installs.
# Implements FR-SAFE-01 checks (1)-(10) as an authorization prerequisite.
#
# This tool NEVER executes: adb install / uninstall, pm clear / uninstall,
# rm, data migration, cloud upload, or session import. A PASS authorizes
# nothing by itself; the owner still approves and performs the operation.
#
# Exit codes: 0 = PASS (all gates green), 1 = BLOCKED (named reasons),
# 2 = ERROR (could not evaluate: usage, missing tools, unreadable inputs).
#
# Usage (all read-only; backup dir is verified, never written):
#   powershell -File scripts/Confirm-EFSafeInstall01.ps1 `
#     -Serial R5CT604K33V -ExpectedModel SM-S908U -User 0 `
#     -ApkPath .\android\app\build\outputs\apk\benchmark\app-benchmark.apk `
#     -ExpectedPackage ai.edgefit.diag.bench `
#     -ExpectedCertSha256 <64-hex> -BackupDir <dir> `
#     -IntendedOperation InstallUpdate
#
# PowerShell 5.1 compatible, zero dependencies beyond adb/aapt/apksigner.
param(
    [string]$Serial = "",
    [string]$ExpectedModel = "",
    [int]$User = 0,
    [string]$ApkPath = "",
    [string]$ExpectedPackage = "",
    [string]$ExpectedCertSha256 = "",
    [string]$BackupDir = "",
    [ValidateSet("InstallUpdate", "NewIdentityInstall", "Uninstall", "ClearData")]
    [string]$IntendedOperation = "InstallUpdate",
    [switch]$AuthorizeDestructive,
    [switch]$AllowNewIdentity,
    [string]$AdbPath = "",
    [string]$AaptPath = "",
    [string]$ApksignerPath = ""
)

$ErrorActionPreference = "Stop"

$SCRIPT:Blocked = New-Object System.Collections.ArrayList
$SCRIPT:Notes = New-Object System.Collections.ArrayList

function Add-Block([string]$Reason) {
    [void]$SCRIPT:Blocked.Add($Reason)
}

function Add-Note([string]$Text) {
    [void]$SCRIPT:Notes.Add($Text)
}

function Resolve-Tool([string]$Explicit, [string]$Name) {
    if ($Explicit -ne "") {
        if (Test-Path $Explicit) { return $Explicit }
        return $null
    }
    $cmd = Get-Command $Name -ErrorAction SilentlyContinue
    if ($null -ne $cmd) { return $cmd.Source }
    return $null
}

# Invokes a tool and captures stdout lines + exit code. Never throws on
# nonzero exit; callers treat that as a failed gate (fail-closed).
function Invoke-Tool([string]$Exe, [string[]]$ToolArgs) {
    try {
        $out = & $Exe @ToolArgs 2>&1 | Out-String
        return @{ Exit = $LASTEXITCODE; Text = $out }
    } catch {
        return @{ Exit = 999; Text = $_.Exception.Message }
    }
}

# --- Gate 0: required inputs and tools (fail-closed) ---

$adb = Resolve-Tool $AdbPath "adb"
$aapt = Resolve-Tool $AaptPath "aapt"
$apksigner = Resolve-Tool $ApksignerPath "apksigner"

if ($Serial -eq "" -or $ExpectedModel -eq "" -or $ApkPath -eq "" -or
    $ExpectedPackage -eq "" -or $ExpectedCertSha256 -eq "") {
    Write-Output "ERROR: Serial, ExpectedModel, ApkPath, ExpectedPackage, and ExpectedCertSha256 are all required."
    exit 2
}
if ($null -eq $adb) { Write-Output "ERROR: adb not found (set -AdbPath)."; exit 2 }
if ($null -eq $aapt) { Write-Output "ERROR: aapt not found (set -AaptPath)."; exit 2 }
if ($null -eq $apksigner) { Write-Output "ERROR: apksigner not found (set -ApksignerPath)."; exit 2 }
if (-not (Test-Path $ApkPath)) { Write-Output "ERROR: APK not found: $ApkPath"; exit 2 }
if ($ExpectedCertSha256 -notmatch "^[0-9a-fA-F]{64}$") {
    Write-Output "ERROR: ExpectedCertSha256 must be 64 hex chars."
    exit 2
}

$apkHash = (Get-FileHash $ApkPath -Algorithm SHA256).Hash
Add-Note "apk_sha256=$apkHash"

# --- Gate 1: exact device + hardware identity (read-only) ---

$devices = Invoke-Tool $adb @("devices")
$serialLine = ($devices.Text -split "`r?`n") |
    Where-Object { $_ -match [regex]::Escape($Serial) } |
    Select-Object -First 1
if ($devices.Exit -ne 0 -or $null -eq $serialLine) {
    Add-Block "device $Serial not listed by adb devices"
} elseif ($serialLine -notmatch "\sdevice\s*$") {
    Add-Block "device $Serial not in 'device' state: $($serialLine.Trim())"
} else {
    Add-Note "device=$Serial state=device"
    $model = Invoke-Tool $adb @("-s", $Serial, "shell", "getprop", "ro.product.model")
    $modelName = ($model.Text -split "`r?`n" | Where-Object { $_.Trim() -ne "" } |
        Select-Object -First 1)
    if ($model.Exit -ne 0 -or $null -eq $modelName) {
        Add-Block "could not read ro.product.model"
    } elseif ($modelName.Trim() -cne $ExpectedModel) {
        Add-Block "hardware model mismatch: got '$($modelName.Trim())', expected '$ExpectedModel'"
    } else {
        Add-Note "model=$ExpectedModel"
    }
}

# --- Gate 2: Android user identity (read-only) ---

$cur = Invoke-Tool $adb @("-s", $Serial, "shell", "am", "get-current-user")
$curUser = ($cur.Text -split "`r?`n" | Where-Object { $_.Trim() -ne "" } |
    Select-Object -First 1)
if ($cur.Exit -ne 0 -or $null -eq $curUser) {
    Add-Block "could not determine current Android user"
} elseif ($curUser.Trim() -ne "$User") {
    Add-Block "Android user mismatch: current '$($curUser.Trim())', expected '$User'"
} else {
    Add-Note "user=$User"
}

# --- Gate 3: APK identity and variant (read-only, from APK bytes) ---

$badging = Invoke-Tool $aapt @("dump", "badging", $ApkPath)
$apkPackage = $null
$apkVersion = $null
if ($badging.Exit -eq 0) {
    $pkgLine = ($badging.Text -split "`r?`n") |
        Where-Object { $_ -match "^package:" } | Select-Object -First 1
    if ($null -ne $pkgLine -and $pkgLine -match "name='([^']+)'" ) {
        $apkPackage = $Matches[1]
    }
    if ($null -ne $pkgLine -and $pkgLine -match "versionCode='([^']+)'") {
        $apkVersion = $Matches[1]
    }
}
if ($null -eq $apkPackage) {
    Add-Block "could not parse APK package name (aapt output unusable)"
} elseif ($apkPackage -cne $ExpectedPackage) {
    Add-Block "APK identity mismatch: APK is '$apkPackage', expected '$ExpectedPackage' (refusing cross-identity install)"
} else {
    Add-Note "apk_package=$apkPackage versionCode=$apkVersion"
}

# --- Gate 4: installed package + version (read-only) ---

$pmList = Invoke-Tool $adb @("-s", $Serial, "shell", "pm", "list", "packages",
    "--user", "$User")
$edgefits = @()
if ($pmList.Exit -eq 0) {
    $edgefits = @($pmList.Text -split "`r?`n" |
        Where-Object { $_ -match "^package:ai\.edgefit\.diag" } |
        ForEach-Object { ($_ -split ":", 2)[1].Trim() } |
        Where-Object { $_ -ne "" })
} else {
    Add-Block "could not list installed packages for user $User"
}
$installedExpected = $edgefits -contains $ExpectedPackage
$otherEdgefits = @($edgefits | Where-Object { $_ -cne $ExpectedPackage })
foreach ($pkg in $edgefits) { Add-Note "installed=$pkg" }
if ($edgefits.Count -eq 0) { Add-Note "installed=<no ai.edgefit.diag* packages>" }

$installedVersion = $null
if ($installedExpected) {
    $dump = Invoke-Tool $adb @("-s", $Serial, "shell", "dumpsys", "package",
        $ExpectedPackage)
    if ($dump.Exit -eq 0) {
        # Android 16 prints versionCode and versionName on
        # separate dumpsys lines; match them independently.
        $codeLine = ($dump.Text -split "`r?`n") |
            Where-Object { $_ -match "versionCode=(\d+)" } |
            Select-Object -First 1
        if ($null -ne $codeLine -and
            $codeLine -match "versionCode=(\d+)") {
            $installedVersion = $Matches[1]
        }
    }
    if ($null -eq $installedVersion) {
        Add-Block "could not determine installed version of $ExpectedPackage"
    } else {
        Add-Note "installed_version=$installedVersion"
    }
}

# --- Gate 5: signing compatibility (read-only) ---

$cert = Invoke-Tool $apksigner @("verify", "--print-certs", $ApkPath)
$apkCert = $null
if ($cert.Exit -eq 0) {
    $shaLine = ($cert.Text -split "`r?`n") |
        Where-Object { $_ -match "SHA-256" } | Select-Object -First 1
    if ($null -ne $shaLine -and $shaLine -match "([0-9a-fA-F]{64})") {
        $apkCert = $Matches[1].ToUpper()
    }
}
if ($null -eq $apkCert) {
    Add-Block "could not determine APK signing certificate"
} elseif ($apkCert -ne $ExpectedCertSha256.ToUpper()) {
    Add-Block "signing certificate mismatch: APK $apkCert != expected $($ExpectedCertSha256.ToUpper())"
} else {
    Add-Note "signing=match"
}

# --- Gate 6: session/evidence inventory (read-only via run-as) ---

$SCRIPT:SessionCount = $null
$SCRIPT:EvidenceCount = $null
if ($installedExpected) {
    foreach ($kind in @("sessions", "evidence")) {
        $ls = Invoke-Tool $adb @("-s", $Serial, "shell", "run-as",
            $ExpectedPackage, "ls", "-1", "files/$kind")
        if ($ls.Exit -ne 0 -or $ls.Text -match "not debuggable|unknown package|Permission denied") {
            Add-Block "cannot inventory $kind for $ExpectedPackage (run-as failed; refusing to proceed blind)"
            break
        }
        $count = @($ls.Text -split "`r?`n" |
            Where-Object { $_.Trim() -match "\.json$" }).Count
        if ($kind -eq "sessions") { $SCRIPT:SessionCount = $count }
        else { $SCRIPT:EvidenceCount = $count }
    }
    if ($null -ne $SCRIPT:SessionCount) {
        Add-Note "inventory_sessions=$($SCRIPT:SessionCount)"
        Add-Note "inventory_evidence=$($SCRIPT:EvidenceCount)"
    }
} else {
    Add-Note "inventory=not-installed"
}

$otherBearsData = $false
foreach ($pkg in $otherEdgefits) {
    $ls = Invoke-Tool $adb @("-s", $Serial, "shell", "run-as", $pkg,
        "ls", "-1", "files/sessions")
    if ($ls.Exit -eq 0) {
        $n = @($ls.Text -split "`r?`n" |
            Where-Object { $_.Trim() -match "\.json$" }).Count
        Add-Note "other_identity=$pkg sessions=$n"
        if ($n -gt 0) { $otherBearsData = $true }
    } else {
        Add-Note "other_identity=$pkg inventory=unreadable"
    }
}

$dataBearing = ($null -ne $SCRIPT:SessionCount -and
    ($SCRIPT:SessionCount + $SCRIPT:EvidenceCount) -gt 0)

# --- Gate 7: verified backup (re-hash only; never writes) ---

$backupOk = $false
if ($dataBearing -or $IntendedOperation -in @("Uninstall", "ClearData")) {
    if ($BackupDir -eq "" -or -not (Test-Path $BackupDir)) {
        Add-Block "data-bearing install requires -BackupDir with a verifiable backup"
    } else {
        $manifest = Join-Path $BackupDir "MANIFEST.sha256"
        if (-not (Test-Path $manifest)) {
            Add-Block "backup manifest missing: $manifest"
        } else {
            $lines = Get-Content $manifest |
                Where-Object { $_.Trim() -ne "" }
            if ($lines.Count -eq 0) {
                Add-Block "backup manifest is empty"
            } else {
                $bad = 0
                foreach ($line in $lines) {
                    if ($line -notmatch "^([0-9a-fA-F]{64})\s+(.+)$") { $bad++; continue }
                    $file = Join-Path $BackupDir $Matches[2].Trim()
                    if (-not (Test-Path $file)) { $bad++; continue }
                    $actual = (Get-FileHash $file -Algorithm SHA256).Hash
                    if ($actual -ne $Matches[1].ToUpper()) { $bad++ }
                }
                if ($bad -gt 0) {
                    Add-Block "backup verification failed ($bad bad entries)"
                } else {
                    $backupOk = $true
                    Add-Note "backup=verified ($($lines.Count) files)"
                }
            }
        }
    }
} else {
    Add-Note "backup=not-required (no data at stake)"
}

# --- Gate 8: authorization for destructive / identity-changing operations ---

if ($IntendedOperation -in @("Uninstall", "ClearData")) {
    if (-not $AuthorizeDestructive) {
        Add-Block "intended $IntendedOperation requires -AuthorizeDestructive"
    }
    if ($dataBearing -and -not $backupOk) {
        Add-Block "intended $IntendedOperation on data-bearing install requires a verified backup"
    }
}
if ($IntendedOperation -eq "NewIdentityInstall" -and -not $AllowNewIdentity) {
    Add-Block "new-identity install requires -AllowNewIdentity (acknowledge the distinct app identity)"
}
if ($IntendedOperation -eq "InstallUpdate" -and -not $installedExpected) {
    Add-Block "InstallUpdate expects $ExpectedPackage already installed; use -IntendedOperation NewIdentityInstall for first installs"
}
if ($otherBearsData -and -not $AllowNewIdentity) {
    Add-Block "another EdgeFit identity ($($otherEdgefits -join ',')) holds session data; pass -AllowNewIdentity to acknowledge it stays separate"
}

# --- Verdict (Gate 10: fail-closed) ---

Write-Output "EF-SAFE-INSTALL-01 PREFLIGHT: $($IntendedOperation) $ExpectedPackage on $Serial"
foreach ($note in $SCRIPT:Notes) { Write-Output "  note: $note" }
Write-Output "  post-install checks (run after the owner-approved operation):"
Write-Output "    1. pm list packages --user $User | findstr edgefit (identity present)"
Write-Output "    2. run-as $ExpectedPackage ls files/sessions files/evidence (counts match pre-install inventory)"
Write-Output "    3. dumpsys package $ExpectedPackage (versionCode advanced as expected)"
Write-Output "    4. launch and confirm sessions visible; stop on any discrepancy"
if ($SCRIPT:Blocked.Count -gt 0) {
    Write-Output "RESULT: BLOCKED"
    foreach ($reason in $SCRIPT:Blocked) { Write-Output "  blocked: $reason" }
    Write-Output "No installation or destructive action was performed."
    exit 1
}
Write-Output "RESULT: PASS (authorization prerequisite only; no automatic installation)"
exit 0
