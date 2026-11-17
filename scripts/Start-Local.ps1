param([switch]$NoBuild)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)
& (Join-Path $PSScriptRoot 'Initialize-LocalEnvironment.ps1')
if (!$NoBuild) { docker compose build; if ($LASTEXITCODE) { throw 'Build failed' } }
docker compose up -d --wait postgres ollama
if ($LASTEXITCODE) { throw 'Infrastructure startup failed' }
$configJson = docker compose config --format json
if ($LASTEXITCODE) { throw 'Compose configuration failed' }
$model = ($configJson | ConvertFrom-Json).services.'agent-worker'.environment.OLLAMA_MODEL
docker compose exec -T ollama ollama show $model *> $null
if ($LASTEXITCODE) { docker compose exec -T ollama ollama pull $model; if ($LASTEXITCODE) { throw 'Model download failed' } }
docker compose up -d --wait --remove-orphans
if ($LASTEXITCODE) { throw 'Application startup failed' }
Write-Output 'Ready: http://localhost:18081/health. Submit a review with scripts/Invoke-Review.ps1.'
