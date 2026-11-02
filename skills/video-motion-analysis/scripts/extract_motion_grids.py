#!/usr/bin/env python3
"""
Extract motion contact-sheet grids and metadata from a video file for Agent inspection.

Usage:
    python extract_motion_grids.py --video input.mp4 --output-dir ./grids/ --interval 1.0 --tile 5x5
"""

import argparse
import json
import os
import subprocess
import sys


def run_command(cmd):
    p = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, shell=True)
    if p.returncode != 0:
        raise RuntimeError(f"Command failed (code {p.returncode}): {cmd}\nStderr: {p.stderr}")
    return p.stdout


def probe_video(video_path):
    cmd = f'ffprobe -v error -show_entries format=duration:stream=width,height,r_frame_rate,codec_type -of json "{video_path}"'
    stdout = run_command(cmd)
    data = json.loads(stdout)
    duration = float(data.get("format", {}).get("duration", 0.0))
    video_stream = next((s for s in data.get("streams", []) if s.get("codec_type") == "video"), {})
    width = video_stream.get("width", 0)
    height = video_stream.get("height", 0)
    fps_str = video_stream.get("r_frame_rate", "25/1")
    if "/" in fps_str:
        num, den = fps_str.split("/")
        fps = float(num) / float(den) if float(den) != 0 else 25.0
    else:
        fps = float(fps_str)
    return {"duration": duration, "width": width, "height": height, "fps": fps}


def export_grid(video_path, output_image, interval=1.0, tile="5x5", start_time=None, end_time=None):
    time_args = ""
    if start_time is not None:
        time_args += f" -ss {start_time}"
    if end_time is not None:
        time_args += f" -to {end_time}"

    vf = f"fps=1/{interval},scale=320:180,tile={tile}" if interval >= 1.0 else f"fps={int(1/interval)},scale=320:180,tile={tile}"
    cmd = f'ffmpeg -y {time_args} -i "{video_path}" -vf "{vf}" -frames:v 1 "{output_image}"'
    run_command(cmd)


def main():
    parser = argparse.ArgumentParser(description="Extract video contact sheets for motion analysis.")
    parser.add_argument("--video", required=True, help="Path to input video file.")
    parser.add_argument("--output-dir", default="./grids", help="Output directory for generated grid images.")
    parser.add_argument("--interval", type=float, default=1.0, help="Sampling interval in seconds (default 1.0s).")
    parser.add_argument("--tile", default="5x5", help="Tile layout (default 5x5).")
    args = parser.parse_args()

    if not os.path.isfile(args.video):
        print(f"Error: video file '{args.video}' not found.", file=sys.stderr)
        sys.exit(1)

    os.makedirs(args.output_dir, exist_ok=True)
    meta = probe_video(args.video)
    print(f"Video metadata: Duration={meta['duration']:.2f}s, Res={meta['width']}x{meta['height']}, FPS={meta['fps']:.2f}")

    # 1. Macro Grid
    macro_out = os.path.join(args.output_dir, "macro_grid.jpg")
    print(f"Generating macro contact sheet: {macro_out}...")
    export_grid(args.video, macro_out, interval=args.interval, tile=args.tile)

    # 2. Save metadata summary
    summary_path = os.path.join(args.output_dir, "motion_meta.json")
    with open(summary_path, "w", encoding="utf-8") as f:
        json.dump(meta, f, indent=2, ensure_ascii=False)

    print(f"Success! Grids generated in '{args.output_dir}'.")


if __name__ == "__main__":
    main()
