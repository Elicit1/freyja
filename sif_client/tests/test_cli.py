import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch


PROJECT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("sif_cli", PROJECT / "sif_client/cli.py")
cli = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cli)
KEY = "sk-client-test-secret"
JOB_ID = "11111111-1111-4111-8111-111111111111"


class ClientTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(dir=PROJECT)
        self.addCleanup(self.directory.cleanup)
        self.home = Path(self.directory.name)
        self.path = self.home / ".sif/config.json"
        home_patch = patch.object(cli.Path, "home", return_value=self.home)
        home_patch.start()
        self.addCleanup(home_patch.stop)
        environment = {name: value for name, value in os.environ.items()
                       if not name.startswith(("SIF_", "COMFY_IMAGE_"))}
        environment_patch = patch.dict(os.environ, environment, clear=True)
        environment_patch.start()
        self.addCleanup(environment_patch.stop)

    def invoke(self, *arguments, key=KEY):
        output, errors = io.StringIO(), io.StringIO()
        with patch.object(sys, "argv", ["sif", *arguments]), \
                patch.object(cli.getpass, "getpass", return_value=key), \
                contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
            cli.main()
        self.assertNotIn(KEY, output.getvalue() + errors.getvalue())
        return json.loads(output.getvalue())

    def test_saved_configuration_is_loaded_on_later_calls_without_prompting(self):
        saved = self.invoke("configure", "--base-url", "http://localhost:8011/")
        self.assertEqual(saved["config_path"], str(self.path.resolve()))
        self.assertEqual(json.loads(self.path.read_text())["api_key"], KEY)
        with patch.object(cli, "Client") as client:
            client.return_value.request.return_value = {"ready": True}
            self.assertTrue(self.invoke("capabilities")["ready"])
            client.assert_called_once_with("http://localhost:8011", KEY)
        self.assertTrue(self.invoke("config")["api_key_saved"])
        if os.name == "posix":
            self.assertEqual(self.path.stat().st_mode & 0o777, 0o600)

    def test_custom_configuration_can_be_created_with_key_from_stdin(self):
        target = self.home / "private/client.json"
        with patch.object(sys, "stdin", io.StringIO(KEY + "\n")):
            self.invoke("--config", str(target), "configure", "--base-url", "https://sif.example", "--api-key-stdin")
        self.assertEqual(json.loads(target.read_text())["api_key"], KEY)
        self.assertFalse(self.path.exists())

    def test_environment_overrides_saved_values_and_global_url_overrides_environment(self):
        self.invoke("configure")
        with patch.dict(os.environ, {"SIF_BASE_URL": "https://environment.example", "SIF_API_KEY": "sk-environment"}), \
                patch.object(cli, "Client") as client:
            client.return_value.request.return_value = {}
            self.invoke("readiness")
            client.assert_called_with("https://environment.example", "sk-environment")
            self.invoke("--base-url", "https://override.example", "queue")
            client.assert_called_with("https://override.example", "sk-environment")

    def test_config_path_precedence_and_legacy_environment_aliases(self):
        with patch.dict(os.environ, {"SIF_CONFIG": str(self.home / "primary.json"),
                                     "COMFY_IMAGE_CONFIG": str(self.home / "legacy.json")}):
            self.assertEqual(cli.config_path(), self.home / "primary.json")
            self.assertEqual(cli.config_path(self.path), self.path)
        with patch.dict(os.environ, {"COMFY_IMAGE_BASE_URL": "https://legacy.example", "COMFY_IMAGE_API_KEY": "sk-legacy"}), \
                patch.object(cli, "Client") as client:
            client.return_value.request.return_value = {}
            self.invoke("jobs")
            client.assert_called_with("https://legacy.example", "sk-legacy")

    def test_logout_removes_saved_key_and_keeps_server_address(self):
        self.invoke("configure", "--base-url", "https://sif.example")
        self.assertFalse(self.invoke("logout")["api_key_saved"])
        self.assertEqual(json.loads(self.path.read_text()), {"base_url": "https://sif.example"})
        with self.assertRaisesRegex(ValueError, "sif configure"):
            self.invoke("capabilities")

    def test_explicit_missing_configuration_fails_without_falling_back(self):
        self.invoke("configure")
        with self.assertRaises(FileNotFoundError):
            self.invoke("--config", str(self.home / "missing.json"), "capabilities")

    def test_invalid_configuration_and_key_cannot_replace_existing_credentials(self):
        self.invoke("configure")
        before = self.path.read_bytes()
        for key in ("", "sk-", "sk-bad\nvalue", "wrong"):
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.invoke("configure", key=key)
            self.assertEqual(self.path.read_bytes(), before)
        for url in ("file:///tmp/x", "https://user:password@example.com", "https://sif.example?key=x"):
            with self.subTest(url=url), self.assertRaises(ValueError):
                self.invoke("configure", "--base-url", url)
        self.path.write_text("[]")
        with self.assertRaisesRegex(ValueError, "JSON object"):
            self.invoke("config")

    def test_failed_atomic_replace_preserves_previous_configuration(self):
        self.invoke("configure")
        before = self.path.read_bytes()
        with patch.object(cli.os, "replace", side_effect=OSError("write failed")), self.assertRaises(OSError):
            self.invoke("configure", "--base-url", "https://new.example")
        self.assertEqual(self.path.read_bytes(), before)
        self.assertEqual(list(self.path.parent.glob(".sif-*")), [])

    def test_source_client_runs_with_only_the_standard_library_from_another_directory(self):
        process = subprocess.run([sys.executable, "-S", str(PROJECT / "generate.py"), "--help"],
                                 cwd=self.home, capture_output=True, text=True)
        self.assertEqual(process.returncode, 0, process.stderr)
        self.assertIn("configure", process.stdout)

    def test_saved_credentials_survive_separate_cli_processes(self):
        environment = {**os.environ, "SIF_CONFIG": str(self.home / "client.json")}
        command = [sys.executable, "-S", str(PROJECT / "generate.py")]
        saved = subprocess.run([*command, "configure", "--api-key-stdin"], input=KEY + "\n",
                               cwd=self.home, env=environment, capture_output=True, text=True)
        self.assertEqual(saved.returncode, 0, saved.stderr)
        inspected = subprocess.run([*command, "config"], cwd=self.home, env=environment,
                                   capture_output=True, text=True)
        self.assertEqual(inspected.returncode, 0, inspected.stderr)
        self.assertTrue(json.loads(inspected.stdout)["api_key_saved"])
        self.assertNotIn(KEY, saved.stdout + saved.stderr + inspected.stdout + inspected.stderr)
        self.assertEqual(json.loads((self.home / "client.json").read_text())["api_key"], KEY)

    def test_http_render_upload_wait_recovery_and_download_use_saved_bearer_key(self):
        calls = []
        image = b"test-image-download"
        job = {"id": JOB_ID, "status": "succeeded", "request": {
            "app": "minimax-h3-image", "mode": "t2i", "tier": "fast", "seed": 42},
            "result": {"effective_prompt": "A teapot", "images": [{
                "format": "png", "width": 2048, "height": 1152, "seed": 42}]}}

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def respond(self, value, status=200):
                data = value if isinstance(value, bytes) else json.dumps(value).encode()
                self.send_response(status)
                self.end_headers()
                self.wfile.write(data)

            def do_POST(self):
                body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
                calls.append((self.command, self.path, dict(self.headers), body))
                if self.path == "/v1/uploads":
                    self.respond({"url": "http://minio.example/input.png"})
                else:
                    self.respond({"id": JOB_ID, "status": "queued"}, 202)

            def do_GET(self):
                calls.append((self.command, self.path, dict(self.headers), None))
                if self.path.endswith("/images/0"):
                    self.respond(image)
                elif self.path == "/v1/capabilities":
                    self.respond({"apps": []})
                elif self.path == "/redirect":
                    self.send_response(302)
                    self.send_header("Location", "/leaked")
                    self.end_headers()
                else:
                    self.respond(job)

        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        base_url = f"http://127.0.0.1:{server.server_port}"
        self.invoke("configure", "--base-url", base_url)
        self.invoke("capabilities")
        output = self.home / "teapot.png"
        result = self.invoke("t2i", "--prompt", "A teapot", "--tier", "fast", "--no-expand",
                             "--output", str(output), "--idempotency-key", "render-test")
        self.assertEqual(result["images"][0]["path"], str(output.resolve()))
        self.assertEqual(output.read_bytes(), image)
        reference = self.home / "reference.png"
        reference.write_bytes(b"reference-input")
        self.invoke("i2i", "--prompt", "Edit <Picture 1>.", "--image", str(reference),
                    "--output", str(self.home / "edit.png"))
        submissions = [call for call in calls if call[1] in {"/v1/t2i", "/v1/i2i"}]
        self.assertEqual(submissions[0][2]["Idempotency-Key"], "render-test")
        self.assertFalse(json.loads(submissions[0][3])["expand_prompt"])
        self.assertEqual(json.loads(submissions[1][3])["images"], ["http://minio.example/input.png"])
        self.invoke("status", JOB_ID, "--output", str(self.home / "recovered.png"))
        self.assertEqual(len([call for call in calls if call[1] in {"/v1/t2i", "/v1/i2i"}]), 2)
        self.assertTrue(any("/wait?" in call[1] for call in calls))
        self.assertTrue(all(call[2]["Authorization"] == "Bearer " + KEY for call in calls))
        self.assertTrue(all(call[1].startswith("/v1/") for call in calls))
        with self.assertRaisesRegex(RuntimeError, "redirected"):
            cli.Client(base_url, KEY).request("/redirect")
        self.assertFalse(any(call[1] == "/leaked" for call in calls))


if __name__ == "__main__":
    unittest.main()
