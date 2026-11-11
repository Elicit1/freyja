from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, StrictBool


Tier = Literal["pro", "standard", "fast"]
Mode = Literal["t2i", "i2i"]
ASPECT_RATIOS = {
    "1:1": "1:1 (Square)", "2:3": "2:3 (Portrait Photo)", "3:2": "3:2 (Photo)",
    "3:4": "3:4 (Portrait Standard)", "4:3": "4:3 (Standard)",
    "9:16": "9:16 (Portrait Widescreen)", "16:9": "16:9 (Widescreen)", "21:9": "21:9 (Ultrawide)",
}


class GenerateRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)
    app: str = Field(default="minimax-h3-image", pattern=r"^[a-z0-9][a-z0-9-]{0,63}$")
    tier: Tier = "standard"
    prompt: str = Field(min_length=1, max_length=32000)
    megapixels: float = Field(default=4.0, ge=0.1, le=20.0, allow_inf_nan=False)
    aspect_ratio: str | None = Field(default=None, description="t2i: 1:1, 2:3, 3:2, 3:4, 4:3, 9:16, 16:9, 21:9; i2i: original")
    expand_prompt: StrictBool = True
    seed: int | None = Field(default=None, strict=True, ge=0, le=9007199254740991)
    image: str | None = Field(default=None, description="i2i only: uploaded image ID or base64 image data URI")

    def normalized(self, mode: Mode):
        if mode == "t2i":
            if self.image is not None:
                raise ServiceError("invalid_image", "t2i does not accept an image.", 422)
            ratio = self.aspect_ratio or "1:1"
            if ratio not in ASPECT_RATIOS:
                raise ServiceError("invalid_aspect_ratio", "Choose one of: " + ", ".join(ASPECT_RATIOS), 422)
        else:
            if not self.image:
                raise ServiceError("image_required", "i2i requires an uploaded image ID or a base64 image data URI.", 422)
            ratio = self.aspect_ratio or "original"
            if ratio != "original":
                raise ServiceError("invalid_aspect_ratio", "i2i preserves the input aspect ratio; use original.", 422)
        return {**self.model_dump(), "mode": mode, "aspect_ratio": ratio}


class ServiceError(Exception):
    def __init__(self, code, detail, status_code=400):
        super().__init__(detail)
        self.code, self.detail, self.status_code = code, detail, status_code
