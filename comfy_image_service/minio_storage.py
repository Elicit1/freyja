"""Reference images transfer through a dedicated, private MinIO staging bucket."""
import threading
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.parse import unquote, urlsplit

import urllib3
from minio import Minio
from minio.error import S3Error
from minio.lifecycleconfig import Expiration, LifecycleConfig, Rule
from minio.commonconfig import ENABLED, Filter

from .models import ServiceError


UPLOAD_RETENTION_SECONDS = 24 * 60 * 60
UPLOAD_PREFIX = "references/"


class ImageStorage:
    def __init__(self, settings):
        self.settings = settings
        self.enabled = bool(settings.minio_endpoint)
        self._ready = False
        self._lock = threading.Lock()
        self.client = self.public_client = None
        if not self.enabled:
            return
        if not settings.minio_access_key or not settings.minio_secret_key:
            raise ValueError("SIF_MINIO_ACCESS_KEY and SIF_MINIO_SECRET_KEY are required with SIF_MINIO_ENDPOINT.")
        if settings.minio_bucket in settings.minio_read_buckets:
            raise ValueError("Use a dedicated SIF upload bucket, separate from existing asset buckets.")
        self.client = self._client(settings.minio_endpoint)
        self.public_client = self._client(settings.minio_public_endpoint or settings.minio_endpoint)

    def _client(self, endpoint):
        parsed = urlsplit(endpoint)
        if parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.path not in {"", "/"}:
            raise ValueError("MinIO endpoints must be HTTP(S) origins.")
        return Minio(parsed.netloc, access_key=self.settings.minio_access_key, secret_key=self.settings.minio_secret_key,
                     secure=parsed.scheme == "https", region="us-east-1",
                     http_client=urllib3.PoolManager(timeout=urllib3.Timeout(connect=5, read=30), retries=2))

    def ensure_bucket(self):
        with self._lock:
            if self._ready:
                return
            bucket = self.settings.minio_bucket
            if not self.client.bucket_exists(bucket):
                try:
                    self.client.make_bucket(bucket)
                except S3Error as error:
                    if error.code != "BucketAlreadyOwnedByYou":
                        raise
            # A storage-side fallback remains active while SIF is offline.
            self.client.set_bucket_lifecycle(bucket, LifecycleConfig([
                Rule(ENABLED, rule_filter=Filter(prefix=UPLOAD_PREFIX), rule_id="expire-sif-references",
                     expiration=Expiration(days=1))]))
            self._ready = True

    def publish(self, upload):
        if not self.enabled:
            return {}
        try:
            self.ensure_bucket()
            path = Path(upload["path"])
            key = UPLOAD_PREFIX + path.name
            content_type = {".png": "image/png", ".jpg": "image/jpeg", ".webp": "image/webp"}[path.suffix]
            with path.open("rb") as stream:
                self.client.put_object(self.settings.minio_bucket, key, stream, path.stat().st_size, content_type=content_type)
            expires = datetime.now(timezone.utc) + timedelta(seconds=UPLOAD_RETENTION_SECONDS)
            # Supplying region avoids a discovery request through the client's external address.
            url = self.public_client.presigned_get_object(self.settings.minio_bucket, key,
                                                         expires=timedelta(seconds=UPLOAD_RETENTION_SECONDS))
            return {"url": url, "bucket": self.settings.minio_bucket, "object_path": key,
                    "expires_at": expires.isoformat(), "retention_seconds": UPLOAD_RETENTION_SECONDS}
        except Exception as error:
            raise ServiceError("image_storage_unavailable", "MinIO image upload failed. Retry the binary upload.", 502) from error

    def object_location(self, url):
        if not self.enabled:
            raise ServiceError("image_storage_unconfigured", "Configure SIF MinIO to use reference image URLs.", 503)
        try:
            parsed = urlsplit(url)
            origins = (self.settings.minio_endpoint, self.settings.minio_public_endpoint, *self.settings.minio_url_aliases)
            allowed = {(urlsplit(origin).scheme.lower(), urlsplit(origin).netloc.lower()) for origin in origins if origin}
            if parsed.scheme not in {"http", "https"} or parsed.username or parsed.password or parsed.fragment or (parsed.scheme.lower(), parsed.netloc.lower()) not in allowed:
                raise ValueError()
            bucket, separator, key = parsed.path.lstrip("/").partition("/")
            bucket, key = unquote(bucket), unquote(key)
            if not separator or not key or "\x00" in key or bucket not in {self.settings.minio_bucket, *self.settings.minio_read_buckets}:
                raise ValueError()
            return bucket, key
        except ValueError as error:
            raise ServiceError("invalid_reference_url", "Use an image URL from the configured MinIO endpoints and buckets.", 422) from error

    def read(self, url):
        bucket, key = self.object_location(url)
        try:
            stat = self.client.stat_object(bucket, key)
            if bucket == self.settings.minio_bucket and key.startswith(UPLOAD_PREFIX) and stat.last_modified.timestamp() + UPLOAD_RETENTION_SECONDS <= datetime.now(timezone.utc).timestamp():
                raise ServiceError("image_expired", "This uploaded image expired after 24 hours. Upload it again.", 410)
            if stat.size > self.settings.max_upload_bytes:
                raise ServiceError("image_too_large", "Reference images are limited to 25 MiB.", 413)
            response = self.client.get_object(bucket, key, request_headers={"If-Match": stat.etag})
            try:
                data = response.read(self.settings.max_upload_bytes + 1)
            finally:
                response.close()
                response.release_conn()
            if len(data) != stat.size:
                raise ServiceError("reference_changed", "The reference image changed during download. Retry submission.", 409)
            return data
        except ServiceError:
            raise
        except S3Error as error:
            if error.code in {"NoSuchKey", "NoSuchObject", "NoSuchBucket"}:
                raise ServiceError("image_not_found", "The MinIO reference image is missing or expired.", 404) from error
            raise ServiceError("reference_download_failed", "MinIO could not read the reference image.", 502) from error
        except Exception as error:
            raise ServiceError("reference_download_failed", "MinIO reference image download failed.", 502) from error

    def cleanup_expired(self):
        if not self.enabled:
            return
        self.ensure_bucket()
        cutoff = datetime.now(timezone.utc).timestamp() - UPLOAD_RETENTION_SECONDS
        for item in self.client.list_objects(self.settings.minio_bucket, prefix=UPLOAD_PREFIX, recursive=True):
            if item.last_modified.timestamp() <= cutoff:
                self.client.remove_object(self.settings.minio_bucket, item.object_name)
