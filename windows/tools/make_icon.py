#!/usr/bin/env python3
"""Draws windows/OpenCam.Client/OpenCam.ico.

The mark is the same one the Android launcher uses: a dark camera body with a
lens, drawn in the palette the whole project shares (#1b1b1b surface, #2f2f2f
body, #fb1a67 accent). It is generated rather than committed as an opaque binary
so the sizes and colours can be changed by editing numbers.

Requires Pillow:  pip install pillow
"""

import pathlib

from PIL import Image, ImageDraw

BACKGROUND = (0x1B, 0x1B, 0x1B, 255)
BODY = (0x2F, 0x2F, 0x2F, 255)
LENS_RING = (0x0C, 0x0C, 0x10, 255)
ACCENT = (0xFB, 0x1A, 0x67, 255)

SUPERSAMPLE = 4
SIZES = [256, 128, 64, 48, 32, 24, 16]


def draw(units, size):
    """Draws the mark on a size x size canvas; units is the 108 unit viewport."""
    scale = size / 108.0

    def s(value):
        return value * scale

    image = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    pen = ImageDraw.Draw(image)

    pen.rounded_rectangle([0, 0, size - 1, size - 1], radius=s(22), fill=BACKGROUND)
    pen.rounded_rectangle([s(22), s(34), s(86), s(74)], radius=s(8), fill=BODY)
    pen.ellipse([s(40), s(40), s(68), s(68)], fill=LENS_RING)
    pen.ellipse([s(46), s(46), s(62), s(62)], fill=ACCENT)
    pen.rectangle([s(44), s(74), s(64), s(80)], fill=BODY)
    pen.rectangle([s(34), s(80), s(74), s(85)], fill=BODY)
    return image


def main():
    target = pathlib.Path(__file__).resolve().parent.parent / "OpenCam.Client" / "OpenCam.ico"

    # Draw once at high resolution, then let Pillow produce every icon size.
    master = draw(108, 1024)
    frames = []
    for size in SIZES:
        frames.append(master.resize((size, size), Image.LANCZOS))

    frames[0].save(target, format="ICO", sizes=[(s, s) for s in SIZES])
    print(f"wrote {target} ({', '.join(f'{s}x{s}' for s in SIZES)})")


if __name__ == "__main__":
    main()
