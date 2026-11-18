param([string]$Prefix='oci-e2e/20261118/',[string]$CampaignDirectory='reports/oci')
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
Set-Location $root
if($Prefix -notmatch '^[A-Za-z0-9][A-Za-z0-9/_-]+/$'){throw 'Expected an explicit campaign branch prefix ending in /'}
$base=Join-Path $root ($CampaignDirectory+'/fixtures')
New-Item -ItemType Directory -Force $base | Out-Null
$manifestPath=Join-Path $root ($CampaignDirectory+'/local-commits.json')
$manifest=@()
if(Test-Path $manifestPath){$manifest=Get-Content $manifestPath -Raw|ConvertFrom-Json}
$cases=@(
    @{name='github-public';platform='github';repository='cleisommais/pr-test-repo';url='https://github.com/cleisommais/pr-test-repo.git';target='main'},
    @{name='github-private';platform='github';repository='cleisommais/pr-test-repo-private';url='https://github.com/cleisommais/pr-test-repo-private.git';target='main'},
    @{name='gitlab';platform='gitlab';repository='cleisommais/pr-test-repo';url='https://gitlab.com/cleisommais/pr-test-repo.git';target='f5/reference'},
    @{name='bitbucket';platform='bitbucket';repository='cleisommais/pr-test-repo';url='https://bitbucket.org/cleisommais/pr-test-repo.git';target='main'}
)
$correct=@'
package demo;

public final class OciTotals {
    private OciTotals() {}

    public static int sum(int[] values) {
        int total = 0;
        for (int value : values) {
            total += value;
        }
        return total;
    }
}
'@
$broken=$correct.Replace('for (int value : values) {','for (int i = 0; i <= values.length; i++) {').Replace('total += value;','total += values[i];')
$env:GIT_TERMINAL_PROMPT='0'
$env:GCM_INTERACTIVE='Never'
foreach($case in $cases){
    $directory=Join-Path $base $case.name
    $branch=$Prefix+$case.name
    $saved=@($manifest|Where-Object{$_.name -eq $case.name})
    if($saved.Count){
        if($saved.Count -ne 1 -or $saved[0].branch -ne $branch){throw 'Existing manifest belongs to another branch campaign'}
        $head=(& git -C $directory rev-parse HEAD).Trim()
        if($LASTEXITCODE -or $head -ne $saved[0].headSha){throw 'Local campaign branch changed after preparation'}
        Write-Output ($case.name+': reused three prepared local commits at '+$head)
        continue
    }
    if(!(Test-Path (Join-Path $directory '.git'))){
        try {
            if($case.platform -eq 'gitlab' -or $case.platform -eq 'bitbucket'){
                $token=[IO.File]::ReadAllText((Join-Path $root ('secrets/'+$case.platform+'-publish-token'))).Trim()
                $username=if($case.platform -eq 'gitlab'){'oauth2'}else{'x-bitbucket-api-token-auth'}
                $authorization='Basic '+[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($username+':'+$token))
                $env:GIT_CONFIG_COUNT='1'
                $env:GIT_CONFIG_KEY_0=if($case.platform -eq 'gitlab'){'http.https://gitlab.com/.extraHeader'}else{'http.https://bitbucket.org/.extraHeader'}
                $env:GIT_CONFIG_VALUE_0='Authorization: '+$authorization
            }
            & git clone --quiet --single-branch --branch $case.target $case.url $directory
            if($LASTEXITCODE){throw ('Cannot clone '+$case.name+' using existing credentials')}
        } finally {
            Remove-Item Env:GIT_CONFIG_COUNT,Env:GIT_CONFIG_KEY_0,Env:GIT_CONFIG_VALUE_0 -ErrorAction SilentlyContinue
            $token=$null;$authorization=$null
        }
    }
    $origin=(& git -C $directory remote get-url origin).Trim()
    if($origin -ne $case.url){throw 'Unexpected local fixture remote'}
    $dirty=& git -C $directory status --porcelain
    if($dirty){throw 'Fixture checkout contains uncommitted edits'}
    & git -C $directory show-ref --verify --quiet ('refs/heads/'+$branch)
    if($LASTEXITCODE -eq 0){
        & git -C $directory checkout --quiet $branch
        $baseline=(& git -C $directory rev-parse ('refs/remotes/origin/'+$case.target)).Trim()
        $commits=@(& git -C $directory rev-list --reverse ($baseline+'..HEAD'))
        $source=[IO.File]::ReadAllText((Join-Path $directory 'src/demo/OciTotals.java')).Replace("`r`n","`n")
        $expected=($broken+"`n").Replace("`r`n","`n")
        $paths=@(& git -C $directory diff --name-only $baseline HEAD)
        if($commits.Count -ne 3 -or $source -cne $expected -or $paths.Count -ne 2 -or
            $paths -notcontains 'src/demo/OciTotals.java' -or $paths -notcontains 'docs/oci-review-scenario.txt'){
            throw 'Unrecorded local branch differs from the authored three-commit fixture'
        }
        $head=(& git -C $directory rev-parse HEAD).Trim()
        $manifest=@($manifest)+@([pscustomobject]@{name=$case.name;platform=$case.platform;repository=$case.repository;directory=$directory;branch=$branch;target=$case.target;baseSha=$baseline;headSha=$head;commits=$commits;sourceFile='src/demo/OciTotals.java';expectedFindingLine=8;expectedFindings=1})
        [IO.File]::WriteAllText($manifestPath,(ConvertTo-Json -InputObject @($manifest) -Depth 10))
        Write-Output ($case.name+': recovered three already-authored local commits; head='+$head)
        continue
    }
    $baseline=(& git -C $directory rev-parse HEAD).Trim()
    if(Test-Path (Join-Path $directory 'src/demo/OciTotals.java')){throw 'Baseline already contains this campaign fixture'}
    & git -C $directory checkout --quiet -b $branch
    if($LASTEXITCODE){throw 'Cannot create local branch'}
    if(!(& git -C $directory config user.name)){ & git -C $directory config user.name 'PR Review Fixture' }
    if(!(& git -C $directory config user.email)){ & git -C $directory config user.email 'prreview-fixture@example.invalid' }
    New-Item -ItemType Directory -Force (Join-Path $directory 'src/demo'),(Join-Path $directory 'docs') | Out-Null
    $commits=@()
    [IO.File]::WriteAllText((Join-Path $directory 'src/demo/OciTotals.java'),$correct+"`n")
    & git -C $directory add -- src/demo/OciTotals.java
    & git -C $directory commit --quiet -m 'test: add totals implementation for OCI integration'
    if($LASTEXITCODE){throw 'First local commit failed'}
    $commits+=(& git -C $directory rev-parse HEAD).Trim()
    [IO.File]::WriteAllText((Join-Path $directory 'src/demo/OciTotals.java'),$broken+"`n")
    & git -C $directory add -- src/demo/OciTotals.java
    & git -C $directory commit --quiet -m 'test: introduce bounds bug for GPT review'
    if($LASTEXITCODE){throw 'Second local commit failed'}
    $commits+=(& git -C $directory rev-parse HEAD).Trim()
    [IO.File]::WriteAllText((Join-Path $directory 'docs/oci-review-scenario.txt'),"Real integration fixture: three local commits, then PR creation and review on the OCI VPS.`n")
    & git -C $directory add -- docs/oci-review-scenario.txt
    & git -C $directory commit --quiet -m 'docs: describe the OCI PR review exercise'
    if($LASTEXITCODE){throw 'Third local commit failed'}
    $head=(& git -C $directory rev-parse HEAD).Trim()
    $commits+=$head
    $manifest=@($manifest)+@([pscustomobject]@{name=$case.name;platform=$case.platform;repository=$case.repository;directory=$directory;branch=$branch;target=$case.target;baseSha=$baseline;headSha=$head;commits=$commits;sourceFile='src/demo/OciTotals.java';expectedFindingLine=8;expectedFindings=1})
    [IO.File]::WriteAllText($manifestPath,(ConvertTo-Json -InputObject @($manifest) -Depth 10))
    Write-Output ($case.name+': prepared three local commits; head='+$head)
}
Write-Output 'Branches are local only; no push or PR has been opened yet.'
