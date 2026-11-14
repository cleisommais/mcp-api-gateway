$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $projectRoot '.env'
New-Item -ItemType Directory -Force -Path (Join-Path $projectRoot 'secrets') | Out-Null
$content = if (Test-Path -LiteralPath $envPath) { [IO.File]::ReadAllText($envPath) }
    else { [IO.File]::ReadAllText((Join-Path $projectRoot '.env.example')) }
$changed = $false
foreach ($key in @('POSTGRES_PASSWORD', 'GATEWAY_TOKEN', 'WEBHOOK_SECRET')) {
    $pattern = '(?m)^' + $key + '=([^\r\n]*)'
    $match = [regex]::Match($content, $pattern)
    if ($match.Success -and -not [string]::IsNullOrWhiteSpace($match.Groups[1].Value)) { continue }
    $bytes = New-Object byte[] 32
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($bytes) } finally { $random.Dispose() }
    $line = $key + '=' + [Convert]::ToBase64String($bytes)
    if ($match.Success) { $content = [regex]::Replace($content, $pattern, $line) }
    else { $content = $content.TrimEnd() + "`n" + $line + "`n" }
    $changed = $true
}
if ($changed) { [IO.File]::WriteAllText($envPath, $content, (New-Object Text.UTF8Encoding($false))) }
Write-Output 'Local environment ready; existing values preserved and missing credentials generated.'
