# EF-COMP-01B-AUTO-01 Phase 2: read-only UI dry run. NEVER taps Run Benchmark.
Import-Module "$PSScriptRoot\EfAuto.psm1" -Force
$shots = "$PSScriptRoot\shots"
New-Item -ItemType Directory -Force -Path $shots | Out-Null
Assert-Serial
Start-EdgeFit
$d = Get-UiDump
Save-Shot "$shots\01-home.png"
'HOME nodes: ' + (($d.SelectNodes('//node') | ForEach-Object { $_.GetAttribute('text') } | Where-Object { $_ }) -join ' | ')
Invoke-UiTap -Doc $d -Desc 'Benchmark'
Start-Sleep -Seconds 2
$d = Get-UiDump
Save-Shot "$shots\02-benchmark.png"
$modelLine = (Find-UiNode -Doc $d -Contains 'Model:').GetAttribute('text')
$configLine = (Find-UiNode -Doc $d -Contains 'Config:').GetAttribute('text')
'MODEL: ' + $modelLine
'CONFIG: ' + $configLine
if ($configLine -like '*bound session*') { throw 'a session is bound; expected unbound' }
Invoke-UiTap -Doc $d -Text 'Select Config'
Start-Sleep -Seconds 2
$d = Get-UiDump
Save-Shot "$shots\03-config-dialog.png"
$items = $d.SelectNodes('//node') | ForEach-Object { $_.GetAttribute('text') } | Where-Object { $_ -like 'Manual*' -or $_ -like 'Device*' }
'OPTIONS: ' + ($items -join ' || ')
if (-not (Find-UiNode -Doc $d -Contains 'Manual A (ctx=512 threads=4 predict=64)')) { throw 'Manual A option missing' }
if (-not (Find-UiNode -Doc $d -Contains 'Manual B (ctx=512 threads=8 predict=64)')) { throw 'Manual B option missing' }
Invoke-UiTap -Doc $d -Contains 'Manual A (ctx=512 threads=4 predict=64)'
Start-Sleep -Seconds 1
Invoke-UiTap -Doc (Get-UiDump) -Text 'Apply'
Start-Sleep -Seconds 2
$d = Get-UiDump
Save-Shot "$shots\04-config-a.png"
'CONFIG-A: ' + (Find-UiNode -Doc $d -Contains 'Config:').GetAttribute('text')
Invoke-UiTap -Doc $d -Text 'Select Config'
Start-Sleep -Seconds 2
Invoke-UiTap -Doc (Get-UiDump) -Contains 'Manual B (ctx=512 threads=8 predict=64)'
Start-Sleep -Seconds 1
Invoke-UiTap -Doc (Get-UiDump) -Text 'Apply'
Start-Sleep -Seconds 2
$d = Get-UiDump
Save-Shot "$shots\05-config-b.png"
'CONFIG-B: ' + (Find-UiNode -Doc $d -Contains 'Config:').GetAttribute('text')
'PHASE2-DRYRUN: PASS (no inference started)'

