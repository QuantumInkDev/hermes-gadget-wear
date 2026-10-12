"""Owned phone emulator acceptance against two isolated stock TLS Gadget servers."""

import argparse
import base64
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import threading
import time


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    adb = Path(os.environ.get("ANDROID_HOME", Path.home() / "Library/Android/sdk")) / "platform-tools/adb"
    env = dict(os.environ, ADB_MDNS_AUTO_CONNECT="0")
    base = [str(adb), "-s", args.serial]
    if not args.serial.startswith("emulator-"):
        raise SystemExit("This fixture accepts an owned emulator only.")
    name = subprocess.check_output(base + ["emu", "avd", "name"], env=env, text=True).splitlines()[0]
    if name != "Hermes_M6_Phone":
        raise SystemExit("Use the owned Hermes_M6_Phone AVD.")
    python = os.environ.get("HERMES_GADGET_PYTHON", ".local/venv/bin/python")
    servers = []
    ports = []
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="phone-gadget-") as temporary:
        directory = Path(temporary)
        cert, key = directory / "cert.pem", directory / "key.pem"
        subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
                        "-subj", "/CN=Synthetic phone fixture", "-addext", "subjectAltName=DNS:localhost",
                        "-keyout", str(key), "-out", str(cert)], check=True, stdout=subprocess.DEVNULL,
                       stderr=subprocess.DEVNULL)
        try:
            for index in range(2):
                ready = directory / f"ready-{index}.json"
                server = subprocess.Popen([python, "-u", "tools/integration_devserver.py", "--ready-file",
                                           str(ready), "--tls-cert", str(cert), "--tls-key", str(key)],
                                          stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                          stderr=subprocess.DEVNULL, text=True, bufsize=1)
                servers.append(server)

                def approve(process=server):
                    for line in process.stdout:
                        match = re.search(r"Code: ([A-Z0-9]+)", line)
                        if match:
                            try:
                                process.stdin.write("approve " + match.group(1) + "\n")
                                process.stdin.flush()
                            except (BrokenPipeError, ValueError):
                                return

                threading.Thread(target=approve, daemon=True).start()
                deadline = time.monotonic() + 10
                while not ready.exists():
                    if server.poll() is not None or time.monotonic() > deadline:
                        raise RuntimeError("Isolated stock TLS server did not become ready.")
                    time.sleep(.025)
                port = json.loads(ready.read_text())["port"]
                ports.append(port)
                subprocess.run(base + ["reverse", f"tcp:{port}", f"tcp:{port}"], env=env, check=True,
                               stdout=subprocess.DEVNULL)
            subprocess.run(base + ["shell", "am", "force-stop", "com.callstack.agentdevice.snapshothelper"],
                           env=env, check=True)
            for apk in ["mobile/build/outputs/apk/debug/mobile-debug.apk",
                        "mobile/build/outputs/apk/androidTest/debug/mobile-debug-androidTest.apk"]:
                subprocess.run(base + ["install", "-r", apk], env=env, check=True)
            command = base + ["shell", "am", "instrument", "-w", "-r", "-e", "class",
                              "dev.quantumink.hermesgadget.mobile.ConversationVaultTest,"
                              "dev.quantumink.hermesgadget.mobile.PhoneConversationTest",
                              "-e", "phonePort1", str(ports[0]), "-e", "phonePort2", str(ports[1]),
                              "-e", "phoneCertificate", base64.b64encode(cert.read_bytes()).decode(),
                              "dev.quantumink.hermesgadget.test/androidx.test.runner.AndroidJUnitRunner"]
            with args.output.open("w") as output:
                subprocess.run(command, env=env, check=True, stdout=output, stderr=subprocess.STDOUT,
                               timeout=90)
            result = args.output.read_text()
            if "OK (2 tests)" not in result or "FAILURES!!!" in result:
                raise RuntimeError("Phone client instrumentation failed; inspect the output log.")
            print("Phone identity and two-stock-TLS-server checks passed.")
        finally:
            for port in ports:
                subprocess.run(base + ["reverse", "--remove", f"tcp:{port}"], env=env,
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            for server in servers:
                server.terminate()
                try:
                    server.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    server.kill()
                    server.wait(timeout=5)


if __name__ == "__main__":
    main()
