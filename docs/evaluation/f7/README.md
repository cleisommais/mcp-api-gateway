# F7 evidence

Executed on 2026-11-14 using the project-owned PostgreSQL, governed MCP Gateway and local `gpt-oss:20b` with medium reasoning. F7 made no external model API calls and enabled no publication.

| Artifact | Meaning |
| --- | --- |
| [assessment.json](assessment.json) | Full test counts, limits, GPU observation, outcomes and known quality failures |
| [concurrency-observations.jsonl](concurrency-observations.jsonl) | Two repositories running while a second public-repository job remains queued |
| [f7_lab_concurrency.json](f7_lab_concurrency.json) | Three jobs, coverage, attempts, per-call bytes/waits and process-cumulative inference metrics; private source/findings text omitted |
| [restart-events.jsonl](restart-events.jsonl) | Guarded SIGKILL of a running attempt, then restart of that same container after lease expiry |
| [f7_lab_restart_actual.json](f7_lab_restart_actual.json) | Same durable job analyzed on attempt 2 with no publication |
| [restart-script-events.json](restart-script-events.json) and [restart-script-result.json](restart-script-result.json) | Reusable restart script independently verified a fresh attempt-2 recovery and removed its own worker |
| [gateway-scope-denial.json](gateway-scope-denial.json) | Live denial audit with unresolved downstream server; no unregistered-platform access |
| [f7_lab_unavailable.json](f7_lab_unavailable.json) | Closed-port model-availability failure persisted |
| [f7_lab_timeout.json](f7_lab_timeout.json) | One-second HTTP timeout, partial report and explicit unknown usage |
| [f7_lab_output_limit.json](f7_lab_output_limit.json) | Real truncation after the accounting fix: 694 prompt and 128 output tokens retained |
| [f7_lab_partial.json](f7_lab_partial.json) | One-batch limit leaves 9 analyzed/13 pending files |
| [f7_lab_injection.json](f7_lab_injection.json) | Seeded defect found but severity MEDIUM is wrong against the HIGH reference |

The complete suite ran with PostgreSQL: **104 passed, zero failures/errors, zero skipped**, including five database tests. The image build separately passed 99 and skipped the database-only five because it has no database connection. The original three database tests had also passed unchanged before F7 implementation. [One-command runner](../../../scripts/Test-Project.ps1).

Nine added checks cover admission bounds/timeout/cancellation, configurable gateway parallelism, atomic database global/repository limits and retained incomplete-generation usage. Existing scope/evidence checks still pass. The live CLI additionally denied an unregistered repository before downstream retrieval.

The wide public review's three attempts included a truncated generation and eight false positives based on an invented older-Java constraint. It stayed partial (11 analyzed/11 pending). Its initial token totals predate the accounting fix and omit the failed generation; do not use them as a complete usage measurement or reconstruct the missing count. The separate output-limit case verifies the corrected behavior. The cross-file case correctly found the 12-versus-1250 unit bug at MEDIUM; the private boundary case found the expected HIGH defect. Raw private reports remain ignored.

Inference metrics are cumulative snapshots of one process and must not be summed across reports. The observed 29.507-second admission wait is separate from native inference duration. GPU VRAM includes desktop/background use. The batched PR exceeds the conservative effective change-input allowance; no native-token limit or throughput claim is made. A replay/pilot is not an actual GitHub delivery. See the [F7 guide](../../f7-concurrency-and-recovery.md) for reproducible settings and limitations.
