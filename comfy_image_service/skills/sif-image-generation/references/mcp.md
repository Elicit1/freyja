# SIF MCP

SIF provides Streamable HTTP with SSE responses at `http://127.0.0.1:8011/mcp`. Use an MCP client that accepts both `application/json` and `text/event-stream`; existing REST endpoints continue to return JSON. Every request, including initialization, discovery, notifications, and all tool calls, requires `Authorization: Bearer sk-…`. The same key is required for REST uploads, waiting/recovery, health checks, image/result downloads and Skill downloads. There are no unauthenticated Agent-facing interfaces; configure a key before probing or calling the service. Browser administrator sessions do not authenticate MCP calls.

The user opens `http://127.0.0.1:8011/`, signs in using their administrator password, and creates an API key by name in **API Keys**. The full key is displayed once. The server persists only its hash. Keys can be independently revoked. Do not obtain credentials by reading the server configuration, database, or startup logs.

Example for clients supporting this JSON shape:

```json
{
  "mcpServers": {
    "sif": {
      "url": "http://127.0.0.1:8011/mcp",
      "headers": {"Authorization": "Bearer YOUR_API_KEY"}
    }
  }
}
```

Replace `YOUR_API_KEY` in the client's private configuration with the user-created key. Configuration field names and secret-storage options depend on the client. This is the SIF key, not a Comfy Cloud credential.

An installed Skill and a configured MCP connection are separate. The Skill provides usage guidance; the host application owns the connection and its credential. On HTTP 401, have the user check the key or create a replacement in the console. Reinstalling the Skill, reading the server environment, or using the administrator password as a Bearer token will not resolve authentication.

## Tools

| Tool | Purpose |
| --- | --- |
| `get_capabilities` | Registered apps, profiles, parameter limits, fixed sampling, queue status |
| `get_readiness` | Installed backend nodes and model filenames |
| `text_to_image` | Queue t2i: prompt, required idempotency_key, tier, megapixels, aspect_ratio, expand_prompt, seed, app |
| `image_to_image` | Queue i2i: prompt, image, required idempotency_key, tier, megapixels, expand_prompt, seed, app; original ratio preserved |
| `upload_image` | Upload base64 PNG/JPEG/WebP bytes and return an upload ID; prefer binary REST upload for large files |
| `wait_for_job` | Wait on the server for an existing job; job_id required, timeout_seconds defaults to 25, range 1–600; returns job state plus wait_timed_out and next_action |
| `get_job` | One-time inspection of job state, queue position, backend ID, errors, and results; prefer wait_for_job to wait |
| `get_queue` | Current render, waiting jobs, and unresolved submissions blocking the queue |
| `get_result` | Job state and authenticated full-image download URLs |

When the user does not specify settings, this Skill recommends **fast, 2K, 16:9, expansion OFF**. For t2i, explicitly pass `tier="fast"`, `megapixels=2.25`, `aspect_ratio="16:9"`, and `expand_prompt=false` to target **2048×1152**. The MCP tool's raw defaults remain `standard` and 4 MP; do not rely on omission to obtain the Skill recommendation. Preserve each user-specified setting, and apply defaults only to missing fields. For another ratio or i2i's preserved input ratio, calculate MP for a 2048-pixel long edge when the user leaves size unspecified. Tiers remain `pro`, `standard`, `fast`; MP range is 0.1–20. Read [api.md](api.md) for aspect ratios, file limits, and fixed sampling.

Always write the `prompt` passed to `text_to_image` or `image_to_image` in English. Translate non-English user requests yourself before calling the tool, retaining the intended content and edit constraints. Preserve `<Picture 1>` markers and exact requested image lettering; describe that lettering in English while keeping the quoted text in the requested language. Leave prompt expansion OFF unless the user explicitly requests it.

Choose a unique `idempotency_key` for each intended render. After losing a submission response, reuse that key with identical arguments. On a successful submission, preserve the returned `id` and call `wait_for_job` with that ID. Do not issue sleep calls or schedule repeated `get_job` polling. Queued jobs do not need resubmission.

The waiting call subscribes to committed queue/job-state changes. It can stream `notifications/progress` when the client supplies a progress token; messages report real job state, waiting position, and monotonically increasing elapsed wait time, not an invented GPU completion percentage. Pending waits also send a status update about every ten seconds. Clients decide how to display these notifications.

SIF normally receives backend completion through ComfyUI WebSocket events rather than a fixed sleep/history-poll loop. Reconnecting preserves the original client/job identity and does not submit another render. This backend transport does not change how the Agent connects: keep using authenticated HTTP MCP and `wait_for_job`.

`wait_for_job` returns immediately for `succeeded`, `failed`, or `unknown`. For pending jobs it returns by its deadline, with `wait_timed_out=true` and `next_action="wait_for_job"`. Call the waiting tool again with the same ID and no extra sleep. A `succeeded` result includes authenticated image URLs; `failed` calls for error inspection; `unknown` calls for backend inspection and must not trigger a replacement render. Disconnecting or cancelling a wait only ends that wait, leaving the durable job and the serial worker intact.

Keep `timeout_seconds` below the client's tool-call deadline. Default 25 seconds fits clients with a 30-second tool timeout; use a larger value only when the host permits it. Progress does not universally reset client timeouts. A wait timeout or connection loss must resume the existing job rather than submit again. The server cannot control the host's scheduling after a tool call returns.

For i2i, supply an upload ID or image data URI. A Windows path is not readable by the Docker server. Upload binary image bytes via `POST /v1/uploads` using the same API key, use the CLI's `--image` path, or send base64 bytes to `upload_image`. Do not put an entire large image into model context just to re-encode it.

Result URLs are authenticated. Use an HTTP client with the configured API key or the CLI's `status JOB_ID --output result.png` to download on the agent's machine. A container-local path or an unauthenticated Markdown image URL is not a completed local download.
