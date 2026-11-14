param(
    [Parameter(Mandatory = $true)][string]$CapturePath,
    [Alias('Profile')][string]$PromptProfile = 'original',
    [string]$Model = 'qwen3:14b',
    [string]$BaseUrl = 'http://localhost:11435',
    [string]$OutputDirectory = 'reports/reasoning-check'
)
$ErrorActionPreference = 'Stop'
$capture = Get-Content -LiteralPath $CapturePath -Raw -Encoding UTF8 | ConvertFrom-Json
$inputs = $capture.inputs.$PromptProfile
if (-not $inputs -or $inputs.Count -ne 1) { throw 'This focused diagnostic requires one captured batch.' }
$inputBatch = @($inputs)[0]
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
foreach ($thinking in @($false, $true)) {
    $payload = @{
        model = $Model
        stream = $false
        think = $thinking
        messages = @(@{role = 'system'; content = $inputBatch.system}, @{role = 'user'; content = $inputBatch.context})
        format = $inputBatch.schema
        options = @{temperature = 0; num_ctx = 8192; num_predict = 4096}
    }
    $response = Invoke-RestMethod -Uri ($BaseUrl.TrimEnd('/') + '/api/chat') -Method Post -ContentType 'application/json' `
        -Body ($payload | ConvertTo-Json -Depth 40 -Compress) -TimeoutSec 180
    $record = @{
        model = $Model; profile = $PromptProfile; thinking = $thinking; maxOutputTokens = 4096
        capturedInputHash = $inputBatch.sha256; headSha = $capture.snapshot.headSha
        evaluationOnly = $true; response = $response
    }
    $name = ($Model -replace '[^A-Za-z0-9_.-]', '-') + '-thinking-' + $thinking.ToString().ToLowerInvariant() + '.json'
    [IO.File]::WriteAllText((Join-Path (Resolve-Path $OutputDirectory).Path $name), ($record | ConvertTo-Json -Depth 40), (New-Object Text.UTF8Encoding($false)))
    Write-Output "Saved $name; completed=$($response.done), stop=$($response.done_reason)"
}
