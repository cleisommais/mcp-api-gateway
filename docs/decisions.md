# Decisions and proposals

[Index](../README.md) · [Plan](../PLAN.md). Record dated 2026-11-12, derived from the original `prompt.md`, local inspection, and user clarifications about the POC, temporary cloud deployment, and portfolio.

## Classification

- **C — Confirmed by the user:** an explicitly stated direction. It does not automatically approve a suggested technology or value used to implement it.
- **P — Initial technical proposal:** an adjustable recommendation, not yet approved.
- **Q — Open question:** requires a choice or evidence; see [open-questions.md](open-questions.md).
- **F — Future enhancement:** outside the proposed initial delivery, with no committed date.

Environment observations are evidence, not decisions. RF/RNF identifiers use these classifications for requirements; proposed implementation strategies are identified in the text. Implemented F1 choices are recorded below without turning every proposal into a user decision.

## Technical choices implemented in F1

The user authorized starting the phase and Git workflow. The exercise uses **Java 21, Maven, official MCP SDK 2.1.0, and stdio**, with protocol **2025-11-25** negotiated in tests. Client and server are separate processes inside one container. The implemented educational tool is `get_review_fixture`.

These choices resolve Q01–Q03 and implement P01 only within F1. They do not yet include Spring, inference, or the gateway. The [phase guide](f1-mcp-stdio.md) records execution, sources, and seven tests. D10's historical Markdown-only restriction applied to the planning delivery; later authorization permits this implementation and the documentation commit.

## Confirmed directions

| ID | Status | Direction |
| --- | --- | --- |
| D01 | C | Use Java and develop in explained increments, planning before implementation |
| D02 | C | Keep MCP Gateway in the design and learning goals, introducing it gradually |
| D03 | C | Run locally in Docker containers |
| D04 | C | Design for GitHub, GitLab, and Bitbucket without tying the core to GitHub APIs |
| D05 | C | Allow multiple providers/models, including local models |
| D06 | C | Support large PRs without a fixed 20-file limit and disclose incomplete coverage |
| D07 | C | Configurable concurrency across repositories without a fixed two-job global limit |
| D08 | C | Reuse existing MCP Servers when suitable |
| D09 | C | Reuse existing Ollama and NVIDIA GPU for initial inference, subject to quality/capacity validation |
| D10 | C | Original planning task: Markdown only; other projects read-only; no infrastructure, code, commit, or push in that delivery |
| D11 | C | Hands-on learning and interview portfolio, without commercial operation or permanent availability |
| D12 | C | Existing Ollama as development/validation default; evaluate installed models before downloads; no automatic updates |
| D13 | C | Demonstrate on a temporary VPS using an external model API independently of the personal computer; initially no cloud GPU or Ollama migration to the VPS |
| D14 | C | Select one initial external provider by quality and total cost per review; configurable endpoint/model/credentials; no implicit automatic external fallback |
| D15 | C | Prepare a public repository with documentation, reproducible examples, evaluations, and a short recording, without credentials/private information |
| D16 | C | Export evidence and delete the VM/billable resources after demonstration; no permanent endpoint dependency |
| D17 | C | Size choices and validation for a learning POC; do not require questions, SLOs, or production operational processes to proceed |
| D18 | C | Update Ollama when needed and consider a newer/GPU-suitable model; existing runtime/models are not permanent constraints |
| D19 | C | English is the project language for documentation, diagrams, code comments, tool descriptions, application messages, and future commit messages |

Hardware, version, and models were **confirmed by the user** in addition to earlier inspection evidence. The canonical record is [local-inference.md](local-inference.md); custom-model characteristics are not inferred from its name or automatically included in that confirmation.

## Proposals and tradeoffs

| ID | Status | Proposal | Alternative and consequence |
| --- | --- | --- | --- |
| P01 | P | Java 21, Maven, Spring Boot, Spring Security, and Spring AI, initially imperative | Framework-free MCP SDK/HTTP client reduces abstraction in F1 but needs more manual integration later |
| P02 | P | One application for ingress, worker, core, and publication; gateway in a separate process | Making every role a microservice increases operations without demonstrated need |
| P03 | P | Dedicated PostgreSQL for jobs, publication, and auditing; database-backed queue | An in-memory queue does not support recovery; a broker can be reconsidered after measurement |
| P04 | P | Docker Compose for application/database in both profiles; reference existing local inference | Duplicate Ollama/models consume disk/VRAM and complicate ownership/maintenance; cloud uses an external API |
| P05 | P | GitHub first and GitHub App for automation | PAT/machine-user options need permission, lifecycle, and personal-dependency evaluation |
| P06 | P | Model receives read tools; application publishes after validation | Model-controlled writes increase prompt injection impact and complicate idempotency |
| P07 | P | Static MVP without shell, PR execution, merge, or automatic approval | Future execution requires sandboxing and a different risk design |
| P08 | P | Short comparison of existing `qwen3:14b` and `qwen3.5:9b` after checking runtime support | `gpt-oss:20b` is optional; do not require exhaustive installed-model testing or infer quality from novelty |
| P09 | P | Initially access local Ollama through `http://host.docker.internal:11434` | Shared networking reduces host dependency but exposes the application to a network with other services; see inventory |
| P10 | P | External fallback disabled by default, enabled explicitly by repository/policy | Automatic continuity can disclose code and incur unauthorized costs |
| P11 | P | Separate tool rounds from the final JSON Schema response | Tools and schema in the same call need model/version-specific validation |
| P12 | P | Start with one local inference call at a time and a bounded application queue | Initial shared-GPU experiment setting, not a global review limit |
| P13 | P | One Docker Compose VPS, initially 2 vCPUs/4 GB RAM | Measure JVMs, database, and MCP Servers; initially no managed database, paid load balancer, or Kubernetes |
| P14 | P | Prefer low-effort free resources/credits, then usage-proportional VPS billing | Oracle Always Free, DigitalOcean, and Hetzner are candidates; no purchase or eligibility confirmed |
| P15 | P | Initial US$5–10 total demonstration budget for infrastructure and inference | Target to validate; reserve margin for extras, taxes/exchange rates, and metering delay; credits are not guaranteed |
| P16 | P | Retrieve commit diffs/files through platform MCP tools, initially without requiring a full clone | Clone/fetch can be a tool implementation later; it does not require PR code execution or a model shell |

API candidates, dated financial references, comparison, and teardown are centralized in [cloud-demo-and-portfolio.md](cloud-demo-and-portfolio.md). Provider, VPS SKU/region, and final limits remain **Q**.

## Consulted compatibility

The sources below were consulted on 2026-11-12. They guide future choices; F1 pins its own dependencies above, while the broader application stack remains proposed.

- Consulted **Spring AI 2.0.2** documentation supports **Spring Boot 4.0.x and 4.1.x**. Boot 4.1.2 documents Java 17–27 and Maven 3.6.3+. Java 21/Maven is therefore a compatible candidate under those conditions. Do not mix arbitrary Boot/Spring AI lines. Use compatible BOMs, including Boot-managed Spring Security, and confirm the combination when introducing Spring in a later build. Sources: [Spring AI](https://docs.spring.io/spring-ai/reference/getting-started.html), [Boot](https://docs.spring.io/spring-boot/system-requirements.html).
- The [official Java SDK](https://java.sdk.modelcontextprotocol.io/latest/) provides synchronous/asynchronous clients and servers. Its documentation distinguishes standard support from server preview for MCP **2026-07-28**. Full client/gateway/downstream support for that revision is unproven. Choose a testable common profile rather than adopting `latest` implicitly. Spring transports come from Spring AI 2.0+; verify the concrete dependency matrix.
- [Spring AI Ollama](https://docs.spring.io/spring-ai/reference/api/chat/ollama-chat.html) documents endpoint, model, context, tools, and schema output. Native integration with automatic download disabled is proposed. Features still need testing against the found **0.18.0** server or an explicitly updated runtime. Current documentation does not prove backward compatibility.
- The [official GitHub server](https://github.com/github/github-mcp-server) is a candidate, not the final choice. Its [GitHub App documentation](https://github.com/github/github-mcp-server/blob/main/docs/github-app-auth.md) describes installation authentication with token renewal in local stdio mode and states this mode does not apply to the HTTP command. Verify the distributed version/mode; this does not prove equivalent remote-server compatibility.
- The [official Ollama release](https://github.com/ollama/ollama/releases/tag/v0.40.16) resolved by `releases/latest` is newer than the found 0.18.0. This supports evaluating an update, not blindly adopting that target. Evaluation and rollback are in [local-inference.md](local-inference.md).

## Future enhancements

| ID | Status | Enhancement | Condition |
| --- | --- | --- | --- |
| E01 | F | Inline comments | Map diff/commit/position and validate each offering's API |
| E02 | F | Interactive/local delegated OAuth | An operation actually needs to act on the user's behalf |
| E03 | F | Integrated enterprise catalog | Approved registry and defined governance |
| E04 | F | Distributed scaling and additional infrastructure | Measurements demonstrate need |
| E05 | F | Additional models, tuning, and per-task routing | Quality/cost/context comparison justifies them |

## Unapproved targets

Webhook ACK p95 of 2 s, review p95 of 3 min, total timeout of 5 min, ten concurrent reviews, two per repository, and one active review per PR/SHA are **earlier illustrative proposals**, not commitments. Do not apply them as approved defaults or proven capacity. Any targets must identify hardware, quotas, size, model, and budget. Measurement is described in [requirements.md](requirements.md).

The latest direction does not require SLOs, latency percentiles, corporate retention policies, or fairness guarantees to complete this POC. Record values from a few examples and their limitations. Basic credential, authorization, output, coverage, and cost controls remain part of the flow and learning.

Initial VPS size and the US$5–10 target are also **P**, not performance/cost guarantees or permission to provision during planning.
