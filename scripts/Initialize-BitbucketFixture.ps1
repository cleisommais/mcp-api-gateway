param()
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$reports = Join-Path $root 'reports/bitbucket'
New-Item -ItemType Directory -Force $reports | Out-Null
docker run --rm --mount "type=bind,source=$root,target=/workspace,readonly" --mount "type=bind,source=$reports,target=/workspace/reports/bitbucket" --workdir /workspace python:3.12-slim python3 scripts/bitbucket_fixture.py
if ($LASTEXITCODE) { throw 'Bitbucket fixture setup failed; inspect reports/bitbucket/fixture-manifest.json' }
