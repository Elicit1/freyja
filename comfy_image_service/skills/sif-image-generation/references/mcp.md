# SIF MCP

SIF provides Streamable HTTP at `http://127.0.0.1:8011/mcp`. Every request, including discovery and notifications, requires `Authorization: Bearer sk-…`. Browser administrator sessions do not authenticate MCP calls.

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
| `get_job` | Job state, queue position, backend ID, errors, and results |
| `get_queue` | Current render, waiting jobs, and unresolved submissions blocking the queue |
| `get_result` | Job state and authenticated full-image download URLs |

Generation tools default to `standard`, 4 MP, **expansion OFF**. Tiers remain `pro`, `standard`, `fast`; MP range is 0.1–20. Read [api.md](api.md) for aspect ratios, file limits, and fixed sampling.

Choose a unique `idempotency_key` for each intended render. After losing a submission response, reuse that key with identical arguments. On a successful submission, preserve the returned `id` and poll `get_job` at reasonable intervals (e.g. every 2–5 seconds). Queued jobs do not need resubmission. An `unknown` job may still be running and blocks later renders. Resolve it through the existing job or ask the operator to inspect it; do not bypass the queue.

For i2i, supply an upload ID or image data URI. A Windows path is not readable by the Docker server. Upload binary image bytes via `POST /v1/uploads` using the same API key, use the CLI's `--image` path, or send base64 bytes to `upload_image`. Do not put an entire large image into model context just to re-encode it.

Result URLs are authenticated. Use an HTTP client with the configured API key or the CLI's `status JOB_ID --output result.png` to download on the agent's machine. A container-local path or an unauthenticated Markdown image URL is not a completed local download.
