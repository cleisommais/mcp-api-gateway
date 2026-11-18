# Plan

- [x] Organize Java code by component and conventional controller/service/repository/model responsibilities.
- [x] Use one local Compose startup with explicit API gateway, MCP gateway, worker, PostgreSQL and Ollama services.
- [x] Consolidate credential files under `secrets/`; keep settings in `.env`.
- [x] Queue authenticated manual requests and signed GitHub webhooks; publish review summaries in the normal worker workflow.
- [x] Document HTTP contracts, debugger entrypoints, database model and cross-worker leasing.
- [x] Run fresh automated and live public/private review/publication checks; record model accuracy separately.
- [x] Record validation outcome, review the final diff and commit the refactor.
- [x] Provide opt-in local/GPT regression checks for frozen public fixtures.
- [x] Add the root OpenAPI contract and document delivered requirements, security boundaries and webhook/CI authentication.
- [x] Add the Bitbucket Cloud read-only MCP adapter, manual review routing and controlled summary publisher, with Windows/Bash setup and fixture validation.
- [x] Populate the private Bitbucket repository with five real PRs and validate inference, publication and read-back.
- [x] Archive superseded result tables and collect a fresh paired local/GPT comparison for all configured repositories using the same prompt and pinned commits.

Next planned work: validate GitHub-delivered webhooks through a public HTTPS callback, then optionally demonstrate the flow on a temporary VPS with Compose and external inference, followed by teardown. No cloud resources are provisioned by this refactor.

GitLab/Bitbucket webhook automation, Bitbucket fork/Data Center support, inline review threads and Kubernetes deployment remain separate work. The current interface publishes one summary comment per change. Current live model-comparison evidence is under `docs/evaluation/current/`.
