"""Launch the actual pinned SDK devserver with ephemeral local state for JVM tests."""

import argparse
import asyncio
import json
import socket
import ssl
import tempfile
from pathlib import Path

from hermes_gadget.devserver import EchoBrain, _console, serve
from hermes_gadget_plugin.hub import DeviceHub
from hermes_gadget_plugin.store import DeviceStore


async def tls_server(directory: Path, ready, certificate: Path | None, key: Path | None, opus: bool = False) -> None:
    """Use the stock hub's native TLS hook and the unmodified SDK echo delegate."""
    context = None
    if certificate and key:
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(certificate, key)
    brain = EchoBrain(require_pairing=True, loopback=True)
    hub = DeviceHub(
        DeviceStore(directory), brain, host=socket.gethostbyname("localhost"),
        port=0, path="/gadget", ssl_context=context, **({"enable_opus": True} if opus else {}),
    )
    brain.hub = hub
    await hub.start()
    ready.set_result(hub)
    stop = asyncio.Event()
    console = asyncio.create_task(_console(hub, brain, stop))
    try:
        await stop.wait()
    finally:
        console.cancel()
        await asyncio.gather(console, return_exceptions=True)
        await hub.stop()


async def main(ready_file: Path, certificate: Path | None, key: Path | None, opus: bool = False) -> None:
    with tempfile.TemporaryDirectory(prefix="gadget-integration-") as directory:
        ready = asyncio.get_running_loop().create_future()
        coroutine = (
            tls_server(Path(directory), ready, certificate, key, opus)
            if (certificate and key) or opus else
            serve(
                socket.gethostbyname("localhost"),
                0,
                "/gadget",
                Path(directory),
                require_pairing=True,
                loopback=True,
                token=None,
                ready=ready,
            )
        )
        task = asyncio.create_task(coroutine)
        try:
            hub = await asyncio.wait_for(asyncio.shield(ready), timeout=10)
            temporary = ready_file.with_suffix(".part")
            temporary.write_text(json.dumps({"port": hub.bound_port}), encoding="utf-8")
            temporary.replace(ready_file)
            await task
        finally:
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--ready-file", type=Path, required=True)
    parser.add_argument("--tls-cert", type=Path)
    parser.add_argument("--tls-key", type=Path)
    parser.add_argument("--opus", action="store_true", help="Requires the isolated patched SDK environment")
    args = parser.parse_args()
    if bool(args.tls_cert) != bool(args.tls_key):
        parser.error("TLS certificate and key must be supplied together")
    asyncio.run(main(args.ready_file, args.tls_cert, args.tls_key, args.opus))
