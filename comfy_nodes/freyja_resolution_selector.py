"""Official ComfyUI resolution selector with an extended megapixel range.

Install this file in ComfyUI/custom_nodes/ and restart ComfyUI.
"""

import math

from comfy_api.latest import ComfyExtension, io
from comfy_extras.nodes_resolution import ASPECT_RATIOS, ResolutionSelector


MAX_MEGAPIXELS = 256.0
MAX_DIMENSION = 16384


class FreyjaResolutionSelector(ResolutionSelector):
    @classmethod
    def define_schema(cls):
        schema = super().define_schema()
        schema.node_id = "FreyjaResolutionSelector"
        schema.display_name = "FREYJA Resolution Selector (MP)"
        schema.description = (
            "Official aspect-ratio and megapixel calculation with support above 16 MP. "
            "Uses 1 MP = 1024 x 1024 pixels; each side must remain at most 16384 pixels."
        )
        for item in schema.inputs:
            if item.id == "megapixels":
                item.default = 4.0
                item.max = MAX_MEGAPIXELS
                item.tooltip = "Target MP (0.1-256). 16 MP = 4096x4096; 64 MP = 8192x8192 at 1:1."
            elif item.id == "multiple":
                item.default = 32
        return schema

    @classmethod
    def execute(cls, aspect_ratio: str, megapixels: float, multiple: int, preview=None):
        if not math.isfinite(megapixels) or not 0.1 <= megapixels <= MAX_MEGAPIXELS:
            raise ValueError("Megapixels must be between 0.1 and 256.")
        if not 8 <= multiple <= 128 or multiple % 4:
            raise ValueError("The alignment multiple must be 8-128 in increments of 4.")
        # Match the official selector's binary-MP convention and rounding exactly.
        w_ratio, h_ratio = ASPECT_RATIOS[aspect_ratio]
        scale = math.sqrt(megapixels * 1024 * 1024 / (w_ratio * h_ratio))
        width = round(w_ratio * scale / multiple) * multiple
        height = round(h_ratio * scale / multiple) * multiple
        if width > MAX_DIMENSION or height > MAX_DIMENSION:
            raise ValueError(
                f"{megapixels:g} MP at {aspect_ratio} produces {width}x{height}; "
                "ComfyUI allows at most 16384 pixels per side. Reduce MP or choose a less elongated ratio."
            )
        return io.NodeOutput(width, height)


class FreyjaResolutionExtension(ComfyExtension):
    async def get_node_list(self):
        return [FreyjaResolutionSelector]


async def comfy_entrypoint():
    return FreyjaResolutionExtension()
