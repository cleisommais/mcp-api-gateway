# Requirements and acceptance

[Index](../README.md) · [Phases F1–F8](../PLAN.md) · [C/P/Q/F classification](decisions.md).

Criteria describe planned checks, not evidence that every capability is delivered. **C** identifies explicitly requested scope; proposed implementation choices are marked **P** in the criterion. **F** denotes future work. Phase indicates first delivery and later hardening where needed. Implemented subsets and verified results are documented in the [F1 guide](f1-mcp-stdio.md) and [F2 guide](f2-local-pr-review.md); later-phase requirements remain planned. Existing RF/RNF identifiers are retained for traceability.

## Functional requirements

| ID | Requirement | Status | Phase | Verifiable acceptance criterion |
| --- | --- | --- | --- | --- |
| RF01 | Manual trigger | C | F2 | Accept platform, connection/repository, and PR/MR number; validate identity and existence; produce a traceable result or rejection reason |
| RF02 | Automatic trigger | C | F4 | An authenticated relevant event creates a durable job; invalid/irrelevant events do not start a review; the initial event set per offering is P |
| RF03 | Target branch filter | C | F4 | Per-repository configuration allows/rejects destinations and records why; test `Development` as a configurable example, not a mandatory branch |
| RF04 | Draft handling | C | F4 | Apply configured policy and test draft/ready transitions; P: skip drafts by default and start when ready |
| RF05 | Data through MCP | C | F1, F2 | Discover and call tools with schemas; review metadata, files, diffs, and context use MCP; distinguish MCP calls from model queries |
| RF06 | Commit snapshot | C | F2 | Fix base/head SHA and repository identity before analysis; all content reads reference the recorded SHA, never a mutable branch |
| RF07 | Structured findings | C | F2 | Each finding has file, valid location, severity, explanation, and SHA-linked evidence; missing evidence prevents publication of the finding |
| RF08 | Output validation | C | F2 | Reject invalid JSON/schema, unknown severity, nonexistent files/lines, and excessive text; record failure or bounded repair without unlimited loops |
| RF09 | Summary with commit and coverage | C | F2 local, F4 publication | Report SHA, analyzed files/hunks, exclusions, pending work, and limits; publisher checks current state before sending and persists the remote identifier |
| RF10 | Inline comments | F | After F4/F5 | Publish only at valid positions in the matching diff; do not reuse an old snapshot's line; unsupported offerings retain summary publication |
| RF11 | Job state and failure reason | C | F2 basic, F4 durable | Query state, timings, attempt, and failure code; distinguish unavailable, cancelled, outdated, invalid, and partial; proposed states are in the architecture |
| RF12 | Event deduplication | C | F4 | Redelivery of the same event ID does not create duplicate logical execution; the key includes platform/connection to prevent collisions |
| RF13 | Duplicate-publication protection | C | F4 | A retry after a lost response reconciles remote marker/ID before resending; the same logical result does not create repeated summaries in test scenarios |
| RF14 | Commits during a review | C | F2, F4 automatic | Detecting a new head marks the old result outdated and prevents publishing it as current; P: cancel old work and schedule the new snapshot |
| RF15 | Manual reprocessing | C | F4 | An authorized request creates an explicit attempt with reason and original-job link; distinguish reanalyzing an old SHA from following current head |
| RF16 | Approved MCP Servers | C | F3 | Only administrative registration sets URL/process, transport, version, and credential reference; changes are audited; the model cannot register servers |
| RF17 | Allowed catalog and routing | C | F3 | Expose only approved tools; resolve each tool to its correct server/connection; handle name collisions and schema changes explicitly |
| RF18 | Per-call authorization | C | F3 | Validate identity, tool, platform, connection, and repository on every invocation; deny resources outside the job even when a tool is visible |
| RF19 | Configurable provider/model | C | F2, F6 comparison | Change endpoint/model through validated configuration without changing the core; record provider, model, and available version/digest for each execution |
| RF20 | Execution budgets | C | F2, F7 capacity | Configure input/output tokens, tool/model calls, cost, and duration limits; exhaustion stops or produces partial results with reasons, without deliberately exceeding limits |
| RF21 | Auditing and traceability | C | F3, F4 durable | Correlate job, attempt, event, identity, authorization, MCP, model, and publication; record decisions without tokens or raw code by default |
| RF22 | Pagination and truncation | C | F2 | Traverse pages to a confirmed end; test more than 20 files, empty/repeated pages, rate limits, and truncated diffs; incomplete sources prevent full-coverage claims |
| RF23 | Large PRs and context | C | F2 basic, F7 | Classify files, split by tokens, retrieve bounded context, and consolidate; report analyzed, excluded, and pending items; evaluate a cross-file bug |
| RF24 | Ollama availability | C | F2 | Query version/models without inference; missing endpoint, incompatible version, or absent model produces a clear diagnostic; do not implicitly pull or start another instance |
| RF25 | Local timeout and unavailability | C | F2 | Simulate timeout/error/busy service; stop or reschedule with a limit; release the application slot and record possibly remaining server-side inference |
| RF26 | Arguments and JSON separate from policy | C | F2/F3 | Validate schema and semantics before each tool; compare SHA, repository, and paths against authorized context even when model-generated |
| RF27 | Explicit external fallback | C | F2/F6 | Local failure never sends code to another provider automatically; verify no external call without enabled policy; enabling requires destination and data scope |
| RF28 | Local and cloud demonstration profiles | C | F2/F6/F8 | Development uses project-owned Ollama/PostgreSQL (D20); demonstration uses an explicitly configured external API and completes webhook → durable job → agent → gateway tools → model → publication, initially without desktop, cloud GPU, or Ollama on the VPS |
| RF29 | Exportable evidence | C | All; completion F8 | Export publishable examples, labeled results, metrics, decisions, and a short recording; artifacts remain accessible after VPS deletion without an online endpoint |
| RF30 | Demonstration teardown | C | F8 | Runbook identifies campaign resources, exports evidence, deletes VM/billable extras, and checks residual balance/billing; powering off is not teardown evidence; execution is operational, outside model tools |

## Nonfunctional requirements

Existing identifiers are preserved, but criteria are sized for **learning and demonstration**. There is no SLO/SLA, permanent availability, mandatory p95, statistical capacity test, or corporate retention policy to approve. Observing a few examples is enough to record results and limitations. Timeouts, concurrency limits, and budget are practical settings selected during the exercise without opening a product questionnaire.

| ID | Property | Status | Phase | Measurement and acceptance criterion |
| --- | --- | --- | --- | --- |
| RNF01 | Webhook receipt confirmation | C | F4 | Save pending analysis before HTTP success; record observed timing and run review in the background of the same Java application without waiting for the model before responding; no p95 target |
| RNF02 | Observed review duration | C | F2/F7 | Record durations for several PRs with size/model/context and distinguish queueing from inference; present measured values without promising latency |
| RNF03 | Configurable concurrency | C | F4/F7 | Test independent active-job, per-repository, model-call, and platform-call limits; configuration changes behavior without recompilation |
| RNF04 | Advanced fair scheduling | F; outside initial POC | Optional after F7 | Retain as a later exercise if actual monopolization appears; do not require fairness algorithms, customer quotas, or waiting-time guarantees to finish the POC |
| RNF05 | Persistence and simple restart | C | F4 | Demonstrate that a saved job remains visible after application restart and can be resumed or reprocessed; document recovery limitations |
| RNF06 | Avoid duplicate publication | C | F4 | Redeliver an event and repeat an attempt; verify the example updates/reuses its identified summary rather than spamming; make no exactly-once claim |
| RNF07 | Timeout and bounded retries | C; values P | F2/F4 | Configure a timeout and few attempts, record errors, and respect rate limits; demonstrate one failure without an infinite loop or a full resilience test suite |
| RNF08 | Demonstrable isolation | C | F3/F7 | Demonstrate one permitted call and one denied call outside repository scope; keep connection/repository/SHA explicit without a production multitenancy matrix |
| RNF09 | Simple logs and correlation | C | F3/F4 | Find an execution by jobId and observe MCP/model/publication; console and database records suffice without a mandatory monitoring stack |
| RNF10 | Credentials and demonstration data | C | F2/F4 | Use demonstration data, secrets outside Git, and token-free logs; export sanitized evidence and discard temporary data on teardown; no corporate retention policy or continuous backup |
| RNF11 | Budget and cost | C; limits Q | F2/F6/F8 | Count all attempts, resent context, extra calls, cache, and billed reasoning without double counting; separate local cost, gross cloud cost, credits, and cash spending; stop with a partial indication on token/call/cost limits |
| RNF12 | Reproducible configuration | C | F1/F4 | Record dependencies, images, protocol, model, and parameters with instructions and secret placeholders so another person can repeat the example |
| RNF13 | Used MCP compatibility | C | F1/F3/F5 | Demonstrate discovery, call, and error for the chosen profile, including authorization with the gateway; no certification or all-version matrix required |
| RNF14 | Quality on known examples | C | F2/F6/F7 | Evaluate four initial cases and record valid findings, false positives, and failures; no minimum precision/recall or statistical approval gate |
| RNF15 | Local GPU use | C | F2/F7 | Observe GPU/VRAM and time during a short call with the chosen model/context; reduce context or change model if memory is insufficient, without continuous capacity testing |
| RNF16 | Bounded inference queue | C; policy P | F2/F7 | Bound application concurrency and `Q_max`; a busy service does not create an infinite queue; jobs remain observable; one initial inference call is P and does not limit global jobs |
| RNF17 | Local endpoint protection | C | F2/F3 | Use only approved application destinations; check network/firewall reachability and no planned public exposure; add authentication/proxy if necessary without inventing native protection |
| RNF18 | Effective context | C | F2/F7 | Record per-call `num_ctx`, prompt/schema/tool/output tokens, and server-observed context; split or declare partial if content does not fit; nominal model context does not prove available capacity |
| RNF19 | Temporary VPS capacity | C; size P | F8 | Observe CPU, RSS/heap, total RAM, disk, queue, and latency in the demonstration; 2 vCPUs/4 GB is accepted only for measured load without OOM or lost jobs; record architecture and ARM image compatibility where relevant |
| RNF20 | Total campaign budget | C; target P | F6/F8 | Initial US$5–10 target, validated and fixed before spending; compare estimated/actual infrastructure/inference/extras, eligible credits, and margin; bound application campaign usage and check final billing without promising a provider hard cap |
| RNF21 | Reproducible public portfolio | C | All; completion F8 | Review files/history and exported artifacts; publish no credentials or private information; instructions, versions, and examples reproduce the POC without the personal machine or a permanent installation |

## Sufficient evaluation for the POC

**P for measurement bands:** small (up to 2,000 diff tokens), medium (2,000–10,000), and large (over 10,000 or larger than effective context). Also record files, hunks, languages, additional context, and cross-component change volume. These bands organize experiments, not input limits or approved targets.

Earlier examples of 2 s/3 min/5 min/10 jobs/2 per repository/1 per PR remain in [decisions.md](decisions.md) only as proposed historical figures. Separate queue time from execution time and active reviews from concurrent GPU/platform calls. Do not extrapolate small-PR performance to large PRs.

Production targets need not be settled to proceed. POC acceptance means explaining and reproducing the flow, demonstrating the controls being learned, and honestly recording quality, time, cost, and limitations. Expand testing only when an observed problem or new learning objective justifies it.
