import hashlib
import json
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import AsyncMock

import httpx

from comfy_client import ComfyAsyncClient
from reference_media import upload_reference_video
from reference_prompt import ReferenceBinding
from video_reference import ReferenceVideoCapability


@unittest.skipUnless(shutil.which('ffmpeg') and shutil.which('ffprobe'), 'FFmpeg and FFprobe required')
class SilentVideoCompatibilityTests(unittest.IsolatedAsyncioTestCase):
    async def test_silent_video_survives_vhs_audio_read_without_changing_frames_or_binding(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / 'silent.mp4'
            subprocess.run(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i', 'testsrc2=size=96x64:rate=24',
                            '-t', '1', '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-an', str(source)], check=True)
            raw = source.read_bytes()
            binding = ReferenceBinding.model_validate({'referenceId': 'video1', 'referenceType': 'REFERENCE_VIDEO',
                'referenceVideo': 'https://archive.test/silent.mp4', 'contentSha256': hashlib.sha256(raw).hexdigest(), 'includeAudio': False})
            cap = ReferenceVideoCapability(max_videos=1, min_clip_seconds=0.1, max_clip_seconds=2,
                max_total_clip_seconds=2, max_decoded_frames=48, max_decode_width=128, max_decode_height=128,
                max_mixed_assets=3, max_download_bytes=1024*1024)
            client = ComfyAsyncClient('http://comfy.invalid', 'ws://comfy.invalid/ws')
            client._http_client = httpx.AsyncClient(transport=httpx.MockTransport(lambda req: httpx.Response(200, content=raw)))
            client.upload_file_to_comfy = AsyncMock(return_value='uploaded/compatible.mp4')
            try:
                result = await upload_reference_video(client, binding, cap, {'archive.test'})
                uploaded = Path(directory) / 'uploaded.mp4'
                uploaded.write_bytes(client.upload_file_to_comfy.await_args.args[0])
                self.assertFalse(result['include_audio'])
                self.assertEqual(source.read_bytes(), raw)
                info = json.loads(subprocess.check_output(['ffprobe', '-v', 'error', '-count_frames', '-show_streams', '-of', 'json', str(uploaded)]))
                video = next(s for s in info['streams'] if s['codec_type'] == 'video')
                self.assertEqual(video['nb_read_frames'], '24')
                self.assertEqual(video['r_frame_rate'], '24/1')
                self.assertTrue(any(s['codec_type'] == 'audio' for s in info['streams']))
                # Exercise the exact extraction pattern that killed ComfyUI's cache scanner.
                audio = subprocess.check_output(['ffmpeg', '-v', 'error', '-i', str(uploaded), '-f', 'f32le', '-'])
                self.assertGreater(len(audio), 0)
                self.assertFalse(any(audio))
                def frame_hashes(path):
                    output = subprocess.check_output(['ffmpeg', '-v', 'error', '-i', str(path), '-map', '0:v:0', '-f', 'framemd5', '-']).decode()
                    return [line for line in output.splitlines() if line and not line.startswith('#')]
                self.assertEqual(frame_hashes(source), frame_hashes(uploaded))
                missing_audio = binding.model_copy(update={'include_audio': True})
                with self.assertRaisesRegex(ValueError, 'audio track is missing'):
                    await upload_reference_video(client, missing_audio, cap, {'archive.test'})
                self.assertEqual(client.upload_file_to_comfy.await_count, 1)
            finally:
                await client.close()


if __name__ == '__main__':
    unittest.main()
