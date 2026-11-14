# F4 — Install the GitHub App and run the lab

[F4 implementation](f4-webhook-jobs.md) · [Setup reference](../config/github-app-setup.json).

The connector created the private fixtures, but the Java containers need their own App identity. Live App authentication, bot publication, and an actual GitHub callback remain pending until these steps are completed. All commands below run from `D:\projects\mcp-api-gateway` in PowerShell. Private-key contents and the webhook secret stay out of chat and Git.

## 1. Prepare local files

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Initialize-LocalEnvironment.ps1
```

This creates the ignored `secrets` directory and fills missing `.env` credentials, including `WEBHOOK_SECRET`, while preserving existing values. Docker Desktop is already the runtime; host Java/Maven are unnecessary.

## 2. Register the App

Open [GitHub App registration](https://github.com/settings/apps/new) under your account. Give it a unique short name, for example `mcp-review-cleisommais-poc`, and use `https://github.com/cleisommais` as its homepage. Leave user OAuth/device authorization off and the callback/setup URL fields empty. Keep webhook delivery inactive initially so you can configure private reads first. Choose installation on your account only.

Set the repository permissions to **Contents: read** and **Pull requests: read/write**; Metadata read is included. Subscribe to pull-request events. Save the App, then note its numeric App ID from its settings. [GitHub registration reference](https://docs.github.com/en/apps/creating-github-apps/registering-a-github-app/registering-a-github-app).

## 3. Download the private key

In the App's settings, open its credentials/key-pair area and create a key. Save the downloaded PEM as:

```text
D:\projects\mcp-api-gateway\secrets\github-app.pem
```

The implementation accepts GitHub's PKCS#1 RSA PEM as well as PKCS#8. It does not need a client secret. The folder is excluded from Git and Docker build context. [GitHub key instructions](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/managing-private-keys-for-github-apps).

## 4. Install on the two demonstration repositories

Use the App's installation page for your account, select only `pr-test-repo` and `pr-test-repo-private`, then install. Open the resulting installation configuration and note the installation ID from its URL. For example, the trailing number in `https://github.com/settings/installations/12345678` is the installation ID. It is different from the App ID. [Installation reference](https://docs.github.com/en/apps/using-github-apps/installing-your-own-github-app).

## 5. Configure `.env`

Add or update these entries in the existing file, replacing the placeholders with your numeric IDs:

```dotenv
GITHUB_APP_ID=YOUR_NUMERIC_APP_ID
GITHUB_INSTALLATION_ID=YOUR_INSTALLATION_ID
PUBLISH_REVIEWS=false
```

Preserve `POSTGRES_PASSWORD`, `GATEWAY_TOKEN`, and `WEBHOOK_SECRET`. Compose mounts the PEM at `/run/secrets/github-app.pem` inside the gateway/worker; no private-key text goes into `.env`. The worker and read-only MCP subprocess request separate repository-scoped installation tokens with the permissions they need. Tokens are refreshed before expiry.

## 6. Check private access before enabling publication

```powershell
docker compose --profile automation build gateway
docker compose --profile automation up -d postgres ollama gateway edge
docker compose --profile review run --rm review github cleisommais/pr-test-repo-private 1
```

Inspect the resulting local report. It should enumerate the private PR's changed file and find the seeded boundary defect. If access fails, verify the App's installation selection, permissions, IDs, and PEM file; a masked 404 can indicate missing private-repository access. This command does not publish a comment.

## 7. Enable the worker and temporary HTTPS entry point

When ready to test bot comments, set `PUBLISH_REVIEWS=true` in `.env`, then run:

```powershell
docker compose --profile automation up -d edge gateway worker
docker compose --profile automation --profile tunnel up -d tunnel
docker compose --profile tunnel logs -f tunnel
```

The optional tunnel service prints a temporary `https://...trycloudflare.com` URL. It forwards only to the edge; the agent worker and MCP Gateway remain internal. This uses a Quick Tunnel without a Cloudflare account/domain. A restarted tunnel can have a different hostname, so update the App's delivery URL when it changes. The service is optional and has not been started as part of the local-only validation. [Quick Tunnel reference](https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/).

## 8. Enable webhook delivery in the App

Set the App's webhook URL to the printed HTTPS hostname followed by `/webhooks/github`. Copy the value of `WEBHOOK_SECRET` from your local `.env` into the App's webhook secret field. Enable delivery and retain TLS verification. A signed ping receives HTTP 200 and creates no review job.

No GitHub Actions workflow is needed. GitHub sends the PR event directly. The edge verifies its HMAC, installation, repository ID/name, branch, draft state, contributor association, and fork policy before persisting a job. [Webhook signature reference](https://docs.github.com/en/webhooks/using-webhooks/validating-webhook-deliveries).

## 9. Trigger a private review

The four fixture PRs already exist. Close and reopen private PR #1 without merging, or push a new commit to a fixture branch. Reopening triggers a supported event. Check:

```powershell
docker compose --profile automation logs -f edge worker gateway
```

Look for the job ID, a completed worker entry, and a summary from your App's bot on the PR. The webhook returns 202 after persistence; review execution happens afterward. Redelivery of the same event must reuse its job. A retry updates/reconciles the bot-owned summary marker rather than deliberately posting another comment.

Inspect persisted state with:

```powershell
docker compose exec -T postgres psql -U mcp_review -d mcp_review -c "SELECT id,repository,pr_number,state,attempts,publication_state,publication_id FROM review.jobs ORDER BY created_at DESC LIMIT 10;"
```

Private report contents stay in ignored `reports/` and PostgreSQL. Do not copy them into public evidence merely because these initial fixtures are synthetic.

## 10. End the temporary session

```powershell
docker compose --profile tunnel stop tunnel
docker compose --profile automation stop edge worker
```

Stopping the tunnel removes its public URL. The PostgreSQL/model volumes remain for the next exercise. An ambiguous publication remains visible as `publication_uncertain`; a local retry reconciles it and does not blindly create another summary. See the [F4 guide](f4-webhook-jobs.md) for the administrative CLI and limitations.
