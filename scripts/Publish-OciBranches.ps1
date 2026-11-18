param([ValidateSet('initial','fixed','regression','fixed-again')][string]$Stage='initial',[string]$CampaignDirectory='reports/oci')
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
Set-Location $root
$manifestPath=Join-Path $root ($CampaignDirectory+'/local-commits.json')
$cases=Get-Content $manifestPath -Raw|ConvertFrom-Json
$env:GIT_TERMINAL_PROMPT='0'
$env:GCM_INTERACTIVE='Never'
foreach($case in $cases){
    $expectedHead=switch($Stage){'fixed'{$case.fixHeadSha};'regression'{$case.regressionHeadSha};'fixed-again'{$case.finalFixHeadSha};default{$case.headSha}}
    $previousHead=switch($Stage){'fixed'{$case.headSha};'regression'{$case.fixHeadSha};'fixed-again'{$case.regressionHeadSha};default{$null}}
    if(!$expectedHead){throw 'No locally authored head recorded for this stage'}
    $head=(& git -C $case.directory rev-parse HEAD).Trim()
    if($LASTEXITCODE -or $head -ne $expectedHead){throw 'Local fixture head changed after preparation'}
    if((& git -C $case.directory status --porcelain)){throw 'Local fixture contains uncommitted edits'}
    try{
        if($case.platform -eq 'gitlab' -or $case.platform -eq 'bitbucket'){
            $token=[IO.File]::ReadAllText((Join-Path $root ('secrets/'+$case.platform+'-publish-token'))).Trim()
            $username=if($case.platform -eq 'gitlab'){'oauth2'}else{'x-bitbucket-api-token-auth'}
            $env:GIT_CONFIG_COUNT='1'
            $env:GIT_CONFIG_KEY_0=if($case.platform -eq 'gitlab'){'http.https://gitlab.com/.extraHeader'}else{'http.https://bitbucket.org/.extraHeader'}
            $env:GIT_CONFIG_VALUE_0='Authorization: Basic '+[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($username+':'+$token))
        }
        $ref='refs/heads/'+$case.branch
        $existing=& git -C $case.directory ls-remote --heads origin $ref
        if($LASTEXITCODE){throw 'Cannot inspect remote fixture branch'}
        if($existing){
            $remoteHead=($existing -split '\s+')[0]
            if($remoteHead -ne $expectedHead -and !($previousHead -and $remoteHead -eq $previousHead)){throw 'Remote branch already exists with different commits; no force push is allowed'}
        }
        if(!$existing -or $remoteHead -ne $expectedHead){
            & git -C $case.directory push --porcelain origin ($expectedHead+':'+$ref)
            if($LASTEXITCODE){throw ('Push failed for '+$case.name)}
        }
        $verified=& git -C $case.directory ls-remote --heads origin $ref
        if($LASTEXITCODE -or ($verified -split '\s+')[0] -ne $expectedHead){throw 'Remote branch does not match locally authored head'}
        $case|Add-Member -NotePropertyName remoteHeadVerified -NotePropertyValue $true -Force
        $case|Add-Member -NotePropertyName pushedAtUtc -NotePropertyValue ([DateTime]::UtcNow.ToString('o')) -Force
        [IO.File]::WriteAllText($manifestPath,(ConvertTo-Json -InputObject @($cases) -Depth 10))
        Write-Output ($case.name+': pushed and verified '+$Stage+' local commits at '+$expectedHead)
    }finally{
        Remove-Item Env:GIT_CONFIG_COUNT,Env:GIT_CONFIG_KEY_0,Env:GIT_CONFIG_VALUE_0 -ErrorAction SilentlyContinue
        $token=$null
    }
}
