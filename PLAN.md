# Delivery and learning plan

Planning reference: 2026-11-12. [Index](README.md) · [Decisions](docs/decisions.md) · [Requirements](docs/requirements.md).

## Objective and boundaries

Build a PR review POC for hands-on learning and an interview portfolio, using a Java agent, MCP Gateway, MCP tools, and interchangeable model providers. The review core must support GitHub, GitLab, and Bitbucket through platform-independent contracts. Development takes place in VS Code and execution is intended to use Docker.

**Confirmed:** plan before implementation; Java; gateway in the final design; containers; multiple platforms and models; large PRs without a fixed file cutoff; configurable concurrency; reuse suitable servers; local NVIDIA inference. On 2026-11-13 the user requested project-owned Ollama/PostgreSQL, superseding reuse of another project's service.

**Additional confirmed directions:** local Ollama as the default for iteration to avoid external inference charges; a suitable model in the isolated project environment; a temporary VPS demonstration using an external API independently of the personal computer; select one initial external provider through evaluation; prepare the repository for public release and remove infrastructure after the demonstration. Commercial operation and permanent availability are not goals. Initially, do not rent a cloud GPU or move Ollama to the VPS.

Costs, candidates, and teardown criteria are centralized in [cloud-demo-and-portfolio.md](docs/cloud-demo-and-portfolio.md). **P:** one Docker Compose machine, initially 2 vCPUs/4 GB, and a total budget of US$5–10, both subject to measurement and revalidation. These figures are neither proven capacity nor authorization to spend during planning.

**Proposed MVP:** static analysis, a manual report first, GitHub automation later, an application-controlled summary, and read-only tools available to the model. No PR code execution, generic shell, code modification, merge, or automatic approval. Inline comments are future work. Stack details remain proposals in [decisions.md](docs/decisions.md).

Do not introduce Kubernetes, Kafka, Redis, multiple specialist agents, or distributed infrastructure without demonstrated need. Their presence in `iam-trainer` is not a reason to copy them.

**POC scale:** use the recommendations in [open-questions.md](docs/open-questions.md) to resolve technical choices within each exercise without asking the user about every detail. SLOs/SLAs, high availability, corporate retention policies, and formal staging approval are not delivery conditions. Simple logs, a few known examples, and basic credential, authorization, and spending controls are sufficient. Updating Ollama and trying a model better suited to local inference are part of preparation when useful; the old environment is not a permanent constraint.

## Proposed phases

**F1, F2 mechanics, F3 gateway, F4 local automation, and F5 manual GitLab review/publication are implemented**, as described in the phase guides below. F2's initial model quality did not meet all expected scenarios; the [F3 evaluation](docs/f3-model-evaluation.md) preserves failures and investigates model/prompt/inference causes. F4's real webhook/publication checks remain separate pending work; F6–F8 remain planned. Necessary security, validation, and honest coverage reporting accompany the first feature that needs them; F7 expands robustness rather than postponing those protections.

### F1 — MCP fundamentals, without a model

**Completed on 2026-11-12:** Java 21, Maven, MCP SDK 2.1.0, stdio, and protocol 2025-11-25; `get_review_fixture`, seven integration tests, and Docker execution without networking. The implementation is on `feat/f1-mcp-stdio`, created after the documentation commit on `develop`.

- **Learn:** host, client, server, discovery, schema, calls, results, and transport. Revisit Java records, interfaces, exceptions, and builds through a small example.
- **Deliver:** a Java client and an educational server with a deterministic read-only fixture tool. Choose a shared protocol profile, pin dependencies, and document Docker execution. Stdio is an educational option; HTTP can demonstrate independent containers.
- **Dependencies:** select compatible JDK/build/SDK versions and transport. Java 21/Maven and the official Java SDK were the initial proposals. No GitHub App, GPU, or model is required.
- **Done when:** discover the tool, display its schema, call it with a valid argument, and explain invalid arguments and unavailable-server errors. Record the protocol profile and close the connection correctly.
- **Validate:** known fixture output, schema error, and connection failure; inspect messages without credentials. Confirm execution in the chosen Docker environment.
- **Later:** AI, real diffs, gateway policies, delegated authentication, and publication.

### F2 — Manual review with local inference

**Implemented on 2026-11-13 on `feat/f2-local-pr-review`:** project-owned Compose infrastructure, Ollama 0.40.16, `qwen3.5:9b`, a narrow public-GitHub read-only MCP adapter, fixed-SHA retrieval, bounded context, validated findings, and local reports. Four real synthetic PRs include a correct 22-file change. See the [guide](docs/f2-local-pr-review.md) for results and limits. PostgreSQL is healthy but queue processing remains F4. No comparison model was downloaded into another project's environment.

- **Learn:** transform a diff into context; distinguish MCP tools from model calls; understand context windows and output validation.
- **Deliver:** manual input with platform, connection/repository, and PR/MR number; metadata/diff/context retrieval through MCP at a fixed SHA; a structured local report with coverage. Use F1 fixtures before a real repository. Own Ollama and PostgreSQL in this project's Compose setup, following D20.
- **Dependencies:** F1; a server providing the required operations; endpoint access and an experimental model choice. Public GitHub reads require no application token in F2; private repositories and bot writes remain later work. Perform the simple checks and four examples in [local-inference.md](docs/local-inference.md).
- **Done when:** a GPU-suitable model produces verifiable findings and accepted JSON, with human checking of diagnosis/severity on the known cases. If results fail, compare another model and inspect the supplied context/prompt/settings before attributing the issue. F3 addresses the comparison omitted in the first F2 delivery. Reject invented files/locations, out-of-scope arguments, and invalid output; paginate files and report partial analysis when a PR exceeds context.
- **Validate:** four known examples, with more than 20 files in one; check truncated content, timeout, and invalid schema handling. Observe cold/warm loading when useful for learning. Do not publish automatically at this stage.
- **Later:** automation, inline comments, and optimizations motivated by actual use. Evaluate model tool calling, while allowing deterministic context retrieval by the harness initially.

### F3 — Minimal governed gateway

**Implemented on `codex/f3-governed-gateway`:** separate Java gateway/agent containers; official MCP Streamable HTTP upstream and stdio downstream; a read-only server/repository registration file; generated lab worker credential; job/repository/PR/SHA checks; independently pinned tool catalog; console audit; bounded in-memory review lifecycle. OAuth, durable jobs, and publication remain later work. The accompanying [quality investigation](docs/f3-model-evaluation.md) compares models on frozen public inputs, prompt profiles, and reasoning settings rather than equating valid JSON with correctness.

- **Learn:** gateway as upstream server and downstream client; routing, identity, and resource authorization.
- **Deliver:** a separate Java process with administrative server registration, an allowed catalog, tool mapping, per-call validation, and auditing. Move F2's experimental direct access to agent → gateway → server.
- **Dependencies:** F1/F2; transport/protocol compatibility checks; an appropriate token issuer if OAuth is adopted. Do not build an IdP. The local lab identity must be explicit and restricted.
- **Done when:** each call carries job/repository/SHA scope and rejects cross-resource access, unapproved tools, and unregistered destinations; downstream credentials remain outside the model. Run agent and gateway as independent containers.
- **Validate:** allowed/denied calls, tampered arguments, repository switching, unavailable server, schema changes, and end-to-end correlation.
- **Later:** a complete enterprise catalog, multiple organizations, and broad interactive delegation.

### F4 — Automation, persistence, and publication

**Implemented locally on `codex/f4-webhook-jobs-private-repos`:** signed edge admission, PostgreSQL jobs/deduplication/leases, a private worker, GitHub App token support, controlled summary publication/reconciliation, and four private fixture PRs with Java checks. The local signed-fixture → database → real MCP/model worker flow passed with publication disabled. Live private App reads, real GitHub webhook delivery, and bot publication remain pending the user's [App installation steps](docs/f4-github-app-setup.md); they are not claimed as validated. See the [F4 guide](docs/f4-webhook-jobs.md).

- **Learn:** authenticated webhooks, app installation, durable jobs, recovery, and external effects.
- **Deliver:** GitHub first; a separate edge validates the signed webhook, commits a pending review, and confirms receipt. A private worker uses the same Java image, appropriate credentials, branch/draft/contributor/fork policy, and controlled publication. PostgreSQL is the implemented queue, without another broker. Internal worker/gateway authentication remains enforced; see [architecture.md](docs/architecture.md).
- **Dependencies:** F3; installed GitHub App and defined permissions; an automation-compatible MCP Server; a dedicated database; an accessible HTTPS endpoint. Consider a tunnel for local webhooks; GitHub cannot directly reach `localhost`.
- **Done when:** duplicate delivery produces one logical job; restart allows recovery; a new commit invalidates the previous review; publication records SHA and coverage and handles ambiguous results without deliberately duplicating the summary.
- **Validate:** invalid signature, irrelevant event, draft, filtered branch, revoked installation, token expiration, rate limits, crash after publication, and PR changes during analysis. Distinguish webhook response time from review execution time.
- **Later:** other platforms and inline comments. High availability is not a goal of this temporary POC.

### F5 — Second server or platform

**Implemented and live-validated on 2026-11-14 on `codex/f5-gitlab-adapter`:** GitLab.com REST v4 through a second repository-bound MCP adapter and platform-scoped gateway routing. The same Java analysis rules produced a correct MEDIUM finding on private synthetic [MR !1](https://gitlab.com/cleisommais/pr-test-repo/-/merge_requests/1); controlled publication and a repeat update produced exactly one summary note. Eighteen new tests cover platform contracts, pagination, omissions, renames, fork reads, permissions, current state and publication recovery. See the [F5 guide](docs/f5-gitlab-platform.md) and [evidence](docs/evaluation/f5/README.md). GitLab webhook/queue automation, inline positions, Self-Managed and Bitbucket remain unsupported.

- **Learn:** what MCP standardizes and what requires semantic translation.
- **Deliver:** a second server or adapter, preferably for another platform, passing the same contract checks. Replacing only the GitHub server does not validate GitLab/Bitbucket.
- **Dependencies:** F3/F4; selected offering (cloud or self-hosted version), an approved server, and compatible credentials.
- **Done when:** identify a change, enumerate files, read by SHA, produce a report, and publish a summary without changing the core analysis rules. Declare missing capabilities.
- **Validate:** pagination, omitted diffs, renames, current state, permissions, and comment locations for the selected offering.
- **Later:** remaining offerings and inline comments with platform-specific mapping.

### F6 — Second model or provider

**Optional two-model experiment:** retain single-model review by default. Proposed `REVIEW_MODE=verified` adds a second model that checks the primary findings against the same immutable context, with explicit verifier model and extra-call/time/cost limits. Disagreements remain visible and evidence is validated in Java; never blindly union findings or assume agreement proves correctness. Run models sequentially on the 16 GB GPU and compare the known cases before adoption. These settings are planned, not implemented or silently enabled by F4.

- **Learn:** compare quality, cost, context, tool use, and latency without coupling the harness to one provider.
- **Deliver:** compare installed local models and run a small external-candidate trial on known PRs with an explicit budget. Select only one initial external provider for the demonstration, recording quality and total cost per review. Local inference already starts in F2.
- **Dependencies:** F2/F3; reproducible evaluation; consent/data policy for sending data outside the local environment. Automatic Ollama updates are not required.
- **Done when:** change endpoint/model through configuration, preserve the findings contract, and demonstrate that unauthorized external fallback is blocked.
- **Validate:** per-case quality, structured output, tools, unavailability, usage, time, and budget under the same conditions; count resent context, additional calls, retries, and billed reasoning. Endpoint, model, and credential reference stay configurable; Plus is not an API credit balance.
- **Later:** task-based selection, additional models, and optimizations justified by measurements.

### F7 — Concurrency, failure, and security experiments

- **Learn:** actual GPU limits, concurrency, coverage, and failure handling.
- **Deliver:** an example involving two repositories, separate job/inference controls, a PR larger than context, and targeted failure/injection checks. Advanced fair scheduling is optional.
- **Dependencies:** a working basic flow; choose one experiment at a time based on learning needs, without pending production targets to approve.
- **Done when:** explain concurrency, failure, and coverage in the executed examples, with no out-of-scope access or partial coverage presented as complete.
- **Validate:** one unavailability/timeout, one restart, and the prompt injection and cross-file bug examples; record results and limitations. Do not require load tests, chaos testing, or statistical samples.
- **Later:** investigate only what helps learning or solves an observed problem; distributed infrastructure is not a POC objective.

### F8 — Temporary demonstration and portfolio delivery

- **Learn:** operate the full flow outside the personal computer, measure cost, and reproduce setup, demonstration, and teardown.
- **Deliver:** reproducible instructions for one VPS with Docker Compose, direct HTTPS webhook, persistence, agent, gateway-mediated tools, the selected external model API, and a summary published on the PR. Prepare README, synthetic examples, results, limitations, and a short recording for a public repository.
- **Dependencies:** F4 and F6's external provider selection, applicable security/budget controls, and a locally validated flow. Demonstration can precede F5 and full F7 hardening; it does not require every platform or a commercial capacity promise.
- **Done when:** opening/updating a PR produces a result without the personal desktop/Ollama, correlates event/job/MCP/model/publication, and preserves evidence after VM deletion. A Git pipeline is not required to trigger the flow.
- **Validate:** the candidate 2 vCPU/4 GB machine through CPU/RAM/queue/latency observations; price and eligible credit before provisioning; call/token/retry/duration limits; sanitized artifacts and the final billable-resource inventory. Measure gross cost and cash spending after credits, following the [cloud plan](docs/cloud-demo-and-portfolio.md).
- **Later:** remove temporary infrastructure and check residual billing; keep only public evidence and instructions. Do not maintain an online endpoint for interviews.

Portfolio documentation and examples develop throughout the phases. Publishing code/the repository, recording the demonstration, and provisioning resources are future tasks, not actions performed by a planning update.

## Recommended first hands-on exercise

The exercise is available: `get_review_fixture` takes a scenario identifier and returns a known synthetic diff. The client discovers the schema, calls the valid scenario, and tries invalid inputs. This is the F1 educational contract, not the final Git integration contract.

Read the [F1 guide](docs/f1-mcp-stdio.md) to run it without AI and follow each component. The goal is to understand the message crossing the MCP boundary and the returned error. Versions and transport are recorded in the guide and POM.

## Current scope

The user authorized F5 and supplied the private GitLab.com test project and separate read/publication credentials in ignored `.env`. The synthetic branches/MR, gateway-mediated local-model review, and single-summary create/update are verified. F4's remaining real GitHub webhook/publication validation is still separate pending work. Optional two-model verification remains planned in F6, disabled by default. Project-owned Ollama/PostgreSQL and the fixture repositories remain the learning environment. Credentials and local publication journals remain outside Git; other projects' infrastructure is preserved.
