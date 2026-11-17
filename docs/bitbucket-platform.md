# Bitbucket Cloud adapter

The `bitbucket` platform uses Bitbucket Cloud REST v2 at `https://api.bitbucket.org/2.0/`. Bitbucket Data Center and fork PRs are outside this adapter's scope. Both PR endpoints must belong to the configured workspace/repository. No PR code is cloned or executed by the review service.

## Runtime flow

An authenticated `POST /api/reviews` with `platform: bitbucket` pins a current, open, non-draft PR on the configured destination branch. The API and worker access repository data through the existing MCP gateway. The gateway routes `bitbucket.get_change_snapshot`, `bitbucket.get_file_at_commit`, `bitbucket.get_change_state` and `bitbucket.close_review` to a repository-bound `BitbucketMcpServer` subprocess. Its three downstream read tools have the same reviewed schemas as the other adapters.

`BitbucketChangeSource` resolves abbreviated hashes embedded in PR metadata through the repository commit endpoint, verifies the resolved hash matches the prefix, and records full source and destination SHAs. Diffstat enumeration and per-file diffs use immutable `source..destination` comparisons with `topic=true`; Bitbucket's revision order is the reverse of `git diff`. The source rechecks metadata after enumeration and when the engine checks currency. Context reads are limited to changed, non-deleted paths at the pinned source SHA, at most 128 KB of UTF-8 text, with binary content rejected.

Diffstat pagination is bounded to 30 pages. Repeated files/pages, changed snapshots and invalid pagination fail explicitly. A reached limit or mismatched advertised count marks enumeration incomplete. Binary, missing, oversized-service-error or truncated patches are not reported as fully analyzed; complete textual hunks must match the advertised addition/deletion counts. The review engine's existing partial-coverage and evidence rules apply unchanged.

The HTTP client is bound to one repository. Server-supplied pagination must stay on the same API origin and requested resource. Only PR diff/diffstat redirects to the corresponding same-repository API resource are accepted; source/LFS, cross-origin and write redirects are rejected. Responses are size-bounded. HTTP errors include the status without echoing response bodies or credentials. Rate limits and writes are not blindly retried.

The worker's `BitbucketReviewPublisher` uses a separate credential outside MCP. It creates or updates one owned, top-level summary identified by an application marker and the authenticated user's UUID. It enumerates comments before writing, revalidates commits/branch/state, and reconciles ambiguous responses instead of repeating a POST. Missing reconciliation leaves publication uncertain; duplicate owned summaries require manual reconciliation. If the PR moves during publication, the existing summary is marked outdated. The administrative `verify` command compares the remote raw comment with the saved report and checks its author.

## Configuration

Run the initializer for your OS as documented in [README](../README.md). It preserves existing files and creates empty `secrets/bitbucket-read-token` and `secrets/bitbucket-publish-token` placeholders. Set `BITBUCKET_EMAIL` in `.env` to the Atlassian account email associated with the tokens. Token files are ignored by Git and mounted individually: read token into MCP gateway, publish token into worker. Child MCP processes receive only their platform read-token file and account email.

For API tokens, configure these [Bitbucket scopes](https://developer.atlassian.com/cloud/bitbucket/rest/intro/#forge-app-and-api-token-scopes):

| Role | Scopes |
| --- | --- |
| MCP reads | `read:repository:bitbucket`, `read:pullrequest:bitbucket` |
| Summary publisher | The read scopes above, `write:pullrequest:bitbucket`, `read:user:bitbucket` |

The email/token Basic form and token-only Bearer form follow [Atlassian's authentication documentation](https://support.atlassian.com/bitbucket-cloud/docs/using-api-tokens/). Separate tokens should use the same account email with this configuration.

Register a repository in both `config/gateway.json` and `config/repositories.json`. The gateway process is `com.prreview.mcp.server.bitbucket.BitbucketMcpServer`, version `0.1.0`, protocol `2025-11-25`, credential mode `token`. Use `workspace/repository` slugs, not clone URLs. Bitbucket repository UUIDs are not numeric IDs: `repositories.json` uses an explicitly assigned positive local queue key (`1` for the provided private test repository). Keep each Bitbucket key unique and stable while reusing the queue. Publication markers use platform/repository/PR identity, independent of this local key.

Manual submission works on both OS families:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Invoke-Review.ps1 -Platform bitbucket -Repository cleisommais/pr-test-repo -Number 1
```

```bash
bash scripts/Invoke-Review.sh --platform bitbucket --repository cleisommais/pr-test-repo --number 1
```

Replace `1` with an existing open PR number. This normal worker workflow publishes a summary, including clean or partial reviews. The regression-comparison tool remains restricted to its frozen public GitHub fixtures; enabling Bitbucket does not authorize sending private code to an external inference provider.

## Validation

Automated tests use local REST fixtures, actual stdio MCP catalog negotiation, the actual HTTP MCP gateway, the existing review engine and deterministic model output. Publisher tests exercise create/update, author/marker ownership, pagination, stale commits and ambiguous response reconciliation. PostgreSQL tests include Bitbucket in platform-isolation checks. These checks do not publish to Bitbucket or evaluate a real model's accuracy.

The full Docker test command passed **134 Java tests, zero skipped**, including **22 Bitbucket-specific tests** and real PostgreSQL. The separate **14 Linux script/fixture checks** passed as an unprivileged user. PowerShell scripts passed syntax validation, and the updated Compose configuration validated successfully.

The private repository is populated with a baseline on `main` and five independent open PRs: array boundary, clean positive counting plus 21 marker records, price-unit conversion, an untrusted code comment with a boundary bug, and a shipping threshold. The tracked files under `fixtures/bitbucket/` define their code and fixed rubric. [Live evidence](evaluation/bitbucket/README.md) records the commits, PRs, model results and remote-comment checks.

Initialize/reuse the fixture, then run only its five live cases:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Initialize-BitbucketFixture.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Test-LiveReviews.ps1 -Platform bitbucket -Label local-original -ExpectedPromptVersion review-original-v3
```

```bash
bash scripts/Initialize-BitbucketFixture.sh
bash scripts/Test-LiveReviews.sh --platform bitbucket --label local-original --expected-prompt-version review-original-v3
```

The initializer uses a Python container on both platforms and the token in `secrets/bitbucket-publish-token`. Fixture setup additionally requires repository write and PR write scopes. It uses the source-commit API, accepts an empty 201 acknowledgment by resolving/verifying the branch, and uses a supported PR-list page size. It never merges, force-pushes or silently replaces differing existing fixture content. Rerunning it reuses the same baseline, branch commits and PR IDs. It writes `reports/bitbucket/fixture-manifest.json`; expected findings are always loaded from the tracked rubric rather than trusted from that generated manifest.

Without `-Platform bitbucket` / `--platform bitbucket`, the live script keeps its existing nine GitHub/GitLab cases. Labels preserve separate result files; `-ExpectedPromptVersion` / `--expected-prompt-version` checks the actual reported profile rather than trusting the label or intended settings. Every run checks full snapshot identity, coverage, finding count/location/severity/explanation and the remote comment's author and exact body. A nonzero rubric exit can coexist with successful retrieval/publication; inspect the per-case results rather than treating a completed job as proof of model quality.

## Explicit GPT API comparison

This opt-in run sends this private fixture's diff/context to the GPT API and updates its review summaries. Use it only when that transmission is intended. It uses a separate queue schema and API port; the normal worker remains on local inference. Keep the repository allowlist exact and retain the budget journal across attempts.

Start the normal services first. The following Docker commands work in PowerShell and Bash. Use a fresh schema for a new comparison; an existing snapshot job is deduplicated and must be explicitly reanalyzed to change its model/profile.

```text
docker compose run -d --name mcp-bitbucket-gpt-api --no-deps -p 127.0.0.1:18082:8081 -e DATABASE_SCHEMA=bitbucket_api_check api-gateway
docker compose run -d --name mcp-bitbucket-gpt-worker --no-deps -e DATABASE_SCHEMA=bitbucket_api_check -e MODEL_PROVIDER=openai -e REVIEW_PROMPT_PROFILE=original -e EXTERNAL_INFERENCE_ENABLED=true -e EXTERNAL_ALLOWED_REPOSITORIES=bitbucket:cleisommais/pr-test-repo -e EXTERNAL_MAX_COST_USD=0.10 -e EXTERNAL_BUDGET_JOURNAL=/reports/bitbucket/openai-budget.json -e OPENAI_MODEL=gpt-6-luna -e OPENAI_REASONING=medium -e REPORT_DIR=/reports/bitbucket/openai agent-worker
```

Submit/poll and verify using that isolated schema:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Test-LiveReviews.ps1 -Platform bitbucket -BaseUrl http://localhost:18082 -DatabaseSchema bitbucket_api_check -Label gpt-api -ExpectedPromptVersion review-original-v3
```

```bash
bash scripts/Test-LiveReviews.sh --platform bitbucket --base-url http://localhost:18082 --database-schema bitbucket_api_check --label gpt-api --expected-prompt-version review-original-v3
```

The USD 0.10 cap applies to conservative reservations using the registered POC pricing profile, not the account balance. Reports include provider token usage and cost estimates. API runs have no retry/fallback and keep `store=false`. Local and API runs use the same commits, rubric, 8K context and 4096-token output budget. Their reasoning settings are local `low` and API `medium`; their prompt profiles are recorded separately. The calibrated-profile trial did not improve overall GPT rubric accuracy and missed a seeded threshold defect; the retained default remains `original` for both providers.

Both providers own the same single summary marker per PR: a later run updates that comment. Each run verifies its body before the next provider runs, and separate saved reports preserve the comparison. To verify an API job later, pass its schema to the administrative CLI (`docker compose exec -T -e DATABASE_SCHEMA=bitbucket_api_check agent-worker java -cp '/app/mcp-api-gateway.jar:/app/lib/*' com.prreview.tools.QueueAdminCli verify JOB_UUID`).

When finished, remove only the two temporary comparison containers. Reports, the budget journal, queue records and the normal local services remain available:

```text
docker rm -f mcp-bitbucket-gpt-worker mcp-bitbucket-gpt-api
```

API references: [pull requests and comments](https://developer.atlassian.com/cloud/bitbucket/rest/api-group-pullrequests/), [immutable diffs and diffstats](https://developer.atlassian.com/cloud/bitbucket/rest/api-group-commits/), [source at a commit](https://developer.atlassian.com/cloud/bitbucket/rest/api-group-source/).
