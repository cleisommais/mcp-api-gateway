# MCP API Gateway — project plan

A PR/MR review POC with a Java agent, MCP Gateway, and adapters for GitHub, GitLab, and Bitbucket. Its purpose is hands-on learning and an interview portfolio demonstrating MCP Client, MCP Servers, authorization, and auditing.

**Status as of 2026-11-13:** F1 is complete. **F2 adds manual public-GitHub PR review through MCP and local Ollama**, with isolated Docker Compose services and local JSON reports on `feat/f2-local-pr-review`. The gateway arrives in F3; webhooks, database jobs, and publication arrive in F4.

**Project language: English.** Documentation, diagrams, code comments, tool descriptions, application messages, and future commit messages use English.

This project owns its Ollama and PostgreSQL containers, network, and named volumes. It uses `qwen3.5:9b` on the local NVIDIA GPU. To demonstrate independence from the personal computer, the plan includes a temporary VPS using an external model API, initially without a cloud GPU or Ollama on the VPS. The Java harness coordinates the review; Ollama serves the local model; the future gateway governs tools.

This is not intended to become a commercial service or a permanently available installation. The repository will be prepared for public release with reproducible instructions, examples, evaluations, and a short recording. The demonstration ends by exporting evidence and removing billable resources.

## Documentation

| Document | Main content |
| --- | --- |
| [PLAN.md](PLAN.md) | Scope, phases, learning goals, and first exercise |
| [F1: Java MCP call](docs/f1-mcp-stdio.md) | How to run, understand, and verify the first phase |
| [F2: local PR review](docs/f2-local-pr-review.md) | Compose setup, code walkthrough, manual review, limits, and test results |
| [Requirements](docs/requirements.md) | Functional/nonfunctional requirement IDs, acceptance, measurement, and phase |
| [Architecture](docs/architecture.md) | Components, contracts, flows, and coverage |
| [Security and identity](docs/security-and-identity.md) | Credentials, authorization, and threats |
| [Decisions](docs/decisions.md) | Confirmed directions, proposals, future work, and alternatives |
| [Open questions](docs/open-questions.md) | Outstanding choices, recommendations, and when to address them |
| [Local infrastructure](docs/local-inference.md) | Verified inventory, Ollama, networking, evaluation, and future updates |
| [Cloud demonstration and portfolio](docs/cloud-demo-and-portfolio.md) | Temporary VPS, API comparison, budget, evidence, and teardown |

Decision classifications are defined in [decisions.md](docs/decisions.md). Including a technical proposal in a diagram or acceptance criterion does not make it an approved decision.

## Run F2

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Initialize-LocalEnvironment.ps1
docker compose up -d postgres ollama
docker compose --profile setup run --rm model-pull
docker compose --profile review build review
docker compose --profile review run --rm review github cleisommais/pr-test-repo 1
```

Reports appear in `reports/`. The model API is at `http://localhost:11435`; PostgreSQL uses `localhost:15432`. Both ports bind to loopback. The setup script generates an ignored `.env` with a local database password; it preserves an existing file. PostgreSQL is provisioned for F4 and is not used as a queue in F2.

The [F2 guide](docs/f2-local-pr-review.md) explains the four open [test PRs](https://github.com/cleisommais/pr-test-repo/pulls), results, and how the MCP tool path differs from the model API call. Reviews stay local.

## Run F1

```powershell
docker build --progress plain -t mcp-api-gateway:f1 .
docker run --rm --network none mcp-api-gateway:f1
```

The client starts a child MCP Server over stdio, discovers `get_review_fixture`, and calls it with valid and invalid arguments. The build runs seven integration tests; the [F1 guide](docs/f1-mcp-stdio.md) explains each file and the unavailable-server scenario.

The F1 exercise needs no AI, Git account, database, or network ports. Both phases use Java 21 and MCP SDK 2.1.0. F2 provisions only this project's infrastructure and preserves other projects' containers and data.
