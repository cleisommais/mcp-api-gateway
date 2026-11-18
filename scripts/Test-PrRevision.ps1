param(
    [Parameter(Mandatory=$true)][string]$Snapshot,
    [ValidatePattern('^[a-f0-9]{40}$')][string]$ExpectedHead,
    [ValidateSet('api','webhook-replay')][string]$Trigger = 'api',
    [ValidateSet('opened','synchronize')][string]$Action = 'synchronize',
    [Parameter(Mandatory=$true)][int]$ExpectedFindings,
    [Parameter(Mandatory=$true)][ValidatePattern('^[a-z0-9-]{1,40}$')][string]$Label,
    [string]$BaseUrl = 'http://localhost:18081',
    [int]$TimeoutSeconds = 900
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
# Snapshot metadata must be captured from the actual platform for this revision.
$pr = Get-Content -LiteralPath $Snapshot -Raw | ConvertFrom-Json
if ($ExpectedHead -and $pr.head.sha -cne $ExpectedHead) {
    throw 'Captured PR metadata is stale; refresh the snapshot before submitting this revision'
}
$headers = @{Authorization='Bearer '+[IO.File]::ReadAllText((Join-Path $root 'secrets/api-token')).Trim()}
if ($Trigger -eq 'api') {
    $receipt = Invoke-RestMethod -Uri "$BaseUrl/api/reviews" -Method Post -Headers $headers -ContentType application/json -Body (
        @{platform='github';repository=$pr.base.repo.full_name;number=$pr.number} | ConvertTo-Json -Compress)
} else {
    # Explicit signed replay, not proof of GitHub delivery.
    $repositories = Get-Content config/repositories.json -Raw | ConvertFrom-Json
    $repository = @($repositories.repositories | Where-Object {
        $_.name -ceq $pr.base.repo.full_name -and (!$_.platform -or $_.platform -eq 'github')
    })
    if ($repository.Count -ne 1) { throw 'Snapshot repository is not uniquely allowlisted' }
    $installationLine = @(Get-Content .env | Where-Object { $_ -match '^GITHUB_INSTALLATION_ID=' })
    if ($installationLine.Count -ne 1) { throw 'Missing GitHub installation setting' }
    $installation = [long]($installationLine[0].Split('=',2)[1])
    $payload = @{action=$Action; installation=@{id=$installation};
        repository=@{id=$repository[0].id;full_name=$repository[0].name}; number=$pr.number; pull_request=$pr}
    $bytes = [Text.Encoding]::UTF8.GetBytes(($payload | ConvertTo-Json -Depth 30 -Compress))
    $hmac = New-Object Security.Cryptography.HMACSHA256
    try {
        $hmac.Key = [Text.Encoding]::UTF8.GetBytes([IO.File]::ReadAllText((Join-Path $root 'secrets/github-webhook-secret')).Trim())
        $signature = 'sha256=' + ([BitConverter]::ToString($hmac.ComputeHash($bytes))).Replace('-','').ToLowerInvariant()
    } finally { $hmac.Dispose() }
    $receipt = Invoke-RestMethod -Uri "$BaseUrl/webhooks/github" -Method Post -ContentType application/json -Body $bytes -Headers @{
        'X-GitHub-Delivery'=[guid]::NewGuid().ToString(); 'X-GitHub-Event'='pull_request'; 'X-Hub-Signature-256'=$signature
    }
}
if (!$receipt.jobId) { throw 'Request did not enqueue a review' }
$deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
do {
    $status = Invoke-RestMethod -Uri "$BaseUrl/api/reviews/$($receipt.jobId)" -Headers $headers
    if ($status.state -notin @('queued','running','publishing')) { break }
    if ([DateTime]::UtcNow -gt $deadline) { throw "Timed out waiting for $($receipt.jobId)" }
    Start-Sleep -Seconds 3
} while ($true)
$directory = Join-Path $root 'reports/lifecycle'
New-Item -ItemType Directory -Force $directory | Out-Null
$status | ConvertTo-Json -Depth 40 | Set-Content -LiteralPath (Join-Path $directory ($Label+'-status.json'))
$receipt | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $directory ($Label+'-receipt.json'))
$findings = @($status.report.findings | Where-Object { $null -ne $_ })
if ($status.state -ne 'succeeded' -or $status.publicationState -ne 'published' -or !$status.commentUrl -or
    $status.report.headSha -cne $pr.head.sha -or $status.report.baseSha -cne $pr.base.sha -or
    $status.report.current -ne $true -or $status.report.status -ne 'complete' -or
    $status.report.enumerationComplete -ne $true -or $status.report.enumeratedFiles -ne $pr.changed_files -or
    @($status.report.errors | Where-Object { $null -ne $_ }).Count -ne 0 -or $findings.Count -ne $ExpectedFindings) {
    throw "Revision validation failed; inspect reports/lifecycle/$Label-status.json"
}
docker compose exec -T agent-worker java -cp '/app/mcp-api-gateway.jar:/app/lib/*' com.prreview.tools.QueueAdminCli verify $receipt.jobId
if ($LASTEXITCODE) { throw 'Remote comment read-back failed' }
[pscustomobject]@{label=$Label;jobId=$receipt.jobId;headSha=$pr.head.sha;findings=$findings.Count;
    commentUrl=$status.commentUrl;trigger=$Trigger;duplicate=$receipt.duplicate;readBackVerified=$true}
