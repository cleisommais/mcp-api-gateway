# PR Review Agent

Java 21 service that reviews GitHub pull requests and GitLab merge requests, then publishes a summary comment with file, line and severity. Saved reports include exact code evidence. PostgreSQL holds the queue; Ollama runs inference locally. [Architecture](architecture.md) covers code entrypoints, API contracts and the database. [Plan](plan.md) lists remaining work.

## Start locally

Requires Docker Desktop with Linux containers, Docker Compose, PowerShell and an NVIDIA GPU supported by Docker. The default model is `gpt-oss:20b` with low reasoning (about 14 GB of model storage; tested with 16 GB VRAM). No host Java or Maven is required to run containers.

```powershell
Copy-Item .env.example .env # First setup only; preserve an existing .env.
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Initialize-LocalEnvironment.ps1
# Configure credentials below, then:
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Start-Local.ps1
Invoke-RestMethod http://localhost:18081/health
```

The startup script builds the application, starts PostgreSQL and Ollama, downloads the selected model if missing, and starts all three Java services. Normal startup needs no profiles. After source changes, run the same command again. Startup preserves database and model volumes.

## Configuration

`.env` contains settings. All credentials are ignored files in **`secrets/`**, mounted individually only into services that need them. The initializer preserves existing credentials and generates local database, API, MCP and webhook secrets. Never commit these files.

| File or setting | Purpose |
| --- | --- |
| `secrets/github-app.pem` | GitHub App private key, downloaded from the App settings |
| `.env`: `GITHUB_APP_ID`, `GITHUB_INSTALLATION_ID` | App identity and installation; numeric values, not secrets |
| `secrets/gitlab-read-token` | GitLab token with `read_api` and access to configured projects |
| `secrets/gitlab-publish-token` | GitLab token with `api` and permission to comment |
| `secrets/api-token` | Bearer token for manual review requests and status |
| `secrets/mcp-gateway-token` | Internal MCP authentication |
| `secrets/github-webhook-secret` | GitHub webhook HMAC secret |
| `secrets/database-password` | PostgreSQL password; preserve it while reusing the database volume |
| `secrets/openai-api-key` | Optional external provider key; unused by default |

For a GitHub App: Settings -> Developer settings -> GitHub Apps -> create/select App. Grant repository **Contents: read**, **Pull requests: read**, **Issues: read and write** (summary comments), and **Metadata: read**. Install it on the selected repositories, download its PEM into `secrets/github-app.pem`, and enter the App and installation IDs in `.env`. Subscribe to **Pull request** events when enabling its webhook. Restart services after changing credentials.

`config/repositories.json` controls manual/webhook targets, numeric repository/project IDs and target branches. `config/gateway.json` independently controls MCP routing and repository access. Update both when adding a repository. The checked-in configuration targets the owner's public/private test repositories.

## Run a review

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Invoke-Review.ps1 -Platform github -Repository cleisommais/pr-test-repo -Number 1
# Private GitHub:
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Invoke-Review.ps1 -Platform github -Repository cleisommais/pr-test-repo-private -Number 1
# GitLab:
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Invoke-Review.ps1 -Platform gitlab -Repository cleisommais/pr-test-repo -Number 1
```

This submits, polls and returns the report plus `commentUrl`. Publication is required in the normal worker path, including a zero-findings summary. Repeated requests for the same base/head reuse the existing job. Partial analysis is explicitly reported; a failed or uncertain job is not a successful review.

Equivalent HTTP request:

```powershell
$headers = @{ Authorization = 'Bearer ' + (Get-Content secrets/api-token -Raw).Trim() }
$body = '{"platform":"github","repository":"cleisommais/pr-test-repo","number":1}'
$job = Invoke-RestMethod http://localhost:18081/api/reviews -Method Post -Headers $headers -ContentType application/json -Body $body
Invoke-RestMethod ("http://localhost:18081" + $job.statusUrl) -Headers $headers
```

For GitHub automation, expose only the API gateway through your HTTPS callback/tunnel and set the App webhook URL to `/webhooks/github`, content type JSON and the secret from `secrets/github-webhook-secret`. An optional local tunnel starts with `docker compose -f compose.yaml -f compose.webhook.yaml up -d`; read its URL with `docker compose -f compose.yaml -f compose.webhook.yaml logs tunnel`. GitLab currently uses the manual API; there is no GitLab webhook endpoint.

## Inspect, debug and test

```powershell
docker compose ps
docker compose logs -f api-gateway agent-worker mcp-gateway
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Test-Project.ps1
```

The test command runs the whole suite with real PostgreSQL; database tests use disposable schemas. Results are generated under ignored `reports/tests/`. Live review reports are also in PostgreSQL and `reports/job-*.json`. Runtime logs include job IDs and MCP routing decisions. `scripts/Test-LiveReviews.ps1` checks the nine configured test PRs/MRs against their expected findings and reads back each remote comment; run it only when you intend to publish/update comments in those test repositories.

Last validation: **109 automated tests passed, zero skipped**, including real PostgreSQL. All nine public/private GitHub and GitLab reviews completed and their remote comments matched the saved reports. The strict model rubric passed **8/9**: public PR #3 was rated HIGH instead of the expected MEDIUM. Both clean PRs had zero findings, and the seeded defects were detected. Per-PR results are in `reports/live-results.json`; local configuration comparisons are in `reports/model-comparison.json`. These fixture results do not guarantee general model accuracy.

For step-through debugging, install a Java 21 JDK and VS Code Extension Pack for Java, then:

```powershell
docker compose -f compose.yaml -f compose.debug.yaml up -d
```

Use the checked-in VS Code attach configurations (API 5005, MCP 5006, worker 5007). Start at `ReviewController.handle`, `ReviewRequestService.create`, `PostgresJobRepository.claim`, `ReviewWorker.once`, `ReviewEngine.review`, and the platform publisher's `publish` method. [Architecture](architecture.md) gives the call sequence. Debug ports bind to loopback.

To retry a failed job after fixing its cause:

```powershell
docker compose exec agent-worker java -cp '/app/mcp-api-gateway.jar:/app/lib/*' com.prreview.tools.QueueAdminCli retry JOB_UUID
```

To recompute a terminal job after a model/configuration change, use `reanalyze JOB_UUID` in the same administrative command; its existing comment is updated. `retry` reuses a saved report when available.

Stop with `docker compose down`. Do not add `-v` unless intentionally deleting this project's database and downloaded models.

## Model settings

Set `OLLAMA_MODEL`, `OLLAMA_THINK`, `OLLAMA_CONTEXT_LENGTH` and `REVIEW_OUTPUT_TOKENS` in `.env`. `REVIEW_MODE=verified` enables a second candidate-verification pass using `VERIFIER_MODEL` (default `qwen3:14b`; download with `docker compose exec ollama ollama pull qwen3:14b`). This increases latency and may reject valid findings; it does not guarantee higher accuracy. Repository reads use MCP; inference calls the provider directly.

External inference requires explicit enablement, a repository allowlist and a positive campaign budget; it is disabled by default. Keep private code local unless you explicitly authorize its transmission. Model findings require human judgment; this service does not approve or merge changes.
