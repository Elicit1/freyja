"""Bounded streaming transport for opaque, digest-verified safetensors only."""
import hashlib
import os
from pathlib import Path
import tempfile
from urllib.parse import urlparse

MAX_BYTES = 2 * 1024**3


async def upload_latent(client, artifact):
    parsed=urlparse(artifact.url)
    hosts={host.strip().lower() for host in os.getenv("REFERENCE_MEDIA_HOSTS", "").split(",") if host.strip()}
    if parsed.scheme not in {"http","https"} or not parsed.hostname or parsed.hostname.lower() not in hosts or parsed.username or parsed.password or parsed.fragment:
        raise ValueError("H3_LATENT_URL_FORBIDDEN: configure REFERENCE_MEDIA_HOSTS for managed MinIO")
    http=await client.get_http_client()
    with tempfile.TemporaryDirectory(prefix="freyja-latent-") as folder:
        path=Path(folder)/f"{artifact.content_sha256}.safetensors"
        digest=hashlib.sha256();count=0
        async with http.stream("GET",artifact.url,follow_redirects=False,timeout=900) as response:
            response.raise_for_status()
            if int(response.headers.get("content-length",0))>MAX_BYTES:raise ValueError("H3_LATENT_TOO_LARGE")
            with path.open("wb") as output:
                async for chunk in response.aiter_bytes(1024*1024):
                    count+=len(chunk)
                    if count>MAX_BYTES:raise ValueError("H3_LATENT_TOO_LARGE")
                    digest.update(chunk);output.write(chunk)
        if count<16 or digest.hexdigest()!=artifact.content_sha256:raise ValueError("H3_LATENT_DIGEST_MISMATCH")
        with path.open("rb") as data:
            response=await http.post(client.http_base+"/upload/image",files={"image":(path.name,data,"application/octet-stream"),"overwrite":(None,"true"),"type":(None,"input")},timeout=900)
            response.raise_for_status();result=response.json()
        relative="/".join(p.strip("/") for p in (result.get("subfolder",""),result["name"]) if p)
        if Path(relative).is_absolute() or ".." in relative.split("/") or "\\" in relative or not relative.endswith(".safetensors"):raise ValueError("H3_LATENT_UPLOAD_PATH_INVALID")
        return relative


async def cache_output_latent(client, asset, directory, identity):
    if asset.get("subfolder")!="freyja_h3" or asset.get("type")!="output" or not asset.get("filename","").endswith(".safetensors") or Path(asset["filename"]).name!=asset["filename"]:
        raise ValueError("H3_LATENT_OUTPUT_PATH_INVALID")
    path=Path(directory)/(hashlib.sha256(identity.encode()).hexdigest()+".safetensors")
    if path.is_file():return path
    path.parent.mkdir(parents=True,exist_ok=True)
    handle,temp=tempfile.mkstemp(prefix="latent-",suffix=".tmp",dir=path.parent)
    try:
        http=await client.get_http_client();count=0
        with os.fdopen(handle,"wb") as output:
            async with http.stream("GET",client.http_base+"/view",params=asset,timeout=900) as response:
                response.raise_for_status()
                if int(response.headers.get("content-length",0))>MAX_BYTES:raise ValueError("H3_LATENT_TOO_LARGE")
                async for chunk in response.aiter_bytes(1024*1024):
                    count+=len(chunk)
                    if count>MAX_BYTES:raise ValueError("H3_LATENT_TOO_LARGE")
                    output.write(chunk)
        if count<16:raise ValueError("H3_LATENT_OUTPUT_EMPTY")
        os.replace(temp,path)
    finally:Path(temp).unlink(missing_ok=True)
    return path
