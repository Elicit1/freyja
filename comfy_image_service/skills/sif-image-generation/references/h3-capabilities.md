# H3 capabilities in SIF

Checked against MiniMax's model repository, local inference examples, Diffusers documentation, and ComfyUI's `MiniMaxH3ImageConditioning` implementation on 2026-11-12. SIF executes the configured ComfyUI backend using its installed INT8, W4A8, and Q4_K_M profiles. [MiniMax model documentation](https://github.com/MiniMax-AI/MiniMax-H3-Image) · [ComfyUI conditioning implementation](https://github.com/Comfy-Org/ComfyUI/blob/master/comfy_extras/nodes_minimax_h3.py)

## Generation and editing

| Capability | SIF interface |
| --- | --- |
| Text-to-image | `text_to_image`, `POST /v1/t2i`, CLI `t2i` |
| Multi-reference editing and composition | `image_to_image`, `POST /v1/i2i`, CLI `i2i`; 1–16 separately connected references |
| Ordered reference identity | `images[0]` is `<Picture 1>`, then `<Picture 2>` through `<Picture 16>` |
| Custom output canvas | `width` + `height`, or `megapixels` + numeric `aspect_ratio`; 32-aligned, ≤20 × 1024² pixels |
| Preserve source ratio | i2i `aspect_ratio="original"`, using the first reference after EXIF orientation |
| Reference conditioning | `reference_mode="latent"` for VAE + vision, `vision` for text-encoder conditioning |
| Reproducible noise | `seed`; each returned image records its seed |
| Multiple candidates | `n=1..10`; separate batch-size-one samplers within one persisted workflow and backend prompt |
| Optional expansion | `expand_prompt`, plus `prompt_expansion` controls for the installed Qwen3.5 9B expander |
| Output encoding | `png`, `jpeg`, `webp`; SIF converts the generated PNG for JPEG/WebP and applies `output_quality` |
| Output delivery | Authenticated URL/binary download, or explicit `get_result(response_format="b64_json")` |
| Completion | MCP `wait_for_job` or REST `/wait`; committed state events, ComfyUI WebSocket completion |

The official multi-reference examples include sixteen people, combined person/color references, and ten furniture references. Assign each image's purpose explicitly in the prompt. SIF preserves the uploaded order and connects every image separately to H3 and the enabled prompt expander. [Official multi-reference showcase](https://github.com/MiniMax-AI/MiniMax-H3-Image/blob/main/showcase/multi-reference.md)

For editing, `<Picture 1>` is the source to edit. Later references can supply identity, garments, objects, palette, style, or setting as directed by the user. New compositions can combine these references by describing the intended output and each source's role. Distinguish attributes to transfer from attributes to preserve in English prose.

```text
Edit <Picture 1>. Preserve the person's face, hairstyle, and pose. Replace the coat with the coat from <Picture 2>, preserving its cut, fabric, and trim. Place the person in the room from <Picture 3>. Use the palette from <Picture 4> for the background decor. Keep the person's skin tones natural.
```

Describe the intended use and arrangement before styling. Quote exact lettering in its required language. For grids and storyboards, specify rows, columns, and reading order explicitly. A collage, diagram, poster, relighting task, style transfer, or scene composition is expressed through the same prompt and reference interface.

## Canvas and sampling

SIF retains its existing definition `1 MP = 1024 × 1024 pixels`. A 2048×1152 canvas is 2.25 MP. MP-based sizes round to the 32-pixel grid, with the upper bound enforced after rounding. Explicit sizes require both sides to be multiples of 32. The first reference's ratio is the i2i default, and a requested canvas can have a different shape.

ComfyUI prepares reference copies for the vision encoder and optional VAE independently of output dimensions. SIF supplies the original uploaded reference to those core nodes. Source limits remain 25 MiB and 40 MP per PNG/JPEG/WebP file. The model's reference capacity and GPU memory use are separate limits. [ComfyUI conditioning implementation](https://github.com/Comfy-Org/ComfyUI/blob/master/comfy_extras/nodes_minimax_h3.py)

H3 uses its six-step PDD schedule. SIF fixes six steps, CFG 1, Euler/Simple, and denoise 1. The official local request documentation rejects other inference-step/guidance values and negative prompts. ComfyUI renders SIF's requested canvas directly. The SGLang/Diffusers `min_generation_area` option belongs to those inference paths. [Official local request parameters](https://github.com/MiniMax-AI/MiniMax-H3-Image#request-parameters) · [Diffusers H3 pipeline](https://huggingface.co/docs/diffusers/main/en/api/pipelines/minimax_h3_image)

## Expansion and hosted API fields

SIF's installed expander is Qwen3.5 9B through ComfyUI `TextGenerateV2`. Its optional controls are listed in [api.md](api.md). The workflow receives the selected output ratio, reference size metadata, all ordered images, and additional user instructions. It extracts the expanded caption and returns that caption as `effective_prompt`. The Skill's current recommendation remains expansion OFF.

MiniMax also documents its hosted H3-Image-Context-IR API and an optional H3-Image-PE checkpoint. Using those services/checkpoints would require their own integration and credentials or installation. The hosted API has its own area presets, source formats, input limits, and expansion rules. SIF's live `get_capabilities` and [API reference](api.md) describe the implemented local contract. [MiniMax generation API](https://platform.minimax.io/docs/api-reference/image-generation-h3-generations) · [MiniMax edits API](https://platform.minimax.io/docs/api-reference/image-generation-h3-edits) · [MiniMax Context-IR API](https://platform.minimax.io/docs/api-reference/image-generation-h3-context-ir)

Mask-based editing, transparent-background generation, negative prompts, and arbitrary sampler overrides are rejected by the SIF generation schema. Describe semantic editing instructions with references through the supported interface.
