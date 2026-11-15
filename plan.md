# Plan

- [x] Organize Java code by component and conventional controller/service/repository/model responsibilities.
- [x] Use one local Compose startup with explicit API gateway, MCP gateway, worker, PostgreSQL and Ollama services.
- [x] Consolidate credential files under `secrets/`; keep settings in `.env`.
- [x] Queue authenticated manual requests and signed GitHub webhooks; publish review summaries in the normal worker workflow.
- [x] Document HTTP contracts, debugger entrypoints, database model and cross-worker leasing.
- [x] Run fresh automated and live public/private review/publication checks; record model accuracy separately.
- [x] Record validation outcome, review the final diff and commit the refactor.
- [ ] Improve model severity consistency: the strict fixture run is 8/9 because public pricing PR #3 is HIGH rather than MEDIUM.

Next planned work: validate GitHub-delivered webhooks through a public HTTPS callback, then optionally demonstrate the flow on a temporary VPS with Compose and external inference, followed by teardown. No cloud resources are provisioned by this refactor.

GitLab webhook automation, inline review threads and Kubernetes deployment remain separate work. The current interface publishes one summary comment per change.
