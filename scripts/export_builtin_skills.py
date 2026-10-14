"""Export current published FREYJA Skills and their complete files for Docker packaging.

python scripts/export_builtin_skills.py --base-url http://127.0.0.1:8080
Read-only API access; never uploads packages or changes the running system.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import tempfile
from urllib.request import urlopen
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED


def fetch(base_url, path, binary=False):
    with urlopen(base_url.rstrip("/") + path, timeout=60) as response:
        data = response.read(20 * 1024 * 1024 + 1)
    if len(data) > 20 * 1024 * 1024:
        raise ValueError("Skill response exceeds 20 MiB")
    if binary:
        return data
    result = json.loads(data)
    if result.get("code") != 200:
        raise ValueError("Skill API request failed: " + path)
    return result["data"]


def published(base_url):
    skills = fetch(base_url, "/system/skills")
    return {item["name"]: item for item in skills if item.get("currentVersionId") is not None}


def validate_archive(data, preview):
    files = {}
    with ZipFile(io.BytesIO(data)) as archive:
        entries = [entry for entry in archive.infolist() if not entry.is_dir()]
        if len(entries) > 200 or sum(entry.file_size for entry in entries) > 50 * 1024 * 1024:
            raise ValueError("Skill archive exceeds supported package limits")
        for entry in entries:
            path = PurePosixPath(entry.filename)
            if path.is_absolute() or ".." in path.parts or "\\" in entry.filename or ":" in entry.filename:
                raise ValueError("Unsafe archive path: " + entry.filename)
            if entry.filename in files or entry.file_size > 10 * 1024 * 1024:
                raise ValueError("Duplicate or oversized archive entry: " + entry.filename)
            files[entry.filename] = archive.read(entry)
    if files.get("SKILL.md") != preview["content"].encode("utf-8"):
        raise ValueError("Exported SKILL.md does not match the pinned published version")
    indexed = {item["relativePath"]: item for item in preview["files"]}
    if set(files) != set(indexed) | {"SKILL.md"}:
        raise ValueError("Exported package is missing files or includes unindexed files")
    for name, item in indexed.items():
        if "sha256:" + hashlib.sha256(files[name]).hexdigest() != item["contentHash"]:
            raise ValueError("Published file hash mismatch: " + name)
        if len(files[name]) != int(item["contentSize"]):
            raise ValueError("Published file size mismatch: " + name)
    digest = hashlib.sha256()
    for name in sorted(files):
        digest.update(name.encode("utf-8") + b"\0" + files[name])
    if "sha256:" + digest.hexdigest() != preview["version"]["contentHash"]:
        raise ValueError("Published package hash mismatch")
    return files


def deterministic_zip(files):
    output = io.BytesIO()
    with ZipFile(output, "w", compression=ZIP_DEFLATED, compresslevel=9) as archive:
        for name in sorted(files):
            entry = ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            entry.compress_type = ZIP_DEFLATED
            entry.external_attr = 0o100644 << 16
            archive.writestr(entry, files[name])
    return output.getvalue()


def export(base_url, output):
    catalog = published(base_url)
    if not catalog:
        raise ValueError("The local system has no published Skills")
    packages = {}
    manifest = {"formatVersion": 1, "source": "FREYJA current published system Skills", "skills": []}
    for name, item in sorted(catalog.items()):
        if not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", name):
            raise ValueError("Invalid Skill name: " + name)
        version_id = str(item["currentVersionId"])
        preview = fetch(base_url, "/system/skills/versions/" + version_id + "/preview")
        data = fetch(base_url, "/system/skills/versions/" + version_id + "/download", binary=True)
        files = validate_archive(data, preview)
        packages[name] = deterministic_zip(files)
        manifest["skills"].append({
            "name": name, "displayName": item["displayName"], "description": item["description"],
            "sourceRevision": preview["version"]["version"], "package": name + ".zip",
            "sha256": hashlib.sha256(packages[name]).hexdigest(),
            "files": [{"path": path, "sha256": hashlib.sha256(content).hexdigest(), "size": len(content)}
                      for path, content in sorted(files.items())],
        })
    current = published(base_url)
    if {name: str(item["currentVersionId"]) for name, item in catalog.items()} != {
            name: str(item["currentVersionId"]) for name, item in current.items()}:
        raise ValueError("Published Skills changed during export; retry to obtain a consistent snapshot")

    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="skill-export-", dir=output) as directory:
        staged = Path(directory)
        for name, data in packages.items():
            (staged / (name + ".zip")).write_bytes(data)
        (staged / "builtin-manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        for path in staged.iterdir():
            path.replace(output / path.name)
    for item in manifest["skills"]:
        print(item["name"] + ": revision=" + str(item["sourceRevision"]) + ", files=" + str(len(item["files"])))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--output", type=Path, default=Path(__file__).resolve().parents[1] / "skills")
    args = parser.parse_args()
    export(args.base_url, args.output)
