#!/usr/bin/env python3
"""Extract the approved metallic portrait surround without its portrait/backdrop.

Requires Pillow. Example:
    python tools/generate_turn_portrait_frames.py --source approved-render.png

The input is never modified. All crop coordinates use Pillow's half-open pixel
convention. The eight border tiles, not the centre tile, are resampled; every
transparent output pixel also has zero RGB. No generated scene is used as a HUD
background. Use --evidence-dir to render a visual inspection sheet.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

from PIL import Image, ImageDraw, __version__ as pillow_version

REFERENCE_SIZE = (2172, 724)
REFERENCE_SHA256 = "528b50b2ed4bad4de226cdc8db42cf1662619c16122c8dd19cfb0307a7d6df49"
OUTPUT_SIZE = (144, 168)
LOGICAL_SIZE = (36, 42)
DENSITY = 4
# The left frame's visible metal. No entity pixels lie in these eight tiles.
SOURCE_X = (128, 148, 270, 289)
SOURCE_Y = (273, 292, 436, 455)
TARGET_X = (2, 20, 124, 142)
TARGET_Y = (2, 20, 148, 166)
OPENING = (20, 20, 124, 148)
OUTER_POLYGON = ((2, 4), (4, 2), (140, 2), (142, 4),
                 (142, 164), (140, 166), (4, 166), (2, 164))
# Gentle, warm exposure changes, not opaque glow/shadow overlays. Shared alpha.
STATES = (
    ("turn-portrait-frame.png", 1.00, (0, 0, 0)),
    ("turn-portrait-frame-current.png", 1.07, (2, 1, 0)),
    ("turn-portrait-frame-hover.png", 1.13, (3, 2, 0)),
    ("turn-portrait-frame-current-hover.png", 1.19, (4, 3, 1)),
)


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def extract_border(source: Image.Image) -> Image.Image:
    """Nine-slice geometry with the portrait-containing ninth tile omitted."""
    texture = Image.new("RGB", OUTPUT_SIZE, (0, 0, 0))
    for row in range(3):
        for col in range(3):
            if row == 1 and col == 1:
                continue
            source_box = (SOURCE_X[col], SOURCE_Y[row],
                          SOURCE_X[col + 1], SOURCE_Y[row + 1])
            target_box = (TARGET_X[col], TARGET_Y[row],
                          TARGET_X[col + 1], TARGET_Y[row + 1])
            size = (target_box[2] - target_box[0],
                    target_box[3] - target_box[1])
            tile = source.crop(source_box).resize(size, Image.Resampling.LANCZOS)
            texture.paste(tile, target_box[:2])
    return texture


def shared_alpha() -> Image.Image:
    """A supersampled outer contour with exact, fully clear inner rectangle."""
    supersampling = 4
    high = Image.new("L", tuple(v * supersampling for v in OUTPUT_SIZE), 0)
    draw = ImageDraw.Draw(high)
    draw.polygon([(x * supersampling, y * supersampling)
                  for x, y in OUTER_POLYGON], fill=255)
    alpha = high.resize(OUTPUT_SIZE, Image.Resampling.LANCZOS)
    draw = ImageDraw.Draw(alpha)
    # Suppress resampling ringing outside the explicit transparent safe margin.
    draw.rectangle((0, 0, OUTPUT_SIZE[0] - 1, 1), fill=0)
    draw.rectangle((0, OUTPUT_SIZE[1] - 2, OUTPUT_SIZE[0] - 1,
                    OUTPUT_SIZE[1] - 1), fill=0)
    draw.rectangle((0, 0, 1, OUTPUT_SIZE[1] - 1), fill=0)
    draw.rectangle((OUTPUT_SIZE[0] - 2, 0, OUTPUT_SIZE[0] - 1,
                    OUTPUT_SIZE[1] - 1), fill=0)
    draw.rectangle((OPENING[0], OPENING[1], OPENING[2] - 1,
                    OPENING[3] - 1), fill=0)
    return alpha


def make_state(texture: Image.Image, alpha: Image.Image,
               gain: float, lift: tuple[int, int, int]) -> Image.Image:
    pixels = []
    texture_bytes = texture.tobytes()
    for index, opacity in enumerate(alpha.tobytes()):
        color = texture_bytes[index * 3:index * 3 + 3]
        if opacity == 0:
            pixels.append((0, 0, 0, 0))
        else:
            rgb = tuple(min(255, max(0, int(channel * gain + add + 0.5)))
                        for channel, add in zip(color, lift))
            pixels.append((*rgb, opacity))
    state = Image.new("RGBA", OUTPUT_SIZE)
    state.putdata(pixels)
    return state


def verify(images: list[Image.Image]) -> dict:
    reference_alpha = images[0].getchannel("A").tobytes()
    for image in images:
        assert image.mode == "RGBA" and image.size == OUTPUT_SIZE
        alpha = image.getchannel("A")
        assert alpha.tobytes() == reference_alpha, "State alpha differs"
        assert alpha.crop(OPENING).getextrema() == (0, 0), "Contaminated opening"
        assert alpha.getpixel((72, 84)) == 0, "Portrait centre is not clear"
        assert alpha.crop((0, 0, 144, 2)).getextrema() == (0, 0)
        assert alpha.crop((0, 166, 144, 168)).getextrema() == (0, 0)
        assert alpha.crop((0, 0, 2, 168)).getextrema() == (0, 0)
        assert alpha.crop((142, 0, 144, 168)).getextrema() == (0, 0)
        assert alpha.getpixel((10, 84)) == 255
        assert alpha.getpixel((72, 10)) == 255
        rgba_bytes = image.tobytes()
        for index in range(0, len(rgba_bytes), 4):
            pixel = rgba_bytes[index:index + 4]
            assert pixel[3] != 0 or pixel[:3] == bytes(3)
    # Sample the four midline opening boundaries, independently of constants.
    alpha = images[0].getchannel("A")
    horizontal = [x for x in range(2, 142) if alpha.getpixel((x, 84)) == 0]
    vertical = [y for y in range(2, 166) if alpha.getpixel((72, y)) == 0]
    measured = [min(horizontal), min(vertical), max(horizontal) + 1,
                max(vertical) + 1]
    assert measured == list(OPENING), f"Unexpected opening: {measured}"
    nonzero = sum(value > 0 for value in reference_alpha)
    return {
        "passed": True,
        "assertions": ["RGBA 144x168", "identical alpha across all four states",
                       "fully transparent opening; no centre portrait RGB",
                       "2px fully clear exterior safe margin",
                       "opaque metal midlines; not an opaque rectangular scene",
                       "all alpha-zero pixels have RGB zero"],
        "measured_opening_box_px_half_open": measured,
        "nonzero_alpha_pixels": nonzero,
        "total_pixels": OUTPUT_SIZE[0] * OUTPUT_SIZE[1],
        "shared_alpha_sha256": hashlib.sha256(reference_alpha).hexdigest(),
    }


def render_evidence(source: Image.Image, images: list[Image.Image],
                    directory: Path) -> None:
    directory.mkdir(parents=True, exist_ok=True)
    source.crop((120, 265, 297, 463)).resize((708, 792)).save(
        directory / "source-detail.png")
    scale = 3
    cell_w, cell_h = 480, 588
    sheet = Image.new("RGB", (cell_w * 4, cell_h), (29, 32, 39))
    draw = ImageDraw.Draw(sheet)
    for index, ((name, _, _), image) in enumerate(zip(STATES, images)):
        origin_x, origin_y = index * cell_w + 24, 48
        for y in range(0, 504, 24):
            for x in range(0, 432, 24):
                shade = 185 if (x // 24 + y // 24) % 2 else 223
                draw.rectangle((origin_x + x, origin_y + y,
                                origin_x + x + 23, origin_y + y + 23),
                               fill=(shade, shade, shade))
        enlarged = image.resize((432, 504), Image.Resampling.NEAREST)
        sheet.paste(enlarged, (origin_x, origin_y), enlarged)
        label = name.removeprefix("turn-portrait-frame").removesuffix(".png")
        draw.text((origin_x, 18), label.lstrip("-") or "normal",
                  fill=(240, 218, 167))
        draw.text((origin_x, 562), "3x view; checker = transparency",
                  fill=(210, 210, 210))
    sheet.save(directory / "frames-checkerboard.png")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True,
                        help="Approved unmodified 2172x724 reference render")
    parser.add_argument("--output-dir", type=Path,
                        default=Path(__file__).resolve().parents[1] / "ui" / "textures")
    parser.add_argument("--evidence-dir", type=Path,
                        help="Optional inspection sheet directory (not a UI asset)")
    args = parser.parse_args()
    source_hash = sha256(args.source)
    with Image.open(args.source) as original:
        if original.size != REFERENCE_SIZE:
            raise SystemExit(f"Expected reference size {REFERENCE_SIZE}; got {original.size}")
        source = original.convert("RGB")
    if REFERENCE_SHA256 and source_hash != REFERENCE_SHA256:
        raise SystemExit("Source SHA-256 differs from the approved reference")
    texture = extract_border(source)
    alpha = shared_alpha()
    images = [make_state(texture, alpha, gain, lift) for _, gain, lift in STATES]
    checks = verify(images)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    outputs = []
    for (name, gain, lift), image in zip(STATES, images):
        path = args.output_dir / name
        image.save(path, format="PNG", optimize=False, compress_level=9)
        # Verify bytes actually saved, not just the in-memory generation result.
        with Image.open(path) as saved:
            assert saved.mode == "RGBA" and saved.tobytes() == image.tobytes()
        outputs.append({"file": name, "sha256": sha256(path),
                        "rgb_gain": gain, "rgb_lift": list(lift)})
    metadata = {
        "schema": "tactical.turn-portrait-frames.v1",
        "source": {"file_name": args.source.name, "sha256": source_hash,
                   "size_px": list(REFERENCE_SIZE),
                   "crop_box_px_half_open": [128, 273, 289, 455],
                   "source_x_cuts_px": list(SOURCE_X),
                   "source_y_cuts_px": list(SOURCE_Y)},
        "output": {"size_px": list(OUTPUT_SIZE),
                   "logical_size_px": list(LOGICAL_SIZE), "pixel_density": DENSITY,
                   "target_x_cuts_px": list(TARGET_X),
                   "target_y_cuts_px": list(TARGET_Y),
                   "opening_box_px_half_open": list(OPENING),
                   "opening_box_logical_px_half_open": [5, 5, 31, 37],
                   "opening_margins_px": {"left": 20, "top": 20,
                                          "right": 20, "bottom": 20},
                   "opening_margins_logical_px": {"left": 5, "top": 5,
                                                  "right": 5, "bottom": 5},
                   "outer_clear_safe_margin_px": 2,
                   "effective_midline_metal_thickness_px": 18,
                   "effective_midline_metal_thickness_logical_px": 4.5,
                   "outer_miter_polygon_px": [list(point) for point in OUTER_POLYGON]},
        "processing": [
            "Read-only source; crop the left metallic portrait frame, not the HUD strip.",
            "Eight-tile nine-slice LANCZOS resampling retains real double-bevel gold, dark groove, and diagonal miter texture.",
            "The centre source tile is never read into an output texture; no portrait, lettering, or backdrop is baked in.",
            "One 4x-supersampled clipped-corner outer mask is reused for all states; the exact rectangular opening and outer safe margins are forced alpha=0.",
            "Alpha-zero RGB is zeroed; state differences are restrained warm RGB exposure adjustments only, with no added glow.",
            "Deterministic pixel and PNG encoding settings; no timestamps or absolute local source path in the manifest.",
        ],
        "generator": "tools/generate_turn_portrait_frames.py",
        "pillow_version": pillow_version,
        "outputs": outputs,
        "verification": checks,
    }
    manifest = args.output_dir / "turn-portrait-frames.json"
    manifest.write_text(json.dumps(metadata, indent=2, ensure_ascii=False) + "\n",
                        encoding="utf-8", newline="\n")
    if args.evidence_dir:
        render_evidence(source, images, args.evidence_dir)
    assert sha256(args.source) == source_hash, "Source was changed"
    print(json.dumps({"source_sha256": source_hash, "checks": checks,
                      "outputs": outputs}, indent=2))


if __name__ == "__main__":
    main()
