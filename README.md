# MCP API Gateway — project plan

A PR/MR review POC with a Java agent, MCP Gateway, and adapters for GitHub, GitLab, and Bitbucket. Its purpose is hands-on learning and an interview portfolio demonstrating MCP Client, MCP Servers, authorization, and auditing.

**Status as of 2026-11-13:** F1/F2 mechanics and **F3 MCP Gateway** are implemented on `codex/f3-governed-gateway`. Manual public-GitHub reviews now use a separate authenticated gateway container. F2's initial scenario quality was overstated; the [model investigation](docs/f3-model-evaluation.md) distinguishes correct reasoning from valid JSON. Webhooks, database jobs, and publication remain F4.

**Project language: English.** Documentation, diagrams, code comments, tool descriptions, application messages, and future commit messages use English.

This project owns its Ollama and PostgreSQL containers, network, and named volumes. Its measured F3 default is `gpt-oss:20b` with medium reasoning on the local NVIDIA GPU; the Qwen candidates remain installed for comparison. To demonstrate independence from the personal computer, the plan includes a temporary VPS using an external model API, initially without a cloud GPU or Ollama on the VPS. The Java harness coordinates the review; Ollama serves the local model; the gateway governs tools.

This is not intended to become a commercial service or a permanently available installation. The repository will be prepared for public release with reproducible instructions, examples, evaluations, and a short recording. The demonstration ends by exporting evidence and removing billable resources.

## Documentation

| Document | Main content |
| --- | --- |
| [PLAN.md](PLAN.md) | Scope, phases, learning goals, and first exercise |
| [F1: Java MCP call](docs/f1-mcp-stdio.md) | How to run, understand, and verify the first phase |
| [F2: local PR review](docs/f2-local-pr-review.md) | Compose setup, code walkthrough, manual review, limits, and test results |
| [F3: governed gateway](docs/f3-governed-gateway.md) | Separate containers, scoped routing, lab identity, contract validation, audit, and tests |
| [F3: model quality investigation](docs/f3-model-evaluation.md) | Identical-input comparisons, larger models, reasoning settings, failures, and conclusions |
| [Requirements](docs/requirements.md) | Functional/nonfunctional requirement IDs, acceptance, measurement, and phase |
| [Architecture](docs/architecture.md) | Components, contracts, flows, and coverage |
| [Security and identity](docs/security-and-identity.md) | Credentials, authorization, and threats |
| [Decisions](docs/decisions.md) | Confirmed directions, proposals, future work, and alternatives |
| [Open questions](docs/open-questions.md) | Outstanding choices, recommendations, and when to address them |
| [Local infrastructure](docs/local-inference.md) | Verified inventory, Ollama, networking, evaluation, and future updates |
| [Cloud demonstration and portfolio](docs/cloud-demo-and-portfolio.md) | Temporary VPS, API comparison, budget, evidence, and teardown |

Decision classifications are defined in [decisions.md](docs/decisions.md). Including a technical proposal in a diagram or acceptance criterion does not make it an approved decision.

## Run the current review flow

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Initialize-LocalEnvironment.ps1
docker compose up -d postgres ollama
docker compose --profile setup run --rm model-pull
docker compose --profile review build gateway review
docker compose --profile review up -d gateway
docker compose --profile review run --rm review github cleisommais/pr-test-repo 1
```

Reports appear in `reports/`. The model API is at `http://localhost:11435`, PostgreSQL uses `localhost:15432`, and the gateway uses `http://localhost:18080/mcp`. All published ports bind to loopback. The setup script preserves existing values and generates missing local database/gateway credentials in ignored `.env`. PostgreSQL is provisioned for F4 and is not used as a queue yet.

The [F3 guide](docs/f3-governed-gateway.md) explains the implemented tool path. The four open [test PRs](https://github.com/cleisommais/pr-test-repo/pulls) and [evaluation evidence](docs/f3-model-evaluation.md) demonstrate both useful findings and model failures. `complete` describes mechanical coverage/validation; quality requires human review. Reviews stay local.

## Run F1

```powershell
docker build --progress plain -t mcp-api-gateway:f1 .
docker run --rm --network none mcp-api-gateway:f1
```

The client starts a child MCP Server over stdio, discovers `get_review_fixture`, and calls it with valid and invalid arguments. The build runs seven integration tests; the [F1 guide](docs/f1-mcp-stdio.md) explains each file and the unavailable-server scenario.

The F1 exercise needs no AI, Git account, database, or network ports. All implemented phases use Java 21 and MCP SDK 2.1.0. The project's infrastructure remains separate from other projects' containers and data.
