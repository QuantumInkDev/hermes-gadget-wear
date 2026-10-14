"""Original synthetic robot portraits and registered companion poses for carousel demos."""
from __future__ import annotations
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
from PIL import Image, ImageDraw

COLORS = [(255, 186, 102, 255), (111, 216, 195, 255), (197, 164, 245, 255)]


def robot(draw, x, y, color, pose='idle', frame=0):
    offset = [0, 1, 2, 1, 0, -1][frame % 6]
    if pose == 'jumping': offset = [0, -4, -9, -9, -4, 0][frame % 6]
    if pose == 'failed': offset = 3
    y += offset
    draw.rounded_rectangle((x + 49, y + 64, x + 142, y + 165), 12, fill=color)
    draw.line((x + 95, y + 64, x + 95, y + 48), fill=color, width=5)
    draw.ellipse((x + 88, y + 37, x + 102, y + 51), fill=color)
    dark = (24, 30, 36, 255)
    for eye in [72, 116]:
        if pose == 'sleeping':
            draw.line((x + eye - 5, y + 96, x + eye + 5, y + 96), fill=dark, width=4)
        else:
            draw.ellipse((x + eye - 5, y + 91, x + eye + 5, y + 101), fill=dark)
    opening = [2, 7, 14][frame % 3] if pose == 'talking' else 3
    draw.rounded_rectangle((x + 85, y + 119, x + 105, y + 119 + opening), 2, fill=dark)
    draw.rectangle((x + 66, y + 166, x + 80, y + 179), fill=color)
    draw.rectangle((x + 111, y + 166, x + 125, y + 179), fill=color)
    if pose == 'waving':
        draw.line((x + 142, y + 102, x + 154 + frame * 2, y + 62), fill=color, width=8)
    elif pose == 'listening':
        draw.arc((x + 149, y + 74, x + 175, y + 112), 285, 75, fill=color, width=3)
    elif pose in ('running', 'running-left', 'running-right'):
        draw.line((x + 49, y + 115, x + 35, y + 124 + frame), fill=color, width=6)


def generate(directory: Path, index=0):
    directory.mkdir(parents=True, exist_ok=True)
    color = COLORS[index % len(COLORS)]
    portrait = Image.new('RGBA', (192, 208), (24, 30, 36, 255))
    robot(ImageDraw.Draw(portrait), 0, 0, color)
    portrait.save(directory / 'portrait.png')
    base = Image.new('RGBA', (1536, 1872))
    draw = ImageDraw.Draw(base)
    states = ['idle', 'running-right', 'running-left', 'waving', 'jumping', 'failed', 'waiting', 'running', 'review']
    for row, state in enumerate(states):
        for frame in range(4 if state == 'waving' else 6):
            robot(draw, frame * 192, row * 208, color, state, frame)
    base_path = directory / 'base.png'
    base.save(base_path)
    companion = Image.new('RGBA', (1152, 624))
    draw = ImageDraw.Draw(companion)
    for row, state in enumerate(['listening', 'talking', 'sleeping']):
        for frame in range(3 if state == 'talking' else 6):
            robot(draw, frame * 192, row * 208, color, state, frame)
    input_path = directory / 'companion-input.png'
    companion.save(input_path)
    before = hashlib.sha256(base_path.read_bytes()).hexdigest()
    spec = importlib.util.spec_from_file_location('extend_pet', Path(__file__).with_name('extend-pet.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    metadata = module.extend(base_path, input_path, directory / 'companion.png')
    assert before == hashlib.sha256(base_path.read_bytes()).hexdigest()
    (directory / 'pet.json').write_text(json.dumps(metadata, indent=2) + '\n')
    return base_path


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    for index in range(3): generate(args.output / f'box-{index + 1}', index)
    files = sorted(p for p in args.output.rglob('*') if p.is_file() and p.name != 'manifest.json')
    manifest = {'original': True, 'personal_art': False, 'generator': 'tools/carousel_fixtures.py', 'base_preserved_by_registration': True, 'files': [{'path': str(p.relative_to(args.output)), 'sha256': hashlib.sha256(p.read_bytes()).hexdigest(), 'bytes': p.stat().st_size} for p in files]}
    (args.output / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
