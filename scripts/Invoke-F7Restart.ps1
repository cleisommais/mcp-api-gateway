# Run the timed claim/kill/restart sequence in one process so a fast fixture cannot finish between manual commands.
$suffix = [Guid]::NewGuid().ToString('N').Substring(0, 8)
$schema = 'f7_lab_restart_' + $suffix
$containerName = 'mcp-f7-restart-' + $suffix
$created = $false
Push-Location -LiteralPath (Split-Path -Parent $PSScriptRoot) -ErrorAction Stop
try {
    & docker compose --profile f7 run --rm -e "DATABASE_SCHEMA=$schema" f7-lab seed restart
    if ($LASTEXITCODE -ne 0) { throw 'Restart fixture setup failed' }
    & docker compose --profile automation run -d --name $containerName -e "DATABASE_SCHEMA=$schema" -e WORKER_LEASE_SECONDS=12 -e PUBLISH_REVIEWS=false -e MODEL_PROVIDER=ollama -e REVIEW_MODE=single worker --once
    if ($LASTEXITCODE -ne 0) { throw 'Restart worker startup failed' }
    $created = $true
    $deadline = [DateTime]::UtcNow.AddSeconds(10)
    do {
        $state = & docker compose exec -T postgres psql -U mcp_review -d mcp_review -At -c "SELECT state||'|'||attempts||'|'||(report IS NULL)::text FROM $schema.jobs;"
        if ($LASTEXITCODE -ne 0) { throw 'Restart state query failed' }
        if ($state -eq 'running|1|true') { break }
        if ([DateTime]::UtcNow -gt $deadline) { throw 'Job was not running with an absent report; no kill attempted' }
        Start-Sleep -Milliseconds 100
    } while ($true)
    $details = & docker inspect $containerName | ConvertFrom-Json
    if ($details[0].Config.Labels.'com.docker.compose.project' -ne 'mcp-api-gateway' -or
            $details[0].Config.Env -notcontains "DATABASE_SCHEMA=$schema") { throw 'Unexpected container scope; no kill attempted' }
    $killedAt = [DateTime]::UtcNow.ToString('o')
    & docker kill --signal KILL $containerName
    if ($LASTEXITCODE -ne 0) { throw 'Worker kill failed' }
    $deadline = [DateTime]::UtcNow.AddSeconds(25)
    do {
        $expired = & docker compose exec -T postgres psql -U mcp_review -d mcp_review -At -c "SELECT (lease_until<now())::text FROM $schema.jobs;"
        if ($LASTEXITCODE -ne 0) { throw 'Lease query failed' }
        if ($expired -eq 'true') { break }
        if ([DateTime]::UtcNow -gt $deadline) { throw 'Lease did not expire within the lab deadline' }
        Start-Sleep -Milliseconds 500
    } while ($true)
    & docker start -a $containerName
    if ($LASTEXITCODE -ne 0) { throw 'Restarted worker process failed' }
    $state = & docker compose exec -T postgres psql -U mcp_review -d mcp_review -At -c "SELECT state||'|'||attempts FROM $schema.jobs;"
    if ($LASTEXITCODE -ne 0 -or $state -ne 'analyzed|2') { throw 'Restart did not produce an analyzed job on attempt 2' }
    & docker compose --profile f7 run --rm -e "DATABASE_SCHEMA=$schema" f7-lab inspect
    if ($LASTEXITCODE -ne 0) { throw 'Final restart inspection failed' }
    $events = @{ schema=$schema; container=$containerName; killedAt=$killedAt; finishedAt=[DateTime]::UtcNow.ToString('o'); result=$state }
    [IO.File]::WriteAllText((Join-Path (Get-Location) "reports/f7/restart-$suffix-events.json"), ($events | ConvertTo-Json) + "`n")
} finally {
    if ($created) {
        $details = & docker inspect $containerName | ConvertFrom-Json
        if ($LASTEXITCODE -eq 0 -and $details[0].Config.Labels.'com.docker.compose.project' -eq 'mcp-api-gateway' -and
                $details[0].Config.Env -contains "DATABASE_SCHEMA=$schema") {
            & docker logs --timestamps $containerName > "reports/f7/restart-$suffix-worker.log" 2>&1
            if ($details[0].State.Running) { & docker stop $containerName }
            & docker rm $containerName
        }
    }
    Pop-Location
}
