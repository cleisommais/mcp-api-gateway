# Architecture

A component-oriented, layered Java backend. Controllers translate HTTP to DTOs, services run workflows, repositories own SQL, and platform clients own external API calls. There is no view layer. The neutral `com.prreview` namespace describes the application rather than an author or implementation milestone. One image runs three separate Java processes.

## Delivered requirements

| Functional requirement | Delivered behavior |
| --- | --- |
| Accept review requests | Authenticated GitHub App/repository, GitLab and Bitbucket Cloud webhooks; authenticated manual/CI requests for configured PRs/MRs |
| Queue work asynchronously | Persist the delivery/job before acknowledging; deduplicate deliveries and base/head snapshots |
| Read the correct change | Retrieve metadata, diffs and bounded file context through MCP, pinned to immutable commit SHAs |
| Review code | Build prompts, call the selected model, audit zero-candidate batches on the same pinned context, validate JSON and exact added-line evidence; optionally verify candidates |
| Publish the result | Create/update a marked summary comment, including zero-findings and partial results; retain uncertain publication state for reconciliation |
| Expose progress | Return job state, attempts, failures, analysis and the published-comment URL through the status API |

| Non-functional requirement for this POC | Delivered mechanism and limit |
| --- | --- |
| Reproducible local operation | One Compose startup with five services; Java 21/Maven build runs in Docker; debugger attach configurations are included |
| Durable processing and recovery | PostgreSQL transactions, unique snapshot key, atomic claims, owner fencing and renewable leases; execution is at least once |
| Bounded resource use | Compose defaults: one active job, primary 8K context/4,096 output tokens, local clean audit 16K context/8,192 output tokens, 240-second call timeout and shared 600-second review deadline; coverage exposes incomplete work |
| Controlled external usage | External inference is disabled by default; explicit repository allowlist, key file, persistent cost reservations and bounded calls are required |
| Diagnosable results | Correlated job IDs, MCP audit decisions, saved coverage/errors/provider metadata and comment reconciliation |
| Verifiable behavior | Real PostgreSQL integration tests, MCP/HTTP/provider contract tests and opt-in live/public-fixture regression checks |

These are POC capabilities. A temporary OCI deployment runs the services and PostgreSQL on one public VPS with GPT inference and Caddy HTTPS; see [deployment](deploy/oci/README.md) and [real webhook evidence](docs/evaluation/oci/README.md). Availability targets, multi-tenant isolation, Kubernetes deployment, distributed inference admission and production capacity guarantees are not delivered. Model evidence validation checks location and format; human review still assesses whether a finding is correct.

## Components and boundaries

```mermaid
flowchart LR
    GH[GitHub, GitLab, Bitbucket webhooks] --> API[API gateway]
    User[Manual HTTP client] --> API
    API --> DB[(PostgreSQL job queue)]
    API --> MCP[MCP gateway]
    DB --> Worker[Agent worker]
    Worker --> MCP
    MCP --> Servers[GitHub, GitLab and Bitbucket MCP servers]
    Servers --> Platforms[Repository APIs]
    Worker --> Model[Ollama or enabled model provider]
    Worker --> Publisher[Platform review publisher]
    Publisher --> Platforms
```

Locally, only the API gateway accepts normal review traffic (`localhost:18081`). On OCI, Caddy publishes 80/443 and proxies to the private API service. GitHub/Bitbucket webhooks use raw-body HMAC; GitLab uses a dedicated secret token over TLS. Repository/branch/fork checks apply before admission; GitHub additionally checks contributor association and any supplied installation identity. Manual requests use a separate bearer token. MCP is private and requires its own bearer token plus tool/repository/SHA scope checks. The worker has no HTTP listener. An internal network does not replace these checks.

The MCP gateway routes repository **tools**, not Claude/OpenAI/Ollama inference. It selects a configured MCP server by platform/repository; the server authenticates through a GitHub App installation token, GitLab read token or Bitbucket read token. GitHub credentials are minted by `GitHubApiClient.AppCredential`; there is no standalone broker service. Model selection belongs to `ReviewService`, based on explicit provider/model settings. Publication is a direct platform write with a separate permission path, outside read-only MCP tools.

| Package under `src/main/java/com/prreview` | Responsibility |
| --- | --- |
| `apigateway` | `ApiGatewayApplication`, controllers, request/response DTOs, admission service, webhook policy |
| `agent` | `AgentWorkerApplication`, worker orchestration, prompt batching, finding validation, model clients and optional verifier |
| `mcpgateway` | `McpGatewayApplication`, routing configuration, tool catalog, scope validation and audit |
| `mcp.client` / `mcp.server` | HTTP MCP client; downstream stdio clients and GitHub/GitLab/Bitbucket read-only MCP servers |
| `domain.model` / `domain.port` | Snapshot, finding, job and event models; `ChangeSource` and `ReviewModel` interfaces |
| `persistence.repository` | `PostgresJobRepository`: schema setup, queue, leases, report and publication state |
| `platform.github` / `platform.gitlab` / `platform.bitbucket` | Platform REST clients and comment reconciliation/publishers |
| `common` / `tools` | JSON, secret-file loading, concurrency control; local queue administration |

## Request-to-comment sequence

1. `ApiGatewayApplication` registers routes. `ReviewController.handle` authenticates and validates `CreateReviewRequest`; `ReviewRequestService.create` checks configuration, requests a pinned snapshot through `GatewayMcpClient`, and calls `PostgresJobRepository.enqueue`. For webhooks, `GitHubWebhookController.handle` calls `GitHubWebhookPolicy.validate` then the same repository. The HTTP acknowledgement happens **after the enqueue transaction commits**.
   `PlatformWebhookController` handles GitLab/Bitbucket callbacks: authenticate, validate routing metadata and persist `webhook_requests`, then acknowledge. `WebhookAdmissionService` claims these durable receipts, resolves the current snapshot through MCP and admits a normal job. A Bitbucket push resolves only the changed branch's open PRs after receiving that event; there is no periodic remote repository scan. Admission retries at 20-second intervals, up to 12 attempts, with a five-minute recovery lease. Receipt persistence and review-job creation are separate stages; receipt UUIDs are not job UUIDs.
2. `ReviewWorker.once` recovers expired leases and calls `PostgresJobRepository.claim`. It starts a heartbeat and rechecks the pinned base/head and target branch through MCP. Repository code is read as data; it is never executed.
3. `McpGatewayService.invoke` checks authentication, catalog, repository, job context and commit scope. `StdioMcpClient` calls the platform MCP server, which uses `GitHubChangeSource`, `GitLabChangeSource` or `BitbucketChangeSource` to fetch metadata, diffs and file contents.
4. `ReviewService.review` selects the configured model(s). `ReviewEngine.review` builds bounded batches from patches and supplemental context, treats repository instructions as untrusted data, requests schema-constrained JSON, and checks finding paths, added lines and exact evidence. `OllamaModelClient.generate` includes the response schema in the system prompt and calls Ollama `/api/chat`. Optional `CandidateVerifier` evaluates proposed findings. Reports include coverage and failures rather than treating incomplete analysis as clean.
   Zero-candidate batches receive an independent pass. `clean-review-audit-v2` requires expected behavior to be grounded in supplied code/callers/contracts; language-defined arithmetic alone does not establish a bug. This corrected a real unsupported overflow finding without changing the primary `review-original-v3` prompt. It remains a model policy, not a guarantee against false positives or missed defects.
5. The worker persists the report before any remote write. Under `publicationLock`, the platform's `GitHubReviewPublisher.publish`, `GitLabReviewPublisher.publish` or `BitbucketReviewPublisher.publish` revalidates the snapshot, finds its own marked comment, records write uncertainty, then creates or updates that comment. It records the comment ID and terminal state. Status derives the comment URL from the stored identity.

## Authentication and security

The source repository is a resource identifier, not an authenticated caller. The caller is a provider delivering an authenticated webhook, or a CI job/user sending an HTTP request. Repository-read credentials are a separate outbound concern.

| Connection | Authentication and checks |
| --- | --- |
| GitHub -> `POST /webhooks/github` | `GitHubWebhookPolicy.validate` verifies raw-body `X-Hub-Signature-256`, repository ID/name, target branch, contributor association and fork policy. An App payload's installation must match configuration. Repository hooks omit installation; the worker still uses only the configured installation. Delivery UUID/body hash suppress duplicate jobs. |
| GitLab -> `POST /webhooks/gitlab` | Constant-time `X-Gitlab-Token` comparison over TLS; configured project ID/path, MR action/state/draft, same-project source/target and target branch. Dedicated webhook secret, distinct from read/publish API tokens. Signing-token authentication is not implemented. |
| Bitbucket -> `POST /webhooks/bitbucket` | Raw-body HMAC-SHA256 in `X-Hub-Signature`; configured repository, open non-draft same-repo PR and target branch. Push admissions pin the pushed commit instead of trusting a temporarily stale PR-list hash. Separate push and PR-update deliveries share one snapshot job. |
| CI/user -> `POST /api/reviews` and `GET /api/reviews/{id}` | `Authorization: Bearer <api-token>`, checked by `ReviewController.handle`. The token comes from `API_TOKEN_FILE`; requests are restricted to configured repository targets. This POC has one shared API token, not per-user or per-repository identities. |
| API/worker -> MCP gateway | Separate internal bearer token, approved tool catalog, configured repository routing and pinned job/commit/path scope |
| Services -> GitHub/GitLab/Bitbucket | GitHub App private key signs a JWT used to obtain a repository-scoped installation token. Reads request read permissions; publication requests Pull requests write. GitLab and Bitbucket use separate read and publish token files. Bitbucket API tokens use the configured Atlassian email for Basic authentication. These credentials are never supplied in the review request. |
| Worker -> inference | Ollama is local to the Compose environment. External requests use the provider key over HTTPS, after the explicit external-data and budget checks. |

**GitHub App webhook setup:** configure the App callback as `https://<reachable-api-host>/webhooks/github`, select JSON, and configure the same secret as `secrets/github-webhook-secret`. Install the App on allowed repositories and subscribe to Pull request events. Opening/updating an eligible PR then triggers the review directly; a GitHub Actions workflow is not required for this path.

Alternatively, configure a JSON repository webhook for Pull requests on each allowed GitHub repository, using the same callback and its configured receiver secret. The OCI acceptance uses repository hooks; the existing App supplies outbound access. GitLab uses `/webhooks/gitlab` with Merge request events and `GITLAB_WEBHOOK_SECRET_FILE`. Bitbucket uses `/webhooks/bitbucket`, PR created/updated and repository push events, with `BITBUCKET_WEBHOOK_SECRET_FILE`. These two routes are registered only when their corresponding secrets are configured. The standalone VPS Compose mounts them; local Compose can opt in with equivalent environment/secret mounts. [Setup instructions](deploy/oci/README.md) include the exact secret-file locations and administration scripts.

**GitHub Actions or GitLab CI setup:** store the value of `secrets/api-token` as a CI secret such as `PR_REVIEW_API_TOKEN`, and the reachable HTTPS base URL as `REVIEW_API_URL`. A hosted CI runner cannot reach the developer computer's `localhost`. Provide the token only to trusted jobs. The request is:

```http
POST /api/reviews
Authorization: Bearer <PR_REVIEW_API_TOKEN>
Content-Type: application/json

{"platform":"github","repository":"owner/repository","number":123}
```

A GitHub Actions job obtains the repository and number from `github.repository` and `github.event.pull_request.number`. A GitLab MR pipeline uses `platform=gitlab`, `CI_PROJECT_PATH` and `CI_MERGE_REQUEST_IID`. A Bitbucket PR pipeline uses `platform=bitbucket`, `BITBUCKET_REPO_FULL_NAME` and `BITBUCKET_PR_ID`. It receives a job/status URL and can poll using the same bearer token. CI is optional; configured provider webhooks trigger reviews directly.

Only the API gateway is exposed for incoming review traffic. Local PostgreSQL/Ollama ports and optional debugger ports bind to loopback; MCP has no default host port. Public HTTPS terminates at the configured tunnel/reverse proxy. The delivered controls use signatures, tokens and allowlists; IP filtering, OIDC, per-caller scopes and internal TLS are not implemented. Repository text is untrusted model input, and repository code is never executed. Credentials live in ignored `secrets/` files with per-service mounts; `.env` contains settings.

## HTTP contracts

Machine-readable contract: [open-api.yaml](open-api.yaml).

| Method/path | Authentication | Contract |
| --- | --- | --- |
| `GET /health` | None | `200 {"status":"UP","component":"api-gateway"}` |
| `POST /api/reviews` | `Authorization: Bearer <api-token>` | Exactly `{"platform":"github","repository":"namespace/repo","number":1}`; platform is `github`, `gitlab` or `bitbucket`; `202` new job, `200` duplicate; `{jobId,state,duplicate,statusUrl}` and `Location` header |
| `GET /api/reviews/{uuid}` | Same API token | `200` with job identity, state, attempts, failureCode, publicationState, commentId, commentUrl, timestamps and nullable report; `404` unknown job |
| `POST /webhooks/github` | GitHub `X-Hub-Signature-256` over raw body, delivery and event headers | GitHub payload; `202` persisted delivery, `200` duplicate; no model work runs in this request |
| `POST /webhooks/gitlab` | `X-Gitlab-Token`, delivery identity and event headers | Native MR payload; `202 {outcome,reviews:[{receiptId,duplicate}]}` after durable receipt; `200` ignored |
| `POST /webhooks/bitbucket` | `X-Hub-Signature` over raw body, `X-Request-UUID`, `X-Event-Key` | Native PR/push payload; same durable-receipt response; `200` ignored |

Manual API errors: `400` invalid input/snapshot, `401` missing or incorrect bearer, `403` disallowed target, `413` oversized body, `503` unavailable dependency. Only open, non-draft changes on the configured target branch are accepted. The internal MCP transport is `POST /mcp` with MCP JSON-RPC and protocol/session headers, not an ordinary REST repository endpoint; use `GatewayMcpClient`.

## Database model

Source of truth: `src/main/resources/db/schema.sql`. Default schema: `review`. Initialization is serialized with a PostgreSQL advisory lock; existing queue data is retained.

```mermaid
erDiagram
    JOBS o|--o{ DELIVERIES : referenced_by
    JOBS {
        uuid id PK
        text platform
        bigint repository_id
        text repository
        int pr_number
        bigint installation_id
        char40 base_sha
        char40 head_sha
        text base_branch
        timestamptz source_updated_at
        text state
        int attempts
        text run_owner
        timestamptz lease_until
        jsonb report
        text failure_code
        text publication_state
        bigint publication_id
        timestamptz created_at
        timestamptz updated_at
    }
    DELIVERIES {
        uuid delivery_id PK
        char64 payload_hash
        text event
        text action
        text outcome
        uuid job_id FK
        timestamptz received_at
    }
```

`jobs` has a unique index on `(platform, repository_id, pr_number, base_sha, head_sha)` and an index on `(state, created_at)`. A delivery can reference zero or one job; multiple deliveries can reference the same snapshot job. Reusing a delivery UUID with a different payload hash is rejected. No raw webhook body or credential is stored. The `report` JSONB contains findings, coverage, model metadata and retrieval trace. GitLab MRs and Bitbucket PRs use the shared `pr_number` column and zero `installation_id`. Bitbucket's numeric `repository_id` is a configured local queue key, not its remote UUID. [Bitbucket details](docs/bitbucket-platform.md) describe platform-specific retrieval, publication and current validation limits.

`webhook_requests` is the durable pre-admission inbox for GitLab/Bitbucket. Its UUID derives from platform, provider delivery and PR number or changed branch. Columns retain repository/PR/branch/head, event/action, payload hash and provider delivery identity, plus state, attempts, retry time and lease. States are `queued -> processing -> complete`, or `failed` after 12 unsuccessful admissions. Only normalized metadata is stored. A callback commits this receipt before HTTP 202; the admission worker later creates/reuses a normal job and its correlated `deliveries` record. Database consumption is asynchronous; remote reads occur only in response to received callbacks.

### Claims with two worker pods

**Yes: two pods sharing the same PostgreSQL database and schema cannot claim the same queued job.** `claim` takes a short transaction-level admission lock, checks global/per-repository active limits, then uses `FOR UPDATE SKIP LOCKED` and one atomic update to set `running`, increment attempts and assign `run_owner` and `lease_until`. The transaction commits before network or model work. There is no long-lived row lock during inference.

The worker renews its default 90-second lease. Report/publication/finish writes require the current owner and an unexpired lease. `recoverExpired` requeues abandoned running/publishing jobs; a new claim assigns a new owner, so stale-owner writes fail. Configure identical limits in every pod; `WORKER_MAX_JOBS` also controls local worker threads. Inference admission is process-local, not a distributed GPU semaphore. MCP review contexts are also held in gateway memory; scaling the MCP gateway itself requires session affinity or a different context-storage design.

Normal states are `queued -> running -> publishing -> succeeded`. Terminal alternatives are `partial`, `failed`, `superseded`, and `publication_uncertain`. Expired running/publishing jobs return to `queued`. An administrative retry requeues failed/uncertain jobs; it preserves any saved report and publication uncertainty.

Execution is **at least once** after lease expiry, not exactly once. Publication uses a platform/repository/change advisory lock and a stable owned-comment marker, updating an existing summary instead of blindly creating another. A remote API write and a database commit cannot form one transaction: if a response is lost, reconciliation checks the remote comment; unresolved ambiguity remains explicit. Kubernetes deployment, distributed inference limits and HA infrastructure are outside this local Compose setup.
