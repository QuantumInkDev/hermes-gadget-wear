"""Isolated read-only Hermes watch catalog. Never opens a Gadget session."""
from __future__ import annotations

import argparse
import hashlib
import hmac
from http.server import BaseHTTPRequestHandler, HTTPServer
from socketserver import ThreadingMixIn
import threading
import io
import json
import os
from pathlib import Path
import re
import ssl
import tempfile
from urllib.parse import urlsplit
import uuid

from PIL import Image, ImageOps

MAX_BYTES = 65536
MAX_PROFILES = 32
MAX_PORTRAIT = 262144
Image.MAX_IMAGE_PIXELS = 16_000_000


def _atomic(path: Path, data: bytes):
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    handle, temporary = tempfile.mkstemp(dir=path.parent)
    try:
        with os.fdopen(handle, 'wb') as output:
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def _read(path: Path, limit: int) -> bytes:
    with path.open('rb') as stream:
        data = stream.read(limit + 1)
    if len(data) > limit:
        raise ValueError('Input exceeds size limit')
    return data


def _name(value):
    if not isinstance(value, str) or not value.strip() or len(value) > 32 or any(ord(c) < 32 or ord(c) == 127 for c in value):
        raise ValueError('Name must be 1-32 characters')
    return value


def _url(value):
    if value is None:
        return None
    parts = urlsplit(value)
    if len(value) > 2048 or parts.scheme != 'wss' or not parts.hostname or parts.username or parts.password or parts.query or parts.fragment:
        raise ValueError('Explicit credential-free wss URL required')
    if parts.port is not None and not 1 <= parts.port <= 65535:
        raise ValueError('Invalid Gadget port')
    # Require canonical mappings; do not silently reinterpret an approved URL.
    if parts.hostname != parts.netloc.split(':')[0] and not parts.hostname.count(':'):
        raise ValueError('Use canonical lowercase hostname')
    if not parts.path or parts.port == 443 or '/./' in parts.path or '/../' in parts.path:
        raise ValueError('Use a canonical Gadget URL')
    return value


def project(mapping_file: Path, state: Path):
    mapping = json.loads(_read(mapping_file, MAX_BYTES))
    records = mapping['profiles']
    if not isinstance(records, list) or len(records) > MAX_PROFILES:
        raise ValueError('At most 32 published profiles')
    state.mkdir(parents=True, exist_ok=True, mode=0o700)
    identity_file = state / 'identities.json'
    identities = json.loads(_read(identity_file, MAX_BYTES)) if identity_file.exists() else {}
    profiles, identifiers, urls, homes = [], set(), set(), set()
    for record in records:
        home = Path(record['home']).expanduser().resolve(strict=True)
        if not home.is_dir() or str(home) in homes:
            raise ValueError('Distinct existing profile homes required')
        homes.add(str(home))
        stable_id = record.get('id') or identities.get(str(home)) or str(uuid.uuid4())
        if not re.fullmatch(r'[A-Za-z0-9_-]{1,64}', stable_id) or stable_id in identifiers:
            raise ValueError('Distinct bounded profile identities required')
        identifiers.add(stable_id)
        identities[str(home)] = stable_id
        url = _url(record.get('gadget_url'))
        if url is not None:
            if url in urls:
                raise ValueError('Distinct Gadget URLs required')
            urls.add(url)
        profile = {'id': stable_id, 'name': _name(record['name']), 'gadget_url': url}
        avatar = record.get('avatar')
        if avatar:
            source = (home / avatar).resolve()
            if not source.is_relative_to(home):
                raise ValueError('Portrait must be inside the mapped profile home')
            raw = _read(source, 8 * 1024 * 1024)
            with Image.open(io.BytesIO(raw)) as image:
                if image.width * image.height > 16_000_000:
                    raise ValueError('Portrait exceeds pixel limit')
                image = ImageOps.exif_transpose(image).convert('RGBA')
                image.thumbnail((256, 256), Image.Resampling.LANCZOS)
                output = io.BytesIO()
                image.save(output, format='PNG')
            data = output.getvalue()
            if len(data) > MAX_PORTRAIT:
                raise ValueError('Normalized portrait exceeds 256 KiB')
            digest = hashlib.sha256(data).hexdigest()
            path = f'/assets/{digest}.png'
            _atomic(state / path.lstrip('/'), data)
            profile['portrait'] = {'sha256': digest, 'bytes': len(data), 'path': path}
        profiles.append(profile)
    result = {'version': 1, 'profiles': profiles}
    if len(json.dumps(result).encode()) > MAX_BYTES:
        raise ValueError('Catalog exceeds 64 KiB')
    _atomic(identity_file, json.dumps(identities).encode())
    # Assets outside the current projection are never served. Bound on-disk retention too.
    keep = {p['portrait']['sha256'] + '.png' for p in profiles if 'portrait' in p}
    assets = state / 'assets'
    if assets.exists():
        for file in assets.glob('*.png'):
            if file.name not in keep:
                file.unlink()
    return result


class CatalogServer(ThreadingMixIn, HTTPServer):
    daemon_threads = True
    block_on_close = False

    def __init__(self, address, handler, tls_context=None):
        self.tls_context = tls_context
        self.slots = threading.BoundedSemaphore(4)
        super().__init__(address, handler)

    def get_request(self):
        connection, address = super().get_request()
        connection.settimeout(8)
        return connection, address

    def process_request(self, request, address):
        if not self.slots.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, address)
        except Exception:
            self.slots.release()
            raise

    def process_request_thread(self, request, address):
        try:
            if self.tls_context:
                request = self.tls_context.wrap_socket(request, server_side=True, do_handshake_on_connect=False)
                request.settimeout(8)
                request.do_handshake()
            super().process_request_thread(request, address)
        except OSError:
            self.shutdown_request(request)
        finally:
            self.slots.release()

    def handle_error(self, *_):
        pass  # No sensitive request/connection diagnostics.


def create_server(address, mapping: Path, state: Path, credential: str, tls_context=None):
    if not 1 <= len(credential) <= 2048 or any(not 33 <= ord(c) <= 126 for c in credential):
        raise ValueError('Catalog credential must be printable without spaces')

    projection_lock = threading.Lock()

    class Handler(BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.0'

        def setup(self):
            super().setup()
            self.connection.settimeout(8)

        def log_message(self, *_):
            pass  # Never log credentials, personal addresses or asset paths.

        def _reply(self, status, data=b'', mime='application/json'):
            self.send_response(status)
            self.send_header('Content-Type', mime)
            self.send_header('Content-Length', str(len(data)))
            self.send_header('Cache-Control', 'private, no-store')
            self.send_header('X-Content-Type-Options', 'nosniff')
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self):
            if not hmac.compare_digest(self.headers.get('Authorization', '').encode(), ('Bearer ' + credential).encode()):
                self._reply(401)
                return
            try:
                with projection_lock:
                    catalog = project(mapping, state)
                    asset_data = _read(state / self.path.lstrip('/'), MAX_PORTRAIT) if self.path in {p['portrait']['path'] for p in catalog['profiles'] if 'portrait' in p} else None
                if self.path == '/catalog/v1':
                    self._reply(200, json.dumps(catalog, separators=(',', ':')).encode())
                    return
                allowed = {p['portrait']['path'] for p in catalog['profiles'] if 'portrait' in p}
                if self.path in allowed:
                    self._reply(200, asset_data, 'image/png')
                else:
                    self._reply(404)
            except (ValueError, OSError, KeyError):
                self._reply(503)

        def do_POST(self):
            self._reply(405)
        do_PUT = do_POST
        do_DELETE = do_POST
        do_PATCH = do_POST

    server = CatalogServer(address, Handler, tls_context)
    server.timeout = 8
    return server


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mapping', type=Path, required=True)
    parser.add_argument('--state', type=Path, required=True)
    parser.add_argument('--credential-file', type=Path, required=True)
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=8766)
    parser.add_argument('--tls-cert', type=Path)
    parser.add_argument('--tls-key', type=Path)
    parser.add_argument('--export', type=Path)
    args = parser.parse_args()
    if args.export:
        _atomic(args.export, json.dumps(project(args.mapping, args.state)).encode())
        return
    if bool(args.tls_cert) != bool(args.tls_key):
        parser.error('Specify both TLS certificate and key')
    if not args.tls_cert and args.host not in ('127.0.0.1', '::1', 'localhost'):
        parser.error('Non-loopback listeners require TLS; loopback requires an HTTPS reverse proxy')
    context = None
    if args.tls_cert:
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(args.tls_cert, args.tls_key)
    server = create_server((args.host, args.port), args.mapping, args.state, _read(args.credential_file, 2049).decode().strip(), context)
    try:
        server.serve_forever()
    finally:
        server.server_close()


if __name__ == '__main__':
    main()
