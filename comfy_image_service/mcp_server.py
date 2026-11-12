"""Authenticated Streamable HTTP tools; generation always uses SIF's single queue."""
import asyncio
import base64
from typing import Annotated, Any, Literal

from mcp.server import MCPServer
from mcp.server.mcpserver import Context
from mcp.server.transport_security import TransportSecuritySettings
from pydantic import Field

from .models import GenerateRequest, PromptExpansion


def create_mcp(state, settings):
    mcp = MCPServer("SIF", log_level="WARNING", instructions="Generate or edit images with English prompts. When settings are unspecified, prefer fast, 2K (2048x1152, 2.25 MP), 16:9 for t2i; preserve the source ratio for i2i. Prompt expansion defaults OFF. Every API/MCP request and image download requires the configured Bearer API key. Tasks run serially. After submission, call wait_for_job with the returned job ID instead of sleeping or repeatedly calling get_job. If wait_timed_out is true, call wait_for_job again with the SAME job ID; never resubmit the render because waiting timed out. Unknown jobs need backend inspection.")

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
        megapixels: Annotated[float | None, Field(ge=0.1, le=20)] = None,
        aspect_ratio: Annotated[str | None, Field(max_length=64, description="W:H between 1:3 and 3:1. Omit with explicit width/height.")] = None,
        expand_prompt: bool = False,
        seed: Annotated[int | None, Field(ge=0, le=9007199254740991)] = None,
        app: str = "minimax-h3-image",
        width: Annotated[int | None, Field(ge=32, le=16384, multiple_of=32)] = None,
        height: Annotated[int | None, Field(ge=32, le=16384, multiple_of=32)] = None,
        n: Annotated[int, Field(ge=1, le=10)] = 1,
        output_format: Literal["png", "jpeg", "webp"] = "png",
        output_quality: Annotated[int, Field(ge=0, le=100)] = 95,
        prompt_expansion: PromptExpansion | None = None,
    ) -> dict[str, Any]:
        """Queue 1–10 serial text-to-image variants. Use MP + custom W:H or exact 32-aligned width/height, up to 20 MP. Preserve the returned ID, then call wait_for_job."""
        size = {} if megapixels is None else {"megapixels": megapixels}
        return await state.service.submit("t2i", GenerateRequest(prompt=prompt, tier=tier, **size,
                                          aspect_ratio=aspect_ratio, expand_prompt=expand_prompt, seed=seed, app=app,
                                          width=width, height=height, n=n, output_format=output_format,
                                          output_quality=output_quality, prompt_expansion=prompt_expansion), idempotency_key)

    @mcp.tool(structured_output=True)
    async def image_to_image(
        prompt: Annotated[str, Field(min_length=1, max_length=32000)],
        idempotency_key: Annotated[str, Field(min_length=1, max_length=128)],
        image: Annotated[str | None, Field(description="Single MinIO reference URL, upload ID or data URI. Supply image or images.")] = None,
        tier: Literal["pro", "standard", "fast"] = "standard",
        megapixels: Annotated[float | None, Field(ge=0.1, le=20)] = None,
        expand_prompt: bool = False,
        seed: Annotated[int | None, Field(ge=0, le=9007199254740991)] = None,
        app: str = "minimax-h3-image",
        images: Annotated[list[str] | None, Field(min_length=1, max_length=16, description="Ordered MinIO image URLs or upload IDs. Upload bytes via authenticated POST /v1/uploads and pass its returned URL. Temporary uploads expire after 24 hours. First image is <Picture 1>.")] = None,
        aspect_ratio: Annotated[str | None, Field(max_length=64, description="original (first reference) or numeric W:H between 1:3 and 3:1. Omit with width/height.")] = None,
        width: Annotated[int | None, Field(ge=32, le=16384, multiple_of=32)] = None,
        height: Annotated[int | None, Field(ge=32, le=16384, multiple_of=32)] = None,
        reference_mode: Literal["latent", "vision"] = "latent",
        n: Annotated[int, Field(ge=1, le=10)] = 1,
        output_format: Literal["png", "jpeg", "webp"] = "png",
        output_quality: Annotated[int, Field(ge=0, le=100)] = 95,
        prompt_expansion: PromptExpansion | None = None,
    ) -> dict[str, Any]:
        """Edit or compose using up to 16 separate ordered references. Name each <Picture N> and its purpose in English. Choose original/custom ratio or exact canvas, latent/vision conditioning, optional expansion, and 1–10 serial variants. Then call wait_for_job."""
        size = {} if megapixels is None else {"megapixels": megapixels}
        return await state.service.submit("i2i", GenerateRequest(prompt=prompt, image=image, images=images, tier=tier, **size,
                                          expand_prompt=expand_prompt, seed=seed, app=app, aspect_ratio=aspect_ratio,
                                          width=width, height=height, reference_mode=reference_mode, n=n,
                                          output_format=output_format, output_quality=output_quality,
                                          prompt_expansion=prompt_expansion), idempotency_key)

    @mcp.tool(structured_output=True)
    async def upload_image(data_base64: Annotated[str, Field(min_length=1, description="Legacy base64 upload. Prefer authenticated HTTP multipart/binary POST /v1/uploads and pass its returned MinIO URL to generation.")]) -> dict[str, Any]:
        """Upload a reference image and return an image ID. Limit: 25 MiB, 40 source MP; no server-path access."""
        return await asyncio.to_thread(state.service.upload, base64.b64decode(data_base64, validate=True))

    @mcp.tool(structured_output=True)
    async def get_job(job_id: str) -> dict[str, Any]:
        """Read current state once for inspection. Use wait_for_job to await completion without client-side sleep loops."""
        return await state.service.job(job_id)

    @mcp.tool(structured_output=True)
    async def wait_for_job(
        job_id: Annotated[str, Field(description="The existing persisted SIF job ID returned by generation. Never submit a replacement to wait.")],
        ctx: Context,
        timeout_seconds: Annotated[float, Field(ge=1, le=600, description="Server-side wait deadline. Default 25 seconds; choose less than the MCP client's tool timeout.")] = 25,
    ) -> dict[str, Any]:
        """Wait on the server until completion, failure, unknown status, or deadline; stream status/elapsed-time notifications when supported. On wait_timed_out=true, call again with the SAME job_id, without sleep. Disconnecting or cancelling this wait never cancels or resubmits the render."""
        async def progress(elapsed, message):
            await ctx.report_progress(elapsed, message=message)
        return await state.service.wait_for_job(job_id, timeout_seconds, progress)

    @mcp.tool(structured_output=True)
    def get_queue() -> dict[str, Any]:
        """Show the single-worker queue, current render, waiting positions, and unresolved jobs blocking new renders."""
        return state.service.queue()

    @mcp.tool(structured_output=True)
    async def get_result(job_id: str, response_format: Literal["url", "b64_json"] = "url") -> dict[str, Any]:
        """Return job state, effective prompt, image dimensions, and download URLs. Send your Bearer API key when downloading."""
        return await state.service.result(job_id, response_format)

    # The parent app authenticates every request, including tool discovery and notifications.
    # Keep the SDK's localhost Host/Origin checks, matching the service's loopback deployment.
    transport = mcp.streamable_http_app(stateless_http=True, json_response=False,
                                        max_request_body_size=(settings.max_upload_bytes + 2) // 3 * 4 + 65536,
                                        transport_security=TransportSecuritySettings(
                                            allowed_hosts=["127.0.0.1:*", "localhost:*", "[::1]:*"],
                                            allowed_origins=["http://127.0.0.1:*", "http://localhost:*"]))
    return mcp, transport
