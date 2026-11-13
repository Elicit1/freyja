# SIF Client over REST

SIF Client is a standalone Python 3.10+ CLI. Install the separate `sif_client` project on the agent's machine.

```bash
python -m pip install /path/to/sif_client
sif configure --base-url http://127.0.0.1:8011
```

The user creates a Key in the Server console, then pastes it into the hidden prompt once. Future commands load the saved URL and Key automatically from `~/.sif/config.json`. The file contains the plaintext credential. POSIX files use mode `0600`; Windows uses the user directory's ACL. `sif config` reports the path, URL, and whether a Key is saved, without displaying the credential. `sif logout` removes the saved Key. Environment overrides continue to apply.

Use `sif --config /private/client.json configure --base-url URL` to create another configuration. Global `--config` and `--base-url` options precede the subcommand. `configure` also accepts `--base-url` after its name and `--api-key-stdin` to read one line from standard input.

Configuration path precedence is `--config`, `SIF_CONFIG`, `COMFY_IMAGE_CONFIG`, then the home-directory default. Explicit missing configurations fail ordinary calls. `configure` can create them. Request URL precedence is `--base-url`, `SIF_BASE_URL`, `COMFY_IMAGE_BASE_URL`, saved URL, then `http://127.0.0.1:8011`. Key precedence is `SIF_API_KEY`, `COMFY_IMAGE_API_KEY`, then the saved Key. Configure writes the entered Key and selected URL to the chosen file. Keep credentials out of command arguments and prompts.

```bash
sif capabilities
sif readiness
sif upload reference.png
sif t2i --tier fast --prompt "A red ceramic teapot beside a sunlit window" --width 2048 --height 1152 --no-expand --output teapot.png
sif i2i --tier fast --image reference.png --prompt "Edit <Picture 1>. Change the teapot to cobalt blue. Preserve its shape and lighting." --mp 2.25 --no-expand --output edited.png
sif queue
sif jobs
sif status JOB_UUID --output recovered.png
```

For another input ratio, calculate MP for the requested long-edge size. Preserve explicit user settings. See [api.md](api.md) for the full generation parameter contract.

The CLI uses REST for capabilities, readiness, uploads, submission, event-backed waiting and image downloads. It sends the saved Bearer Key automatically. Server-side rendering remains in the persistent queue. Recover a disconnected job with `status`; recover a lost submission response with the same idempotency key and identical parameters.

From a source checkout, `python sif_client/generate.py ...` works without installation. Installed clients also support `python -m sif_client ...`. The Skill's `scripts/generate.py` is a compatibility launcher requiring the separately installed Client, or the sibling source project in a full checkout.

MCP hosts keep their own connection configuration for `/mcp`. Saving CLI credentials configures REST commands. Read [mcp.md](mcp.md) when using MCP tools.
