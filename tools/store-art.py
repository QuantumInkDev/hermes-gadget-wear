#!/usr/bin/env python3
"""Reproduce original procedural store artwork; requires the pinned Pillow tool environment."""
import argparse
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont


def robot(draw, x, y, size):
    amber = '#FFBA66'
    dark = '#11170F'
    stroke = max(2, int(size / 80))
    draw.rectangle((x + size * .2, y + size * .25, x + size * .8, y + size * .75), fill=amber)
    for eye in [.36, .64]:
        draw.rectangle((x + size * (eye - .04), y + size * .4,
                        x + size * (eye + .04), y + size * .48), fill=dark)
    draw.rectangle((x + size * .4, y + size * .61, x + size * .6, y + size * .64), fill=dark)
    draw.line((x + size * .5, y + size * .16, x + size * .5, y + size * .25), fill=amber, width=stroke)
    draw.ellipse((x + size * .47, y + size * .11, x + size * .53, y + size * .17), fill=amber)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    icon = Image.new('RGB', (512, 512), '#11170F')
    robot(ImageDraw.Draw(icon), 16, 16, 480)
    icon.save(args.output / 'icon-512.png')
    graphic = Image.new('RGB', (1024, 500), '#11170F')
    draw = ImageDraw.Draw(graphic)
    robot(draw, 24, 54, 380)
    draw.text((440, 100), 'HERMES', fill='#B6ED93', font=ImageFont.load_default(size=64))
    draw.text((440, 175), 'GADGET', fill='#F0F3EB', font=ImageFont.load_default(size=64))
    draw.text((440, 278), 'Speak to your own agent', fill='#BEC7B7', font=ImageFont.load_default(size=28))
    draw.text((440, 335), 'WEAR OS + ANDROID', fill='#B6ED93', font=ImageFont.load_default(size=23))
    graphic.save(args.output / 'feature-1024x500.png')


if __name__ == '__main__':
    main()
