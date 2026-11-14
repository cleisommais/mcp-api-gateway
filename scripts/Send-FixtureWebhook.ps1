param(
    [Parameter(Mandatory = $true)][string]$PayloadPath,
    [string]$Url = 'http://localhost:18081/webhooks/github',
    [string]$DeliveryId = [Guid]::NewGuid().ToString(),
    [string]$EventType = 'pull_request'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$content = [IO.File]::ReadAllText((Join-Path $projectRoot '.env'))
$match = [regex]::Match($content, '(?m)^WEBHOOK_SECRET=([^\r\n]+)')
if (-not $match.Success) { throw 'Initialize the local webhook secret first.' }
$bytes = [IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $PayloadPath))
$signer = New-Object Security.Cryptography.HMACSHA256
try {
    $signer.Key = [Text.Encoding]::UTF8.GetBytes($match.Groups[1].Value)
    $digest = [BitConverter]::ToString($signer.ComputeHash($bytes)).Replace('-', '').ToLowerInvariant()
} finally { $signer.Dispose() }
$headers = @{'X-GitHub-Delivery'=$DeliveryId; 'X-GitHub-Event'=$EventType; 'X-Hub-Signature-256'=('sha256='+$digest)}
$response = Invoke-WebRequest -Uri $Url -Method Post -Headers $headers -ContentType 'application/json' -Body $bytes -UseBasicParsing
Write-Output "HTTP $($response.StatusCode)"
Write-Output $response.Content
