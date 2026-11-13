import base64
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


PROJECT = Path(__file__).resolve().parents[1]
EXECUTABLE = os.environ.get("SIF_EXE")
KEY = "sk-release-test-key"
JOB_ID = "11111111-1111-4111-8111-111111111111"
PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aX1sAAAAASUVORK5CYII="
)


@unittest.skipUnless(os.name == "nt" and EXECUTABLE, "Set SIF_EXE to test the Windows release")
class WindowsReleaseTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(dir=PROJECT)
        self.addCleanup(self.temporary.cleanup)
        self.home = Path(self.temporary.name)
        self.portable = self.home / "SIF portable 中文"
        self.portable.mkdir()
        self.executable = self.portable / "sif.exe"
        shutil.copyfile(EXECUTABLE, self.executable)
        release_directory = Path(EXECUTABLE).resolve().parent
        for name in ("install.cmd", "install.ps1", "uninstall.cmd", "uninstall.ps1"):
            shutil.copyfile(release_directory / name, self.portable / name)
        self.system = Path(os.environ["SystemRoot"]) / "System32"
        self.powershell = self.system / "WindowsPowerShell/v1.0/powershell.exe"
        self.environment = {name: value for name, value in os.environ.items()
                            if not name.startswith(("SIF_", "COMFY_IMAGE_", "PYTHON"))}
        self.environment.update(PATH=str(self.system), SIF_CONFIG=str(self.home / "client.json"))

    def run_process(self, command, input=None, cwd=None):
        result = subprocess.run(command, input=input, capture_output=True, encoding="utf-8",
                                env=self.environment, cwd=cwd or self.home, timeout=60)
        self.assertNotIn(KEY, result.stdout + result.stderr)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        return result

    def invoke(self, *arguments, input=None):
        result = self.run_process([str(self.executable), *arguments], input=input)
        return json.loads(result.stdout)

    def test_cmd_and_powershell_run_the_executable_without_python_on_path(self):
        for command in ([str(self.system / "cmd.exe"), "/d", "/c", "sif.exe --help"],
                        [str(self.powershell), "-NoProfile", "-Command", ".\\sif.exe --help"]):
            with self.subTest(command=command):
                result = self.run_process(command, cwd=self.portable)
                self.assertIn("configure", result.stdout)

    def test_saved_key_and_complete_rest_generation_work_in_the_frozen_executable(self):
        calls = []
        waits = []
        job = {
            "id": JOB_ID,
            "status": "succeeded",
            "request": {"app": "minimax-h3-image", "mode": "t2i", "tier": "fast", "seed": 42},
            "result": {"effective_prompt": "A red teapot", "images": [
                {"format": "png", "width": 1, "height": 1, "seed": 42}
            ]},
        }

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *arguments):
                pass

            def respond(self, value, status=200):
                payload = value if isinstance(value, bytes) else json.dumps(value).encode()
                self.send_response(status)
                self.end_headers()
                self.wfile.write(payload)

            def do_POST(self):
                payload = self.rfile.read(int(self.headers.get("Content-Length", 0)))
                calls.append((self.path, dict(self.headers), payload))
                if self.path == "/v1/uploads":
                    self.respond({"url": "http://minio.example/reference.png"})
                else:
                    self.respond({"id": JOB_ID, "status": "queued"}, 202)

            def do_GET(self):
                calls.append((self.path, dict(self.headers), None))
                if "/wait?" in self.path:
                    waits.append(self.path)
                    self.respond({"id": JOB_ID, "status": "queued", "wait_timed_out": True}
                                 if len(waits) == 1 else job)
                elif self.path.endswith("/images/0"):
                    self.respond(PNG)
                elif self.path in {"/v1/capabilities", "/v1/readiness", "/v1/queue", "/v1/jobs"}:
                    self.respond({"ok": True})
                else:
                    self.respond(job)

        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        address = f"http://127.0.0.1:{server.server_port}"
        self.invoke("configure", "--base-url", address, "--api-key-stdin", input=KEY + "\n")
        self.assertTrue(self.invoke("config")["api_key_saved"])
        for command in ("capabilities", "readiness", "queue", "jobs"):
            self.assertTrue(self.invoke(command)["ok"])
        output = self.home / "生成 图片.png"
        generated = self.invoke("t2i", "--prompt", "A red teapot", "--tier", "fast", "--no-expand",
                                "--output", str(output), "--idempotency-key", "release-render")
        self.assertEqual(generated["images"][0]["path"], str(output.resolve()))
        self.assertEqual(output.read_bytes(), PNG)
        reference = self.home / "reference.png"
        reference.write_bytes(PNG)
        self.invoke("i2i", "--prompt", "Edit <Picture 1>.", "--image", str(reference),
                    "--output", str(self.home / "edited.png"))
        self.invoke("status", JOB_ID, "--output", str(self.home / "recovered.png"))
        submissions = [call for call in calls if call[0] in {"/v1/t2i", "/v1/i2i"}]
        self.assertEqual(len(submissions), 2)
        self.assertEqual(submissions[0][1]["Idempotency-Key"], "release-render")
        self.assertFalse(json.loads(submissions[0][2])["expand_prompt"])
        self.assertEqual(json.loads(submissions[1][2])["images"], ["http://minio.example/reference.png"])
        self.assertTrue(all(call[0].startswith("/v1/") for call in calls))
        self.assertTrue(all(call[1]["Authorization"] == "Bearer " + KEY for call in calls))
        self.assertFalse(self.invoke("logout")["api_key_saved"])

    def test_install_upgrade_and_uninstall_preserve_unrelated_files_and_optional_config(self):
        installed = self.home / "Installed SIF"
        command = [str(self.system / "cmd.exe"), "/d", "/c", "call", str(self.portable / "install.cmd"),
                   "-InstallDirectory", str(installed), "-SkipPathUpdate"]
        self.run_process(command)
        self.run_process(command)
        self.assertEqual((installed / "sif.exe").read_bytes(), self.executable.read_bytes())
        config = self.home / "client.json"
        config.write_text(json.dumps({"api_key": KEY}))
        unrelated = installed / "keep.txt"
        unrelated.write_text("user file")
        uninstall = [str(self.system / "cmd.exe"), "/d", "/c", "call", str(installed / "uninstall.cmd"),
                     "-SkipPathUpdate", "-ConfigPath", str(config)]
        self.run_process(uninstall)
        self.assertTrue(config.exists())
        self.assertEqual(list(installed.iterdir()), [unrelated])
        self.run_process(command)
        self.run_process([*uninstall, "-RemoveConfig"])
        self.assertFalse(config.exists())
        self.assertTrue(unrelated.exists())
        unrelated.unlink()
        self.run_process(command)
        self.run_process(uninstall)
        self.assertFalse(installed.exists())


if __name__ == "__main__":
    unittest.main()
