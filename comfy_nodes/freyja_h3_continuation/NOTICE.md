# FREYJA H3 continuation nodes

This separately deployed ComfyUI node package is licensed under GPL-3.0-or-later.
The Java application and gateway communicate with it over ComfyUI's public graph
and asset protocols; this notice applies to this node package.

Acknowledgements:

- AIMixer/ComfyUI_MiniMaxH3_Director: latent continuation and redraw strategy.
- ethanfel/ComfyUI-MiniMaxH3-Context-Loop: scheduler-matched prefix mask strategy.
- roycho87/geiru_context_suite: context noise, clean keystone and late capstone.
  The late-capstone packed-model-call adapter in `nodes.py` is adapted from the
  GPL-3.0 strategy in `clown_keyframe.py` (retrieved 2026-11-09).
- Comfy-Org/ComfyUI: native H3 AV layout, masks and model patcher APIs.

FREYJA's chroma recipe is independently specified: Gaussian Cb/Cr deviations
with standard deviation `0.15 * strength`, inverse BT.601 chroma coefficients,
RGB values in [0,1], clamp after addition. Temporal taper covers the selected
number of final context frames. Its strength is not interchangeable with other
nodes' noise/denoise parameters. Protocol and recipe version: 1.

No model weights are redistributed. References describe mechanisms and community
experiments, not a guarantee of drift-free generation.
