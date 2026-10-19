"""Stage a companion bundle and review evidence without editing a profile or input art."""
import argparse
import hashlib
import importlib.util
import io
import json
import shutil
import stat
import unicodedata
from pathlib import Path, PureWindowsPath

from PIL import Image, ImageChops, ImageDraw

_SPEC = importlib.util.spec_from_file_location('extend_pet', Path(__file__).with_name('extend-pet.py'))
_register = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(_register)
COUNTS = (('listening', 6), ('talking', 3), ('sleeping', 6))
RESERVED = {'pet.json', 'gadget-sheet.png', 'manifest.json', 'contact-sheet.png'}
MAX_ART_BYTES = 8 * 1024 * 1024
MAX_METADATA_BYTES = 65536
HASH_CHUNK_BYTES = 64 * 1024


def bounded_size(path, limit=MAX_ART_BYTES):
    size = path.stat().st_size
    if size > limit:
        raise ValueError('Input exceeds byte limit')
    return size


def sha256(path, limit=MAX_ART_BYTES):
    bounded_size(path, limit)
    digest = hashlib.sha256()
    total = 0
    with path.open('rb') as stream:
        while chunk := stream.read(min(HASH_CHUNK_BYTES, limit - total + 1)):
            total += len(chunk)
            if total > limit:
                raise ValueError('Input exceeds byte limit while hashing')
            digest.update(chunk)
    return digest.hexdigest()


def record(path, limit=MAX_ART_BYTES):
    size = bounded_size(path, limit)
    return {'path': str(path.resolve()), 'sha256': sha256(path, limit), 'bytes': size}


def _within(child, parent):
    return child == parent or parent in child.parents


def _within_source_directory(destination, source_dirs):
    """Compare actual ancestors: resolve() alone does not canonicalize case aliases."""
    try:
        identities = set()
        for source in source_dirs:
            info = source.stat()
            if not stat.S_ISDIR(info.st_mode):
                raise ValueError('Cannot inspect source directory')
            identities.add((info.st_dev, info.st_ino))
        for ancestor in destination.parents:
            try:
                ancestor.lstat()
            except FileNotFoundError:
                # A fresh destination may have several not-yet-created parents.
                continue
            # Only absent entries are ignored; broken links and inspection errors refuse.
            info = ancestor.stat()
            if not stat.S_ISDIR(info.st_mode):
                raise ValueError('Cannot inspect output ancestor directory')
            if (info.st_dev, info.st_ino) in identities:
                return True
    except (OSError, RuntimeError) as exc:
        raise ValueError('Cannot inspect output/source directories') from exc
    return False


def _relative_path(value, field, limit=None):
    if not isinstance(value, str) or not value.strip() or '\x00' in value:
        raise ValueError(f'Invalid {field}')
    try:
        length = len(value.encode('utf-8'))
    except UnicodeError as exc:
        raise ValueError(f'Invalid {field} Unicode') from exc
    windows = PureWindowsPath(value)
    if (windows.drive or windows.root or ':' in value
            or '..' in value.replace('\\', '/').split('/') or (limit is not None and length > limit)):
        raise ValueError(f'{field} must be a bounded relative path')
    return Path(value)


def _unique_metadata(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('Duplicate pet metadata field')
        result[key] = value
    return result


def _output_collisions(paths):
    # Also refuse file/directory and case/Unicode aliases on common macOS/Windows filesystems.
    keys = [tuple(unicodedata.normalize('NFC', part).casefold() for part in path.parts) for path in paths]
    for index, key in enumerate(keys):
        for other in keys[:index]:
            if key[:len(other)] == other or other[:len(key)] == key:
                raise ValueError('Base filename collides with staging output')


def _copy_art(source, output):
    """Exclusive, bounded copy; final source and output hashes are checked by build."""
    bounded_size(source)
    output.parent.mkdir(parents=True, exist_ok=True)
    with source.open('rb') as src, output.open('xb') as dst:
        total = 0
        while chunk := src.read(min(HASH_CHUNK_BYTES, MAX_ART_BYTES - total + 1)):
            total += len(chunk)
            if total > MAX_ART_BYTES:
                raise ValueError('Input exceeds byte limit while copying')
            dst.write(chunk)


def _base_image(path):
    image = _register.opened(path)
    if image.width not in (1536, 1728) or image.height % 208 or not 1664 <= image.height <= 2496:
        raise ValueError('Expected base atlas geometry')
    return image


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
            bounds = _register.bbox(cell)
            if not bounds:
                raise ValueError('Empty raw companion pose')
            if bounds[0] == 0 or bounds[1] == 0 or bounds[2] == cell.width or bounds[3] == cell.height:
                raise ValueError('Clipped raw companion pose')
            scaled = cell.resize((max(1, round(cell.width * scale)), max(1, round(cell.height * scale))), Image.Resampling.NEAREST)
            x, y = (192 - scaled.width) // 2, (208 - scaled.height) // 2
            result.paste(scaled, (column * 192 + x, row * 208 + y))
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open('xb') as stream:
        result.save(stream, 'PNG')
    return {'source': record(source_path), 'output': record(output), 'scale': scale,
            'grid': [6, 3], 'operation': 'equal-grid extraction, shared nearest-neighbour scale, blank unused talking cells'}


def talking_metrics(sheet, mouth_box, overlay=False):
    if mouth_box is None:
        return {'status': 'unchecked', 'reason': 'No reviewed mouth rectangle supplied'}
    if len(mouth_box) != 4 or any(type(n) is not int for n in mouth_box):
        raise ValueError('Mouth rectangle requires four integers')
    left, top, right, bottom = mouth_box
    if overlay and (right - left > 96 or bottom - top > 104):
        raise ValueError('Overlay rectangle exceeds region limits')
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
    if any(outside) and not overlay:
        raise ValueError('Talking body changes outside the reviewed mouth rectangle')
    if not all(inside) or _changed(frames[1].crop(mouth_box), frames[2].crop(mouth_box)) == 0:
        raise ValueError('Talking requires three distinct mouth shapes')
    if overlay:
        for frame in frames:
            region = frame.crop(mouth_box)
            if not _register.bbox(region):
                raise ValueError('Empty overlay region sample')
        return {'status': 'overlay_region_validated', 'reviewed_region': list(mouth_box),
                'source_whole_body_match': 'failed' if any(outside) else 'matched',
                'outside_mouth_changed_pixels': outside, 'mouth_changed_pixels': inside,
                'raw_full_frame_changed_pixels': [_changed(anchor, frame) for frame in frames[1:]],
                'requires_expression_overlays': 1}
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


def build(metadata_path: Path, companion_path: Path, output_dir: Path, mouth_box=None, overlay_rect=None,
          gadget_base=None):
    if overlay_rect is not None and mouth_box is not None:
        raise ValueError('Full-frame mouth gate and expression overlay are mutually exclusive')
    metadata_path, companion_path, output_dir = map(Path, (metadata_path, companion_path, output_dir))
    directory = metadata_path.resolve().parent
    bounded_size(metadata_path, MAX_METADATA_BYTES)
    with metadata_path.open('rb') as stream:
        raw_metadata = stream.read(MAX_METADATA_BYTES + 1)
    if len(raw_metadata) > MAX_METADATA_BYTES:
        raise ValueError('Pet metadata exceeds limit')
    metadata = json.loads(raw_metadata.decode('utf-8'), object_pairs_hook=_unique_metadata)
    if not isinstance(metadata, dict) or 'spritesheetPath' not in metadata:
        raise ValueError('Invalid pet metadata')
    if 'gadgetBasePath' in metadata:
        if gadget_base is None:
            raise ValueError('Metadata declares gadgetBasePath; supply explicit --gadget-base')
        _relative_path(metadata['gadgetBasePath'], 'gadgetBasePath', 512)
    relative = _relative_path(metadata['spritesheetPath'], 'spritesheetPath')
    try:
        base = (directory / relative).resolve()
        base_exists = base.is_file()
    except (OSError, RuntimeError) as exc:
        raise ValueError('Invalid base path') from exc
    if not _within(base, directory) or not base_exists:
        raise ValueError('Base path escapes pet directory or is missing')
    selected = Path(gadget_base).resolve() if gadget_base is not None else base
    # The explicit variant retains the exact regular metadata path, including subdirectories.
    base_output = relative if gadget_base is not None else Path(base.name)
    alternate_output = Path('gadget-base' + (selected.suffix or '.png')) if gadget_base is not None else None
    names = [*(Path(name) for name in RESERVED), base_output]
    if alternate_output is not None:
        names.append(alternate_output)
    _output_collisions(names)
    destination = output_dir.resolve()
    source_dirs = {directory}
    if gadget_base is not None:
        source_dirs.update((companion_path.resolve().parent, selected.parent))
    if output_dir.exists() or output_dir.is_symlink() or _within_source_directory(destination, source_dirs):
        raise ValueError('Output must be a fresh directory outside all source directories')
    # Check every source before hashing any of them; invalid art never reaches a read.
    inputs = [(metadata_path, MAX_METADATA_BYTES), (base, MAX_ART_BYTES), (companion_path, MAX_ART_BYTES)]
    if gadget_base is not None:
        inputs.append((selected, MAX_ART_BYTES))
    for path, limit in inputs:
        bounded_size(path, limit)
    sources = {'metadata': record(metadata_path, MAX_METADATA_BYTES), 'base': record(base), 'companion': record(companion_path)}
    if sources['metadata']['sha256'] != hashlib.sha256(raw_metadata).hexdigest():
        raise ValueError('Input changed during staging')
    if gadget_base is not None:
        sources['gadget_base'] = record(selected)
    # Decode/validate before creating staging files. extend repeats the bounded checks.
    regular_image = _base_image(base)
    base_image = _base_image(selected) if gadget_base is not None else regular_image
    base_delivery = delivery_sheet(base_image, 'base')
    output_dir.mkdir(parents=True, exist_ok=False)
    try:
        fragment = _register.extend(selected, companion_path, output_dir / 'gadget-sheet.png')
        companion_hash = sha256(output_dir / 'gadget-sheet.png')
        sheet = _register.opened(output_dir / 'gadget-sheet.png')
        talking = talking_metrics(sheet, overlay_rect if overlay_rect is not None else mouth_box,
                                  overlay=overlay_rect is not None)
        if overlay_rect is not None:
            fragment['gadgetSheet']['rows'][1]['overlay'] = {'anchor_frame': 0, 'rect': list(overlay_rect)}
        companion_delivery = delivery_sheet(sheet, 'companion')
        if overlay_rect is not None:
            companion_delivery['rows'][1]['overlay'] = fragment['gadgetSheet']['rows'][1]['overlay']
        _copy_art(base, output_dir / base_output)
        if alternate_output is not None:
            _copy_art(selected, output_dir / alternate_output)
            metadata['gadgetBasePath'] = alternate_output.as_posix()
        else:
            metadata['spritesheetPath'] = base.name
        metadata.update(fragment)
        proposed_metadata = (json.dumps(metadata, indent=2) + '\n').encode('utf-8')
        if len(proposed_metadata) > MAX_METADATA_BYTES:
            raise ValueError('Output pet metadata exceeds limit')
        with (output_dir / 'pet.json').open('xb') as stream:
            stream.write(proposed_metadata)
        frames = []
        for row, (name, count) in enumerate(COUNTS):
            for column in range(count):
                cell = sheet.crop((column * 192, row * 208, (column + 1) * 192, (row + 1) * 208))
                frames.append({'state': name, 'frame': column, 'bbox': list(_register.bbox(cell))})
        contact_sheet(base_image, sheet, output_dir / 'contact-sheet.png')
        for name, source in sources.items():
            limit = MAX_METADATA_BYTES if name == 'metadata' else MAX_ART_BYTES
            if sha256(Path(source['path']), limit) != source['sha256']:
                raise ValueError('Input changed during staging')
        outputs = {path.relative_to(output_dir).as_posix(): {'sha256': sha256(path), 'bytes': path.stat().st_size}
                   for path in sorted(output_dir.rglob('*')) if path.is_file()}
        expected = {base_output.as_posix(): sources['base']['sha256'],
                    'pet.json': hashlib.sha256(proposed_metadata).hexdigest(),
                    'gadget-sheet.png': companion_hash}
        if alternate_output is not None:
            expected[alternate_output.as_posix()] = sources['gadget_base']['sha256']
        if any(outputs[name]['sha256'] != digest for name, digest in expected.items()):
            raise ValueError('Output changed during staging')
        receipt = {'schema': 1, 'sources': sources, 'outputs': outputs,
                   'registration': 'median idle height, center and baseline; nearest neighbour',
                   'frames': frames, 'talking': talking, 'delivery': [base_delivery, companion_delivery],
                   'acceptance': {'human_art_review': 'pending', 'live_delivery': 'untested',
                                  'playback_response': 'untested', 'physical_legibility': 'untested'}}
        if overlay_rect is not None:
            receipt['acceptance']['sdk_overlay_negotiation'] = 'unproven'
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
    parser.add_argument('--gadget-base', type=Path, help='Separate validated Gadget base; preserve the regular atlas and its metadata path')
    parser.add_argument('--output-dir', type=Path)
    parser.add_argument('--prepared-output', type=Path)
    parser.add_argument('--mouth-box', nargs=4, type=int, metavar=('LEFT', 'TOP', 'RIGHT', 'BOTTOM'))
    parser.add_argument('--expression-overlay', nargs=4, type=int, metavar=('LEFT', 'TOP', 'RIGHT', 'BOTTOM'),
                        help='Explicit reviewed cell-space replacement region; requires negotiated overlays v1')
    args = parser.parse_args()
    if args.expression_overlay and args.mouth_box:
        parser.error('--expression-overlay and --mouth-box are mutually exclusive')
    if args.prepare_grid:
        if not args.prepared_output or args.metadata or args.companion or args.output_dir or args.mouth_box or args.expression_overlay or args.gadget_base:
            parser.error('--prepare-grid requires only --prepared-output')
        receipt = prepare_grid(args.prepare_grid, args.prepared_output)
        print(json.dumps(receipt, indent=2))
        raise SystemExit(0)
    if not args.metadata or not args.companion or not args.output_dir or args.prepared_output:
        parser.error('Staging requires --metadata, --companion and --output-dir')
    build(args.metadata, args.companion, args.output_dir, tuple(args.mouth_box) if args.mouth_box else None,
          tuple(args.expression_overlay) if args.expression_overlay else None, args.gadget_base)
    # Paths and metadata stay in the private receipt; avoid leaking them in normal logs.
    print('Staged bundle and review receipt; human, live and physical acceptance remain pending.')
