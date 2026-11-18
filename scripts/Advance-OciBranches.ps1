param([Parameter(Mandatory=$true)][ValidateSet('regression','fixed-again')][string]$Stage,[string]$CampaignDirectory='reports/oci/webhooks')
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
Set-Location $root
$path=Join-Path $root ($CampaignDirectory+'/local-commits.json')
$cases=Get-Content $path -Raw|ConvertFrom-Json
$property=if($Stage -eq 'regression'){'regressionHeadSha'}else{'finalFixHeadSha'}
$priorStage=if($Stage -eq 'regression'){'fixed'}else{'regression'}
foreach($case in $cases){
    if(!(Test-Path ($CampaignDirectory+'/results/'+$priorStage+'-'+$case.name+'-readback.log'))){throw 'Read back the previous real platform comment before advancing'}
    if((& git -C $case.directory status --porcelain)){throw 'Fixture contains uncommitted changes'}
    $head=(& git -C $case.directory rev-parse HEAD).Trim()
    if($case.$property){if($head -ne $case.$property){throw 'Local head differs from recorded revision'};continue}
    $previous=if($Stage -eq 'regression'){$case.fixHeadSha}else{$case.regressionHeadSha}
    if(!$previous -or $head -ne $previous){throw 'Unexpected previous fixture revision'}
    $sourcePath=Join-Path $case.directory 'src/demo/OciTotals.java'
    $source=[IO.File]::ReadAllText($sourcePath)
    if($Stage -eq 'regression'){
        if(!$source.Contains('for (int value : values) {')){throw 'Expected clean helper before regression'}
        $source=$source.Replace('for (int value : values) {','for (int i = 0; i <= values.length; i++) {').Replace('total += value;','total += values[i];')
    }else{
        if(!$source.Contains('for (int i = 0; i <= values.length; i++) {')){throw 'Expected bounds regression before final fix'}
        $source=$source.Replace('for (int i = 0; i <= values.length; i++) {','for (int value : values) {').Replace('total += values[i];','total += value;')
    }
    [IO.File]::WriteAllText($sourcePath,$source)
    & git -C $case.directory add -- src/demo/OciTotals.java
    if($LASTEXITCODE){throw 'Cannot stage local revision'}
    & git -C $case.directory commit --quiet -m ('test: '+$Stage+' after webhook audit correction')
    if($LASTEXITCODE){throw 'Cannot commit local revision'}
    $revision=(& git -C $case.directory rev-parse HEAD).Trim()
    $case|Add-Member -NotePropertyName $property -NotePropertyValue $revision -Force
    [IO.File]::WriteAllText($path,(ConvertTo-Json -InputObject @($cases) -Depth 10))
    Write-Output ($case.name+': locally committed '+$Stage+' '+$revision)
}
