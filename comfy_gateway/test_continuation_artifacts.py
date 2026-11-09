import hashlib
import os
from pathlib import Path
import tempfile
import types
import unittest
from unittest.mock import patch
import httpx
from h3_continuation import ArtifactInput
from continuation_artifacts import upload_latent, cache_output_latent


class ArtifactTransportTest(unittest.IsolatedAsyncioTestCase):
    async def test_signed_source_is_digest_checked_and_uploaded_once(self):
        data=b"safe-tensor-test-data";seen=[]
        def handler(request):
            seen.append((request.method,request.url.host))
            if request.method=="GET":return httpx.Response(200,content=data)
            return httpx.Response(200,json={"name":"context.safetensors","subfolder":"managed"})
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as http:
            async def get_http():return http
            client=types.SimpleNamespace(get_http_client=get_http,http_base="http://compute.test")
            source=ArtifactInput(url="https://minio.test/object?signature=short-lived",content_sha256=hashlib.sha256(data).hexdigest())
            with patch.dict(os.environ,{"REFERENCE_MEDIA_HOSTS":"minio.test"}):
                self.assertEqual(await upload_latent(client,source),"managed/context.safetensors")
            self.assertEqual(seen,[("GET","minio.test"),("POST","compute.test")])

    async def test_redirect_digest_mismatch_and_untrusted_host_never_upload(self):
        for url,status,data,expected in [("https://other.test/a",200,b"x"*20,"URL_FORBIDDEN"),
                                         ("https://minio.test/a",200,b"x"*20,"DIGEST_MISMATCH"),
                                         ("https://minio.test/a",302,b"",None)]:
            uploaded=[]
            def handler(request):
                if request.method=="POST":uploaded.append(True)
                return httpx.Response(status,content=data,headers={"Location":"https://other.test/secret"})
            async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as http:
                async def get_http():return http
                client=types.SimpleNamespace(get_http_client=get_http,http_base="http://compute.test")
                with patch.dict(os.environ,{"REFERENCE_MEDIA_HOSTS":"minio.test"}):
                    with self.assertRaises((ValueError,httpx.HTTPStatusError)) as raised:
                        await upload_latent(client,ArtifactInput(url=url,content_sha256="a"*64))
                    if expected:self.assertIn(expected,str(raised.exception))
            self.assertFalse(uploaded)

    async def test_output_cache_is_atomic_and_reuses_the_exact_execution(self):
        calls=[]
        def handler(request):calls.append(request.url.path);return httpx.Response(200,content=b"safetensors-bytes-opaque")
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as http:
            async def get_http():return http
            client=types.SimpleNamespace(get_http_client=get_http,http_base="http://compute.test")
            asset={"filename":"raw.safetensors","subfolder":"freyja_h3","type":"output"}
            with tempfile.TemporaryDirectory() as folder:
                a=await cache_output_latent(client,asset,folder,"node:prompt")
                b=await cache_output_latent(client,asset,folder,"node:prompt")
                self.assertEqual(a,b);self.assertEqual(len(calls),1)
                self.assertEqual(list(Path(folder).glob("*.tmp")),[])
                with self.assertRaisesRegex(ValueError,"PATH_INVALID"):
                    await cache_output_latent(client,{**asset,"filename":"../raw.safetensors"},folder,"other")


if __name__=="__main__":unittest.main()
