param([ValidateSet('github','gitlab','bitbucket')][string]$Platform = 'github', [string]$Repository = 'cleisommais/pr-test-repo', [int]$Number = 1, [string]$BaseUrl = 'http://localhost:18081', [int]$TimeoutSeconds = 1800)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$headers = @{Authorization='Bearer ' + [IO.File]::ReadAllText((Join-Path $root 'secrets/api-token')).Trim()}
$body = @{platform=$Platform; repository=$Repository; number=$Number} | ConvertTo-Json -Compress
$created = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/reviews" -Headers $headers -ContentType 'application/json' -Body $body
Write-Output "Job $($created.jobId); duplicate=$($created.duplicate)"
$deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
do {
    $status = Invoke-RestMethod -Uri ($BaseUrl + $created.statusUrl) -Headers $headers
    if ($status.state -notin @('queued','running','publishing')) { break }
    if ([DateTime]::UtcNow -gt $deadline) { throw "Timed out waiting for $($created.jobId)" }
    Start-Sleep -Seconds 3
} while ($true)
$status
if ($status.state -ne 'succeeded' -or !$status.commentUrl) { throw "Review did not complete with publication: $($status.state); $($status.failureCode)" }
