# OCI reactive webhook acceptance — 2026-11-18

The application and PostgreSQL run on one Oracle VPS in `us-chicago-1`, through
`compose.vps.yaml`. GPT `gpt-6-luna` is the inference provider; no Ollama service
runs there. Caddy supplies valid public HTTPS at
`https://prreview.147.224.171.64.sslip.io` (public IP `147.224.171.64`).

**Final policy: 8/8 real webhook checks passed**: one broken and one corrected
revision on each of four targets. Every final report is complete, covers both
changed files, identifies the full latest commit, contains zero findings, and
updates the original owned summary. The actual remote comment body and author
were read back and verified after each accepted stage.

| Target | Initial/local regression | Final fix | Final head | Summary |
| --- | --- | --- | --- | --- |
| [GitHub public #7](https://github.com/cleisommais/pr-test-repo/pull/7) | 1 finding | 0 | `c4ec97f5612cf6d9f1d1e12a51ce5b3d66410376` | [6980739501](https://github.com/cleisommais/pr-test-repo/pull/7#issuecomment-6980739501) |
| [GitHub private #7](https://github.com/cleisommais/pr-test-repo-private/pull/7) | 1 finding | 0 | `49bb027729c49382ce2971ddb916c5410def80ae` | [6980743702](https://github.com/cleisommais/pr-test-repo-private/pull/7#issuecomment-6980743702) |
| [GitLab !4](https://gitlab.com/cleisommais/pr-test-repo/-/merge_requests/4) | 1 finding | 0 | `07643e0178f0bede8461acbdbecf136503707e26` | [4265163419](https://gitlab.com/cleisommais/pr-test-repo/-/merge_requests/4#note_4265163419) |
| [Bitbucket #8](https://bitbucket.org/cleisommais/pr-test-repo/pull-requests/8) | 1 finding | 0 | `57ec00b2cba672e409306f8f2d5ddcf8e55251e9` | [905372799](https://bitbucket.org/cleisommais/pr-test-repo/pull-requests/8#comment-905372799) |

## What actually triggered the reviews

Each branch started with **three local Git commits**, pushed with native Git
before opening the PR/MR. Those commits added a correct totals helper, introduced
an inclusive loop bound, then added neutral documentation. Subsequent fixes and
regressions were also local Git commits followed by non-force pushes.

Provider callbacks, not review-submission scripts, initiated this campaign:

- GitHub: repository webhooks, `pull_request` actions `opened` and `synchronize`.
  The existing GitHub App supplies outbound reads/publication. Both public and
  private fixture repositories have active JSON hooks to `/webhooks/github`.
- GitLab: the owner manually configured Merge request events to `/webhooks/gitlab`.
  The available review token returned 403 for hook administration. Real `open`
  and `update` deliveries were correlated to the review jobs.
- Bitbucket: a signed repository hook to `/webhooks/bitbucket`, subscribed to PR
  created/updated and repository push. Real `repo:push` and `pullrequest:updated`
  events for the same commit reused one snapshot job.

The temporary repository poller used for an earlier smoke test was stopped,
removed from Compose/source, and its stopped container was removed. None of the
acceptance jobs above were triggered by polling or manual `POST /api/reviews`.
The waiter only reads status, persisted delivery records and platform comments.

## Failures and fixes retained in the evidence

The first public GitHub opened delivery arrived during a deployment restart and
received HTTP 502. GitHub's own redelivery API resent that original event after
the receiver became healthy; it returned 202 and created the review. Repository
webhook payloads omit `installation`: the receiver now supports them with raw-body
HMAC and configured repository-ID/name checks, while preserving installation
validation on App payloads and the configured outbound App identity.

GitLab/Bitbucket admission now uses a PostgreSQL inbox so callbacks acknowledge
quickly after durable persistence. Repository consistency checks and bounded
retries occur outside the HTTP callback. Only normalized routing metadata is
stored. All 11 final inbox records were complete, with none pending or failed.
This campaign exercised normal processing and deduplication; it did not inject
a crash to verify five-minute lease recovery.

The first Bitbucket fix removed the bounds defect, but the clean audit reported
generic Java `int` overflow without an established requirement for different
numeric behavior. That failed the expected clean result and is retained. Audit
policy `clean-review-audit-v2` now requires expected behavior grounded in supplied
code, callers or contracts. The primary `review-original-v3` prompt is unchanged.
After deployment, a fresh bounds regression and fresh fix on **every** target
passed (8/8). The earlier failure is not counted as a pass or hidden by a rerun
of the same immutable snapshot.

An additional GitHub redelivery of the final real `synchronize` event returned
HTTP 200 with duplicate handling, retaining the existing job/comment. No extra
model attempt was added: the VPS journal still contained 36 completed calls,
including the earlier polling smoke test and failed clean-result attempt.

## Contract, runtime and budget checks

`open-api.yaml` validates as OpenAPI 3.1. Nine actual HTTPS boundary responses
matched their documented schemas: health, protected status access, authenticated
unknown job, unauthenticated webhook rejection and wrong-method handling for all
three webhook routes. TLS certificate checks remained enabled. These checks used
the real VPS, not mock repository servers.

The five final services are running; all four with configured health checks are
healthy. PostgreSQL and internal gateways publish no host ports. The worker's JAR
SHA-256 matched the compiled local artifact:
`d81876564baaa7a9025822ad8156738668f15c396df0f71f576469712d39c8b9`.

Free A1 host capacity was unavailable; this is the explicitly authorized paid
fallback: E5 Flex, 1 OCPU / 4 GB, 50 GB boot volume. It must not be described as an
active Free Tier host. A six-hour allocation is approximately USD 0.19 at the
checked [Oracle E5 CPU/memory rates](https://www.oracle.com/europe/a/ocom/docs/corporate/pricing/oracle-paas-and-iaas-global-price-list.pdf),
before any other applicable charges; this is not an invoice. The user authorized
a USD 1 total test ceiling.

GPT conservative reservations totaled **USD 0.0980705**. The user separately
approved increasing the cumulative reservation ceiling to **USD 0.20** after
acceptance, leaving headroom for further repository tests. The journal and prior
reservations were preserved. Reservations are distinct from billed usage.

OCI Resource Scheduler was created and confirmed ACTIVE for a one-time
`STOP_RESOURCE` at **2026-11-18 19:27 UTC / 13:27 America/Chicago**, restricted by
IAM to this schedule and this instance. A later read-back could not refresh the
expired local OCI CLI session; the previously confirmed scheduler operates
independently of that local session. Guest shutdown is not the billing safeguard.
The host, disk and webhook configuration remain temporary resources; extending
this experiment requires checking budget and renewing OCI management access.

## Evidence and reproduction

[results.json](results.json) contains full commit/job IDs, expected and observed
findings, provider delivery identities/actions, retained failed check, comment
identities, contract checks and runtime/budget metadata. Full provider reports and
read-back logs remain in ignored `reports/oci/webhooks/`; credentials, deployment
archives, personal OCI identifiers and private keys are excluded.

See [deployment and webhook setup](../../../deploy/oci/README.md). Run
`scripts/summarize_oci_webhooks.py` to verify captured reports and regenerate the
export. `scripts/verify_oci_contract.py` uses Python with
`openapi-spec-validator==0.7.2` against the actual HTTPS endpoint. The follow-up
`Advance-OciBranches.ps1` stages `regression` and `fixed-again` create the extra
local commits; `Wait-OciReviews.ps1` validates those stages against audit v2.

These are synthetic test repositories. This validates the observed webhook and
iterative-review lifecycle, not general model accuracy, merge/approval automation
or webhook delivery during every possible outage. Fixtures remain open/unmerged.
