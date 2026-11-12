import math
import re
from fractions import Fraction
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, StrictBool


Tier = Literal["pro", "standard", "fast"]
Mode = Literal["t2i", "i2i"]
ASPECT_RATIOS = {
    "1:1": "1:1 (Square)", "2:3": "2:3 (Portrait Photo)", "3:2": "3:2 (Photo)",
    "3:4": "3:4 (Portrait Standard)", "4:3": "4:3 (Standard)",
    "9:16": "9:16 (Portrait Widescreen)", "16:9": "16:9 (Widescreen)", "21:9": "21:9 (Ultrawide)",
}


class PromptExpansion(BaseModel):
    """Controls for the installed TextGenerateV2 expander."""
    model_config = ConfigDict(extra="forbid")
    max_length: int = Field(default=8192, strict=True, ge=1, le=32768)
    thinking: StrictBool = True
    temperature: float = Field(default=1, ge=0.01, le=2, allow_inf_nan=False)
    top_k: int = Field(default=20, strict=True, ge=0, le=1000)
    top_p: float = Field(default=0.95, ge=0, le=1, allow_inf_nan=False)
    min_p: float = Field(default=0, ge=0, le=1, allow_inf_nan=False)
    repetition_penalty: float = Field(default=1, ge=0, le=5, allow_inf_nan=False)
    presence_penalty: float = Field(default=0, ge=0, le=5, allow_inf_nan=False)
    seed: int = Field(default=0, strict=True, ge=0, le=9007199254740991)
    instructions: str | None = Field(default=None, max_length=16000, description="Additional English expansion instructions. The workflow retains its required JSON output contract.")


def parse_ratio(value):
    if not re.fullmatch(r"[0-9]+(?:\.[0-9]+)?:[0-9]+(?:\.[0-9]+)?", value):
        raise ServiceError("invalid_aspect_ratio", "Use a numeric W:H ratio, such as 5:4 or 2.39:1.", 422)
    left, right = map(Fraction, value.split(":"))
    if right <= 0 or not Fraction(1, 3) <= left / right <= 3:
        raise ServiceError("invalid_aspect_ratio", "Output aspect ratio must be between 1:3 and 3:1.", 422)
    return float(left / right)


def canvas_size(values, source_size=None):
    if values.get("width") is not None:
        return values["width"], values["height"]
    if values["aspect_ratio"] == "original":
        if source_size is None:
            raise ServiceError("image_required", "The original ratio requires the first reference image.", 422)
        ratio = source_size[0] / source_size[1]
    else:
        ratio = parse_ratio(values["aspect_ratio"])
    pixels = values["megapixels"] * 1024 * 1024
    width = max(32, round(math.sqrt(pixels * ratio) / 32) * 32)
    height = max(32, round(math.sqrt(pixels / ratio) / 32) * 32)
    # Rounding at the upper limit must never push the canvas above 20 MP.
    while width * height > 20 * 1024 * 1024:
        if width >= height:
            width -= 32
        else:
            height -= 32
    if max(width, height) > 16384:
        raise ServiceError("invalid_size", "Canvas sides may contain at most 16384 pixels.", 422)
    return width, height


class GenerateRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)
    app: str = Field(default="minimax-h3-image", pattern=r"^[a-z0-9][a-z0-9-]{0,63}$")
    tier: Tier = "standard"
    prompt: str = Field(min_length=1, max_length=32000)
    megapixels: float = Field(default=4.0, ge=0.1, le=20.0, allow_inf_nan=False)
    aspect_ratio: str | None = Field(default=None, max_length=64, description="Numeric W:H between 1:3 and 3:1. i2i also accepts original (first reference).")
    width: int | None = Field(default=None, strict=True, ge=32, le=16384, multiple_of=32)
    height: int | None = Field(default=None, strict=True, ge=32, le=16384, multiple_of=32)
    expand_prompt: StrictBool = True
    seed: int | None = Field(default=None, strict=True, ge=0, le=9007199254740991)
    image: str | None = Field(default=None, description="i2i only: MinIO image URL, uploaded image ID, or image data URI")
    images: list[str] | None = Field(default=None, min_length=1, max_length=16, description="Ordered MinIO image URLs or upload IDs, <Picture 1> through <Picture 16>. Use either image or images.")
    reference_mode: Literal["latent", "vision"] = Field(default="latent", description="latent anchors references in the VAE and text encoder. vision uses the text encoder only.")
    n: int = Field(default=1, strict=True, ge=1, le=10, description="Serial single-image samples in one persisted backend workflow; seeds increment from seed.")
    output_format: Literal["png", "jpeg", "webp"] = "png"
    output_quality: int = Field(default=95, strict=True, ge=0, le=100, description="JPEG/WebP encoding quality. PNG remains lossless.")
    prompt_expansion: PromptExpansion | None = None

    def normalized(self, mode: Mode):
        if mode == "t2i":
            if self.image is not None or self.images is not None:
                raise ServiceError("invalid_image", "t2i does not accept an image.", 422)
            ratio = self.aspect_ratio or "1:1"
        else:
            if not self.image and not self.images:
                raise ServiceError("image_required", "i2i requires an uploaded image ID or a base64 image data URI.", 422)
            ratio = self.aspect_ratio or "original"
        if self.image is not None and self.images is not None:
            raise ServiceError("invalid_image", "Supply image or images, with references in their intended order.", 422)
        if self.images is not None and any(not item.strip() for item in self.images):
            raise ServiceError("invalid_image", "Reference image IDs must be nonempty.", 422)
        if ratio != "original":
            parse_ratio(ratio)
        if (self.width is None) != (self.height is None):
            raise ServiceError("invalid_size", "Supply both width and height.", 422)
        if self.width is not None:
            if self.aspect_ratio is not None or "megapixels" in self.model_fields_set:
                raise ServiceError("conflicting_size", "Use width/height together, or megapixels/aspect_ratio.", 422)
            if self.width * self.height > 20 * 1024 * 1024 or not 1 / 3 <= self.width / self.height <= 3:
                raise ServiceError("invalid_size", "Output canvas must be at most 20 MP and between 1:3 and 3:1.", 422)
            ratio = f"{self.width}:{self.height}"
        if not self.expand_prompt and self.prompt_expansion is not None:
            raise ServiceError("expansion_disabled", "Enable expand_prompt to supply prompt_expansion controls.", 422)
        return {**self.model_dump(), "mode": mode, "aspect_ratio": ratio}


class ServiceError(Exception):
    def __init__(self, code, detail, status_code=400):
        super().__init__(detail)
        self.code, self.detail, self.status_code = code, detail, status_code
