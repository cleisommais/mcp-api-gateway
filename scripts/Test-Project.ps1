$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)
New-Item -ItemType Directory -Force reports/tests | Out-Null
$composeFiles = @('-f', 'compose.yaml')
if (Test-Path 'compose.override.yaml') { $composeFiles += @('-f', 'compose.override.yaml') }
docker compose @composeFiles -f compose.tools.yaml run --build --rm checks
if ($LASTEXITCODE) { throw 'Test suite failed' }
