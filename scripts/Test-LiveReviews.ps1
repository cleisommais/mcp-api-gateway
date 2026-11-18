param([string]$BaseUrl='http://localhost:18081', [int]$TimeoutSeconds=3600, [ValidateSet('existing','bitbucket','all')][string]$Platform='existing', [ValidatePattern('^[a-z0-9-]{1,40}$')][string]$Label, [ValidatePattern('^[a-z][a-z0-9_]{0,62}$')][string]$DatabaseSchema, [ValidateSet('review-original-v3','review-calibrated-v2')][string]$ExpectedPromptVersion, [string]$ExpectedModel, [switch]$SubmitOnly, [switch]$Resume, [switch]$RequireFresh)
$ErrorActionPreference='Stop'
if($SubmitOnly -and $Resume){throw 'SubmitOnly and Resume are mutually exclusive'}
$root=Split-Path -Parent $PSScriptRoot
Set-Location $root
New-Item -ItemType Directory -Force (Join-Path $root 'reports') | Out-Null
$headers=@{Authorization='Bearer '+[IO.File]::ReadAllText((Join-Path $root 'secrets/api-token')).Trim()}
$cases=@()
foreach($repo in @('cleisommais/pr-test-repo','cleisommais/pr-test-repo-private')) {
    foreach($number in 1..4) {$cases += [pscustomobject]@{platform='github';repository=$repo;number=$number}}
}
$cases += [pscustomobject]@{platform='gitlab';repository='cleisommais/pr-test-repo';number=1}
$prefix='live'
if($Platform -in @('bitbucket','all')) {
    $manifest=Get-Content (Join-Path $root 'reports/bitbucket/fixture-manifest.json') -Raw | ConvertFrom-Json
    $definitions=Get-Content (Join-Path $root 'fixtures/bitbucket/cases.json') -Raw | ConvertFrom-Json
    $bitbucketCases=@($manifest.cases)
    if($manifest.repository -ne 'cleisommais/pr-test-repo' -or $manifest.baseBranch -ne 'main' -or $bitbucketCases.Count -ne $definitions.Count) { throw 'Run Initialize-BitbucketFixture first; invalid or incomplete manifest' }
    foreach($case in $bitbucketCases) {
        $definition=@($definitions | Where-Object { $_.id -ceq $case.id })
        if($definition.Count -ne 1 -or $case.platform -ne 'bitbucket' -or $case.repository -ne $manifest.repository -or $case.number -lt 1 -or $case.headSha -notmatch '^[a-f0-9]{40}$' -or $case.baseSha -notmatch '^[a-f0-9]{40}$') { throw 'Invalid Bitbucket fixture identity' }
        $case.expected=$definition[0].expected
    }
    if(@($bitbucketCases.id | Select-Object -Unique).Count -ne $definitions.Count) { throw 'Repeated Bitbucket fixture identity' }
    if($Platform -eq 'all'){$cases+=$bitbucketCases;$prefix='current/live'}else{$cases=$bitbucketCases;$prefix='bitbucket/live'}
}
if($Label) { $prefix=if($Platform -eq 'bitbucket'){'bitbucket/'+$Label}elseif($Platform -eq 'all'){'current/'+$Label}else{$Label} }
New-Item -ItemType Directory -Force (Split-Path -Parent (Join-Path $root ('reports/'+$prefix+'-jobs.json')))|Out-Null
$jobs=@()
if($Resume) {
    $jobs=Get-Content (Join-Path $root ('reports/'+$prefix+'-jobs.json')) -Raw|ConvertFrom-Json
    if($jobs.Count -ne $cases.Count){throw 'Saved job count differs from requested cohort'}
    $savedKeys=@()
    foreach($job in $jobs){
        $matching=@($cases|Where-Object{$_.platform -eq $job.platform -and $_.repository -eq $job.repository -and $_.number -eq $job.number})
        if($matching.Count -ne 1 -or $job.jobId -notmatch '^[a-f0-9-]{36}$' -or $job.statusUrl -ne ('/api/reviews/'+$job.jobId)){throw 'Invalid saved review job identity'}
        if(!$job.PSObject.Properties['fixture']) {
            $fixture=$matching[0]
            foreach($key in @('baseSha','headSha')){if($job.PSObject.Properties[$key]){$fixture|Add-Member -NotePropertyName $key -NotePropertyValue $job.$key -Force}}
            $job|Add-Member -NotePropertyName fixture -NotePropertyValue $fixture -Force
        }
        if($job.platform -eq 'bitbucket'){$job.fixture.expected=$matching[0].expected}
        $savedKeys+=($job.platform+':'+$job.repository+':'+$job.number)
    }
    if(@($savedKeys|Select-Object -Unique).Count -ne $cases.Count){throw 'Repeated saved job identity'}
} else {
foreach($case in $cases) {
    $body=@{platform=$case.platform;repository=$case.repository;number=$case.number}|ConvertTo-Json -Compress
    $response=Invoke-RestMethod -Uri "$BaseUrl/api/reviews" -Headers $headers -Method Post -ContentType application/json -Body $body
    if($RequireFresh -and $response.duplicate){throw 'Existing job returned; use a fresh evaluation queue schema'}
    $jobs += [pscustomobject]@{platform=$case.platform;repository=$case.repository;number=$case.number;jobId=$response.jobId;statusUrl=$response.statusUrl;fixture=$case}
    $jobs|ConvertTo-Json -Depth 8|Set-Content (Join-Path $root ('reports/'+$prefix+'-jobs.json'))
    Write-Output "$($case.platform) $($case.repository) #$($case.number): $($response.state)"
}
}
if($SubmitOnly){return}
$deadline=[DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
do {
    $results=@()
    foreach($job in $jobs) {
        $status=Invoke-RestMethod -Uri ($BaseUrl+$job.statusUrl) -Headers $headers
        $findings=@($status.report.findings | Where-Object { $null -ne $_ })
        if($job.platform -eq 'bitbucket') {
            $expected=$job.fixture.expected
            $detected=($findings.Count -eq $expected.count)
            $severityPassed=$true
            if($detected -and $expected.count -eq 1) {
                $finding=$findings[0]
                $detected=$finding.file -eq $expected.file -and $finding.line -eq $expected.line
                $explanation=$finding.explanation.Replace(',','')
                foreach($pattern in $expected.explanationPatterns) { $detected=$detected -and $explanation -match $pattern }
                $severityPassed=$finding.severity -eq $expected.severity
            }
            $snapshotPassed=$status.report.headSha -eq $job.fixture.headSha -and $status.report.baseSha -eq $job.fixture.baseSha -and $status.report.current -eq $true -and $status.report.enumerationComplete -eq $true -and $status.report.enumeratedFiles -eq $expected.files -and @($status.report.errors | Where-Object { $null -ne $_ }).Count -eq 0
            $results += [pscustomobject]@{platform=$job.platform;repository=$job.repository;number=$job.number;jobId=$job.jobId;case=$job.fixture.id;state=$status.state;failureCode=$status.failureCode;findings=$findings.Count;actualSeverities=@($findings|ForEach-Object {$_.severity});model=$status.report.model;promptVersion=$status.report.promptVersion;analysisErrors=@($status.report.errors);expectedCount=$expected.count;expectedSeverity=$expected.severity;defectDetected=($detected -and $snapshotPassed);severityPassed=$severityPassed;snapshotPassed=$snapshotPassed;qualityPassed=($detected -and $severityPassed -and $snapshotPassed);commentUrl=$status.commentUrl;publicationState=$status.publicationState}
            continue
        }
        $expectedSeverity=if($job.platform -eq 'gitlab' -or $job.number -eq 3){'MEDIUM'}elseif($job.number -eq 2){$null}else{'HIGH'}
        $expectedCount=if($expectedSeverity){1}else{0}
        $quality=($findings.Count -eq $expectedCount)
        $severityPassed=$true
        if($expectedCount -eq 1 -and $findings.Count -eq 1){$severityPassed=$findings[0].severity -eq $expectedSeverity}
        if($expectedCount -eq 1 -and $findings.Count -eq 1) {
            $expectedFile=if($job.platform -eq 'gitlab'){'src/demo/ShippingCalculator.java'}elseif($job.number -eq 3){'src/demo/Checkout.java'}else{'src/demo/ArrayTotals.java'}
            $expectedLine=if($job.platform -eq 'github' -and $job.number -eq 3){10}elseif($job.platform -eq 'github' -and $job.number -eq 4){9}else{8}
            $quality=$quality -and $findings[0].file -eq $expectedFile -and $findings[0].line -eq $expectedLine
            $explanation=$findings[0].explanation.Replace(',','')
            if($job.platform -eq 'gitlab'){$quality=$quality -and $explanation -match '5000' -and $explanation -match '500'}
            elseif($job.number -eq 3){$quality=$quality -and $explanation -match '\b12\b' -and $explanation -match '1250'}
            else{$quality=$quality -and $explanation -match '(?i)bounds|crash|exception'}
        }
        $expectedFiles=$status.report.expectedFiles
        $snapshotPassed=$null -ne $expectedFiles -and $expectedFiles -ge 0 -and $status.report.current -eq $true -and $status.report.enumerationComplete -eq $true -and $status.report.enumeratedFiles -eq $expectedFiles -and @($status.report.errors|Where-Object{$null -ne $_}).Count -eq 0
        if($job.fixture.baseSha){$snapshotPassed=$snapshotPassed -and $status.report.baseSha -eq $job.fixture.baseSha}
        if($job.fixture.headSha){$snapshotPassed=$snapshotPassed -and $status.report.headSha -eq $job.fixture.headSha}
        $results += [pscustomobject]@{platform=$job.platform;repository=$job.repository;number=$job.number;jobId=$job.jobId;state=$status.state;failureCode=$status.failureCode;findings=$findings.Count;actualSeverities=@($findings | ForEach-Object { $_.severity });model=$status.report.model;promptVersion=$status.report.promptVersion;analysisErrors=@($status.report.errors);expectedCount=$expectedCount;expectedSeverity=$expectedSeverity;defectDetected=($quality -and $snapshotPassed);severityPassed=$severityPassed;snapshotPassed=$snapshotPassed;qualityPassed=($quality -and $severityPassed -and $snapshotPassed);commentUrl=$status.commentUrl;publicationState=$status.publicationState}
    }
    if($ExpectedPromptVersion) {
        foreach($result in $results) {
            $profilePassed=$result.promptVersion -eq $ExpectedPromptVersion
            $result|Add-Member -NotePropertyName promptProfilePassed -NotePropertyValue $profilePassed -Force
            $result.qualityPassed=$result.qualityPassed -and $profilePassed
        }
    }
    if($ExpectedModel){foreach($result in $results){$modelPassed=$result.model -eq $ExpectedModel;$result|Add-Member -NotePropertyName modelPassed -NotePropertyValue $modelPassed -Force;$result.qualityPassed=$result.qualityPassed -and $modelPassed}}
    $results|ConvertTo-Json -Depth 5|Set-Content (Join-Path $root ('reports/'+$prefix+'-results.json'))
    $active=@($results|Where-Object {$_.state -in @('queued','running','publishing')})
    if(!$active.Count){break}
    if([DateTime]::UtcNow -gt $deadline){throw ('Live reviews timed out; inspect reports/'+$prefix+'-results.json')}
    Start-Sleep -Seconds 5
}while($true)
foreach($result in $results) {
    $verified=$false
    if($result.commentUrl) {
        $execEnvironment=@()
        if($DatabaseSchema) { $execEnvironment=@('-e',('DATABASE_SCHEMA='+$DatabaseSchema)) }
        docker compose exec -T @execEnvironment agent-worker java -cp '/app/mcp-api-gateway.jar:/app/lib/*' com.prreview.tools.QueueAdminCli verify $result.jobId
        $verified=($LASTEXITCODE -eq 0)
    }
    $result|Add-Member -NotePropertyName remoteCommentVerified -NotePropertyValue $verified -Force
}
$results|ConvertTo-Json -Depth 5|Set-Content (Join-Path $root ('reports/'+$prefix+'-results.json'))
$results|Format-Table platform,repository,number,state,findings,qualityPassed,commentUrl -AutoSize
if(@($results|Where-Object {$_.state -ne 'succeeded' -or !$_.commentUrl -or !$_.qualityPassed -or !$_.remoteCommentVerified}).Count){throw ('Live validation failed; inspect reports/'+$prefix+'-results.json')}
