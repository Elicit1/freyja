"""Bounded transport and media validation for already prepared, immutable clips."""
import asyncio
import hashlib
import json
import math
import os
import tempfile
import uuid
from pathlib import Path
from urllib.parse import urlparse, urljoin


async def upload_reference_video(client, binding, capability, allowed_hosts):
    http = await client.get_http_client()
    url = binding.video_url
    raw = bytearray()
    for redirect in range(4):
        parsed = urlparse(url)
        if (parsed.scheme not in ("http", "https") or parsed.username or parsed.password
                or parsed.fragment or not parsed.hostname or parsed.hostname.lower() not in allowed_hosts):
            raise ValueError("REFERENCE_VIDEO_SOURCE_INVALID: configure REFERENCE_MEDIA_HOSTS for the archive host")
        async with http.stream("GET", url, follow_redirects=False, timeout=60.0) as response:
            if response.status_code in (301, 302, 303, 307, 308):
                url = urljoin(url, response.headers.get("location", ""))
                continue
            response.raise_for_status()
            if int(response.headers.get("content-length", 0)) > capability.max_download_bytes:
                raise ValueError("REFERENCE_VIDEO_LIMIT_EXCEEDED: video download is too large")
            async for chunk in response.aiter_bytes():
                raw.extend(chunk)
                if len(raw) > capability.max_download_bytes:
                    raise ValueError("REFERENCE_VIDEO_LIMIT_EXCEEDED: video download is too large")
            break
    else:
        raise ValueError("REFERENCE_VIDEO_SOURCE_INVALID: too many redirects")
    if not raw or hashlib.sha256(raw).hexdigest() != binding.content_sha256:
        raise ValueError("REFERENCE_VIDEO_SOURCE_CHANGED: archived clip digest does not match")
    with tempfile.TemporaryDirectory(prefix="freyja_ref_video_") as folder:
        source = Path(folder) / "clip.mp4"
        source.write_bytes(raw)
        process = await asyncio.create_subprocess_exec(
            os.getenv("FFPROBE_PATH", "ffprobe"), "-v", "error", "-count_frames", "-show_entries",
            "stream=codec_type,width,height,nb_read_frames,r_frame_rate:format=duration", "-of", "json", str(source),
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
        try:
            stdout, _ = await asyncio.wait_for(process.communicate(), timeout=60)
        except BaseException:
            if process.returncode is None:
                process.kill()
                await process.wait()
            raise
        if process.returncode != 0:
            raise ValueError("REFERENCE_VIDEO_DECODE_FAILED: ffprobe could not decode the clip")
        probe = json.loads(stdout)
        video = next((s for s in probe.get("streams", []) if s.get("codec_type") == "video"), None)
        duration = float(probe.get("format", {}).get("duration", 0))
        if (not video or not math.isfinite(duration) or duration <= 0
                or duration < capability.min_clip_seconds - 1 / capability.decode_fps
                or duration > min(capability.max_clip_seconds, capability.max_total_clip_seconds) + 1 / capability.decode_fps
                or not 0 < int(video.get("nb_read_frames", 0)) <= capability.max_decoded_frames
                or not 0 < video.get("width", 0) <= capability.max_decode_width
                or not 0 < video.get("height", 0) <= capability.max_decode_height):
            raise ValueError("REFERENCE_VIDEO_LIMIT_EXCEEDED: decoded clip does not meet the model contract")
        rate = video.get("r_frame_rate", "0/1").split("/")
        fps = float(rate[0]) / float(rate[1]) if len(rate) == 2 and float(rate[1]) else 0
        if abs(fps - capability.decode_fps) > 0.01:
            raise ValueError("REFERENCE_VIDEO_LIMIT_EXCEEDED: prepared clip frame rate does not match")
        if binding.include_audio and not any(s.get("codec_type") == "audio" for s in probe.get("streams", [])):
            raise ValueError("REFERENCE_VIDEO_DECODE_FAILED: requested video audio track is missing")
    uploaded = await client.upload_file_to_comfy(bytes(raw), f"ref_video_{uuid.uuid4().hex}.mp4", preserve_subfolder=True)
    return {"reference_id": binding.reference_id, "filename": uploaded, "include_audio": binding.include_audio}
