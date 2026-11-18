param([ValidateSet('initial','fixed','regression','fixed-again')][string]$Stage='initial',[int]$TimeoutSeconds=900,[string]$CampaignDirectory='reports/oci/webhooks',[string[]]$Names=@('github-public','github-private','gitlab','bitbucket'))
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
Set-Location $root
$state=Get-Content reports/oci/host-state.json -Raw|ConvertFrom-Json
$manifest=Get-Content ($CampaignDirectory+'/local-commits.json') -Raw|ConvertFrom-Json
$key=Join-Path $root 'secrets/oci-vps-ed25519'
$known=Join-Path $root 'secrets/oci-known-hosts'
$sshOptions=@('-i',$key,'-o','BatchMode=yes','-o','ConnectTimeout=10','-o',('UserKnownHostsFile='+$known))
$hostAddress='ubuntu@'+$state.publicIp
$headers=@{Authorization='Bearer '+[IO.File]::ReadAllText((Join-Path $root 'reports/oci/bundle/secrets/api-token')).Trim()}
$cases=@()
foreach($name in $Names){
    $case=Get-Content ($CampaignDirectory+'/'+$name+'-pull-request.json') -Raw|ConvertFrom-Json
    if($Stage -ne 'initial'){
        $local=$manifest|Where-Object{$_.name -eq $name}
        $expectedHead=switch($Stage){'fixed'{$local.fixHeadSha};'regression'{$local.regressionHeadSha};'fixed-again'{$local.finalFixHeadSha}}
        if(!$expectedHead){throw ('No locally committed revision recorded for '+$name)}
        $case.headSha=$expectedHead
    }
    $cases+=@($case)
}
$directory=Join-Path $root ($CampaignDirectory+'/results')
New-Item -ItemType Directory -Force $directory|Out-Null
$deadline=[DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
$jobs=@{}
do{
    $sql="SELECT coalesce(json_agg(t),'[]'::json) FROM (SELECT j.platform,j.repository,j.pr_number,j.head_sha,j.id,d.delivery_id,d.event,d.action,d.received_at FROM review.jobs j JOIN review.deliveries d ON d.job_id=j.id WHERE d.event<>'manual_review') t;"
    $encoded=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($sql))
    $command='cd /opt/prreview && echo '+$encoded+' | base64 -d | docker compose -f compose.vps.yaml exec -T postgres psql -U mcp_review -d mcp_review -At'
    $queueJson=(& ssh @sshOptions $hostAddress $command)-join "`n"
    if($LASTEXITCODE){throw 'Cannot read persisted webhook delivery evidence'}
    [IO.File]::WriteAllText((Join-Path $directory ($Stage+'-delivery-evidence.json')),$queueJson)
    $deliveries=$queueJson|ConvertFrom-Json
    foreach($delivery in $deliveries){
        foreach($case in $cases){
            if($case.platform -eq $delivery.platform -and $case.repository -eq $delivery.repository -and
                $case.number -eq $delivery.pr_number -and $case.headSha -ceq $delivery.head_sha){
                $jobs[$case.name]=[guid]$delivery.id
            }
        }
    }
    $logs=& ssh @sshOptions $hostAddress 'cd /opt/prreview && docker compose -f compose.vps.yaml logs --no-color --tail 500 api-gateway'
    if($LASTEXITCODE){throw 'Cannot read PR discovery evidence from the VPS'}
    foreach($line in $logs){
        if($line -match 'Webhook (?<platform>[a-z]+); delivery=(?<delivery>[A-Za-z0-9{}_-]+); event=(?<event>[A-Za-z_:]+); action=[A-Za-z_:]+; repository=(?<repo>[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+); number=(?<number>\d+); head=(?<head>[a-f0-9]+);.* job=(?<job>[a-f0-9-]{36})'){
            $match=$matches.Clone()
            foreach($case in $cases){
                if($case.platform -eq $match.platform -and $case.repository -eq $match.repo -and
                    $case.number -eq [int]$match.number -and $case.headSha.StartsWith($match.head)){
                    $jobs[$case.name]=[guid]($match.job)
                }
            }
        }
    }
    $active=$false
    foreach($case in $cases){
        if(!$jobs.ContainsKey($case.name)){$active=$true;continue}
        $status=Invoke-RestMethod -Uri ('https://'+$state.publicHost+'/api/reviews/'+$jobs[$case.name]) -Headers $headers -TimeoutSec 30
        if($status.state -in @('queued','running','publishing')){$active=$true;continue}
        [IO.File]::WriteAllText((Join-Path $directory ($Stage+'-'+$case.name+'.json')),($status|ConvertTo-Json -Depth 45))
        $expected=if($Stage -in @('initial','regression')){1}else{0}
        $findings=@($status.report.findings|Where-Object{$null -ne $_})
        if($status.state -ne 'succeeded' -or $status.publicationState -ne 'published' -or !$status.commentUrl -or
            $status.report.headSha -cne $case.headSha -or $status.report.current -ne $true -or
            $status.report.status -ne 'complete' -or $status.report.enumerationComplete -ne $true -or
            $status.report.enumeratedFiles -ne 2 -or $status.report.expectedFiles -ne 2 -or
            $status.report.model -ne 'gpt-6-luna' -or $findings.Count -ne $expected -or
            @($status.report.errors|Where-Object{$null -ne $_}).Count -ne 0){
            throw ('OCI live review failed for '+$case.name+'; inspect '+$directory+'/'+$Stage+'-'+$case.name+'.json')
        }
        if($expected -eq 1 -and ($findings[0].file -ne 'src/demo/OciTotals.java' -or $findings[0].line -ne 8)){
            throw 'Finding is not at the seeded added-line defect'
        }
        if($Stage -in @('regression','fixed-again') -and $status.report.cleanReviewAudit.version -ne 'clean-review-audit-v2'){
            throw 'The deployed worker does not contain the corrected audit policy'
        }
        if($Stage -ne 'initial'){
            $initial=Get-Content (Join-Path $directory ('initial-'+$case.name+'.json')) -Raw|ConvertFrom-Json
            if($initial.commentId -ne $status.commentId){throw 'The fix created another summary instead of updating the existing one'}
        }
    }
    if(!$active){break}
    if([DateTime]::UtcNow -gt $deadline){throw 'Timed out waiting for automatically discovered PR reviews on OCI'}
    Start-Sleep -Seconds 5
}while($true)
foreach($case in $cases){
    $job=[guid]$jobs[$case.name]
    $command='cd /opt/prreview && docker compose -f compose.vps.yaml exec -T agent-worker java -cp /app/mcp-api-gateway.jar:/app/lib/* com.prreview.tools.QueueAdminCli verify '+$job
    $prior=$ErrorActionPreference
    $ErrorActionPreference='Continue'
    try{$output=& ssh @sshOptions $hostAddress $command 2>&1;$exitCode=$LASTEXITCODE}finally{$ErrorActionPreference=$prior}
    [IO.File]::WriteAllText((Join-Path $directory ($Stage+'-'+$case.name+'-readback.log')),(($output|ForEach-Object{"$_"})-join "`n"))
    if($exitCode){throw ('Actual remote comment read-back failed for '+$case.name)}
    $status=Get-Content (Join-Path $directory ($Stage+'-'+$case.name+'.json')) -Raw|ConvertFrom-Json
    [pscustomobject]@{name=$case.name;stage=$Stage;jobId=$job;model=$status.report.model;findings=@($status.report.findings|Where-Object{$null-ne $_}).Count;commentUrl=$status.commentUrl;trigger='Provider webhook delivered to OCI';remoteReadBackVerified=$true}
}
