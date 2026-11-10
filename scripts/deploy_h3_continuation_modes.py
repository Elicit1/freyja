"""Activate prepared images only after read-only SQL and compute-queue checks.

Run from the Windows workspace when the compute node is online and the node ZIP
has been installed. Does not generate video or restart the compute node.
"""
import argparse
from datetime import datetime
from pathlib import Path
import subprocess

ROOT=Path(__file__).resolve().parents[1]


def docker(*args,**kwargs):
    return subprocess.run(['wsl','-d','Ubuntu','--exec','docker',*args],check=True,**kwargs)


def preflight():
    # Credentials stay inside the already configured containers and are never printed.
    sql="SELECT COUNT(*) FROM render_task WHERE status IN ('QUEUED','PENDING','RENDERING','RUNNING'); SELECT COUNT(*) FROM ai_task WHERE status IN ('QUEUED','PENDING','RENDERING','RUNNING'); SELECT COUNT(*) FROM shot_continuation_preparation WHERE status='PREPARING' AND updated_at>DATE_SUB(NOW(),INTERVAL 30 MINUTE);"
    result=docker('exec','-i','freyja-mysql','sh','-c','export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql -uroot --batch --raw --skip-column-names freyja',input=sql,text=True,capture_output=True)
    if any(int(line) for line in result.stdout.splitlines() if line.strip()):raise RuntimeError('Active render/AI/preparation tasks; deployment stopped')
    probe='''import json,urllib.request
from config import get_config
required={'FreyjaH3ChromaNoise','FreyjaH3Continue','FreyjaH3Restore','FreyjaH3SaveLatent',
 'FreyjaH3CheckFrames','FreyjaH3UpscaleEncode','FreyjaH3LoadLatent',
 'FreyjaH3LatentResize','FreyjaH3FinishSize','FreyjaH3TileModel'}
for node in get_config().nodes.values():
 if not node.enabled or not any(m.h3_extensions for m in node.models):continue
 base=node.http_url.rstrip('/')
 info=json.load(urllib.request.urlopen(base+'/object_info',timeout=8))
 missing=required-set(info)
 if missing:raise RuntimeError('Missing FREYJA H3 nodes: '+', '.join(sorted(missing)))
 queue=json.load(urllib.request.urlopen(base+'/queue',timeout=8))
 if queue.get('queue_running') or queue.get('queue_pending'):raise RuntimeError('Compute queue is busy')
print('SQL and compute preflight passed; no video was submitted')
'''
    docker('exec','-i','freyja-comfy-gateway','python','-',input=probe,text=True)


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--tag',default='continuation-modes-20261109');parser.add_argument('--check-only',action='store_true')
    args=parser.parse_args();preflight()
    if args.check_only:return
    suffix=datetime.now().strftime('%Y%m%d-%H%M%S')
    for part in ('backend','comfy-gateway','frontend'):docker('image','inspect',f'freyja-{part}:{args.tag}',stdout=subprocess.DEVNULL)
    for part in ('backend','comfy-gateway','frontend'):
        docker('tag',f'astrag1/freyja-{part}:1.0.0',f'freyja-{part}:before-modes-{suffix}')
        docker('tag',f'freyja-{part}:{args.tag}',f'astrag1/freyja-{part}:1.0.0')
    linux_root='/mnt/'+str(ROOT.drive)[0].lower()+'/'+ROOT.as_posix().split(':/',1)[1]
    docker('compose','--project-directory',linux_root,'-f',linux_root+'/docker-compose.yml','-f',linux_root+'/docker-compose.override.yml','up','-d','--no-build','--no-deps','backend','comfy-gateway','frontend')
    print('Activated four-mode application images. Rollback image suffix:',f'before-modes-{suffix}')


if __name__=='__main__':main()
