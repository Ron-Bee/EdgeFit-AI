# EdgeFit AI EF-ANDROID-RECOVERY-INSTALL-05 backup regression tests.
# Exercises EFRecoveryLib.ps1 with no adb and no device: binary
# capture runs a child writer process, archive checks use tar.exe
# on crafted fixtures, package parsing uses synthetic pm output.
# Exit 0 = all pass, 1 = failure.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File scripts/Test-EFRecoveryBackup01.ps1

$ErrorActionPreference = 'Stop'

$SCRIPT:HERE = $PSScriptRoot
. (Join-Path $SCRIPT:HERE 'EFRecoveryLib.ps1')
$SCRIPT:Pass = 0
$SCRIPT:Fail = 0
$SCRIPT:Failures = New-Object System.Collections.ArrayList

function Assert-True([bool]$Cond, [string]$Name, [string]$Detail = '') {
    if ($Cond) {
        $SCRIPT:Pass++
        Write-Output "  PASS $Name"
    }
    else {
        $SCRIPT:Fail++
        [void]$SCRIPT:Failures.Add($Name)
        Write-Output "  FAIL $Name $Detail"
    }
}

function New-TempDir {
    $root = Join-Path ([System.IO.Path]::GetTempPath()) 'ef-recbackup-test'
    if (-not (Test-Path $root)) { [void](New-Item -ItemType Directory $root) }
    $dir = Join-Path $root ([System.Guid]::NewGuid().ToString('N'))
    [void](New-Item -ItemType Directory $dir)
    return $dir
}

# Rewrites header-block 0's entry name and repairs the ustar
# checksum, yielding a LISTABLE tarball with an attacker-style path.
function Set-TarBlockName([string]$TarIn, [string]$TarOut, [string]$NewName) {
    $bytes = [IO.File]::ReadAllBytes($TarIn)
    for ($i = 0; $i -lt 100; $i++) { $bytes[$i] = 0 }
    $nb = [Text.Encoding]::ASCII.GetBytes($NewName)
    [Array]::Copy($nb, 0, $bytes, 0, [Math]::Min($nb.Length, 100))
    $sum = 0
    for ($i = 0; $i -lt 512; $i++) {
        if ($i -ge 148 -and $i -lt 156) { $sum += 32 }
        else { $sum += $bytes[$i] }
    }
    $oct = ([Convert]::ToString($sum, 8)).PadLeft(6, '0')
    $ob = [Text.Encoding]::ASCII.GetBytes($oct)
    [Array]::Copy($ob, 0, $bytes, 148, 6)
    $bytes[154] = 0
    $bytes[155] = 32
    [IO.File]::WriteAllBytes($TarOut, $bytes)
}

$Tmp = New-TempDir
Write-Output 'EF-RECOVERY-BACKUP-01 SELF-TEST'

# --- environment: tar.exe is a hard dependency (installer needs it too) ---
$tarCmd = Get-Command tar -ErrorAction SilentlyContinue
Assert-True ($null -ne $tarCmd) 'env.tar-present'
$psCmd = Get-Command powershell -ErrorAction SilentlyContinue
Assert-True ($null -ne $psCmd) 'env.powershell-present'
if (($null -eq $tarCmd) -or ($null -eq $psCmd)) {
    Write-Output 'FATAL: missing test tooling'
    exit 1
}
$tarExe = $tarCmd.Source
$psExe = $psCmd.Source

# --- deterministic non-ASCII binary fixture (crosses the 64KiB chunk) ---
$rng = New-Object System.Random 42
$fixture = New-Object byte[] 65537
$rng.NextBytes($fixture)
for ($i = 0; $i -lt 512; $i++) { $fixture[$i] = [byte]($i % 256) }
$fixtureFile = Join-Path $Tmp 'fixture.bin'
[IO.File]::WriteAllBytes($fixtureFile, $fixture)
$fixtureHash = (Get-FileHash $fixtureFile -Algorithm SHA256).Hash

# Child writer: raw binary stdout, line stderr, controlled exit.
$writer = Join-Path $Tmp 'writer.ps1'
Set-Content $writer @'
$bytes = [IO.File]::ReadAllBytes($args[0])
$raw = [Console]::OpenStandardOutput()
$raw.Write($bytes, 0, $bytes.Length)
$raw.Flush()
$line = $args[1]
for ($i = 0; $i -lt [int]$args[3]; $i++) { [Console]::Error.WriteLine($line) }
exit [int]$args[2]
'@

function Invoke-Writer([string]$OutFile, [string]$ErrLine, [int]$Exit, [int]$Repeat) {
    $argLine = '-NoProfile -ExecutionPolicy Bypass -File "' + $writer +
        '" "' + $fixtureFile + '" "' + $ErrLine + '" ' + $Exit +
        ' ' + $Repeat
    return Invoke-NativeBinary $psExe $argLine $OutFile 60
}

# 1. byte-exact binary capture incl. NUL + high bytes.
$r1 = Invoke-Writer (Join-Path $Tmp 'cap1.bin') 'STDERR-MARKER-1' 0 1
Assert-True ($r1.Exit -eq 0) 'bin.exit-zero'
Assert-True ((Get-FileHash (Join-Path $Tmp 'cap1.bin') -Algorithm SHA256).Hash -eq $fixtureHash) 'bin.byte-exact'
Assert-True ($r1.Bytes -eq 65537) 'bin.byte-count'

# 2. stderr (300KB >> pipe buffer) never leaks into stdout: proves
# concurrent draining instead of a read-then-read deadlock.
$big = 'E' * 1024
$r2 = Invoke-Writer (Join-Path $Tmp 'cap2.bin') $big 0 300
Assert-True ((Get-FileHash (Join-Path $Tmp 'cap2.bin') -Algorithm SHA256).Hash -eq $fixtureHash) 'stderr.stdout-clean'
Assert-True ($r2.Stderr.Length -gt 200000) 'stderr.captured'
Assert-True ($r2.Exit -eq 0) 'stderr.exit-zero'

# 3. exit code propagation.
$r3 = Invoke-Writer (Join-Path $Tmp 'cap3.bin') 'boom' 7 1
Assert-True ($r3.Exit -eq 7) 'exit.propagated'

# 4. start failure is a reserved code, never a throw.
$r4 = Invoke-NativeBinary 'Z:\nope\missing.exe' '' (Join-Path $Tmp 'cap4.bin') 10
Assert-True ($r4.Exit -eq 998) 'start.fail-closed'

# 5. text tool capture merges stderr without throwing under Stop.
$textFile = Join-Path $Tmp 'text.txt'
Set-Content $textFile @('TEXT-LINE-1', 'TEXT-LINE-2')
$rt = Invoke-ToolText $psExe @('-NoProfile', '-ExecutionPolicy', 'Bypass',
    '-File', $writer, $textFile, 'TOOLTEXT-MARKER', '0', '1')
$rtJoined = ($rt.Lines -join "`n")
Assert-True ($rt.Exit -eq 0) 'tooltext.exit-zero'
Assert-True (($rtJoined -match 'TEXT-LINE-1') -and ($rtJoined -match 'TOOLTEXT-MARKER')) 'tooltext.merged'

# 6. text tool exit code + missing executable.
$rt6 = Invoke-ToolText $psExe @('-NoProfile', '-ExecutionPolicy', 'Bypass',
    '-File', $writer, $textFile, 'x', '9', '1')
Assert-True ($rt6.Exit -eq 9) 'tooltext.exit-code'
$rt6b = Invoke-ToolText 'Z:\nope\missing.exe' @()
Assert-True ($rt6b.Exit -eq 999) 'tooltext.missing-exe'

# --- tarball fixtures (device-shaped layout, members archived
# --- individually exactly as the installer requests them) ---
$stage = Join-Path $Tmp 'stage'
foreach ($d in @('files\sessions', 'files\evidence', 'files\sessions-quarantine')) {
    [void](New-Item -ItemType Directory (Join-Path $stage $d) -Force)
}
Set-Content (Join-Path $stage 'files\sessions\SES-aaaaaaaaaaaaaaaa.json') '{"a":1,"u":"äöü"}'
Set-Content (Join-Path $stage 'files\sessions\SES-bbbbbbbbbbbbbbbb.json') '{"a":2}'
Set-Content (Join-Path $stage 'files\sessions\SES-cccccccccccccccc.json') '{"a":3}'
Set-Content (Join-Path $stage 'files\evidence\BENCH-20261029T010000Z-abcdef12.bench.json') '{"r":1}'
Set-Content (Join-Path $stage 'files\evidence\BENCH-20261029T010000Z-abcdef12.withdraw.json') '{"w":1}'
Set-Content (Join-Path $stage 'files\install.json') '{"install_id":"x"}'
$goodTar = Join-Path $Tmp 'good.tar'
& $tarExe -cf $goodTar -C $stage files/sessions files/evidence files/sessions-quarantine files/install.json | Out-Null
Assert-True ($LASTEXITCODE -eq 0) 'env.tar-create'
$members = @('files/sessions', 'files/evidence', 'files/sessions-quarantine', 'files/install.json')
$members3 = @('files/sessions', 'files/evidence', 'files/sessions-quarantine')
$expSes = @('SES-aaaaaaaaaaaaaaaa.json', 'SES-bbbbbbbbbbbbbbbb.json', 'SES-cccccccccccccccc.json')
$expEv = @('BENCH-20261029T010000Z-abcdef12.bench.json', 'BENCH-20261029T010000Z-abcdef12.withdraw.json')

# 7. valid nonempty backup accepted with exact counts.
$c7 = Test-BackupArchive $tarExe $goodTar $members $expSes $expEv
Assert-True ($c7.Ok) 'tar.accept-nonempty' $c7.Reason
Assert-True (($c7.Sessions.Count -eq 3) -and ($c7.Evidence.Count -eq 2)) 'tar.counts'

# 8. valid empty backup accepted (0/0 baseline, pre-marker install).
$stageEmpty = Join-Path $Tmp 'empty'
foreach ($d in @('files\sessions', 'files\evidence', 'files\sessions-quarantine')) {
    [void](New-Item -ItemType Directory (Join-Path $stageEmpty $d) -Force)
}
$emptyTar = Join-Path $Tmp 'empty.tar'
& $tarExe -cf $emptyTar -C $stageEmpty files/sessions files/evidence files/sessions-quarantine | Out-Null
$c8 = Test-BackupArchive $tarExe $emptyTar $members3 @() @()
Assert-True ($c8.Ok) 'tar.accept-empty' $c8.Reason

# 9. truncated archive rejected.
$truncBytes = [IO.File]::ReadAllBytes($goodTar)
$cut = [int]($truncBytes.Length * 0.4)
$truncFile = Join-Path $Tmp 'trunc.tar'
[IO.File]::WriteAllBytes($truncFile, $truncBytes[0..($cut - 1)])
$c9 = Test-BackupArchive $tarExe $truncFile $members $expSes $expEv
Assert-True ((-not $c9.Ok) -and ($c9.Reason -match 'list failed')) 'tar.reject-truncated' $c9.Reason

# 10. corrupt header rejected.
$badBytes = [IO.File]::ReadAllBytes($goodTar)
$badBytes[100] = ($badBytes[100] + 1) % 256
$badFile = Join-Path $Tmp 'bad.tar'
[IO.File]::WriteAllBytes($badFile, $badBytes)
$c10 = Test-BackupArchive $tarExe $badFile $members $expSes $expEv
Assert-True ((-not $c10.Ok) -and ($c10.Reason -match 'list failed')) 'tar.reject-corrupt' $c10.Reason

# 11. missing required entry rejected by name.
$c11 = Test-BackupArchive $tarExe $goodTar $members @('SES-aaaaaaaaaaaaaaaa.json', 'SES-dddddddddddddddd.json') $expEv
Assert-True ((-not $c11.Ok) -and ($c11.Reason -match 'dddddddddddddddd')) 'tar.reject-missing' $c11.Reason

# 11b. null inventory arguments create no phantom missing entries
# (the INSTALL-05 failure: `$null | Where-Object` runs once).
$c11b = Test-BackupArchive $tarExe $emptyTar $members3 $null $null
Assert-True ($c11b.Ok) 'tar.null-inventory-ok' $c11b.Reason

# 11c. blank inventory entries are dropped, never treated as files.
$c11c = Test-BackupArchive $tarExe $emptyTar $members3 @('', '  ') @()
Assert-True ($c11c.Ok) 'tar.blank-inventory-ok' $c11c.Reason
Assert-True ((@($c11c.Sessions | Where-Object { "$_".Trim() -eq '' }).Count -eq 0)) 'tar.no-blank-sessions'

# 12. missing requested member rejected.
$c12 = Test-BackupArchive $tarExe $emptyTar $members @() @()
Assert-True ((-not $c12.Ok) -and ($c12.Reason -match 'missing member')) 'tar.reject-missing-member' $c12.Reason

# 13. unexpected entry rejected.
[void](New-Item -ItemType Directory (Join-Path $stage 'files\sneaky') -Force)
Set-Content (Join-Path $stage 'files\sneaky\x.json') '{}'
$sneakyTar = Join-Path $Tmp 'sneaky.tar'
& $tarExe -cf $sneakyTar -C $stage files/sessions files/evidence files/sessions-quarantine files/install.json files/sneaky | Out-Null
$c13 = Test-BackupArchive $tarExe $sneakyTar $members $expSes $expEv
Assert-True ((-not $c13.Ok) -and ($c13.Reason -match 'unexpected')) 'tar.reject-unexpected' $c13.Reason

# 14a. parent-climbing path rejected (listable archive: the validator fires, not tar).
$evilTar = Join-Path $Tmp 'evil.tar'
Set-TarBlockName $goodTar $evilTar '../evil/'
$evilList = & $tarExe -tf $evilTar 2>$null
Assert-True (($LASTEXITCODE -eq 0) -and ($evilList -match '\.\.')) 'surgery.listable'
$c14a = Test-BackupArchive $tarExe $evilTar $members $expSes $expEv
Assert-True ((-not $c14a.Ok) -and ($c14a.Reason -match 'unsafe')) 'tar.reject-parent' $c14a.Reason

# 14b. absolute path rejected.
$absTar = Join-Path $Tmp 'abs.tar'
Set-TarBlockName $goodTar $absTar 'C:/evil/'
$c14b = Test-BackupArchive $tarExe $absTar $members $expSes $expEv
Assert-True ((-not $c14b.Ok) -and ($c14b.Reason -match 'unsafe')) 'tar.reject-absolute' $c14b.Reason

# 14c. mock adb for inventory tests: no device needed.
$mockAdb = Join-Path $Tmp 'mock-adb.ps1'
Set-Content $mockAdb @'
$joined = $args -join ' '
if ($joined -match 'boom') { Write-Output 'mock-adb: request failed'; exit 1 }
if ($joined -match 'files/sessions-quarantine') { exit 0 }
if ($joined -match 'files/evidence') { exit 0 }
if ($joined -match 'files/sessions') {
    Write-Output 'SES-aaaaaaaaaaaaaaaa.json'
    Write-Output 'SES-bbbbbbbbbbbbbbbb.json'
    exit 0
}
exit 0
'@

# 14d. empty inventory returns a real zero-length array, never $null
# (the INSTALL-05 producer: bare `return @()` collapses to $null).
$r14d = Get-AppList $mockAdb 'SERIAL-X' 'ai.edgefit.diag' 'evidence'
Assert-True ($r14d -is [array]) 'applist.empty-is-array'
Assert-True ($r14d.Count -eq 0) 'applist.empty-count-zero'
Assert-True ($null -ne $r14d) 'applist.empty-not-null'

# 14e. nonempty inventory passes names through exactly.
$r14e = Get-AppList $mockAdb 'SERIAL-X' 'ai.edgefit.diag' 'sessions'
Assert-True (($r14e.Count -eq 2) -and ($r14e[0] -eq 'SES-aaaaaaaaaaaaaaaa.json') -and ($r14e[1] -eq 'SES-bbbbbbbbbbbbbbbb.json')) 'applist.nonempty-exact'

# 14f. tool failure surfaces the inventory error (Fail stubbed).
function Fail([string]$FailMessage) { throw "FAIL-STUB:$FailMessage" }
$failFired = $false
try { Get-AppList $mockAdb 'SERIAL-X' 'ai.edgefit.diag' 'boom' | Out-Null }
catch { $failFired = ($_.Exception.Message -match 'FAIL-STUB.*run-as inventory') }
Assert-True $failFired 'applist.failure-surfaced'

# 14g. end-to-end: empty producer output feeds the verifier cleanly.
$chain = Test-BackupArchive $tarExe $emptyTar $members3 (Get-AppList $mockAdb 'SERIAL-X' 'ai.edgefit.diag' 'evidence') @()
Assert-True ($chain.Ok) 'chain.empty-ok' $chain.Reason

# 15. package parse keeps diagnostics out of identity names.
$pmLines = @(
    'package:ai.edgefit.diag',
    'Exception occurred while executing ''list'':',
    'java.lang.SecurityException: Shell does not have permission to access user 150',
    'package:ai.edgefit.diag.bench',
    '',
    '  package:ai.edgefit.diag  '
)
$g15 = Get-EdgeFitPackages $pmLines 'ai.edgefit.diag'
Assert-True ($g15.Found) 'pm.found'
Assert-True (($g15.Siblings.Count -eq 1) -and ($g15.Siblings[0] -eq 'ai.edgefit.diag.bench')) 'pm.siblings'
Assert-True (($g15.Diagnostics -join '|') -match 'SecurityException') 'pm.diags-kept'
Assert-True (@($g15.Siblings | Where-Object { $_ -match 'Error|Exception|java' }).Count -eq 0) 'pm.no-pollution'

# 16. absent package parses cleanly.
$g16 = Get-EdgeFitPackages @('List of nothing', '') 'ai.edgefit.diag'
Assert-True (-not $g16.Found) 'pm.absent'

# 17. library carries no mutating verbs.
$libText = Get-Content (Join-Path $SCRIPT:HERE 'EFRecoveryLib.ps1') -Raw
Assert-True ($libText -notmatch '(?i)\b(uninstall|exec-out|monkey|force-stop|reboot)\b|pm clear|adb push|install -[rd]\b') 'lib.zero-mutation'

# 18. installer: one in-place update, after backup verification,
# user-scoped pm, and a global fault trap for honest verdicts.
$inst = Get-Content (Join-Path $SCRIPT:HERE 'Invoke-EFRecoveryInstall01.ps1') -Raw
Assert-True (([regex]::Matches($inst, 'adb -s \$Serial install -r')).Count -eq 1) 'installer.single-install'
$iBackup = $inst.IndexOf('Test-BackupArchive')
$iInstall = $inst.IndexOf('install -r $ApkPath')
Assert-True (($iBackup -gt 0) -and ($iInstall -gt $iBackup)) 'installer.backup-before-install'
Assert-True (($inst -match "'pm', 'list',") -and ($inst -match "'packages', '--user'")) 'installer.user-scoped-pm'
Assert-True ($inst -match '(?m)^trap \{') 'installer.fault-trap'

Remove-Item $Tmp -Recurse -Force -ErrorAction SilentlyContinue

Write-Output ''
if ($SCRIPT:Fail -eq 0) {
    Write-Output "EF-RECOVERY-BACKUP-01 SELF-TEST: $($SCRIPT:Pass) passed, 0 failed"
    exit 0
}
else {
    Write-Output "EF-RECOVERY-BACKUP-01 SELF-TEST: $($SCRIPT:Pass) passed, $($SCRIPT:Fail) failed"
    foreach ($f in $SCRIPT:Failures) { Write-Output "  failed: $f" }
    exit 1
}
