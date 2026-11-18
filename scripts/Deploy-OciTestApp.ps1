param([switch]$PrepareOnly)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
Set-Location $root
$state=Get-Content reports/oci/host-state.json -Raw|ConvertFrom-Json
if(!$state.publicIp){throw 'A reachable VPS must be prepared first'}
if($state.allocationMode -like 'paid-*' -and (!$state.autoStopScheduleId -or !$state.autoStopPolicyId)){throw 'Paid test requires control-plane auto-stop first'}
if(!(Test-Path dist/mcp-api-gateway.jar)){throw 'Build/export the portable Java artifact to dist first'}
$known=Join-Path $root 'secrets/oci-known-hosts'
$key=Join-Path $root 'secrets/oci-vps-ed25519'
$sshOptions=@('-i',$key,'-o','BatchMode=yes','-o','ConnectTimeout=10','-o','StrictHostKeyChecking=accept-new','-o',('UserKnownHostsFile='+$known))
$hostAddress='ubuntu@'+$state.publicIp
$uid=(& ssh @sshOptions $hostAddress 'id -u').Trim()
if($LASTEXITCODE -or $uid -notmatch '^\d+$'){throw 'Cannot determine remote service owner'}
$gid=(& ssh @sshOptions $hostAddress 'id -g').Trim()
if($LASTEXITCODE -or $gid -notmatch '^\d+$'){throw 'Cannot determine remote service group'}
& ssh @sshOptions $hostAddress 'test -f /var/lib/cloud/prreview-bootstrap-complete && docker compose version'
if($LASTEXITCODE){throw 'Cloud bootstrap is incomplete'}
$bundle=Join-Path $root 'reports/oci/bundle'
foreach($directory in @('config','deploy/oci','dist/lib','secrets','reports')){New-Item -ItemType Directory -Force (Join-Path $bundle $directory)|Out-Null}
foreach($path in @('compose.vps.yaml','.dockerignore','deploy/oci/Dockerfile','deploy/oci/Caddyfile','deploy/oci/start.sh','config/gateway.json','config/repositories.json','dist/mcp-api-gateway.jar')){
    Copy-Item -LiteralPath (Join-Path $root $path) -Destination (Join-Path $bundle $path) -Force
}
Get-ChildItem (Join-Path $root 'dist/lib') -File|ForEach-Object{Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $bundle 'dist/lib') -Force}
$rng=[Security.Cryptography.RandomNumberGenerator]::Create()
try{
    foreach($name in @('database-password','api-token','mcp-gateway-token','github-webhook-secret','gitlab-webhook-secret','bitbucket-webhook-secret')){
        $destination=Join-Path $bundle ('secrets/'+$name)
        if(!(Test-Path $destination)){
            $bytes=New-Object byte[] 32
            $rng.GetBytes($bytes)
            [IO.File]::WriteAllText($destination,([BitConverter]::ToString($bytes).Replace('-','').ToLowerInvariant()))
        }
    }
}finally{$rng.Dispose()}
# Explicit credential allowlist: the OCI SSH private key is never part of the upload.
foreach($name in @('github-app.pem','gitlab-read-token','gitlab-publish-token','bitbucket-read-token','bitbucket-publish-token','openai-api-key')){
    $source=Join-Path $root ('secrets/'+$name)
    if(!(Test-Path $source) -or (Get-Item $source).Length -eq 0){throw ('Missing configured credential file '+$name)}
    Copy-Item -LiteralPath $source -Destination (Join-Path $bundle ('secrets/'+$name)) -Force
}
$settings=@{}
foreach($line in Get-Content .env){if($line -match '^([A-Z0-9_]+)=(.*)$'){$settings[$matches[1]]=$matches[2].Trim().Trim('"').Trim("'")}}
$environment=@(
    "GITHUB_APP_ID=$($settings.GITHUB_APP_ID)",
    "GITHUB_INSTALLATION_ID=$($settings.GITHUB_INSTALLATION_ID)",
    "BITBUCKET_EMAIL=$($settings.BITBUCKET_EMAIL)",
    "LOCAL_UID=$uid",
    "LOCAL_GID=$gid",
    "PUBLIC_HOST=$($state.publicHost)",
    'EXTERNAL_MAX_COST_USD=0.20'
)
[IO.File]::WriteAllText((Join-Path $bundle '.env'),(($environment -join "`n")+"`n"))
$archive=Join-Path $root 'reports/oci/deploy-bundle.tar.gz'
$approvedSecrets=@('database-password','api-token','mcp-gateway-token','github-webhook-secret','gitlab-webhook-secret','bitbucket-webhook-secret','github-app.pem','gitlab-read-token','gitlab-publish-token','bitbucket-read-token','bitbucket-publish-token','openai-api-key')
$actualSecrets=@(Get-ChildItem (Join-Path $bundle 'secrets') -File|ForEach-Object{$_.Name})
if(Compare-Object ($approvedSecrets|Sort-Object) ($actualSecrets|Sort-Object)){throw 'Staged credentials differ from the explicit deployment allowlist'}
$payload=@('.env','.dockerignore','compose.vps.yaml','deploy/oci/Dockerfile','deploy/oci/Caddyfile','deploy/oci/start.sh','config/gateway.json','config/repositories.json','dist/mcp-api-gateway.jar')
$payload+=@(Get-ChildItem (Join-Path $bundle 'dist/lib') -File|ForEach-Object{'dist/lib/'+$_.Name})
$payload+=@($approvedSecrets|ForEach-Object{'secrets/'+$_})
$payloadList=Join-Path $root 'reports/oci/deploy-payload.txt'
[IO.File]::WriteAllText($payloadList,(($payload -join "`n")+"`n"))
& tar -czf $archive -C $bundle -T $payloadList
if($LASTEXITCODE){throw 'Cannot create deployment bundle'}
if($PrepareOnly){
    Write-Output ('Prepared '+$payload.Count+' explicitly listed files for '+$state.publicIp)
    $approvedSecrets|ForEach-Object{Write-Output ('Credential file: '+$_)}
    Write-Output 'No upload performed. OCI/SSH private keys are excluded.'
    return
}
& scp @sshOptions $archive ($hostAddress+':/opt/prreview/deploy-bundle.tar.gz')
if($LASTEXITCODE){throw 'Encrypted bundle upload failed'}
& ssh @sshOptions $hostAddress 'tar -xzf /opt/prreview/deploy-bundle.tar.gz -C /opt/prreview && bash /opt/prreview/deploy/oci/start.sh'
if($LASTEXITCODE){throw 'Remote Compose startup failed'}
Write-Output ('Deployed API endpoint: https://'+$state.publicHost)
