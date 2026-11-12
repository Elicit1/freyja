---
name: sif-image-generation
description: Generate or edit images through the configured SIF MCP server or CLI. Use for requests targeting this self-hosted service, with pro, standard, or fast profiles, reference-image editing, queued rendering, and image downloads.
---

# SIF Image Generation

Prefer the configured **SIF MCP server** for tool discovery and generation. It exposes `get_capabilities`, `get_readiness`, `text_to_image`, `image_to_image`, `upload_image`, `wait_for_job`, `get_job`, `get_queue`, and `get_result`. Read [references/mcp.md](references/mcp.md) for connection setup, input uploads, and server-side waiting.

For shell automation or local image uploads/downloads, use the dependency-free Python CLI in `scripts/generate.py`. It uploads inputs, submits a task, waits, downloads the image, and prints JSON with the output path, tier, seed, and effective prompt.

Read [references/api.md](references/api.md) for all parameters, CLI/HTTP usage, and task recovery. Do not print API tokens or put them in prompts. If the MCP server is missing or returns 401, ask the user to configure the SIF endpoint and a console-created API key; installing this Skill does not install an MCP connection or provision credentials.

## Prompt language and authentication

Write every t2i and i2i `prompt` in **English**, whether calling MCP, REST, or the CLI. Translate a Chinese or other non-English request into English before submitting it, preserving the requested subject, composition, edits, and preservation requirements. Keep reference markers such as `<Picture 1>` unchanged. If the user requests exact lettering inside the image, preserve that literal text in its requested language while writing the surrounding instructions in English. Translate the prompt yourself; do not enable prompt expansion just to translate it.

**All SIF API and MCP calls require an API key.** Send `Authorization: Bearer sk-…` with every request, including MCP initialization, discovery and notifications, capabilities, readiness, uploads, queue/job polling, result/image downloads, health, API documentation, and Skill downloads. Configure authentication in the MCP client or CLI so it is attached automatically. Do not try unauthenticated probes, put the key in tool arguments or URLs, or treat a returned image URL as public. If no valid key is configured, ask the user to configure one before calling the service. Browser password login and administrator sessions are separate from these Agent interfaces.

## Select the operation and profile

- **t2i** creates a new image from a description. Use `16:9` unless the user specifies another ratio or dimensions.
- **i2i** edits an existing image. Supply the actual file with `--image`, refer to it as `<Picture 1>`, and describe both the requested changes and what must remain. The original aspect ratio is preserved.
- **fast** uses Q4_K_M GGUF and is the recommended profile when the user does not specify one. Its name does not guarantee lower latency on every GPU or cache state.
- **standard** uses W4A8; select it when the user requests this profile or W4A8.
- **pro** uses INT8; select it when the user requests this profile or INT8. Precision does not guarantee a superior result for every prompt.

When the user leaves settings unspecified, recommend **fast, 2K, 16:9**, with expansion OFF. Here 2K means a 2048-pixel long edge; the default t2i canvas is **2048×1152**, corresponding to **2.25 MP** under SIF's `1 MP = 1024×1024` definition. Explicitly send `tier="fast"`, `megapixels=2.25`, `aspect_ratio="16:9"`, and `expand_prompt=false`, or pass `--tier fast --mp 2.25 --aspect-ratio 16:9 --no-expand` in the CLI. The underlying service/CLI defaults differ, so do not omit these parameters and assume they match this recommendation.

Apply defaults only to missing settings: preserve any profile, dimensions, resolution, or aspect ratio the user specifies. For a different explicit ratio with no size request, target a 2048-pixel long edge and calculate the area. For explicit dimensions, derive the ratio and `megapixels = width × height / (1024 × 1024)`. For i2i, keep the input ratio; if size is unspecified, derive MP from the uploaded dimensions to target a 2048-pixel long edge instead of forcing 16:9. Do not silently downgrade a failed request or generate an unspecified number of alternatives.

For now, recommend expansion OFF for both t2i and i2i, including short descriptions. MCP tools default to `expand_prompt=false`. Pass `--no-expand` in CLI calls, or `"expand_prompt": false` in REST requests. Enable expansion only when the user explicitly requests it. The underlying REST service and CLI defaults remain ON, so do not omit the CLI disabling flag accidentally. Sampling is fixed at six steps and CFG 1; do not invent steps, CFG, negative-prompt, or denoise parameters. Keep the tier values exactly `pro`, `standard`, and `fast`, without a service-name prefix.

## Command-line use

The user creates a named `sk-` API key after signing in to the SIF console. Configure it in the MCP client, or use `SIF_BASE_URL` and `SIF_API_KEY` / an explicit `--config /path/to/private-client.json` for CLI calls. Do not read the server environment, Docker logs, or server database to obtain credentials; do not create a key or change the administrator password on the user's behalf unless asked. The client no longer searches the repository for a private connection file. Run `capabilities` and `readiness` on first use or after a service change.

```bash
python scripts/generate.py capabilities
python scripts/generate.py t2i --tier fast --prompt "A red ceramic teapot beside a sunlit window" --mp 2.25 --aspect-ratio 16:9 --no-expand --output teapot.png
# Example i2i input: 16:9. For other source ratios, derive MP for a 2048-pixel long edge.
python scripts/generate.py i2i --tier fast --image source.png --prompt "Edit <Picture 1>. Change the teapot to cobalt blue; preserve its shape, framing, and lighting." --mp 2.25 --no-expand --output edited.png
```

Adapt script paths to the installed Skill directory. In the source repository use `python comfy_image_service/generate.py ...`.

## Completion and recovery

After submitting a render, preserve the returned `id` and call **`wait_for_job(job_id, timeout_seconds=25)`**. SIF waits for job-state events and returns the result; compatible clients also receive streamed status and elapsed-wait notifications. **Do not call shell/clock sleep tools or repeatedly poll `get_job` while `wait_for_job` is available.** Use `get_job` for a one-time inspection or to recover a lost submission response.

If `wait_timed_out=true` and the job remains queued/running, call `wait_for_job` again with the **same job ID**, without an extra sleep or a new submission. Keep each wait deadline below the host's tool-call timeout; the server accepts up to 600 seconds when the host allows it. Cancellation or disconnection of a wait does not cancel the render. Progress notifications do not guarantee the host will extend its timeout or wake the model after the call ends.

SIF runs one render at a time; extra jobs wait in its persistent FIFO queue. Return and preserve job IDs and queue positions. An unresolved backend submission (`unknown`) returns promptly from the waiting tool and pauses later tasks. Inspect the existing job or ask the operator to resolve it; do not repeatedly wait or submit a replacement for an unresolved job.

Report success only after the job reaches `succeeded`; if the user requested a local file, also verify a real downloaded image path. Download result URLs with the same API key. Display or link the image using the host application's local-file rendering. The 20 MP parameter limit does not prove the backend can render at that size.

If the MCP connection times out, resume `wait_for_job` with the same job ID. For CLI recovery/downloads, use `status JOB_ID --output result.png`; the CLI handles its own waiting without Agent-issued sleep calls. The render may still be running; do not start a replacement just because the connection was lost. The CLI retries ambiguous submissions only with the identical request and idempotency key. Reuse that key when recovering a lost submission response manually.

For backend failures, inspect the error and readiness results. Resolve missing files, nodes, or capacity issues before retrying. Ask before changing an explicit profile, resolution, requested edit, or generating extra alternatives.
