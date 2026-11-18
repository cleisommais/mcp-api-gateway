param([string]$Profile='oci-chicago')
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
Set-Location $root
$statePath=Join-Path $root 'reports/oci/host-state.json'
$state=Get-Content $statePath -Raw|ConvertFrom-Json
if(!$state.instanceId -or !$state.stopDeadlineUtc){throw 'Expected an explicitly budgeted paid test instance'}
$common=@('--profile',$Profile,'--auth','security_token','--region',$state.region,'--output','json','--no-retry')
function Invoke-Oci([string[]]$Arguments,[string]$Label) {
    $prior=$ErrorActionPreference
    $ErrorActionPreference='Continue'
    try { $output=& oci @Arguments @common 2>&1; $exitCode=$LASTEXITCODE } finally {$ErrorActionPreference=$prior}
    $text=($output|ForEach-Object{"$_"}) -join "`n"
    [IO.File]::WriteAllText((Join-Path $root ('reports/oci/'+$Label+'.json')),$text)
    if($exitCode){throw ('OCI '+$Label+' failed: '+$text)}
    ($text|ConvertFrom-Json).data
}
function Save-State { [IO.File]::WriteAllText($statePath,($state|ConvertTo-Json -Depth 12)) }
$deadline=[DateTime]::Parse($state.stopDeadlineUtc).ToUniversalTime()
if($deadline -le [DateTime]::UtcNow.AddMinutes(5)){throw 'The test stop deadline is too close or already passed'}
if(!$state.autoStopScheduleId){
    $cron='{0} {1} {2} {3} *' -f $deadline.Minute,$deadline.Hour,$deadline.Day,$deadline.Month
    $request=@{compartmentId=$state.compartmentId;displayName='prreview-test-budget-stop';description='One-time compute stop for the user-authorized USD 1 integration test';action='STOP_RESOURCE';recurrenceType='CRON';recurrenceDetails=$cron;localTimeZone='UTC';timeStarts=[DateTime]::UtcNow.AddMinutes(2).ToString("yyyy-MM-ddTHH:mm:ssZ");timeEnds=$deadline.AddMinutes(5).ToString("yyyy-MM-ddTHH:mm:ssZ");resources=@(@{id=$state.instanceId})}
    $path=Join-Path $root 'reports/oci/autostop-request.json'
    [IO.File]::WriteAllText($path,($request|ConvertTo-Json -Depth 8))
    $schedule=Invoke-Oci @('resource-scheduler','schedule','create','--from-json',('file://'+$path)) 'autostop-created'
    $state|Add-Member -NotePropertyName autoStopScheduleId -NotePropertyValue $schedule.id -Force
    Save-State
}
if(!$state.autoStopPolicyId){
    $statement="Allow any-user to {INSTANCE_READ, INSTANCE_POWER_ACTIONS} in tenancy where all {request.principal.type='resourceschedule', request.principal.id='$($state.autoStopScheduleId)', target.instance.id='$($state.instanceId)'}"
    $request=@{compartmentId=$state.compartmentId;name=('prreview-autostop-'+$state.instanceId.Substring($state.instanceId.Length-8));description='Only this schedule may read and stop/start this single test instance; no create/delete permissions';statements=@($statement)}
    $path=Join-Path $root 'reports/oci/autostop-policy-request.json'
    [IO.File]::WriteAllText($path,($request|ConvertTo-Json -Depth 5))
    $policy=Invoke-Oci @('iam','policy','create','--from-json',('file://'+$path)) 'autostop-policy'
    $state|Add-Member -NotePropertyName autoStopPolicyId -NotePropertyValue $policy.id -Force
    Save-State
}
$schedule=Invoke-Oci @('resource-scheduler','schedule','get','--schedule-id',$state.autoStopScheduleId) 'autostop-verified'
if($schedule.action -ne 'STOP_RESOURCE' -or @($schedule.resources).Count -ne 1 -or $schedule.resources[0].id -ne $state.instanceId){throw 'Auto-stop schedule scope mismatch'}
[pscustomobject]@{action=$schedule.action;state=$schedule.'lifecycle-state';deadlineUTC=$state.stopDeadlineUtc;scheduleId=$schedule.id;policyId=$state.autoStopPolicyId}|ConvertTo-Json
