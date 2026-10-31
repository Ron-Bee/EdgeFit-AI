# EF-COMP-01B-PREP owner preflight (offline).
# Runs the corpus hash check, then the JVM study runner
# (freeze + R decision + M0/M1 payloads), then reports
# the READY / BLOCKED verdict. No live calls, no
# benchmarks, no database writes.
#
# Usage (PowerShell 5.1, from the repo root):
#   $env:EDGEFIT_EVIDENCE = 'C:\Users\Ron\EdgeFit-Evidence'
#   .\scripts\ef-comp-01b-prep.ps1
#
# Optional:
#   .\scripts\ef-comp-01b-prep.ps1 -OutDir eval\comp-01b `
#     -CorpusPath eval\comp-01a-corpus.json -Ids 'run-b-benchmark,run-c-benchmark,run-d-benchmark'
param(
  [string]$OutDir = 'eval\comp-01b',
  [string]$CorpusPath = 'eval\comp-01a-corpus.json',
  [string]$Ids = 'run-b-benchmark,run-c-benchmark,run-d-benchmark'
)

$ErrorActionPreference = 'Stop'

function Repo-Root {
  return (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
}

$root = Repo-Root
Set-Location $root

if (-not $env:EDGEFIT_EVIDENCE) {
  Write-Error 'EDGEFIT_EVIDENCE must name the evidence directory.'
  exit 2
}

Write-Host '== 1/3 corpus hash check (node) =='
& node scripts/ef-comp-01a-manifest.mjs --manifest $CorpusPath
if ($LASTEXITCODE -ne 0) {
  Write-Error 'Corpus manifest check failed; study BLOCKED.'
  exit 1
}

Write-Host '== 2/3 JVM study preflight (freeze + R + payloads) =='
$env:EF_STUDY_OUT = (Join-Path $root $OutDir)
$env:EF_STUDY_CORPUS = (Join-Path $root $CorpusPath)
$env:EF_STUDY_IDS = $Ids
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$gradle = Get-ChildItem "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.9-bin\*\gradle-8.9\bin\gradle.bat" |
  Select-Object -First 1 -ExpandProperty FullName
if (-not $gradle) {
  Write-Error 'Gradle 8.9 wrapper distribution not found.'
  exit 2
}
& $gradle -p android :app:testBenchmarkUnitTest --offline --tests 'ai.edgefit.diag.EvalStudyRunnerTest'
if ($LASTEXITCODE -ne 0) {
  Write-Error 'JVM study runner failed; study BLOCKED.'
  exit 1
}

Write-Host '== 3/3 verdict =='
$verdictPath = Join-Path $env:EF_STUDY_OUT 'study-verdict.json'
if (-not (Test-Path $verdictPath)) {
  Write-Error 'study-verdict.json missing; study BLOCKED.'
  exit 1
}
$verdict = Get-Content $verdictPath -Raw | ConvertFrom-Json
Write-Host ("verdict: " + $verdict.verdict)
foreach ($p in $verdict.problems) { Write-Host ("problem: " + $p) }
Write-Host ("artifacts: " + $verdictPath)
if ($verdict.verdict -ne 'READY') { exit 1 }
exit 0
