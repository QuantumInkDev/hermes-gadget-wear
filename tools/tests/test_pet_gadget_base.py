"""Preserving staging for a separate Gadget base; original synthetic fixtures only."""
import hashlib
import io
import json
from pathlib import Path
import random
import runpy
import sys

import pytest
from PIL import Image
from test_extend_pet import fixture
from test_pet_companion import inputs, load


def alternate_inputs(tmp_path):
    metadata, base, companion = inputs(tmp_path)
    raw = tmp_path / 'raw'; raw.mkdir(exist_ok=True)
    if companion.parent != raw:
        companion = companion.rename(raw / companion.name)
    alternate = tmp_path / 'prepared' / 'watch.png'; alternate.parent.mkdir()
    fixture(alternate, 1536, 1872, 60, [6] * 9)
    return metadata, base, companion, alternate


def detailed_sheet(path):
    image = Image.new('RGBA', (1536, 1872)); rng = random.Random(716)
    for row in range(9):
        for column in range(6):
            cell = Image.frombytes('RGB', (140, 150), rng.randbytes(140 * 150 * 3)).convert('RGBA')
            image.paste(cell, (column * 192 + 26, row * 208 + 30))
    image.save(path, 'PNG')
    assert 2 * 1024 * 1024 < path.stat().st_size < 8 * 1024 * 1024


def snapshots(paths):
    return {p: hashlib.sha256(p.read_bytes()).hexdigest() for p in paths}


def test_large_regular_is_preserved_and_registration_uses_alternate(tmp_path):
    tool = load()
    metadata, base, companion, alternate = alternate_inputs(tmp_path)
    nested = base.parent / 'desktop' / base.name; nested.parent.mkdir(); base = base.rename(nested)
    data = json.loads(metadata.read_text()); data['spritesheetPath'] = 'desktop/' + base.name
    metadata.write_text(json.dumps(data))
    detailed_sheet(base)
    original = snapshots((metadata, base, companion, alternate))
    with pytest.raises(ValueError, match='2 MiB'):
        tool.build(metadata, companion, tmp_path / 'legacy')
    output = tmp_path / 'bundle'
    # The new argument is trailing: all original positional arguments keep their meaning.
    receipt = tool.build(metadata, companion, output, None, None, alternate)
    proposed = json.loads((output / 'pet.json').read_text())
    assert proposed['spritesheetPath'] == data['spritesheetPath']
    assert proposed['gadgetBasePath'] == 'gadget-base.png'
    assert (output / proposed['spritesheetPath']).read_bytes() == base.read_bytes()
    assert (output / proposed['gadgetBasePath']).read_bytes() == alternate.read_bytes()
    assert receipt['sources']['gadget_base']['sha256'] == original[alternate]
    assert receipt['outputs']['desktop/base.webp']['sha256'] == original[base]
    assert receipt['outputs']['gadget-base.png']['sha256'] == original[alternate]
    assert set(receipt['outputs']) == {'pet.json', 'desktop/base.webp', 'gadget-base.png', 'gadget-sheet.png', 'contact-sheet.png'}
    assert all(frame['bbox'][3] - frame['bbox'][1] == 60 for frame in receipt['frames'])
    with Image.open(alternate) as image:
        stream = io.BytesIO(); image.save(stream, 'PNG')
    assert receipt['delivery'][0]['sha256'] == hashlib.sha256(stream.getvalue()).hexdigest()
    assert receipt['delivery'][0]['bytes'] <= 2 * 1024 * 1024
    assert snapshots(original) == original


@pytest.mark.parametrize('declared', ['watch.png', 'base.webp'])
def test_existing_field_without_explicit_argument_refuses_before_output(tmp_path, declared):
    tool = load(); metadata, base, companion, alternate = alternate_inputs(tmp_path)
    data = json.loads(metadata.read_text()); data['gadgetBasePath'] = declared
    metadata.write_text(json.dumps(data)); original = snapshots((metadata, base, companion, alternate))
    with pytest.raises(ValueError, match='gadget.base'):
        tool.build(metadata, companion, tmp_path / 'bundle')
    assert not (tmp_path / 'bundle').exists()
    assert snapshots(original) == original


def test_explicit_argument_replaces_stale_reference_with_copied_alternate(tmp_path):
    tool = load(); metadata, base, companion, alternate = alternate_inputs(tmp_path)
    data = json.loads(metadata.read_text()); data['gadgetBasePath'] = 'old/missing.png'
    metadata.write_text(json.dumps(data)); original = snapshots((metadata, base, companion, alternate))
    tool.build(metadata, companion, tmp_path / 'bundle', gadget_base=alternate)
    proposed = json.loads((tmp_path / 'bundle/pet.json').read_text())
    assert proposed['gadgetBasePath'] == 'gadget-base.png'
    assert proposed['spritesheetPath'] == data['spritesheetPath']
    assert snapshots(original) == original


@pytest.mark.parametrize('relative', ['gadget-base.png', 'GADGET-BASE.PNG', 'pet.json',
    'gadget-sheet.png', 'manifest.json', 'contact-sheet.png', 'gadget-base.png/nested.webp'])
def test_output_collisions_refuse_before_copying_or_writing(tmp_path, monkeypatch, relative):
    tool = load(); metadata, base, companion, alternate = alternate_inputs(tmp_path)
    # pet.json cannot also hold image bytes; the collision must be detected before decode.
    target = metadata.parent / relative
    if target != metadata:
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(base.read_bytes())
    data = json.loads(metadata.read_text()); data['spritesheetPath'] = relative
    metadata.write_text(json.dumps(data)); original = snapshots((metadata, base, companion, alternate))
    def no_decode(*args, **kwargs): pytest.fail('Collision reached image read')
    monkeypatch.setattr(tool._register, 'opened', no_decode)
    output = tmp_path / 'bundle'
    with pytest.raises(ValueError, match='collid|collision'):
        tool.build(metadata, companion, output, gadget_base=alternate)
    assert not output.exists() and snapshots(original) == original


@pytest.mark.parametrize('kind', ['metadata', 'companion', 'alternate', 'existing', 'symlink'])
def test_output_outside_every_source_directory_and_existing_targets(tmp_path, kind):
    tool = load(); metadata, base, companion, alternate = alternate_inputs(tmp_path)
    output = tmp_path / 'bundle'
    if kind in ('metadata', 'companion', 'alternate'):
        output = {'metadata': metadata, 'companion': companion, 'alternate': alternate}[kind].parent / 'bundle'
    elif kind == 'existing': output.mkdir(); (output / 'keep').write_bytes(b'preserve')
    else: output.symlink_to(tmp_path / 'missing', target_is_directory=True)
    original = snapshots((metadata, base, companion, alternate))
    with pytest.raises(ValueError): tool.build(metadata, companion, output, gadget_base=alternate)
    assert snapshots(original) == original
    if kind == 'existing': assert (output / 'keep').read_bytes() == b'preserve'
    elif kind == 'symlink': assert output.is_symlink()
    else: assert not output.exists()


@pytest.mark.parametrize('field', ['spritesheetPath', 'gadgetBasePath'])
@pytest.mark.parametrize('value', [None, False, [], '', '../escape.png', r'..\escape.png',
    '/escape.png', r'C:\escape.png', 'C:escape.png', r'\\host\escape.png',
    'x\x00.png', '\ud800.png', 'é' * 257])
def test_malformed_path_metadata_refuses_with_explicit_argument(tmp_path, field, value):
    tool = load(); metadata, base, companion, alternate = alternate_inputs(tmp_path)
    data = json.loads(metadata.read_text()); data[field] = value
    metadata.write_text(json.dumps(data))
    with pytest.raises(ValueError): tool.build(metadata, companion, tmp_path / 'bundle', gadget_base=alternate)
    assert not (tmp_path / 'bundle').exists()


@pytest.mark.parametrize('raw', ['[]', 'null', '{broken',
    '{"spritesheetPath":"base.webp","spritesheetPath":"other.webp"}'])
def test_malformed_metadata_objects_refuse_before_output(tmp_path, raw):
    tool = load(); metadata, base, companion, alternate = alternate_inputs(tmp_path)
    metadata.write_text(raw)
    with pytest.raises(ValueError): tool.build(metadata, companion, tmp_path / 'bundle', gadget_base=alternate)
    assert not (tmp_path / 'bundle').exists()


@pytest.mark.parametrize('kind', ['source-bytes', 'png-bytes', 'pixels', 'geometry', 'rgb', 'opaque', 'empty'])
def test_alternate_quotas_geometry_alpha_and_frames(tmp_path, kind):
    tool = load(); metadata, base, companion, alternate = alternate_inputs(tmp_path)
    if kind == 'source-bytes':
        with alternate.open('wb') as stream: stream.truncate(8 * 1024 * 1024 + 1)
    elif kind == 'png-bytes': detailed_sheet(alternate)
    else:
        mode, size, color = 'RGBA', (1536, 1872), (0, 0, 0, 0)
        if kind == 'pixels': size = (1536, 2288)
        elif kind == 'geometry': size = (1535, 1872)
        elif kind == 'rgb': mode, color = 'RGB', 'red'
        elif kind == 'opaque': color = 'red'
        Image.new(mode, size, color).save(alternate)
    original = snapshots((metadata, base, companion, alternate))
    with pytest.raises(ValueError): tool.build(metadata, companion, tmp_path / 'bundle', gadget_base=alternate)
    assert not (tmp_path / 'bundle').exists() and snapshots(original) == original


def test_output_metadata_quota_and_late_failure_preserve_all_inputs(tmp_path):
    tool = load(); metadata, base, companion, alternate = alternate_inputs(tmp_path)
    data = json.loads(metadata.read_text()); data['description'] = ''
    data['description'] = 'x' * (65536 - len(json.dumps(data).encode()))
    raw = json.dumps(data); assert len(raw.encode()) == 65536
    metadata.write_text(raw); original = snapshots((metadata, base, companion, alternate))
    with pytest.raises(ValueError, match='metadata|limit'):
        tool.build(metadata, companion, tmp_path / 'bundle', gadget_base=alternate)
    assert not (tmp_path / 'bundle').exists() and snapshots(original) == original


@pytest.mark.parametrize('kind', ['copy-corruption', 'alternate-corruption', 'write-failure', 'source-change'])
def test_late_failures_and_hash_rechecks_remove_only_owned_output(tmp_path, monkeypatch, kind):
    tool = load(); metadata, base, companion, alternate = alternate_inputs(tmp_path)
    output = tmp_path / 'bundle'; original = snapshots((metadata, base, companion, alternate))
    contact = tool.contact_sheet
    def interfere(base_image, sheet, target):
        contact(base_image, sheet, target)
        if kind == 'write-failure': raise OSError('injected output failure')
        path = {'copy-corruption': output / 'base.webp', 'alternate-corruption': output / 'gadget-base.png',
                'source-change': alternate}[kind]
        with path.open('ab') as stream: stream.write(b'changed')
    monkeypatch.setattr(tool, 'contact_sheet', interfere)
    with pytest.raises((ValueError, OSError)):
        tool.build(metadata, companion, output, gadget_base=alternate)
    assert not output.exists()
    assert snapshots(p for p in original if kind != 'source-change' or p != alternate) == {
        p: h for p, h in original.items() if kind != 'source-change' or p != alternate}


def test_cli_alternate_and_prepare_grid_argument_rules(tmp_path, monkeypatch, capsys):
    tool = load(); metadata, base, companion, alternate = alternate_inputs(tmp_path)
    path = str(Path(tool.__file__))
    monkeypatch.setattr(sys, 'argv', [path, '--metadata', str(metadata), '--companion', str(companion),
                                    '--output-dir', str(tmp_path / 'bundle'), '--gadget-base', str(alternate)])
    runpy.run_path(path, run_name='__main__')
    assert json.loads((tmp_path / 'bundle/pet.json').read_text())['gadgetBasePath'] == 'gadget-base.png'
    monkeypatch.setattr(sys, 'argv', [path, '--prepare-grid', str(companion), '--prepared-output',
                                    str(tmp_path / 'prepared.png'), '--gadget-base', str(alternate)])
    with pytest.raises(SystemExit) as error: runpy.run_path(path, run_name='__main__')
    assert error.value.code == 2
    assert '--prepare-grid requires only --prepared-output' in capsys.readouterr().err
    assert not (tmp_path / 'prepared.png').exists()


def test_no_field_api_retains_legacy_flat_layout_and_receipt(tmp_path):
    tool = load(); metadata, base, companion, alternate = alternate_inputs(tmp_path)
    nested = base.parent / 'desktop' / base.name; nested.parent.mkdir(); base = base.rename(nested)
    data = json.loads(metadata.read_text()); data['spritesheetPath'] = 'desktop/' + base.name
    metadata.write_text(json.dumps(data)); original = snapshots((metadata, base, companion))
    output = tmp_path / 'bundle'
    receipt = tool.build(metadata, companion, output, (88, 104, 104, 124), None)
    proposed = json.loads((output / 'pet.json').read_text())
    assert 'gadgetBasePath' not in proposed
    assert proposed['spritesheetPath'] == base.name
    assert set(receipt['sources']) == {'metadata', 'base', 'companion'}
    assert set(receipt['outputs']) == {'pet.json', 'base.webp', 'gadget-sheet.png', 'contact-sheet.png'}
    assert (output / base.name).read_bytes() == base.read_bytes()
    assert snapshots(original) == original
