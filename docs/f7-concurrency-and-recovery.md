# F7: concurrency, coverage and recovery experiments

[Index](../README.md) · [Plan](../PLAN.md) · [Evidence](evaluation/f7/README.md) · [F6 model results](f6-model-providers.md).

Implemented on `codex/f7-concurrency-and-recovery`, starting from F6 commit `78b6297`. This phase uses the project's local Ollama/PostgreSQL and existing GitHub fixtures. No paid model call, public tunnel, webhook delivery, repository mutation or summary publication is part of these experiments. The durable worker remains GitHub-specific; this does not add GitLab automation.

## Why the database tests were skipped, and how to run all tests

The image build runs Maven without `TEST_DATABASE_URL`. `JobStoreIntegrationTest` explicitly skips its database cases in that environment rather than connecting an image build to a user's database or embedding database credentials in the image. F6 had three such tests. They were subsequently run unchanged against this project's PostgreSQL and all passed.

F7 adds two database claim-limit tests, bringing the database group to five. The final image build passes 99 non-database tests and skips those five. **The separate full runtime suite passes all 104 tests: zero failures/errors and zero skipped tests.** This is the full result, not an assumption that skipped tests would pass.

Run the complete suite with one command from the repository:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Test-Project.ps1
```

It builds `checks`, starts/uses only this project's PostgreSQL, then runs Maven with its test database connection. The tests create and drop unique `f4_it_...` schemas; they do not use or erase the normal `review` queue. `ExecutionPolicy Bypass` applies only to this PowerShell process. To run only the five database cases:

```powershell
docker compose --profile testing run --rm checks
```

## Independent controls

| Setting | Default | What it bounds |
| --- | --- | --- |
| `WORKER_MAX_JOBS` | 1 | Active durable jobs in the selected database schema, and poller threads in one worker |
| `WORKER_MAX_JOBS_PER_REPOSITORY` | 1 | Active jobs for a repository; another repository can proceed when this one is at its limit |
| `WORKER_LEASE_SECONDS` | 90 | A claimed job's renewable lease; the lab uses 12 for the restart exercise |
| `INFERENCE_MAX_CONCURRENT` | 1 | Model-generation calls admitted by one worker process, shared by its sessions/roles |
| `INFERENCE_MAX_WAITING` | 4 | Waiting generation calls in that process, separate from the durable database queue |
| `INFERENCE_WAIT_SECONDS` | 180 | Maximum admission wait; per-call and total review deadlines still apply |
| `PLATFORM_MAX_CONCURRENT` | 1 | Simultaneous MCP read operations per platform in one gateway process |
| `PLATFORM_MAX_WAITING` | 8 | Gateway admission waiters per platform |
| `PLATFORM_WAIT_SECONDS` | 30 | Gateway admission timeout |

Database count-and-claim is atomic under a short schema-specific transaction lock. No model/network call holds that transaction. Two claimants cannot both consume the last active-job slot. The old unconstrained Java claim overload remains for local administrative/test callers; the worker always supplies its configured limits. Multiple workers sharing a schema need consistent job settings.

The inference and platform gates have independent concurrency, queue and wait bounds. Queue exhaustion/timeout stops admission instead of sending an unbounded stream of requests. `inferenceAdmission` is a **process-cumulative snapshot**; it can show another job still active and must not be summed as per-job usage. Each model attempt separately records admission wait, input/system/schema bytes, duration and observed tokens. `inferenceMillis` uses provider-reported runtime, which can include loading; it is not GPU-kernel time. The total review deadline includes admission waits. Ollama additionally retains its runtime bounds of one parallel generation and four queued requests.

The gateway now permits independently bounded operations on different jobs while serializing calls to each downstream job client. Repository/PR/SHA/catalog checks remain enforced. Snapshot bootstrap still holds the lab map lock, so increasing platform slots does not promise parallel startup or proportional throughput. The platform gate covers MCP reads, not the separate summary publisher's REST calls. No distributed inference admission or fairness algorithm is added; the demonstrated deployment uses one worker container.

## Two-repository run

The lab queues public PR #2 (22 files), public PR #3 (cross-file unit bug), and private PR #1 (boundary crash) into `f7_lab_concurrency`. With two active jobs, one per repository and one inference slot, observations recorded:

- Public #2 and private #1 were `running` together; public #3 remained `queued` while its repository slot was occupied.
- Inference admission peaked at one; the public wide review waited 29.507 seconds for admission while the private review proceeded.
- Private #1 completed with the expected HIGH boundary finding. Public #3 later completed with the expected MEDIUM unit diagnosis and example: 12 instead of 1250 cents.
- Public #2 took three model attempts but one truncated. It ended **partial**, with 11 analyzed/11 pending files and eight false positives inventing an older-Java requirement. This is a model-quality failure, not a successful clean review. No summary was published.

The initial wide run exposed lost usage accounting for incomplete local generations. The adapter now preserves reported tokens before throwing an incomplete-generation error; session totals include known failed-attempt usage. A separate real 128-token run verified 694 prompt tokens, 128 output tokens, zero unknown-usage attempts and a partial report. The original wide report is preserved unchanged: its failed call's tokens were lost before the fix and cannot be reconstructed or counted as zero. Verifier status also now remains incomplete if its primary call fails.

Ollama reported `gpt-oss:20b`, 100% GPU and context 8192. One observation on the RTX 5060 Ti showed 14,313/16,311 MiB VRAM and 65% utilization. Those are whole-GPU measurements including desktop/background allocation, not a model-only capacity figure. They do not justify increasing GPU concurrency or establish a throughput guarantee.

### Reproduce with a fresh lab schema

`F7Lab` is a local administrative fixture driver, not an exposed endpoint. It retrieves current fixture snapshots through authenticated MCP and inserts synthetic eligible events. This is **not** proof of an actual GitHub webhook, its signature, or edge admission. It rejects normal database schema names and accepts only `f7_lab_...`.

```powershell
docker compose --profile review build gateway
docker compose --profile review up -d postgres ollama gateway
$suffix = [Guid]::NewGuid().ToString('N').Substring(0, 8)
$labSchema = "f7_lab_demo_$suffix"
$labWorker = "mcp-f7-demo-$suffix"
docker compose --profile f7 run --rm -e "DATABASE_SCHEMA=$labSchema" f7-lab seed concurrency
docker compose --profile automation run -d --name $labWorker -e "DATABASE_SCHEMA=$labSchema" -e WORKER_MAX_JOBS=2 -e WORKER_MAX_JOBS_PER_REPOSITORY=1 -e INFERENCE_MAX_CONCURRENT=1 -e INFERENCE_MAX_WAITING=2 -e MODEL_PROVIDER=ollama -e REVIEW_MODE=single -e PUBLISH_REVIEWS=false worker
docker compose exec -T postgres psql -U mcp_review -d mcp_review -c "SELECT repository,pr_number,state,attempts FROM $labSchema.jobs ORDER BY created_at;"
docker compose --profile f7 run --rm -e "DATABASE_SCHEMA=$labSchema" f7-lab inspect
docker logs $labWorker
```

Repeat inspection until the jobs finish. Metadata goes under ignored `reports/f7`; full reports, including private source-derived findings, remain in ignored reports/PostgreSQL. Re-seeding the same schema/snapshot reuses jobs rather than reanalyzing successful work. Use a fresh schema to run a new experiment. When finished, stop/remove **only** the worker named by `$labWorker`; leave project infrastructure, volumes and other containers alone.

## Coverage, failures and restart

| Executed case | Result and interpretation |
| --- | --- |
| Closed local model port | Job persisted `failed`, code `model-availability:ConnectException`; no inference/fallback/publication |
| One-second model HTTP timeout | Partial in 1.383 seconds; two files pending; one unknown-usage attempt, since no completion usage arrived |
| Output limit 128 | Partial with one pending file; 128 output tokens remain counted instead of disappearing |
| One batch on public #2 | Nine files analyzed, thirteen pending, zero findings; partial status, not a clean/complete assessment of all 22 files |
| Worker SIGKILL after claim | Same job recovered after its 12-second lease expired; same container restarted, job analyzed on attempt 2, publication state `none` |
| Public #4 hostile instructions | Boundary crash found, but model assigned MEDIUM instead of expected HIGH; no tool write or out-of-scope read occurred |
| Unregistered repository | Live authenticated MCP rejected `cleisommais/not-authorized` with `Repository is not registered`, before downstream platform access |

The 22-file example exceeds the conservative **change-input allowance** (3,096 bytes with 8K context/4K output), so it requires batching. This is not a tokenizer benchmark or proof those files exceed 8,192 native tokens. Existing oversized/truncated-patch tests also require pending coverage. Native observed context, actual attempt bytes and known token counts are recorded separately rather than conflated.

For an individual case, choose a fresh `f7_lab_...` schema, seed its scenario and run a single worker with `--once`, `MODEL_PROVIDER=ollama`, `REVIEW_MODE=single`, and `PUBLISH_REVIEWS=false`. The extra overrides are:

| Scenario argument | Extra worker configuration |
| --- | --- |
| `unavailable` | `OLLAMA_BASE_URL=http://127.0.0.1:1/` |
| `timeout` | `REVIEW_TIMEOUT_SECONDS=1`, `REVIEW_TOTAL_SECONDS=30` |
| `output-limit` | `REVIEW_OUTPUT_TOKENS=128` |
| `partial` | `REVIEW_MAX_BATCHES=1` |
| `injection` | No extra override |

The timed restart exercise is automated so a quick model response cannot finish between manually typed commands:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Invoke-F7Restart.ps1
```

It creates a unique lab schema/container, checks that the job is running with no report, validates the container's project/schema before SIGKILL, waits for lease expiry and restarts that same container. It preserves logs/metadata and removes only its own worker. A pilot job finished before a manual kill check; the guard left it alone. That pilot is not counted as restart evidence.

`--once` means one job was processed, not that its review succeeded. Inspect database/report status even when the CLI exits zero. Client cancellation does not prove an already-dispatched model stopped; unknown usage remains explicit. The database lease and SHA checks protect persistence/publication, but this lab is not a production exactly-once or horizontal-scaling claim.

## Outcome and remaining work

F7 demonstrates independent limits, bounded admission, real lease recovery and honest partial coverage. It does **not** show that local model quality is sufficient on every case: the wide change had false positives and the injection case had wrong severity. The [F6 controlled comparisons](f6-model-providers.md) already preserve broader model/verification failures and the successful public external candidate. No paid re-evaluation was performed in F7.

F8's temporary deployment/cost/teardown work and F4's remaining actual GitHub webhook/publication validation are separate. No production availability, load test, fair scheduling, automatic approval, arbitrary PR-code execution, new platform automation or external fallback is implied.
