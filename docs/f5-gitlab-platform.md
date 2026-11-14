# F5: GitLab.com through the same review contract

[Index](../README.md) · [Plan](../PLAN.md) · [Architecture](architecture.md).

F5 adds a second platform, GitLab.com, using the existing Java review engine, evidence validation, local model and governed MCP transport. GitHub remains supported. The selected offering is **GitLab.com REST v4**, not GitLab Self-Managed or Bitbucket.

## What MCP standardizes

Both adapters expose the independently pinned `get_change_snapshot`, `get_file_at_commit` and `get_change_state` tool schemas/descriptions. The gateway authenticates the worker, validates each job/resource/SHA, and routes to an approved subprocess. Discovery and structured results use Java MCP SDK 2.1.0 and protocol 2025-11-25.

MCP does not translate GitHub PRs into GitLab MRs. `GitLabApi` performs that translation into `ReviewData.Snapshot`, `FileChange` and `Source`. `ReviewEngine` retains its prompts, batching, finding/severity schema, added-line/evidence checks and coverage rules. The report now also includes the target `baseBranch`, which the manual publisher checks.

Upstream tools are namespaced `github.*` and `gitlab.*`, including `close_review`. The gateway exposes four tools per configured platform. Routing uses **platform plus repository**, so `cleisommais/pr-test-repo` on GitHub and GitLab cannot share a job accidentally. Registration pins the executable, version, protocol and credential mode; callers cannot supply a GitLab host or server executable.

## Adapter semantics and limits

| Concern | GitLab translation |
| --- | --- |
| Identity | URL-encode the full namespace/project, including subgroups; use the project-local MR **IID**, not the global MR ID |
| Snapshot | Require ready `diff_refs` with full base/head/start SHAs and a matching top-level head SHA; reject changes during enumeration |
| Base | `baseSha` is the merge-base from `diff_refs.base_sha`; `start_sha` is additionally checked for current-state changes |
| Files | Page `/diffs` at 100 files per request; reject duplicate paths; at most 30 requests, explicitly partial if the end is not proven |
| Count | A numeric `changes_count` must equal the enumerated total; missing or capped counts such as `1000+` cannot prove completeness |
| Patch | `collapsed`, `too_large`, blank/binary or malformed/truncated hunk lengths remain pending; generated files follow the existing exclusion policy |
| Renames/deletions | Use the new path for a rename and old path for a deletion; status maps to the existing contract; inline old/new positions are not inferred |
| Content | Read the source project identified by the authorized MR, including a fork, at the pinned head SHA; verify returned commit and path; enforce 128 KB and reject NUL-containing content |
| Current state | Check base/head/start refs, source project, MR identity, open/closed state, draft and target branch |
| Errors | Permission, missing-resource, rate-limit and server errors stop the operation; no automatic API retry or redirects; error bodies and tokens stay out of messages |
| Publication | Top-level MR note, controlled outside MCP, using a separate write credential; no inline discussion/position API |

Patch totals are counted from supplied hunks because this endpoint does not provide GitHub-style per-file addition/deletion totals. Hunk-length validation and platform flags detect known incompleteness; they cannot prove that GitLab supplied every possible hunk. No raw-diff fallback or collapsed-file expansion is implemented. Mode-only and rename-only entries without text patches remain pending rather than being claimed as analyzed.

The [official GitLab MCP server](https://docs.gitlab.com/user/model_context_protocol/mcp_server/) and its [tool catalog](https://docs.gitlab.com/user/model_context_protocol/mcp_server_tools/) were considered. This increment uses a narrow project-owned adapter to preserve the reviewed three-tool contract and restrict credentials to the needed reads. It does not claim that the official server lacks these capabilities or that its OAuth/transport/catalog has been validated here. Broader server integration is a separate exercise.

## Credentials and execution

Keep credentials in ignored `.env`:

```dotenv
GITLAB_READ_TOKEN=your-read-api-token
GITLAB_PUBLISH_TOKEN=your-api-token
```

Use `read_api` for private retrieval. Only the gateway receives that value, and it forwards it only to a registered GitLab subprocess with `credentialMode: token`. The review/model container has neither GitLab token. Public registrations can use `anonymous`.

The explicit `gitlab-publish` container receives only the publication token and saved reports. GitLab note writes require an `api` credential with sufficient project membership. Prefer a project-bound bot identity where available; a personal token publishes as its owner. The successful live example uses the token owner's identity, not a GitHub App. Choose short expirations and rotate tokens separately. GitLab's broader `api` scope is not equivalent to a note-only permission; the Java transport restricts runtime writes to MR notes.

The checked-in registration includes the user's selected private GitLab test project. To review it:

```powershell
docker compose --profile review build gateway review
docker compose --profile review up -d postgres ollama gateway
docker compose --profile review run --rm review gitlab cleisommais/pr-test-repo 1
```

Reports are saved as `reports/gitlab-<namespace>-<project>-pr<IID>-<head-prefix>.json`. The `gitlab-` prefix keeps GitHub and GitLab artifacts separate. `complete` describes supplied coverage and accepted evidence, not proof of correct reasoning.

Publish a saved report explicitly:

```powershell
docker compose --profile gitlab-publish run --rm gitlab-publish /reports/gitlab-cleisommais-pr-test-repo-pr1-4143f7bd0bf4.json
```

The publisher requires matching platform/repository/IID, a current complete/partial report, an open non-draft MR and matching SHAs/target branch. It enumerates notes using GitLab-supported `created_at` ordering, selects a marker owned by the authenticated numeric user ID, and updates that note on later runs. Text is escaped; embedded newlines cannot introduce standalone GitLab quick actions or mentions. GitLab body normalization is accounted for when reconciling a lost response.

Before writing, the CLI records `<report>.publication.json`. It keeps a local lock for that report. On restart, an attempted publication with no matching owned note becomes uncertain instead of issuing another POST. Keep the report, journal and lock together. Inspect uncertain publication manually; deleting the journal discards the retry guard. One operator/publisher per MR is the POC assumption: different report files or different hosts are not protected by this local lock. Notes have no atomic compare-and-swap with the MR head; the text records the SHA, and a post-write check marks a changed MR's result outdated.

## Reproduce the live fixture

The private test project initially contained only `README.md`. The setup script creates `f5/reference` from its default branch with the two known Java files under [fixture/](evaluation/f5/fixture/), then creates `f5/shipping-boundary` with one deliberate mutation: `>= 5000` becomes `> 5000`. It opens an MR against the reference branch. It does not commit to the default branch or merge anything.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Initialize-GitLabFixture.ps1
```

This administrative setup uses `GITLAB_PUBLISH_TOKEN` for branch/commit/MR creation, following the user's explicit authorization for the test repository; it is not a runtime MCP capability. Existing fixture branch names are reused without overwriting them. If they have been edited, inspect them before reuse. API timeouts during setup require checking remote state before rerunning.

## Manual review versus the agent's live review

The comparison uses private synthetic [MR !1](https://gitlab.com/cleisommais/pr-test-repo/-/merge_requests/1), at head `4143f7bd0bf4cd801c11e42ffac574dd91eeb462`. Manual inspection of the known reference and the one-line mutation establishes the expected result before assessing the saved agent report. This is one deliberately seeded example, not an independent human benchmark.

| Check | Manual assessment of the fixture | Agent result |
| --- | --- | --- |
| Defect and location | Changing `>= 5000` to `> 5000` in `ShippingCalculator.java`, line 8, excludes the exact free-shipping threshold | Identified the same changed condition at line 8, with matching patch evidence |
| Concrete behavior | `shippingCents(4999)` remains 500; `shippingCents(5000)` incorrectly becomes 500 instead of 0; `shippingCents(5001)` remains 0 | Explicitly explained that an order of exactly 5,000 cents incorrectly incurs a 500-cent fee and gave the same failing input/output |
| Severity | MEDIUM: an incorrect shipping charge at a boundary, without a demonstrated crash or broader outage | MEDIUM |
| Findings | One known seeded correctness defect in the single changed file | One accepted finding; no additional findings, rejected findings, warnings or errors |
| Coverage and publication | Check the changed file and preserve the analyzed SHA in the summary | One file analyzed, complete enumeration, current report; the explicit publisher created a summary and its repeat invocation updated the same note |

The agent matched the expected diagnosis, example and severity. Against this fixture's known expectation there was no observed false positive or missed seeded defect. That does not establish recall for unknown defects or general review accuracy. `complete` indicates supplied coverage and mechanical validation; the report still requires human review.

The run used local `gpt-oss:20b` with medium reasoning: 29.937 seconds total, 27.435 seconds inference, 707 prompt tokens and 515 output tokens reported by Ollama. Manual inspection was not timed, so no human-versus-agent speed claim is made. The [saved synthetic report](evaluation/f5/live-review.json) contains the original explanation and evidence. The [published summary](https://gitlab.com/cleisommais/pr-test-repo/-/merge_requests/1#note_4235283597) was read back as exactly one marked note after two publication invocations; manual review itself does not publish anything.

## Validation and remaining capabilities

The [evaluation record](evaluation/f5/README.md) contains the live result and sanitized artifacts. Local tests check the same snapshot/file/current-state and finding contract on GitHub and GitLab, including real gateway HTTP transport and both real downstream MCP catalogs. GitLab-specific tests cover 101 files, omitted/truncated diffs, renames, fork reads, stale refs, state changes, permissions/rate limits, and summary recovery.

**Implemented:** manual GitLab review and controlled summary publication. **Not implemented:** GitLab webhook admission, durable GitLab queue jobs, automatic scheduling, token renewal, inline comments, alternate GitLab hosts, or Bitbucket. F4's webhook/worker/database flow remains GitHub-specific. The one live MR does not validate real GitLab fork permissions, huge diffs or a general model-quality claim; those edge cases are local contract tests.

Sources checked on 2026-11-14: [Merge requests API](https://docs.gitlab.com/api/merge_requests/), [repository files](https://docs.gitlab.com/api/repository_files/), [MR notes and body normalization](https://docs.gitlab.com/api/notes/), [commit creation](https://docs.gitlab.com/api/commits/#create-a-commit).
