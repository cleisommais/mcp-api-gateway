param([string]$Repository = 'cleisommais/pr-test-repo')
$ErrorActionPreference = 'Stop'
if ($Repository -notmatch '^[A-Za-z0-9_-]+(?:/[A-Za-z0-9_.-]+)+$' -or $Repository.Split('/') -contains '..') {
    throw 'Expected namespace/project'
}
$projectRoot = Split-Path -Parent $PSScriptRoot
$entry = Get-Content -LiteralPath (Join-Path $projectRoot '.env') | Where-Object { $_ -match '^\s*GITLAB_PUBLISH_TOKEN\s*=' } | Select-Object -Last 1
if (-not $entry) { throw 'Add GITLAB_PUBLISH_TOKEN with api scope to the ignored .env first' }
$gitlabSetupToken = ($entry -split '=',2)[1].Trim().Trim('"').Trim("'")
if (-not $gitlabSetupToken) { throw 'GITLAB_PUBLISH_TOKEN is empty' }
$headers = @{ 'PRIVATE-TOKEN' = $gitlabSetupToken }
function Request-GitLab([string]$Method, [string]$Path, $Payload = $null, [switch]$AllowMissing) {
    $request = @{ Uri = "https://gitlab.com/api/v4/$Path"; Method = $Method; Headers = $headers }
    if ($null -ne $Payload) {
        $request.ContentType = 'application/json'
        $request.Body = [Text.Encoding]::UTF8.GetBytes(($Payload | ConvertTo-Json -Depth 12 -Compress))
    }
    try { Invoke-RestMethod @request }
    catch {
        $status = [int]$_.Exception.Response.StatusCode
        if ($AllowMissing -and $status -eq 404) { return $null }
        throw "GitLab fixture setup HTTP $status; inspect permissions or reconcile an ambiguous write before rerunning"
    }
}
$project = Request-GitLab GET ('projects/' + [Uri]::EscapeDataString($Repository))
$projectPath = 'projects/' + $project.id
$reference = 'f5/reference'
$source = 'f5/shipping-boundary'
$referenceBranch = Request-GitLab GET ($projectPath + '/repository/branches/' + [Uri]::EscapeDataString($reference)) -AllowMissing
if (-not $referenceBranch) {
    $actions = @('ShippingCalculator.java', 'ShippingCalculatorCheck.java') | ForEach-Object {
        @{ action='create'; file_path="src/demo/$_"; content=[IO.File]::ReadAllText((Join-Path $projectRoot "docs/evaluation/f5/fixture/$_")) }
    }
    $null = Request-GitLab POST ($projectPath + '/repository/commits') @{
        branch=$reference; start_branch=$project.default_branch; commit_message='test: add F5 synthetic shipping reference'; actions=@($actions)
    }
}
$sourceBranch = Request-GitLab GET ($projectPath + '/repository/branches/' + [Uri]::EscapeDataString($source)) -AllowMissing
if (-not $sourceBranch) {
    $correct = [IO.File]::ReadAllText((Join-Path $projectRoot 'docs/evaluation/f5/fixture/ShippingCalculator.java'))
    $broken = $correct.Replace('subtotalCents >= 5000', 'subtotalCents > 5000')
    if ($correct -eq $broken) { throw 'Synthetic mutation did not match the reference' }
    $null = Request-GitLab POST ($projectPath + '/repository/commits') @{
        branch=$source; start_branch=$reference; commit_message='test: change synthetic shipping boundary';
        actions=@(@{ action='update'; file_path='src/demo/ShippingCalculator.java'; content=$broken })
    }
}
$existing = Request-GitLab GET ($projectPath + '/merge_requests?scope=all&state=opened&source_branch=' + [Uri]::EscapeDataString($source))
if (@($existing).Count -gt 1) { throw 'Multiple fixture MRs found; reconcile manually' }
if ($existing) {
    $mr = @($existing)[0]
    if ($mr.target_branch -ne $reference) { throw 'Existing fixture MR has a different target; no changes made' }
} else {
    $mr = Request-GitLab POST ($projectPath + '/merge_requests') @{
        source_branch=$source; target_branch=$reference; title='F5 synthetic fixture: shipping threshold';
        description='Synthetic Java fixture for the MCP review POC. Static review only; do not merge.';
        remove_source_branch=$false
    }
}
@{ platform='gitlab'; repository=$Repository; projectId=$project.id; number=$mr.iid; url=$mr.web_url;
    referenceBranch=$reference; sourceBranch=$source; headSha=$mr.sha } | ConvertTo-Json
