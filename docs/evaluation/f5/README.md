# F5 evidence: GitLab.com as a second platform

Executed on 2026-11-14, using the project's Java 21 / MCP SDK 2.1.0 Docker image, protocol 2025-11-25, and existing local Ollama `gpt-oss:20b` with medium reasoning. No external model API was used.

## Live case

- Private project: `cleisommais/pr-test-repo`, numeric project ID `88936485`.
- Synthetic [MR !1](https://gitlab.com/cleisommais/pr-test-repo/-/merge_requests/1), `f5/shipping-boundary` → `f5/reference`; the default branch was preserved.
- Base: `879f61bc5ffd8ea53558c005942255e42cbcd578`.
- Head: `4143f7bd0bf4cd801c11e42ffac574dd91eeb462`.
- One changed file, complete enumeration and patch analysis, one accepted MEDIUM finding, no rejected findings, warnings or errors.
- Checked diagnosis: `shippingCents(5000)` returns 500 after `>= 5000` becomes `> 5000`; the documented/reference result is 0. This matches the known defect and expected severity.
- Review elapsed 29.937 s, inference 27.435 s, 707 prompt tokens and 515 output tokens as reported by the runtime. One small case does not establish broader quality or latency.
- [Summary note `4235283597`](https://gitlab.com/cleisommais/pr-test-repo/-/merge_requests/1#note_4235283597) was created and then updated by a second publication invocation. Read-back confirmed exactly one marked summary and an unchanged open, non-draft MR head.
- The publisher uses the supplied token owner's GitLab identity; it is not a GitHub App or an independently provisioned bot.

Artifacts:

- [live-review.json](live-review.json): original saved synthetic review, including coverage, validated evidence, model metadata and gateway correlation. `publicationEnabled: false` describes the manual review command; publication happened subsequently through the explicit publisher.
- [gateway-audit.jsonl](gateway-audit.jsonl): snapshot, pinned file read, current-state check and close for the live job.
- [validation.json](validation.json): machine-readable checks and limitations.
- [fixture/](fixture/): known correct Java reference; the administrative script applies the single deliberate mutation remotely. The review path does not execute MR code.

## Local contract checks

`docker build -t mcp-api-gateway:f5 .` passed: **80 tests discovered, 77 passed, zero failures/errors, three PostgreSQL integration tests skipped** because the build has no test database. No database schema or queue behavior changed in F5. The 18 added checks are:

| Suite | Count | Coverage |
| --- | --- | --- |
| `GitLabApiTest` | 10 | 101-file pagination, renames/additions/deletions/generated policy, fork source-project/head reads, capped counts, omitted/truncated patches, duplicate pages, snapshot changes, stale refs, decoded limits, permissions/rate limits, traversal and real stdio catalog |
| `GitLabPublisherTest` | 6 | Owned-note create/update, lost-response reconciliation/uncertainty, stale/draft/closed/retargeted refusal, note pagination, post-write invalidation, escaped mentions/quick actions |
| `PlatformContractTest` | 2 | Identical GitHub/GitLab changes through real gateway HTTP produce the same validated findings/coverage; identical repository names cannot cross platform/job scope |

The cross-platform fixture uses local REST responses and a deterministic model to isolate contract behavior. Both production downstream catalogs are discovered over real stdio MCP. The live case additionally exercises the production GitLab stdio subprocess and real local model.

The first live publication attempt failed before writing: GitLab rejected `order_by=id` for notes. It was corrected to documented `created_at` ordering, added to the fixture checks, rebuilt and then successfully exercised. Note-body normalization is also handled during response-loss reconciliation.

## Limits

GitLab webhook/queue automation, inline comments and diff positions, Self-Managed, Bitbucket, live fork permissions and huge live diffs are unvalidated or unsupported. Omitted patches and capped counts remain partial. Publication assumes one operator per MR; the local per-report lock/journal is not distributed uniqueness. GitHub's outstanding F4 webhook/publication checks remain pending. See the [guide](../../f5-gitlab-platform.md).
