# F4 evidence

- [validation.json](validation.json): 58 non-database checks and three real PostgreSQL integration checks, plus explicit pending live checks.
- [private-fixtures.json](private-fixtures.json): creation SHAs/links and expected outcomes of the new dependency-free Java fixture checks. Private source and raw private reports are not exported here.
- [local-pipeline.json](local-pipeline.json): a locally signed synthetic event for an existing public PR, its 202/duplicate-200 receipts, and the real queue/MCP/model report. It used a synthetic installation ID, not a real GitHub App delivery, and publication was disabled.

The private repository's anonymous read was tested through the actual MCP path and returned GitHub's masked 404. The App connection fails closed while credentials are unconfigured. Positive live App/private/publication checks are pending the [user's setup](../../f4-github-app-setup.md). Synthetic token/JWT/publication tests are not substituted for those live checks.
