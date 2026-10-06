"""Apply the reviewed project patch series to an isolated pinned SDK; never touch live Hermes."""

import argparse
import subprocess
import sys
import venv
from pathlib import Path

SDK_COMMIT = "323e3303ab68981f810fc3208119cd8a22e64af0"
ROOT = Path(__file__).resolve().parents[1]
SDK = ROOT / ".local" / "upstream" / "hermes-gadget-sdk-patched"
ENVIRONMENT = ROOT / ".local" / "extension-venv"


def run(*args: str) -> None:
    subprocess.run(args, check=True, cwd=ROOT)


def main(sdk: Path = SDK, environment: Path = ENVIRONMENT) -> None:
    sdk, environment = sdk.resolve(), environment.resolve()
    local = (ROOT / ".local").resolve()
    if not sdk.is_relative_to(local) or not environment.is_relative_to(local):
        raise RuntimeError("Extension checkouts and environments must stay under ignored .local")
    if not sdk.exists():
        sdk.parent.mkdir(parents=True, exist_ok=True)
        run("git", "clone", "--depth", "1", "--branch", "v0.2.0", "https://github.com/Adolanium/hermes-gadget-sdk.git", str(sdk))
    commit = subprocess.check_output(["git", "-C", str(sdk), "rev-parse", "HEAD"], text=True).strip()
    if commit != SDK_COMMIT:
        raise RuntimeError("Patched SDK base does not match the pin")
    if subprocess.check_output(["git", "-C", str(sdk), "status", "--porcelain"], text=True).strip():
        raise RuntimeError("Use a fresh isolated SDK checkout to apply the patch series")
    for patch in sorted((ROOT / "server" / "patches").glob("*.patch")):
        run("git", "-C", str(sdk), "apply", "--check", str(patch))
        run("git", "-C", str(sdk), "apply", str(patch))
    if not environment.exists():
        venv.create(environment, with_pip=True)
    python = environment / ("Scripts/python.exe" if sys.platform == "win32" else "bin/python")
    pip = subprocess.run([str(python), "-m", "pip", "--version"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if pip.returncode:
        run(str(python), "-m", "ensurepip", "--upgrade")
    run(str(python), "-m", "pip", "install", "websockets==16.1.1", "Pillow==12.3.0", "pytest==9.1.1")
    run(str(python), "-m", "pip", "install", "--no-deps", "-e", str(sdk))
    print("Isolated patched SDK ready; live Hermes and the stock test environment are unchanged.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk-dir", type=Path, default=SDK)
    parser.add_argument("--environment", type=Path, default=ENVIRONMENT)
    args = parser.parse_args()
    main(args.sdk_dir, args.environment)
