#!/usr/bin/env python3
"""Save the two Turbo8 workflows in ComfyUI and register FREYJA model entries."""
import argparse
import json
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import quote, urlencode
from urllib.request import Request, urlopen

from init_minimax_h3_fl2va import api

WORKFLOWS = (
    ("video_minimax_h3_r2v_official_turbo8.json", "minimax-h3-ref-turbo8",
     "MiniMax H3 官方 Ref2VA Turbo8", "Ref2VA INT8 + 768p Turbo8 LoRA，固定8步。"),
    ("video_minimax_h3_r2v_turbo8_face_refine.json", "minimax-h3-ref-turbo8-face-refine",
     "MiniMax H3 官方 Ref2VA Turbo8 人脸修复", "Ref2VA INT8 + 768p Turbo8 LoRA；首段8步、H3 FaceRefine修复段8步。"),
)


def install_comfy_workflows(comfy_url):
    root = Path(__file__).resolve().parents[2]
    for filename, _, _, _ in WORKFLOWS:
        content = (root / "comfyUI_workflow" / filename).read_bytes()
        expected = json.loads(content)
        url = comfy_url.rstrip("/") + "/userdata/" + quote("workflows/" + filename, safe="")
        request = Request(url + "?overwrite=false", data=content, method="POST",
                          headers={"Content-Type": "application/json"})
        try:
            with urlopen(request, timeout=30) as response:
                json.load(response)
        except HTTPError as exc:
            if exc.code != 409:
                raise
            with urlopen(url, timeout=30) as response:
                if json.load(response) != expected:
                    raise RuntimeError(f"{filename} already exists with different contents; preserving it.") from exc
        with urlopen(url, timeout=30) as response:
            if json.load(response) != expected:
                raise RuntimeError(f"Saved workflow verification failed: {filename}")
        print("ComfyUI workflow saved:", filename)


def initialize_models(base_url, provider_id):
    provider = api(base_url, f"/ai/provider/{provider_id}")
    if provider.get("status") != 1 or provider.get("providerType") != "OPENAI":
        raise RuntimeError("Choose an enabled OPENAI provider pointing to Comfy Gateway.")
    records = []
    page_num = 1
    while True:
        query = urlencode({"providerId": provider_id, "pageNum": page_num, "pageSize": 100})
        page = api(base_url, "/ai/model/page?" + query)
        records.extend(page["records"])
        if page_num >= int(page["pages"]):
            break
        page_num += 1
    for _, code, name, remark in WORKFLOWS:
        existing = [model for model in records if model["modelCode"] == code]
        if len(existing) > 1:
            raise RuntimeError(f"Duplicate model code: {code}")
        payload = {
            "providerId": str(provider_id), "modelCode": code, "modelName": name,
            "modelType": "TXT2VIDEO_REF", "maxImages": 5, "maxAudios": 3, "maxVideos": 0,
            "status": 1, "remark": remark, "sortOrder": existing[0].get("sortOrder", 0) if existing else 0,
        }
        if existing:
            payload["id"] = str(existing[0]["id"])
            for key in ("temperature", "maxTokens", "topP", "paramsJson"):
                payload[key] = existing[0].get(key)
        api(base_url, "/ai/model", "PUT" if existing else "POST", payload)
    enabled = api(base_url, f"/ai/provider/{provider_id}/model/list")
    for _, code, _, _ in WORKFLOWS:
        saved = [model for model in enabled if model["modelCode"] == code]
        if len(saved) != 1 or saved[0]["modelType"] != "TXT2VIDEO_REF":
            raise RuntimeError(f"Enabled model verification failed: {code}")
        print("FREYJA model registered:", saved[0]["id"], saved[0]["modelName"], code)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--comfy-url", default="http://127.0.0.1:8188")
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--provider-id", required=True)
    args = parser.parse_args()
    install_comfy_workflows(args.comfy_url)
    initialize_models(args.base_url, args.provider_id)
