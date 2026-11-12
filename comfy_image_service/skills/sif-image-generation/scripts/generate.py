#!/usr/bin/env python3
"""Generate and edit images through SIF using only Python's standard library."""
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
        path = Path(output or f"outputs/{job_id}.{image['format']}").expanduser().resolve()
        if index: path = path.with_name(path.stem + f"-{index}" + path.suffix)
        expected = "." + image["format"]
        if path.suffix.lower() != expected and not (expected == ".jpg" and path.suffix.lower() == ".jpeg"):
            raise ValueError("Output filename must end in " + expected)
        path.parent.mkdir(parents=True, exist_ok=True)
        # Never forward the key to an arbitrary URL returned in a result.
        data = client.request(f"/v1/jobs/{job_id}/images/{index}", binary=True)
        with path.open("wb" if overwrite else "xb") as stream: stream.write(data)
        saved.append({"path": str(path), "width": image["width"], "height": image["height"], "seed": image.get("seed")})
    return {"job_id": job_id, "status": "succeeded", "app": job["request"]["app"], "mode": job["request"]["mode"],
            "tier": job["request"]["tier"], "seed": job["request"]["seed"],
            "effective_prompt": job["result"]["effective_prompt"], "images": saved}


def upload_file(client, value):
    path = Path(value)
    if not path.is_file():
        raise FileNotFoundError(path)
    if path.stat().st_size > 25 * 1024 * 1024:
        raise ValueError("Input image exceeds 25 MiB.")
    return client.request("/v1/uploads", path.read_bytes(), content_type="application/octet-stream")


def parse_args():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url")
    parser.add_argument("--config", type=Path, help="Private JSON containing base_url and api_key")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("capabilities")
    sub.add_parser("readiness")
    p = sub.add_parser("upload", help="Upload a local image and print its MinIO URL and 24-hour expiry")
    p.add_argument("image", type=Path)
    for mode in ("t2i", "i2i"):
        p = sub.add_parser(mode)
        p.add_argument("--app", default="minimax-h3-image")
        p.add_argument("--tier", choices=["pro", "standard", "fast"], default="standard")
        p.add_argument("--prompt", required=True)
        p.add_argument("--megapixels", "--mp", type=float)
        p.add_argument("--aspect-ratio", default=None)
        p.add_argument("--width", type=int)
        p.add_argument("--height", type=int)
        p.add_argument("--n", "--count", type=int, default=1)
        p.add_argument("--output-format", choices=["png", "jpeg", "webp"], default="png")
        p.add_argument("--output-quality", type=int, default=95)
        p.add_argument("--prompt-expansion", type=Path, help="JSON file with expansion controls, including optional instructions")
        p.add_argument("--no-expand", action="store_true")
        p.add_argument("--seed", type=int)
        p.add_argument("--output", type=Path)
        p.add_argument("--overwrite", action="store_true")
        p.add_argument("--wait-timeout", type=float, default=1800)
        p.add_argument("--idempotency-key")
        if mode == "i2i":
            p.add_argument("--image", action="append", required=True, help="Local file or MinIO image URL; repeat in <Picture N> order, up to 16 images")
            p.add_argument("--reference-mode", choices=["latent", "vision"], default="latent")
    p = sub.add_parser("status", help="Resume an existing job without submitting again")
    p.add_argument("job_id")
    p.add_argument("--output", type=Path)
    p.add_argument("--overwrite", action="store_true")
    p.add_argument("--wait-timeout", type=float, default=1800)
    return parser.parse_args()


def main():
    args = parse_args()
    config_env = os.environ.get("SIF_CONFIG") or os.environ.get("COMFY_IMAGE_CONFIG")
    config_path = args.config or (Path(config_env) if config_env else None)
    config = json.loads(config_path.read_text(encoding="utf-8-sig")) if config_path else {}
    base_url = args.base_url or os.environ.get("SIF_BASE_URL") or os.environ.get("COMFY_IMAGE_BASE_URL") or config.get("base_url", "http://127.0.0.1:8011")
    key = os.environ.get("SIF_API_KEY") or os.environ.get("COMFY_IMAGE_API_KEY") or config.get("api_key", "")
    if not key or not key.startswith("sk-"): raise ValueError("Create an API key in the SIF console, then set SIF_API_KEY or pass --config with your private client configuration.")
    client = Client(base_url, key)
    if args.command in {"capabilities", "readiness"}:
        print(json.dumps(client.request("/v1/" + args.command), ensure_ascii=False, indent=2));return
    if args.command == "upload":
        print(json.dumps(upload_file(client, args.image), ensure_ascii=False, indent=2))
        return
    if args.output and args.output.exists() and not args.overwrite:
        raise FileExistsError("Output exists; choose another path or pass --overwrite.")
    if args.command == "status":
        job_id = str(uuid.UUID(args.job_id)); job = client.request("/v1/jobs/" + job_id)
    else:
        if args.megapixels is not None and not 0.1 <= args.megapixels <= 20:
            raise ValueError("--megapixels must be between 0.1 and 20.")
        if (args.width is None) != (args.height is None):
            raise ValueError("Supply both --width and --height.")
        if args.width is not None:
            if args.megapixels is not None or args.aspect_ratio is not None:
                raise ValueError("Use --width/--height together, or --mp/--aspect-ratio.")
            if any(side < 32 or side > 16384 or side % 32 for side in (args.width, args.height)):
                raise ValueError("Canvas sides must be multiples of 32 between 32 and 16384.")
            if args.width * args.height > 20 * 1024 * 1024 or not 1/3 <= args.width / args.height <= 3:
                raise ValueError("Canvas must be at most 20 MP and between 1:3 and 3:1.")
        if not 1 <= args.n <= 10 or not 0 <= args.output_quality <= 100:
            raise ValueError("--n must be 1–10 and --output-quality must be 0–100.")
        if args.no_expand and args.prompt_expansion:
            raise ValueError("Remove --no-expand to supply expansion controls.")
        if args.seed is not None and not 0 <= args.seed <= 9007199254740991:
            raise ValueError("--seed must be between 0 and 9007199254740991.")
        body = {"app":args.app,"tier":args.tier,"prompt":args.prompt,
                "expand_prompt":not args.no_expand,"seed":args.seed,"aspect_ratio":args.aspect_ratio,
                "width":args.width,"height":args.height,"n":args.n,
                "output_format":args.output_format,"output_quality":args.output_quality}
        if args.megapixels is not None:
            body["megapixels"] = args.megapixels
        if args.prompt_expansion:
            body["prompt_expansion"] = json.loads(args.prompt_expansion.read_text(encoding="utf-8-sig"))
        if args.command == "i2i":
            if len(args.image) > 16:
                raise ValueError("Provide at most 16 --image arguments.")
            for value in args.image:
                if value.startswith(("http://", "https://")):
                    continue
                path = Path(value)
                if not path.is_file():
                    raise FileNotFoundError(path)
                if path.stat().st_size > 25*1024*1024:
                    raise ValueError("Input image exceeds 25 MiB.")
            references = []
            for value in args.image:
                if value.startswith(("http://", "https://")):
                    references.append(value)
                else:
                    uploaded = upload_file(client, value)
                    references.append(uploaded.get("url") or uploaded["id"])
            body["images"] = references
            body["reference_mode"] = args.reference_mode
        key = args.idempotency_key or str(uuid.uuid4())
        print("Idempotency-Key: " + key, file=sys.stderr)
        job = client.submit(args.command, body, key); job_id = job["id"]
    started, previous = time.monotonic(), None
    while job["status"] not in {"succeeded", "failed", "unknown"}:
        if job["status"] != previous:
            print(f"Job {job_id}: {job['status']}", file=sys.stderr); previous = job["status"]
        if time.monotonic()-started > args.wait_timeout:
            raise TimeoutError(f"Job {job_id} is still pending. Resume with: status {job_id}")
        remaining = args.wait_timeout - (time.monotonic() - started)
        timeout = max(1, min(25, remaining))
        try:
            job = client.request(f"/v1/jobs/{job_id}/wait?timeout_seconds={timeout}")
        except (urllib.error.URLError, TimeoutError, ConnectionError):
            time.sleep(2)
    print(json.dumps(download(client, job, args.output, args.overwrite), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    try: main()
    except Exception as error:
        print(json.dumps({"error": str(error)}, ensure_ascii=False), file=sys.stderr)
        sys.exit(1)
