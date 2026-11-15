param([ValidateSet('openai','ollama')][string]$Provider='openai', [Parameter(Mandatory=$true)][string]$Label)
$ErrorActionPreference='Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)
$env:REGRESSION_PROVIDER=$Provider
docker compose -f compose.yaml -f compose.tools.yaml run --build --rm model-regression capture
if($LASTEXITCODE){throw 'Public fixture capture failed'}
docker compose -f compose.yaml -f compose.tools.yaml run --rm model-regression run $Label
if($LASTEXITCODE){throw 'Regression failed; inspect reports/model-regression/'+$Label+'/results.json'}
