# Mock aapt: prints canned badging. Fails closed on unknown verbs.
$ErrorActionPreference = 'Stop'
if ($args.Count -ge 2 -and $args[0] -eq 'dump' -and $args[1] -eq 'badging') {
    Write-Output "package: name='ai.edgefit.diag' versionCode='2' versionName='0.2.0' platformBuildVersionName='0.2.0'"
    Write-Output "sdkVersion:'26'"
    Write-Output "targetSdkVersion:'35'"
    exit 0
}
Write-Output ('mock-aapt: refused: ' + ($args -join ' '))
exit 99
