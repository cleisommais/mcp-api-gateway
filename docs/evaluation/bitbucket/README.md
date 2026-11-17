# Bitbucket live validation

Validation date: 2026-11-16 (America/Chicago). The private test repository was populated with a correct baseline and five independent open PRs. All reviews used actual Bitbucket reads through MCP, PostgreSQL jobs, provider inference, controlled summary publication and exact remote-comment read-back.

| PR | Scenario | Expected |
| --- | --- | --- |
| [#1](https://bitbucket.org/cleisommais/pr-test-repo/pull-requests/1) | array-boundary | HIGH at `src/demo/ArrayTotals.java:8` |
| [#2](https://bitbucket.org/cleisommais/pr-test-repo/pull-requests/2) | clean-positive-count | Zero findings |
| [#3](https://bitbucket.org/cleisommais/pr-test-repo/pull-requests/3) | price-units | MEDIUM at `src/demo/Checkout.java:10` |
| [#4](https://bitbucket.org/cleisommais/pr-test-repo/pull-requests/4) | untrusted-comment | HIGH at `src/demo/ArrayTotals.java:9` |
| [#5](https://bitbucket.org/cleisommais/pr-test-repo/pull-requests/5) | shipping-threshold | MEDIUM at `src/demo/ShippingCalculator.java:8` |

| Run | Model | Prompt | Strict rubric | Published and verified |
| --- | --- | --- | --- | --- |
| local-calibrated-baseline | gpt-oss:20b | review-calibrated-v2 | 3/5 | 5/5 |
| local-calibrated-repeat | gpt-oss:20b | review-calibrated-v2 | 5/5 | 5/5 |
| gpt-original | gpt-6-luna | review-original-v3 | 4/5 | 5/5 |
| gpt-calibrated | gpt-6-luna | review-calibrated-v2 | 4/5 | 5/5 |
| local-calibrated-restore | gpt-oss:20b | review-calibrated-v2 | 5/5 | 5/5 |
| local-original-confirmed | gpt-oss:20b | review-original-v3 | 2/5 | 5/5 |

The explicitly verified original-prompt local run scored 2/5: it missed the array-boundary and shipping-threshold defects and rated the price-unit defect HIGH instead of MEDIUM. GPT with the original prompt found all four seeded defects and left the clean PR with zero findings, but also rated the price-unit defect HIGH instead of the fixed MEDIUM rubric (4/5). Its API calls and publication succeeded; this severity mismatch remains a model-quality limitation. The calibrated GPT trial corrected that severity but missed the shipping-threshold defect (4/5). These different prompt trials do not establish general accuracy, and the local calibrated results also varied across runs.

The first local trial exposed a pre-existing configuration inconsistency: `ReviewService` defaulted to the rejected calibrated profile while the engine and documentation retained the original prompt. The worker now defaults to `original`, and Compose forwards the explicit profile setting. A final audit also found an existing `.env` override still selecting calibration: the runs initially labeled `local-original` and `local-restored` were actually calibrated and are relabeled accurately above. Their 3/5 then 5/5 results show variability within that profile; the improvement cannot be attributed to a prompt change. The final `local-original-confirmed` run followed an explicit environment correction and active-worker profile check. A prompt-version assertion is now available in both live test scripts to catch mismatched configurations. Each provider’s report and verification result is preserved separately.

Live API compatibility fixes covered Bitbucket’s abbreviated PR commit hashes and the fixture endpoint’s empty 201 commit acknowledgment and supported PR-list page size. Regression checks cover the observed behaviors. The baseline and all five prepared branches compiled, and deterministic Java probes confirmed the intended sum, price-unit and shipping behavior. Fixture setup was rerun without adding commits or PRs. No fixture PR was merged.

Both providers used the same fixed commits and rubric, an 8192-token context and 4096-token output limit. Local inference used `gpt-oss:20b`, low reasoning, on the GPU. GPT used `gpt-6-luna`, medium reasoning. The original and calibrated profiles are reported independently. Each fixture kept one owned summary comment: subsequent runs updated the same comment IDs.

API campaign: 14 calls; 13452 input tokens and 2167 output tokens. Recorded cost estimate: USD 0.0025653500; conservative reservations: USD 0.0384043750 against a USD 0.10 cap. This uses the registered POC rate profile and is not an invoice. Both API trials share one persistent journal; no budget reset or automatic fallback was used. External inference was enabled only for this Bitbucket repository in an isolated queue. Temporary comparison services were removed and the normal worker remains local.

Validation also passed 134 Java tests with real PostgreSQL (zero skipped) and 14 Linux script/fixture checks. The model fixtures are small synthetic examples; these results do not guarantee correct findings or severity on other PRs.

See [live-validation.json](live-validation.json) for commit SHAs, per-run findings, provider usage, job/comment identities and read-back checks. Raw reports and logs remain in ignored `reports/bitbucket/`. [Reproduction commands](../../bitbucket-platform.md) cover Windows and macOS/Linux.
