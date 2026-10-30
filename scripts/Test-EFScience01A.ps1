# EdgeFit AI EF-SCIENCE-01A self-test.
# Black-box: builds fixtures in temp, invokes the analysis tool as a
# child process, asserts exit codes and report values. No network,
# no repo writes outside temp. Exit 0 = all pass, 1 = failure.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File scripts/Test-EFScience01A.ps1

$ErrorActionPreference = 'Stop'

# Normalize extended-length (\\?\) prefixes: PowerShell 5.1
# Join-Path cannot parse them.
$SCRIPT:HERE = ($PSScriptRoot -replace '^[\\/][\\/]\?[\\/]', '')
$SCRIPT:ROOT = Split-Path $SCRIPT:HERE -Parent
$SCRIPT:TOOL = Join-Path $SCRIPT:HERE 'Invoke-EFScience01A.ps1'

function Get-EFTempRoot {
    $candidates = New-Object System.Collections.ArrayList
    if (-not [string]::IsNullOrEmpty($env:EF_SCIENCE_TMP)) {
        [void]$candidates.Add($env:EF_SCIENCE_TMP)
    }
    [void]$candidates.Add([System.IO.Path]::GetTempPath())
    foreach ($base in $candidates) {
        try {
            $probe = Join-Path $base 'ef-science-01a-write-probe'
            [System.IO.File]::WriteAllText($probe, 'probe',
                [System.Text.Encoding]::UTF8)
            [System.IO.File]::Delete($probe)
            return $base
        } catch {
            continue
        }
    }
    throw 'no writable temp directory (set EF_SCIENCE_TMP)'
}

$SCRIPT:TEMP = Join-Path (Get-EFTempRoot) 'ef-science-01a'
$SCRIPT:PASS = 0
$SCRIPT:FAIL = New-Object System.Collections.ArrayList

function Write-EFPass {
    param([string]$Name)
    $SCRIPT:PASS++
    Write-Host "  PASS $Name"
}

function Write-EFFail {
    param([string]$Name, [string]$Detail)
    [void]$SCRIPT:FAIL.Add("${Name}: $Detail")
    Write-Host "  FAIL ${Name}: $Detail"
}

function Assert-EFTrue {
    param([string]$Name, [bool]$Cond, [string]$Detail)
    if ($Cond) { Write-EFPass $Name } else { Write-EFFail $Name $Detail }
}

function Assert-EFNear {
    param([string]$Name, $Got, [double]$Want, [double]$Tol = 1e-6)
    if ($null -eq $Got) {
        Write-EFFail $Name "got null, want $Want"
        return
    }
    $diff = [Math]::Abs([double]$Got - $Want)
    if ($diff -le $Tol) { Write-EFPass $Name }
    else { Write-EFFail $Name "got $Got, want $Want" }
}

function Format-EFDec {
    param([double]$Value)
    $text = $Value.ToString('G17',
        [System.Globalization.CultureInfo]::InvariantCulture)
    if ($text -notmatch '[.eE]') { $text += '.0' }
    return $text
}

function New-EFFixture {
    param(
        [string]$RunId,
        [string]$Collected,
        [int]$GenMs,
        [int]$GenTokens = 64,
        [int]$PrefillMs = 1150,
        [int]$PrefillTokens = 23,
        [string]$Status = 'COMPLETED',
        [string]$Error = '',
        [string]$Thermal = 'none',
        [int]$Threads = 4,
        [int]$Ctx = 512,
        [int]$Predict = 64,
        [string]$ModelSha = '6f85a640a97cf2bf5b8e764087b1e83da0fdb51d7c9fab7d0fece9385611df83',
        [string]$PromptSha = '36104db0657a294048410f9043d4efc8510a77088ca1e2f17c0b6c8359c44382',
        [string]$Variant = 'benchmark',
        [string]$NativeOpt = '-O3 -g',
        [double]$TpsOverride = [double]::NaN
    )
    $tpsGen = $GenTokens * 1000.0 / $GenMs
    $tpsPrefill = $PrefillTokens * 1000.0 / $PrefillMs
    if (-not [double]::IsNaN($TpsOverride)) { $tpsGen = $TpsOverride }
    # Always emit a decimal point so fixtures parse as
    # Decimal, exactly like on-device artifacts.
    $tpsGenText = Format-EFDec $tpsGen
    $tpsPrefillText = Format-EFDec $tpsPrefill
    $errJson = 'null'
    if (-not [string]::IsNullOrEmpty($Error)) {
        $errJson = '"' + $Error + '"'
    }
    $xbuild = ''
    if ($Variant -ne '') {
        $xbuild = ',"x_native_build":{"native_opt":"' + $NativeOpt +
            '","variant":"' + $Variant + '"}'
    }
    return @"
{"contract":"edgefit-bench-result","contract_version":1,"run_id":"$RunId","device":{"profile_ref":"$('d' * 64)","model":"SM-S908U","sdk_int":36,"supported_abis":["arm64-v8a"],"logical_processors":8,"total_ram_bytes":7571582976,"provenance":"platform_reported"},"model":{"display_name":"mock.gguf","size_bytes":807694464,"sha256":"$ModelSha","quantization":"2","metadata":{}},"runtime":{"impl":"llama.cpp","version":"0.7.0-dev","backend":"cpu","abi":"arm64-v8a"},"config":{"n_ctx":$Ctx,"n_threads":$Threads,"n_predict":$Predict,"temperature":0.0,"sampler":"greedy","seed":42,"timeout_ms":600000},"workload":{"id":"edgefit-prompt-v1","prompt_sha256":"$PromptSha","prompt_chars":118},"measurements":{"load_ms":1358,"prefill_ms":$PrefillMs,"prefill_tokens":$PrefillTokens,"gen_ms":$GenMs,"generated_tokens":$GenTokens,"tps_gen":$tpsGenText,"tps_prefill":$tpsPrefillText,"eos_stop":false,"mem":{"pss_before_kb":94130,"pss_after_kb":182638,"alloc_delta_bytes":2730032,"method":"debug-memoryinfo+mallinfo2"},"thermal":{"before":"$Thermal","mid":"$Thermal","after":"$Thermal","available":true}},"status":"$Status","error":$errJson,"provenance":"on_device_measurement","app_version":"0.2.0","collected_at_utc":"$Collected"$xbuild}
"@
}

function New-EFCase {
    param([string]$Name)
    $dir = Join-Path $SCRIPT:TEMP $Name
    if (Test-Path $dir) { Remove-Item $dir -Recurse -Force }
    [void](New-Item $dir -ItemType Directory -Force)
    return $dir
}

function Invoke-EFTool {
    param([string]$Dir, [string]$Extra = '')
    $out = Join-Path $Dir 'report.json'
    $args = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
        $SCRIPT:TOOL, '-ArtifactPath', $Dir, '-OutPath', $out)
    if ($Extra -ne '') { $args += $Extra.Split(' ') }
    & "$PSHOME\powershell.exe" @args | Out-Null
    return @{ Code = $LASTEXITCODE; Out = $out }
}

function Read-EFReport {
    param([string]$Path)
    return (Get-Content $Path -Raw -Encoding UTF8 | ConvertFrom-Json)
}

# ---- 1. valid cohort statistics vs hand-computed values ----

$dir = New-EFCase 'stats'
$gen = @(6400, 3200, 1600)
$expect = @(10.0, 20.0, 40.0)
for ($i = 0; $i -lt 3; $i++) {
    $rid = 'BENCH-20261028T01000{0}Z-aaaa111{0}' -f $i
    $ts = '2026-10-28T01:00:0{0}Z' -f $i
    [System.IO.File]::WriteAllText(
        (Join-Path $dir "run$i.json"),
        (New-EFFixture -RunId $rid -Collected $ts -GenMs $gen[$i]),
        [System.Text.Encoding]::UTF8)
}
$r = Invoke-EFTool $dir
Assert-EFTrue 'stats.exit0' ($r.Code -eq 0) "exit=$($r.Code)"
if ($r.Code -eq 0 -and (Test-Path $r.Out)) {
    $rep = Read-EFReport $r.Out
    Assert-EFTrue 'stats.oneCohort' ($rep.cohorts.Count -eq 1) (
        "cohorts=$($rep.cohorts.Count)")
    $s = $rep.cohorts[0].gen_stats
    Assert-EFTrue 'stats.n3' ($s.n -eq 3) "n=$($s.n)"
    Assert-EFNear 'stats.mean' $s.mean 23.333333333333332
    Assert-EFNear 'stats.median' $s.median 20.0
    Assert-EFNear 'stats.min' $s.min 10.0
    Assert-EFNear 'stats.max' $s.max 40.0
    Assert-EFNear 'stats.sd' $s.sd_sample 15.275252316519467
    Assert-EFNear 'stats.cv' $s.cv 0.6546536707076915
    Assert-EFTrue 'stats.interval' (
        $s.interval_method -match 'not a confidence interval') (
        "$($s.interval_method)")
}

# ---- 2. duplicate run_id refused ----

$dir = New-EFCase 'duprun'
$fx = New-EFFixture -RunId 'BENCH-20261028T010000Z-aaaa1111' `
    -Collected '2026-10-28T01:00:00Z' -GenMs 6400
[System.IO.File]::WriteAllText((Join-Path $dir 'a.json'), $fx,
    [System.Text.Encoding]::UTF8)
[System.IO.File]::WriteAllText((Join-Path $dir 'b.json'), $fx,
    [System.Text.Encoding]::UTF8)
$r = Invoke-EFTool $dir
Assert-EFTrue 'duprun.exit2' ($r.Code -eq 2) "exit=$($r.Code)"

# ---- 3. failed run excluded with reason ----

$dir = New-EFCase 'failed'
[System.IO.File]::WriteAllText((Join-Path $dir 'ok.json'),
    (New-EFFixture -RunId 'BENCH-20261028T010000Z-aaaa1111' `
        -Collected '2026-10-28T01:00:00Z' -GenMs 6400),
    [System.Text.Encoding]::UTF8)
[System.IO.File]::WriteAllText((Join-Path $dir 'bad.json'),
    (New-EFFixture -RunId 'BENCH-20261028T020000Z-bbbb2222' `
        -Collected '2026-10-28T02:00:00Z' -GenMs 6400 `
        -Status 'FAILED' -Error 'thermal state unsafe: severe'),
    [System.Text.Encoding]::UTF8)
$r = Invoke-EFTool $dir
Assert-EFTrue 'failed.exit0' ($r.Code -eq 0) "exit=$($r.Code)"
if ($r.Code -eq 0 -and (Test-Path $r.Out)) {
    $rep = Read-EFReport $r.Out
    Assert-EFTrue 'failed.oneExcluded' (
        $rep.excluded.Count -eq 1) "excluded=$($rep.excluded.Count)"
    Assert-EFTrue 'failed.reason' (
        $rep.excluded[0].reason -match 'severe') (
        "$($rep.excluded[0].reason)")
    Assert-EFTrue 'failed.oneValid' (
        $rep.valid_runs.Count -eq 1) "valid=$($rep.valid_runs.Count)"
}

# ---- 4. model hash mismatch: separate cohorts, no comparison ----

$dir = New-EFCase 'modelhash'
[System.IO.File]::WriteAllText((Join-Path $dir 'a.json'),
    (New-EFFixture -RunId 'BENCH-20261028T010000Z-aaaa1111' `
        -Collected '2026-10-28T01:00:00Z' -GenMs 6400),
    [System.Text.Encoding]::UTF8)
[System.IO.File]::WriteAllText((Join-Path $dir 'b.json'),
    (New-EFFixture -RunId 'BENCH-20261028T020000Z-bbbb2222' `
        -Collected '2026-10-28T02:00:00Z' -GenMs 3200 `
        -ModelSha ('bb' * 32)),
    [System.Text.Encoding]::UTF8)
$r = Invoke-EFTool $dir
Assert-EFTrue 'modelhash.exit0' ($r.Code -eq 0) "exit=$($r.Code)"
if ($r.Code -eq 0 -and (Test-Path $r.Out)) {
    $rep = Read-EFReport $r.Out
    Assert-EFTrue 'modelhash.twoCohorts' (
        $rep.cohorts.Count -eq 2) "cohorts=$($rep.cohorts.Count)"
    Assert-EFTrue 'modelhash.noCompare' (
        $rep.comparisons.Count -eq 0) (
        "comparisons=$($rep.comparisons.Count)")
}

# ---- 5. build mismatch splits cohort, two fields differ ----

$dir = New-EFCase 'build'
[System.IO.File]::WriteAllText((Join-Path $dir 'a.json'),
    (New-EFFixture -RunId 'BENCH-20261028T010000Z-aaaa1111' `
        -Collected '2026-10-28T01:00:00Z' -GenMs 6400),
    [System.Text.Encoding]::UTF8)
[System.IO.File]::WriteAllText((Join-Path $dir 'b.json'),
    (New-EFFixture -RunId 'BENCH-20261028T020000Z-bbbb2222' `
        -Collected '2026-10-28T02:00:00Z' -GenMs 3200 `
        -Variant ''),
    [System.Text.Encoding]::UTF8)
$r = Invoke-EFTool $dir
Assert-EFTrue 'build.exit0' ($r.Code -eq 0) "exit=$($r.Code)"
if ($r.Code -eq 0 -and (Test-Path $r.Out)) {
    $rep = Read-EFReport $r.Out
    Assert-EFTrue 'build.twoCohorts' ($rep.cohorts.Count -eq 2) (
        "cohorts=$($rep.cohorts.Count)")
    Assert-EFTrue 'build.oneCompare' (
        $rep.comparisons.Count -eq 1) "comparisons=$($rep.comparisons.Count)"
    Assert-EFTrue 'build.notIsolated' (
        $rep.comparisons[0].isolated_single_field -eq $false) (
        'multi-field diff claimed isolated')
}

# ---- 6. single config field differs: isolated flag true ----

$dir = New-EFCase 'threads'
[System.IO.File]::WriteAllText((Join-Path $dir 'a.json'),
    (New-EFFixture -RunId 'BENCH-20261028T010000Z-aaaa1111' `
        -Collected '2026-10-28T01:00:00Z' -GenMs 6400 -Threads 4),
    [System.Text.Encoding]::UTF8)
[System.IO.File]::WriteAllText((Join-Path $dir 'b.json'),
    (New-EFFixture -RunId 'BENCH-20261028T020000Z-bbbb2222' `
        -Collected '2026-10-28T02:00:00Z' -GenMs 3200 -Threads 8),
    [System.Text.Encoding]::UTF8)
$r = Invoke-EFTool $dir
Assert-EFTrue 'threads.exit0' ($r.Code -eq 0) "exit=$($r.Code)"
if ($r.Code -eq 0 -and (Test-Path $r.Out)) {
    $rep = Read-EFReport $r.Out
    $c = $rep.comparisons[0]
    Assert-EFTrue 'threads.isolated' (
        $c.isolated_single_field -eq $true) 'expected isolated flag'
    Assert-EFTrue 'threads.field' (
        $c.differing_fields.Count -eq 1 -and
        $c.differing_fields[0] -ceq 'n_threads') (
        "$($c.differing_fields -join ',')")
    Assert-EFNear 'threads.deltaMedian' $c.delta_median_gen_abs 10.0
    Assert-EFNear 'threads.deltaPct' $c.delta_median_gen_pct 100.0
    Assert-EFTrue 'threads.note' ($c.note -match 'does not prove cause') (
        "$($c.note)")
}

# ---- 7. tampered throughput refused ----

$dir = New-EFCase 'tampers'
[System.IO.File]::WriteAllText((Join-Path $dir 'a.json'),
    (New-EFFixture -RunId 'BENCH-20261028T010000Z-aaaa1111' `
        -Collected '2026-10-28T01:00:00Z' -GenMs 6400 `
        -TpsOverride 99.9),
    [System.Text.Encoding]::UTF8)
$r = Invoke-EFTool $dir
Assert-EFTrue 'tampers.exit2' ($r.Code -eq 2) "exit=$($r.Code)"

# ---- 8. single run: null spread, warning ----

$dir = New-EFCase 'single'
[System.IO.File]::WriteAllText((Join-Path $dir 'a.json'),
    (New-EFFixture -RunId 'BENCH-20261028T010000Z-aaaa1111' `
        -Collected '2026-10-28T01:00:00Z' -GenMs 6400),
    [System.Text.Encoding]::UTF8)
$r = Invoke-EFTool $dir
Assert-EFTrue 'single.exit0' ($r.Code -eq 0) "exit=$($r.Code)"
if ($r.Code -eq 0 -and (Test-Path $r.Out)) {
    $rep = Read-EFReport $r.Out
    Assert-EFTrue 'single.sdNull' (
        $null -eq $rep.cohorts[0].gen_stats.sd_sample) 'sd not null'
    Assert-EFTrue 'single.warn' ($rep.warnings.Count -ge 1) 'no warning'
}

# ---- 9. missing metadata refused ----

$dir = New-EFCase 'missing'
[System.IO.File]::WriteAllText((Join-Path $dir 'a.json'),
    '{"contract":"edgefit-bench-result","contract_version":1}',
    [System.Text.Encoding]::UTF8)
$r = Invoke-EFTool $dir
Assert-EFTrue 'missing.exit2' ($r.Code -eq 2) "exit=$($r.Code)"

# ---- 10. malformed JSON refused ----

$dir = New-EFCase 'malformed'
[System.IO.File]::WriteAllText((Join-Path $dir 'a.json'), '{oops',
    [System.Text.Encoding]::UTF8)
$r = Invoke-EFTool $dir
Assert-EFTrue 'malformed.exit2' ($r.Code -eq 2) "exit=$($r.Code)"

# ---- 11. prediction error accepted when strictly earlier ----

$dir = New-EFCase 'predok'
[System.IO.File]::WriteAllText((Join-Path $dir 'a.json'),
    (New-EFFixture -RunId 'BENCH-20261028T010000Z-aaaa1111' `
        -Collected '2026-10-28T01:00:00Z' -GenMs 3200),
    [System.Text.Encoding]::UTF8)
$pred = '{"predictions":[{"run_id":"BENCH-20261028T010000Z-aaaa1111",' +
    '"predicted_gen_tps":15.0,"predicted_prefill_tps":23.0,' +
    '"predicted_at_utc":"2026-10-28T00:00:00Z"}]}'
$predPath = Join-Path $dir 'pred.json'
[System.IO.File]::WriteAllText($predPath, $pred,
    [System.Text.Encoding]::UTF8)
# NOTE: pred.json is not an artifact; run tool on a.json only.
$out = Join-Path $dir 'report.json'
& "$PSHOME\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File `
    $SCRIPT:TOOL -ArtifactPath (Join-Path $dir 'a.json') `
    -OutPath $out -PredictionsPath $predPath | Out-Null
Assert-EFTrue 'predok.exit0' ($LASTEXITCODE -eq 0) "exit=$LASTEXITCODE"
if ((Test-Path $out)) {
    $rep = Read-EFReport $out
    $e = $rep.cohorts[0].prediction_errors[0]
    Assert-EFNear 'predok.signed' $e.gen_signed 5.0
    Assert-EFNear 'predok.pct' $e.gen_pct 33.333333333333336
}

# ---- 12. same-second prediction does not qualify ----

$dir = New-EFCase 'predlate'
[System.IO.File]::WriteAllText((Join-Path $dir 'a.json'),
    (New-EFFixture -RunId 'BENCH-20261028T010000Z-aaaa1111' `
        -Collected '2026-10-28T01:00:00Z' -GenMs 3200),
    [System.Text.Encoding]::UTF8)
$pred = '{"predictions":[{"run_id":"BENCH-20261028T010000Z-aaaa1111",' +
    '"predicted_gen_tps":15.0,"predicted_prefill_tps":23.0,' +
    '"predicted_at_utc":"2026-10-28T01:00:00Z"}]}'
$predPath = Join-Path $dir 'pred.json'
[System.IO.File]::WriteAllText($predPath, $pred,
    [System.Text.Encoding]::UTF8)
$out = Join-Path $dir 'report.json'
& "$PSHOME\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File `
    $SCRIPT:TOOL -ArtifactPath (Join-Path $dir 'a.json') `
    -OutPath $out -PredictionsPath $predPath | Out-Null
Assert-EFTrue 'predlate.exit0' ($LASTEXITCODE -eq 0) "exit=$LASTEXITCODE"
if ((Test-Path $out)) {
    $rep = Read-EFReport $out
    Assert-EFTrue 'predlate.excluded' (
        $rep.excluded.Count -eq 1) (
        "excluded=$($rep.excluded.Count)")
    Assert-EFTrue 'predlate.noErrors' (
        $rep.cohorts[0].prediction_errors.Count -eq 0) 'error computed'
}

# ---- 13. manifest match ok, mismatch fatal ----

$dir = New-EFCase 'manifest'
$fx = New-EFFixture -RunId 'BENCH-20261028T010000Z-aaaa1111' `
    -Collected '2026-10-28T01:00:00Z' -GenMs 6400
$ap = Join-Path $dir 'a.json'
[System.IO.File]::WriteAllText($ap, $fx, [System.Text.Encoding]::UTF8)
$sha = (Get-FileHash $ap -Algorithm SHA256).Hash.ToLower()
[System.IO.File]::WriteAllText((Join-Path $dir 'm.tsv'), "a.json $sha",
    [System.Text.Encoding]::UTF8)
$out = Join-Path $dir 'report.json'
& "$PSHOME\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File `
    $SCRIPT:TOOL -ArtifactPath $ap -OutPath $out `
    -ManifestPath (Join-Path $dir 'm.tsv') | Out-Null
Assert-EFTrue 'manifest.ok' ($LASTEXITCODE -eq 0) "exit=$LASTEXITCODE"
[System.IO.File]::WriteAllText((Join-Path $dir 'bad.tsv'),
    "a.json $('0' * 64)", [System.Text.Encoding]::UTF8)
& "$PSHOME\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File `
    $SCRIPT:TOOL -ArtifactPath $ap -OutPath $out `
    -ManifestPath (Join-Path $dir 'bad.tsv') | Out-Null
Assert-EFTrue 'manifest.bad' ($LASTEXITCODE -eq 2) "exit=$LASTEXITCODE"

# ---- 14. reproducibility: identical bytes modulo timestamp ----

$dir = New-EFCase 'repro'
[System.IO.File]::WriteAllText((Join-Path $dir 'a.json'),
    (New-EFFixture -RunId 'BENCH-20261028T010000Z-aaaa1111' `
        -Collected '2026-10-28T01:00:00Z' -GenMs 6400),
    [System.Text.Encoding]::UTF8)
# Scope to a.json: a directory run would ingest its own
# report outputs as artifacts on the second pass.
$out1 = Join-Path $dir 'r1.json'
$out2 = Join-Path $dir 'r2.json'
& "$PSHOME\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File `
    $SCRIPT:TOOL -ArtifactPath (Join-Path $dir 'a.json') `
    -OutPath $out1 | Out-Null
Start-Sleep -Seconds 2
& "$PSHOME\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File `
    $SCRIPT:TOOL -ArtifactPath (Join-Path $dir 'a.json') `
    -OutPath $out2 | Out-Null
$j1 = (Get-Content $out1 -Raw | ConvertFrom-Json)
$j2 = (Get-Content $out2 -Raw | ConvertFrom-Json)
$j1.created_utc = 'X'
$j2.created_utc = 'X'
$s1 = ($j1 | ConvertTo-Json -Depth 14)
$s2 = ($j2 | ConvertTo-Json -Depth 14)
Assert-EFTrue 'repro.identical' ($s1 -ceq $s2) 'reports differ'

# ---- 15. workload mismatch: separate cohorts, no comparison ----

$dir = New-EFCase 'workload'
[System.IO.File]::WriteAllText((Join-Path $dir 'a.json'),
    (New-EFFixture -RunId 'BENCH-20261028T010000Z-aaaa1111' `
        -Collected '2026-10-28T01:00:00Z' -GenMs 6400),
    [System.Text.Encoding]::UTF8)
[System.IO.File]::WriteAllText((Join-Path $dir 'b.json'),
    (New-EFFixture -RunId 'BENCH-20261028T020000Z-bbbb2222' `
        -Collected '2026-10-28T02:00:00Z' -GenMs 3200 `
        -PromptSha ('cc' * 32)),
    [System.Text.Encoding]::UTF8)
$r = Invoke-EFTool $dir
Assert-EFTrue 'workload.exit0' ($r.Code -eq 0) "exit=$($r.Code)"
if ($r.Code -eq 0 -and (Test-Path $r.Out)) {
    $rep = Read-EFReport $r.Out
    Assert-EFTrue 'workload.twoCohorts' (
        $rep.cohorts.Count -eq 2) "cohorts=$($rep.cohorts.Count)"
    Assert-EFTrue 'workload.noCompare' (
        $rep.comparisons.Count -eq 0) (
        "comparisons=$($rep.comparisons.Count)")
}

Write-Host ''
Write-Host ("EF-SCIENCE-01A SELF-TEST: {0} passed, {1} failed" -f @(
        $SCRIPT:PASS, $SCRIPT:FAIL.Count))
if ($SCRIPT:FAIL.Count -gt 0) { exit 1 }
exit 0
