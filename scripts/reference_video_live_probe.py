"""Bounded live acceptance probe. Does not change published model capability flags."""
import json
import asyncio
import subprocess
import sys
import time
import uuid
from pathlib import Path

import requests
import cv2
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'logs' / 'reference-video-live-probe'
BASE = 'http://100.81.173.100:8188'


def make_synthetic_fixtures():
    """Only generated geometric test patterns; never read creator media."""
    for index, color in enumerate(((255, 80, 20), (30, 180, 120))):
        frame = np.full((256, 256, 3), color, dtype=np.uint8)
        cv2.rectangle(frame, (60, 60), (196, 196), (255, 255, 255), 8)
        cv2.imwrite(str(OUT / f'synthetic_{index}.png'), frame)
    source = OUT / 'synthetic_source.mp4'
    writer = cv2.VideoWriter(str(source), cv2.VideoWriter_fourcc(*'mp4v'), 24, (768, 576))
    for index in range(353):
        frame = np.full((576, 768, 3), 32, dtype=np.uint8)
        center = (80 + (index * 4) % 600, 288 + int(100 * np.sin(index / 20)))
        cv2.circle(frame, center, 44, (230, 110, 20), -1)
        writer.write(frame)
    writer.release()
    video = OUT / 'synthetic_reference_24fps.mp4'
    subprocess.run(['ffmpeg', '-y', '-v', 'error', '-i', str(source), '-f', 'lavfi', '-i', 'anullsrc=r=48000:cl=stereo',
                    '-map', '0:v:0', '-map', '1:a:0', '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-c:a', 'aac',
                    '-t', str(353/24), '-shortest', str(video)], check=True)
    return [OUT / 'synthetic_0.png', OUT / 'synthetic_1.png', video]


def build_graph():
    OUT.mkdir(parents=True, exist_ok=True)
    queue = requests.get(BASE + '/queue', timeout=10).json()
    if queue.get('queue_running') or queue.get('queue_pending'):
        raise RuntimeError('Compute queue is busy; probe not submitted')
    uploaded = []
    for path in make_synthetic_fixtures():
        name = 'freyja_probe_' + uuid.uuid4().hex + path.suffix
        with path.open('rb') as stream:
            response = requests.post(BASE + '/upload/image', files={'image': (name, stream)}, data={'type': 'input', 'overwrite': 'false'}, timeout=60)
        response.raise_for_status()
        data = response.json()
        uploaded.append((data.get('subfolder', '').strip('/') + '/' + data['name']).lstrip('/'))
    (OUT / 'uploads.json').write_text(json.dumps(uploaded), encoding='utf-8')
    code = '''import json,sys
from config import get_config,get_base_dir
from video_reference import ReferenceVideoCapability,workflow_revision
from schemas import ImageGenerationRequest
from workflow_engine import WorkflowEngine
cfg=get_config().model_copy(deep=True)
model=cfg.get_exact_model('minimax-h3-ref-turbo8')
node=cfg.get_node(model.node_id)
# Isolated acceptance instance only; public config remains UNVERIFIED during testing.
model.reference_video=ReferenceVideoCapability(max_videos=1,min_clip_seconds=2,max_clip_seconds=353/24,max_total_clip_seconds=353/24,decode_fps=24,max_decoded_frames=353,max_decode_width=768,max_decode_height=576,max_mixed_assets=3,supports_video_audio=False)
model.reference_video.verified_backend=node.http_url
model.reference_video.verified_revision=workflow_revision(model,get_base_dir())
model.reference_video.status='VERIFIED'
files=json.loads(sys.argv[1])
request=ImageGenerationRequest(model=model.model_name,size='544x960',duration=4,seed=20261025,prompt='subject_definitions: <Subject 1> is the blue square design in <Picture 1>. <Subject 2> is the green background design in <Picture 2>. <Video 1> provides geometric motion.\\nsummary: [reference generation] A simple abstract moving shape.\\nretention_analysis: Use the image colors and the reference motion.\\ndetailed_description: [Shot 1] The blue square moves over a green background. Follow <Video 1> for motion. Static camera.\\noverall_soundscape: Silence.\\nnon_diegetic_music: N/A',references=[{'referenceId':'probe-video','referenceType':'REFERENCE_VIDEO','referenceVideo':'https://archive.invalid/probe.mp4','contentSha256':'a'*64}],node_id=model.node_id,prompt_format='MINIMAX_H3_REF2VA_VIDEO_V1',reference_video_revision=model.reference_video.verified_revision)
graph=WorkflowEngine(cfg).assemble_workflow(request,uploaded_ref_images=files[:2],uploaded_ref_videos=[{'reference_id':'probe-video','filename':files[2],'include_audio':False}])
graph['92']['inputs']['filename_prefix']='Freyja-Acceptance/Turbo8-RefVideo'
print(json.dumps(graph))
'''
    result = subprocess.run(['wsl', '-d', 'Ubuntu', '--', 'docker', 'exec', '-w', '/app', 'freyja-comfy-gateway', 'python', '-c', code, json.dumps(uploaded)], capture_output=True, check=True)
    graph = json.loads(result.stdout.decode('utf-8'))
    (OUT / 'graph.json').write_text(json.dumps(graph, ensure_ascii=False, indent=2), encoding='utf-8')
    response = requests.post(BASE + '/prompt', json={'prompt': graph, 'client_id': 'freyja-live-acceptance-' + uuid.uuid4().hex}, timeout=30)
    data = response.json()
    (OUT / 'submission.json').write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding='utf-8')
    if response.status_code != 200 or data.get('node_errors'):
        print(json.dumps(data, ensure_ascii=False))
        raise RuntimeError('Live ComfyUI validation failed')
    print(json.dumps({'prompt_id': data['prompt_id'], 'state': 'SUBMITTED', 'output': '4s 544x960', 'input_video_frames': 353}), flush=True)


def poll():
    prompt_id = json.loads((OUT / 'submission.json').read_text(encoding='utf-8'))['prompt_id']
    response = requests.get(BASE + '/history/' + prompt_id, timeout=15)
    response.raise_for_status()
    history = response.json().get(prompt_id)
    if history:
        (OUT / 'history.json').write_text(json.dumps(history, ensure_ascii=False, indent=2), encoding='utf-8')
        print(json.dumps({'prompt_id': prompt_id, 'status': history.get('status'), 'outputs': history.get('outputs')}, ensure_ascii=False))
    else:
        queue = requests.get(BASE + '/queue', timeout=10).json()
        print(json.dumps({'prompt_id': prompt_id, 'running': any(item[1] == prompt_id for item in queue.get('queue_running', [])), 'pending': any(item[1] == prompt_id for item in queue.get('queue_pending', []))}))
        running = next((item for item in queue.get('queue_running', []) if item[1] == prompt_id), None)
        if running:
            async def progress():
                import websockets
                client_id = running[3].get('client_id')
                if not client_id:
                    return
                async with websockets.connect(BASE.replace('http:', 'ws:') + '/ws?clientId=' + client_id, open_timeout=5) as socket:
                    for _ in range(3):
                        try:
                            event = json.loads(await asyncio.wait_for(socket.recv(), timeout=3))
                        except asyncio.TimeoutError:
                            break
                        if event.get('type') in ('executing', 'progress', 'execution_error'):
                            print(json.dumps({'event': event['type'], 'data': event.get('data')}, ensure_ascii=False))
            asyncio.run(progress())


if __name__ == '__main__':
    if len(sys.argv) > 1 and sys.argv[1] == 'poll':
        poll()
    else:
        build_graph()
