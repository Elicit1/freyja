"""Authenticated Streamable HTTP tools; generation always uses SIF's single queue."""
import base64
from typing import Annotated, Any, Literal

from mcp.server import MCPServer
from mcp.server.transport_security import TransportSecuritySettings
from pydantic import Field

from .models import GenerateRequest


def create_mcp(state, settings):
    mcp = MCPServer("SIF", log_level="WARNING", instructions="Generate or edit images through SIF. Use standard for routine work, pro for INT8, fast for Q4_K_M. Tasks run serially; poll get_job instead of submitting a replacement. Prompt expansion defaults OFF. Images require the same Bearer API key to download.")

    @mcp.tool(structured_output=True)
    def get_capabilities() -> dict[str, Any]:
        """List available t2i/i2i apps, pro/standard/fast tiers, MP limits, and fixed sampling."""
        return state.service.capabilities()

    @mcp.tool(structured_output=True)
    async def get_readiness() -> dict[str, Any]:
        """Check backend nodes and model files; this does not prove 20 MP fits in GPU memory."""
        return await state.service.readiness()

    @mcp.tool(structured_output=True)
    async def text_to_image(
        prompt: Annotated[str, Field(min_length=1, max_length=32000)],
        idempotency_key: Annotated[str, Field(min_length=1, max_length=128, description="A unique ID for this intended render. Reuse it and identical arguments after a lost response.")],
        tier: Literal["pro", "standard", "fast"] = "standard",
        megapixels: Annotated[float, Field(ge=0.1, le=20)] = 4,
        aspect_ratio: Literal["1:1", "2:3", "3:2", "3:4", "4:3", "9:16", "16:9", "21:9"] = "1:1",
        expand_prompt: bool = False,
        seed: Annotated[int | None, Field(ge=0, le=9007199254740991)] = None,
        app: str = "minimax-h3-image",
    ) -> dict[str, Any]:
        """Queue one text-to-image render. Returns a persisted job ID and queue position; poll get_job until completion."""
        return await state.service.submit("t2i", GenerateRequest(prompt=prompt, tier=tier, megapixels=megapixels,
                                          aspect_ratio=aspect_ratio, expand_prompt=expand_prompt, seed=seed, app=app), idempotency_key)

    @mcp.tool(structured_output=True)
    async def image_to_image(
        prompt: Annotated[str, Field(min_length=1, max_length=32000)],
        image: Annotated[str, Field(description="Upload ID returned by upload_image or POST /v1/uploads, or a base64 image data URI. Never a local path or URL.")],
        idempotency_key: Annotated[str, Field(min_length=1, max_length=128)],
        tier: Literal["pro", "standard", "fast"] = "standard",
        megapixels: Annotated[float, Field(ge=0.1, le=20)] = 4,
        expand_prompt: bool = False,
        seed: Annotated[int | None, Field(ge=0, le=9007199254740991)] = None,
        app: str = "minimax-h3-image",
    ) -> dict[str, Any]:
        """Queue an edit of <Picture 1>, preserving its aspect ratio. Describe requested changes and preservation requirements."""
        return await state.service.submit("i2i", GenerateRequest(prompt=prompt, image=image, tier=tier, megapixels=megapixels,
                                          expand_prompt=expand_prompt, seed=seed, app=app), idempotency_key)

    @mcp.tool(structured_output=True)
    def upload_image(data_base64: Annotated[str, Field(min_length=1, description="Base64-encoded PNG/JPEG/WebP bytes. Prefer REST binary upload for large files.")]) -> dict[str, Any]:
        """Upload a reference image and return an image ID. Limit: 25 MiB, 40 source MP; no server-path access."""
        return state.service.upload(base64.b64decode(data_base64, validate=True))

    @mcp.tool(structured_output=True)
    async def get_job(job_id: str) -> dict[str, Any]:
        """Read a queued/running/completed job and its authenticated image URLs. An unknown job may still be rendering."""
        return await state.service.job(job_id)

    @mcp.tool(structured_output=True)
    def get_queue() -> dict[str, Any]:
        """Show the single-worker queue, current render, waiting positions, and unresolved jobs blocking new renders."""
        return state.service.queue()

    @mcp.tool(structured_output=True)
    async def get_result(job_id: str) -> dict[str, Any]:
        """Return job state, effective prompt, image dimensions, and download URLs. Send your Bearer API key when downloading."""
        return await state.service.job(job_id)

    # The parent app authenticates every request, including tool discovery and notifications.
    # Keep the SDK's localhost Host/Origin checks, matching the service's loopback deployment.
    transport = mcp.streamable_http_app(stateless_http=True, json_response=True,
                                        max_request_body_size=(settings.max_upload_bytes + 2) // 3 * 4 + 65536,
                                        transport_security=TransportSecuritySettings(
                                            allowed_hosts=["127.0.0.1:*", "localhost:*", "[::1]:*"],
                                            allowed_origins=["http://127.0.0.1:*", "http://localhost:*"]))
    return mcp, transport
