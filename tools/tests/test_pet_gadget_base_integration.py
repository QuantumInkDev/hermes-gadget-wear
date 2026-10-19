"""Opt-in actual SDK / isolated Hermes reader proof; never imports installed Hermes."""
import asyncio
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import sys
import types

import pytest
from PIL import Image
from test_pet_gadget_base import alternate_inputs, detailed_sheet, snapshots
from test_pet_companion import load


def bound_module(monkeypatch, name, path, package=False):
    spec = importlib.util.spec_from_file_location(name, path,
            submodule_search_locations=[str(path.parent)] if package else None)
    module = importlib.util.module_from_spec(spec)
    monkeypatch.setitem(sys.modules, name, module)
    spec.loader.exec_module(module)
    assert Path(module.__file__).resolve() == path.resolve()
    return module


@pytest.mark.parametrize('capability', [None, True, 1])
def test_actual_reader_keeps_regular_while_staged_sdk_delivers_alternate(tmp_path, monkeypatch, capability):
    required = ('PET_TEST_SDK', 'PET_TEST_READER', 'PET_TEST_READER_SHA256', 'PET_TEST_CONSTANTS_SHA256')
    if any(not os.environ.get(key) for key in required):
        pytest.skip('Requires explicit isolated SDK/reader paths and pinned reader hashes')
    sdk, reader = Path(os.environ['PET_TEST_SDK']).resolve(), Path(os.environ['PET_TEST_READER']).resolve()
    # Set synthetic home before even loading the stdlib-only constants/reader.
    profile = tmp_path / 'profile'; profile.mkdir()
    monkeypatch.setenv('HERMES_HOME', str(profile))
    for filename, key in [('store.py', 'PET_TEST_READER_SHA256'), ('hermes_constants.py', 'PET_TEST_CONSTANTS_SHA256')]:
        assert hashlib.sha256((reader / filename).read_bytes()).hexdigest() == os.environ[key]
    constants = bound_module(monkeypatch, 'hermes_constants', reader / 'hermes_constants.py')
    store = bound_module(monkeypatch, '_task1_actual_pet_store', reader / 'store.py')
    bound_module(monkeypatch, 'hermes_gadget_plugin', sdk / 'plugin/__init__.py', package=True)
    from hermes_gadget_plugin import pets, protocol
    assert Path(pets.__file__).resolve() == sdk / 'plugin/pets.py'
    assert constants.get_hermes_home() == profile

    tool = load(); metadata, regular, companion, alternate = alternate_inputs(tmp_path)
    detailed_sheet(regular)
    original = snapshots((metadata, regular, companion, alternate))
    output = profile / 'pets' / 'synthetic'
    receipt = tool.build(metadata, companion, output, gadget_base=alternate,
                         overlay_rect=(88, 132, 104, 148))
    pet = store.load_pet('synthetic')
    assert pet.directory == output
    assert pet.spritesheet == output / regular.name
    assert pet.spritesheet.read_bytes() == regular.read_bytes()
    with Image.open(pet.spritesheet) as image: assert image.size == (1536, 1872)
    monkeypatch.setitem(sys.modules, 'agent.pet.store', store)
    # The real installed configuration loader is deliberately not imported.
    monkeypatch.setitem(sys.modules, 'hermes_cli.config', types.SimpleNamespace(
        load_config_readonly=lambda: {'display': {'pet': {'enabled': True, 'slug': 'synthetic'}}}))
    value = pets.active_package()
    assert value[0]['sheets'] == receipt['delivery']
    assert value[1][0] == pets._sheet(output / 'gadget-base.png', 'base')
    assert value[1][0][0]['sha256'] != hashlib.sha256(regular.read_bytes()).hexdigest()

    class Session:
        paired, closed = True, False
        caps = {'pet': {'asset_channel': 4, 'expression_overlays': capability}}
        def __init__(self): self.messages, self.frames = [], []
        async def send_json(self, data): self.messages.append(data)
        async def send_binary(self, data): self.frames.append(protocol.parse_binary(data, additional_channels=(4,)))
    session = Session()
    asyncio.run(pets.send_package(session, value))
    for stream, (descriptor, expected) in enumerate(value[1], 1):
        frames = [frame for frame in session.frames if frame.stream == stream]
        assert [frame.seq for frame in frames] == list(range(len(frames)))
        payload = b''.join(frame.payload for frame in frames)
        assert payload == expected and len(payload) == descriptor['bytes']
        assert hashlib.sha256(payload).hexdigest() == descriptor['sha256']
    assert [s['kind'] for s in session.messages[0]['sheets']] == ['base', 'companion']
    assert ('talking' in [r['name'] for r in session.messages[0]['sheets'][1]['rows']]) == (type(capability) is int and capability == 1)
    assert snapshots(original) == original
    assert store.load_pet('synthetic').spritesheet == pet.spritesheet
    proof = {'reader': store.__file__, 'reader_sha256': os.environ['PET_TEST_READER_SHA256'],
             'constants': constants.__file__, 'constants_sha256': os.environ['PET_TEST_CONSTANTS_SHA256'],
             'sdk': pets.__file__, 'pillow': Image.__file__, 'home': str(profile),
             'config': 'synthetic in-memory stub', 'regular_bytes': regular.stat().st_size,
             'regular_sha256': original[regular], 'delivery': receipt['delivery'],
             'binary_frames': len(session.frames), 'capability': capability}
    (tmp_path / 'reader-proof.json').write_text(json.dumps(proof, indent=2) + '\n')
