param([string]$Profile='oci-chicago', [string]$Region='us-chicago-1', [ValidateSet(2,4,6)][int]$MemoryGB=6, [switch]$PaidTest)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
Set-Location $root
$reports=Join-Path $root 'reports/oci'
New-Item -ItemType Directory -Force $reports | Out-Null
$config=[IO.File]::ReadAllText((Join-Path $env:USERPROFILE '.oci/config'))
$section=(($config -split ('\['+[regex]::Escape($Profile)+'\]'))[1] -split '\r?\n\[')[0]
$tenant=[regex]::Match($section,'(?m)^tenancy\s*=\s*(\S+)').Groups[1].Value
if (!$tenant) { throw 'Tenancy not found in selected OCI profile' }
$common=@('--profile',$Profile,'--auth','security_token','--region',$Region,'--output','json','--no-retry')
$shape=if($PaidTest){'VM.Standard.E5.Flex'}else{'VM.Standard.A1.Flex'}
if($PaidTest -and $MemoryGB -gt 4){throw 'Paid test is limited to one OCPU and at most 4 GB'}
$statePath=Join-Path $reports 'host-state.json'
$state=if(Test-Path $statePath){Get-Content $statePath -Raw|ConvertFrom-Json}else{[pscustomobject]@{region=$Region;compartmentId=$tenant}}
if($state.region -ne $Region -or $state.compartmentId -ne $tenant){throw 'Saved state belongs to a different OCI scope'}
function Save-State { [IO.File]::WriteAllText($statePath,($state|ConvertTo-Json -Depth 12)) }
function Set-State($name,$value) { $state|Add-Member -NotePropertyName $name -NotePropertyValue $value -Force; Save-State }
function Invoke-Oci([string[]]$Arguments,[string]$Label) {
    $prior=$ErrorActionPreference
    $ErrorActionPreference='Continue'
    try { $output=& oci @Arguments @common 2>&1; $exitCode=$LASTEXITCODE } finally {$ErrorActionPreference=$prior}
    $text=($output|ForEach-Object{"$_"}) -join "`n"
    [IO.File]::WriteAllText((Join-Path $reports ($Label+'.json')),$text)
    if($exitCode){throw "OCI $Label failed: $text"}
    if(!$text.Trim()){return @()}
    return ($text|ConvertFrom-Json).data
}
function Create-Resource([string[]]$Command,$Body,[string]$Label) {
    $path=Join-Path $reports ($Label+'-request.json')
    [IO.File]::WriteAllText($path,($Body|ConvertTo-Json -Depth 14))
    Invoke-Oci ($Command+@('--from-json',('file://'+$path))) $Label
}
$regions=Invoke-Oci @('iam','region-subscription','list','--tenancy-id',$tenant) 'regions'
if(!($regions|Where-Object{$_.'region-name' -eq $Region -and $_.'is-home-region'})){throw 'Always Free deployment must use the tenancy home region'}
$instances=@(Invoke-Oci @('compute','instance','list','--compartment-id',$tenant,'--all') 'instances-current')
$otherA1=@($instances|Where-Object{$_.shape -eq 'VM.Standard.A1.Flex' -and $_.'lifecycle-state' -ne 'TERMINATED' -and $_.id -ne $state.instanceId})
if($otherA1.Count){throw 'Existing A1 allocation requires a fresh free-tier capacity calculation before launch'}
$boot=@(Invoke-Oci @('bv','boot-volume','list','--compartment-id',$tenant,'--all') 'boot-volumes-current')
$block=@(Invoke-Oci @('bv','volume','list','--compartment-id',$tenant,'--all') 'block-volumes-current')
$disk=($boot+$block|Where-Object{$_.'lifecycle-state' -ne 'TERMINATED'}|Measure-Object -Property 'size-in-gbs' -Sum).Sum
if(!$state.instanceId -and $disk -gt 150){throw 'A 50 GB boot volume would exceed the 200 GB Always Free storage allowance'}
$keyPath=Join-Path $root 'secrets/oci-vps-ed25519'
if(!(Test-Path $keyPath)) {
    ssh-keygen -q -t ed25519 -f $keyPath -N '""' -C prreview-oci-test
    if($LASTEXITCODE){throw 'SSH key generation failed'}
}
$publicKey=[IO.File]::ReadAllText($keyPath+'.pub').Trim()
if(!$state.vcnId){$vcn=Create-Resource @('network','vcn','create') @{compartmentId=$tenant;cidrBlock='10.74.0.0/16';displayName='prreview-test-vcn';dnsLabel='prreview'} 'vcn';Set-State vcnId $vcn.id}
if(!$state.internetGatewayId){$igw=Create-Resource @('network','internet-gateway','create') @{compartmentId=$tenant;vcnId=$state.vcnId;isEnabled=$true;displayName='prreview-test-internet'} 'internet-gateway';Set-State internetGatewayId $igw.id}
if(!$state.routeTableId){$rt=Create-Resource @('network','route-table','create') @{compartmentId=$tenant;vcnId=$state.vcnId;displayName='prreview-test-public-routes';routeRules=@(@{destination='0.0.0.0/0';destinationType='CIDR_BLOCK';networkEntityId=$state.internetGatewayId})} 'route-table';Set-State routeTableId $rt.id}
if(!$state.securityListId){
    $clientIp=(Invoke-RestMethod -Uri 'https://api.ipify.org').Trim()
    if($clientIp -notmatch '^\d{1,3}(\.\d{1,3}){3}$'){throw 'Cannot determine SSH client IPv4'}
    $ingress=@(@{protocol='6';source=($clientIp+'/32');sourceType='CIDR_BLOCK';isStateless=$false;tcpOptions=@{destinationPortRange=@{min=22;max=22}}})
    foreach($port in @(80,443)){$ingress+=@{protocol='6';source='0.0.0.0/0';sourceType='CIDR_BLOCK';isStateless=$false;tcpOptions=@{destinationPortRange=@{min=$port;max=$port}}}}
    $sl=Create-Resource @('network','security-list','create') @{compartmentId=$tenant;vcnId=$state.vcnId;displayName='prreview-test-ingress';ingressSecurityRules=$ingress;egressSecurityRules=@(@{protocol='all';destination='0.0.0.0/0';destinationType='CIDR_BLOCK';isStateless=$false})} 'security-list'
    Set-State securityListId $sl.id
    Set-State sshClientIp $clientIp
}
if(!$state.subnetId){$sn=Create-Resource @('network','subnet','create') @{compartmentId=$tenant;vcnId=$state.vcnId;cidrBlock='10.74.1.0/24';displayName='prreview-test-public';dnsLabel='public';routeTableId=$state.routeTableId;securityListIds=@($state.securityListId);prohibitPublicIpOnVnic=$false} 'subnet';Set-State subnetId $sn.id}
if(!$state.instanceId){
    $images=Invoke-Oci @('compute','image','list','--compartment-id',$tenant,'--shape',$shape,'--all') 'images-current'
    $pattern=if($PaidTest){'^Canonical-Ubuntu-24\.04-\d'}else{'^Canonical-Ubuntu-24\.04-aarch64-'}
    $image=$images|Where-Object{$_.'display-name' -match $pattern}|Sort-Object 'time-created' -Descending|Select-Object -First 1
    if(!$image){throw 'No compatible Ubuntu 24.04 image'}
    $cloud=[Convert]::ToBase64String([IO.File]::ReadAllBytes((Join-Path $root 'deploy/oci/cloud-init.yaml')))
    $domains=Invoke-Oci @('iam','availability-domain','list','--compartment-id',$tenant) 'availability-domains'
    foreach($domain in $domains){
        $body=@{compartmentId=$tenant;availabilityDomain=$domain.name;displayName='prreview-oci-test';shape=$shape;shapeConfig=@{ocpus=1;memoryInGBs=$MemoryGB};sourceDetails=@{sourceType='image';imageId=$image.id;bootVolumeSizeInGBs=50};subnetId=$state.subnetId;assignPublicIp=$true;vnicDisplayName='prreview-public-vnic';metadata=@{ssh_authorized_keys=$publicKey;user_data=$cloud};freeformTags=@{project='mcp-api-gateway';purpose='integration-test';allocation=($shape+'-1ocpu-'+$MemoryGB+'gb')}}
        try {
            $instance=Create-Resource @('compute','instance','launch') $body ('launch-'+($domain.name -replace '[^A-Za-z0-9-]','-'))
            Set-State instanceId $instance.id
            Set-State availabilityDomain $domain.name
            Set-State allocationMode $(if($PaidTest){'paid-test-authorized-max-usd-1'}else{'always-free'})
            Set-State shape $shape
            Set-State memoryGB $MemoryGB
            Set-State createdAtUtc ([DateTime]::UtcNow.ToString('o'))
            if($PaidTest){Set-State stopDeadlineUtc ([DateTime]::UtcNow.AddHours(6).ToString('o'))}
            break
        } catch {
            if($_.Exception.Message -notmatch 'Out of host capacity|OutOfHostCapacity|OUT_OF_HOST_CAPACITY'){throw}
            Write-Output ('No '+$shape+' host capacity in '+$domain.name)
        }
    }
    if(!$state.instanceId){throw ('All availability domains rejected the requested shape '+$shape)}
}
$instance=Invoke-Oci @('compute','instance','get','--instance-id',$state.instanceId) 'instance'
if($instance.'lifecycle-state' -eq 'RUNNING'){
    $vnics=@(Invoke-Oci @('compute','instance','list-vnics','--instance-id',$state.instanceId) 'instance-vnics')
    $public=@($vnics|Where-Object{$_.'public-ip'})
    if($public.Count -ne 1){throw 'Expected one public VNIC for the isolated test host'}
    Set-State publicIp $public[0].'public-ip'
    Set-State publicHost ('prreview.'+$public[0].'public-ip'+'.sslip.io')
}
Write-Output ('Instance '+$instance.id+'; state='+$instance.'lifecycle-state')
if($instance.'lifecycle-state' -ne 'RUNNING'){Write-Output 'Run this script again after provisioning to record the public VNIC; the existing instance will be reused.'}
