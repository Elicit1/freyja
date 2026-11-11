"""20 MP variants of the official H3 Image workflow's dimension nodes.

Copy to ComfyUI/custom_nodes/ and restart. Core ComfyUI files are unchanged.
"""

import math

from comfy_api.latest import ComfyExtension
from comfy_extras.nodes_post_processing import ResizeImageMaskNode
from comfy_extras.nodes_resolution import ResolutionSelector


def validate_mp(value, minimum):
    if not math.isfinite(value) or not minimum <= value <= 20.0:
        raise ValueError(f"Megapixels must be between {minimum:g} and 20.")


class FreyjaResolutionSelector20MP(ResolutionSelector):
    @classmethod
    def define_schema(cls):
        schema = super().define_schema()
        schema.node_id = "FreyjaResolutionSelector20MP"
        schema.display_name = "Resolution Selector (20 MP)"
        for item in schema.inputs:
            if item.id == "megapixels":
                item.default = 4.0
                item.max = 20.0
                item.tooltip = "Target MP, up to 20. Uses the official 1024x1024 pixels per MP."
            elif item.id == "multiple":
                item.default = 32
        return schema

    @classmethod
    def execute(cls, aspect_ratio, megapixels, multiple, preview=None):
        validate_mp(megapixels, 0.1)
        return super().execute(aspect_ratio, megapixels, multiple, preview)


class FreyjaResizeImageMask20MP(ResizeImageMaskNode):
    @classmethod
    def define_schema(cls):
        schema = super().define_schema()
        schema.node_id = "FreyjaResizeImageMask20MP"
        schema.display_name = "Resize Image/Mask (20 MP)"
        for item in schema.inputs:
            if item.id == "resize_type":
                for option in item.options:
                    if option.key == "scale total pixels":
                        for field in option.inputs:
                            if field.id == "megapixels":
                                field.max = 20.0
                                field.default = 4.0
                                field.tooltip = "Target MP, up to 20. Preserves the input aspect ratio."
        return schema

    @classmethod
    def execute(cls, input, scale_method, resize_type):
        if resize_type["resize_type"] == "scale total pixels":
            validate_mp(resize_type["megapixels"], 0.01)
        return super().execute(input, scale_method, resize_type)


class FreyjaH3Image20MPExtension(ComfyExtension):
    async def get_node_list(self):
        return [FreyjaResolutionSelector20MP, FreyjaResizeImageMask20MP]


async def comfy_entrypoint():
    return FreyjaH3Image20MPExtension()
