"""Synthetic art only: packaging/provenance and registration acceptance boundaries."""
import importlib.util
import json
from pathlib import Path

import pytest
from PIL import Image, ImageDraw

from test_extend_pet import fixture

spec = importlib.util.spec_from_file_location('pet_companion', Path(__file__).parents[1] / 'pet-companion.py')
pipeline = importlib.util.module_from_spec(spec)


def load():
    spec.loader.exec_module(pipeline)
    return pipeline


def inputs(tmp_path):
    pet = tmp_path / 'original'; pet.mkdir()
    base = pet / 'base.webp'
    fixture(base, 1536, 1872, 100, [6] * 9)
    metadata = pet / 'pet.json'
    metadata.write_text(json.dumps({'id': 'synthetic', 'displayName': 'Synthetic', 'spritesheetPath': base.name}))
    source = tmp_path / 'raw' / 'expressions.png'
    source.parent.mkdir()
    fixture(source, 1152, 624, 100, [6, 3, 6])
    image = Image.open(source)
    draw = ImageDraw.Draw(image)
    for column, height in enumerate([1, 3, 7]):
        draw.rectangle((column * 192 + 92, 208 + 110, column * 192 + 99, 208 + 110 + height), fill='black')
    image.save(source)
    return metadata, base, source


def test_source_bound_bundle_and_exact_talking_body(tmp_path):
    tool = load()
    metadata, base, source = inputs(tmp_path)
    before = [p.read_bytes() for p in (metadata, base, source)]
    output = tmp_path / 'bundle'
    receipt = tool.build(metadata, source, output, (88, 104, 104, 124))
    assert receipt['schema'] == 1
    assert receipt['talking']['outside_mouth_changed_pixels'] == [0, 0]
    assert receipt['talking']['mouth_changed_pixels'][1] > 0
    assert receipt['sources']['base']['sha256'] == tool.sha256(base)
    assert receipt['outputs']['base.webp']['sha256'] == tool.sha256(base)
    assert (output / 'base.webp').read_bytes() == base.read_bytes()
    assert [p.read_bytes() for p in (metadata, base, source)] == before
    proposed = json.loads((output / 'pet.json').read_text())
    assert proposed['gadgetSheet']['rows'] == [
        {'name': 'listening', 'index': 0, 'frames': 6},
        {'name': 'talking', 'index': 1, 'frames': 3},
        {'name': 'sleeping', 'index': 2, 'frames': 6}]
    result = Image.open(output / 'gadget-sheet.png')
    assert result.getchannel('A').crop((576, 208, 1152, 416)).getbbox() is None
    assert len(receipt['frames']) == 15
    assert all(frame['bbox'] == [71, 80, 121, 180] for frame in receipt['frames'])
    assert (output / 'contact-sheet.png').is_file()
    assert json.loads((output / 'manifest.json').read_text()) == receipt


@pytest.mark.parametrize('kind', ['traversal', 'symlink', 'existing', 'inside_source', 'base_name_collision'])
def test_rejects_path_escape_and_output_collisions(tmp_path, kind):
    tool = load()
    metadata, base, source = inputs(tmp_path)
    output = tmp_path / 'bundle'
    data = json.loads(metadata.read_text())
    if kind == 'traversal': data['spritesheetPath'] = '../expressions.png'
    elif kind == 'symlink':
        (metadata.parent / 'escape.png').symlink_to(source)
        data['spritesheetPath'] = 'escape.png'
    elif kind == 'existing': output.mkdir()
    elif kind == 'inside_source': output = metadata.parent / 'new-art'
    elif kind == 'base_name_collision': data['spritesheetPath'] = 'gadget-sheet.png'; base.rename(metadata.parent / 'gadget-sheet.png')
    metadata.write_text(json.dumps(data))
    before = metadata.read_bytes(), source.read_bytes()
    with pytest.raises(ValueError): tool.build(metadata, source, output)
    assert before == (metadata.read_bytes(), source.read_bytes())


def test_mouth_gate_rejects_body_motion_and_cleans_failed_staging(tmp_path):
    tool = load()
    metadata, base, source = inputs(tmp_path)
    image = Image.open(source)
    ImageDraw.Draw(image).point((192 + 95, 208 + 170), fill='white')
    image.save(source)
    output = tmp_path / 'bundle'
    with pytest.raises(ValueError, match='body'):
        tool.build(metadata, source, output, (88, 104, 104, 124))
    assert not output.exists()


def test_without_mouth_region_explicitly_records_unchecked(tmp_path):
    tool = load()
    metadata, base, source = inputs(tmp_path)
    receipt = tool.build(metadata, source, tmp_path / 'bundle')
    assert receipt['talking'] == {'status': 'unchecked', 'reason': 'No reviewed mouth rectangle supplied'}


def test_three_talking_shapes_are_required_when_mouth_gate_enabled(tmp_path):
    tool = load()
    metadata, base, source = inputs(tmp_path)
    image = Image.open(source)
    first = image.crop((0, 208, 192, 416))
    image.paste(first, (192, 208)); image.paste(first, (384, 208)); image.save(source)
    with pytest.raises(ValueError, match='distinct'):
        tool.build(metadata, source, tmp_path / 'bundle', (88, 104, 104, 124))


def test_delivery_manifest_binds_actual_alpha_preserving_png_payload(tmp_path):
    import hashlib
    import io
    tool = load()
    metadata, base, source = inputs(tmp_path)
    output = tmp_path / 'bundle'
    receipt = tool.build(metadata, source, output)
    for entry, path in zip(receipt['delivery'], [output / 'base.webp', output / 'gadget-sheet.png']):
        with Image.open(path) as image:
            payload = io.BytesIO(); image.convert('RGBA').save(payload, 'PNG')
        assert entry['sha256'] == hashlib.sha256(payload.getvalue()).hexdigest()
        assert entry['bytes'] == len(payload.getvalue())
        assert entry['bytes'] <= 2 * 1024 * 1024
    assert [entry['kind'] for entry in receipt['delivery']] == ['base', 'companion']


def test_opaque_base_rejected_by_delivery_contract(tmp_path):
    tool = load()
    metadata, base, source = inputs(tmp_path)
    Image.new('RGBA', (1536, 1872), 'red').save(base, 'PNG')
    with pytest.raises(ValueError, match='Opaque'):
        tool.build(metadata, source, tmp_path / 'bundle')


def test_grid_preparation_preserves_alpha_aspect_and_raw_input(tmp_path):
    tool = load()
    source = tmp_path / 'raw.png'
    image = Image.new('RGBA', (1704, 923))
    draw = ImageDraw.Draw(image)
    for row, (_, count) in enumerate(tool.COUNTS):
        for column in range(count):
            x, y = column * 284 + 100, round(row * 923 / 3) + 50
            draw.rectangle((x, y, x + 79, y + 199), fill=(190, 80, 20, 230))
    image.save(source)
    before = source.read_bytes()
    output = tmp_path / 'prepared.png'
    tool.prepare_grid(source, output)
    with Image.open(output) as prepared:
        assert prepared.size == (1152, 624)
        assert prepared.getchannel('A').getextrema() == (0, 230)
        assert prepared.getchannel('A').crop((576, 208, 1152, 416)).getbbox() is None
        bounds = tool._register.bbox(prepared.crop((0, 0, 192, 208)))
        assert abs((bounds[2] - bounds[0]) / (bounds[3] - bounds[1]) - 0.4) < .015
    assert source.read_bytes() == before
    with pytest.raises(ValueError, match='exists'):
        tool.prepare_grid(source, output)


@pytest.mark.parametrize('oversized', ['base', 'companion'])
def test_build_checks_all_art_sizes_before_hashing_or_bulk_reads(tmp_path, monkeypatch, oversized):
    tool = load()
    metadata, base, companion = inputs(tmp_path)
    target = base if oversized == 'base' else companion
    # Sparse invalid input exercises the boundary without allocating its contents.
    with target.open('wb') as stream:
        stream.truncate(8 * 1024 * 1024 + 1)
    original_hash = tool.sha256
    hashed = []
    def observed_hash(path, *args, **kwargs):
        hashed.append(Path(path))
        return original_hash(path, *args, **kwargs)
    original_read = Path.read_bytes
    def reject_bulk_read(path):
        if path == target:
            pytest.fail('Oversized source reached bulk read before the size gate')
        return original_read(path)
    monkeypatch.setattr(tool, 'sha256', observed_hash)
    monkeypatch.setattr(Path, 'read_bytes', reject_bulk_read)
    output = tmp_path / 'bundle'
    with pytest.raises(ValueError, match='limit'):
        tool.build(metadata, companion, output)
    assert not hashed
    assert not output.exists()


def test_hash_streams_valid_input_and_rejects_oversize_before_open(tmp_path, monkeypatch):
    import hashlib
    tool = load()
    source = tmp_path / 'source.bin'
    source.write_bytes(b'synthetic' * 20000)
    expected = hashlib.sha256(source.read_bytes()).hexdigest()
    def no_bulk_read(path):
        pytest.fail('Hashing must stream bounded chunks, not call read_bytes')
    monkeypatch.setattr(Path, 'read_bytes', no_bulk_read)
    assert tool.sha256(source) == expected
    with source.open('wb') as stream:
        stream.truncate(8 * 1024 * 1024 + 1)
    original_open = Path.open
    def reject_source_open(path, *args, **kwargs):
        if path == source:
            pytest.fail('Oversized source was opened before the size gate')
        return original_open(path, *args, **kwargs)
    monkeypatch.setattr(Path, 'open', reject_source_open)
    with pytest.raises(ValueError, match='limit'):
        tool.sha256(source)


@pytest.mark.parametrize('edge', ['left', 'top', 'right', 'bottom'])
def test_raw_cell_boundary_clipping_rejected_before_padding(tmp_path, edge):
    tool = load()
    source, output = tmp_path / 'clipped-raw.png', tmp_path / 'prepared.png'
    # Half-height source cells would be vertically padded into the 208px target,
    # hiding top/bottom cuts from extend() if preparation did not inspect raw alpha.
    image = Image.new('RGBA', (1152, 312))
    draw = ImageDraw.Draw(image)
    for row, (_, count) in enumerate(tool.COUNTS):
        for column in range(count):
            left, top, right, bottom = 71, 20, 120, 69
            if edge == 'left': left, right = 0, 49
            elif edge == 'top': top, bottom = 0, 49
            elif edge == 'right': left, right = 142, 191
            elif edge == 'bottom': top, bottom = 54, 103
            draw.rectangle((column * 192 + left, row * 104 + top,
                            column * 192 + right, row * 104 + bottom), fill=(190, 80, 20, 230))
    image.save(source)
    original = source.read_bytes()
    with pytest.raises(ValueError, match='Clipped raw'):
        tool.prepare_grid(source, output)
    assert source.read_bytes() == original
    assert not output.exists()


def test_overlay_staging_keeps_raw_body_difference_honest_and_sources_unchanged(tmp_path):
    tool = load()
    metadata, base, source = inputs(tmp_path)
    image = Image.open(source)
    ImageDraw.Draw(image).point((192 + 95, 208 + 170), fill='white')
    image.save(source)
    before = [p.read_bytes() for p in (metadata, base, source)]
    receipt = tool.build(metadata, source, tmp_path / 'overlay', overlay_rect=(88, 104, 104, 124))
    talking = receipt['talking']
    assert talking['status'] == 'overlay_region_validated'
    assert talking['source_whole_body_match'] == 'failed'
    assert talking['outside_mouth_changed_pixels'][0] > 0
    assert talking['requires_expression_overlays'] == 1
    assert talking['reviewed_region'] == [88, 104, 104, 124]
    assert receipt['acceptance']['sdk_overlay_negotiation'] == 'unproven'
    proposed = json.loads((tmp_path / 'overlay' / 'pet.json').read_text())
    assert proposed['gadgetSheet']['rows'][1]['overlay'] == {'anchor_frame': 0, 'rect': [88, 104, 104, 124]}
    assert receipt['delivery'][1]['rows'][1]['overlay'] == proposed['gadgetSheet']['rows'][1]['overlay']
    assert before == [p.read_bytes() for p in (metadata, base, source)]


@pytest.mark.parametrize('rect', [(True, 0, 30, 30), (0, 0, 97, 104), (0, 0, 96, 105), (0, 0, 0, 30), (0, 0, 192, 208), (80, 100, 193, 124)])
def test_overlay_staging_rejects_invalid_regions_and_conflicting_modes(tmp_path, rect):
    tool = load()
    metadata, base, source = inputs(tmp_path)
    with pytest.raises(ValueError): tool.build(metadata, source, tmp_path / 'invalid', overlay_rect=rect)
    assert not (tmp_path / 'invalid').exists()
    with pytest.raises(ValueError): tool.build(metadata, source, tmp_path / 'conflicting', mouth_box=(88, 104, 104, 124), overlay_rect=(88, 104, 104, 124))
