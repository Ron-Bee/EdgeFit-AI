# EdgeFit AI EF-ANDROID-RECOVERY-INSTALL-05 shared recovery helpers.
# Binary-safe process capture, backup archive verification, and
# package-list parsing. Helpers here are read-only apart from the
# local artifact files they are explicitly asked to write; the
# update flow itself lives in the installer, never here.
# PowerShell 5.1 compatible. Dot-source from scripts/:
#   . (Join-Path $PSScriptRoot 'EFRecoveryLib.ps1')

# Runs a native process, streaming raw stdout bytes straight into
# $OutFile with no text decoding. Stderr is routed by cmd.exe to a
# side file (never a second pipe), so no async reader is needed and
# large error output cannot deadlock a full stdout pipe: with a
# single stdout pipe drained continuously, the child can always
# proceed. The copy itself runs as framework async I/O (no
# scriptblocks off-thread, which Windows PowerShell forbids), so a
# hung child still honors $TimeoutSec. Never throws: start, output,
# and timeout faults surface as reserved exit codes instead.
# Returns @{ Exit = [int]; Stderr = [string]; Bytes = [long] }.
# Exit is the process code, or 997 (local output fault),
# 998 (process start fault), 999 (timeout; killed).
function Invoke-NativeBinary(
    [string]$Exe,
    [string]$Arguments,
    [string]$OutFile,
    [int]$TimeoutSec = 120
) {
    $errFile = [IO.Path]::GetTempFileName()
    try {
        $probe = Get-Command $Exe -ErrorAction SilentlyContinue
        if (($null -eq $probe) -and (-not (Test-Path $Exe))) {
            return @{ Exit = 998; Stderr = "executable not found: $Exe"; Bytes = 0 }
        }
        $psi = New-Object System.Diagnostics.ProcessStartInfo
        $psi.FileName = 'cmd.exe'
        $psi.Arguments = '/c ""' + $Exe + '" ' + $Arguments +
            ' 2> "' + $errFile + '"'
        $psi.UseShellExecute = $false
        $psi.RedirectStandardOutput = $true
        $psi.RedirectStandardError = $true
        $psi.CreateNoWindow = $true
        $proc = New-Object System.Diagnostics.Process
        $proc.StartInfo = $psi
        try {
            $proc.Start() | Out-Null
        }
        catch {
            return @{ Exit = 998; Stderr = $_.Exception.Message; Bytes = 0 }
        }
        try {
            $fs = New-Object System.IO.FileStream(
                $OutFile, [System.IO.FileMode]::Create,
                [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
            try {
                $copyTask = $proc.StandardOutput.BaseStream.CopyToAsync($fs)
                $finished = $false
                try {
                    $finished = $copyTask.Wait($TimeoutSec * 1000)
                }
                catch {
                    return @{ Exit = 997;
                        Stderr = $_.Exception.Message; Bytes = 0 }
                }
                if (-not $finished) {
                    try { $proc.Kill() } catch { }
                    return @{ Exit = 999; Stderr = 'timed out'; Bytes = 0 }
                }
                if ($copyTask.IsFaulted) {
                    return @{ Exit = 997; Stderr = 'stream copy fault';
                        Bytes = 0 }
                }
            }
            finally { $fs.Close() }
        }
        catch {
            try { $proc.Kill() } catch { }
            return @{ Exit = 997; Stderr = $_.Exception.Message; Bytes = 0 }
        }
        $proc.WaitForExit(15000) | Out-Null
        $code = 999
        try { $code = $proc.ExitCode } catch { }
        $errText = ''
        try {
            $errText = [IO.File]::ReadAllText(
                $errFile, [Text.Encoding]::UTF8)
        }
        catch { }
        try {
            $shellErr = $proc.StandardError.ReadToEnd()
            if (-not [string]::IsNullOrWhiteSpace($shellErr)) {
                $errText += $shellErr
            }
        }
        catch { }
        try { $proc.Close() } catch { }
        $size = 0
        try { $size = (Get-Item $OutFile).Length } catch { }
        return @{ Exit = $code; Stderr = $errText; Bytes = $size }
    }
    finally {
        try { Remove-Item $errFile -Force -ErrorAction SilentlyContinue } catch { }
    }
}

# Verifies a device backup tarball against the baseline inventory.
# $RequestedMembers are the exact device paths handed to tar (each
# must appear; every listed entry must sit under one);
# $ExpectedSessions/$ExpectedEvidence are basenames that must be
# present. Rejects unreadable, truncated, and corrupt archives,
# missing members or entries, unexpected entries, and unsafe paths
# (absolute, drive-letter, or parent-climbing).
# Returns @{ Ok=[bool]; Reason=[string]; Sessions=[string[]];
#   Evidence=[string[]]; Entries=[int] }.
function Test-BackupArchive(
    [string]$TarExe,
    [string]$TarPath,
    [string[]]$RequestedMembers,
    [string[]]$ExpectedSessions,
    [string[]]$ExpectedEvidence
) {
    $ExpectedSessions = @($ExpectedSessions |
        Where-Object { $_ -ne $null -and "$_".Trim() -ne '' })
    $ExpectedEvidence = @($ExpectedEvidence |
        Where-Object { $_ -ne $null -and "$_".Trim() -ne '' })
    $fail = { param($why) return @{ Ok = $false; Reason = $why;
        Sessions = @(); Evidence = @(); Entries = 0; Quarantine = @() } }
    if ([string]::IsNullOrWhiteSpace($TarExe) -or -not (Test-Path $TarExe)) {
        return &$fail 'tar tool missing'
    }
    if (-not (Test-Path $TarPath)) { return &$fail 'tarball missing' }
    if ((Get-Item $TarPath).Length -eq 0) {
        return &$fail 'tarball empty'
    }
    $prevEA = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        try {
            $list = & $TarExe -tf $TarPath 2>$null
            $tarCode = $LASTEXITCODE
        }
        catch {
            return &$fail 'tar list fault (truncated or corrupt archive)'
        }
    }
    finally { $ErrorActionPreference = $prevEA }
    if ($tarCode -ne 0) {
        return &$fail 'tar list failed (truncated or corrupt archive)'
    }
    $sessions = @()
    $evidence = @()
    $entries = 0
    $quarantine = @()
    foreach ($raw in $list) {
        $entry = "$raw".Trim() -replace '^\./', ''
        if ($entry -eq '') { continue }
        if ($entry -match '^(?:[A-Za-z]:|/|\\\\)' -or $entry -match '\.\.') {
            return &$fail "unsafe archive path: $entry"
        }
        $confined = $false
        foreach ($member in $RequestedMembers) {
            if ($entry -eq $member -or $entry.StartsWith($member + '/')) {
                $confined = $true
                break
            }
        }
        if (-not $confined) {
            return &$fail "unexpected archive entry: $entry"
        }
        $entries++
        if ($entry -match '^files/sessions/SES-.*\.json$') {
            $sessions += ($entry -split '/')[-1]
        }
        elseif ($entry -match '^files/evidence/.*\.json$') {
            $evidence += ($entry -split '/')[-1]
        }
        elseif ($entry -match '^files/sessions-quarantine/.*\.json$') {
            $quarantine += ($entry -split '/')[-1]
        }
    }
    $seenNames = @(
        foreach ($raw in $list) {
            $normalized = "$raw".Trim() -replace '^\./', ''
            if ($normalized -ne '') { $normalized }
        })
    foreach ($member in $RequestedMembers) {
        $covered = @($seenNames | Where-Object {
            $_ -eq $member -or $_ -eq ($member + '/') -or
            $_.StartsWith($member + '/') }).Count -gt 0
        if (-not $covered) {
            return &$fail "backup missing member: $member"
        }
    }
    $missing = @($ExpectedSessions | Where-Object {
        $sessions -notcontains $_ })
    if ($missing.Count -gt 0) {
        return &$fail ("backup missing sessions: " +
            (($missing | Select-Object -First 3) -join ', '))
    }
    $missingEv = @($ExpectedEvidence | Where-Object {
        $evidence -notcontains $_ })
    if ($missingEv.Count -gt 0) {
        return &$fail ("backup missing evidence: " +
            (($missingEv | Select-Object -First 3) -join ', '))
    }
    return @{ Ok = $true; Reason = ''; Sessions = $sessions;
        Evidence = $evidence; Entries = $entries; Quarantine = $quarantine }
}

# Interprets an update command's exit code plus output lines. A
# transfer-progress line is noise: neither success nor failure.
# Explicit Failure dominates (even with exit 0, for old tools);
# Success with exit 0 passes; anything else is Indeterminate and
# the caller must reconcile, never assume.
# Returns @{ Status = 'Success'|'Failed'|'Indeterminate'; Detail = [string] }.
function Get-InstallResult(
    [int]$ExitCode,
    [string[]]$Lines
) {
    $text = @($Lines | ForEach-Object { "$_" })
    $failLine = @($text | Where-Object { $_ -match 'Failure' } |
        Select-Object -First 1)
    $hasSuccess = @($text | Where-Object { $_ -match 'Success' }).Count -gt 0
    if ($failLine.Count -gt 0) {
        return @{ Status = 'Failed'; Detail = "$($failLine[0])".Trim() }
    }
    if ($ExitCode -ne 0) {
        if ($hasSuccess) {
            return @{ Status = 'Indeterminate';
                Detail = "exit $ExitCode with Success text (contradictory)" }
        }
        $tail = ($text | Select-Object -Last 3) -join ' | '
        return @{ Status = 'Failed'; Detail = "exit $ExitCode : $tail" }
    }
    if ($hasSuccess) {
        return @{ Status = 'Success'; Detail = 'Android reported Success' }
    }
    return @{ Status = 'Indeterminate'; Detail = 'no Success or Failure in output' }
}

# Runs a native tool for TEXT output, merging stderr as text lines.
# Neutralizes the Windows PowerShell 5.1 trap where any native
# stderr line is terminating under $ErrorActionPreference='Stop':
# the preference is lowered only for the call itself. Never throws
# for tool behavior: missing executables surface as Exit 999.
# Returns @{ Exit = [int]; Lines = [string[]] }.
function Invoke-ToolText(
    [string]$Exe,
    [string[]]$ToolArgs
) {
    $prevEA = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        try {
            $out = & $Exe @ToolArgs 2>&1 | ForEach-Object { "$_" }
            return @{ Exit = $LASTEXITCODE; Lines = @($out) }
        }
        catch {
            return @{ Exit = 999; Lines = @($_.Exception.Message) }
        }
    }
    finally { $ErrorActionPreference = $prevEA }
}

# Parses `pm list packages` output for EdgeFit identities. ONLY
# `package:` lines naming ai.edgefit.diag* become identities; every
# other line (errors, SecurityExceptions, stack frames, blanks)
# lands in Diagnostics and can never be mistaken for a name.
# Returns @{ Found=[bool]; Siblings=[string[]]; Diagnostics=[string[]] }.
function Get-EdgeFitPackages(
    [string[]]$Lines,
    [string]$ExpectedAppId
) {
    $pkgs = @()
    $diags = @()
    foreach ($raw in $Lines) {
        $line = "$raw".Trim()
        if ($line -eq '') { continue }
        if ($line -match '^package:\s*(\S+)\s*$') {
            $name = $Matches[1]
            if ($name -like 'ai.edgefit.diag*') { $pkgs += $name }
            else { $diags += "unrelated package line: $line" }
            continue
        }
        $diags += $line
    }
    $found = $pkgs -contains $ExpectedAppId
    $sibs = @($pkgs | Where-Object { $_ -ne $ExpectedAppId })
    return @{ Found = $found; Siblings = $sibs; Diagnostics = $diags }
}

# Lists one app-private directory via run-as. ALWAYS returns a real
# string array, even when empty: a bare `return @()` would collapse
# to $null for the caller, and `$null | Where-Object {...}` runs
# once with $_=$null, fabricating phantom missing-file entries
# downstream (INSTALL-05). Text capture goes through
# Invoke-ToolText so tool stderr can never throw here.
function Get-AppList(
    [string]$AdbExe,
    [string]$DeviceSerial,
    [string]$AppId,
    [string]$Dir
) {
    $r = Invoke-ToolText $AdbExe @('-s', $DeviceSerial, 'shell',
        "run-as $AppId ls files/$Dir")
    if ($r.Exit -ne 0) {
        Fail "run-as inventory of files/$Dir failed (debuggable build required)"
    }
    return ,@($r.Lines | ForEach-Object { "$_".Trim() } |
        Where-Object { $_ -ne '' })
}

# Verifies the Android consent activation against explicit
# Collection A approval evidence
# (EF-ANDROID-CONSENT-INSTALL-GATE-01). Pure string checks: the
# caller fetches the backend evidence (fetch + show + byte-true
# blob hash) and passes the observed values in. Returns $true
# only when every check holds; otherwise $false with a reason.
# Fails closed: any missing or inconsistent input blocks.
function Test-EFConsentActivation(
    [string]$ContractSource,
    [string]$ExpectedVersion,
    [string]$ExpectedSha256,
    [string]$ApprovalDoc,
    [string]$PolicyBlobSha256,
    [ref]$Reason
) {
    $Reason.Value = ''
    if ([string]::IsNullOrWhiteSpace($ContractSource)) {
        $Reason.Value = 'GlobalContract.kt unreadable'; return $false
    }
    if ([string]::IsNullOrWhiteSpace($ExpectedVersion)) {
        $Reason.Value = 'expected policy version missing'; return $false
    }
    if ($ExpectedSha256 -notmatch '^[0-9A-Fa-f]{64}$') {
        $Reason.Value = 'expected policy SHA malformed'; return $false
    }
    $sha = $ExpectedSha256.ToUpper()
    $escVer = [regex]::Escape($ExpectedVersion)
    if ($ContractSource -notmatch ('CONSENT_POLICY_VERSION\s*=\s*"' + $escVer + '"')) {
        $Reason.Value = "policy version is not $ExpectedVersion"; return $false
    }
    if ($ContractSource -notmatch 'CONSENT_POLICY_APPROVED\s*=\s*true') {
        $Reason.Value = 'consent flag is not approved-true'; return $false
    }
    if ($ContractSource -notmatch 'CONSENT_SCOPES\s*=\s*listOf\("aggregates"\)') {
        $Reason.Value = 'consent scopes are not aggregates-only'; return $false
    }
    if ($ContractSource.ToUpper().IndexOf($sha, [System.StringComparison]::Ordinal) -lt 0) {
        $Reason.Value = 'approved policy SHA not cited in source'; return $false
    }
    if ([string]::IsNullOrWhiteSpace($ApprovalDoc)) {
        $Reason.Value = 'approval evidence missing'; return $false
    }
    if ($ApprovalDoc -notmatch '\-\s*\[x\]\s*A\.\s*COLLECTION APPROVED') {
        $Reason.Value = 'Collection A approval not recorded'; return $false
    }
    if ($ApprovalDoc -match '\[x\]\s*B\.\s*PUBLICATION APPROVED') {
        $Reason.Value = 'Publication B must remain unapproved'; return $false
    }
    if ($ApprovalDoc -notmatch '\-\s*\[\s\]\s*B\.\s*PUBLICATION APPROVED') {
        $Reason.Value = 'Publication B unapproved record missing'; return $false
    }
    if ($ApprovalDoc.ToUpper().IndexOf($sha, [System.StringComparison]::Ordinal) -lt 0) {
        $Reason.Value = 'approval doc does not cite the expected policy SHA'; return $false
    }
    if ([string]::IsNullOrWhiteSpace($PolicyBlobSha256) -or
        $PolicyBlobSha256.ToUpper() -ne $sha) {
        $Reason.Value = 'policy blob hash does not match the approval'; return $false
    }
    return $true
}
