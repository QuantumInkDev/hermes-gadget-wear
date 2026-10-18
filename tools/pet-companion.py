"""Stage a companion bundle and review evidence without editing a profile or input art."""
import argparse
import hashlib
import importlib.util
import io
import json
import shutil
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw

_SPEC = importlib.util.spec_from_file_location('extend_pet', Path(__file__).with_name('extend-pet.py'))
_register = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(_register)
COUNTS = (('listening', 6), ('talking', 3), ('sleeping', 6))
RESERVED = {'pet.json', 'gadget-sheet.png', 'manifest.json', 'contact-sheet.png'}


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def record(path):
    return {'path': str(path.resolve()), 'sha256': sha256(path), 'bytes': path.stat().st_size}


def _within(child, parent):
    return child == parent or parent in child.parents


def _changed(first, second):
    # Include alpha changes; RGBA.getbbox alone can miss RGB-only changes.
    difference = ImageChops.difference(first, second)
    channels = difference.split()
    mask = channels[0]
    for channel in channels[1:]:
        mask = ImageChops.lighter(mask, channel)
    return sum(mask.histogram()[1:])


def prepare_grid(source_path: Path, output: Path):
    """Pack a reviewed six-column/three-row generated grid; no painted pixel edits."""
    source_path, output = Path(source_path), Path(output)
    if output.exists() or output.is_symlink():
        raise ValueError('Output already exists')
    source = _register.opened(source_path)
    if source.width < 6 or source.height < 3:
        raise ValueError('Grid requires six columns and three rows')
    scale = min(192 / ((source.width + 5) // 6), 208 / ((source.height + 2) // 3))
    result = Image.new('RGBA', (1152, 624))
    for row, (_, count) in enumerate(COUNTS):
        for column in range(count):
            box = (round(column * source.width / 6), round(row * source.height / 3),
                   round((column + 1) * source.width / 6), round((row + 1) * source.height / 3))
            cell = source.crop(box)
            scaled = cell.resize((max(1, round(cell.width * scale)), max(1, round(cell.height * scale))), Image.Resampling.NEAREST)
            x, y = (192 - scaled.width) // 2, (208 - scaled.height) // 2
            result.paste(scaled, (column * 192 + x, row * 208 + y))
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open('xb') as stream:
        result.save(stream, 'PNG')
    return {'source': record(source_path), 'output': record(output), 'scale': scale,
            'grid': [6, 3], 'operation': 'equal-grid extraction, shared nearest-neighbour scale, blank unused talking cells'}


def talking_metrics(sheet, mouth_box):
    if mouth_box is None:
        return {'status': 'unchecked', 'reason': 'No reviewed mouth rectangle supplied'}
    left, top, right, bottom = mouth_box
    if not (0 <= left < right <= 192 and 0 <= top < bottom <= 208):
        raise ValueError('Mouth rectangle must be inside one 192x208 cell')
    frames = [sheet.crop((column * 192, 208, (column + 1) * 192, 416)) for column in range(3)]
    outside, inside = [], []
    anchor = frames[0]
    for frame in frames[1:]:
        inside.append(_changed(anchor.crop(mouth_box), frame.crop(mouth_box)))
        a, b = anchor.copy(), frame.copy()
        a.paste((0, 0, 0, 0), mouth_box); b.paste((0, 0, 0, 0), mouth_box)
        outside.append(_changed(a, b))
    if any(outside):
        raise ValueError('Talking body changes outside the reviewed mouth rectangle')
    if not all(inside) or _changed(frames[1].crop(mouth_box), frames[2].crop(mouth_box)) == 0:
        raise ValueError('Talking requires three distinct mouth shapes')
    return {'status': 'passed', 'mouth_box': list(mouth_box),
            'outside_mouth_changed_pixels': outside, 'mouth_changed_pixels': inside}


def delivery_sheet(image, kind):
    """Mirror the existing SDK's PNG/taxonomy limits for a staging preflight."""
    current = ('idle', 'running-right', 'running-left', 'waving', 'jumping', 'failed', 'waiting', 'running', 'review')
    legacy = ('idle', 'wave', 'run', 'failed', 'review', 'jump')
    states = tuple((name, 6) for name in (current if image.height // 208 >= 9 else legacy)) if kind == 'base' else COUNTS
    occupied = []
    for row, (name, count) in enumerate(states):
        frames = 0
        for column in range(count):
            alpha = image.getchannel('A').crop((column * 192, row * 208, (column + 1) * 192, (row + 1) * 208))
            if not alpha.point(lambda value: 255 if value > 16 else 0).getbbox():
                break
            if alpha.getextrema()[0] == 255:
                raise ValueError('Opaque pet frame is not deliverable')
            frames += 1
        if frames:
            occupied.append({'name': name, 'index': row, 'frames': frames})
    if not occupied or (kind == 'base' and occupied[0]['name'] != 'idle'):
        raise ValueError('Empty delivery sheet')
    data = io.BytesIO()
    image.save(data, 'PNG')
    payload = data.getvalue()
    if len(payload) > 2 * 1024 * 1024:
        raise ValueError('Delivery PNG exceeds 2 MiB limit')
    return {'kind': kind, 'width': image.width, 'height': image.height,
            'bytes': len(payload), 'sha256': hashlib.sha256(payload).hexdigest(), 'rows': occupied}


def contact_sheet(base, sheet, output):
    preview = Image.new('RGB', (1152, 4 * 236), '#24242b')
    draw = ImageDraw.Draw(preview)
    for row, (name, count) in enumerate((('base idle', 6),) + COUNTS):
        draw.text((8, row * 236 + 5), name, fill='white')
        atlas, atlas_row = (base, 0) if row == 0 else (sheet, row - 1)
        for column in range(count):
            cell = atlas.crop((column * 192, atlas_row * 208, (column + 1) * 192, (atlas_row + 1) * 208))
            preview.paste(cell, (column * 192, row * 236 + 24), cell)
    preview.save(output)


def build(metadata_path: Path, companion_path: Path, output_dir: Path, mouth_box=None):
    metadata_path, companion_path, output_dir = map(Path, (metadata_path, companion_path, output_dir))
    directory = metadata_path.resolve().parent
    if metadata_path.stat().st_size > 65536:
        raise ValueError('Metadata exceeds 65536-byte limit')
    metadata = json.loads(metadata_path.read_text())
    relative = Path(metadata['spritesheetPath'])
    if relative.is_absolute() or '..' in relative.parts:
        raise ValueError('Base path must be confined to the pet directory')
    base = (directory / relative).resolve()
    if not _within(base, directory) or not base.is_file():
        raise ValueError('Base path escapes pet directory or is missing')
    if base.name in RESERVED:
        raise ValueError('Base filename collides with staging output')
    destination = output_dir.resolve()
    if output_dir.exists() or output_dir.is_symlink() or _within(destination, directory):
        raise ValueError('Output must be a fresh directory outside the source pet')
    sources = {'metadata': record(metadata_path), 'base': record(base), 'companion': record(companion_path)}
    # Decode/validate before creating staging files. extend repeats the bounded checks.
    base_image = _register.opened(base)
    base_delivery = delivery_sheet(base_image, 'base')
    output_dir.mkdir(parents=True, exist_ok=False)
    try:
        fragment = _register.extend(base, companion_path, output_dir / 'gadget-sheet.png')
        sheet = _register.opened(output_dir / 'gadget-sheet.png')
        talking = talking_metrics(sheet, mouth_box)
        companion_delivery = delivery_sheet(sheet, 'companion')
        shutil.copyfile(base, output_dir / base.name)
        metadata['spritesheetPath'] = base.name
        metadata.update(fragment)
        (output_dir / 'pet.json').write_text(json.dumps(metadata, indent=2) + '\n')
        frames = []
        for row, (name, count) in enumerate(COUNTS):
            for column in range(count):
                cell = sheet.crop((column * 192, row * 208, (column + 1) * 192, (row + 1) * 208))
                frames.append({'state': name, 'frame': column, 'bbox': list(_register.bbox(cell))})
        contact_sheet(base_image, sheet, output_dir / 'contact-sheet.png')
        for source in sources.values():
            if sha256(Path(source['path'])) != source['sha256']:
                raise ValueError('Input changed during staging')
        outputs = {path.name: {'sha256': sha256(path), 'bytes': path.stat().st_size}
                   for path in sorted(output_dir.iterdir())}
        receipt = {'schema': 1, 'sources': sources, 'outputs': outputs,
                   'registration': 'median idle height, center and baseline; nearest neighbour',
                   'frames': frames, 'talking': talking, 'delivery': [base_delivery, companion_delivery],
                   'acceptance': {'human_art_review': 'pending', 'live_delivery': 'untested',
                                  'playback_response': 'untested', 'physical_legibility': 'untested'}}
        (output_dir / 'manifest.json').write_text(json.dumps(receipt, indent=2) + '\n')
        return receipt
    except BaseException:
        shutil.rmtree(output_dir)
        raise


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--metadata', type=Path)
    parser.add_argument('--prepare-grid', type=Path, help='Prepare a reviewed six-column/three-row raw grid instead of staging')
    parser.add_argument('--companion', type=Path)
    parser.add_argument('--output-dir', type=Path)
    parser.add_argument('--prepared-output', type=Path)
    parser.add_argument('--mouth-box', nargs=4, type=int, metavar=('LEFT', 'TOP', 'RIGHT', 'BOTTOM'))
    args = parser.parse_args()
    if args.prepare_grid:
        if not args.prepared_output or args.metadata or args.companion or args.output_dir or args.mouth_box:
            parser.error('--prepare-grid requires only --prepared-output')
        receipt = prepare_grid(args.prepare_grid, args.prepared_output)
        print(json.dumps(receipt, indent=2))
        raise SystemExit(0)
    if not args.metadata or not args.companion or not args.output_dir or args.prepared_output:
        parser.error('Staging requires --metadata, --companion and --output-dir')
    build(args.metadata, args.companion, args.output_dir, tuple(args.mouth_box) if args.mouth_box else None)
    # Paths and metadata stay in the private receipt; avoid leaking them in normal logs.
    print('Staged bundle and review receipt; human, live and physical acceptance remain pending.')
