import importlib.util
from pathlib import Path

import pytest
from PIL import Image, ImageDraw

spec = importlib.util.spec_from_file_location('extend_pet', Path(__file__).parents[1] / 'extend-pet.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def fixture(path, width, height, size, counts):
    image = Image.new('RGBA', (width, height))
    draw = ImageDraw.Draw(image)
    for row, count in enumerate(counts):
        for column in range(count):
            x = column * 192 + 96
            bottom = row * 208 + 180
            draw.rectangle((x - size // 4, bottom - size, x + size // 4 - 1, bottom - 1), fill=(255, 190, 90, 255))
    image.save(path)
    return image


def test_shared_registration_matches_base_and_preserves_inputs(tmp_path):
    base, source, output = [tmp_path / name for name in ('base.png', 'input.png', 'gadget-sheet.png')]
    fixture(base, 1536, 1872, 100, [6] * 9)
    fixture(source, 1152, 624, 50, [6, 3, 6])
    before = base.read_bytes(), source.read_bytes()
    metadata = module.extend(base, source, output)
    result = Image.open(output)
    for row, count in enumerate((6, 3, 6)):
        for column in range(count):
            assert module.bbox(result.crop((column * 192, row * 208, (column + 1) * 192, (row + 1) * 208))) == (72, 80, 120, 180)
    assert before == (base.read_bytes(), source.read_bytes())
    assert metadata['gadgetSheet']['rows'][1]['frames'] == 3
    assert module.bbox(result.crop((576, 208, 768, 416))) is None


def test_empty_clipped_and_drifting_rows_are_rejected(tmp_path):
    base, source = tmp_path / 'base.png', tmp_path / 'input.png'
    fixture(base, 1536, 1872, 100, [6] * 9)
    image = fixture(source, 1152, 624, 50, [6, 3, 6])
    image.paste((0, 0, 0, 0), (0, 0, 192, 208)); image.save(source)
    with pytest.raises(ValueError, match='Empty'): module.extend(base, source, tmp_path / 'out.png')
    image = fixture(source, 1152, 624, 50, [6, 3, 6])
    image.paste((0, 0, 0, 0), (0, 0, 192, 208))
    ImageDraw.Draw(image).rectangle((61, 100, 85, 149), fill=(255, 190, 90, 255)); image.save(source)
    with pytest.raises(ValueError, match='drifts'): module.extend(base, source, tmp_path / 'out.png')

    image = fixture(source, 1152, 624, 50, [6, 3, 6])
    for column in range(6):
        image.paste((0, 0, 0, 0), (column * 192, 0, (column + 1) * 192, 208))
        ImageDraw.Draw(image).rectangle((column * 192, 100, column * 192 + 24, 149), fill=(255, 190, 90, 255))
    image.save(source)
    with pytest.raises(ValueError, match='Clipped'): module.extend(base, source, tmp_path / 'out.png')


@pytest.mark.parametrize('kind', ['base', 'source', 'existing', 'symlink', 'hardlink'])
def test_callable_never_overwrites_existing_paths(tmp_path, kind):
    base, source = tmp_path / 'base.png', tmp_path / 'input.png'
    fixture(base, 1536, 1872, 100, [6] * 9)
    fixture(source, 1152, 624, 50, [6, 3, 6])
    output = tmp_path / 'out.png'
    if kind == 'base': output = base
    elif kind == 'source': output = source
    elif kind == 'symlink': output.symlink_to(base)
    elif kind == 'hardlink': output.hardlink_to(base)
    else: output.write_bytes(b'existing')
    before = base.read_bytes(), source.read_bytes(), output.read_bytes()
    with pytest.raises(ValueError, match='exist|separate'):
        module.extend(base, source, output)
    assert before == (base.read_bytes(), source.read_bytes(), output.read_bytes())


@pytest.mark.parametrize('width,rows', [(1536, 9), (1728, 8)])
def test_preserves_supported_base_taxonomies(tmp_path, width, rows):
    base, source, output = [tmp_path / name for name in ('base.png', 'in.png', 'out.png')]
    fixture(base, width, rows * 208, 100, [6] * rows)
    fixture(source, 1152, 624, 50, [6, 3, 6])
    before = base.read_bytes()
    module.extend(base, source, output)
    assert base.read_bytes() == before
    with Image.open(output) as result:
        assert result.size == (1152, 624)


def test_oversized_eleven_row_base_rejected_without_modification(tmp_path):
    base, source = tmp_path / 'base.png', tmp_path / 'in.png'
    fixture(base, 1536, 2288, 100, [6] * 11)
    fixture(source, 1152, 624, 50, [6, 3, 6])
    before = base.read_bytes()
    with pytest.raises(ValueError, match='bounded'):
        module.extend(base, source, tmp_path / 'out.png')
    assert base.read_bytes() == before
