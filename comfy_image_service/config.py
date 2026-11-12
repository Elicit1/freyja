import os
from dataclasses import dataclass
from pathlib import Path

from dotenv import load_dotenv


@dataclass(frozen=True)
class Settings:
    home: Path
    initial_password: str = ""
    comfy_url: str = "http://127.0.0.1:8188"
    public_base_url: str = ""
    timeout_seconds: float = 1800
    poll_seconds: float = 2
    max_upload_bytes: int = 25 * 1024 * 1024
    max_output_bytes: int = 128 * 1024 * 1024
    max_pending_jobs: int = 100

    @property
    def apps_dir(self):
        return self.home / "apps"

    @property
    def data_dir(self):
        return self.home / "data"

    @classmethod
    def from_env(cls):
        home = Path(os.environ.get("IMAGE_SERVICE_HOME", Path(__file__).parent)).resolve()
        load_dotenv(home / ".env", override=False)
        return cls(home=home, initial_password=os.environ.get("SIF_ADMIN_PASSWORD", ""),
                   comfy_url=os.environ.get("COMFY_URL", "http://127.0.0.1:8188").rstrip("/"),
                   public_base_url=os.environ.get("IMAGE_PUBLIC_BASE_URL", "").rstrip("/"),
                   timeout_seconds=float(os.environ.get("IMAGE_TIMEOUT_SECONDS", "1800")))
