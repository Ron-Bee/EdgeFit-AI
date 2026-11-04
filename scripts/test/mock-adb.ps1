# Mock adb for Test-EFManualDevicePrep01.ps1 (invoked as -AdbPath).
# Scenario via $env:MOCK_ADB. Every invocation is logged when
# $env:MOCK_LOG is set. Mutating verbs (push/install) always fail:
# the wrapper must never call them under -DryRun.
$ErrorActionPreference = 'Stop'
$scenario = $env:MOCK_ADB
if ([string]::IsNullOrEmpty($scenario)) { $scenario = 'existing' }
if (-not [string]::IsNullOrEmpty($env:MOCK_LOG)) {
    ($args -join ' ') | Out-File $env:MOCK_LOG -Append -Encoding ascii
}
function Out-Lines([string[]]$Lines) { foreach ($l in $Lines) { Write-Output $l } }

if ($args.Count -ge 1 -and $args[0] -eq 'devices') {
    if ($scenario -eq 'none') { Out-Lines @('List of devices attached', ''); exit 0 }
    if ($scenario -eq 'unauthorized') {
        Out-Lines @('List of devices attached', 'R5CT604K33V unauthorized'); exit 0
    }
    if ($scenario -eq 'multidev') {
        Out-Lines @('List of devices attached',
            'R5CT604K33V device product:b0qsqw model:SM_S908U device:b0q',
            'EMULATOR1 device product:sdk model:sdk device:emulator'); exit 0
    }
    Out-Lines @('List of devices attached',
        'R5CT604K33V device product:b0qsqw model:SM_S908U device:b0q transport_id:1')
    exit 0
}
if ($args.Count -ge 3 -and $args[0] -eq '-s' -and $args[2] -eq 'shell') {
    $rest = ($args[3..($args.Count - 1)] -join ' ')
    if ($rest -match '^getprop ro\.product\.model') { Write-Output 'SM-S908U'; exit 0 }
    if ($rest -match '^getprop ro\.build\.version\.sdk') {
        if ($scenario -eq 'lowsdk') { Write-Output '25' } else { Write-Output '34' }
        exit 0
    }
    if ($rest -match '^getprop ro\.product\.cpu\.abi$') {
        if ($scenario -eq 'wrongabi') { Write-Output 'armeabi-v7a' } else { Write-Output 'arm64-v8a' }
        exit 0
    }
    if ($rest -match '^getprop ro\.product\.cpu\.abilist') {
        if ($scenario -eq 'wrongabi') { Write-Output 'armeabi-v7a,armeabi' }
        else { Write-Output 'arm64-v8a,armeabi-v7a,armeabi' }
        exit 0
    }
    if ($rest -match 'MemTotal') { Write-Output 'MemTotal:       11560104 kB'; exit 0 }
    if ($rest -match '^df /sdcard') {
        $avail = '3000000'
        if ($scenario -eq 'lowstorage') { $avail = '100000' }
        Out-Lines @('Filesystem      1K-blocks    Used Available Use% Mounted on',
            "/dev/block/dm-8 121234568 11111 $avail   1% /sdcard")
        exit 0
    }
    if ($rest -match '^df /data') {
        Out-Lines @('Filesystem      1K-blocks    Used Available Use% Mounted on',
            '/dev/block/dm-9 121234568 11111 9000000   1% /data')
        exit 0
    }
    if ($rest -match 'thermalservice') { Write-Output 'Status: NONE, throttling: false'; exit 0 }
    if ($rest -match 'cmd package list packages') {
        if ($scenario -eq 'fresh' -or $scenario -eq 'bench') {
            if ($scenario -eq 'bench') { Write-Output 'package:ai.edgefit.diag.bench' }
            Write-Output 'package:com.example.other'
        } else {
            Write-Output 'package:ai.edgefit.diag'
            Write-Output 'package:com.example.other'
        }
        exit 0
    }
    if ($rest -match '^stat -c') {
        if ($env:MOCK_MODEL_STATE -eq 'present') {
            Write-Output $env:MOCK_MODEL_SIZE
            exit 0
        }
        Write-Output "stat: '$rest': No such file or directory"
        exit 1
    }
    if ($rest -match '^sha256sum ') {
        Write-Output ("$env:MOCK_MODEL_SHA  /sdcard/Download/EdgeFit/model.gguf")
        exit 0
    }
    if ($rest -match '^run-as ') {
        if ($scenario -eq 'token') {
            Out-Lines @('EdgeFitGlobalVault', 'sessions', 'evidence')
        } else {
            Out-Lines @('sessions', 'evidence')
        }
        exit 0
    }
    if ($rest -match '^mkdir ') { exit 0 }
    Write-Output "mock-adb: unhandled shell: $rest"
    exit 99
}
Write-Output ("mock-adb: refused (mutating or unknown verb): " + ($args -join ' '))
exit 99
