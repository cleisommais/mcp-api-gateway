# Security and identity

[Index](../README.md) · [Architecture](architecture.md) · [Requirements](requirements.md).

Requested direction: least privilege, per-call authorization, and identity isolation. Concrete mechanisms below are **P** unless explicitly required. F7 expands evaluation; protections needed by a feature must be present when that feature is delivered.

## Identities and credentials at each boundary

| Boundary | Identity and mechanism to plan | Rule |
| --- | --- | --- |
| User → application/gateway | Session/OIDC from an appropriate authorization server; deployment Q | Validate issuer, audience, expiration, and permissions; identity does not automatically grant every repository |
| Worker/agent → gateway | Workload identity and gateway-targeted token; noninteractive flow to choose | Bind job, connection, and resource scope outside model control |
| Gateway → MCP Server | Connection-specific credential, OAuth where supported, or the server's approved mechanism | Do not indiscriminately forward the agent's bearer token |
| MCP Server/adapter → Git platform | App installation, service token, or delegation compatible with the offering | Restrict repositories, operations, and lifetime; respect revocation/suspension |
| Application → provider/model | External provider's own credential; network/proxy controls for local Ollama | Do not reuse Git/user credentials or put credentials in prompts |
| Platform → webhook | Platform-specific delivery authenticity verification | Authentic sender does not make code, descriptions, or comments trusted instructions |

The [MCP Authorization specification](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization) covers HTTP transport authorization; it does not require the same flow for `stdio`, which uses process/environment credentials according to implementation. For HTTP, use an appropriate authorization server, resource/audience validation, and per-call policy. Do not build an IdP.

Filtering the catalog limits exposure but never replaces enforcement. The gateway reevaluates identity, tool, platform, connection, repository, SHA, and budget on each call. The application and downstream components validate their own responsibilities; the gateway alone does not secure the entire flow. Reference: [MCP Security Best Practices](https://modelcontextprotocol.io/docs/2026-07-28/tutorials/security/security_best_practices).

## Proposed GitHub automation

Install a GitHub App beforehand on selected repositories. Installation and personal authorization to act on a user's behalf are different concepts. Automation runs as the installation's bot without login for every PR; opening/updating a PR in the UI can trigger a webhook without GitHub Actions.

The backend or compatible MCP Server obtains a temporary installation access token using the App identity, restricts resources, and obtains another when it expires. This does not depend on a personal refresh token. The consulted documentation states a one-hour lifetime; always use the returned expiration, renew with a margin, and stop attempts after revocation/suspension. Source: [installation authentication](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/authenticating-as-a-github-app-installation).

**P for permissions:** metadata, content, and PR read access for review; summary writes only through the publisher's identity/path. Exact permissions depend on selected endpoints and are checked before F4. Do not grant write access just because the server exposes a tool. PAT and machine-user approaches remain alternatives, not the approved default.

The official server documents [App authentication over stdio](https://github.com/github/github-mcp-server/blob/main/docs/github-app-auth.md); HTTP has different conditions. Confirm version, installation, and renewal support in the selected mode. Do not infer that a remote server's interactive OAuth login accepts the same token automatically.

## Future interactive delegation

Use OAuth when an operation genuinely needs to act on behalf of the user. Store credentials securely, renew only when the mechanism supports it and authorization remains valid, and request authorization again when necessary. A `git push` credential is not agent authorization. Local execution does not require personal identity.

## Untrusted content and prompt injection

Code, comments, strings, documentation, PR title/description, commit messages, discussions, and tool responses can contain adversarial instructions. Reading them can influence the model without executing code.

Distinguish **analysis manipulation**, such as hiding a bug or inventing approval, from **tool abuse**, such as reading another repository or disclosing code. Instruction separation and pattern detection help, but no filter eliminates prompt injection. Treat PR data as evidence, never as authority to change policies, destinations, or credentials.

| Threat | Proposed control outside the model | Planned check |
| --- | --- | --- |
| PR asks to ignore a bug | Separate instructions from data; validate evidence and use labeled examples | A known bug with hostile instructions remains evaluated; record detection failures |
| Model requests another repository/SHA | Immutable authorized context and harness/gateway argument checks | Deny and audit cross-resource access in the tested combinations |
| Traversal and ambiguous paths | Platform-aware normalization; reject `..`, inappropriate absolute paths, ambiguous encoding, and out-of-policy symlinks | Adversarial path fixtures fail before reads |
| SSRF and arbitrary destinations | URLs/processes only from administrative configuration; egress allowlist; DNS/redirect checks | Block metadata URLs, inappropriate loopback, external redirects, and unregistered hosts |
| Token for the wrong resource | Check signature/issuer/audience/expiration according to mechanism; separate credentials per hop | A token valid for another resource remains denied |
| Tool poisoning | Pin server/version and schema/description fingerprint; review changes before exposure | Schema/description change invalidates approval or suspends the connection |
| Excessive tool use/context | Allowlist, schemas, byte/token/call/duration limits | Excessive requests end with an error/partial report rather than a loop |
| Secret in a file/diff | Detect before transmission; redact or block by policy; protect evidence | Synthetic secrets do not appear in external prompts, comments, or logs |
| Malicious output | Schema and semantic validation, safe escaping/rendering, link/mention controls | Hostile HTML/links/instructions and invented evidence do not become publishable comments |
| Replay and duplication | Authenticate, deduplicate by delivery/connection, and reconcile publication | Redelivery/retry does not spam summaries |
| Shared GPU saturation | Bounded queue, slots, timeouts, evaluation timing, and diagnostics | Concurrent load does not silently trigger external fallback |

Do not indiscriminately remove data needed for review: report affected coverage when redaction/blocking removes evidence. Secrets must never appear in versioned configuration examples/documents.

## Webhooks

For GitHub, verify the HMAC of the original body using the connection secret and a constant-time comparison, following [official validation guidance](https://docs.github.com/en/webhooks/using-webhooks/validating-webhook-deliveries). Check type/action, installation, repository, size, and delivery ID; persist deduplication before acknowledging acceptance. Do not assume GitLab or Bitbucket uses the same format.

For [GitLab](https://docs.gitlab.com/user/project/integrations/webhooks/) and [Bitbucket Cloud](https://support.atlassian.com/bitbucket-cloud/docs/manage-webhooks/), check the selected offering/version's mechanisms and test tampered bodies and incorrect credentials. Replay policy must use what the platform actually supplies; do not invent a mandatory signed timestamp when absent. TLS, connection secret, deduplication, and administrative reprocessing have different roles.

## Ollama and transmitted data

The observed endpoint responds locally without authentication and publishes its port on `0.0.0.0` and `[::]`. This does not prove external reachability, since firewall/network access was not tested. Docker execution alone is not protection. Before integration, confirm allowed reachability and restrict firewall/network access; use an authenticated proxy/TLS across an untrusted boundary. None of these infrastructure changes were performed.

Only the model access layer should contact registered inference destinations. The model, PR, and tool result cannot choose an endpoint. The cloud demonstration uses an explicitly configured external API as its primary destination; this does not enable automatic fallback when local Ollama fails. Define provider, repositories, data classification, retention, budget, and transmission records. Local inference still requires networking for Git platforms and remote MCP Servers.

## Temporary VPS and public portfolio

The same webhook, identity, authorization, SHA, and publication checks apply on the VPS. **P:** expose only required HTTPS ingress and restricted administrative access; keep database, gateway, and internal MCP Servers without public ports. Use secret references, least-privilege campaign credentials, and spending/call limits; do not copy unrelated desktop credentials into the demonstration.

Synthetic/publishable data is the proposed default for evaluation and recordings. A free API plan is not permission to send private code: check retention, training/data use, quotas, and offering terms before selecting it. Do not grant shell access, hosted tools, or provider-direct MCP access merely because the API offers them; review tools remain governed by the application's gateway.

Before public release, review files/history, example licenses, personal paths, private projects, PR links, invoices, and recording screens. The current local inventory contains diagnostic details and needs a sanitized public version. Do not publish real `.env` files, App keys, model/cloud tokens, prompts containing private code, or results disclosing restricted data.

Export sanitized evidence before removing infrastructure. Teardown includes inventory/deletion of POC resources, revocation of temporary credentials, and residual-billing checks; do not affect other projects' shared credentials/volumes. Procedure and evidence are in [cloud-demo-and-portfolio.md](cloud-demo-and-portfolio.md). These are not model tools and have not been executed.

## Logs, retention, and initial scope

**P for the POC:** simple logs with jobId, authorization decision, stages, and errors; no tokens or private code by default. Keep useful portfolio evidence and discard temporary data on teardown. No retention-by-class policy, continuous backup, SIEM, or corporate audit process is required. Demonstrating one permitted and one denied call makes the control observable.

The proposed MVP performs static analysis without PR code execution, generic shell, merge, automatic approval, or model-driven code changes. The deterministic publisher sends only validated, reconciled results. Adversarial checks assess both blocked abuse and reduced review quality; blocking tools does not guarantee correct analysis.
