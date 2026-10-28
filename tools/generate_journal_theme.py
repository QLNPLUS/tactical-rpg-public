"""Rebuild native4x journal tiles and nine-slice frames from committed clean inputs.

Pillow is the only dependency. Inputs live in ui/textures/journal, never explore/.
The source crops and metal/seal art are the user-approved game-painted assets.
"""
from pathlib import Path
from PIL import Image, ImageDraw
import argparse
import hashlib
import json
import math

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "ui" / "textures" / "journal"


def tile(source: str, target: str) -> None:
    """Mirror the outer quarters so repeated material has no hard edge seam."""
    image = Image.open(ASSETS / source).convert("RGBA")
    quarter = image.crop((0, 0, 256, 256))
    result = Image.new("RGBA", (512, 512))
    result.paste(quarter, (0, 0))
    result.paste(quarter.transpose(Image.Transpose.FLIP_LEFT_RIGHT), (256, 0))
    result.paste(quarter.transpose(Image.Transpose.FLIP_TOP_BOTTOM), (0, 256))
    result.paste(quarter.transpose(Image.Transpose.ROTATE_180), (256, 256))
    result.save(ASSETS / target)


def frame(target: str, paper: bool) -> None:
    """Transparent 128-logical-pixel frame; 32px corners never stretch."""
    image = Image.new("RGBA", (512, 512))
    draw = ImageDraw.Draw(image)
    draw.rectangle((2, 2, 509, 509), outline="#151822", width=12)
    draw.rectangle((10, 10, 501, 501), outline="#635849", width=4)
    draw.rectangle((18, 18, 493, 493), outline="#b59a62", width=3)
    if paper:
        draw.rectangle((25, 25, 486, 486), outline="#9b8152", width=2)
        # Small edge-only nicks: no stains, tears, or markings in the text area.
        draw.line((180, 502, 191, 493, 209, 503), fill="#92734b", width=2)
        draw.line((343, 21, 355, 28, 365, 22), fill="#ab8e5d", width=2)
    corner = Image.open(ASSETS / "corner-metal-source.png").convert("RGBA")
    corner = corner.resize((128, 128), Image.Resampling.NEAREST)
    image.alpha_composite(corner, (4, 4))
    image.alpha_composite(corner.transpose(Image.Transpose.FLIP_LEFT_RIGHT), (380, 4))
    image.alpha_composite(corner.transpose(Image.Transpose.FLIP_TOP_BOTTOM), (4, 380))
    image.alpha_composite(corner.transpose(Image.Transpose.ROTATE_180), (380, 380))
    image.save(ASSETS / target)


def settings_gear() -> None:
    """Draw an eight-tooth warm-gold icon without relying on a font glyph."""
    scale = 4
    image = Image.new("RGBA", (64 * scale, 64 * scale))
    draw = ImageDraw.Draw(image)
    points = []
    for tooth in range(8):
        for offset, radius in ((-0.5, 21), (-0.30, 28), (0.30, 28), (0.5, 21)):
            angle = (tooth + offset) * math.tau / 8
            points.append(((32 + math.cos(angle) * radius) * scale,
                           (32 + math.sin(angle) * radius) * scale))
    draw.polygon(points, fill="#a57b3d")
    draw.line(points + [points[0]], fill="#4c3823", width=2 * scale,
              joint="curve")
    draw.ellipse((16 * scale, 16 * scale, 48 * scale, 48 * scale),
                 outline="#c8a364", width=scale)
    draw.ellipse((21 * scale, 21 * scale, 43 * scale, 43 * scale),
                 fill="#4c3823")
    draw.ellipse((23 * scale, 23 * scale, 41 * scale, 41 * scale),
                 fill=(0, 0, 0, 0))
    image.resize((64, 64), Image.Resampling.LANCZOS).save(ASSETS / "settings-gear.png")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    outputs = ("paper-tile.png", "leather-tile.png", "paper-frame.png", "leather-frame.png",
               "settings-gear.png")
    before = {name: hashlib.sha256((ASSETS / name).read_bytes()).hexdigest()
              for name in outputs if (ASSETS / name).exists()}
    tile("paper-source.png", "paper-tile.png")
    tile("leather-source.png", "leather-tile.png")
    frame("paper-frame.png", True)
    frame("leather-frame.png", False)
    settings_gear()
    after = {name: hashlib.sha256((ASSETS / name).read_bytes()).hexdigest() for name in outputs}
    if args.check and before != after:
        raise SystemExit("Journal generated assets were stale; regenerated them. Re-run --check.")
    print(json.dumps({"native_scale": 4, "tile_logical_px": 128,
                      "frame_slice_native_px": 128, "frame_corner_logical_px": 32,
                      "reproducible": before == after, "sha256": after}, indent=2))


if __name__ == "__main__":
    main()
