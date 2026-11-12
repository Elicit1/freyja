"""Optional browser acceptance: run with Playwright installed and the local Edge browser."""
import json
import shutil
import socket
import tempfile
import threading
import time
from pathlib import Path

import uvicorn
from playwright.sync_api import sync_playwright

from comfy_image_service.api import create_app
from comfy_image_service.config import Settings
from comfy_image_service.tests.test_service import PASSWORD, SOURCE, FakeComfy


def main():
    output = SOURCE / "data" / "verification"
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as directory:
        home = Path(directory)
        shutil.copytree(SOURCE / "apps", home / "apps")
        app = create_app(Settings(home=home, initial_password=PASSWORD), FakeComfy())
        listener = socket.socket()
        listener.bind(("127.0.0.1", 0))
        listener.listen()
        port = listener.getsockname()[1]
        server = uvicorn.Server(uvicorn.Config(app, log_level="error"))
        thread = threading.Thread(target=lambda: server.run(sockets=[listener]), daemon=True)
        thread.start()
        for _ in range(200):
            if server.started:
                break
            time.sleep(0.025)
        assert server.started
        try:
            with sync_playwright() as playwright:
                browser = playwright.chromium.launch(channel="msedge", headless=True)
                context = browser.new_context(viewport={"width": 1440, "height": 1050}, device_scale_factor=1)
                page = context.new_page()
                errors = []
                page.on("pageerror", lambda error: errors.append(str(error)))
                base = f"http://127.0.0.1:{port}"
                page.goto(base)
                page.locator("#login-view").wait_for(state="visible")
                page.screenshot(path=str(output / "sif-login.png"), full_page=True)
                page.locator("#login-password").fill("incorrect")
                page.locator("#login-form button[type=submit]").click()
                page.get_by_text("密码不正确，请重试。").wait_for()
                page.locator("#login-password").fill(PASSWORD)
                page.locator("#login-form button[type=submit]").click()
                page.get_by_text("暂无排队任务", exact=True).wait_for()
                page.screenshot(path=str(output / "sif-overview.png"), full_page=True)
                page.locator("nav [data-page=keys]").click()
                page.get_by_role("button", name="＋ 创建 API Key").click()
                page.locator("#key-name").fill("Desktop Agent")
                page.get_by_role("button", name="生成 API Key", exact=True).click()
                page.locator("#key-result").wait_for(state="visible")
                key = page.locator("#new-key").input_value()
                assert key.startswith("sk-")
                assert page.request.get(base + "/v1/capabilities", headers={"Authorization": "Bearer " + key}).status == 200
                page.get_by_role("button", name="我已保存", exact=True).click()
                assert page.locator("#new-key").input_value() == ""
                page.get_by_role("cell", name="Desktop Agent", exact=True).wait_for()
                assert key not in page.locator("#keys-body").inner_text()
                page.screenshot(path=str(output / "sif-api-keys.png"), full_page=True)
                page.locator("nav [data-page=mcp]").click()
                assert "YOUR_API_KEY" in page.locator("#mcp-config").inner_text()
                page.screenshot(path=str(output / "sif-mcp.png"), full_page=True)
                page.set_viewport_size({"width": 390, "height": 844})
                page.locator("nav [data-page=overview]").click()
                assert page.evaluate("document.documentElement.scrollWidth <= innerWidth")
                page.screenshot(path=str(output / "sif-mobile.png"), full_page=True)
                page.set_viewport_size({"width": 1440, "height": 1050})
                page.locator("nav [data-page=keys]").click()
                page.get_by_role("button", name="撤销", exact=True).click()
                page.locator("#confirm-accept").click()
                page.get_by_role("cell", name="已撤销", exact=True).wait_for()
                assert page.request.get(base + "/v1/capabilities", headers={"Authorization": "Bearer " + key}).status == 401
                page.locator("nav [data-page=security]").click()
                page.locator("#current-password").fill(PASSWORD)
                new_password = "browser-changed-password-123"
                page.locator("#new-password").fill(new_password)
                page.locator("#confirm-password").fill(new_password)
                page.locator("#password-form button[type=submit]").click()
                page.locator("#login-view").wait_for(state="visible")
                page.locator("#login-password").fill(new_password)
                page.locator("#login-form button[type=submit]").click()
                page.locator("#console-view").wait_for(state="visible")
                page.locator("#logout").click()
                page.locator("#login-view").wait_for(state="visible")
                assert not errors, errors
                browser.close()
                result = {"login": "passed", "key_create_and_revoke": "passed", "mcp_configuration": "passed",
                          "password_change_and_relogin": "passed", "mobile_overflow": False, "javascript_errors": errors}
                (output / "sif-browser-verification.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
                print(json.dumps(result))
        finally:
            server.should_exit = True
            thread.join(timeout=10)
            listener.close()


if __name__ == "__main__":
    main()
