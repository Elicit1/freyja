#!/usr/bin/env python3
"""Compatibility entrypoint for the separately installed SIF Client."""
import sys
from pathlib import Path

# Source checkouts can use the independent client without installing a package.
client_project = (Path(__file__).resolve().parent / "../../../../sif_client").resolve()
if (client_project / "sif_client" / "cli.py").is_file():
    sys.path.insert(0, str(client_project))

if __name__ == "__main__":
    try:
        from sif_client.cli import entrypoint
    except ModuleNotFoundError:
        sys.exit("Install SIF Client first: python -m pip install /path/to/sif_client. Then run: sif configure")
    entrypoint()
