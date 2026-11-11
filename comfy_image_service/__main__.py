import os

import uvicorn

from .config import Settings


if __name__ == "__main__":
    Settings.from_env()
    uvicorn.run("comfy_image_service.api:app", host=os.environ.get("IMAGE_HOST", "127.0.0.1"),
                port=int(os.environ.get("IMAGE_PORT", "8011")), workers=1)
