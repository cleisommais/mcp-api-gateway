# Local inference and isolated infrastructure

[Index](../README.md) · [F3 guide](f3-governed-gateway.md) · [Model evaluation](f3-model-evaluation.md) · [Decisions](decisions.md).

## Current environment — 2026-11-13

The user requested independent Ollama and PostgreSQL for this project (D20), replacing the earlier proposal to reuse `divine-touch`. [compose.yaml](../compose.yaml) owns the services, network, and two named volumes. No other project's service, model directory, or database is mounted or changed.

| Item | Observed configuration |
| --- | --- |
| GPU | NVIDIA GeForce RTX 5060 Ti, 16,311 MiB total |
| Host driver | 576.88; successful inference observed with this version |
| CPU/RAM | Prior inspection: Ryzen 9 5950X, 16 cores/32 threads, approximately 63.9 GiB physical RAM |
| Ollama | 0.40.16, `ollama/ollama:0.40.16` |
| Selected F3 model | `gpt-oss:20b`, 20.9B, MXFP4, gpt-oss, llamacpp |
| Selected model digest | `f38aa0c53da5f8c49d08c43a99df24ff53167fe68e24664a7777288e7656fdfe` |
| Capabilities returned by `/api/show` | completion, tools, thinking; named levels low/medium/high; medium selected |
| Retained comparison models | `qwen3.5:9b` (9.0B/Q4_K_M), `qwen3:14b` (14.8B/Q4_K_M) |
| Inference context | Requested `num_ctx=8192`; `ollama ps` observed 8192 |
| Selected-model offload | `ollama ps`: **100% GPU**, 12 GB model entry |
| GPU memory during GPT-OSS evaluation | 14,158 MiB used across the GPU; includes other host usage, not an isolated model measurement |
| Parallelism | One inference, one loaded model, four queued requests; 10-minute keep-alive |
| PostgreSQL | 17.6-alpine; F4 uses the `review` schema for durable jobs and a separate `f4_demo` schema for local fixture evidence |
| Host ports | Ollama `127.0.0.1:11435`; PostgreSQL `127.0.0.1:15432`; edge `127.0.0.1:18081`; gateway internal by default, loopback 18080 only with the debugging override |
| Java endpoint | `http://ollama:11434` on the project's Compose network |
| Persistent volumes | `mcp-api-gateway_ollama_data`, `mcp-api-gateway_postgres_data` |

The [Qwen catalog](https://ollama.com/library/qwen3.5:9b) suggested the initial F2 candidate for 16 GB. F2 observed 100% GPU, a 5.9 GB model entry, and 8,305 MiB total GPU use, but its review quality was overstated. F3 compared Qwen size/prompt/reasoning settings and then the user-suggested [GPT-OSS candidate](https://ollama.com/library/gpt-oss:20b). GPT-OSS matched diagnosis/severity on all four captured reference cases and the selected configuration also passed a live gateway review of PR #3. The [evaluation](f3-model-evaluation.md) records failures, limits, hashes, and raw evidence. Devstral was not evaluated because another candidate was not needed to move this POC forward.

Catalog download size, model weights, the `ollama ps` entry, and total GPU memory are different measurements. The GPT-OSS allocation above is observed fit for one 8K-context inference, not arbitrary context/parallelism capacity. Size or novelty alone does not establish quality.

## Setup, maintenance, and separation

Follow the [F3 commands](f3-governed-gateway.md). The initialization script generates missing local database/gateway credentials in ignored `.env` and preserves existing values. No GitHub or inference credential is required for the public fixture exercise; GPT-OSS runs locally in Ollama. The agent mounts only the report directory; the gateway mounts only its read-only administrative registration. Neither mounts a database/model volume or Docker socket.

The image was selected from the [Ollama stable release](https://github.com/ollama/ollama/releases/tag/v0.40.16). NVIDIA access uses Docker Compose [GPU reservations](https://docs.docker.com/compose/how-tos/gpu-support/). Version/model queries do not prove GPU inference; the observed `ollama ps` result does. Other projects can still compete for the same physical GPU despite separate containers and volumes.

For a future update, change `OLLAMA_VERSION` deliberately, preserve this project's volume, recreate only its Ollama service, and repeat version/model, GPU, JSON, and tool checks. Keep the previous image reference for rollback. Do not update or delete another project's models. Application inference calls never pull models, recreate containers, or fall back to an external provider.

`docker compose down` stops this project's services and preserves named volumes. `docker compose stop` preserves containers too. Volume deletion is unnecessary for routine shutdown and would remove the model/database.

## Context and review quality

The JDK HTTP client calls Ollama's [native chat API](https://docs.ollama.com/api/chat), with streaming disabled, temperature zero, an explicit context, and bounded output. The current default uses GPT-OSS medium reasoning and 4,096 output tokens. F2's historical settings were Qwen, thinking false, and 1,536 output tokens. Ollama's [JSON Schema output](https://docs.ollama.com/capabilities/structured-outputs) is followed by Java field/type/path/line/evidence checks. F2's separate [native tool-call probe](https://docs.ollama.com/capabilities/tool-calling) returned the requested name and exact argument; it did not execute a tool and does not establish GPT-OSS tool quality. The actual harness retrieves context deterministically through the F3 MCP Gateway.

The engine bounds serialized input to one UTF-8 byte per available input token, conservatively for the English/Java examples. Historical 1,536-token output reserves 2,500 tokens overall. The 4,096-token default instead reserves output plus 1,000 tokens for policy/schema, leaving 3,096 input bytes at 8K context. This is a byte heuristic, not a model tokenizer or proof for arbitrary languages. Whole patches are batched without a file-count cutoff. Oversized/missing/truncated patches and exhausted batch budgets remain explicitly pending. Additional full-file context is read at the immutable SHA, limited to four files per batch, and omitted with a warning if it does not fit. All selected patches remain included.

The [F2 historical evaluation](f2-local-pr-review.md#observed-results) records two prompt iterations: invalid locations were rejected, and an added-line mapping improved location accuracy. Severity and the cross-file explanation still failed. The [F3 investigation](f3-model-evaluation.md) then separated prompt omissions, inference controls, raw model errors, batching effects, and structural validation limits. The chosen GPT-OSS configuration is a better observed starting point; four synthetic cases do not establish general accuracy, security, or performance guarantees. Every report still requires human review.

A timed-out client call may leave inference briefly running on the server. F2 records the failure, closes its MCP subprocess, and performs no retry or service restart. Ollama's bounded queue and single-inference setting are local experiment defaults; multiple manual invocations are not a durable worker queue.

## Historical inventory — 2026-11-12

The earlier read-only inspection found Ollama 0.18.0 in `divine-touch-infra`, with `qwen3:14b` (9.3 GB), `gpt-oss:20b` (13 GB), and a custom `divine-touch-30b:latest` (13 GB). No model was loaded during that inventory, so GPU inference and effective context were then unverified. The old reuse/host-network proposals are superseded by D20. Those models and volumes were not imported, updated, or removed for F2. The user stopped the other project's service independently.

## Costs and later work

Local inference avoids external inference charges but still uses disk, electricity, and hardware time; no energy accounting is required for this POC. A desktop-independent demonstration will use an explicitly selected external API on a temporary VPS, following the [cloud plan](cloud-demo-and-portfolio.md). ChatGPT/Codex account access is separate from Java application credentials and is not used as an inference API credential.

F3 implements the governed MCP Gateway and model comparison. F4 implements signed edge admission, PostgreSQL jobs, App credential support, and controlled publication; private App reads are verified, while real callback/publication checks remain in the [App guide](f4-github-app-setup.md). [F6](f6-model-providers.md) implements optional candidate verification and compares two installed verifiers with preserved failures; it stays disabled by default. Its approved public external trial selects GPT-6 Luna for the initial cloud demonstration without changing the local default. Concurrent capacity, finer patch splitting, arbitrary-language tokenization, and broader quality cases remain later experiments.
