"""
Official OpenAI Python SDK Verification Script for ComfyUI Gateway.
Demonstrates:
1. Listing models (GET /v1/models)
2. Pure Text-to-Image (0 reference images) with FLUX.2 Klein 9B
3. Multi-Reference Image Generation (1~N reference images) via extra_body
4. Model switching (e.g. SDXL Base)
5. Base64 format response (response_format="b64_json")
"""

import sys
import json
import logging
from openai import OpenAI

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("test_client")

GATEWAY_BASE_URL = "http://127.0.0.1:8000/v1"
API_KEY = "not-needed"  # Local gateway does not require OpenAI key


def test_openai_sdk_integration():
    logger.info("=" * 60)
    logger.info("Initializing OpenAI Python SDK Client...")
    logger.info(f"Target Gateway: {GATEWAY_BASE_URL}")
    logger.info("=" * 60)

    client = OpenAI(
        base_url=GATEWAY_BASE_URL,
        api_key=API_KEY
    )

    # -------------------------------------------------------------
    # 1. Test GET /v1/models
    # -------------------------------------------------------------
    logger.info("\n[Test 1] Listing registered models via client.models.list()...")
    try:
        models_page = client.models.list()
        for m in models_page.data:
            logger.info(f" -> Found registered model: {m.id} (owned by: {m.owned_by})")
    except Exception as e:
        logger.error(f"Failed to list models: {e}")

    # -------------------------------------------------------------
    # 2. Test Pure Text-to-Image (0 Reference Images) - FLUX.2 Klein 9B
    # -------------------------------------------------------------
    logger.info("\n[Test 2] FLUX.2 Klein 9B - Pure Text-to-Image (0 Reference Images)...")
    prompt_t2i = "A futuristic cyberpunk city street at dusk, neon rain reflections, photorealistic, 8k"
    try:
        response = client.images.generate(
            model="flux-2-klein-9b",
            prompt=prompt_t2i,
            size="1280x720",
            response_format="url"
        )
        logger.info("✅ Generation Success!")
        logger.info(f"Timestamp: {response.created}")
        for idx, img in enumerate(response.data):
            logger.info(f" Image #{idx + 1} URL: {img.url}")
    except Exception as e:
        logger.error(f"Test 2 failed (Check if ComfyUI is running on 8188): {e}")

    # -------------------------------------------------------------
    # 3. Test Multi-Reference Image Generation - FLUX.2 Klein 9B
    # -------------------------------------------------------------
    logger.info("\n[Test 3] FLUX.2 Klein 9B - Multi-Reference Image Injection (2 reference images)...")
    prompt_ref = "The same protagonist standing in a high-tech laboratory, holding an energy orb, dramatic lighting"
    # Example reference images: can be HTTP URLs, local file paths, or Base64
    ref_images = [
        "https://raw.githubusercontent.com/comfyanonymous/ComfyUI/master/input/example.png",
        "https://raw.githubusercontent.com/comfyanonymous/ComfyUI/master/input/example.png"
    ]
    try:
        response = client.images.generate(
            model="flux-2-klein-9b",
            prompt=prompt_ref,
            size="1024x1024",
            response_format="url",
            extra_body={
                "ref_images": ref_images,
                "steps": 4,
                "seed": 888888
            }
        )
        logger.info("✅ Multi-Reference Generation Success!")
        for idx, img in enumerate(response.data):
            logger.info(f" Image #{idx + 1} URL: {img.url}")
    except Exception as e:
        logger.error(f"Test 3 failed: {e}")

    # -------------------------------------------------------------
    # 4. Test Model Switching - SDXL Base Model
    # -------------------------------------------------------------
    logger.info("\n[Test 4] Switching Model to 'sdxl-base' (Config-Driven)...")
    prompt_sdxl = "Majestic snow-capped mountain landscape at golden hour, ultra-detailed oil painting style"
    try:
        response = client.images.generate(
            model="sdxl-base",
            prompt=prompt_sdxl,
            size="1024x1024",
            response_format="url",
            extra_body={
                "steps": 20,
                "cfg": 7.0
            }
        )
        logger.info("✅ SDXL Generation Success!")
        for idx, img in enumerate(response.data):
            logger.info(f" Image #{idx + 1} URL: {img.url}")
    except Exception as e:
        logger.error(f"Test 4 failed: {e}")

    # -------------------------------------------------------------
    # 5. Test Base64 Response Format
    # -------------------------------------------------------------
    logger.info("\n[Test 5] Requesting Base64 output format (response_format='b64_json')...")
    try:
        response = client.images.generate(
            model="flux-2-klein-9b",
            prompt="A minimalist glowing geometric cube floating in void",
            size="512x512",
            response_format="b64_json"
        )
        logger.info("✅ Base64 Response Success!")
        for idx, img in enumerate(response.data):
            prefix = img.b64_json[:50] if img.b64_json else "None"
            logger.info(f" Image #{idx + 1} Base64 preview: {prefix}... (len: {len(img.b64_json) if img.b64_json else 0})")
    except Exception as e:
        logger.error(f"Test 5 failed: {e}")

    logger.info("\n" + "=" * 60)
    logger.info("SDK Verification Completed.")
    logger.info("=" * 60)


if __name__ == "__main__":
    test_openai_sdk_integration()
