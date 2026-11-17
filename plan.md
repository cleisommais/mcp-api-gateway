# Plan

- [x] Organize Java code by component and conventional controller/service/repository/model responsibilities.
- [x] Use one local Compose startup with explicit API gateway, MCP gateway, worker, PostgreSQL and Ollama services.
- [x] Consolidate credential files under `secrets/`; keep settings in `.env`.
- [x] Queue authenticated manual requests and signed GitHub webhooks; publish review summaries in the normal worker workflow.
- [x] Document HTTP contracts, debugger entrypoints, database model and cross-worker leasing.
- [x] Run fresh automated and live public/private review/publication checks; record model accuracy separately.
- [x] Record validation outcome, review the final diff and commit the refactor.
- [x] Check the local severity mismatch against the GPT API on frozen public fixtures (4/4); accept local variability for the POC and reject a prompt candidate that regressed locally.
- [x] Add the root OpenAPI contract and document delivered requirements, security boundaries and webhook/CI authentication.
- [x] Add the Bitbucket Cloud read-only MCP adapter, manual review routing and controlled summary publisher, with Windows/Bash setup and fixture validation.
- [x] Populate the private Bitbucket repository with five real PRs; validate local/GPT inference, publication, read-back and repeat updates; preserve prompt-profile comparisons.

Next planned work: validate GitHub-delivered webhooks through a public HTTPS callback, then optionally demonstrate the flow on a temporary VPS with Compose and external inference, followed by teardown. No cloud resources are provisioned by this refactor.

GitLab/Bitbucket webhook automation, Bitbucket fork/Data Center support, inline review threads and Kubernetes deployment remain separate work. The current interface publishes one summary comment per change. Bitbucket live fixture and model-comparison evidence is under `docs/evaluation/bitbucket/`.
