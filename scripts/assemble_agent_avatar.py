"""Assemble approved imagegen artwork into a Codex Pet v1 sprite package.

Requires Pillow, used only offline. No character art is drawn or synthesized.
Run from any directory: python scripts/assemble_agent_avatar.py
"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont, ImageOps

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "docs/agent-avatar/source"
OUTPUT = ROOT / "front/public/agent/freyja"
DOCS = ROOT / "docs/agent-avatar"
SIZE = (192, 208)
def find_gutter(mask: Image.Image, vertical: bool, fraction: float = .5) -> int:
    length = mask.width if vertical else mask.height
    center = length * fraction
    runs = []
    run_start = None
    for position in range(int(length * (fraction - .08)), int(length * (fraction + .08))):
        box = (position, 0, position + 1, mask.height) if vertical else (0, position, mask.width, position + 1)
        empty = mask.crop(box).getbbox() is None
        if empty and run_start is None:
            run_start = position
        if not empty and run_start is not None:
            runs.append((run_start, position))
            run_start = None
    if run_start is not None:
        runs.append((run_start, int(length * (fraction + .08))))
    if not runs:
        raise ValueError("Keyframe artwork overlaps: regenerate the source with clear transparent gutters")
    start, end = min(runs, key=lambda run: abs((run[0] + run[1]) / 2 - center))
    return (start + end) // 2


def keyframes(path: Path) -> list[Image.Image]:
    sheet = Image.open(path).convert("RGBA")
    if sheet.getchannel("A").getextrema()[0] != 0:
        raise ValueError(f"Source does not have a transparent background: {path}")
    width, height = sheet.size
    mask = sheet.getchannel("A").point(lambda alpha: 255 if alpha >= 112 else 0)
    row_cut = find_gutter(mask, False)
    columns = 4 if path.stem == "running-right" else 2
    boxes = []
    for top, bottom in [(0, row_cut), (row_cut, height)]:
        strip = mask.crop((0, top, width, bottom))
        cuts = [0] + [find_gutter(strip, True, i / columns) for i in range(1, columns)] + [width]
        boxes.extend((cuts[i], top, cuts[i + 1], bottom) for i in range(columns))
    poses = []
    for box in boxes:
        bounds = mask.crop(box).getbbox()
        if bounds is None:
            raise ValueError(f"Missing keyframe in {path}")
        # The bottom of each figure contains its boots. Anchor to that shared
        # landmark so varying hair widths cannot move the whole character.
        foot_band = mask.crop((box[0], box[1] + max(bounds[1], bounds[3] - 22), box[2], box[1] + bounds[3]))
        feet = foot_band.getbbox()
        foot_x = (feet[0] + feet[2]) / 2
        poses.append((box, bounds, foot_x))
    left = min(bounds[0] - foot_x for _, bounds, foot_x in poses)
    right = max(bounds[2] - foot_x for _, bounds, foot_x in poses)
    tallest = max(bounds[3] - bounds[1] for _, bounds, _ in poses)
    source_baselines = [box[1] + bounds[3] - (height / 2 if index >= columns else 0)
                        for index, (box, bounds, _) in enumerate(poses)]
    jumps = [max(source_baselines) - baseline if path.stem == "success" else 0
             for baseline in source_baselines]
    # One shared scale for the entire state, with room for the real jump.
    scale = min((SIZE[0] - 12) / (right - left), (SIZE[1] - 20) / (tallest + max(jumps)))
    foot_anchor = (SIZE[0] - (right - left) * scale) / 2 - left * scale
    frames = []
    for index, (box, bounds, foot_x) in enumerate(poses):
        cell = sheet.crop(box)
        fitted = cell.resize((round(cell.width * scale), round(cell.height * scale)), Image.Resampling.LANCZOS)
        frame = Image.new("RGBA", SIZE)
        frame.alpha_composite(fitted, (round(foot_anchor - foot_x * scale),
                                      round(SIZE[1] - 10 - (bounds[3] + jumps[index]) * scale)))
        frames.append(frame)
    return frames


ROWS = [
    ("idle", "待机", "idle", [0, 1, 2, 3, 2, 1]),
    ("running-right", "向右移动", "running-right", list(range(8))),
    ("running-left", "向左移动", "running-right", list(range(8))),
    ("waving", "招手", "attention", [0, 1, 2, 3]),
    ("jumping", "完成", "success", [0, 1, 2, 3, 0]),
    ("failed", "遇到问题", "error", [0, 1, 2, 3, 2, 1, 0, 1]),
    ("waiting", "等待处理", "attention", [0, 1, 2, 3, 2, 1]),
    ("running", "工作", "working", [0, 1, 2, 3, 2, 1]),
    ("review", "思考", "thinking", [0, 1, 2, 3, 2, 1]),
]


def main() -> None:
    import zipfile
    OUTPUT.mkdir(parents=True, exist_ok=True)
    atlas = Image.new("RGBA", (1536, 1872))
    originals = {source: keyframes(SOURCE / f"{source}.png") for _, _, source, _ in ROWS}
    for row, (name, _, source, order) in enumerate(ROWS):
        for column, index in enumerate(order):
            frame = originals[source][index]
            if name == "running-left":
                frame = ImageOps.mirror(frame)
            atlas.alpha_composite(frame, (column * SIZE[0], row * SIZE[1]))
    sheet = OUTPUT / "spritesheet.webp"
    atlas.save(sheet, lossless=True, method=6)
    manifest = {"id": "freyja", "displayName": "FREYJA", "description": "银白发、紫金花饰的 Q 版创作助手", "spritesheetPath": "spritesheet.webp", "spriteVersionNumber": 1}
    (OUTPUT / "pet.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    pack = OUTPUT / "freyja.codex-pet.zip"
    with zipfile.ZipFile(pack, "w", zipfile.ZIP_DEFLATED) as archive:
        for filename in ["pet.json", "spritesheet.webp"]:
            archive.write(OUTPUT / filename, filename)
    decoded = Image.open(sheet).convert("RGBA")
    assert decoded.size == (1536, 1872) and decoded.getpixel((0, 0))[3] == 0
    report = {"format": "Codex Pet v1", "frameSize": SIZE, "atlasSize": decoded.size, "transparent": True, "rows": []}
    font_path = Path("C:/Windows/Fonts/msyh.ttc")
    font = ImageFont.truetype(str(font_path), 17) if font_path.exists() else ImageFont.load_default()
    preview = Image.new("RGBA", (696, 840), (249, 247, 255, 255))
    draw = ImageDraw.Draw(preview)
    for row, (name, label, _, order) in enumerate(ROWS):
        frames = [decoded.crop((c*192, row*208, (c+1)*192, (row+1)*208)) for c in range(len(order))]
        assert all(frame.getbbox() is not None for frame in frames)
        assert len({hashlib.sha256(f.tobytes()).hexdigest() for f in frames}) >= 4
        for column in range(len(order), 8):
            assert decoded.crop((column*192, row*208, (column+1)*192, (row+1)*208)).getbbox() is None
        report["rows"].append({"name": name, "frames": len(order), "distinctFrames": len({hashlib.sha256(f.tobytes()).hexdigest() for f in frames})})
        x, y = 12 + (row % 3)*228, 12 + (row // 3)*276
        preview.alpha_composite(frames[0], (x+12, y+8))
        draw.text((x+108, y+237), label, font=font, fill=(94, 66, 138), anchor="mm")
    preview.convert("RGB").save(DOCS / "preview.png")
    for filename in ["spritesheet.webp", "freyja.codex-pet.zip"]:
        report[filename] = {"bytes": (OUTPUT / filename).stat().st_size, "sha256": hashlib.sha256((OUTPUT / filename).read_bytes()).hexdigest()}
    (DOCS / "validation.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    main()
