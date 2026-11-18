# Temporary OCI integration deployment

`compose.vps.yaml` runs Caddy, the API gateway, MCP gateway, review worker and
PostgreSQL on one VPS. Inference uses OpenAI `gpt-6-luna`; no local model server is
required. Only Caddy publishes ports 80/443. The database and internal gateways
remain on the Compose network. Credentials are mounted individually from files.

Reviews are reactive: provider callbacks enqueue work. There is no repository
poller. GitLab/Bitbucket callbacks authenticate and persist normalized routing
metadata before acknowledging HTTP 202. Admission then resolves the snapshot
through MCP, with up to 12 attempts and 20 seconds between retries. A five-minute
claim lease allows recovery after interruption. Raw request bodies and secret
headers are not stored. GitHub callbacks authenticate and enqueue the supplied
snapshot directly. Snapshot uniqueness suppresses duplicate model jobs, including
Bitbucket's separate push and PR-update events for the same commit.

## Provision and deploy from Windows

Run from the repository root with an authenticated OCI CLI profile, Docker, SSH,
Git credentials and the existing platform/model secret files configured.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/New-OciTestHost.ps1
# If provisioning is still in progress, run the same command again once RUNNING.
```

The default requests one A1 OCPU / 6 GB in the tenancy home region. It never falls
back to paid compute automatically. If free capacity is unavailable, a paid test
requires separate spending authorization, followed by `-PaidTest -MemoryGB 4`.
The paid path records a six-hour deadline. Before deploying it, run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Set-OciAutoStop.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Build-OciArtifact.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Deploy-OciTestApp.ps1 -PrepareOnly
# Review reports/oci/deploy-payload.txt and the destination in host-state.json.
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Deploy-OciTestApp.ps1
```

The build compiles/packages Java without running test doubles. Acceptance uses
the real deployed services and provider APIs. The payload allowlist excludes
OCI credentials and SSH private keys. SCP encrypts transport; the local archive
contains credentials and must remain ignored. Keep the database password while
reusing the PostgreSQL volume. Deployment preserves generated VPS secrets.

The paid deadline uses **OCI Resource Scheduler STOP_RESOURCE**, with an IAM
policy restricted to that schedule and instance. A guest OS shutdown alone is
not the budget control. Stopped instances retain their disk and public callback
configuration; check storage/network charges before extending the experiment.
This test host is temporary, not a permanent free-tier deployment.

The VPS GPT journal has an explicitly approved USD 0.20 cumulative conservative
reservation ceiling, including completed calls. The completed live campaign
reserved USD 0.0980705. Reservations are not billed spend; reports separately
retain usage and POC price estimates. The overall temporary-test authorization is
USD 1. Increasing duration or limits needs a new budget check.

## Configure reactive callbacks

Let `HOST` be `publicHost` from ignored `reports/oci/host-state.json`. Keep TLS
verification enabled. Secret files below are under
`reports/oci/bundle/secrets/`; never include their values in source or reports.

| Provider | Callback | Authentication | Events |
| --- | --- | --- | --- |
| GitHub | `https://HOST/webhooks/github` | `github-webhook-secret`, HMAC SHA-256 | Pull requests |
| GitLab | `https://HOST/webhooks/gitlab` | `gitlab-webhook-secret`, X-Gitlab-Token over TLS | Merge request events |
| Bitbucket | `https://HOST/webhooks/bitbucket` | `bitbucket-webhook-secret`, HMAC SHA-256 | PR created, PR updated, repository push |

GitHub repository hooks use JSON. Both configured test repositories need a hook.
The existing GitHub App remains the read/publication identity. Repository hooks
do not contain an installation object: after HMAC and repository-ID validation,
the receiver uses only the configured installation. App events containing a
different installation remain rejected. `Set-OciGitHubWebhooks.ps1` configures the
two fixture repository hooks through the locally cached Git credential without
saving or printing it. Its `deliveries` and `redeliver` actions use GitHub's real
delivery APIs; they never post simulated payloads to the receiver.

GitLab's review credential may lack hook administration. In that case an owner
must configure Settings → Webhooks manually. The current deployment uses the
secret-token authentication supported by GitLab. GitLab signing-token validation
is not implemented. This does not claim GitLab Self-Managed coverage.

`configure_oci_webhooks.py --platform bitbucket --apply` creates the fixture
repository hook using its existing administration-capable token. It requires
Python and OpenSSL only for the optional GitHub App administration path; current
GitHub acceptance uses repository hooks.

## Run an isolated real lifecycle

Use a fresh campaign prefix/directory. Three local Git commits create a correct
helper, introduce a bounds bug, and add neutral documentation. Push before
opening PRs. GitLab/Bitbucket requests can be opened with `open_oci_change.py`;
open the two GitHub PRs through GitHub and record their metadata in the same
campaign directory. Keep fixtures unmerged.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Prepare-OciBranches.ps1 -Prefix oci-webhook/20261118/ -CampaignDirectory reports/oci/webhooks
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Publish-OciBranches.ps1 -CampaignDirectory reports/oci/webhooks
# Open PRs, recording <name>-pull-request.json for each target.
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Wait-OciReviews.ps1 -Stage initial
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Fix-OciBranches.ps1 -CampaignDirectory reports/oci/webhooks
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Publish-OciBranches.ps1 -Stage fixed -CampaignDirectory reports/oci/webhooks
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Wait-OciReviews.ps1 -Stage fixed
```

The waiter never submits reviews. It correlates persisted provider deliveries to
jobs, checks current full commit SHA and complete coverage, then reads back the
actual published comment and checks its body and author. The fixed stage must
reuse the original comment ID and have zero findings on the latest commit.
