# EdgeFit AI EF-ANDROID-CONSENT-INSTALL-GATE-01 self-test.
# White-box: dot-sources EFRecoveryLib.ps1, drives
# Test-EFConsentActivation with fixtures plus the live
# GlobalContract.kt source. No device, no network, no repo writes.
# Exit 0 = all pass, 1 = failure.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File scripts/Test-EFConsentGate01.ps1

$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot 'EFRecoveryLib.ps1')

$SCRIPT:Pass = 0
$SCRIPT:Fail = 0
$SCRIPT:Failures = New-Object System.Collections.ArrayList

function Assert-True([bool]$Cond, [string]$Name) {
    if ($Cond) {
        $SCRIPT:Pass++
        Write-Output "  PASS $Name"
    } else {
        $SCRIPT:Fail++
        [void]$SCRIPT:Failures.Add($Name)
        Write-Output "  FAIL $Name"
    }
}

$VER = 'ef-consent-01'
$SHA = 'B83E78FBF78AD0FBE4E77CFBBCF37709519A4305A45D86FFCCFF6C72E78CDB21'

$GOOD_CONTRACT = @"
object GlobalContract {
    const val CONSENT_POLICY_VERSION = "$VER"
    val CONSENT_SCOPES = listOf("aggregates")
    // Policy SHA-256 $SHA (approval commit 6e09295).
    const val CONSENT_POLICY_APPROVED = true
}
"@

$GOOD_APPROVAL = @"
- Policy text SHA-256: ``$SHA``
- [x] A. COLLECTION APPROVED - genuine-device uploads stamping
- [ ] B. PUBLICATION APPROVED - rows meeting version rules
"@

function Invoke-Gate($contract, $approval, $blob) {
    $why = ''
    $ok = Test-EFConsentActivation $contract $VER $SHA $approval $blob ([ref]$why)
    return @($ok, $why)
}

# 1. Correct approved policy passes.
$r = Invoke-Gate $GOOD_CONTRACT $GOOD_APPROVAL $SHA
Assert-True ($r[0] -eq $true) 'approved policy passes'

# 2. Wrong policy version blocks.
$bad = $GOOD_CONTRACT.Replace($VER, 'ef-consent-02')
$r = Invoke-Gate $bad $GOOD_APPROVAL $SHA
Assert-True (($r[0] -eq $false) -and ($r[1] -match 'version')) 'wrong policy version blocks'

# 3. Unapproved activation (flag false) blocks.
$bad = $GOOD_CONTRACT.Replace('= true', '= false')
$r = Invoke-Gate $bad $GOOD_APPROVAL $SHA
Assert-True (($r[0] -eq $false) -and ($r[1] -match 'approved-true')) 'flag false blocks'

# 4. Wrong policy blob SHA blocks.
$r = Invoke-Gate $GOOD_CONTRACT $GOOD_APPROVAL ('0' * 64)
Assert-True (($r[0] -eq $false) -and ($r[1] -match 'blob hash')) 'wrong blob SHA blocks'

# 5. Missing approval evidence blocks.
$r = Invoke-Gate $GOOD_CONTRACT '' $SHA
Assert-True (($r[0] -eq $false) -and ($r[1] -match 'evidence missing')) 'missing approval blocks'

# 6. Publication B approval blocks.
$bad = $GOOD_APPROVAL.Replace('[ ] B. PUBLICATION', '[x] B. PUBLICATION')
$r = Invoke-Gate $GOOD_CONTRACT $bad $SHA
Assert-True (($r[0] -eq $false) -and ($r[1] -match 'Publication B')) 'B approval blocks'

# 7. Widened scopes block.
$bad = $GOOD_CONTRACT.Replace('listOf("aggregates")', 'listOf("aggregates", "runs")')
$r = Invoke-Gate $bad $GOOD_APPROVAL $SHA
Assert-True (($r[0] -eq $false) -and ($r[1] -match 'scopes')) 'widened scopes block'

# 8. Missing SHA citation blocks.
$bad = $GOOD_CONTRACT.Replace($SHA, '')
$r = Invoke-Gate $bad $GOOD_APPROVAL $SHA
Assert-True (($r[0] -eq $false) -and ($r[1] -match 'SHA not cited')) 'missing SHA citation blocks'

# 9. Live GlobalContract.kt passes with expected evidence.
$live = Get-Content (Join-Path (Split-Path $PSScriptRoot -Parent) 'android/app/src/main/java/ai/edgefit/diag/GlobalContract.kt') -Raw
$r = Invoke-Gate $live $GOOD_APPROVAL $SHA
Assert-True ($r[0] -eq $true) 'live source passes'

Write-Output ("PASS={0} FAIL={1}" -f $SCRIPT:Pass, $SCRIPT:Fail)
if ($SCRIPT:Fail -gt 0) {
    Write-Output ("failures: " + ($SCRIPT:Failures -join '; '))
    exit 1
}
exit 0
