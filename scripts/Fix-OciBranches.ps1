param([string]$CampaignDirectory='reports/oci')
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
Set-Location $root
$manifestPath=Join-Path $root ($CampaignDirectory+'/local-commits.json')
$cases=Get-Content $manifestPath -Raw|ConvertFrom-Json
foreach($case in $cases){
    $initial=Get-Content ($CampaignDirectory+'/results/initial-'+$case.name+'.json') -Raw|ConvertFrom-Json
    if($initial.state -ne 'succeeded' -or $initial.report.headSha -ne $case.headSha -or
        !(Test-Path ($CampaignDirectory+'/results/initial-'+$case.name+'-readback.log'))){throw 'Initial remote review must be verified before fixing its branch'}
    if((& git -C $case.directory status --porcelain)){throw 'Fixture contains uncommitted edits'}
    $head=(& git -C $case.directory rev-parse HEAD).Trim()
    if($case.fixHeadSha){
        if($head -ne $case.fixHeadSha){throw 'Recorded fix differs from local head'}
        continue
    }
    if($head -ne $case.headSha){throw 'Fixture head differs from initial review'}
    $path=Join-Path $case.directory 'src/demo/OciTotals.java'
    $source=[IO.File]::ReadAllText($path)
    if(!$source.Contains('for (int i = 0; i <= values.length; i++) {')){throw 'Expected seeded bounds bug is absent'}
    $source=$source.Replace('for (int i = 0; i <= values.length; i++) {','for (int value : values) {').Replace('total += values[i];','total += value;')
    [IO.File]::WriteAllText($path,$source)
    & git -C $case.directory add -- src/demo/OciTotals.java
    if($LASTEXITCODE){throw 'Cannot stage local fix'}
    & git -C $case.directory commit --quiet -m 'fix: avoid reading past the end of totals input'
    if($LASTEXITCODE){throw 'Cannot commit local fix'}
    $fix=(& git -C $case.directory rev-parse HEAD).Trim()
    $case|Add-Member -NotePropertyName fixHeadSha -NotePropertyValue $fix -Force
    [IO.File]::WriteAllText($manifestPath,(ConvertTo-Json -InputObject @($cases) -Depth 10))
    Write-Output ($case.name+': locally committed fix '+$fix)
}
