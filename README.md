# PR Review Agent

Java 21 service that reviews GitHub pull requests and GitLab merge requests, then publishes a summary comment with file, line and severity. Saved reports include exact code evidence. PostgreSQL holds the queue; Ollama runs inference locally. [Architecture](architecture.md) covers requirements, security, code entrypoints and the database. [open-api.yaml](open-api.yaml) defines the HTTP contract. [Plan](plan.md) lists remaining work.

## Start locally

Run commands from the repository root. Install Git and [Docker with Compose](https://docs.docker.com/compose/install/): Docker Desktop with Linux containers on Windows/macOS, or Docker Engine plus the Compose plugin on Linux. Start Docker before continuing; `docker version` and `docker compose version` must succeed. On Linux, configure your account's [Docker access](https://docs.docker.com/engine/install/linux-postinstall/) so the commands below work without `sudo`.

Windows uses Windows PowerShell 5.1 or later. macOS/Linux uses Bash 3.2 or later and Python 3.9 or later (standard library only; no `pip` packages). For example, install Python with `brew install python` on macOS with [Homebrew](https://brew.sh/), or `sudo apt-get install git python3 curl` on Debian/Ubuntu after `sudo apt-get update`. Other Linux distributions can use their package manager. Check `bash --version` and `python3 --version`. No host Java or Maven is required to build, run or test containers.

The default Compose configuration uses CPU inference on Windows, Linux and macOS, including Apple Silicon. The default model is `gpt-oss:20b` with low reasoning (about 14 GB of model storage; previously tested with 16 GB NVIDIA VRAM). Allocate enough RAM and disk to Docker for the model, context and services. CPU inference can be substantially slower; adjust `REVIEW_TIMEOUT_SECONDS` and `REVIEW_TOTAL_SECONDS` in `.env` if needed. This Docker setup does not expose Apple's Metal GPU to Ollama. Optional NVIDIA acceleration is described below.

Windows PowerShell:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Initialize-LocalEnvironment.ps1
# Configure credentials below, then:
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Start-Local.ps1
Invoke-RestMethod http://localhost:18081/health
```

macOS/Linux Bash:

```bash
bash scripts/Initialize-LocalEnvironment.sh
# Configure credentials below, then:
bash scripts/Start-Local.sh
curl --fail http://localhost:18081/health
```

Both initializers create `.env` from `.env.example` only when missing. The Bash scripts use Python for credential generation and authenticated HTTP requests. Every `.ps1` entrypoint has a matching `.sh` entrypoint; PowerShell is not required on macOS/Linux. Invoking the shell files with `bash` avoids requiring an executable-bit change.

The startup script builds the application, starts PostgreSQL and Ollama, downloads the selected model if missing, and starts all three Java services. Normal startup needs no profiles. After source changes, run the same command again. Startup preserves database and model volumes. Use `-NoBuild` (PowerShell) or `--no-build` (Bash) to reuse an existing application image.

For **optional NVIDIA acceleration on Windows/Linux**, first configure [Windows WSL 2 GPU support](https://docs.docker.com/desktop/features/gpu/) or the [Linux NVIDIA Container Toolkit](https://docs.ollama.com/docker). Before startup, copy the GPU configuration once:

```powershell
# Windows PowerShell; preserve an existing custom override.
if (!(Test-Path compose.override.yaml)) { Copy-Item compose.gpu.yaml compose.override.yaml }
```

```bash
# Linux Bash; preserve an existing custom override.
if [ ! -e compose.override.yaml ]; then cp compose.gpu.yaml compose.override.yaml; fi
```

`compose.override.yaml` is ignored by Git and automatically loaded by normal `docker compose` commands. Startup, test and model-regression scripts honor it. CPU-only machines and Macs should leave this NVIDIA override absent. Existing NVIDIA users must opt in with this copy when updating to the portable configuration.

## Configuration

`.env` contains settings. All credentials are ignored files in **`secrets/`**, mounted individually only into services that need them. The initializer preserves existing credentials and generates local database, API, MCP and webhook secrets. Never commit these files.

On macOS/Linux, initialization creates private secret files and appends `LOCAL_UID`/`LOCAL_GID` to `.env` if absent. Java containers run as that user so file-backed secrets are readable and `reports/` is writable on Linux. Run initialization as your normal user. If moving the checkout to another Unix account, update those IDs using `id -u` and `id -g` and ensure that account owns `secrets/` and `reports/`.

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

For a GitHub App: Settings -> Developer settings -> GitHub Apps -> create/select App. Grant repository **Contents: read**, **Pull requests: read and write** (summary publication), and **Metadata: read**. Install it on the selected repositories, download its PEM into `secrets/github-app.pem`, and enter the App and installation IDs in `.env`. Subscribe to **Pull request** events when enabling its webhook. Restart services after changing credentials.

`config/repositories.json` controls manual/webhook targets, numeric repository/project IDs and target branches. `config/gateway.json` independently controls MCP routing and repository access. Update both when adding a repository. The checked-in configuration targets the owner's public/private test repositories.

## Run a review

Windows PowerShell:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Invoke-Review.ps1 -Platform github -Repository cleisommais/pr-test-repo -Number 1
# Private GitHub:
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Invoke-Review.ps1 -Platform github -Repository cleisommais/pr-test-repo-private -Number 1
# GitLab:
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Invoke-Review.ps1 -Platform gitlab -Repository cleisommais/pr-test-repo -Number 1
```

macOS/Linux Bash:

```bash
bash scripts/Invoke-Review.sh --platform github --repository cleisommais/pr-test-repo --number 1
# Private GitHub:
bash scripts/Invoke-Review.sh --platform github --repository cleisommais/pr-test-repo-private --number 1
# GitLab:
bash scripts/Invoke-Review.sh --platform gitlab --repository cleisommais/pr-test-repo --number 1
```

This submits, polls and returns the report plus `commentUrl`. Publication is required in the normal worker path, including a zero-findings summary. Repeated requests for the same base/head reuse the existing job. Partial analysis is explicitly reported; a failed or uncertain job is not a successful review.

To change the endpoint or polling deadline, use `-BaseUrl`/`-TimeoutSeconds` in PowerShell or `--base-url`/`--timeout-seconds` in Bash. The review deadline defaults to 1800 seconds.

Equivalent HTTP request:

```powershell
$headers = @{ Authorization = 'Bearer ' + (Get-Content secrets/api-token -Raw).Trim() }
$body = '{"platform":"github","repository":"cleisommais/pr-test-repo","number":1}'
$job = Invoke-RestMethod http://localhost:18081/api/reviews -Method Post -Headers $headers -ContentType application/json -Body $body
Invoke-RestMethod ("http://localhost:18081" + $job.statusUrl) -Headers $headers
```

```bash
token=$(cat secrets/api-token)
job=$(curl --fail --silent --show-error http://localhost:18081/api/reviews \
  -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
  --data '{"platform":"github","repository":"cleisommais/pr-test-repo","number":1}')
status_url=$(printf '%s' "$job" | python3 -c 'import json,sys; print(json.load(sys.stdin)["statusUrl"])')
curl --fail --silent --show-error "http://localhost:18081$status_url" -H "Authorization: Bearer $token"
```

For GitHub automation, expose only the API gateway through your HTTPS callback/tunnel and set the App webhook URL to `/webhooks/github`, content type JSON and the secret from `secrets/github-webhook-secret`. An optional local tunnel starts with `docker compose -f compose.yaml -f compose.webhook.yaml up -d`; read its URL with `docker compose -f compose.yaml -f compose.webhook.yaml logs tunnel`. GitLab currently uses the manual API; there is no GitLab webhook endpoint.

The tunnel and debug commands use explicit `-f` files, which disable automatic override loading. If using the NVIDIA override, insert `-f compose.override.yaml` immediately after `-f compose.yaml` in those commands. These Docker commands work in both PowerShell and Bash.

## Inspect, debug and test

```text
docker compose ps
docker compose logs -f api-gateway agent-worker mcp-gateway
```

Windows PowerShell:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Test-Project.ps1
```

macOS/Linux Bash:

```bash
bash scripts/Test-Project.sh
```

Initialize the environment before running tests, even if you are not starting the application. The test command runs the whole suite with real PostgreSQL; database tests use disposable schemas. Results are generated under ignored `reports/tests/`. Live review reports are also in PostgreSQL and `reports/job-*.json`. Runtime logs include job IDs and MCP routing decisions.

The separate script-portability checks use an isolated checkout, mock HTTP server and mock Docker CLI; they do not use credentials or publish comments. On macOS/Linux, run `python3 -m unittest discover -s scripts/tests -v`. To run those checks from either PowerShell or Bash using a Linux container:

```text
docker run --rm --mount "type=bind,source=$PWD,target=/workspace,readonly" --workdir /workspace --env PYTHONDONTWRITEBYTECODE=1 python:3.12-slim python3 -m unittest discover -s scripts/tests -v
```

The live fixture check submits the nine configured test PRs/MRs, checks their expected findings and reads back each remote comment. Run it only when you intend to publish/update comments in those test repositories:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Test-LiveReviews.ps1
```

```bash
bash scripts/Test-LiveReviews.sh
```

Both versions accept the endpoint and timeout options described above; the live deadline defaults to 3600 seconds. The Bash version writes the same `reports/live-jobs.json` and `reports/live-results.json` artifacts as PowerShell.

Last validation: **111 automated tests passed, zero skipped**, including real PostgreSQL. All nine public/private GitHub and GitLab reviews completed and their remote comments matched the saved reports. The strict model rubric passed **8/9**: public PR #3 was rated HIGH instead of the expected MEDIUM. Both clean PRs had zero findings, and the seeded defects were detected. Per-PR results are in `reports/live-results.json`; local configuration comparisons are in `reports/model-comparison.json`. These fixture results do not guarantee general model accuracy.

To compare the local provider with the GPT API on frozen public fixtures:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Test-ModelRegression.ps1 -Provider openai -Label api-check
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Test-ModelRegression.ps1 -Provider ollama -Label local-check
```

```bash
bash scripts/Test-ModelRegression.sh --provider openai --label api-check
bash scripts/Test-ModelRegression.sh --provider ollama --label local-check
```

Labels must be unique. This opt-in check never publishes comments, permits only the fixed public test repository, and shares a persistent USD 0.10 reservation cap across API runs. Reports are under `reports/model-regression/`. The GPT API scored 4/4 before and after a stricter severity-prompt trial; the trial reduced local accuracy to 1/4, so the existing prompt was retained.

Start the application first for either comparison; the API comparison also needs a key in `secrets/openai-api-key`.

For step-through debugging on any of the three operating systems, install a [Java 21 JDK](https://adoptium.net/temurin/releases/?version=21) matching your host architecture and VS Code Extension Pack for Java. Confirm `java -version` reports 21, open this repository in VS Code, then:

```text
docker compose -f compose.yaml -f compose.debug.yaml up -d
```

Use the checked-in VS Code attach configurations (API 5005, MCP 5006, worker 5007). Start at `ReviewController.handle`, `ReviewRequestService.create`, `PostgresJobRepository.claim`, `ReviewWorker.once`, `ReviewEngine.review`, and the platform publisher's `publish` method. [Architecture](architecture.md) gives the call sequence. Debug ports bind to loopback.

To retry a failed job after fixing its cause:

```text
docker compose exec agent-worker java -cp '/app/mcp-api-gateway.jar:/app/lib/*' com.prreview.tools.QueueAdminCli retry JOB_UUID
```

To recompute a terminal job after a model/configuration change, use `reanalyze JOB_UUID` in the same administrative command; its existing comment is updated. `retry` reuses a saved report when available.

Stop with `docker compose down`. Do not add `-v` unless intentionally deleting this project's database and downloaded models.

## Model settings

Set `OLLAMA_MODEL`, `OLLAMA_THINK`, `OLLAMA_CONTEXT_LENGTH` and `REVIEW_OUTPUT_TOKENS` in `.env`. `REVIEW_MODE=verified` enables a second candidate-verification pass using `VERIFIER_MODEL` (default `qwen3:14b`; download with `docker compose exec ollama ollama pull qwen3:14b`). This increases latency and may reject valid findings; it does not guarantee higher accuracy. Repository reads use MCP; inference calls the provider directly.

External inference requires explicit enablement, a repository allowlist and a positive campaign budget; it is disabled by default. Keep private code local unless you explicitly authorize its transmission. Model findings require human judgment; this service does not approve or merge changes.
