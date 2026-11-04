# EF-COMP-01B-AUTO-01 host automation harness (Phase 1).
# PowerShell 5.1 + ADB. Text-resolved UI taps only.
$script:Adb = 'C:\Users\Ron\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$script:Serial = 'R5CT604K33V'
$script:Pkg = 'ai.edgefit.diag.bench'

function Invoke-Adb {
    param([string[]]$AdbArgs, [switch]$Raw)
    $out = & $script:Adb -s $script:Serial @AdbArgs 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) { throw "adb $($AdbArgs -join ' ') failed: $out" }
    return $out
}

function Get-UiDump {
    $xml = Invoke-Adb -AdbArgs @('exec-out', 'uiautomator', 'dump', '/dev/tty')
    $start = $xml.IndexOf('<?xml')
    if ($start -lt 0) { $start = $xml.IndexOf('<hierarchy') }
    if ($start -lt 0) { throw "no XML in uiautomator output: $($xml.Substring(0, [Math]::Min(300, $xml.Length)))" }
    $body = $xml.Substring($start)
    $end = $body.IndexOf('</hierarchy>')
    if ($end -lt 0) { throw 'truncated uiautomator XML' }
    [xml]$doc = $body.Substring(0, $end + '</hierarchy>'.Length)
    return $doc
}

function Find-UiNode {
    param($Doc, [string]$Text, [string]$Contains, [string]$Desc)
    $nodes = $Doc.SelectNodes('//node')
    foreach ($n in $nodes) {
        $t = $n.GetAttribute('text'); $d = $n.GetAttribute('content-desc')
        $hit = $true
        if ($Text) { $hit = $hit -and ($t -eq $Text) }
        if ($Contains) { $hit = $hit -and ($t -like "*$Contains*") }
        if ($Desc) { $hit = $hit -and ($d -ceq $Desc) }
        if ($hit) { return $n }
    }
    return $null
}

function Get-NodeCenter {
    param($Node)
    if ($Node.GetAttribute('bounds') -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
        $cx = ([int]$Matches[1] + [int]$Matches[3]) / 2
        $cy = ([int]$Matches[2] + [int]$Matches[4]) / 2
        return @($cx, $cy)
    }
    throw 'unparseable bounds'
}

function Invoke-UiTap {
    param($Doc, [string]$Text, [string]$Contains, [string]$Desc)
    $n = Find-UiNode -Doc $Doc -Text $Text -Contains $Contains -Desc $Desc
    if ($null -eq $n) { throw "UI node not found Text='$Text' Contains='$Contains' Desc='$Desc'" }
    $c = Get-NodeCenter -Node $n
    Invoke-Adb -AdbArgs @('shell', 'input', 'tap', [int]$c[0], [int]$c[1]) | Out-Null
    return $n
}

function Save-Shot {
    param([string]$Path)
    $cmd = "`"$script:Adb`" -s $($script:Serial) exec-out screencap -p > `"$Path`""
    cmd /c $cmd
    if ($LASTEXITCODE -ne 0) { throw "screencap failed" }
}

function Start-EdgeFit {
    Invoke-Adb -AdbArgs @('shell', 'monkey', '-p', $script:Pkg, '1') | Out-Null
    Start-Sleep -Seconds 3
}

function Assert-Serial {
    $dev = Invoke-Adb -AdbArgs @('devices')
    if ($dev -notmatch $script:Serial) { throw 'target serial not connected' }
}

