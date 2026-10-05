"""Launch the actual pinned SDK devserver with ephemeral local state for JVM tests."""

import argparse
import asyncio
import json
import socket
import tempfile
from pathlib import Path

from hermes_gadget.devserver import serve


async def main(ready_file: Path) -> None:
    with tempfile.TemporaryDirectory(prefix="gadget-integration-") as directory:
        ready = asyncio.get_running_loop().create_future()
        task = asyncio.create_task(
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
    asyncio.run(main(parser.parse_args().ready_file))
