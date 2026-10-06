"""Register later-generated companion rows against a base idle row; never modify the base."""
import argparse
import json
import statistics
from pathlib import Path

from PIL import Image


def bbox(image):
    return image.getchannel('A').point(lambda value: 255 if value > 16 else 0).getbbox()


def opened(path):
    if path.stat().st_size > 8 * 1024 * 1024:
        raise ValueError('Input exceeds limit')
    with Image.open(path) as source:
        if source.width * source.height > 3200000 or source.mode != 'RGBA':
            raise ValueError('Expected bounded RGBA art')
        return source.copy()


def extend(base_path: Path, companion_path: Path, output: Path):
    base, source = opened(base_path), opened(companion_path)
    if base.width not in (1536, 1728) or base.height % 208 or not 1664 <= base.height <= 2496 or source.size != (1152, 624):
        raise ValueError('Expected base atlas and six-column, three-row companion input')
    boxes = []
    for index in range(6):
        box = bbox(base.crop((index * 192, 0, (index + 1) * 192, 208)))
        if not box:
            break
        boxes.append(box)
    if not boxes:
        raise ValueError('Empty idle row')
    target_height = statistics.median(box[3] - box[1] for box in boxes)
    target_center = statistics.median((box[0] + box[2]) / 2 for box in boxes)
    target_bottom = statistics.median(box[3] for box in boxes)
    result = Image.new('RGBA', source.size)
    rows = []
    for row, (name, count) in enumerate([('listening', 6), ('talking', 3), ('sleeping', 6)]):
        frames = [source.crop((column * 192, row * 208, (column + 1) * 192, (row + 1) * 208)) for column in range(count)]
        bounds = [bbox(frame) for frame in frames]
        if any(box is None for box in bounds):
            raise ValueError('Empty companion frame')
        heights = [box[3] - box[1] for box in bounds]
        center = statistics.median((box[0] + box[2]) / 2 for box in bounds)
        bottom = statistics.median(box[3] for box in bounds)
        height = statistics.median(heights)
        if any(abs((box[0] + box[2]) / 2 - center) > 6 or abs(box[3] - bottom) > 6 or abs(h - height) > max(6, height * .15) for box, h in zip(bounds, heights)):
            raise ValueError('Companion registration drifts')
        scale = target_height / height
        if not .25 <= scale <= 4:
            raise ValueError('Extreme scale change requires review')
        for column, (frame, box) in enumerate(zip(frames, bounds)):
            if box[0] == 0 or box[1] == 0 or box[2] == 192 or box[3] == 208:
                raise ValueError('Clipped source pose')
            # One scale and registration per row; preserve relative pose motion.
            scaled = frame.resize((round(192 * scale), round(208 * scale)), Image.Resampling.NEAREST)
            x = round(target_center - center * scale)
            y = round(target_bottom - bottom * scale)
            sb = bbox(scaled)
            if not sb or sb[0] + x < 1 or sb[1] + y < 1 or sb[2] + x > 191 or sb[3] + y > 207:
                raise ValueError('Registered pose would clip')
            cell = Image.new('RGBA', (192, 208))
            cell.paste(scaled, (x, y))
            result.paste(cell, (column * 192, row * 208))
        rows.append({'name': name, 'index': row, 'frames': count})
    output.parent.mkdir(parents=True, exist_ok=True)
    result.save(output, 'PNG')
    return {'gadgetSheet': {'spritesheetPath': output.name, 'rows': rows}}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', type=Path, required=True)
    parser.add_argument('--companion', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.resolve() in (args.base.resolve(), args.companion.resolve()):
        parser.error('Output must be separate from both inputs')
    print(json.dumps(extend(args.base, args.companion, args.output), indent=2))
