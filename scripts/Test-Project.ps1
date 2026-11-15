$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)
New-Item -ItemType Directory -Force reports/tests | Out-Null
docker compose -f compose.yaml -f compose.tools.yaml run --build --rm checks
if ($LASTEXITCODE) { throw 'Test suite failed' }
