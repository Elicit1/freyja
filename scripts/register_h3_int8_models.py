"""Register the two H3 INT8 base models through Studio's model-center API.

Existing entries are preserved, including creator-edited limits and parameters.
"""
import argparse
import json
import os
from urllib.parse import urlencode
from urllib.request import Request, urlopen


def model_definitions(provider_id):
    return [
        dict(providerId=str(provider_id), modelCode=f"minimax-h3-{kind}-int8",
             modelName=f"MiniMax H3 INT8 {label} · 基础20步（无加速）",
             modelType=model_type, maxImages=images, maxAudios=audios, maxVideos=videos,
             paramsJson=None, sortOrder=order, status=1,
             remark="INT8底模直接采样，默认20步；无加速LoRA、EasyCache或注意力加速补丁。支持二采及人脸修复。")
        for kind, label, model_type, images, audios, videos, order in (
            ("fl2va", "首尾帧", "TXT2VIDEO_FIRST_LAST", 2, 0, 0, 80),
            ("ref2va", "多参考", "TXT2VIDEO_REF", 9, 3, 3, 81))]


def api(backend, method, path, payload=None):
    token = os.environ.get("FREYJA_API_TOKEN", "").strip()
    if not token:
        raise RuntimeError("Set FREYJA_API_TOKEN to a valid Studio login token before registering models")
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8") if payload is not None else None
    request = Request(backend.rstrip("/") + path, data=body, method=method,
                      headers={"Content-Type": "application/json", "Authorization": "Bearer " + token})
    with urlopen(request, timeout=30) as response:
        result = json.load(response)
    if result.get("code") != 200:
        raise RuntimeError(result.get("msg") or "Studio API request failed")
    return result.get("data")


def register(backend, provider_id):
    existing = {}
    page = 1
    while True:
        params = urlencode(dict(providerId=str(provider_id), pageNum=page, pageSize=100))
        result = api(backend, "GET", "/ai/model/page?" + params)
        rows = result["records"]
        existing.update({row["modelCode"]: row for row in rows})
        if page * 100 >= int(result["total"]) or not rows:
            break
        page += 1
    for definition in model_definitions(provider_id):
        code = definition["modelCode"]
        if code in existing:
            print(json.dumps({"modelCode": code, "action": "preserved", "id": str(existing[code]["id"])}))
        else:
            api(backend, "POST", "/ai/model", definition)
            print(json.dumps({"modelCode": code, "action": "created"}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--backend", default="http://127.0.0.1:8080")
    parser.add_argument("--provider-id", required=True, help="Existing gateway provider ID (keep as a string)")
    args = parser.parse_args()
    register(args.backend, args.provider_id)
