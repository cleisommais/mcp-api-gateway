# Live iterative reviews across all platforms

Completed November 18, 2026 (America/Chicago). The acceptance run used real remote commits, real GitHub/GitLab/Bitbucket API reads, local `gpt-oss:20b` inference, and published platform comments. No mocked platform or model responses were used. The final service build compiled and packaged with `-DskipTests`; mock tests were not run as acceptance evidence.

Each target completed the same five-stage cycle:

| Stage | Expected and observed findings | Purpose |
| --- | --- | --- |
| Initial bug | One HIGH bounds finding | Publish the first actionable review |
| Unrelated commit | Same unresolved finding | Verify the full current PR diff is reviewed |
| Fix | Zero, with complete current coverage | Remove the resolved finding from the summary |
| Regression | One HIGH bounds finding | Detect a later reintroduced defect |
| Final fix | Zero, with complete current coverage | Return to a clean result at the latest head |

| Target | Accepted revisions | Stable summary | Final head |
| --- | --- | --- | --- |
| [Public GitHub PR #5](https://github.com/cleisommais/pr-test-repo/pull/5) | 5/5 | [6966031836](https://github.com/cleisommais/pr-test-repo/pull/5#issuecomment-6966031836) | `88efb999d5e1bd8bba20c85c955c768a4c448493` |
| [Private GitHub PR #5](https://github.com/cleisommais/pr-test-repo-private/pull/5) | 5/5 | [6966500323](https://github.com/cleisommais/pr-test-repo-private/pull/5#issuecomment-6966500323) | `0260bed7b6f90c4e97f7f0fa138e31599a94e2d1` |
| [GitLab MR !2](https://gitlab.com/cleisommais/pr-test-repo/-/merge_requests/2) | 5/5 | [4260331148](https://gitlab.com/cleisommais/pr-test-repo/-/merge_requests/2#note_4260331148) | `792ac761f7377b56bbd38d5b896f86f31ca9c8ac` |
| [Bitbucket PR #6](https://bitbucket.org/cleisommais/pr-test-repo/pull-requests/6) | 5/5 | [904864998](https://bitbucket.org/cleisommais/pr-test-repo/pull-requests/6#comment-904864998) | `1e8de0d4b442c3d09b5e727b8c3a9591726fe147` |

All 20 accepted revisions completed with the expected finding count, location/severity, full file inventory, current commit, publication and remote read-back. Each target retained one summary identity. Final comments identify the latest fix commit and say "No validated issues found in the current PR revision." Duplicate final requests reused the completed jobs on all four targets. GitLab and Bitbucket also checked duplicate requests after every stage. Every final head was reviewed using the final medium-reasoning audit configuration.

[Machine-readable evidence](results.json) records every accepted commit/job, findings, audit policy, comment identity and earlier failed attempts. [Initial GitHub read-backs](github.json) preserve the original first three comment bodies. Full worker reports, captured metadata and intermediate diagnoses remain in ignored `reports/lifecycle/`. Fixtures remain open and unmerged; baseline branches were not modified.

## Failures found and corrected

The original low-reasoning model missed the initial GitLab bug and lost Bitbucket's unresolved finding after the unrelated commit. The retrieved patch and full-file context contained the defect. A same-reasoning second pass still missed it. A high-reasoning audit recovered findings, but exhausted 4,096 and then 8,192 output tokens on a clean Bitbucket revision; these runs correctly published incomplete reviews and were not counted as passes.

Zero-candidate batches now receive a second correctness pass over the same pinned context. The final local audit uses medium reasoning, a 16,384-token context and an 8,192-token output budget. It detected the known live-input defect and completed the clean diagnostic, then passed the real worker/publication checks. The primary prompt/profile remains unchanged. Both calls use the selected provider and shared call/deadline/cost controls; there is no external fallback. An audit failure leaves partial coverage and cannot publish a clean result. The audit version and per-batch outcomes are saved in each report.

The publisher now reserves its clean wording for complete, current, fully enumerated coverage. Partial or outdated zero-finding reports explicitly warn against treating the result as clean. The runner was also corrected to recover a remotely committed revision after interruption, avoid a Bitbucket method/field collision, wait for in-flight reanalysis, and reject a stale captured GitHub head before submission.

These synthetic cases validate the observed cycle, not general model accuracy. Earlier accepted stages used the policy available at that point; the evidence retains those policy versions. All final heads were rechecked with the final policy. The previous paired-model campaign under `docs/evaluation/current/` predates this audit and must not be interpreted as evaluating it.

## Repeating the real checks

GitLab and Bitbucket use `scripts/test_live_lifecycle.py`. It creates an isolated branch and change request in the explicitly configured synthetic repository, commits each stage, requests a normal review, and reads the actual platform comment back before the next commit. The GitLab token needs repository commit/MR write capability; the Bitbucket setup token needs repository/PR write scopes. Tokens stay in the existing ignored secret files. The runner never merges, force-pushes or changes webhooks.

From the repository root on Windows:

```powershell
docker run --rm --mount "type=bind,source=$($PWD.Path),target=/workspace,readonly" --mount "type=bind,source=$($PWD.Path)/reports/lifecycle,target=/workspace/reports/lifecycle" --workdir /workspace python:3.12-slim python3 -u scripts/test_live_lifecycle.py --platform gitlab --resume
```

Use `--platform bitbucket` for Bitbucket. On macOS/Linux, use `$(pwd)` in each mount source. Omit `--resume` only for a new campaign after explicitly archiving its existing manifest. `--resume` continues missing stages and performs fresh final remote read-back/duplicate checks when all five are already complete.

For GitHub, commit through the connector, wait until the remote PR metadata reaches that exact commit, save the snapshot, then run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Test-PrRevision.ps1 -Snapshot reports/lifecycle/private-fixed-again-snapshot.json -ExpectedHead 0260bed7b6f90c4e97f7f0fa138e31599a94e2d1 -ExpectedFindings 0 -Label private-fixed-again
```

`Test-PrRevision.ps1` checks the committed snapshot, coverage and finding count, then uses the worker's `QueueAdminCli verify` for an exact remote-body and author comparison. Run `scripts/summarize_lifecycle.py` in the Python container to validate the saved evidence and regenerate `results.json`; it refuses missing, partial, incorrect or unverified results.

## Trigger scope

The first public GitHub review used a signed local event replay with real GitHub metadata. Subsequent reviews used the authenticated review API. Webhook settings were left unchanged at the user's request, and the temporary tunnel was stopped. This run verifies live platform integration and repeated review/publication; it does not claim automatic webhook delivery or merge/approval automation.
