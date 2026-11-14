# F4 evidence

- [validation.json](validation.json): 59 non-database checks, three previously verified PostgreSQL integration checks, live private PR #1 metadata, and explicit pending live checks.
- [private-fixtures.json](private-fixtures.json): creation SHAs/links and expected outcomes of the new dependency-free Java fixture checks. Private source and raw private reports are not exported here.
- [local-pipeline.json](local-pipeline.json): a locally signed synthetic event for an existing public PR, its 202/duplicate-200 receipts, and the real queue/MCP/model report. It used a synthetic installation ID, not a real GitHub App delivery, and publication was disabled.

The private repository's anonymous read was tested through the actual MCP path and returned GitHub's masked 404. The App connection fails closed while credentials are unconfigured. After supplying the missing local App/installation IDs, the real `private-github-app` connection retrieved PR #1 and its pinned file. Its manual `gpt-oss:20b` review completed with one HIGH finding for the seeded boundary defect and publication disabled. Private source and the raw report remain in ignored local storage. Live callback/publication and private PRs #2–#4 model reviews remain pending. Synthetic token/JWT/publication tests are not substituted for those live checks.
