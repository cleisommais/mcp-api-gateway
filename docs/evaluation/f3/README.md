# F3 evidence

All code/prose in these model inputs comes from the public synthetic test repository. Expected answers and human judgments are in [assessment.json](assessment.json) and the [evaluation guide](../../f3-model-evaluation.md), outside model inputs.

- `comparison/`: sixteen original/calibrated Qwen reports and four captured input fixtures. Hashes match across each two-model pair within its profile/PR.
- `direct-replay/`: paired local API outputs with thinking off/on. Export omits reasoning traces; final response text and usage remain.
- `reasoning-14b/`: four real agent/gateway reports, including 11 false positives in the correct-change scenario.
- `reasoning-9b-offline/`: the later 9B reasoning experiment. Current PR state was not checked; model output remains mechanically validated but needs human grading.
- `gpt-oss/`: four captured-fixture reports plus the selected configuration's live PR #3 report.
- `gateway-audit.json`: five scoped calls correlated to that live report's job ID, including cleanup.

Offline artifact exports corrected capture-time metadata from the original fixtures and retained evaluation time separately; generated findings were not edited. The runner now makes this distinction itself. Separate campaigns can serialize object keys differently, so the exact-input guarantee applies within controlled pairs, not arbitrary reports across processes. These are a few observed examples, not a statistical quality/performance claim. Model tags can change; reports retain the observed digest and options.
