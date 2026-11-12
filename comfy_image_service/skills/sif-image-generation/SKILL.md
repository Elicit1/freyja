---
name: sif-image-generation
description: Generate or edit images through the configured SIF MCP server or CLI. Use for requests targeting this self-hosted service, with pro, standard, or fast profiles, reference-image editing, queued rendering, and image downloads.
---

# SIF Image Generation

Prefer the configured **SIF MCP server** for tool discovery and generation. It exposes `get_capabilities`, `get_readiness`, `text_to_image`, `image_to_image`, `upload_image`, `get_job`, `get_queue`, and `get_result`. Read [references/mcp.md](references/mcp.md) for connection setup, input uploads, and the asynchronous tool sequence.

For shell automation or local image uploads/downloads, use the dependency-free Python CLI in `scripts/generate.py`. It uploads inputs, submits a task, waits, downloads the image, and prints JSON with the output path, tier, seed, and effective prompt.

Read [references/api.md](references/api.md) for all parameters, CLI/HTTP usage, and task recovery. Do not print API tokens or put them in prompts. If the MCP server is missing or returns 401, ask the user to configure the SIF endpoint and a console-created API key; installing this Skill does not install an MCP connection or provision credentials.

## Select the operation and profile

- **t2i** creates a new image from a description. Choose the aspect ratio for its intended use.
- **i2i** edits an existing image. Supply the actual file with `--image`, refer to it as `<Picture 1>`, and describe both the requested changes and what must remain. The original aspect ratio is preserved.
- **standard** uses W4A8 and is the default for routine generation and editing.
- **pro** uses INT8, for detail-sensitive final candidates or an explicit request for this profile. Precision does not guarantee a superior result for every prompt.
- **fast** uses Q4_K_M GGUF, for drafts, iteration, or a smaller weight footprint. Its name does not guarantee lower latency on every GPU or cache state.

Respect the user's explicit profile. Start ordinary work with `standard`, 4 MP, and prompt expansion disabled by explicitly passing `--no-expand`. Use lower MP for requested previews; increase toward 16–20 MP when the requested dimensions need it and the backend can run it. Do not silently downgrade a failed request or generate an unspecified number of alternatives.

For now, recommend expansion OFF for both t2i and i2i, including short or Chinese descriptions. MCP tools default to `expand_prompt=false`. Pass `--no-expand` in CLI calls, or `"expand_prompt": false` in REST requests. Enable expansion only when the user explicitly requests it. The underlying REST service and CLI defaults remain ON, so do not omit the CLI disabling flag accidentally. Sampling is fixed at six steps and CFG 1; do not invent steps, CFG, negative-prompt, or denoise parameters. Keep the tier values exactly `pro`, `standard`, and `fast`, without a service-name prefix.

## Command-line use

The user creates a named `sk-` API key after signing in to the SIF console. Configure it in the MCP client, or use `SIF_BASE_URL` and `SIF_API_KEY` / an explicit `--config /path/to/private-client.json` for CLI calls. Do not read the server environment, Docker logs, or server database to obtain credentials; do not create a key or change the administrator password on the user's behalf unless asked. The client no longer searches the repository for a private connection file. Run `capabilities` and `readiness` on first use or after a service change.

```bash
python scripts/generate.py capabilities
python scripts/generate.py t2i --tier standard --prompt "A red ceramic teapot beside a sunlit window" --mp 4 --aspect-ratio 16:9 --no-expand --output teapot.png
python scripts/generate.py i2i --tier pro --image source.png --prompt "Edit <Picture 1>. Change the teapot to cobalt blue; preserve its shape, framing, and lighting." --mp 4 --no-expand --output edited.png
```

Adapt script paths to the installed Skill directory. In the source repository use `python comfy_image_service/generate.py ...`.

## Completion and recovery

SIF runs one render at a time; extra jobs wait in its persistent FIFO queue. Return and preserve job IDs and queue positions. A polling timeout does not cancel a job. An unresolved backend submission pauses later tasks until completion is known or the administrator explicitly resolves it in the console.

Report success only after the job reaches `succeeded`; if the user requested a local file, also verify a real downloaded image path. Download result URLs with the same API key. Display or link the image using the host application's local-file rendering. The 20 MP parameter limit does not prove the backend can render at that size.

On a polling timeout or `unknown` status, retain the job ID and use `status JOB_ID --output result.png`. The render may still be running; do not start a replacement just because the connection was lost. The CLI retries ambiguous submissions only with the identical request and idempotency key. Reuse that key when recovering a lost submission response manually.

For backend failures, inspect the error and readiness results. Resolve missing files, nodes, or capacity issues before retrying. Ask before changing an explicit profile, resolution, requested edit, or generating extra alternatives.
