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
    source = tmp_path / 'expressions.png'
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
