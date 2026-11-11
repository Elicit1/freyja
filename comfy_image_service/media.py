import base64
import binascii
import hashlib
import io
import uuid

from PIL import Image, UnidentifiedImageError

from .models import ServiceError


def image_info(data):
    try:
        with Image.open(io.BytesIO(data)) as image:
            if image.format not in {"PNG", "JPEG", "WEBP"}:
                raise ValueError("Only PNG, JPEG, and WebP images are supported.")
            width, height = image.size
            if width * height > 40 * 1024 * 1024:
                raise ValueError("Source images may contain at most 40 MP.")
            kind = {"PNG": "png", "JPEG": "jpg", "WEBP": "webp"}[image.format]
            image.verify()
        return width, height, kind
    except (UnidentifiedImageError, OSError, ValueError, Image.DecompressionBombError) as error:
        raise ServiceError("invalid_image", str(error), 422) from error


def save_upload(data, settings, store):
    if len(data) > settings.max_upload_bytes:
        raise ServiceError("image_too_large", "Image uploads are limited to 25 MiB.", 413)
    width, height, extension = image_info(data)
    upload_id = str(uuid.uuid4())
    directory = settings.data_dir / "uploads"
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / f"{upload_id}.{extension}"
    path.write_bytes(data)
    try:
        return store.save_upload(upload_id, path, hashlib.sha256(data).hexdigest(), width, height)
    except Exception:
        path.unlink(missing_ok=True)
        raise


def resolve_image(value, settings, store):
    if value.startswith("data:"):
        header, sep, payload = value.partition(",")
        if not sep or header not in {"data:image/png;base64", "data:image/jpeg;base64", "data:image/webp;base64"}:
            raise ServiceError("invalid_image", "Use a base64 PNG, JPEG, or WebP data URI.", 422)
        if len(payload) > (settings.max_upload_bytes + 2) // 3 * 4:
            raise ServiceError("image_too_large", "Image uploads are limited to 25 MiB.", 413)
        try:
            data = base64.b64decode(payload, validate=True)
        except (ValueError, binascii.Error) as error:
            raise ServiceError("invalid_image", "Invalid base64 image data.", 422) from error
        return save_upload(data, settings, store)
    # Input paths and URLs are intentionally not dereferenced by this service.
    # Callers upload file bytes; this also works with private/local assets.
    return store.upload(value)
