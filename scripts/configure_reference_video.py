"""Prepare or publish a narrowly scoped Turbo8 reference-video capability.

Publishing requires a successful live synthetic probe and preserves other model parameters.
"""
import json
import subprocess
import sys
from pathlib import Path

import requests
import yaml

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'logs/reference-video-live-probe'
MODEL_ID = '2108013966187388929'
MODEL_CODE = 'minimax-h3-ref-turbo8'
BACKEND = 'http://127.0.0.1:8080'
GATEWAY = 'http://127.0.0.1:8000'
FIELDS = ('id', 'providerId', 'modelCode', 'modelName', 'modelType', 'temperature',
          'maxTokens', 'topP', 'paramsJson', 'maxImages', 'maxAudios', 'maxVideos',
          'sortOrder', 'status', 'remark')
LIMITS = dict(schema_version=1, status='UNVERIFIED', adapter='MINIMAX_H3_REF_VIDEO_V1',
              max_videos=1, min_clip_seconds=2, max_clip_seconds=353/24,
              max_total_clip_seconds=353/24, decode_fps=24, max_decoded_frames=353,
              max_decode_width=768, max_decode_height=576, max_mixed_assets=3,
              supports_video_audio=False, max_download_bytes=128*1024*1024,
              condition_node_id='136', output_node_ids=['130'])
NAMES = dict(schema_version='schemaVersion', max_videos='maxVideos',
             min_clip_seconds='minClipSeconds', max_clip_seconds='maxClipSeconds',
             max_total_clip_seconds='maxTotalClipSeconds', decode_fps='decodeFps',
             max_decoded_frames='maxDecodedFrames', max_decode_width='maxDecodeWidth',
             max_decode_height='maxDecodeHeight', max_mixed_assets='maxMixedAssets',
             supports_video_audio='supportsVideoAudio', max_download_bytes='maxDownloadBytes')


def api(method, path, **kwargs):
    response = requests.request(method, BACKEND + path, timeout=30, **kwargs)
    response.raise_for_status()
    result = response.json()
    if result.get('code') != 200:
        raise RuntimeError(str(result.get('msg')))
    return result.get('data')


def prepare():
    OUT.mkdir(parents=True, exist_ok=True)
    current = api('GET', '/ai/model/' + MODEL_ID)
    backup = OUT / 'model_before.json'
    if not backup.exists():
        backup.write_text(json.dumps(current, ensure_ascii=False, indent=2), encoding='utf-8')
    path = ROOT / 'comfy_gateway/nodes/machine_local.yaml'
    text = path.read_text(encoding='utf-8')
    backup = OUT / 'machine_local_before.yaml'
    if not backup.exists():
        backup.write_text(text, encoding='utf-8')
    # Replace only the exact model's capability block; retain comments and all other models.
    marker = '  - model_name: "' + MODEL_CODE + '"'
    start = text.index(marker)
    end = text.find('\n  - model_name:', start + len(marker))
    if end < 0:
        end = len(text)
    section = text[start:end]
    cap_start = section.index('    reference_video:')
    cap_end = section.index('    default_steps:', cap_start)
    block = yaml.safe_dump({'reference_video': LIMITS}, sort_keys=False)
    block = ''.join('    ' + line + '\n' for line in block.splitlines())
    section = section[:cap_start] + block + section[cap_end:]
    path.write_text(text[:start] + section + text[end:], encoding='utf-8')
    params = json.loads(current.get('paramsJson') or '{}')
    params.setdefault('videoGeneration', {})['referenceVideo'] = {
        NAMES.get(key, key): value for key, value in LIMITS.items()
        if key not in ('condition_node_id', 'output_node_ids')}
    payload = {key: current.get(key) for key in FIELDS}
    payload['paramsJson'] = json.dumps(params, ensure_ascii=False)
    payload['maxVideos'] = 1
    api('PUT', '/ai/model', json=payload)
    print(json.dumps({'model': MODEL_CODE, 'status': 'UNVERIFIED', 'platform_config_saved': True}))


def publish():
    verification = json.loads((OUT / 'output_verification.json').read_text(encoding='utf-8'))
    if (verification.get('frames'), verification.get('fps'), verification.get('width'), verification.get('height')) != (107, 24, 544, 960):
        raise RuntimeError('Decoded live-test video verification is required')
    history = json.loads((OUT / 'history.json').read_text(encoding='utf-8'))
    if history.get('status', {}).get('status_str') != 'success' or not history.get('status', {}).get('completed'):
        raise RuntimeError('A completed, successful live probe is required')
    if not history.get('outputs', {}).get('92'):
        raise RuntimeError('Live probe has no actual video output')
    graph = json.loads((OUT / 'graph.json').read_text(encoding='utf-8'))
    if graph['136']['inputs'].get('ref_videos.ref_video_0') != ['136_ref_vid_0', 0]:
        raise RuntimeError('Live probe did not bind the video frame sequence')
    if len([key for key in graph['136']['inputs'] if key.startswith('ref_images.ref_image_')]) != 2:
        raise RuntimeError('Live probe did not include both test images')
    response = requests.get(GATEWAY + '/v1/models/' + MODEL_CODE + '/capabilities', timeout=20)
    response.raise_for_status()
    cap = response.json()
    if not cap.get('healthy') or cap['referenceVideo']['maxDecodedFrames'] != 353:
        raise RuntimeError('Gateway must be healthy and use the prepared contract')
    revision = cap['workflowRevision']
    path = ROOT / 'comfy_gateway/nodes/machine_local.yaml'
    text = path.read_text(encoding='utf-8')
    start = text.index('  - model_name: "' + MODEL_CODE + '"')
    end = text.find('\n  - model_name:', start + 1)
    if end < 0:
        end = len(text)
    section = text[start:end]
    local = yaml.safe_load(text)
    backend = local['http_url']
    block = section.index('    reference_video:')
    block_end = section.index('    default_steps:', block)
    limits = dict(LIMITS, status='VERIFIED', verified_backend=backend, verified_revision=revision)
    replacement = ''.join('    ' + line + '\n' for line in yaml.safe_dump({'reference_video': limits}, sort_keys=False).splitlines())
    path.write_text(text[:start] + section[:block] + replacement + section[block_end:] + text[end:], encoding='utf-8')
    subprocess.run(['wsl', '-d', 'Ubuntu', '--', 'docker', 'restart', 'freyja-comfy-gateway'], check=True, capture_output=True)
    current = api('GET', '/ai/model/' + MODEL_ID)
    params = json.loads(current.get('paramsJson') or '{}')
    contract = params['videoGeneration']['referenceVideo']
    contract['status'] = 'VERIFIED'
    contract['workflowRevision'] = revision
    payload = {key: current.get(key) for key in FIELDS}
    payload['paramsJson'] = json.dumps(params, ensure_ascii=False)
    api('PUT', '/ai/model', json=payload)
    report = {'modelCode': MODEL_CODE, 'workflowRevision': revision,
              'testPromptId': json.loads((OUT/'submission.json').read_text())['prompt_id'],
              'syntheticOnly': True, 'productionShotSubmitted': False,
              'scope': 'One silent 24fps reference clip, two reference images, input transport and full sampler/video-output integration. Creative motion fidelity and 768x1344 resource usage are not certified.',
              'outputVerification': verification}
    (OUT/'acceptance.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(report))


if __name__ == '__main__':
    if len(sys.argv) != 2 or sys.argv[1] not in ('prepare', 'publish'):
        raise SystemExit('Usage: configure_reference_video.py prepare|publish')
    (prepare if sys.argv[1] == 'prepare' else publish)()
