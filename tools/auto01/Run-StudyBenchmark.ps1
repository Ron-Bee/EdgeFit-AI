# EF-COMP-01B-AUTO-01 Phase 4: execute ONE approved study run. No retries.
param([int]$RunNum, [string]$Candidate)
Import-Module "$PSScriptRoot\EfAuto.psm1" -Force
if ($Candidate -notin @('A','B')) { throw 'candidate must be A or B' }
$threads = if ($Candidate -eq 'A') { 4 } else { 8 }
$triple = "ctx=512 threads=$threads predict=64"
$shots = "$PSScriptRoot\shots"
$runs = 'C:\Users\Ron\Projects\EdgeFit-AI\eval\comp-01b\runs'
Assert-Serial
'PREREAD battery/mem/thermal:'
Invoke-Adb -AdbArgs @('shell', 'dumpsys', 'battery') | Select-String -Pattern 'level:|temperature:' | Select-Object -First 2
Invoke-Adb -AdbArgs @('shell', 'cat', '/proc/meminfo') | Select-String -Pattern 'MemAvailable' | Select-Object -First 1
Invoke-Adb -AdbArgs @('shell', 'dumpsys', 'thermalservice') | Select-String -Pattern 'Thermal Status:'
Start-EdgeFit
Invoke-UiTap -Doc (Get-UiDump) -Desc 'Benchmark' | Out-Null
Start-Sleep -Seconds 2
Invoke-UiTap -Doc (Get-UiDump) -Text 'Select Config' | Out-Null
Start-Sleep -Seconds 2
Invoke-UiTap -Doc (Get-UiDump) -Contains "Manual $Candidate (ctx=512 threads=$threads predict=64)" | Out-Null
Start-Sleep -Seconds 1
Invoke-UiTap -Doc (Get-UiDump) -Text 'Apply' | Out-Null
Start-Sleep -Seconds 2
$d = Get-UiDump
$cfg = (Find-UiNode -Doc $d -Contains 'Config:').GetAttribute('text')
'CONFIG: ' + $cfg
if ($cfg -like '*bound session*') { throw 'session bound; refused' }
if ($cfg -notlike "*$triple*") { throw "config mismatch: $cfg" }
Save-Shot "$shots\run$RunNum-prerun.png"
Invoke-UiTap -Doc $d -Text 'Run Benchmark' | Out-Null
'RUN TAPPED ONCE at ' + (Get-Date -Format 'o')
$terminal = $null
for ($i = 0; $i -lt 60; $i++) {
    Start-Sleep -Seconds 10
    $d = Get-UiDump
    foreach ($s in @('Benchmark: Completed','Benchmark: Failed','Benchmark: Timed out','Benchmark: Cancelled')) {
        if (Find-UiNode -Doc $d -Contains $s) { $terminal = $s; break }
    }
    if ($terminal) { break }
    if ($i -eq 2) { Save-Shot "$shots\run$RunNum-midrun.png" }
}
if (-not $terminal) { Save-Shot "$shots\run$RunNum-timeout.png"; throw 'no terminal state in 600s; STOP, no retry' }
'TERMINAL: ' + $terminal
Save-Shot "$shots\run$RunNum-terminal.png"
if ($terminal -ne 'Benchmark: Completed') { throw "run ended $terminal; STOP, no retry" }
Invoke-UiTap -Doc (Get-UiDump) -Text 'Save' | Out-Null
Start-Sleep -Seconds 3
Save-Shot "$shots\run$RunNum-picker.png"
$d = Get-UiDump
$savers = @($d.SelectNodes('//node') | Where-Object { $_.GetAttribute('text') -eq 'SAVE' -and $_.GetAttribute('package') -like '*documentsui*' -and $_.GetAttribute('clickable') -eq 'true' })
if ($savers.Count -ne 1) { throw "expected 1 documentsui SAVE, found $($savers.Count); STOP" }
$c = Get-NodeCenter -Node $savers[0]
$beforeSave = (Invoke-Adb -AdbArgs @('shell', 'ls', '-t', '/sdcard/Download/edgefit-bench-*.json')).Trim().Split("`n")[0].Trim()
Invoke-Adb -AdbArgs @('shell', 'input', 'tap', [int]$c[0], [int]$c[1]) | Out-Null
$dl = $null
for ($i = 0; $i -lt 12; $i++) {
    Start-Sleep -Seconds 5
    $now = (Invoke-Adb -AdbArgs @('shell', 'ls', '-t', '/sdcard/Download/edgefit-bench-*.json')).Trim().Split("`n")[0].Trim()
    if ($now -ne $beforeSave) { $dl = $now; break }
}
if (-not $dl) { throw 'no new export file appeared; STOP' }
'EXPORT: ' + $dl
'PULLING: ' + $dl
$dest = Join-Path $runs ("run$RunNum-$Candidate-" + ($dl.Split('/')[-1]))
Invoke-Adb -AdbArgs @('pull', $dl, $dest) | Out-Null
$j = Get-Content $dest -Raw | ConvertFrom-Json
$sha = (Get-FileHash $dest -Algorithm SHA256).Hash
'RUN_ID=' + $j.run_id + ' STATUS=' + $j.status + ' CFG=' + $j.config.n_ctx + '/' + $j.config.n_threads + '/' + $j.config.n_predict + ' TPS=' + $j.measurements.tps_gen + ' COLLECTED=' + $j.collected_at_utc + ' SHA=' + $sha
if ($j.status -ne 'COMPLETED') { throw 'export status not COMPLETED' }
if ($j.config.n_threads -ne $threads) { throw 'export config mismatch' }
if ($j.collected_at_utc -le '2026-10-31T02:20:24Z') { throw 'export predates decisions' }
$ledger = @{run=$RunNum; candidate=$Candidate; run_id=$j.run_id; status=$j.status; collected=$j.collected_at_utc; tps_gen=$j.measurements.tps_gen; tps_prefill=$j.measurements.tps_prefill; file=(Split-Path $dest -Leaf); sha256=$sha} | ConvertTo-Json -Compress
Add-Content (Join-Path $runs 'ledger.jsonl') -Value $ledger
Invoke-UiTap -Doc (Get-UiDump) -Text 'Back' | Out-Null
"RUN$RunNum DONE"
