# EF-ANDROID-FLEET-UX-01 Phase D: read-only UX audit crawl.
# NEVER taps Run Benchmark, Meta requests, approvals, deletes.
Import-Module "$PSScriptRoot\EfAuto.psm1" -Force
$shots = "$PSScriptRoot\shots\uxaudit"
New-Item -ItemType Directory -Force -Path $shots | Out-Null
Assert-Serial
Start-EdgeFit
Invoke-UiTap -Doc (Get-UiDump) -Desc 'Home' | Out-Null
Start-Sleep -Seconds 2
function Snap([string]$name) {
    $d = Get-UiDump
    Save-Shot "$shots\$name.png"
    $texts = ($d.SelectNodes('//node') | ForEach-Object {
        $_.GetAttribute('text') } | Where-Object { $_ } |
        Select-Object -Unique) -join ' | '
    "== $name == $texts"
}
Snap 'ux-01-home'
Invoke-UiTap -Doc (Get-UiDump) -Text 'DEVICE DETAILS' | Out-Null
Start-Sleep -Seconds 2
Snap 'ux-02-device-details'
Invoke-Adb -AdbArgs @('shell','input','keyevent','KEYCODE_BACK') | Out-Null
Start-Sleep -Seconds 2
Invoke-UiTap -Doc (Get-UiDump) -Text 'RECOMMENDED MODELS' | Out-Null
Start-Sleep -Seconds 2
Snap 'ux-03-model-catalog'
Invoke-Adb -AdbArgs @('shell','input','keyevent','KEYCODE_BACK') | Out-Null
Start-Sleep -Seconds 2
Invoke-UiTap -Doc (Get-UiDump) -Desc 'Benchmark' | Out-Null
Start-Sleep -Seconds 2
Snap 'ux-04-benchmark'
Invoke-UiTap -Doc (Get-UiDump) -Desc 'Sessions' | Out-Null
Start-Sleep -Seconds 2
Snap 'ux-05-sessions'
Invoke-UiTap -Doc (Get-UiDump) -Desc 'Agent' | Out-Null
Start-Sleep -Seconds 2
Snap 'ux-06-agent'
Invoke-UiTap -Doc (Get-UiDump) -Desc 'Settings' | Out-Null
Start-Sleep -Seconds 2
Snap 'ux-07-settings'
Invoke-UiTap -Doc (Get-UiDump) -Text 'HELP' | Out-Null
Start-Sleep -Seconds 2
Snap 'ux-08-help'
Invoke-Adb -AdbArgs @('shell','input','keyevent','KEYCODE_BACK') | Out-Null
Start-Sleep -Seconds 2
'UX-AUDIT-CRAWL DONE'
