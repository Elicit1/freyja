"""Install this repository's node package into an explicitly named ComfyUI root.

Never restarts ComfyUI or submits a task. Existing nodes are moved to a checked
backup path inside the named ComfyUI root before atomic installation.
"""
import argparse
from datetime import datetime, timezone
from pathlib import Path
import shutil
import uuid

SOURCE=Path(__file__).resolve().parents[1]/'comfy_nodes/freyja_h3_continuation'


def inside(root,path):
    resolved=path.resolve()
    if not resolved.is_relative_to(root):raise ValueError('Installation path escapes the explicitly named ComfyUI root')
    return resolved


def install(raw_root):
    root=Path(raw_root).resolve(strict=True)
    if not (root/'main.py').is_file() or not (root/'custom_nodes').is_dir():
        raise ValueError('Expected ComfyUI directory containing main.py and custom_nodes/')
    target=inside(root,root/'custom_nodes/freyja_h3_continuation')
    stage=inside(root,root/'custom_nodes'/('.freyja_h3_staging_'+uuid.uuid4().hex))
    backup=inside(root,root/'freyja_node_backups'/(datetime.now(timezone.utc).strftime('%Y%m%d-%H%M%S')+'-'+uuid.uuid4().hex[:8])/'freyja_h3_continuation')
    if (root/'custom_nodes/freyja_h3_continuation').is_symlink():raise ValueError('Refusing to replace a symbolic-link node directory')
    files=[p for p in SOURCE.rglob('*') if p.is_file() and '__pycache__' not in p.parts and p.suffix not in {'.pyc','.pyo'}]
    stage.mkdir()
    moved=False
    try:
        for source in files:
            output=inside(root,stage/source.relative_to(SOURCE));output.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(source,output)
        if target.exists():
            backup.parent.mkdir(parents=True,exist_ok=True)
            # Both resolved paths were verified to remain inside the explicitly named root.
            target.rename(backup);moved=True
        stage.rename(target)
    except BaseException:
        if moved and not target.exists():backup.rename(target)
        raise
    finally:
        if stage.exists():
            # Only delete the exact checked staging directory created by this invocation.
            inside(root,stage);shutil.rmtree(stage)
    print('Installed:',target)
    if moved:print('Backup:',backup)
    print('Use the ComfyUI Python environment to install requirements.txt if necessary. Restart only when its queue is idle.')


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--comfy-root',required=True)
    install(parser.parse_args().comfy_root)
