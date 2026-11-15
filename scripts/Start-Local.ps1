param([switch]$NoBuild)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)
& powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Initialize-LocalEnvironment.ps1
if (!$NoBuild) { docker compose build; if ($LASTEXITCODE) { throw 'Build failed' } }
docker compose up -d --wait postgres ollama
if ($LASTEXITCODE) { throw 'Infrastructure startup failed' }
$model = 'gpt-oss:20b'
$match = [regex]::Match([IO.File]::ReadAllText((Join-Path (Get-Location) '.env')), '(?m)^OLLAMA_MODEL=([^\r\n]+)')
if ($match.Success) { $model = $match.Groups[1].Value }
docker compose exec -T ollama ollama show $model *> $null
if ($LASTEXITCODE) { docker compose exec -T ollama ollama pull $model; if ($LASTEXITCODE) { throw 'Model download failed' } }
docker compose up -d --wait --remove-orphans
if ($LASTEXITCODE) { throw 'Application startup failed' }
Write-Output 'Ready: http://localhost:18081/health. Submit a review with scripts/Invoke-Review.ps1.'
