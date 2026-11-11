"""Register a trusted local app bundle without changing or restarting the service."""
import argparse
import shutil
import tempfile
from pathlib import Path

from .config import Settings
from .registry import Manifest, Registry


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=["validate", "register"])
    parser.add_argument("bundle", type=Path, help="Directory containing app.json and API workflow JSON files")
    args = parser.parse_args()
    source = args.bundle.resolve()
    manifest = Manifest.model_validate_json((source / "app.json").read_bytes())
    settings = Settings.from_env()
    settings.apps_dir.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="image-app-") as temp:
        staging_root = Path(temp)
        staging = staging_root / manifest.id
        shutil.copytree(source, staging)
        registry = Registry(staging_root)
        apps, errors = registry.scan()
        if errors or manifest.id not in apps:
            raise ValueError(str(errors))
        if args.command == "register":
            target = settings.apps_dir / manifest.id
            if target.exists():
                raise FileExistsError("An app with this directory already exists. Review and update its bundle files explicitly.")
            shutil.copytree(staging, target)
            print(f"Registered {manifest.id}. Discovery is immediate; no service restart required.")
        else:
            print(f"Valid app: {manifest.id}")


if __name__ == "__main__":
    main()
