$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $projectRoot '.env'
if (Test-Path -LiteralPath $envPath) {
    Write-Output 'Existing .env preserved.'
    exit 0
}
$bytes = New-Object byte[] 32
$random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try { $random.GetBytes($bytes) } finally { $random.Dispose() }
$password = [Convert]::ToBase64String($bytes)
$example = [System.IO.File]::ReadAllText((Join-Path $projectRoot '.env.example'))
$content = $example.Replace('POSTGRES_PASSWORD=', 'POSTGRES_PASSWORD=' + $password)
[System.IO.File]::WriteAllText($envPath, $content, (New-Object System.Text.UTF8Encoding($false)))
Write-Output 'Created ignored .env with a random local PostgreSQL password.'
