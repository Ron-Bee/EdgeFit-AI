# EdgeFit AI EF-SCIENCE-01A
# Deterministic offline analysis of validated benchmark artifacts.
# PowerShell 5.1, no dependencies, no network, read-only inputs.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File scripts/Invoke-EFScience01A.ps1 `
#     -ArtifactPath <file-or-dir> -OutPath <report.json> `
#     [-PredictionsPath <predictions.json>] [-ManifestPath <manifest.tsv>]
#
# Exit codes: 0 = analysis complete (exclusions are data, listed in
# the report); 2 = input/contract errors (nothing written);
# 1 = unexpected failure.
#
# Statistics: mean, median, min, max, sample SD (n-1), CV, and the
# observed [min, max] range as the descriptive interval. No CIs at
# small n, no p-values, no causal claims. See docs/ef-science-01-protocol.md.

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ArtifactPath,

    [Parameter(Mandatory = $true)]
    [string]$OutPath,

    [string]$PredictionsPath = $null,

    [string]$ManifestPath = $null
)

$ErrorActionPreference = 'Stop'

$SCRIPT:TOOL_VERSION = '1a-1'
$SCRIPT:MAX_FILE_BYTES = 65536
$SCRIPT:MAX_FILES = 256
$SCRIPT:TPS_TOLERANCE = 0.01
$SCRIPT:RUN_RE = '^BENCH-[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}$'
$SCRIPT:TS_RE = '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$'
$SCRIPT:HEX64_RE = '^[0-9a-f]{64}$'
$SCRIPT:TOP_FIELDS = @(
    'contract', 'contract_version', 'run_id', 'device', 'model',
    'runtime', 'config', 'workload', 'measurements', 'status',
    'error', 'provenance', 'app_version', 'collected_at_utc'
)
$SCRIPT:STATUSES = @('COMPLETED', 'CANCELLED', 'FAILED', 'TIMEOUT')
$SCRIPT:MEM_METHODS = @('debug-memoryinfo+mallinfo2', 'activity-manager')

function Get-EFSha256Hex {
    param([string]$Path)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $stream = [System.IO.File]::OpenRead($Path)
        try {
            $bytes = $sha.ComputeHash($stream)
        } finally {
            $stream.Close()
        }
    } finally {
        $sha.Dispose()
    }
    return ([System.BitConverter]::ToString($bytes)).Replace('-', '').ToLower()
}

function Test-EFInt {
    param($Value)
    return ($Value -is [int] -or $Value -is [long])
}

function Test-EFNum {
    param($Value)
    # NOTE: PowerShell 5.1 ConvertFrom-Json yields Decimal
    # for fractional JSON numbers, never Double.
    if ($Value -is [int] -or $Value -is [long] -or
        $Value -is [decimal]) {
        return $true
    }
    if ($Value -is [double]) {
        return (-not [double]::IsNaN($Value)) -and
            (-not [double]::IsInfinity($Value))
    }
    return $false
}

function Get-EFDouble {
    param($Value)
    if ($Value -is [double]) { return $Value }
    if ($Value -is [int] -or $Value -is [long] -or
        $Value -is [decimal]) {
        $d = [double]$Value
        if ([double]::IsNaN($d) -or [double]::IsInfinity($d)) {
            return $null
        }
        return $d
    }
    return $null
}

# Mirrors scripts/ef_bench_result_01.py validate_result(). Returns a
# list of violation strings; empty means VALID.
function Get-EFContractViolations {
    param($Doc)
    $errors = New-Object System.Collections.ArrayList
    if ($Doc -isnot [System.Management.Automation.PSCustomObject]) {
        [void]$errors.Add('result: must be a JSON object')
        return $errors
    }
    foreach ($prop in $Doc.PSObject.Properties) {
        if ($prop.Name.StartsWith('x_')) { continue }
        if ($SCRIPT:TOP_FIELDS -cnotcontains $prop.Name) {
            [void]$errors.Add("$($prop.Name): unknown top-level field")
        }
    }
    if ($Doc.contract -cne 'edgefit-bench-result') {
        [void]$errors.Add('contract: must equal edgefit-bench-result')
    }
    if (-not (Test-EFInt $Doc.contract_version)) {
        [void]$errors.Add('contract_version: must be an integer')
    } elseif ($Doc.contract_version -ne 1) {
        [void]$errors.Add('contract_version: unsupported version')
    }
    if ($Doc.run_id -isnot [string] -or
        $Doc.run_id -cnotmatch $SCRIPT:RUN_RE) {
        [void]$errors.Add('run_id: bad format')
    }
    if ($SCRIPT:STATUSES -cnotcontains $Doc.status) {
        [void]$errors.Add('status: unknown status')
    }
    if ($Doc.status -ceq 'COMPLETED') {
        if ($null -ne $Doc.error) {
            [void]$errors.Add('COMPLETED: error must be null')
        }
    } elseif ($Doc.error -isnot [string] -or $Doc.error -eq '') {
        [void]$errors.Add('non-COMPLETED: error must be a non-empty string')
    }
    if ($Doc.provenance -cne 'on_device_measurement') {
        [void]$errors.Add("provenance: must equal 'on_device_measurement'")
    }
    if ([string]::IsNullOrWhiteSpace([string]$Doc.app_version)) {
        [void]$errors.Add('app_version: required')
    }
    if ($Doc.collected_at_utc -isnot [string] -or
        $Doc.collected_at_utc -cnotmatch $SCRIPT:TS_RE) {
        [void]$errors.Add('collected_at_utc: must match YYYY-MM-DDTHH:MM:SSZ')
    }
    Test-EFDevice $errors $Doc.device
    Test-EFModel $errors $Doc.model
    Test-EFRuntime $errors $Doc.runtime
    Test-EFConfig $errors $Doc.config
    Test-EFWorkload $errors $Doc.workload
    Test-EFMeasurements $errors $Doc.measurements
    return $errors
}

function Test-EFDevice {
    param($Errors, $Device)
    if ($Device -isnot [System.Management.Automation.PSCustomObject]) {
        [void]$Errors.Add('device: must be an object')
        return
    }
    if ($Device.profile_ref -isnot [string] -or
        $Device.profile_ref -cnotmatch $SCRIPT:HEX64_RE) {
        [void]$Errors.Add('device.profile_ref: must be 64 hex chars')
    }
    if ([string]::IsNullOrWhiteSpace([string]$Device.model)) {
        [void]$Errors.Add('device.model: required')
    }
    if (-not (Test-EFInt $Device.sdk_int)) {
        [void]$Errors.Add('device.sdk_int: must be an integer')
    }
    if ($Device.supported_abis -isnot [array] -or
        $Device.supported_abis.Count -lt 1) {
        [void]$Errors.Add('device.supported_abis: must be a non-empty array')
    }
    if (-not (Test-EFInt $Device.logical_processors)) {
        [void]$Errors.Add('device.logical_processors: must be an integer')
    }
    if (-not (Test-EFInt $Device.total_ram_bytes) -or
        $Device.total_ram_bytes -le 0) {
        [void]$Errors.Add('device.total_ram_bytes: must be positive')
    }
    if ($Device.provenance -cne 'platform_reported') {
        [void]$Errors.Add("device.provenance: must equal 'platform_reported'")
    }
}

function Test-EFModel {
    param($Errors, $Model)
    if ($Model -isnot [System.Management.Automation.PSCustomObject]) {
        [void]$Errors.Add('model: must be an object')
        return
    }
    if ([string]::IsNullOrWhiteSpace([string]$Model.display_name)) {
        [void]$Errors.Add('model.display_name: required')
    }
    if (-not (Test-EFInt $Model.size_bytes) -or $Model.size_bytes -le 0) {
        [void]$Errors.Add('model.size_bytes: must be a positive integer')
    }
    if ($Model.sha256 -isnot [string] -or
        $Model.sha256 -cnotmatch $SCRIPT:HEX64_RE) {
        [void]$Errors.Add('model.sha256: must be 64 lowercase hex chars')
    }
}

function Test-EFRuntime {
    param($Errors, $Runtime)
    if ($Runtime -isnot [System.Management.Automation.PSCustomObject]) {
        [void]$Errors.Add('runtime: must be an object')
        return
    }
    foreach ($field in @('impl', 'version', 'backend', 'abi')) {
        if ([string]::IsNullOrWhiteSpace([string]$Runtime.$field)) {
            [void]$Errors.Add("runtime.${field}: required")
        }
    }
}

function Test-EFConfig {
    param($Errors, $Config)
    if ($Config -isnot [System.Management.Automation.PSCustomObject]) {
        [void]$Errors.Add('config: must be an object')
        return
    }
    if (-not (Test-EFInt $Config.n_ctx)) {
        [void]$Errors.Add('config.n_ctx: must be an integer')
    }
    if (-not (Test-EFInt $Config.n_threads)) {
        [void]$Errors.Add('config.n_threads: must be an integer')
    }
    if (-not (Test-EFInt $Config.n_predict)) {
        [void]$Errors.Add('config.n_predict: must be an integer')
    }
    if ($Config.sampler -cne 'greedy') {
        [void]$Errors.Add('config.sampler: must equal greedy')
    }
    if (-not (Test-EFInt $Config.seed)) {
        [void]$Errors.Add('config.seed: must be an integer')
    }
    if (-not (Test-EFInt $Config.timeout_ms) -or $Config.timeout_ms -le 0) {
        [void]$Errors.Add('config.timeout_ms: must be a positive integer')
    }
}

function Test-EFWorkload {
    param($Errors, $Workload)
    if ($Workload -isnot [System.Management.Automation.PSCustomObject]) {
        [void]$Errors.Add('workload: must be an object')
        return
    }
    if ($Workload.id -cne 'edgefit-prompt-v1') {
        [void]$Errors.Add("workload.id: must equal 'edgefit-prompt-v1'")
    }
    if ($Workload.prompt_sha256 -isnot [string] -or
        $Workload.prompt_sha256 -cnotmatch $SCRIPT:HEX64_RE) {
        [void]$Errors.Add('workload.prompt_sha256: must be 64 hex chars')
    }
    if (-not (Test-EFInt $Workload.prompt_chars) -or
        $Workload.prompt_chars -le 0) {
        [void]$Errors.Add('workload.prompt_chars: must be positive')
    }
}

function Test-EFMeasurements {
    param($Errors, $M)
    if ($M -isnot [System.Management.Automation.PSCustomObject]) {
        [void]$Errors.Add('measurements: must be an object')
        return
    }
    foreach ($field in @('load_ms', 'prefill_ms', 'gen_ms')) {
        if (-not (Test-EFInt $M.$field) -or $M.$field -lt 0) {
            [void]$Errors.Add(
                "measurements.${field}: must be a non-negative integer")
        }
    }
    foreach ($field in @('prefill_tokens', 'generated_tokens')) {
        if (-not (Test-EFInt $M.$field) -or $M.$field -lt 0) {
            [void]$Errors.Add(
                "measurements.${field}: must be a non-negative integer")
        }
    }
    # Throughput cross-check, recomputed from tokens/ms.
    if ((Test-EFInt $M.gen_ms) -and $M.gen_ms -gt 0) {
        $want = [double]$M.generated_tokens * 1000.0 / [double]$M.gen_ms
        $got = Get-EFDouble $M.tps_gen
        if ($null -eq $got -or
            [Math]::Abs($got - $want) -gt $SCRIPT:TPS_TOLERANCE) {
            [void]$Errors.Add('measurements.tps_gen: inconsistent')
        }
    }
    if ((Test-EFInt $M.prefill_ms) -and $M.prefill_ms -gt 0) {
        $want = [double]$M.prefill_tokens * 1000.0 / [double]$M.prefill_ms
        $got = Get-EFDouble $M.tps_prefill
        if ($null -eq $got -or
            [Math]::Abs($got - $want) -gt $SCRIPT:TPS_TOLERANCE) {
            [void]$Errors.Add('measurements.tps_prefill: inconsistent')
        }
    }
    if ($M.eos_stop -isnot [bool]) {
        [void]$Errors.Add('measurements.eos_stop: must be a boolean')
    }
    if ($M.mem -isnot [System.Management.Automation.PSCustomObject]) {
        [void]$Errors.Add('measurements.mem: must be an object')
    } else {
        foreach ($field in @('pss_before_kb', 'pss_after_kb')) {
            if (-not (Test-EFInt $M.mem.$field) -or $M.mem.$field -lt 0) {
                [void]$Errors.Add(
                    "mem.${field}: must be a non-negative integer")
            }
        }
        if (-not (Test-EFInt $M.mem.alloc_delta_bytes)) {
            [void]$Errors.Add('mem.alloc_delta_bytes: must be an integer')
        }
        if ($SCRIPT:MEM_METHODS -cnotcontains $M.mem.method) {
            [void]$Errors.Add('mem.method: unknown method')
        }
    }
    if ($M.thermal -isnot [System.Management.Automation.PSCustomObject]) {
        [void]$Errors.Add('measurements.thermal: must be an object')
    } else {
        foreach ($field in @('before', 'mid', 'after')) {
            if ([string]::IsNullOrWhiteSpace(
                    [string]$M.thermal.$field)) {
                [void]$Errors.Add("thermal.${field}: required")
            }
        }
        if ($M.thermal.available -isnot [bool]) {
            [void]$Errors.Add('thermal.available: must be a boolean')
        }
    }
}

# Exact-cohort key. Two runs pool ONLY when every field
# matches; native build identity is included because it
# moves performance (debug vs benchmark variant).
function Get-EFCohortKey {
    param($Doc)
    $nativeVariant = $null
    $nativeOpt = $null
    if ($null -ne $Doc.x_native_build) {
        $nativeVariant = [string]$Doc.x_native_build.variant
        $nativeOpt = [string]$Doc.x_native_build.native_opt
    }
    $fields = [ordered]@{
        model_sha256     = $Doc.model.sha256
        model_size_bytes = [long]$Doc.model.size_bytes
        workload_id      = $Doc.workload.id
        prompt_sha256    = $Doc.workload.prompt_sha256
        runtime_impl     = $Doc.runtime.impl
        runtime_version  = $Doc.runtime.version
        runtime_backend  = $Doc.runtime.backend
        runtime_abi      = $Doc.runtime.abi
        n_ctx            = [long]$Doc.config.n_ctx
        n_threads        = [long]$Doc.config.n_threads
        n_predict        = [long]$Doc.config.n_predict
        sampler          = $Doc.config.sampler
        seed             = [long]$Doc.config.seed
        app_version      = [string]$Doc.app_version
        native_variant   = $nativeVariant
        native_opt       = $nativeOpt
    }
    $parts = @()
    foreach ($key in $fields.Keys) {
        $value = $fields[$key]
        if ($null -eq $value) { $value = '' }
        $parts += "${key}=${value}"
    }
    return @{
        Key    = ($parts -join '|')
        Fields = $fields
    }
}

function Get-EFMedian {
    param([double[]]$Sorted)
    $n = $Sorted.Count
    if ($n -eq 0) { return $null }
    $mid = [Math]::Floor($n / 2)
    if ($n % 2 -eq 1) { return $Sorted[$mid] }
    return ($Sorted[$mid - 1] + $Sorted[$mid]) / 2.0
}

function Get-EFStats {
    param([double[]]$Values)
    $sorted = @($Values | Sort-Object)
    $n = $sorted.Count
    $mean = ($sorted | Measure-Object -Average).Average
    $sd = $null
    if ($n -ge 2) {
        $sum = 0.0
        foreach ($v in $sorted) { $sum += ($v - $mean) * ($v - $mean) }
        $sd = [Math]::Sqrt($sum / ($n - 1))
    }
    $cv = $null
    if ($null -ne $sd -and $mean -gt 0) { $cv = $sd / $mean }
    return [pscustomobject][ordered]@{
        n     = $n
        mean  = $mean
        median = (Get-EFMedian $sorted)
        min   = $sorted[0]
        max   = $sorted[$n - 1]
        sd_sample = $sd
        cv    = $cv
        interval_method = 'observed range [min, max]; not a confidence interval'
    }
}

function Get-EFPredictions {
    param([string]$Path)
    if ([string]::IsNullOrEmpty($Path)) { return @{} }
    if (-not (Test-Path $Path -PathType Leaf)) {
        throw "predictions file not found: $Path"
    }
    $raw = Get-Content $Path -Raw -Encoding UTF8
    $doc = $raw | ConvertFrom-Json
    if ($null -eq $doc.predictions -or $doc.predictions -isnot [array]) {
        throw 'predictions: must hold a predictions array'
    }
    $map = @{}
    foreach ($p in $doc.predictions) {
        $runId = [string]$p.run_id
        if ([string]::IsNullOrWhiteSpace($runId)) {
            throw 'predictions: run_id required'
        }
        if ($map.ContainsKey($runId)) {
            throw "predictions: duplicate run_id $runId"
        }
        $gen = Get-EFDouble $p.predicted_gen_tps
        $prefill = Get-EFDouble $p.predicted_prefill_tps
        $at = [string]$p.predicted_at_utc
        if ($null -eq $gen -or $null -eq $prefill) {
            throw "predictions: non-finite values for $runId"
        }
        if ($gen -le 0 -or $prefill -le 0) {
            throw "predictions: values must be > 0 for $runId"
        }
        if ($at -cnotmatch $SCRIPT:TS_RE) {
            throw "predictions: bad predicted_at_utc for $runId"
        }
        $map[$runId] = @{
            Gen     = $gen
            Prefill = $prefill
            At      = $at
        }
    }
    return $map
}

function Get-EFManifest {
    param([string]$Path)
    if ([string]::IsNullOrEmpty($Path)) { return $null }
    if (-not (Test-Path $Path -PathType Leaf)) {
        throw "manifest file not found: $Path"
    }
    $map = @{}
    foreach ($line in (Get-Content $Path -Encoding UTF8)) {
        $trimmed = $line.Trim()
        if ($trimmed -eq '' -or $trimmed.StartsWith('#')) { continue }
        $parts = $trimmed -split '\s+', 2
        if ($parts.Count -ne 2) { throw "manifest: bad line: $trimmed" }
        $map[$parts[0].ToLower()] = $parts[1].Trim().ToLower()
    }
    return $map
}

# ---- main ----

try {
    $item = Get-Item $ArtifactPath -ErrorAction Stop
    if ($item -is [System.IO.DirectoryInfo]) {
        $files = @(Get-ChildItem $item.FullName -File -Filter '*.json' |
            Sort-Object Name)
    } else {
        $files = @($item)
    }
    if ($files.Count -eq 0) { throw 'no JSON artifacts found' }
    if ($files.Count -gt $SCRIPT:MAX_FILES) {
        throw "too many files (>$($SCRIPT:MAX_FILES))"
    }

    $manifest = Get-EFManifest $ManifestPath
    $predictions = Get-EFPredictions $PredictionsPath

    $inputs = New-Object System.Collections.ArrayList
    $valid = New-Object System.Collections.ArrayList
    $excluded = New-Object System.Collections.ArrayList
    $fatal = New-Object System.Collections.ArrayList
    $seenRuns = @{}

    foreach ($file in $files) {
        $name = $file.Name
        if ($file.Length -gt $SCRIPT:MAX_FILE_BYTES) {
            [void]$fatal.Add("${name}: exceeds $(
                $SCRIPT:MAX_FILE_BYTES) bytes")
            continue
        }
        $sha = Get-EFSha256Hex $file.FullName
        [void]$inputs.Add([pscustomobject][ordered]@{
                file       = $name
                sha256     = $sha
                bytes      = $file.Length
            })
        if ($null -ne $manifest) {
            if (-not $manifest.ContainsKey($name.ToLower())) {
                [void]$fatal.Add("${name}: missing from manifest")
                continue
            }
            if ($manifest[$name.ToLower()] -cne $sha) {
                [void]$fatal.Add("${name}: hash mismatch vs manifest")
                continue
            }
        }
        try {
            $doc = (Get-Content $file.FullName -Raw -Encoding UTF8 |
                ConvertFrom-Json)
        } catch {
            [void]$fatal.Add("${name}: malformed JSON")
            continue
        }
        $violations = Get-EFContractViolations $doc
        if ($violations.Count -gt 0) {
            foreach ($v in $violations) {
                [void]$fatal.Add("${name}: $v")
            }
            continue
        }
        $runId = [string]$doc.run_id
        if ($seenRuns.ContainsKey($runId)) {
            [void]$fatal.Add(
                "${name}: duplicate run_id $runId " +
                "(also in $($seenRuns[$runId]))")
            continue
        }
        $seenRuns[$runId] = $name
        if ($doc.status -cne 'COMPLETED') {
            [void]$excluded.Add([pscustomobject][ordered]@{
                    run_id = $runId
                    file   = $name
                    reason = "status $($doc.status): $($doc.error)"
                })
            continue
        }
        [void]$valid.Add(@{ Doc = $doc; File = $name; Sha = $sha })
    }

    # Duplicate content: same bytes under two names.
    $bySha = @{}
    foreach ($entry in $valid) {
        if ($bySha.ContainsKey($entry.Sha)) {
            [void]$fatal.Add(
                "$($entry.File): duplicate content of $($bySha[$entry.Sha])")
        } else {
            $bySha[$entry.Sha] = $entry.File
        }
    }

    if ($fatal.Count -gt 0) {
        Write-Host 'EF-SCIENCE-01A: INPUT ERRORS'
        foreach ($f in ($fatal | Sort-Object -Unique)) {
            Write-Host "  FAIL $f"
        }
        exit 2
    }

    # Cohort grouping (exact key only).
    $cohorts = @{}
    foreach ($entry in $valid) {
        $ck = Get-EFCohortKey $entry.Doc
        if (-not $cohorts.ContainsKey($ck.Key)) {
            $cohorts[$ck.Key] = @{
                Fields  = $ck.Fields
                Members = (New-Object System.Collections.ArrayList)
            }
        }
        [void]$cohorts[$ck.Key].Members.Add($entry)
    }

    $cohortReports = New-Object System.Collections.ArrayList
    $validRuns = New-Object System.Collections.ArrayList
    foreach ($key in ($cohorts.Keys | Sort-Object)) {
        $members = $cohorts[$key].Members
        $genValues = New-Object System.Collections.ArrayList
        $prefillValues = New-Object System.Collections.ArrayList
        $runIds = New-Object System.Collections.ArrayList
        $predErrors = New-Object System.Collections.ArrayList
        foreach ($m in ($members | Sort-Object {
                [string]$_.Doc.run_id })) {
            $doc = $m.Doc
            $gen = Get-EFDouble $doc.measurements.tps_gen
            $prefill = Get-EFDouble $doc.measurements.tps_prefill
            [void]$genValues.Add($gen)
            [void]$prefillValues.Add($prefill)
            [void]$runIds.Add([string]$doc.run_id)
            [void]$validRuns.Add([pscustomobject][ordered]@{
                    run_id = [string]$doc.run_id
                    file   = $m.File
                    sha256 = $m.Sha
                    gen_tps = $gen
                    prefill_tps = $prefill
                    collected_at_utc =
                        [string]$doc.collected_at_utc
                    thermal = '{0}/{1}/{2}' -f @(
                        $doc.measurements.thermal.before,
                        $doc.measurements.thermal.mid,
                        $doc.measurements.thermal.after)
                })
            $runId = [string]$doc.run_id
            if ($predictions.ContainsKey($runId)) {
                $p = $predictions[$runId]
                $collected = [string]$doc.collected_at_utc
                # Science rule: strictly earlier only. Same
                # second is ambiguous and does not qualify.
                if ($p.At -ge $collected) {
                    [void]$excluded.Add(
                        [pscustomobject][ordered]@{
                            run_id = $runId
                            file   = $m.File
                            reason = 'prediction does not strictly ' +
                                'predate collection'
                        })
                    continue
                }
                [void]$predErrors.Add([pscustomobject][ordered]@{
                        run_id = $runId
                        gen_signed = ($gen - $p.Gen)
                        gen_abs = [Math]::Abs($gen - $p.Gen)
                        gen_pct = (($gen - $p.Gen) / $p.Gen * 100.0)
                        prefill_signed = ($prefill - $p.Prefill)
                        prefill_abs =
                            [Math]::Abs($prefill - $p.Prefill)
                        prefill_pct = (($prefill - $p.Prefill) /
                            $p.Prefill * 100.0)
                    })
            }
        }
        [void]$cohortReports.Add([pscustomobject][ordered]@{
                cohort_id = $key
                key_fields = $cohorts[$key].Fields
                n = $runIds.Count
                run_ids = $runIds.ToArray()
                gen_stats = (Get-EFStats ([double[]]$genValues.ToArray()))
                prefill_stats =
                    (Get-EFStats ([double[]]$prefillValues.ToArray()))
                prediction_errors = $predErrors.ToArray()
            })
    }

    # Cross-cohort comparison: same model bytes + same
    # workload only. Anything else is refused, never pooled.
    $comparisons = New-Object System.Collections.ArrayList
    $cohortList = @($cohortReports | Sort-Object cohort_id)
    for ($i = 0; $i -lt $cohortList.Count; $i++) {
        for ($j = $i + 1; $j -lt $cohortList.Count; $j++) {
            $a = $cohortList[$i]
            $b = $cohortList[$j]
            $fa = $a.key_fields
            $fb = $b.key_fields
            if ($fa.model_sha256 -cne $fb.model_sha256 -or
                $fa.workload_id -cne $fb.workload_id -or
                $fa.prompt_sha256 -cne $fb.prompt_sha256) {
                continue
            }
            $differing = New-Object System.Collections.ArrayList
            foreach ($k in $fa.Keys) {
                if ([string]$fa[$k] -cne [string]$fb[$k]) {
                    [void]$differing.Add($k)
                }
            }
            $am = $a.gen_stats.median
            $bm = $b.gen_stats.median
            $amean = $a.gen_stats.mean
            $bmean = $b.gen_stats.mean
            $medPct = $null
            $meanPct = $null
            if ($am -gt 0) {
                $medPct = ($bm - $am) / $am * 100.0
            }
            if ($amean -gt 0) {
                $meanPct = ($bmean - $amean) / $amean * 100.0
            }
            [void]$comparisons.Add([pscustomobject][ordered]@{
                    cohort_a = $a.cohort_id
                    cohort_b = $b.cohort_id
                    n_a = $a.n
                    n_b = $b.n
                    differing_fields = $differing.ToArray()
                    isolated_single_field =
                        ($differing.Count -eq 1)
                    delta_median_gen_abs = ($bm - $am)
                    delta_median_gen_pct = $medPct
                    delta_mean_gen_abs = ($bmean - $amean)
                    delta_mean_gen_pct = $meanPct
                    note = 'Observed difference only. A single ' +
                        'differing field does not prove cause; ' +
                        'thermal and memory conditions in the ' +
                        'run rows are uncontrolled caveats.'
                })
        }
    }

    $warnings = New-Object System.Collections.ArrayList
    foreach ($c in $cohortList) {
        if ($c.n -lt 5) {
            [void]$warnings.Add(
                "cohort n=$($c.n) below the 5-run study target: " +
                "descriptive only, $($c.cohort_id)")
        }
    }

    $report = [pscustomobject][ordered]@{
        tool       = 'Invoke-EFScience01A'
        tool_version = $SCRIPT:TOOL_VERSION
        created_utc = (
            [DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ'))
        stats_methods = 'mean, median, min, max; sample SD ' +
            '(n-1, null when n<2); CV=SD/mean (null unless ' +
            'mean>0); descriptive interval [min,max]; ' +
            'prediction errors vs prediction denominator'
        inputs     = $inputs.ToArray()
        valid_runs = $validRuns.ToArray()
        excluded   = @($excluded | Sort-Object run_id)
        cohorts    = @($cohortReports | Sort-Object cohort_id)
        comparisons = $comparisons.ToArray()
        warnings   = $warnings.ToArray()
    }

    $json = $report | ConvertTo-Json -Depth 14
    $outDir = Split-Path $OutPath -Parent
    if ($outDir -ne '' -and -not (Test-Path $outDir)) {
        [void](New-Item $outDir -ItemType Directory -Force)
    }
    [System.IO.File]::WriteAllText($OutPath, $json,
        [System.Text.Encoding]::UTF8)
    Write-Host 'EF-SCIENCE-01A: ANALYSIS COMPLETE'
    Write-Host ("  valid={0} excluded={1} cohorts={2} comparisons={3}" -f @(
            $validRuns.Count, $excluded.Count,
            $cohortReports.Count, $comparisons.Count))
    foreach ($w in $warnings) { Write-Host "  WARN $w" }
    exit 0
} catch {
    Write-Host "EF-SCIENCE-01A: ERROR $($_.Exception.Message)"
    exit 1
}
