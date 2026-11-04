# Mock apksigner: prints a canned SHA-256 digest (override via MOCK_CERT).
$ErrorActionPreference = 'Stop'
if ($args.Count -ge 1 -and $args[0] -eq 'verify') {
    $cert = $env:MOCK_CERT
    if ([string]::IsNullOrEmpty($cert)) {
        $cert = '7D:56:31:EA:34:DA:AA:C3:B5:25:7C:23:02:B6:DC:82:EE:A8:1A:0D:54:5E:0E:2E:09:74:12:43:6C:C3:BA:C4'
    }
    Write-Output 'Verifies'
    Write-Output "SHA-256 digest: $cert"
    exit 0
}
Write-Output ('mock-apksigner: refused: ' + ($args -join ' '))
exit 99
