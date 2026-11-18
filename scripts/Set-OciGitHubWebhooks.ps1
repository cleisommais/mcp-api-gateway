param([ValidateSet('configure','deliveries','redeliver')][string]$Action='configure',
    [string]$Repository='cleisommais/pr-test-repo',[long]$DeliveryId=0)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
Set-Location $root
$repositories=@('cleisommais/pr-test-repo','cleisommais/pr-test-repo-private')
if($Repository -notin $repositories){throw 'Only the two authorized fixture repositories are supported'}
$state=Get-Content reports/oci/host-state.json -Raw|ConvertFrom-Json
$url='https://'+$state.publicHost+'/webhooks/github'
$env:GIT_TERMINAL_PROMPT='0'
$env:GCM_INTERACTIVE='Never'
$credential=@("protocol=https`nhost=github.com`n`n"|git credential fill)
if($LASTEXITCODE){throw 'Cannot authenticate using Git Credential Manager'}
$entry=$credential|Where-Object{$_ -like 'password=*'}|Select-Object -First 1
if(!$entry){throw 'No cached GitHub credential available'}
$token=$entry.Substring(9)
$headers=@{Authorization='Bearer '+$token;Accept='application/vnd.github+json';'X-GitHub-Api-Version'='2022-11-28'}
try {
    $evidence=@()
    foreach($repo in $repositories){
        if($Action -ne 'configure' -and $repo -ne $Repository){continue}
        $endpoint='https://api.github.com/repos/'+$repo+'/hooks'
        $hooks=Invoke-RestMethod -Uri $endpoint -Headers $headers -TimeoutSec 30
        $matches=@($hooks|Where-Object{$_.config.url -eq $url})
        if($matches.Count -gt 1){throw 'More than one hook points at the test callback'}
        if($Action -eq 'configure'){
            if(!$matches.Count){
                $body=@{name='web';active=$true;events=@('pull_request');config=@{url=$url;content_type='json';insecure_ssl='0';secret=[IO.File]::ReadAllText((Join-Path $root 'reports/oci/bundle/secrets/github-webhook-secret')).Trim()}}
                $hook=Invoke-RestMethod -Uri $endpoint -Method Post -Headers $headers -ContentType application/json -Body ($body|ConvertTo-Json -Depth 6) -TimeoutSec 30
            }else{
                $hook=$matches[0]
                if(!$hook.active -or $hook.events -notcontains 'pull_request' -or $hook.config.content_type -ne 'json' -or $hook.config.insecure_ssl -ne '0'){
                    $body=@{active=$true;events=@(@($hook.events)+@('pull_request')|Sort-Object -Unique);config=@{url=$url;content_type='json';insecure_ssl='0'}}
                    $hook=Invoke-RestMethod -Uri ($endpoint+'/'+$hook.id) -Method Patch -Headers $headers -ContentType application/json -Body ($body|ConvertTo-Json -Depth 6) -TimeoutSec 30
                }
            }
            $evidence+=@([pscustomobject]@{repository=$repo;hookId=$hook.id;url=$hook.config.url;contentType=$hook.config.content_type;active=$hook.active;events=@($hook.events)})
        }else{
            if($matches.Count -ne 1){throw 'Webhook has not been configured for this repository'}
            $hook=$matches[0]
            if($Action -eq 'redeliver'){
                if($DeliveryId -lt 1){throw 'Specify a provider delivery ID to redeliver'}
                Invoke-RestMethod -Uri ($endpoint+'/'+$hook.id+'/deliveries/'+$DeliveryId+'/attempts') -Method Post -Headers $headers -TimeoutSec 30|Out-Null
            }
            $rows=Invoke-RestMethod -Uri ($endpoint+'/'+$hook.id+'/deliveries?per_page=30') -Headers $headers -TimeoutSec 30
            $evidence+=@($rows|Select-Object id,guid,event,action,status,status_code,delivered_at,redelivery,duration)
        }
    }
    $path=if($Action -eq 'configure'){'reports/oci/github-repository-hooks.json'}else{'reports/oci/'+($Repository.Split('/')[-1])+'-webhook-deliveries.json'}
    [IO.File]::WriteAllText((Join-Path $root $path),(ConvertTo-Json -InputObject @($evidence) -Depth 8))
    $evidence|ConvertTo-Json -Depth 8
}finally{$credential=$null;$entry=$null;$token=$null;$headers=$null;$body=$null}
