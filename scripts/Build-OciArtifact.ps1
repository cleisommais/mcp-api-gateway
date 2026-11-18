param()
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
Set-Location $root
& docker build --file deploy/oci/build.Dockerfile --target artifact --output type=local,dest=dist .
if($LASTEXITCODE){throw 'Portable Java build failed'}
