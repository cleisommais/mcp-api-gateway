param([string]$BaseUrl='http://localhost:18081', [int]$TimeoutSeconds=3600)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$headers=@{Authorization='Bearer '+[IO.File]::ReadAllText((Join-Path $root 'secrets/api-token')).Trim()}
$cases=@()
foreach($repo in @('cleisommais/pr-test-repo','cleisommais/pr-test-repo-private')) {
    foreach($number in 1..4) {$cases += [pscustomobject]@{platform='github';repository=$repo;number=$number}}
}
$cases += [pscustomobject]@{platform='gitlab';repository='cleisommais/pr-test-repo';number=1}
$jobs=@()
foreach($case in $cases) {
    $response=Invoke-RestMethod -Uri "$BaseUrl/api/reviews" -Headers $headers -Method Post -ContentType application/json -Body ($case|ConvertTo-Json -Compress)
    $jobs += [pscustomobject]@{platform=$case.platform;repository=$case.repository;number=$case.number;jobId=$response.jobId;statusUrl=$response.statusUrl}
    $jobs|ConvertTo-Json -Depth 4|Set-Content (Join-Path $root 'reports/live-jobs.json')
    Write-Output "$($case.platform) $($case.repository) #$($case.number): $($response.state)"
}
$deadline=[DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
do {
    $results=@()
    foreach($job in $jobs) {
        $status=Invoke-RestMethod -Uri ($BaseUrl+$job.statusUrl) -Headers $headers
        $findings=@($status.report.findings | Where-Object { $null -ne $_ })
        $expectedSeverity=if($job.platform -eq 'gitlab' -or $job.number -eq 3){'MEDIUM'}elseif($job.number -eq 2){$null}else{'HIGH'}
        $expectedCount=if($expectedSeverity){1}else{0}
        $quality=($findings.Count -eq $expectedCount)
        if($expectedCount -eq 1 -and $findings.Count -eq 1){$quality=$quality -and $findings[0].severity -eq $expectedSeverity}
        if($expectedCount -eq 1 -and $findings.Count -eq 1) {
            $expectedFile=if($job.platform -eq 'gitlab'){'src/demo/ShippingCalculator.java'}elseif($job.number -eq 3){'src/demo/Checkout.java'}else{'src/demo/ArrayTotals.java'}
            $expectedLine=if($job.platform -eq 'github' -and $job.number -eq 3){10}elseif($job.platform -eq 'github' -and $job.number -eq 4){9}else{8}
            $quality=$quality -and $findings[0].file -eq $expectedFile -and $findings[0].line -eq $expectedLine
            $explanation=$findings[0].explanation.Replace(',','')
            if($job.platform -eq 'gitlab'){$quality=$quality -and $explanation -match '5000' -and $explanation -match '500'}
            elseif($job.number -eq 3){$quality=$quality -and $explanation -match '\b12\b' -and $explanation -match '1250'}
            else{$quality=$quality -and $explanation -match '(?i)bounds|crash|exception'}
        }
        $results += [pscustomobject]@{platform=$job.platform;repository=$job.repository;number=$job.number;jobId=$job.jobId;state=$status.state;failureCode=$status.failureCode;findings=$findings.Count;actualSeverities=@($findings | ForEach-Object { $_.severity });model=$status.report.model;promptVersion=$status.report.promptVersion;analysisErrors=@($status.report.errors);expectedCount=$expectedCount;expectedSeverity=$expectedSeverity;qualityPassed=$quality;commentUrl=$status.commentUrl;publicationState=$status.publicationState}
    }
    $results|ConvertTo-Json -Depth 5|Set-Content (Join-Path $root 'reports/live-results.json')
    $active=@($results|Where-Object {$_.state -in @('queued','running','publishing')})
    if(!$active.Count){break}
    if([DateTime]::UtcNow -gt $deadline){throw 'Live reviews timed out; inspect reports/live-results.json'}
    Start-Sleep -Seconds 5
}while($true)
foreach($result in $results) {
    $verified=$false
    if($result.commentUrl) {
        docker compose exec -T agent-worker java -cp '/app/mcp-api-gateway.jar:/app/lib/*' com.prreview.tools.QueueAdminCli verify $result.jobId
        $verified=($LASTEXITCODE -eq 0)
    }
    $result|Add-Member -NotePropertyName remoteCommentVerified -NotePropertyValue $verified -Force
}
$results|ConvertTo-Json -Depth 5|Set-Content (Join-Path $root 'reports/live-results.json')
$results|Format-Table platform,repository,number,state,findings,qualityPassed,commentUrl -AutoSize
if(@($results|Where-Object {$_.state -ne 'succeeded' -or !$_.commentUrl -or !$_.qualityPassed -or !$_.remoteCommentVerified}).Count){throw 'Live validation failed; inspect reports/live-results.json'}
