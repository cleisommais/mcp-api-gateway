# Open questions and recommendations

[Index](../README.md) · [Phases](../PLAN.md) · [Decisions](decisions.md).

This is a **POC for learning and demonstrating the flow**. These questions guide upcoming exercises; they are not a questionnaire the user must answer before starting. **My recommendation** provides a proposed technical path, adjustable based on results.

Existing IDs are preserved. Updating Ollama when necessary is a confirmed direction; the exact version and model are technical choices to resolve through the exercise. This document does not perform infrastructure changes.

**F1 update:** Q01–Q03 are resolved for the first exercise: Java 21, Maven, official MCP SDK 2.1.0, stdio, and negotiated protocol 2025-11-25. Implementation and tests are in the [F1 guide](f1-mcp-stdio.md). The rows preserve the recommendations' reasoning, not unresolved blockers for the completed phase.

**F2 update:** Q04–Q08 and Q14 are resolved for the public manual exercise: a narrow read-only GitHub MCP server, Ollama 0.40.16, `qwen3.5:9b`, 8K context, observed 100% GPU, isolated Compose infrastructure, and four real PRs. Q16 now applies only to future maintenance of this project's instance. See [results and limits](f2-local-pr-review.md). These are technical choices made within the authorized POC scope.

| ID | Question | POC impact | My recommendation | When to address |
| --- | --- | --- | --- | --- |
| Q01 | Which JDK, build tool, and MCP SDK? | A small Java example that compiles | Start with Java 21, Maven, and the official Java SDK, pinning a compatible stable combination in the first build. Introduce Spring Boot/AI when they help the exercise | Start of F1 |
| Q02 | Which transport first? | Understand messages before service networking | Start with `stdio` and an educational server; use Streamable HTTP between agent and gateway in F3 | F1 and F3 |
| Q03 | Which protocol revision? | Client and server need a common profile | Use a stable revision supported by chosen dependencies; leave previews for another exercise and record the working profile | F1; check when adding a server |
| Q04 | Which Git MCP Server first? | Read PR/diff/content without implementing every Git API | F2 implements only three public-read tools without a personal token; reconsider the official GitHub server with GitHub App authentication for F4 automation | F2 resolved; F4 authentication later |
| Q05 | How to confirm GPU use and effective context? | Useful model execution on existing hardware | Make a short call and observe `ollama ps`/VRAM; set `num_ctx` explicitly, starting at 4K then 8K, with one inference at a time. Adjust to available memory | First local F2 test |
| Q06 | How to proceed with the found Ollama 0.18.0? | An old runtime may limit models/features | Leave the other project's runtime untouched; F2 installs pinned Ollama 0.40.16 into its own volume, following D20 | F2 resolved |
| Q07 | Which local model to try? | Useful review, tools, and JSON within 16 GB VRAM | Use **`qwen3.5:9b`** as the measured F2 starting point. Another model comparison belongs in F6 if useful | F2 resolved; [evidence](local-inference.md) |
| Q08 | How to access and isolate Ollama? | Independent lifecycle without disrupting other projects | Project network endpoint `http://ollama:11434`; host loopback port 11435; dedicated model volume. One inference at a time initially | F2 resolved by D20 |
| Q09 | Which events, branches, and drafts? | An understandable automation flow | Start with PR opened, new commit, reopened, and ready-for-review; skip drafts. Configure the target branch, using `Development` only as an example | F4 |
| Q10 | How to authenticate agent and gateway? | Learn identity/authorization without building an IAM platform | Start with a dedicated worker-to-gateway service credential and check resource/tool per call. Add an OAuth/OIDC exercise using an existing authorization server when studying delegation; do not require an IdP to start | F3; OAuth later |
| Q11 | Which GitHub permissions and publication? | Read the PR and publish as a bot | Install a GitHub App only on the demonstration repository, with content read and PR read/write as required by endpoints. Use one identifiable summary updated on retry; no inline comments initially | F4 |
| Q12 | Which second platform to demonstrate? | Show that the core is independent of GitHub | Prefer GitLab.com if access is simple, implementing only the needed contract. Bitbucket and self-hosted offerings remain targets without requiring every combination to finish the POC | F5; does not block GitHub demo |
| Q13 | Which performance/concurrency limits? | Avoid GPU stalls or uncontrolled spending | No mandatory SLO/p95 or customer fairness guarantee. Start with one local inference at a time and configurable jobs/calls. Measure several PRs and demonstrate a small two-repository execution | When adding concurrency; F7 |
| Q14 | How to evaluate quality? | Distinguish useful output from merely valid JSON | Start with four cases: known bug, correct code, cross-file bug, and prompt injection. Record findings, false positives, JSON, tools, time, and coverage without a mandatory statistical target | F2/F6 |
| Q15 | How to handle POC data? | Avoid publishing secrets/private code | Use fixtures/demo repository, credentials outside Git, and token-free logs. Export sanitized evidence and discard temporary data on teardown; no corporate retention or continuous backup | Throughout exercises and publication |
| Q16 | How to update without losing the environment? | Preserve this project's independent lifecycle | Pin the image, preserve its named volume, recreate only its Ollama service, and check GPU/model/JSON/tool use; leave other projects alone. No formal staging/release window | Future maintenance; [procedure](local-inference.md) |
| Q17 | Which external provider first? | Show the flow outside the desktop cheaply | First test the already-researched GPT-6 Luna on the same PRs; adopt if quality/cost suffice. Compare a second candidate only for a reason rather than integrating all providers | F6/F8; [sources and criteria](cloud-demo-and-portfolio.md) |
| Q18 | Which VPS? | Host a short demonstration | Prefer DigitalOcean 2 vCPUs/4 GB for simplicity, checking the price before creation. Use an available free resource if easier; do not block learning while seeking the perfect offer or ARM compatibility | F8 |
| Q19 | Which budget and credits? | Keep the test inexpensive | Plan up to US$10 total, aiming for US$5; limit reviews/tokens/retries. Count credits only after confirming the balance and minimum API top-up; Plus is not an API balance | Before spending |
| Q20 | What belongs in the portfolio? | Reproduce the learning | Own demonstration PRs, MIT license as a proposal, README, diagrams, examples, and results. Review files/history and hide personal data in recordings; no other projects' private details | Public-release preparation |
| Q21 | How to end the demonstration? | Stop billing and endpoint dependency | Run a short session, export results/recording, remove webhook, delete VM/extras, and check billing. A simple checklist suffices; no permanent operational process | End of F8 |

## How to proceed now

**F1 and F2 are implemented.** Run the [manual review](f2-local-pr-review.md) and follow repository retrieval, model calls, and output validation. The next implementation increment is F3, the MCP Gateway. No product questionnaire is pending.

F2 validated runtime/model support in its own environment. Later comparisons can reuse the four PRs without requiring exhaustive model benchmarking or maintenance of another project's instance.

Keep controls useful to the POC: protected credentials, per-call authorization, tool/result validation, honest coverage, spending limits, and enough persistence to demonstrate the flow. Production SLOs, high availability, SLAs, corporate auditing, and formal capacity management are not completion conditions. The [nonfunctional requirements](requirements.md) reflect this scope.
