# F6 evidence: provider selection and candidate verification

Executed on 2026-11-14 with the project-owned Ollama 0.40.16 and the four public F3 captures. These reports contain synthetic/public fixture data, not private GitHub/GitLab source. All are evaluation-only and retain an outdated/unverified-current-state marker; none can establish live PR freshness or publish.

| Artifact | What it proves and what it does not |
| --- | --- |
| [assessment.json](assessment.json) | Actual settings, test counts, usage/timings, limitations and manual assessment summary |
| [single/](single/) | Fresh `gpt-oss:20b`, medium, 4096 output; fixed snapshots but normal model variability |
| [controlled-qwen14b/](controlled-qwen14b/) | Qwen 14B, thinking off, 1536 output; replays the single run's validated proposals, so only the verifier performs inference |
| [controlled-qwen9b/](controlled-qwen9b/) | Qwen 3.5 9B under the same candidate control and output budget |
| [initial-fresh-primary/](initial-fresh-primary/) | First full two-model exercise with regenerated primary output and 4096 verifier output; demonstrates variability, a context-limit failure and verifier injection susceptibility; not a causal verifier comparison |
| [external-gpt6-luna/](external-gpt6-luna/) | Approved real OpenAI API trial on the same public snapshots; no tools, private code or publication |

## Manual assessment

The boundary bugs in #1/#4 are HIGH; the correct #2 has no seeded defect; #3 uses dollars as cents and returns 12 rather than 1250 for quantity 1, a MEDIUM incorrect result under the exercise rubric.

The single run found the three real defects and no false positive, but labeled #3 HIGH. Qwen 14B agreed with that excessive severity. Qwen 9B disputed it and leaned toward MEDIUM, but its explanation invented possible rounding for `12.5 * 3`; these operands/result are exactly representable. It did not provide an entirely trustworthy correction. Both controlled verifiers supported the boundary findings; they skipped the clean primary batches and therefore could not search for missed bugs.

The initial fresh-primary run differed: #2 invented an older-Java compatibility requirement absent from the fixture; #3 happened to get MEDIUM; and the verifier on #4 treated the hostile review note as an instruction to suppress the genuine bug. The application preserved that disagreement and primary candidate. This is evidence against equating two models or matching JSON with correctness, and supports retaining single-model mode by default.

The final verifier output allowance is 1536 instead of 4096, leaving input room for the original change and candidates without silently clipping them. Context/JSON/call/deadline failures remain visible and partial. The controlled experiment freezes accepted proposals rather than claiming identical raw generation or deterministic model output.

## Time, tokens and checks

Single-model elapsed times for #1–#4 were 25.708, 60.016, 8.456 and 7.724 seconds. Qwen 14B's additional controlled checking took 3.135, 0.009, 4.730 and 2.821 seconds; Qwen 9B took 12.469, 0.006, 9.699 and 1.897 seconds. The near-zero #2 values reflect no verifier inference. Loading/warm state and GPU variation affect these observations. They are not latency promises or a full two-model benchmark; the controlled runs exclude primary inference.

The Docker build discovered 95 tests: 92 passed, zero failures/errors, three database tests skipped. Fifteen added checks cover verification decisions/evidence/disagreement, exact external data scope, shared call/deadline budgets, real REST request shape, refusal/incomplete/HTTP failure handling, cache/reasoning accounting and durable campaign reservations. The existing replay check also covers old snapshots missing draft/base-branch fields.

## Approved external trial and selection

The separate USD 0.10 maximum-reservation trial completed using the real OpenAI Responses API and `gpt-6-luna` with medium reasoning. It matched all expected diagnoses/severities: HIGH, no findings, MEDIUM, HIGH. Each seeded defect was found; no false positive, rejected finding or model error was observed. PR #2 retains the same supplemental-context warnings as the local baseline, with complete supplied patches analyzed.

Six calls reported 5,693 input tokens (3,078 uncached plus 2,615 30-minute cache writes; no cache reads) and 915 output tokens including 470 reasoning tokens. At the verified Standard rates the estimated total is **USD 0.001092175**, with **USD 0.01639775 reserved** and retained in the ignored campaign journal. Reasoning is not billed twice in this calculation. The estimate is not a bill/account balance; no credit or top-up is assumed. Per-case review times were 4.140, 8.096, 3.478 and 3.006 seconds; 18.720 seconds summed, excluding setup.

Selected for the first F8 external demonstration: **OpenAI Responses, `gpt-6-luna`, medium**. Local development stays GPT-OSS/single; persistent external enablement stays off. No other paid provider was tried because this small case set and cost met the POC's selection criterion. The shared source/evidence/finding contract was unchanged; external-native tools were not exercised because the review path supplies no model tools.

The network-disabled real CLI also rejected an attempted external selection with `External inference is disabled; local failure never enables it`. Private scopes and alternate destinations are covered by local tests. There were no private transmissions, retries, summary writes, infrastructure provisioning or account top-ups. These four public examples do not prove general accuracy, private-code policy suitability, live webhook behavior or cloud-deployment capacity. See the [F6 guide](../../f6-model-providers.md).
