#!/usr/bin/env python3
"""Produce timing evidence for choreography; never infer genre or downbeats.

Requires Python 3.10+, numpy, ffmpeg and ffprobe. Input is a local file.
Source media is read only. All event times use source-file seconds.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import shutil
import subprocess
import sys
from pathlib import Path

try:
    import numpy as np
except ImportError:
    raise SystemExit("Missing dependency: numpy. Install it in the chosen Python environment.")

SAMPLE_RATE = 16000
HOP = 160
WINDOW = 1024
STEP = HOP / SAMPLE_RATE


def run(command: list[str]) -> bytes:
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
    if result.returncode:
        detail = result.stderr.decode("utf-8", errors="replace")[-2000:]
        raise ValueError(f"{command[0]} failed: {detail}")
    return result.stdout


def probe(path: Path) -> dict:
    data = json.loads(run([
        "ffprobe", "-v", "error", "-select_streams", "a:0",
        "-show_entries", "stream=sample_rate,channels,duration,start_time:format=duration,start_time",
        "-of", "json", str(path),
    ]))
    streams = data.get("streams", [])
    if not streams:
        raise ValueError("The input has no readable audio stream.")
    stream = streams[0]
    duration = stream.get("duration", data.get("format", {}).get("duration"))
    if duration in (None, "N/A"):
        duration = data.get("format", {}).get("duration")
    start_time = stream.get("start_time")
    return {
        "reported_duration_seconds": float(duration) if duration not in (None, "N/A") else None,
        "sample_rate": int(stream["sample_rate"]) if stream.get("sample_rate") else None,
        "channels": stream.get("channels"),
        "stream_start_time_seconds": float(start_time) if start_time not in (None, "N/A") else None,
        "duration_status": "media_metadata_not_independent_sample_accuracy_verification",
    }


def decode(path: Path, start: float, end: float | None) -> np.ndarray:
    # Seek after the input so ffmpeg decodes/discards samples to the requested point.
    command = ["ffmpeg", "-v", "error", "-nostdin", "-i", str(path)]
    command += ["-ss", str(start)]
    if end is not None:
        command += ["-t", str(end - start)]
    command += ["-map", "0:a:0", "-vn", "-ac", "1", "-ar", str(SAMPLE_RATE),
                "-f", "f32le", "pipe:1"]
    samples = np.frombuffer(run(command), dtype="<f4").astype(np.float64)
    if not len(samples):
        raise ValueError("The requested analysis interval contains no decoded audio samples.")
    if not np.isfinite(samples).all():
        raise ValueError("Decoded audio contains non-finite samples.")
    return samples


def features(samples: np.ndarray) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    padded = np.pad(samples, (WINDOW // 2, WINDOW // 2))
    count = 1 + (len(padded) - WINDOW) // HOP
    window = np.hanning(WINDOW)
    novelty = np.zeros(count)
    rms = np.zeros(count)
    centroids = np.zeros(count)
    frequencies = np.fft.rfftfreq(WINDOW, 1 / SAMPLE_RATE)
    previous = np.zeros(WINDOW // 2 + 1)
    # Work in batches instead of allocating the whole track's STFT.
    for begin in range(0, count, 512):
        ids = np.arange(begin, min(begin + 512, count))
        frames = padded[ids[:, None] * HOP + np.arange(WINDOW)]
        rms[ids] = np.sqrt(np.mean(frames * frames, axis=1))
        magnitude = np.abs(np.fft.rfft(frames * window, axis=1))
        spectral = np.log1p(magnitude * 10)
        differences = np.diff(np.vstack([previous, spectral]), axis=0)
        novelty[ids] = np.mean(np.maximum(differences, 0), axis=1)
        centroids[ids] = (magnitude @ frequencies) / np.maximum(magnitude.sum(axis=1), 1e-12)
        previous = spectral[-1]
    novelty = np.convolve(novelty, np.array([0.25, 0.5, 0.25]), mode="full")[1:-1]
    scale = np.percentile(novelty, 95)
    if scale > 1e-10:
        novelty /= scale
    else:
        novelty[:] = 0
    return novelty, rms, centroids


def onset_indices(novelty: np.ndarray) -> np.ndarray:
    if len(novelty) < 3 or not np.any(novelty):
        return np.array([], dtype=int)
    threshold = max(0.2, float(np.median(novelty) + 0.4 * np.std(novelty)))
    candidates = np.where(
        (novelty[1:-1] >= novelty[:-2]) & (novelty[1:-1] > novelty[2:]) &
        (novelty[1:-1] >= threshold)
    )[0] + 1
    # Suppress nearby peaks by salience, not by arbitrary first-arrival order.
    selected: list[int] = []
    occupied = np.zeros(len(novelty), dtype=bool)
    radius = round(0.09 / STEP)
    for index in candidates[np.argsort(novelty[candidates])[::-1]]:
        if not occupied[index]:
            selected.append(int(index))
            occupied[max(0, index - radius):min(len(novelty), index + radius + 1)] = True
    return np.array(sorted(selected), dtype=int)


def tempos(novelty: np.ndarray, onsets: np.ndarray, duration: float) -> list[dict]:
    if duration < 3 or len(onsets) < 4:
        return []
    values = np.maximum(novelty - np.median(novelty), 0)
    size = 1 << (2 * len(values) - 1).bit_length()
    spectrum = np.fft.rfft(values, size)
    correlation = np.fft.irfft(spectrum * spectrum.conjugate(), size)[:len(values)]
    correlation /= np.maximum(np.arange(len(values), 0, -1), 1)
    if correlation[0] < 1e-12:
        return []
    correlation /= correlation[0]
    low, high = math.ceil(60 / (200 * STEP)), min(len(values) - 2, int(60 / (50 * STEP)))
    if high <= low:
        return []
    choices = []
    for lag in range(low, high + 1):
        if correlation[lag] >= correlation[lag - 1] and correlation[lag] > correlation[lag + 1]:
            delta = 0.0
            denominator = correlation[lag - 1] - 2 * correlation[lag] + correlation[lag + 1]
            if abs(denominator) > 1e-12:
                delta = float(np.clip(0.5 * (correlation[lag - 1] - correlation[lag + 1]) / denominator,
                                     -0.5, 0.5))
            bpm = 60 / ((lag + delta) * STEP)
            score = float(max(0, correlation[lag]))
            # Ranking is a heuristic, not a genre/tempo truth or confidence probability.
            ranking = score * (1 - 0.04 * abs(math.log2(bpm / 110)))
            choices.append((ranking, {"bpm": round(bpm, 3), "periodicity_score": round(score, 4)}))
    choices.sort(key=lambda item: item[0], reverse=True)
    if not choices or choices[0][1]["periodicity_score"] < 0.12:
        return []
    minimum_score = max(0.08, max(item[1]["periodicity_score"] for item in choices) * 0.25)
    distinct = []
    for _, item in choices:
        if item["periodicity_score"] < minimum_score:
            continue
        if all(abs(item["bpm"] - previous["bpm"]) > 2 for previous in distinct):
            distinct.append(item)
        if len(distinct) == 5:
            break
    return distinct


def make_grid(novelty: np.ndarray, onsets: np.ndarray, start: float, duration: float,
              bpm: float, anchor: float | None, supplied_bpm: bool) -> dict:
    period = 60 / bpm
    explicit_anchor = anchor is not None
    if anchor is None:
        scores = []
        for phase in np.arange(0, period, STEP):
            ticks = np.arange(phase, duration, period)
            indices = np.minimum((ticks / STEP).astype(int), len(novelty) - 1)
            # Small timing tolerance avoids implying that onset peaks are exact beats.
            nearby = np.stack([novelty[np.clip(indices + shift, 0, len(novelty) - 1)]
                               for shift in range(-3, 4)])
            scores.append((float(nearby.max(axis=0).mean()), phase))
        _, phase = max(scores, key=lambda item: item[0])
        anchor = start + float(phase)
    first = anchor + math.ceil((start - anchor) / period - 1e-9) * period
    times = np.arange(first, start + duration - 1e-8, period)
    onset_times = start + onsets * STEP
    if len(onset_times) and len(times):
        positions = np.searchsorted(onset_times, times)
        left = onset_times[np.clip(positions - 1, 0, len(onset_times) - 1)]
        right = onset_times[np.clip(positions, 0, len(onset_times) - 1)]
        distances = np.minimum(np.abs(left - times), np.abs(right - times))
        alignment = float(np.mean(distances <= 0.07))
    else:
        alignment = 0.0
    return {
        "bpm": round(bpm, 6),
        "method": "supplied_bpm_and_anchor" if supplied_bpm and explicit_anchor else
                  "supplied_bpm_estimated_phase" if supplied_bpm else
                  "global_periodicity_and_estimated_phase",
        "status": "user_supplied_requires_music_verification" if supplied_bpm else
                  "automatic_estimate_requires_listening",
        "beat_anchor_source_seconds": round(anchor, 6),
        "beat_times_source_seconds": [round(float(value), 6) for value in times],
        "onset_alignment_ratio_70ms": round(alignment, 4),
        "alignment_is_not_a_confidence_probability": True,
        "downbeats": None,
        "time_signature": None,
        "phrase_start": None,
    }


def analyze(path: Path, start: float, end: float | None,
            bpm: float | None, anchor: float | None) -> dict:
    metadata = probe(path)
    if metadata["reported_duration_seconds"] is not None and start >= metadata["reported_duration_seconds"]:
        raise ValueError("--start is outside the media's reported duration.")
    samples = decode(path, start, end)
    duration = len(samples) / SAMPLE_RATE
    novelty, rms, centroids = features(samples)
    onsets = onset_indices(novelty)
    onsets = onsets[onsets * STEP < duration]
    candidates = tempos(novelty, onsets, duration)
    warnings = [
        "Automatic BPM and phase are hypotheses, not verified musical beats or downbeats.",
        "A fixed global grid cannot establish variable tempo, meter, bar starts or phrase boundaries.",
        "Onset peaks include subdivisions and ornamentation; salience is not musical accent.",
        "Timing uses decoded source-file seconds; FFT windows, resampling and codec seeking limit accuracy.",
        "No genre, dance-style, instrumentation or section labels are inferred by this script.",
    ]
    if duration < 8:
        warnings.append("Short interval: tempo/phase evidence may be weak or ambiguous.")
    if end is not None and duration < end - start - 0.1:
        warnings.append("Decoded interval ends earlier than requested; use actual decoded coverage.")
    if candidates:
        first = candidates[0]["bpm"]
        if any(abs(item["bpm"] / first - ratio) < 0.035
               for item in candidates[1:] for ratio in (0.5, 2.0)):
            warnings.append("Half/double-time alternatives appear among the tempo candidates.")
    chosen = bpm if bpm is not None else candidates[0]["bpm"] if candidates else None
    grid = make_grid(novelty, onsets, start, duration, chosen, anchor, bpm is not None) if chosen else None
    if grid and grid["onset_alignment_ratio_70ms"] < 0.55:
        warnings.append("Low grid/onset agreement: inspect syncopation, silence, phase, BPM and tempo changes.")
    if grid is None:
        warnings.append("Insufficient periodic evidence: no automatic beat grid was produced.")
    bins = []
    for begin in np.arange(0, duration, 0.5):
        region = samples[int(begin * SAMPLE_RATE):min(len(samples), int((begin + 0.5) * SAMPLE_RATE))]
        energy = float(np.sqrt(np.mean(region * region)))
        bins.append({"start_source_seconds": round(start + float(begin), 6),
                     "end_source_seconds": round(start + min(float(begin) + 0.5, duration), 6),
                     "rms_dbfs": round(20 * math.log10(max(energy, 1e-12)), 3)})
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    active = rms > max(float(rms.max()) * 0.03, 1e-6)
    return {
        "schema_version": 1,
        "source": {"file": str(path.resolve()), "sha256": digest.hexdigest(), **metadata},
        "analysis": {"start_source_seconds": start, "end_source_seconds": start + duration,
                     "decoded_samples": len(samples), "analysis_sample_rate": SAMPLE_RATE,
                     "analysis_channels": 1, "feature_hop_seconds": STEP,
                     "spectral_window_seconds": WINDOW / SAMPLE_RATE,
                     "active_frame_fraction": round(float(active.mean()), 4),
                     "median_spectral_centroid_hz": round(float(np.median(centroids[active])), 2)
                     if np.any(active) else None},
        "tempo_candidates": candidates,
        "tempo_score_is_not_a_confidence_probability": True,
        "beat_grid": grid,
        "onsets": [{"source_seconds": round(start + int(index) * STEP, 6),
                    "relative_salience": round(float(novelty[index]), 4)} for index in onsets],
        "energy_500ms": bins,
        "warnings": warnings,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("audio", type=Path)
    parser.add_argument("--start", type=float, default=0.0, help="Source interval start in seconds.")
    parser.add_argument("--end", type=float, help="Source interval end in seconds, exclusive.")
    parser.add_argument("--bpm", type=float, help="Supplied steady tempo, not independently verified.")
    parser.add_argument("--beat-anchor", type=float, help="Supplied beat's source time; requires --bpm.")
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    try:
        for program in ("ffmpeg", "ffprobe"):
            if not shutil.which(program):
                raise ValueError(f"Missing required program on PATH: {program}")
        if not args.audio.is_file():
            raise ValueError("Input must be an existing local media file.")
        if not math.isfinite(args.start) or args.start < 0:
            raise ValueError("--start must be a finite non-negative source time.")
        if args.end is not None and (not math.isfinite(args.end) or args.end <= args.start):
            raise ValueError("--end must be finite and greater than --start.")
        if args.bpm is not None and (not math.isfinite(args.bpm) or not 20 <= args.bpm <= 400):
            raise ValueError("--bpm must be finite and in the range 20–400.")
        if args.beat_anchor is not None and (
            not math.isfinite(args.beat_anchor) or args.beat_anchor < 0 or args.bpm is None
        ):
            raise ValueError("--beat-anchor must be a finite non-negative source time supplied with --bpm.")
        if args.out.resolve() == args.audio.resolve():
            raise ValueError("Output must not overwrite the source audio.")
        result = analyze(args.audio, args.start, args.end, args.bpm, args.beat_anchor)
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False) + "\n",
                            encoding="utf-8")
        print(json.dumps({"output": str(args.out.resolve()),
                          "analyzed_seconds": round(result["analysis"]["end_source_seconds"] - args.start, 3),
                          "tempo_candidates": result["tempo_candidates"],
                          "grid_status": result["beat_grid"]["status"] if result["beat_grid"] else None},
                         ensure_ascii=False))
        return 0
    except (ValueError, OSError, json.JSONDecodeError) as error:
        print(f"Audio analysis failed: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
