"""Build installable node and platform-guide ZIPs without contacting a compute node."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile

ROOT=Path(__file__).resolve().parents[1]


def package(folder, destination):
    files=sorted(p for p in folder.rglob('*') if p.is_file() and '__pycache__' not in p.parts and p.suffix not in {'.pyc','.pyo'})
    destination.parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(destination,'w',zipfile.ZIP_DEFLATED) as archive:
        for path in files:archive.write(path,path.relative_to(folder.parent).as_posix())
    with zipfile.ZipFile(destination) as archive:
        if archive.testzip() is not None:raise RuntimeError('ZIP verification failed')
    return {'file':str(destination),'sha256':hashlib.sha256(destination.read_bytes()).hexdigest(),'files':len(files)}


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--output',type=Path,default=ROOT/'logs/h3-continuation-modes-release')
    args=parser.parse_args()
    results=[package(ROOT/'comfy_nodes/freyja_h3_continuation',args.output/'freyja_h3_continuation.zip'),
             package(ROOT/'skills/freyja-platform-guide',ROOT/'skills/freyja-platform-guide.zip')]
    (args.output/'packages.json').write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(results,ensure_ascii=False))


if __name__=='__main__':main()
