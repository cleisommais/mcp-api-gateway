# Local inference: inventory and plan

[Index](../README.md) · [Architecture](architecture.md) · [Requirements](requirements.md) · [Security](security-and-identity.md).

Inspection performed on **2026-11-12** using read-only commands. Memory/usage readings represent different instants, not a benchmark. No inference, pull, update, restart, network connection changes, or volume/file changes were made in the inspected projects.

## User-confirmed information

The user confirmed **RTX 5060 Ti with 16 GB VRAM, Ryzen 9 5950X, approximately 64 GiB RAM, Ollama 0.18.0 running under `divine-touch-infra`, and installed models `qwen3:14b`, `gpt-oss:20b`, and `divine-touch-30b:latest`**. Local reference: `D:/projects/divine-touch/divine-touch-infra`.

These confirmations match the earlier inspection; this documentation update did not repeat runtime queries. Architecture, quantization, template, context, and custom-model capacity are not inferred from its name or automatically confirmed by that list. Technical metadata below comes from the earlier `/api/show` query, a separate source, and is not proof of quality or effective capacity.

**C:** use existing Ollama as the development/validation default to avoid external inference charges during iteration. Briefly test the existing setup, but update the runtime when necessary and consider a newer/GPU-suitable model, following the latest user direction. The application does not update automatically; maintenance and downloads are explicit exercise steps. A desktop-independent demonstration uses a VPS with an external API, following [cloud-demo-and-portfolio.md](cloud-demo-and-portfolio.md). Java remains the harness and the gateway continues governing tools.

## Sources and method

Local sources read selectively, without secret files:

- `D:/projects/iam-trainer/iam-trainer-core/docker-compose.yml` and `README.md`.
- `D:/projects/iam-trainer/system-design/readme.md` and `project-plan.md`.
- `D:/projects/divine-touch/divine-touch-infra/docker-compose.yml` and `README.md`.

Diagnostics: `nvidia-smi` on the host and inside the container; CPU/RAM CIM queries; `docker ps`/`ps -a`; `ollama --version`, `list`, and `ps`; selected image, health, volume, network, GPU reservation, and Compose-label fields; `/api/version` and selected `/api/show` metadata. The POST to `/api/show` queries metadata without generating output. Only preselected nonsensitive Ollama/NVIDIA variables were displayed; no complete inspection or `.env` content was printed.

Docker/CIM queries initially blocked by the sandbox were repeated with tool-requested approval. Completed checks below are runtime evidence, not deductions based only on README files.

## Hardware

| Item | Runtime evidence | Documented information / limitation |
| --- | --- | --- |
| GPU | NVIDIA GeForce RTX 5060 Ti; 16,311 MiB reported total | Matches the 16 GB class documented in the projects |
| Host driver | 576.88 | Observed version, not a current-version recommendation |
| Instantaneous usage | About 1,787–1,832 MiB used, 1–2% utilization | No active generation observed; does not prove model GPU use |
| CPU | AMD Ryzen 9 5950X; 16 cores and 32 logical processors | Matches the system-design hardware description |
| Host physical RAM | 68,635,979,776 bytes, approximately 63.9 GiB | Does not measure memory available to Docker/WSL2 |
| Disk | Not measured | system-design mentions a 1 TB+ SSD; free space/effective capacity remain unverified |
| Docker/WSL2 limits | Not measured | Measure VM RAM/CPU availability before capacity experiments |

The [official Ollama hardware matrix](https://docs.ollama.com/gpu) lists RTX 5060 Ti/architecture 12.0. This supports GPU candidacy; the actual image/driver/model combination still needs an observed inference call. [Docker documentation](https://docs.ollama.com/docker) describes NVIDIA device access and persistence at `/root/.ollama`.

## Which configuration is active

| Item | Documented `iam-trainer-core` | Documented and running `divine-touch-infra` |
| --- | --- | --- |
| Container | `iam-trainer-ollama` | **Active:** `divine-touch-ollama` |
| Declared image | `ollama/ollama:latest` | `ollama/ollama:latest` |
| Activation | `llm` profile | No required service profile in the inspected Compose file |
| GPU | NVIDIA reservation, count all, gpu capability | Same reservation confirmed by DeviceRequests; GPU visible inside container |
| Model volume | `ollama-data:/root/.ollama` | **Actual volume:** `divine-touch-infra_ollama_data:/root/.ollama` |
| Additional bind | Not observed in Ollama section | `D:/projects/divine-touch/divine-touch-infra/models` → `/models` |
| Port | `${OLLAMA_HOST_PORT:-11434}:11434` | `11434:11434`; runtime publishes on `0.0.0.0` and `[::]` |
| Network | Compose key `iam-trainer-net` | **Actual network:** `divine-touch-infra_divine-touch-net`; aliases `ollama` and `divine-touch-ollama` |
| Parallelism | Compose default 4, configurable by variable | Runtime `OLLAMA_NUM_PARALLEL=1` |
| Keep alive | Compose default 15m | Runtime `OLLAMA_KEEP_ALIVE=30m` |
| Other controls | Defaults: max loaded 1, queue 128, flash attention 1 | Runtime flash attention 1, `OLLAMA_NUM_CTX=8192`; max loaded/queue not set among selected variables |
| Health check | `ollama list` | `ollama list`; container was healthy |

Labels `com.docker.compose.project.working_dir` and `project.config_files` explicitly point to **`D:/projects/divine-touch/divine-touch-infra/docker-compose.yml`**. Labels, mounts, network, and parameters confirm the origin beyond a similar name. The `iam-trainer-ollama` container was stopped and was not started.

Also active were `divine-touch-postgres` (`postgres:15`, port 5432) and `divine-touch-pgadmin` (`dpage/pgadmin4`, port 5050). The `iam-trainer` Compose file describes PyTorch/CUDA training, PostgreSQL/pgvector, Redis, Keycloak, applications, and observability; its discovered containers were stopped. This does not prove there are no clients outside Docker or no future use of the same GPU. Do not implicitly reuse unrelated applications' databases or volumes.

## Actual version and installed models

CLI and API version: **Ollama 0.18.0**. Observed state: running/healthy.

Observed repository image/digest: `ollama/ollama@sha256:e23e6499890325d31f3454cdc83f7a613a2423c8a9e167bbafea0b0e3c27d7c6`. A Compose `latest` tag does not prove an update; digest and running version identify the observed snapshot.

| Installed model | Abbreviated local ID | `ollama list` size | `/api/show` metadata | Nominal artifact context |
| --- | --- | --- | --- | --- |
| `qwen3:14b` | `bdbd181c33f2` | 9.3 GB | qwen3, 14.8B, Q4_K_M; completion/tools/thinking | 40,960 tokens |
| `gpt-oss:20b` | `17052f91a42e` | 13 GB | gptoss, 20.9B, MXFP4; completion/tools/thinking | 131,072 tokens |
| `divine-touch-30b:latest` | `93c850ab1d3a` | 13 GB | qwen3moe, 30.5B, Q3_K_M; completion/tools; own `num_ctx=8192` parameter | 262,144 tokens |

Observed numeric parameters: qwen3 temperature 0.6/top_k 20/top_p 0.95/repeat_penalty 1; gpt-oss temperature 1; custom model temperature 0.7/top_k 20/top_p 0.8/repeat_penalty 1.05. These are artifact metadata, not recommended review parameters.

`ollama ps` returned an empty list. Therefore, **allocated context, offload percentage, tokens/s, and GPU inference were not verified**. NVIDIA reservation and container `nvidia-smi` prove device access, not model execution. Do not run generation merely to complete this inventory.

Models `mistral:7b`, `llama3.2:3b`, `nomic-embed-text`, and fine-tuned artifacts mentioned in `iam-trainer` belong to that project's configuration/documentation; they were not found in active Ollama. Do not adopt the custom model based only on name/size; provenance, template, and suitability need review.

## Context: discrepancy to validate

Compose and runtime environment contain **`OLLAMA_NUM_CTX=8192`**. Official [context documentation](https://docs.ollama.com/context-length) and [FAQ](https://docs.ollama.com/faq) document service-side `OLLAMA_CONTEXT_LENGTH` and per-call `options.num_ctx`. It was not established that `OLLAMA_NUM_CTX` changes the 0.18.0 server default. Do not claim the service already gives the agent an effective 8K window.

The custom model's `num_ctx=8192` is an artifact setting, distinct from the environment variable. Inspected native models did not expose that parameter. Nominal model context, requested context, and actually allocated context are three different measurements.

**P:** configure context explicitly in the integration/request and observe `ollama ps` during evaluation. Start with a moderate window, such as 4K then 8K if memory permits, without changing the shared service during documentation work. Reserve tokens for instructions, tools, history, and output. More context/parallelism uses more memory; 9.3 GB of weights does not guarantee any arbitrary context or batch size in 16 GB.

## Recommended reuse and networking

**P for first access:** Java containers use configurable endpoint **`http://host.docker.internal:11434`**, reusing the already published port. [Docker Desktop documentation](https://docs.docker.com/desktop/features/networking/networking-how-tos/) describes this host-access DNS name. The API was queried from the host during inspection; access from a future project container remains untested.

This avoids initially attaching the application to the entire `divine-touch` network, which also contains the database and pgAdmin, without modifying the inspected Compose file. It depends on Docker Desktop, the owning service being active, and appropriate exposure controls. Do not use `localhost:11434` inside the Java container: it would refer to that container.

**P alternative:** declare existing `divine-touch-infra_divine-touch-net` as an external network in the future project Compose file and attach only the component needing model access; endpoint `http://divine-touch-ollama:11434`. Do not create a second network with the same logical name and assume automatic connectivity; do not pin a temporary IP. This expands network trust and depends on the external network lifecycle. A dedicated inference network could improve isolation later but would require coordinated infrastructure changes and has not been created.

Neither option supplies authentication by itself. Observed all-interface publication requires checking firewall/LAN reachability and policy before real data use. Do not create another Ollama instance or duplicate models merely for convenience.

## Integration and experimental model selection

**C:** reuse local inference while retaining architectural support for other providers; installed models are a baseline, not a requirement for exhaustive evaluation before trying a better candidate. **P:** Spring AI native Ollama integration, configurable endpoint/model, disabled automatic pulls, and simple per-call/job limits. The [official integration](https://docs.spring.io/spring-ai/reference/api/chat/ollama-chat.html) documents these capabilities; test the runtime/model combination during the exercise.

| Candidate | Why evaluate it | Recommendation limit |
| --- | --- | --- |
| Installed `qwen3:14b` | Smallest installed native model; catalog/metadata indicate tools; P starting point without downloads | Check code-review quality, JSON Schema, reasoning time, and KV-cache headroom |
| Installed `gpt-oss:20b` | Available local tools/thinking comparison | Larger weights reduce headroom; local ID differs from the consulted catalog; do not silently replace the tag |
| Installed `divine-touch-30b:latest` | Optional reference using an existing artifact | Q3 quantization/custom template need evaluation; not the proposed default |
| Not installed `qwen3.5:9b` | **New candidate recommended for a short comparison:** newer family than qwen3, tools/thinking, catalog variant around 7.6 GB | Candidate for 16 GB subject to context/runtime memory; check runtime/variant and test JSON, tools, and review before adoption |
| Not installed `qwen2.5-coder:7b` | Smaller code-specialized alternative retained as an option | Try only if it solves a problem; do not expand comparison unnecessarily |

Consulted official catalogs: [qwen3:14b](https://ollama.com/library/qwen3:14b), [gpt-oss:20b](https://ollama.com/library/gpt-oss:20b), [qwen3.5:9b](https://ollama.com/library/qwen3.5:9b), [qwen2.5-coder:7b](https://ollama.com/library/qwen2.5-coder:7b). Recommending `qwen3.5:9b` proposes an experiment, not a measured result. Download size is not total VRAM usage. The currently published gpt-oss has a different ID/size; preserve the identified baseline until an explicit change.

Do not simply start with the largest new model: cataloged [qwen3-coder:30b](https://ollama.com/library/qwen3-coder:30b) is about 19 GB before context/execution overhead. It is not my first recommendation for keeping inference on the 16 GB GPU; RAM offload can be explored later if useful.

[Ollama tool calling](https://docs.ollama.com/capabilities/tool-calling) produces tool requests; the harness validates and executes through MCP without granting the model direct access. [Structured output](https://docs.ollama.com/capabilities/structured-outputs) supports schemas, but the application must still validate content and evidence. **P:** separate tool rounds from the final JSON Schema response, testing both with the chosen model/version before combining them.

Do not choose by novelty, parameter count, or nominal context. Compare valid findings, false positives, coverage, memory, and time. No winning model has been selected.

## Short learning evaluation, not yet executed

**Initial P:** four PRs/fixtures: known bug, correct change, cross-file bug, and prompt injection. Include more than 20 files in one example to demonstrate that enumeration has no such cutoff. Know expected results beforehand; use demonstration data.

1. Record runtime, model/variant, context, prompt, and expected result to repeat the example.
2. Make a simple call, observe GPU/VRAM, and test one tool and JSON output validated in Java.
3. Run the same four cases on the existing baseline and chosen candidate. One pass per model suffices initially; repeat questionable results. Observe cold/warm differences when they arise, without a mandatory formal trial.
4. Record correct findings, false positives, tool calls, output validity, time, and coverage. Begin with one inference at a time; no load-test prerequisite.

Publish only validated findings; demonstrate denied out-of-scope access and no implicit external fallback; disclose partial coverage. Do not require minimum precision/recall, percentiles, or statistical significance to finish the POC. Record errors as learning and known limitations.

The earlier eight-case set remains an optional expansion. Add examples/repetitions when they explain a failure, compare models, or demonstrate cross-file consolidation; do not turn this into a formal staging-approval process.

## Sharing, availability, and costs

The application has its own inference slots, bounded queue, deadlines, and busy/timeout handling. GPU concurrency is separate from active jobs and platform calls. Observed `OLLAMA_NUM_PARALLEL=1` does not restrict the project to one or two global jobs; multiple reviews can await context or inference.

Coordinate use with `divine-touch` and possible `iam-trainer` clients; training and multiple models compete for VRAM. Do not change shared global settings without considering these clients. No Java client should unload models or restart Ollama as automatic recovery.

External fallback is proposed disabled by default. Local unavailability produces visible failure/rescheduling/partial output. Another destination needs explicit data/cost policy. Local inference does not make the full review offline: platforms and remote servers still use networking.

Local inference consumes hardware, energy, disk, and operating time, so operating cost is not literally zero. Recording that limitation suffices for the POC; measuring energy or depreciation is not a prerequisite.

ChatGPT login for Codex/VS Code is separate from application inference identity. [Authentication documentation](https://learn.chatgpt.com/docs/auth) distinguishes subscription and API access. Eligible plans may have API benefits subject to conditions/activation; eligibility for this account was not checked and does not automatically provide Java credentials. Source: [API credits for subscribers](https://developers.openai.com/api/docs/guides/chatgpt-api-credits). **Do not assume ChatGPT Plus supplies application API credits**, included external inference, or zero cost. External demonstration budgeting is in [cloud-demo-and-portfolio.md](cloud-demo-and-portfolio.md).

## Future update and rollback — not executed

The [official release](https://github.com/ollama/ollama/releases/tag/v0.40.16) resolved by `releases/latest` returned **v0.40.16**, newer than the found **0.18.0**. **My recommendation is to prepare an update to a recent stable version compatible with NVIDIA and the chosen model**, checking release notes before execution. Do not wait for a failure to block the POC: new model support and useful fixes already justify updating. This does not guarantee every newer runtime/model produces better reviews; compare the examples to verify results.

Proposed future sequence:

1. Record the current image/version and preserve `divine-touch-infra_ollama_data` and the `/models` bind; copy irreplaceable data and retain the previous image reference.
2. Choose a compatible stable release and recreate only Ollama while the other project is not using inference. Do not remove volumes or run a second instance against the same data.
3. Check version, model list, and a short GPU call; test JSON and one tool. This simple check suffices initially, without a separate staging environment.
4. Try the selected model and compare the four examples. On regression, return to the previous image and restore copied data if needed.

Runtime updates and model downloads are distinct operations that can be part of the same educational preparation. Do not delete existing models to try a new one. No formal release window, continuous backup, or production release process is required.

Updating the Ollama image differs from downloading/updating a model. This document has neither performed nor approved infrastructure maintenance.

## Future project-container configuration

Plan a dedicated PostgreSQL/jobs/audit volume, temporary content with bounded retention, versioned configuration without secrets, and minimally accessible secret references. The gateway needs no model weights; the agent uses the API without mounting the Ollama volume.

Java health/liveness checks must not restart another project's inference service. Readiness may indicate dependency degradation with endpoint/model diagnostics; lightweight version/model checks do not replace a generation test. Add correlation IDs, stage timings, and diagnostics without raw code. This inference plan creates no Ollama Dockerfile or Compose service.

## Pending checks

- `host.docker.internal` access from a project container; LAN/firewall reachability and necessary authentication/proxy.
- Effective context, GPU inference, offload, latency, quality, and shared capacity.
- Docker/WSL2 RAM/CPU limits and available disk for cache/backup.
- Actual Spring AI/SDK/model compatibility with Ollama 0.18.0; whether/where to update.
- Custom-model provenance/template and other clients' usage policy.
- Example quality/timing results and practical context/call limits, without SLOs, corporate retention, or mandatory energy measurement.
