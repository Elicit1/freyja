#!/usr/bin/env python3
"""Idempotently register FL2VA Turbo4/Turbo8 through FREYJA's model API."""
import argparse
import json
from urllib.parse import urlencode
from urllib.request import Request, urlopen

def api(base_url, path, method="GET", payload=None):
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8") if payload is not None else None
    request = Request(base_url.rstrip("/") + path, data=body, method=method,
                      headers={"Content-Type": "application/json; charset=utf-8"})
    with urlopen(request, timeout=30) as response:
        result = json.load(response)
    if result.get("code") != 200:
        raise RuntimeError(f"{method} {path}: {result.get('msg', result)}")
    return result.get("data")


def initialize(base_url, provider_id, turbo_steps=4):
    if turbo_steps not in (4, 8):
        raise ValueError("FL2VA Turbo supports 4 or 8 steps.")
    model_code = f"minimax-h3-fl2va-turbo{turbo_steps}"
    provider = api(base_url, f"/ai/provider/{provider_id}")
    if provider.get("status") != 1 or provider.get("providerType") != "OPENAI":
        raise RuntimeError("Choose an enabled OPENAI provider pointing to Comfy Gateway.")
    print(f"Provider: {provider['providerName']} ({provider_id}), {provider['baseUrl']}")
    existing = []
    page_num = 1
    while True:
        query = urlencode({"providerId": provider_id, "pageNum": page_num, "pageSize": 100})
        page = api(base_url, "/ai/model/page?" + query)
        existing.extend(m for m in page["records"] if m["modelCode"] == model_code)
        if page_num >= int(page["pages"]):
            break
        page_num += 1
    if len(existing) > 1:
        raise RuntimeError("Multiple active records have this model code; resolve them before initialization.")
    payload = {
        "providerId": str(provider_id), "modelCode": model_code,
        "modelName": f"MiniMax H3 官方 FL2VA Turbo{turbo_steps}",
        "modelType": "TXT2VIDEO_FIRST_LAST", "maxImages": 2,
        "maxAudios": 0, "maxVideos": 0, "status": 1,
        "sortOrder": existing[0].get("sortOrder", 0) if existing else 0,
        "paramsJson": existing[0].get("paramsJson") if existing else None,
        "remark": f"Comfy-Org 官方 FL2VA INT8 + LightX2V {'768p ' if turbo_steps == 4 else ''}Turbo LoRA，固定{turbo_steps}步；支持文生视频、首帧、首尾帧及原生音频。",
    }
    if existing:
        for key in ("temperature", "maxTokens", "topP"):
            payload[key] = existing[0].get(key)
        payload["id"] = str(existing[0]["id"])
    api(base_url, "/ai/model", "PUT" if existing else "POST", payload)
    models = api(base_url, f"/ai/provider/{provider_id}/model/list")
    saved = [m for m in models if m["modelCode"] == model_code]
    if len(saved) != 1 or saved[0]["modelType"] != "TXT2VIDEO_FIRST_LAST":
        raise RuntimeError("Model did not appear exactly once in the enabled model list.")
    print(json.dumps(saved[0], ensure_ascii=False, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--provider-id", required=True, help="Existing Comfy Gateway provider ID (string)")
    parser.add_argument("--steps", type=int, choices=(4, 8), default=4)
    args = parser.parse_args()
    initialize(args.base_url, args.provider_id, args.steps)
