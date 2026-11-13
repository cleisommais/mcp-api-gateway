# Local inference and isolated infrastructure

[Index](../README.md) · [F2 guide](f2-local-pr-review.md) · [Decisions](decisions.md).

## Current environment — 2026-11-13

The user requested independent Ollama and PostgreSQL for this project (D20), replacing the earlier proposal to reuse `divine-touch`. [compose.yaml](../compose.yaml) owns the services, network, and two named volumes. No other project's service, model directory, or database is mounted or changed.

| Item | Observed configuration |
| --- | --- |
| GPU | NVIDIA GeForce RTX 5060 Ti, 16,311 MiB total |
| Host driver | 576.88; successful inference observed with this version |
| CPU/RAM | Prior inspection: Ryzen 9 5950X, 16 cores/32 threads, approximately 63.9 GiB physical RAM |
| Ollama | 0.40.16, `ollama/ollama:0.40.16` |
| Model | `qwen3.5:9b`, 9.0B, Q4_K_M, qwen35, llamacpp |
| Model digest | `56671c2ab9385f9cfcb404638e32cd62d88e3501d44822208363c010179a3c90` |
| Capabilities returned by `/api/show` | completion, vision, tools, thinking; F2 uses text only |
| Inference context | Requested `num_ctx=8192`; `ollama ps` observed 8192 |
| Offload | `ollama ps`: **100% GPU**, 5.9 GB allocated model entry |
| GPU memory at a post-review observation | 8,305 MiB used across the GPU; includes other host usage, not an isolated model measurement |
| Parallelism | One inference, one loaded model, four queued requests; 10-minute keep-alive |
| PostgreSQL | 17.6-alpine; healthy `mcp_review` database; reserved for F4 |
| Host ports | Ollama `127.0.0.1:11435`; PostgreSQL `127.0.0.1:15432` |
| Java endpoint | `http://ollama:11434` on the project's Compose network |
| Persistent volumes | `mcp-api-gateway_ollama_data`, `mcp-api-gateway_postgres_data` |

The [model catalog](https://ollama.com/library/qwen3.5:9b) suggested a manageable candidate for 16 GB. Catalog download size, model weights, the `ollama ps` allocation, and total GPU memory are different measurements. The actual GPU call, native tool-call probe, and four reviews support adopting this as the F2 starting point. No comparison with another model was performed in the isolated environment; that remains an F6 experiment if useful. Newness alone does not establish quality.

## Setup, maintenance, and separation

Follow the [F2 commands](f2-local-pr-review.md). The initialization script generates a random local database password in ignored `.env`; no GitHub or inference credentials are required for the public fixture exercise. The Java container mounts only the report directory, not either data volume or the Docker socket.

The image was selected from the [Ollama stable release](https://github.com/ollama/ollama/releases/tag/v0.40.16). NVIDIA access uses Docker Compose [GPU reservations](https://docs.docker.com/compose/how-tos/gpu-support/). Version/model queries do not prove GPU inference; the observed `ollama ps` result does. Other projects can still compete for the same physical GPU despite separate containers and volumes.

For a future update, change `OLLAMA_VERSION` deliberately, preserve this project's volume, recreate only its Ollama service, and repeat version/model, GPU, JSON, and tool checks. Keep the previous image reference for rollback. Do not update or delete another project's models. Application inference calls never pull models, recreate containers, or fall back to an external provider.

`docker compose down` stops this project's services and preserves named volumes. `docker compose stop` preserves containers too. Volume deletion is unnecessary for routine shutdown and would remove the model/database.

## Context and review quality

The JDK HTTP client calls Ollama's [native chat API](https://docs.ollama.com/api/chat), with streaming disabled, temperature zero, thinking disabled, an explicit context, and at most 1,536 output tokens. Ollama's [JSON Schema output](https://docs.ollama.com/capabilities/structured-outputs) is followed by Java field/type/path/line/evidence checks. A separate [native tool-call probe](https://docs.ollama.com/capabilities/tool-calling) returned the requested name and exact argument; it does not execute a tool. The actual review harness retrieves context deterministically through MCP.

F2 reserves 2,500 context tokens for policy/schema/output and bounds serialized input to one UTF-8 byte per remaining token, conservatively for the English/Java examples. This is a byte heuristic, not a model tokenizer or proof for arbitrary languages. Whole patches are batched without a file-count cutoff. Oversized/missing/truncated patches and exhausted batch budgets remain explicitly pending. Additional full-file context is read at the immutable SHA, limited to four files per batch, and omitted with a warning if it does not fit. All selected patches remain included.

The [evaluation](f2-local-pr-review.md#observed-results) records two prompt iterations. The first correctly identified bugs but returned invalid locations in two cases; the validator rejected them. Supplying an added-line mapping fixed the measured examples. Severity was overestimated as CRITICAL, and the cross-file explanation mixed the unit mismatch with decimal truncation. These are recorded model limitations. Four synthetic cases establish a learning baseline, not general accuracy, security, or performance guarantees.

A timed-out client call may leave inference briefly running on the server. F2 records the failure, closes its MCP subprocess, and performs no retry or service restart. Ollama's bounded queue and single-inference setting are local experiment defaults; multiple manual invocations are not a durable worker queue.

## Historical inventory — 2026-11-12

The earlier read-only inspection found Ollama 0.18.0 in `divine-touch-infra`, with `qwen3:14b` (9.3 GB), `gpt-oss:20b` (13 GB), and a custom `divine-touch-30b:latest` (13 GB). No model was loaded during that inventory, so GPU inference and effective context were then unverified. The old reuse/host-network proposals are superseded by D20. Those models and volumes were not imported, updated, or removed for F2. The user stopped the other project's service independently.

## Costs and later work

Local inference avoids external inference charges but still uses disk, electricity, and hardware time; no energy accounting is required for this POC. A desktop-independent demonstration will use an explicitly selected external API on a temporary VPS, following the [cloud plan](cloud-demo-and-portfolio.md). ChatGPT/Codex account access is separate from Java application credentials and is not used as an inference API credential.

F3 adds the governed MCP Gateway. F4 adds webhooks, PostgreSQL jobs, GitHub App identity, and controlled publication. Effective capacity under concurrent workloads, a second model, finer patch splitting, arbitrary-language tokenization, and severity calibration remain later experiments rather than prerequisites for this manual increment.
