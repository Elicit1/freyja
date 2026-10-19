"""Build or verify repository-authored Skill ZIPs without accessing a running server.

python scripts/package_repository_skills.py story-writing freyja-platform-guide
python scripts/package_repository_skills.py --check story-writing freyja-platform-guide
"""
import argparse
import hashlib
import json
from pathlib import Path

from export_builtin_skills import deterministic_zip


def source_bytes(path):
    # Git checkouts may use CRLF on Windows. Keep textual package hashes portable.
    if path.suffix.lower() in {".md", ".txt", ".yaml", ".yml", ".json"}:
        return path.read_text(encoding="utf-8").encode("utf-8")
    return path.read_bytes()


def package(names, check=False):
    root = Path(__file__).resolve().parents[1] / "skills"
    manifest_path = root / "builtin-manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    entries = {item["name"]: item for item in manifest["skills"]}
    selected = names or [name for name, item in entries.items()
                         if item.get("source") == "repository:skills/" + name]
    staged = []
    for name in selected:
        item = entries.get(name)
        if item is None or item.get("source") != "repository:skills/" + name:
            raise ValueError("Not a repository-authored Skill in the manifest: " + name)
        source = root / name
        files = {path.relative_to(source).as_posix(): source_bytes(path)
                 for path in sorted(source.rglob("*")) if path.is_file()}
        if "SKILL.md" not in files:
            raise ValueError("Missing SKILL.md: " + name)
        data = deterministic_zip(files)
        digest = hashlib.sha256(data).hexdigest()
        index = [{"path": path, "sha256": hashlib.sha256(content).hexdigest(), "size": len(content)}
                 for path, content in sorted(files.items())]
        archive = root / (name + ".zip")
        if check:
            if not archive.is_file() or archive.read_bytes() != data:
                raise ValueError("Package differs from source: " + name)
            if item.get("package") != archive.name or item.get("sha256") != digest or item.get("files") != index:
                raise ValueError("Manifest differs from source: " + name)
        else:
            staged.append((archive, data))
            item.update(package=archive.name, sha256=digest, files=index)
        print(name + (": verified" if check else ": packaged") + ", files=" + str(len(files)))
    if not check:
        for archive, data in staged:
            archive.write_bytes(data)
        manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Verify only; do not change ZIPs or the manifest")
    parser.add_argument("names", nargs="*")
    args = parser.parse_args()
    package(args.names, args.check)
