"""Run synthetic carousel checks only on the owned Hermes watch emulator."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import threading
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--adb-port', default='5041')
    parser.add_argument('--serial', default='emulator-5570')
    parser.add_argument('--test', default='dev.quantumink.hermesgadget.wear.WatchCarouselTest')
    parser.add_argument('--reset-owned-emulator', action='store_true', required=True)
    parser.add_argument('--captures', type=Path)
    parser.add_argument('--legacy-pets', action='store_true')
    parser.add_argument('--pets', action='store_true', help='Isolated extension SDK and original companion fixtures')
    args = parser.parse_args()
    python = os.environ['HERMES_GADGET_EXTENSION_PYTHON' if args.pets or args.legacy_pets else 'HERMES_GADGET_PYTHON']
    if not args.serial.startswith('emulator-'):
        parser.error('Physical devices are excluded')
    adb = [args.adb, '-P', args.adb_port, '-s', args.serial]

    def run(*command, timeout=60):
        result = subprocess.run(adb + list(command), capture_output=True, text=True, timeout=timeout)
        result.check_returncode()
        return result.stdout

    if run('shell', 'getprop', 'ro.boot.qemu.avd_name').strip() != 'Hermes_M2':
        parser.error('Only the owned Hermes_M2 AVD is allowed')
    processes, ports = [], []
    with tempfile.TemporaryDirectory(prefix='hermes-carousel-') as temporary:
        try:
            for index in range(3):
                ready = Path(temporary) / f'ready-{index}.json'
                extra = ['--pets', '--companion', '--fixture-index', str(index)] if args.pets else ['--pets'] if args.legacy_pets else []
                process = subprocess.Popen([python, '-u', 'tools/integration_devserver.py', '--prompt-fixture', '--ready-file', str(ready)] + extra, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
                processes.append(process)
                def approve(server=process):
                    for line in server.stdout:
                        match = re.search(r'Code: ([A-Z0-9]+)', line)
                        if match:
                            server.stdin.write('approve ' + match.group(1) + '\n')
                            server.stdin.flush()
                threading.Thread(target=approve, daemon=True).start()
                deadline = time.monotonic() + 10
                while not ready.exists():
                    if process.poll() is not None or time.monotonic() >= deadline:
                        raise RuntimeError('Synthetic stock server did not start')
                    time.sleep(.05)
                port = str(json.loads(ready.read_text())['port'])
                ports.append(port)
                run('reverse', 'tcp:' + port, 'tcp:' + port)
            for artifact in ('wear/build/outputs/apk/debug/wear-debug.apk', 'wear/build/outputs/apk/androidTest/debug/wear-debug-androidTest.apk'):
                if 'Success' not in run('install', '-r', artifact):
                    raise RuntimeError('Install failed; existing package was preserved')
            if 'Success' not in run('shell', 'pm', 'clear', 'dev.quantumink.hermesgadget'):
                raise RuntimeError('Owned emulator reset failed')
            run('shell', 'run-as', 'dev.quantumink.hermesgadget', 'mkdir', '-p', 'files')
            for index in range(3):
                portrait = Path('docs/demo/carousel/fixtures') / f'box-{index + 1}' / 'portrait.png'
                if portrait.exists():
                    destination = '/data/local/tmp/carousel-portrait.png'
                    run('push', str(portrait), destination)
                    run('shell', 'run-as', 'dev.quantumink.hermesgadget', 'cp', destination, f'files/synthetic-portrait-{index}.png')
                    run('shell', 'rm', destination)
            run('shell', 'input', 'keyevent', '224')
            command = ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class', args.test]
            if args.pets: command += ['-e', 'syntheticCompanion', 'true']
            for name, port in zip(('syntheticEndpoint', 'syntheticSecondEndpoint', 'syntheticThirdEndpoint'), ports):
                command += ['-e', name, 'ws://localhost:' + port + '/gadget']
            command += ['dev.quantumink.hermesgadget.test/androidx.test.runner.AndroidJUnitRunner']
            output = run(*command, timeout=180)
            print(output)
            if 'OK (' not in output or 'FAILURES' in output or 'INSTRUMENTATION_FAILED' in output:
                raise RuntimeError('Carousel instrumentation did not pass')
            if args.captures:
                args.captures.mkdir(parents=True, exist_ok=True)
                for name in run('shell', 'run-as', 'dev.quantumink.hermesgadget', 'ls', 'files/carousel-captures').splitlines():
                    if re.fullmatch(r'[a-zA-Z0-9_-]+\.png', name):
                        data = subprocess.check_output(adb + ['exec-out', 'run-as', 'dev.quantumink.hermesgadget', 'cat', 'files/carousel-captures/' + name])
                        (args.captures / name).write_bytes(data)
        finally:
            for port in ports:
                run('reverse', '--remove', 'tcp:' + port)
            for server in processes:
                if server.poll() is None:
                    server.terminate()
                    server.wait(timeout=5)
                if server.stdin:
                    server.stdin.close()
                if server.stdout:
                    server.stdout.close()


if __name__ == '__main__':
    main()
