"""Install pinned test dependencies and the unmodified SDK into ignored local state."""

import subprocess
import sys
import venv
from pathlib import Path

SDK_COMMIT = "323e3303ab68981f810fc3208119cd8a22e64af0"
ROOT = Path(__file__).resolve().parents[1]
SDK = ROOT / ".local" / "upstream" / "hermes-gadget-sdk"
ENVIRONMENT = ROOT / ".local" / "venv"


def run(*args: str) -> None:
    subprocess.run(args, check=True, cwd=ROOT)


def main() -> None:
    if not SDK.exists():
        SDK.parent.mkdir(parents=True, exist_ok=True)
        run("git", "clone", "--depth", "1", "--branch", "v0.2.0", "https://github.com/Adolanium/hermes-gadget-sdk.git", str(SDK))
    commit = subprocess.check_output(["git", "-C", str(SDK), "rev-parse", "HEAD"], text=True).strip()
    if commit != SDK_COMMIT:
        raise RuntimeError("Local SDK checkout does not match the pinned commit")
    if subprocess.check_output(["git", "-C", str(SDK), "status", "--porcelain"], text=True).strip():
        raise RuntimeError("Local SDK checkout must be unmodified")
    if not ENVIRONMENT.exists():
        venv.create(ENVIRONMENT, with_pip=True)
    python = ENVIRONMENT / ("Scripts/python.exe" if sys.platform == "win32" else "bin/python")
    pip = subprocess.run([str(python), "-m", "pip", "--version"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if pip.returncode:
        run(str(python), "-m", "ensurepip", "--upgrade")
    run(str(python), "-m", "pip", "install", "websockets==16.1.1", "Pillow==12.3.0", "pytest==9.1.1")
    run(str(python), "-m", "pip", "install", "--no-deps", "-e", str(SDK))
    print("Pinned SDK devserver ready. Set HERMES_GADGET_PYTHON to the local virtualenv Python.")


if __name__ == "__main__":
    main()
