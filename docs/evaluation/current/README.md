# Current all-repository comparison

Fresh campaign started 2026-11-16T20:04:55-06:00 (America/Chicago).

One new analysis per provider for each of the 14 fixture PRs/MRs. Both providers used `review-original-v3`, the same pinned base/head commits, an 8192-token context and a 4096-token output budget. Local inference used `gpt-oss:20b` with low reasoning; the GPT API used `gpt-6-luna` with medium reasoning. No prompt tuning, result selection, or model-quality rerun is included in this dataset.

| Target | Cases | Local strict rubric | GPT strict rubric | Local detection/clean checks | GPT detection/clean checks |
| --- | --- | --- | --- | --- | --- |
| github `cleisommais/pr-test-repo` | 4 | 1/4 | 3/4 | 2/4 | 4/4 |
| github `cleisommais/pr-test-repo-private` | 4 | 1/4 | 3/4 | 1/4 | 4/4 |
| bitbucket `cleisommais/pr-test-repo` | 5 | 4/5 | 4/5 | 4/5 | 5/5 |
| gitlab `cleisommais/pr-test-repo` | 1 | 1/1 | 1/1 | 1/1 | 1/1 |
| **Total** | **14** | **7/14** | **11/14** | **8/14** | **14/14** |

Publication and exact remote-comment read-back: local **14/14**, GPT **14/14**.

Detection/clean checks require the expected finding count, file, added-line location, explanation patterns and complete current coverage. The strict rubric additionally requires the expected severity and verified model/prompt. Job completion and publication are counted separately from model correctness. File inventory checks use the source-reported changed-file count; the public and private GitHub clean fixtures have different inventories.

## Per-PR results

| Target / PR | Expected findings / severity | Local findings / severity | GPT findings / severity | Local strict | GPT strict |
| --- | --- | --- | --- | --- | --- |
| github `cleisommais/pr-test-repo` #1 | 1 / HIGH | 0 | 1 / HIGH | Fail | Pass |
| github `cleisommais/pr-test-repo` #2 | 0 | 0 | 0 | Pass | Pass |
| github `cleisommais/pr-test-repo` #3 | 1 / MEDIUM | 1 / HIGH | 1 / HIGH | Fail | Fail |
| github `cleisommais/pr-test-repo` #4 | 1 / HIGH | 0 | 1 / HIGH | Fail | Pass |
| github `cleisommais/pr-test-repo-private` #1 | 1 / HIGH | 0 | 1 / HIGH | Fail | Pass |
| github `cleisommais/pr-test-repo-private` #2 | 0 | 0 | 0 | Pass | Pass |
| github `cleisommais/pr-test-repo-private` #3 | 1 / MEDIUM | 1 / HIGH | 1 / HIGH | Fail | Fail |
| github `cleisommais/pr-test-repo-private` #4 | 1 / HIGH | 0 | 1 / HIGH | Fail | Pass |
| gitlab `cleisommais/pr-test-repo` #1 | 1 / MEDIUM | 1 / MEDIUM | 1 / MEDIUM | Pass | Pass |
| bitbucket `cleisommais/pr-test-repo` #1 | 1 / HIGH | 1 / HIGH | 1 / HIGH | Pass | Pass |
| bitbucket `cleisommais/pr-test-repo` #2 | 0 | 0 | 0 | Pass | Pass |
| bitbucket `cleisommais/pr-test-repo` #3 | 1 / MEDIUM | 1 / MEDIUM | 1 / HIGH | Fail | Fail |
| bitbucket `cleisommais/pr-test-repo` #4 | 1 / HIGH | 1 / HIGH | 1 / HIGH | Pass | Pass |
| bitbucket `cleisommais/pr-test-repo` #5 | 1 / MEDIUM | 1 / MEDIUM | 1 / MEDIUM | Pass | Pass |

## Collection and reproducibility

Each provider used a separate newly created PostgreSQL schema. Every job was checked for zero attempts and no saved report before inference. The two cohorts' commit pairs were compared before enabling GPT inference. The common runtime image and prompt fingerprint are recorded in the JSON evidence. Full findings, errors, usage, job IDs and verified comment URLs are included for every case.

Previous active reports and the superseded result tables were backed up under ignored `reports/archive/`. This page and `comparison.json` contain only the current paired campaign. Credentials, fixture definitions and cumulative API budget journals were preserved. The API allowlist was limited to the four configured repositories. Normal local services remain configured with external inference disabled. Later provider runs update the same owned summary comments; each provider's read-back was completed before the next provider began publication.

This campaign made 20 API calls with 18421 input and 2755 output tokens. Recorded cost estimate: USD 0.0034395250; new conservative reservations: USD 0.0542133750. The cumulative journal retains previous reservations and uses a USD 0.25 cap for this collection. Cost figures use the registered POC rate profile and are not an invoice.

Raw current results are in `reports/current/`. Regrade or verify saved jobs with `Test-LiveReviews` using `-Platform all -Resume` (PowerShell) or `--platform all --resume` (Bash), the matching label, API base URL, queue schema, expected model and expected prompt version. `-SubmitOnly -RequireFresh` / `--submit-only --require-fresh` permits checking commit pins before starting a provider worker. To collect another independent cohort, create new queue schemas rather than resuming these jobs.

[Machine-readable current evidence](comparison.json). These synthetic fixtures are a small sample and do not establish general model accuracy.

Fresh automated checks: 134 Java tests with real PostgreSQL, 0 skipped; 18 script/fixture/comparison tests passed.
