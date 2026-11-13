"""MinIO transfers and retention use in-memory objects and synthetic PNG files."""
import hashlib
import importlib.util
import io
import json
import time
import unittest
import sys
from dataclasses import replace
from datetime import datetime, timedelta, timezone
from pathlib import Path
from types import SimpleNamespace
from urllib.parse import parse_qs, urlsplit
from unittest.mock import Mock, patch

from minio.error import S3Error

from comfy_image_service.minio_storage import ImageStorage, UPLOAD_RETENTION_SECONDS
from comfy_image_service.models import GenerateRequest, ServiceError
from comfy_image_service.registry import Registry
from comfy_image_service.tests.test_service import SOURCE, ServiceFixture, png


class ObjectStream(io.BytesIO):
    released = False

    def release_conn(self):
        self.released = True


class MemoryMinio:
    def __init__(self):
        self.objects = {}
        self.lifecycle = None
        self.removed = []
        self.reads = []
        self.last_stream = None

    def bucket_exists(self, bucket):
        return True

    def set_bucket_lifecycle(self, bucket, config):
        self.lifecycle = (bucket, config)

    def put_object(self, bucket, key, stream, size, content_type):
        data = stream.read()
        assert len(data) == size
        self.objects[bucket, key] = (data, datetime.now(timezone.utc))

    def stat_object(self, bucket, key):
        if (bucket, key) not in self.objects:
            raise S3Error(response=None, code='NoSuchKey', message='missing', resource=key, request_id=None, host_id=None)
        data, modified = self.objects[bucket, key]
        return SimpleNamespace(size=len(data), last_modified=modified, etag=hashlib.sha256(data).hexdigest())

    def get_object(self, bucket, key, request_headers):
        self.reads.append((bucket, key, request_headers))
        self.last_stream = ObjectStream(self.objects[bucket, key][0])
        return self.last_stream

    def list_objects(self, bucket, prefix, recursive):
        return [SimpleNamespace(object_name=key, last_modified=value[1])
                for (name, key), value in list(self.objects.items()) if name == bucket and key.startswith(prefix)]

    def remove_object(self, bucket, key):
        self.removed.append((bucket, key))
        self.objects.pop((bucket, key), None)


class MinioTests(ServiceFixture):
    def setUp(self):
        super().setUp()
        settings = replace(self.settings, minio_endpoint='http://minio-internal:9000',
                           minio_public_endpoint='http://127.0.0.1:9000', minio_url_aliases=('http://minio.localhost:9000',),
                           minio_access_key='test-access', minio_secret_key='test-secret')
        self.storage = ImageStorage(settings)
        self.objects = MemoryMinio()
        self.storage.client = self.objects
        self.app.state.service.storage = self.storage

    def upload(self, multipart=False):
        if multipart:
            return self.client.post('/v1/uploads', headers=self.auth, files={'file': ('reference.png', png(), 'image/png')})
        return self.client.post('/v1/uploads', headers=self.auth, content=png())

    def test_binary_and_multipart_upload_return_a_minio_url_with_24_hour_expiry(self):
        for multipart in (False, True):
            response = self.upload(multipart)
            self.assertEqual(response.status_code, 201, response.text)
            body = response.json()
            self.assertEqual(body['bucket'], 'sif-uploads')
            self.assertEqual(body['retention_seconds'], 86400)
            parsed = urlsplit(body['url'])
            self.assertEqual(parsed.netloc, '127.0.0.1:9000')
            self.assertEqual(parse_qs(parsed.query)['X-Amz-Expires'], ['86400'])
            self.assertAlmostEqual(datetime.fromisoformat(body['expires_at']).timestamp() - time.time(), 86400, delta=5)
            self.assertEqual(self.objects.objects[body['bucket'], body['object_path']][0], png())
            self.assertNotIn('test-secret', json.dumps(body))
        self.assertEqual(self.objects.lifecycle[0], 'sif-uploads')
        self.assertEqual(self.objects.lifecycle[1].rules[0].expiration.days, 1)

    def test_generation_fetches_minio_and_freyja_urls_into_immutable_snapshots(self):
        uploaded = self.upload().json()
        self.objects.objects['video-assets', 'characters/face.png'] = (png(), datetime.now(timezone.utc))
        response = self.client.post('/v1/i2i', headers=self.auth, json={
            'prompt': 'Edit <Picture 1> using the person from <Picture 2>.', 'images': [uploaded['url'],
            'http://minio.localhost:9000/video-assets/characters/face.png'], 'expand_prompt': False})
        self.assertEqual(response.status_code, 202, response.text)
        job = self.finished(response.json()['id'])
        self.assertEqual(job['status'], 'succeeded', job)
        self.assertEqual(len(job['request']['images']), 2)
        self.assertNotIn('X-Amz', json.dumps(job))
        self.assertTrue(self.objects.last_stream.closed)
        self.assertTrue(self.objects.last_stream.released)
        self.assertEqual(self.objects.reads[1][0:2], ('video-assets', 'characters/face.png'))
        self.assertIn('If-Match', self.objects.reads[0][2])

    def test_upload_id_and_url_share_content_based_idempotency(self):
        uploaded = self.upload().json()
        headers = {**self.auth, 'Idempotency-Key': 'same-image-url-and-id'}
        first = self.client.post('/v1/i2i', headers=headers, json={'prompt': 'Edit it.', 'image': uploaded['id']})
        second = self.client.post('/v1/i2i', headers=headers, json={'prompt': 'Edit it.', 'image': uploaded['url']})
        self.assertEqual(first.status_code, 202)
        self.assertEqual(second.status_code, 202, second.text)
        self.assertEqual(first.json()['id'], second.json()['id'])

    def test_external_hosts_other_buckets_and_credentials_in_urls_are_rejected(self):
        urls = ['http://unconfigured.example/image.png', 'http://127.0.0.1:9000/private-secrets/image.png',
                'http://user:password@127.0.0.1:9000/sif-uploads/image.png', 'http://127.0.0.1:9000/video-assets/']
        for url in urls:
            response = self.client.post('/v1/i2i', headers=self.auth, json={'prompt': 'Edit it.', 'image': url})
            self.assertEqual(response.status_code, 422, response.text)
        self.assertEqual(self.objects.reads, [])
        self.assertEqual(self.fake.graphs, [])

    def test_expired_missing_and_invalid_minio_images_fail_before_job_creation(self):
        uploaded = self.upload().json()
        location = (uploaded['bucket'], uploaded['object_path'])
        self.objects.objects[location] = (png(), datetime.now(timezone.utc) - timedelta(hours=24, seconds=1))
        expired = self.client.post('/v1/i2i', headers=self.auth, json={'prompt': 'Edit it.', 'image': uploaded['url']})
        self.assertEqual(expired.status_code, 410, expired.text)
        del self.objects.objects[location]
        missing = self.client.post('/v1/i2i', headers=self.auth, json={'prompt': 'Edit it.', 'image': uploaded['url']})
        self.assertEqual(missing.status_code, 404, missing.text)
        self.objects.objects[location] = (b'not an image', datetime.now(timezone.utc))
        invalid = self.client.post('/v1/i2i', headers=self.auth, json={'prompt': 'Edit it.', 'image': uploaded['url']})
        self.assertEqual(invalid.status_code, 422, invalid.text)
        self.assertEqual(self.fake.graphs, [])

    def test_cleanup_only_removes_expired_sif_objects(self):
        old = datetime.now(timezone.utc) - timedelta(hours=24, seconds=1)
        now = datetime.now(timezone.utc)
        self.objects.objects.update({('sif-uploads', 'references/old.png'): (png(), old),
                                     ('sif-uploads', 'references/fresh.png'): (png(), now),
                                     ('sif-uploads', 'other/preserved.png'): (png(), old),
                                     ('video-assets', 'references/freyja.png'): (png(), old)})
        self.storage.cleanup_expired()
        self.assertEqual(self.objects.removed, [('sif-uploads', 'references/old.png')])
        self.assertEqual(len(self.objects.objects), 3)

    def test_multipart_and_url_generation_require_api_keys(self):
        self.assertEqual(self.client.post('/v1/uploads', files={'file': ('reference.png', png(), 'image/png')}).status_code, 401)
        self.assertEqual(self.client.post('/v1/i2i', json={'prompt': 'x', 'image': 'http://127.0.0.1:9000/video-assets/x.png'}).status_code, 401)


class RetentionTests(ServiceFixture):
    def test_expired_local_uploads_are_removed_and_pending_snapshots_are_protected(self):
        upload = self.client.post('/v1/uploads', headers=self.auth, content=png()).json()
        store = self.app.state.store
        path = store.upload(upload['id'])['path']
        path = Path(path)
        values = GenerateRequest(prompt='Edit it.', image=upload['id']).normalized('i2i')
        graph, values, metadata = Registry(self.settings.apps_dir).assemble(values, [(32, 32)])
        with store.lock:
            job, _ = store.create_job(values, graph, metadata, 'protected-snapshot', None, 100)
            store.update(job['id'], 'unknown')
            with store.db:
                store.db.execute('UPDATE uploads SET created=? WHERE id=?', (time.time() - 86401, upload['id']))
        store.cleanup_expired_uploads()
        self.assertTrue(path.is_file())
        store.update(job['id'], 'failed')
        store.cleanup_expired_uploads()
        self.assertFalse(path.exists())
        with self.assertRaises(ServiceError):
            store.upload(upload['id'])

    def test_expired_ids_are_rejected_even_before_the_next_cleanup_tick(self):
        upload = self.client.post('/v1/uploads', headers=self.auth, content=png()).json()
        store = self.app.state.store
        with store.lock, store.db:
            store.db.execute('UPDATE uploads SET created=? WHERE id=?', (time.time() - 86401, upload['id']))
        response = self.client.post('/v1/i2i', headers=self.auth, json={'prompt': 'Edit it.', 'image': upload['id']})
        self.assertIn(response.status_code, {404, 410})
        self.assertEqual(self.fake.graphs, [])


class CLIReferenceTests(unittest.TestCase):
    def test_existing_minio_urls_skip_binary_upload_and_local_file_access(self):
        spec = importlib.util.spec_from_file_location('sif_cli_minio', SOURCE.parent / 'sif_client/sif_client/cli.py')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        client = Mock()
        client.submit.return_value = {'id': '11111111-1111-4111-8111-111111111111', 'status': 'succeeded'}
        url = 'http://127.0.0.1:9000/sif-uploads/references/input.png?X-Amz-Signature=example'
        with patch.object(sys, 'argv', ['generate.py', 'i2i', '--image', url, '--prompt', 'Edit it.', '--no-expand']), \
                patch.dict('os.environ', {'SIF_API_KEY': 'sk-test'}), \
                patch.object(module, 'Client', return_value=client), \
                patch.object(module, 'download', return_value={'ok': True}), \
                patch('sys.stdout', new_callable=io.StringIO), patch('sys.stderr', new_callable=io.StringIO):
            module.main()
        self.assertEqual(client.submit.call_args.args[1]['images'], [url])
        client.request.assert_not_called()


if __name__ == '__main__':
    unittest.main()
