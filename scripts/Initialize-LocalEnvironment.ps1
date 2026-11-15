$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$secretRoot = Join-Path $projectRoot 'secrets'
New-Item -ItemType Directory -Force $secretRoot | Out-Null
$envPath = Join-Path $projectRoot '.env'
if (!(Test-Path $envPath)) { Copy-Item (Join-Path $projectRoot '.env.example') $envPath }
foreach ($name in @('database-password','mcp-gateway-token','github-webhook-secret','api-token','gitlab-read-token','gitlab-publish-token','openai-api-key','github-app.pem')) {
    $path = Join-Path $secretRoot $name
    if (Test-Path $path) { continue }
    $value = ''
    if ($name -in @('database-password','mcp-gateway-token','github-webhook-secret','api-token')) {
        $bytes = New-Object byte[] 32
        $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
        try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
        $value = [Convert]::ToBase64String($bytes)
    }
    [IO.File]::WriteAllText($path, $value, (New-Object Text.UTF8Encoding($false)))
}
Write-Output 'Credential files initialized in secrets/; existing files preserved.'
