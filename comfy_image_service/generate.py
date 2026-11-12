"""SIF dependency-free command-line entrypoint for local AI agents."""
import runpy
from pathlib import Path

if __name__ == "__main__":
    runpy.run_path(str(Path(__file__).parent / "skills/sif-image-generation/scripts/generate.py"), run_name="__main__")
