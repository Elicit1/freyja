#!/usr/bin/env python3
"""Upload, submit, poll, and download ComfyUI images using only Python's standard library."""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path


class Client:
    def __init__(self, base_url, api_key):
        parsed = urllib.parse.urlsplit(base_url)
        if parsed.scheme not in {"http", "https"} or not parsed.netloc:
            raise ValueError("API base URL must be an http(s) URL.")
        self.base_url, self.api_key = base_url.rstrip("/"), api_key
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def request(self, path, data=None, content_type="application/json", idempotency_key=None, binary=False):
        headers = {"Authorization": "Bearer " + self.api_key}
        if data is not None: headers["Content-Type"] = content_type
        if idempotency_key: headers["Idempotency-Key"] = idempotency_key
        request = urllib.request.Request(self.base_url + path, data=data, headers=headers)
        try:
            with self.opener.open(request, timeout=90) as response:
                data = response.read(128 * 1024 * 1024 + 1)
                if len(data) > 128 * 1024 * 1024: raise ValueError("Response exceeded 128 MiB.")
                return data if binary else json.loads(data)
        except urllib.error.HTTPError as error:
            detail = error.read(8192).decode("utf8", errors="replace")
            raise RuntimeError(f"HTTP {error.code}: {detail}") from error

    def submit(self, mode, body, key):
        data = json.dumps(body).encode()
        for attempt in range(3):
            try:
                return self.request("/v1/" + mode, data, idempotency_key=key)
            except (urllib.error.URLError, TimeoutError, ConnectionError):
                if attempt == 2:
                    raise RuntimeError("Submission response was lost. Retry with the SAME --idempotency-key " + key)
                time.sleep(2)


def download(client, job, output, overwrite):
    if job["status"] != "succeeded":
        raise RuntimeError(json.dumps({"job_id": job["id"], "status": job["status"], "error": job.get("error")}, ensure_ascii=False))
    job_id, saved = str(uuid.UUID(job["id"])), []
    for index, image in enumerate(job["result"]["images"]):
        path = Path(output or f"outputs/{job_id}.png").expanduser().resolve()
        if index: path = path.with_name(path.stem + f"-{index}" + path.suffix)
        expected = "." + image["format"]
        if path.suffix.lower() != expected and not (expected == ".jpg" and path.suffix.lower() == ".jpeg"):
            raise ValueError("Output filename must end in " + expected)
        path.parent.mkdir(parents=True, exist_ok=True)
        # Never forward the key to an arbitrary URL returned in a result.
        data = client.request(f"/v1/jobs/{job_id}/images/{index}", binary=True)
        with path.open("wb" if overwrite else "xb") as stream: stream.write(data)
        saved.append({"path": str(path), "width": image["width"], "height": image["height"]})
    return {"job_id": job_id, "status": "succeeded", "app": job["request"]["app"], "mode": job["request"]["mode"],
            "tier": job["request"]["tier"], "seed": job["request"]["seed"],
            "effective_prompt": job["result"]["effective_prompt"], "images": saved}


def parse_args():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url")
    parser.add_argument("--config", type=Path, help="Private JSON containing base_url and api_key")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("capabilities")
    sub.add_parser("readiness")
    for mode in ("t2i", "i2i"):
        p = sub.add_parser(mode)
        p.add_argument("--app", default="minimax-h3-image")
        p.add_argument("--tier", choices=["pro", "standard", "fast"], default="standard")
        p.add_argument("--prompt", required=True)
        p.add_argument("--megapixels", "--mp", type=float, default=4)
        p.add_argument("--aspect-ratio", default=None)
        p.add_argument("--no-expand", action="store_true")
        p.add_argument("--seed", type=int)
        p.add_argument("--output", type=Path)
        p.add_argument("--overwrite", action="store_true")
        p.add_argument("--wait-timeout", type=float, default=1800)
        p.add_argument("--idempotency-key")
        if mode == "i2i": p.add_argument("--image", type=Path, required=True)
    p = sub.add_parser("status", help="Resume an existing job without submitting again")
    p.add_argument("job_id")
    p.add_argument("--output", type=Path)
    p.add_argument("--overwrite", action="store_true")
    p.add_argument("--wait-timeout", type=float, default=1800)
    return parser.parse_args()


def main():
    args = parse_args()
    config_path = args.config or (Path(os.environ["COMFY_IMAGE_CONFIG"]) if os.environ.get("COMFY_IMAGE_CONFIG") else None)
    candidate = Path(__file__).resolve().parents[3] / ".client.json"
    if config_path is None and candidate.is_file(): config_path = candidate
    config = json.loads(config_path.read_text(encoding="utf-8-sig")) if config_path else {}
    base_url = args.base_url or os.environ.get("COMFY_IMAGE_BASE_URL") or config.get("base_url", "http://127.0.0.1:8011")
    key = os.environ.get("COMFY_IMAGE_API_KEY") or config.get("api_key", "")
    if not key: raise ValueError("Set COMFY_IMAGE_API_KEY or use --config with a private connection JSON file.")
    client = Client(base_url, key)
    if args.command in {"capabilities", "readiness"}:
        print(json.dumps(client.request("/v1/" + args.command), ensure_ascii=False, indent=2));return
    if args.output and args.output.exists() and not args.overwrite:
        raise FileExistsError("Output exists; choose another path or pass --overwrite.")
    if args.command == "status":
        job_id = str(uuid.UUID(args.job_id)); job = client.request("/v1/jobs/" + job_id)
    else:
        if not 0.1 <= args.megapixels <= 20: raise ValueError("--megapixels must be between 0.1 and 20.")
        if args.command == "t2i" and args.aspect_ratio not in {None,"1:1","2:3","3:2","3:4","4:3","9:16","16:9","21:9"}:
            raise ValueError("Unsupported t2i aspect ratio.")
        if args.command == "i2i" and args.aspect_ratio not in {None,"original"}:
            raise ValueError("i2i preserves the original image ratio.")
        if args.seed is not None and not 0 <= args.seed <= 9007199254740991:
            raise ValueError("--seed must be between 0 and 9007199254740991.")
        body = {"app":args.app,"tier":args.tier,"prompt":args.prompt,"megapixels":args.megapixels,
                "expand_prompt":not args.no_expand,"seed":args.seed,"aspect_ratio":args.aspect_ratio}
        if args.command == "i2i":
            if not args.image.is_file(): raise FileNotFoundError(args.image)
            if args.image.stat().st_size > 25*1024*1024: raise ValueError("Input image exceeds 25 MiB.")
            upload = client.request("/v1/uploads", args.image.read_bytes(), content_type="application/octet-stream")
            body["image"] = upload["id"]
        key = args.idempotency_key or str(uuid.uuid4())
        print("Idempotency-Key: " + key, file=sys.stderr)
        job = client.submit(args.command, body, key); job_id = job["id"]
    started, previous = time.monotonic(), None
    while job["status"] not in {"succeeded", "failed", "unknown"}:
        if job["status"] != previous:
            print(f"Job {job_id}: {job['status']}", file=sys.stderr); previous = job["status"]
        if time.monotonic()-started > args.wait_timeout:
            raise TimeoutError(f"Job {job_id} is still pending. Resume with: status {job_id}")
        time.sleep(2)
        try: job = client.request("/v1/jobs/" + job_id)
        except (urllib.error.URLError, TimeoutError, ConnectionError):continue
    print(json.dumps(download(client, job, args.output, args.overwrite), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    try: main()
    except Exception as error:
        print(json.dumps({"error": str(error)}, ensure_ascii=False), file=sys.stderr)
        sys.exit(1)
