param([int]$TimeoutSeconds=600)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
Set-Location $root
$directory=Join-Path $root 'reports/oci/any-branch'
$cases=Get-Content (Join-Path $directory 'cases.json') -Raw|ConvertFrom-Json
$state=Get-Content reports/oci/host-state.json -Raw|ConvertFrom-Json
$options=@('-i',(Join-Path $root 'secrets/oci-vps-ed25519'),'-o','BatchMode=yes','-o','ConnectTimeout=10','-o',('UserKnownHostsFile='+(Join-Path $root 'secrets/oci-known-hosts')))
$hostAddress='ubuntu@'+$state.publicIp
$headers=@{Authorization='Bearer '+[IO.File]::ReadAllText((Join-Path $root 'reports/oci/bundle/secrets/api-token')).Trim()}
$sql="SELECT coalesce(json_agg(t),'[]'::json) FROM (SELECT j.id,j.platform,j.repository,j.pr_number,j.head_sha,j.base_sha,j.base_branch,d.delivery_id,d.event,d.action FROM review.jobs j JOIN review.deliveries d ON d.job_id=j.id WHERE d.event<>'manual_review') t;"
$encoded=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($sql))
$command='cd /opt/prreview && echo '+$encoded+' | base64 -d | docker compose -f compose.vps.yaml exec -T postgres psql -U mcp_review -d mcp_review -At'
$deadline=[DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
$results=@{}
do {
    $json=(& ssh @options $hostAddress $command)-join "`n"
    if($LASTEXITCODE){throw 'Cannot read persisted webhook evidence'}
    $rows=$json|ConvertFrom-Json
    [IO.File]::WriteAllText((Join-Path $directory 'deliveries.json'),$json)
    foreach($case in $cases){
        if($results.ContainsKey($case.name)){continue}
        $jobs=@($rows|Where-Object{$_.platform -eq $case.platform -and $_.repository -eq $case.repository -and $_.pr_number -eq $case.number -and $_.head_sha.StartsWith($case.headSha) -and $_.base_branch -ceq $case.baseBranch})
        if(!$jobs.Count){continue}
        $job=$jobs[0]
        if($case.oldJobId -and $job.id -eq $case.oldJobId){throw 'Retarget to another branch incorrectly reused the old job'}
        if($case.oldJobId){
            $previous=@($rows|Where-Object{$_.id -eq $case.oldJobId})
            if(!$previous.Count -or $previous[0].base_sha -ne $job.base_sha -or $previous[0].head_sha -ne $job.head_sha){throw 'Retarget test must preserve base/head SHAs while changing destination name'}
        }
        $status=Invoke-RestMethod -Uri ('https://'+$state.publicHost+'/api/reviews/'+$job.id) -Headers $headers -TimeoutSec 30
        if($status.state -in @('queued','running','publishing')){continue}
        [IO.File]::WriteAllText((Join-Path $directory ($case.name+'.json')),($status|ConvertTo-Json -Depth 45))
        if($status.state -ne 'succeeded' -or $status.publicationState -ne 'published' -or
            $status.report.status -ne 'complete' -or $status.report.current -ne $true -or
            $status.report.baseBranch -cne $case.baseBranch -or !$status.report.headSha.StartsWith($case.headSha) -or
            $status.report.enumerationComplete -ne $true -or $status.report.enumeratedFiles -ne $status.report.expectedFiles -or
            @($status.report.errors|Where-Object{$null -ne $_}).Count){throw ('Real any-branch review failed for '+$case.name)}
        $count=@($status.report.findings|Where-Object{$null -ne $_}).Count
        if($case.expectedFindings -ge 0 -and $count -ne $case.expectedFindings){throw 'Fixture clean-result expectation failed'}
        $results[$case.name]=$status
    }
    if($results.Count -eq $cases.Count){break}
    if([DateTime]::UtcNow -gt $deadline){throw 'Timed out waiting for real any-branch webhooks'}
    Start-Sleep -Seconds 5
}while($true)
foreach($case in $cases){
    $status=$results[$case.name]
    $verify='cd /opt/prreview && docker compose -f compose.vps.yaml exec -T agent-worker java -cp /app/mcp-api-gateway.jar:/app/lib/* com.prreview.tools.QueueAdminCli verify '+$status.jobId
    $prior=$ErrorActionPreference;$ErrorActionPreference='Continue'
    try{$output=& ssh @options $hostAddress $verify 2>&1;$verifyExit=$LASTEXITCODE}finally{$ErrorActionPreference=$prior}
    [IO.File]::WriteAllText((Join-Path $directory ($case.name+'-readback.log')),(($output|ForEach-Object{"$_"})-join "`n"))
    if($verifyExit){throw ('Remote comment verification failed: '+$case.name)}
    [pscustomobject]@{name=$case.name;number=$case.number;baseBranch=$status.report.baseBranch;jobId=$status.jobId;findings=@($status.report.findings|Where-Object{$null -ne $_}).Count;commentUrl=$status.commentUrl;remoteReadBackVerified=$true}
}
