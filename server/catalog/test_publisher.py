import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
import threading
import urllib.request
import urllib.error

from PIL import Image

SPEC = importlib.util.spec_from_file_location('publisher', Path(__file__).with_name('publisher.py'))


class PublisherTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.publisher = importlib.util.module_from_spec(SPEC)
        SPEC.loader.exec_module(self.publisher)
        self.home = self.root / 'profile'
        self.home.mkdir()
        Image.new('RGB', (900, 600), 'orange').save(self.home / 'avatar.jpg', exif=b'')
        self.mapping = self.root / 'mapping.json'
        self.mapping.write_text(json.dumps({'profiles': [{'home': str(self.home), 'name': 'Agent', 'gadget_url': 'wss://box.example.invalid/gadget', 'avatar': 'avatar.jpg'}]}))

    def tearDown(self):
        self.tmp.cleanup()

    def test_projection_persists_identity_and_strips_private_data(self):
        first = self.publisher.project(self.mapping, self.root / 'state')
        second = self.publisher.project(self.mapping, self.root / 'state')
        self.assertEqual(first, second)
        text = json.dumps(first)
        self.assertNotIn(str(self.home), text)
        self.assertNotIn('device_key', text)
        record = first['profiles'][0]
        asset = self.root / 'state' / record['portrait']['path'].lstrip('/')
        data = asset.read_bytes()
        self.assertEqual(hashlib.sha256(data).hexdigest(), record['portrait']['sha256'])
        with Image.open(io.BytesIO(data)) as image:
            self.assertEqual((256, 171), image.size)
            self.assertFalse(image.getexif())
        mapping = json.loads(self.mapping.read_text())
        mapping['profiles'][0]['name'] = 'Renamed'
        self.mapping.write_text(json.dumps(mapping))
        self.assertEqual(record['id'], self.publisher.project(self.mapping, self.root / 'state')['profiles'][0]['id'])

    def test_missing_endpoint_is_explicit_and_inputs_are_unchanged(self):
        mapping = json.loads(self.mapping.read_text())
        mapping['profiles'][0].pop('gadget_url')
        self.mapping.write_text(json.dumps(mapping))
        before = self.mapping.read_bytes()
        result = self.publisher.project(self.mapping, self.root / 'state')
        self.assertIsNone(result['profiles'][0]['gadget_url'])
        self.assertEqual(before, self.mapping.read_bytes())

    def test_rejects_external_art_and_insecure_gadget_urls(self):
        for field, value in [('avatar', '../outside.jpg'), ('gadget_url', 'ws://box.example.invalid/gadget')]:
            mapping = json.loads(self.mapping.read_text())
            mapping['profiles'][0][field] = value
            self.mapping.write_text(json.dumps(mapping))
            with self.assertRaises(ValueError):
                self.publisher.project(self.mapping, self.root / 'state')
            mapping['profiles'][0][field] = 'avatar.jpg' if field == 'avatar' else 'wss://box.example.invalid/gadget'
            self.mapping.write_text(json.dumps(mapping))

    def test_authenticated_get_has_no_write_or_arbitrary_file_routes(self):
        server = self.publisher.create_server(('127.0.0.1', 0), self.mapping, self.root / 'state', 'synthetic-only-token')
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        base = f'http://127.0.0.1:{server.server_port}'
        try:
            for path in ['/catalog/v1', '/assets/../mapping.json']:
                with self.assertRaises(urllib.error.HTTPError) as caught:
                    urllib.request.urlopen(base + path, timeout=2)
                self.assertEqual(401, caught.exception.code)
                caught.exception.close()
            request = urllib.request.Request(base + '/catalog/v1', headers={'Authorization': 'Bearer synthetic-only-token'})
            with urllib.request.urlopen(request, timeout=2) as response:
                catalog = json.load(response)
            asset = catalog['profiles'][0]['portrait']
            request = urllib.request.Request(base + asset['path'], headers={'Authorization': 'Bearer synthetic-only-token'})
            with urllib.request.urlopen(request, timeout=2) as response:
                self.assertEqual(asset['bytes'], len(response.read()))
            for method, path, expected in [('POST', '/catalog/v1', 405), ('GET', '/assets/../mapping.json', 404), ('GET', '/catalog/v1?secret=x', 404)]:
                request = urllib.request.Request(base + path, method=method, headers={'Authorization': 'Bearer synthetic-only-token'})
                with self.assertRaises(urllib.error.HTTPError) as caught:
                    urllib.request.urlopen(request, timeout=2)
                self.assertEqual(expected, caught.exception.code)
                caught.exception.close()
        finally:
            server.shutdown()
            server.server_close()
            worker.join(2)

    def test_orientation_is_applied_and_all_exif_is_removed(self):
        image = Image.new('RGB', (900, 600), 'blue')
        exif = Image.Exif()
        exif[274] = 6
        exif[315] = 'private portrait author'
        image.save(self.home / 'avatar.jpg', exif=exif)
        result = self.publisher.project(self.mapping, self.root / 'state')
        asset = self.root / 'state' / result['profiles'][0]['portrait']['path'].lstrip('/')
        with Image.open(asset) as portrait:
            self.assertEqual((171, 256), portrait.size)
            self.assertFalse(portrait.getexif())
