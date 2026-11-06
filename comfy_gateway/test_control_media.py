"""Native ControlNet inputs are validated/prepared before ComfyUI submission."""
import hashlib
import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

import httpx
from comfy_client import ComfyAsyncClient
from reference_media import upload_reference_video
from reference_prompt import ReferenceBinding
from test_video_reference import binding, setup_model


class ControlMediaTests(unittest.IsolatedAsyncioTestCase):
    async def test_short_control_is_rejected_before_upload_or_grayscale_conversion(self):
        model,_=setup_model();raw=b"prepared source"
        client=ComfyAsyncClient("http://comfy:8188","ws://comfy:8188/ws")
        client._http_client=httpx.AsyncClient(transport=httpx.MockTransport(lambda req:httpx.Response(200,content=raw)))
        client.upload_file_to_comfy=AsyncMock()
        process=MagicMock(returncode=0)
        process.communicate=AsyncMock(return_value=(json.dumps({"streams":[{"codec_type":"video","width":64,"height":64,"nb_read_frames":"96","r_frame_rate":"24/1"}],"format":{"duration":"4"}}).encode(),b""))
        try:
            with patch("reference_media.asyncio.create_subprocess_exec",AsyncMock(return_value=process)),patch("reference_media._grayscale_video",AsyncMock()) as gray:
                with self.assertRaisesRegex(ValueError,"H3_CONTROL_RANGE_INVALID"):
                    await upload_reference_video(client,ReferenceBinding.model_validate(binding(digest=hashlib.sha256(raw).hexdigest())),model.reference_video,{"archive.test"},required_frames=120,grayscale=True)
                gray.assert_not_awaited();client.upload_file_to_comfy.assert_not_awaited()
        finally:await client.close()

    @unittest.skipUnless(shutil.which(os.getenv("FFMPEG_PATH","ffmpeg")) and shutil.which(os.getenv("FFPROBE_PATH","ffprobe")),"FFmpeg/FFprobe required")
    async def test_gray_derivative_preserves_frames_and_color_semantic_reference(self):
        ffmpeg=os.getenv("FFMPEG_PATH","ffmpeg");ffprobe=os.getenv("FFPROBE_PATH","ffprobe")
        def run(args):return subprocess.run(args,check=True,capture_output=True).stdout
        with tempfile.TemporaryDirectory(prefix="freyja_native_control_test_") as folder:
            source=Path(folder)/"red.mp4"
            run([ffmpeg,"-v","error","-nostdin","-y","-f","lavfi","-i","color=c=red:s=64x64:r=24:d=1","-c:v","libx264","-pix_fmt","yuv420p",str(source)])
            raw=source.read_bytes();model,_=setup_model()
            client=ComfyAsyncClient("http://comfy:8188","ws://comfy:8188/ws")
            client._http_client=httpx.AsyncClient(transport=httpx.MockTransport(lambda req:httpx.Response(200,content=raw)))
            client.upload_file_to_comfy=AsyncMock(side_effect=["refs/color.mp4","refs/gray.mp4"])
            try:
                result=await upload_reference_video(client,ReferenceBinding.model_validate(binding(digest=hashlib.sha256(raw).hexdigest())),model.reference_video,{"archive.test"},required_frames=24,grayscale=True)
                self.assertEqual(result["filename"],"refs/color.mp4");self.assertEqual(result["control_filename"],"refs/gray.mp4");self.assertEqual(result["frames"],24)
                pixels=[]
                for i,call in enumerate(client.upload_file_to_comfy.await_args_list):
                    uploaded=Path(folder)/f"uploaded_{i}.mp4";uploaded.write_bytes(call.args[0])
                    probe=json.loads(run([ffprobe,"-v","error","-count_frames","-show_entries","stream=codec_type,nb_read_frames,r_frame_rate","-of","json",str(uploaded)]))
                    video=next(stream for stream in probe["streams"] if stream["codec_type"]=="video")
                    self.assertEqual(int(video["nb_read_frames"]),24);self.assertEqual(video["r_frame_rate"],"24/1")
                    self.assertTrue(any(stream["codec_type"]=="audio" for stream in probe["streams"]))  # VHS compatibility.
                    frame=run([ffmpeg,"-v","error","-nostdin","-i",str(uploaded),"-frames:v","1","-f","rawvideo","-pix_fmt","rgb24","pipe:1"])
                    pixels.append(tuple(frame[:3]));self.assertTrue(call.kwargs["preserve_subfolder"])
                red,gray=pixels
                self.assertGreater(red[0]-red[1],100);self.assertLessEqual(max(gray)-min(gray),2)
                self.assertEqual(source.read_bytes(),raw)
            finally:await client.close()
