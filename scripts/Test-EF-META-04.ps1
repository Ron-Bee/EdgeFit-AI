# EdgeFit AI EF-META-04
# Local tool argument validation qualification.
# No network or Meta API calls.

$ErrorActionPreference = 'Stop'

Add-Type -AssemblyName System.Web.Extensions

function Test-EdgeFitArguments {
    param([object]$InputArgs)

    # Accept only a JSON dictionary.
    if ($null -eq $InputArgs -or
        $InputArgs -isnot [System.Collections.IDictionary]) {
        throw 'INVALID_OBJECT'
    }

    $expected = @(
        'device_class',
        'model_id',
        'limit'
    )

    # Examine actual JSON field names, not PowerShell properties.
    $names = @($InputArgs.Keys)

    foreach ($name in $names) {
        if ($name -cnotin $expected) {
            throw 'UNAUTHORIZED_ARGUMENT'
        }
    }

    foreach ($name in $expected) {
        if ($name -cnotin $names) {
            throw 'MISSING_ARGUMENT'
        }
    }

    if ($InputArgs['device_class'] -isnot [string] -or
        $InputArgs['device_class'] -cne 'android-8gb-test') {
        throw 'INVALID_DEVICE'
    }

    if ($InputArgs['model_id'] -isnot [string] -or
        $InputArgs['model_id'] -cne 'llama-3.2-1b') {
        throw 'INVALID_MODEL'
    }

    $limit = $InputArgs['limit']

    if ($limit -isnot [int] -and
        $limit -isnot [long]) {
        throw 'INVALID_LIMIT_TYPE'
    }

    if ($limit -lt 1 -or $limit -gt 3) {
        throw 'OUT_OF_RANGE'
    }

    return $true
}

function Invoke-AuthorizedTool {
    param(
        [string]$Name,
        [string]$ArgumentJson
    )

    if ($Name -cne 'retrieve_comparable_benchmarks') {
        throw 'UNAUTHORIZED_TOOL'
    }

    if ([string]::IsNullOrWhiteSpace($ArgumentJson)) {
        throw 'INVALID_OBJECT'
    }

    try {
        $serializer = New-Object `
            System.Web.Script.Serialization.JavaScriptSerializer

        $root = $serializer.DeserializeObject($ArgumentJson)
    }
    catch {
        throw 'MALFORMED_JSON'
    }

    # Arrays, primitives and null must be rejected.
    if ($null -eq $root -or
        $root -isnot [System.Collections.IDictionary]) {
        throw 'INVALID_OBJECT'
    }

    # Validate the dictionary directly.
    Test-EdgeFitArguments -InputArgs $root
}

# ------------------------------------------------------------
# Test matrix
# ------------------------------------------------------------

$valid = '{"device_class":"android-8gb-test","model_id":"llama-3.2-1b","limit":3}'

$cases = @(
    @{
        name='valid minimum'
        json='{"device_class":"android-8gb-test","model_id":"llama-3.2-1b","limit":1}'
        tool='retrieve_comparable_benchmarks'
        expect='ACCEPT'
    },
    @{
        name='valid maximum'
        json=$valid
        tool='retrieve_comparable_benchmarks'
        expect='ACCEPT'
    },
    @{
        name='missing field'
        json='{"device_class":"android-8gb-test","model_id":"llama-3.2-1b"}'
        tool='retrieve_comparable_benchmarks'
        expect='MISSING_ARGUMENT'
    },
    @{
        name='extra field'
        json='{"device_class":"android-8gb-test","model_id":"llama-3.2-1b","limit":1,"execute_shell":"cmd"}'
        tool='retrieve_comparable_benchmarks'
        expect='UNAUTHORIZED_ARGUMENT'
    },
    @{
        name='limit too large'
        json='{"device_class":"android-8gb-test","model_id":"llama-3.2-1b","limit":999}'
        tool='retrieve_comparable_benchmarks'
        expect='OUT_OF_RANGE'
    },
    @{
        name='limit zero'
        json='{"device_class":"android-8gb-test","model_id":"llama-3.2-1b","limit":0}'
        tool='retrieve_comparable_benchmarks'
        expect='OUT_OF_RANGE'
    },
    @{
        name='limit as text'
        json='{"device_class":"android-8gb-test","model_id":"llama-3.2-1b","limit":"3"}'
        tool='retrieve_comparable_benchmarks'
        expect='INVALID_LIMIT_TYPE'
    },
    @{
        name='unapproved device'
        json='{"device_class":"other","model_id":"llama-3.2-1b","limit":1}'
        tool='retrieve_comparable_benchmarks'
        expect='INVALID_DEVICE'
    },
    @{
        name='unapproved model'
        json='{"device_class":"android-8gb-test","model_id":"unknown","limit":1}'
        tool='retrieve_comparable_benchmarks'
        expect='INVALID_MODEL'
    },
    @{
        name='malformed JSON'
        json='{bad'
        tool='retrieve_comparable_benchmarks'
        expect='MALFORMED_JSON'
    },
    @{
        name='wrong top-level type'
        json='[1,2,3]'
        tool='retrieve_comparable_benchmarks'
        expect='INVALID_OBJECT'
    },
    @{
        name='unauthorized tool'
        json=$valid
        tool='run_shell'
        expect='UNAUTHORIZED_TOOL'
    }
)

# ------------------------------------------------------------
# Execute test matrix
# ------------------------------------------------------------

$results = foreach ($case in $cases) {
    $actual = 'ACCEPT'

    try {
        $null = Invoke-AuthorizedTool `
            -Name $case.tool `
            -ArgumentJson $case.json
    }
    catch {
        $actual = $_.Exception.Message
    }

    [pscustomobject]@{
        Test = $case.name
        Expected = $case.expect
        Actual = $actual
        Pass = ($actual -ceq $case.expect)
    }
}

$results | Format-Table -AutoSize

$pass = @($results | Where-Object { $_.Pass }).Count
$total = $results.Count

Write-Host ""
Write-Host "====================================" -ForegroundColor Cyan
Write-Host "EF-META-04 QUALIFICATION RESULTS"
Write-Host "====================================" -ForegroundColor Cyan
Write-Host "Passed: $pass/$total"

if ($pass -eq $total) {
    Write-Host "EF-META-04 LOCAL VALIDATOR: PASS" -ForegroundColor Green
}
else {
    Write-Host "EF-META-04 LOCAL VALIDATOR: FAIL" -ForegroundColor Red
}

Write-Host "Production dispatcher: NOT QUALIFIED"
Write-Host "Live invalid model requests: NOT QUALIFIED"
Write-Host "Meta API calls: NONE"

if ($pass -ne $total) {
    exit 1
}