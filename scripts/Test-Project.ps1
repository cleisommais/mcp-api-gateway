# Build-time tests deliberately have no database; this run supplies the project PostgreSQL connection.
Push-Location -LiteralPath (Split-Path -Parent $PSScriptRoot) -ErrorAction Stop
try {
    & docker compose --profile testing build checks
    if ($LASTEXITCODE -ne 0) { throw 'Checks image build failed' }
    & docker compose --profile testing run --rm checks --batch-mode --no-transfer-progress test
    if ($LASTEXITCODE -ne 0) { throw 'Project tests failed' }
} finally { Pop-Location }
