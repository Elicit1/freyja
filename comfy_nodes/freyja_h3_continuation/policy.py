"""Tensor-independent frame and noise arithmetic; H3's 1,4,4,4,4 VAE cycle."""
import math

VERSION = "FREYJA_H3_AV_V1"

def latent_steps(frames):
    if frames < 5 or (frames - 5) % 17:
        raise ValueError("FREYJA_H3_FRAME_GRID: expected 17k+5 frames")
    return (frames - 5) // 17 * 5 + 2

def pixel_frames(steps):
    return steps // 5 * 17 + (0, 1, 5, 9, 13)[steps % 5]

def tail_start(total_steps, context_frames):
    count = latent_steps(context_frames)
    start = total_steps - count
    if start < 0 or start % 5:
        raise ValueError("FREYJA_H3_PHASE_MISMATCH: cannot shift an unaligned native tail")
    return start, count

def noise_weights(count, mode, strength, ratio=1.0):
    if count < 2:
        raise ValueError("FREYJA_H3_CONTEXT_TOO_SHORT")
    if mode == "LATENT_PROTECTED":
        return [strength * (1 - i / (count - 1)) for i in range(count)]
    taper = min(4, count)
    base = [1.0] * (count - taper) + [1 - (1-strength)*(i+1)/taper for i in range(taper)]
    return [max(strength, x * ratio) for x in base]

def schedule_ratio(current, sigmas):
    below = [float(x) for x in sigmas if math.isfinite(float(x)) and 0 <= float(x) < current - max(1e-7, current*1e-6)]
    return max(below) / current if current > 0 and below else 0.0
