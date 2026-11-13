# MCP API Gateway — project plan

A PR/MR review POC with a Java agent, MCP Gateway, and adapters for GitHub, GitLab, and Bitbucket. Its purpose is hands-on learning and an interview portfolio demonstrating MCP Client, MCP Servers, authorization, and auditing.

**Status as of 2026-11-12:** planning documentation committed on `develop`; **F1 implemented on `feat/f1-mcp-stdio`**, with a real Java client and server, a fixture tool, and Docker execution. AI review and webhook automation belong to later phases.

**Project language: English.** Documentation, diagrams, code comments, tool descriptions, application messages, and future commit messages use English.

Existing Ollama inference is the default for development and validation, subject to quality and capacity checks. To demonstrate independence from the personal computer, the plan includes a temporary VPS using an external model API, initially without a cloud GPU or Ollama on the VPS. The Java harness coordinates the review; Ollama serves the local model; the gateway governs tools.

This is not intended to become a commercial service or a permanently available installation. The repository will be prepared for public release with reproducible instructions, examples, evaluations, and a short recording. The demonstration ends by exporting evidence and removing billable resources.

## Documentation

| Document | Main content |
| --- | --- |
| [PLAN.md](PLAN.md) | Scope, phases, learning goals, and first exercise |
| [F1: Java MCP call](docs/f1-mcp-stdio.md) | How to run, understand, and verify the first phase |
| [Requirements](docs/requirements.md) | Functional/nonfunctional requirement IDs, acceptance, measurement, and phase |
| [Architecture](docs/architecture.md) | Components, contracts, flows, and coverage |
| [Security and identity](docs/security-and-identity.md) | Credentials, authorization, and threats |
| [Decisions](docs/decisions.md) | Confirmed directions, proposals, future work, and alternatives |
| [Open questions](docs/open-questions.md) | Outstanding choices, recommendations, and when to address them |
| [Local infrastructure](docs/local-inference.md) | Verified inventory, Ollama, networking, evaluation, and future updates |
| [Cloud demonstration and portfolio](docs/cloud-demo-and-portfolio.md) | Temporary VPS, API comparison, budget, evidence, and teardown |

Decision classifications are defined in [decisions.md](docs/decisions.md). Including a technical proposal in a diagram or acceptance criterion does not make it an approved decision.

## Inspection results

The user confirmed an RTX 5060 Ti with 16 GB VRAM, Ryzen 9 5950X, approximately 64 GiB RAM, and Ollama **0.18.0** managed by `divine-touch-infra`, with `qwen3:14b`, `gpt-oss:20b`, and `divine-touch-30b:latest` installed. These details match the earlier inspection; no model was loaded during that query. Performance and GPU inference remain unverified. Evidence and limitations are recorded in the [inventory](docs/local-inference.md).

## Run F1

```powershell
docker build --progress plain -t mcp-api-gateway:f1 .
docker run --rm --network none mcp-api-gateway:f1
```

The client starts a child MCP Server over stdio, discovers `get_review_fixture`, and calls it with valid and invalid arguments. The build runs seven integration tests; the [F1 guide](docs/f1-mcp-stdio.md) explains each file and the unavailable-server scenario.

The exercise needs no AI, Git account, database, or network ports. The gateway remains part of the final design and is introduced in F3. After working through the exercise, the next increment is F2; it has not started. Ollama and other projects' infrastructure were not changed; nothing was pushed or deployed.
