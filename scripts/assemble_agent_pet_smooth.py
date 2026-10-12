"""Package imagegen's 16-pose sheets; only crop, align and resize approved artwork.

Creates a denser atlas for the built-in Freyja renderer and a standard Codex Pet v1
atlas/ZIP for portable uploads. Original source artwork remains untouched.
"""
from __future__ import annotations

import hashlib
import json
import zipfile
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont, ImageOps
import cv2
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "docs/agent-avatar/source/smooth"
OUTPUT = ROOT / "front/public/agent/freyja"
DOCS = ROOT / "docs/agent-avatar"
SIZE = (192, 208)
ROWS = [
    ("idle", "待机", "idle", 6),
    ("running-right", "向右移动", "running-right", 8),
    ("running-left", "向左移动", "running-right", 8),
    ("waving", "招手", "attention", 4),
    ("jumping", "完成", "success", 5),
    ("failed", "遇到问题", "error", 8),
    ("waiting", "等待处理", "attention", 6),
    ("running", "工作", "working", 6),
    ("review", "思考", "thinking", 6),
]


def poses(name: str) -> list[Image.Image]:
    sheet = Image.open(SOURCE / f"{name}.png").convert("RGBA")
    assert sheet.getchannel("A").getextrema()[0] == 0, f"{name}: missing transparency"
    mask = sheet.getchannel("A").point(lambda alpha: 255 if alpha >= 112 else 0)
    column_counts = np.count_nonzero(np.asarray(mask), axis=0)
    column_cuts = [0]
    for part in range(1, 4):
        center = round(part * sheet.width / 4)
        positions = np.arange(center - 28, center + 29)
        candidates = positions[column_counts[positions] == column_counts[positions].min()]
        column_cuts.append(int(candidates[np.argmin(np.abs(candidates - center))]))
    column_cuts.append(sheet.width)
    cells = []
    for row in range(4):
        for column in range(4):
            left, right = column_cuts[column:column + 2]
            strip = np.asarray(mask.crop((left, 0, right, sheet.height)))
            row_cuts = [0]
            for part in range(1, 4):
                center = round(part * sheet.height / 4)
                positions = np.arange(center - 28, center + 29)
                occupied = np.count_nonzero(strip[positions], axis=1)
                candidates = positions[occupied == occupied.min()]
                row_cuts.append(int(candidates[np.argmin(np.abs(candidates - center))]))
            row_cuts.append(sheet.height)
            top, bottom = row_cuts[row:row + 2]
            cell = sheet.crop((left, top, right, bottom))
            pixels = np.asarray(cell)
            _, components, stats, _ = cv2.connectedComponentsWithStats((pixels[:, :, 3] >= 112).astype(np.uint8), 8)
            dominant = 1 + int(np.argmax(stats[1:, cv2.CC_STAT_AREA]))
            x, y, width, height = stats[dominant, :4]
            # A regular sheet may place the neighboring pose's boot at the next cell's top.
            # Use the main connected silhouette as the crop boundary, preserving original pixels.
            bounds = (max(0, int(x) - 2), max(0, int(y) - 2), min(cell.width, int(x + width) + 2), min(cell.height, int(y + height) + 2))
            # A boot can lift or detach from the connected silhouette. Use the broad upper-head
            # landmark for horizontal alignment, so changing foot contacts cannot shift the torso.
            head = (components == dominant)[int(y + height * .08):int(y + height * .34)]
            head_x = float(np.where(head)[1].mean())
            baseline = top + bounds[3] - row * sheet.height / 4
            cells.append((cell, bounds, head_x, baseline))
    # Uniform scale through each entire action, a shared head center and a fixed ground baseline.
    left = min(bounds[0] - x for _, bounds, x, _ in cells)
    right = max(bounds[2] - x for _, bounds, x, _ in cells)
    height = max(bounds[3] - bounds[1] for _, bounds, _, _ in cells)
    scale = min((SIZE[0] - 16) / (right - left), (SIZE[1] - 24) / height)
    anchor = (SIZE[0] - (right - left) * scale) / 2 - left * scale
    frames = []
    for cell, bounds, x, baseline in cells:
        # Crop each occupied cell before resampling so one pose cannot bleed into its neighbor.
        artwork = cell.crop(bounds)
        fitted = artwork.resize((round(artwork.width * scale), round(artwork.height * scale)), Image.Resampling.LANCZOS)
        frame = Image.new("RGBA", SIZE)
        jump = min(8, round((max(item[3] for item in cells) - baseline) * scale)) if name == "success" else 0
        frame.alpha_composite(fitted, (round(anchor + (bounds[0] - x) * scale), SIZE[1] - 12 - fitted.height - jump))
        frames.append(frame)
    return frames


def digest(path: Path) -> dict:
    data = path.read_bytes()
    return {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}


def main() -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    originals = {name: poses(name) for name in {row[2] for row in ROWS}}
    dense = Image.new("RGBA", (SIZE[0] * 16, SIZE[1] * 9))
    portable = Image.new("RGBA", (SIZE[0] * 8, SIZE[1] * 9))
    report = {"frameSize": SIZE, "denseAtlasSize": dense.size, "portableAtlasSize": portable.size, "rows": []}
    preview = Image.new("RGBA", (16 * 100, 9 * 126), (249, 247, 255, 255))
    draw = ImageDraw.Draw(preview)
    font_path = Path("C:/Windows/Fonts/msyh.ttc")
    font = ImageFont.truetype(str(font_path), 13) if font_path.exists() else ImageFont.load_default()
    for row, (name, label, origin, count) in enumerate(ROWS):
        frames = originals[origin]
        if name == "running-left":
            frames = [ImageOps.mirror(frame) for frame in frames]
        selected = [round(index * 16 / count) % 16 for index in range(count)]
        for column, frame in enumerate(frames):
            dense.alpha_composite(frame, (column * SIZE[0], row * SIZE[1]))
            thumb = frame.resize((96, 104), Image.Resampling.LANCZOS)
            preview.alpha_composite(thumb, (column * 100 + 2, row * 126))
        for column, index in enumerate(selected):
            portable.alpha_composite(frames[index], (column * SIZE[0], row * SIZE[1]))
        draw.text((4, row * 126 + 107), label, fill=(94, 66, 138), font=font)
        hashes = {hashlib.sha256(frame.tobytes()).hexdigest() for frame in frames}
        assert len(hashes) == 16, f"{name}: repeated pose drawings"
        report["rows"].append({"name": name, "frames": 16, "distinctFrames": len(hashes), "portableFrames": count, "selected": selected})
    smooth_path = OUTPUT / "spritesheet-smooth-v2.webp"
    dense.save(smooth_path, lossless=True, method=6)
    # Preserve the original portable atlas and package; publish this revision under new names.
    portable_path = OUTPUT / "spritesheet-smooth-codex.webp"
    portable.save(portable_path, lossless=True, method=6)
    manifest = {"id": "freyja", "displayName": "FREYJA", "description": "银白发、紫金花饰的 Q 版创作助手",
                "spritesheetPath": portable_path.name, "spriteVersionNumber": 1}
    manifest_path = OUTPUT / "pet-smooth.json"
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    package_path = OUTPUT / "freyja-smooth.codex-pet.zip"
    with zipfile.ZipFile(package_path, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.write(manifest_path, "pet.json")
        archive.write(portable_path, portable_path.name)
    for path in [smooth_path, portable_path, package_path]:
        decoded = Image.open(path).convert("RGBA") if path != package_path else None
        if decoded is not None:
            assert decoded.getpixel((0, 0))[3] == 0
        report[path.name] = digest(path)
    preview.convert("RGB").save(DOCS / "smooth-frames-preview.png")
    (DOCS / "smooth-validation.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    main()
