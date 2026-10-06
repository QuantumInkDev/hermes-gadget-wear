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


async def tls_server(directory: Path, ready, certificate: Path | None, key: Path | None, opus: bool = False, pets: bool = False, client_tts: bool = False) -> None:
    """Use the stock hub's native TLS hook and the unmodified SDK echo delegate."""
    context = None
    if certificate and key:
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(certificate, key)
    brain = EchoBrain(require_pairing=True, loopback=True)
    if client_tts:
        async def voice_hint(session): return "syntheticVoice"
        brain.voice_hint = voice_hint
    if pets:
        from PIL import Image, ImageDraw
        from hermes_gadget_plugin.pets import package
        sheet = directory / "sample.webp"
        image = Image.new("RGBA", (1536, 1872))
        draw = ImageDraw.Draw(image)
        for row in range(9):
            for column in range(4 if row == 3 else 6):
                x, y = column * 192 + 60, row * 208 + 70
                draw.rectangle((x, y, x + 71, y + 109), fill=(255, 186, 102, 255))
                draw.rectangle((x + 12, y + 25, x + 22, y + 35), fill=(20, 24, 20, 255))
                draw.rectangle((x + 48, y + 25, x + 58, y + 35), fill=(20, 24, 20, 255))
        image.save(sheet, "WEBP", lossless=True)
        async def sample_pet(session):
            return package(directory, sheet, "Sample robot", "sample-robot")
        brain.pet_package = sample_pet
    hub = DeviceHub(
        DeviceStore(directory), brain, host=socket.gethostbyname("localhost"),
        port=0, path="/gadget", ssl_context=context, **({"enable_opus": True} if opus else {}), **({"enable_pets": True} if pets else {}), **({"enable_client_tts": True} if client_tts else {}),
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


async def main(ready_file: Path, certificate: Path | None, key: Path | None, opus: bool = False, pets: bool = False, client_tts: bool = False) -> None:
    with tempfile.TemporaryDirectory(prefix="gadget-integration-") as directory:
        ready = asyncio.get_running_loop().create_future()
        coroutine = (
            tls_server(Path(directory), ready, certificate, key, opus, pets, client_tts)
            if (certificate and key) or opus or pets or client_tts else
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
    parser.add_argument("--pets", action="store_true", help="Original synthetic pet; isolated patched SDK only")
    parser.add_argument("--client-tts", action="store_true", help="Isolated patched SDK speech negotiation")
    args = parser.parse_args()
    if bool(args.tls_cert) != bool(args.tls_key):
        parser.error("TLS certificate and key must be supplied together")
    asyncio.run(main(args.ready_file, args.tls_cert, args.tls_key, args.opus, args.pets, args.client_tts))
