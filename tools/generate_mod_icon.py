"""Reproducible original Tactical RPG release icon; no external assets or fonts."""
from pathlib import Path
import argparse
import hashlib
import io
import math

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent


def icon(size: int) -> Image.Image:
    # Draw once at native master resolution, then supersample the release sizes.
    n = 2048
    image = Image.new("RGB", (n, n))
    pixels = image.load()
    for y in range(n):
        for x in range(n):
            distance = min(1.0, math.hypot((x - n * .5) / n, (y - n * .39) / n) * 1.6)
            shade = int(12 * (1 - distance))
            pixels[x, y] = (19 + shade, 23 + shade, 34 + shade)
    draw = ImageDraw.Draw(image)
    s = n / 1024

    def polygon(points, fill, outline=None, width=1):
        p = [(round(x * s), round(y * s)) for x, y in points]
        draw.polygon(p, fill=fill)
        if outline:
            draw.line(p + [p[0]], fill=outline, width=round(width * s), joint="curve")

    def line(points, fill, width):
        draw.line([(round(x * s), round(y * s)) for x, y in points], fill=fill,
                  width=max(1, round(width * s)), joint="curve")

    frame = [(112, 48), (912, 48), (976, 112), (976, 912), (912, 976),
             (112, 976), (48, 912), (48, 112)]
    line(frame + [frame[0]], "#0c0e17", 30)
    line(frame + [frame[0]], "#98713b", 13)
    inset = [(118, 64), (906, 64), (960, 118), (960, 906), (906, 960),
             (118, 960), (64, 906), (64, 118)]
    line(inset + [inset[0]], "#efd5a0", 3)
    for cx, cy in [(112, 112), (912, 112), (112, 912), (912, 912)]:
        polygon([(cx, cy - 12), (cx + 12, cy), (cx, cy + 12), (cx - 12, cy)],
                "#c9a05a", "#f2dcaa", 2)
    # An isometric 3x3 board communicates tactics without depending on text.
    def cell(i, j):
        x, y = 512 + (i - j) * 108, 584 + (i + j) * 45
        return [(x, y), (x + 108, y + 45), (x, y + 90), (x - 108, y + 45)]
    polygon([(188, 719), (512, 854), (836, 719), (836, 743), (512, 878), (188, 743)],
            "#493729", "#b38c4f", 5)
    for i in range(3):
        for j in range(3):
            polygon(cell(i, j), "#c6ad76" if (i + j) % 2 == 0 else "#4a4d58",
                    "#d6b97e", 4)
    # The blades are independent original geometric drawings, not game textures.
    def sword(mirror=False):
        def p(points):
            return [(1024 - x if mirror else x, y) for x, y in points]
        polygon(p([(260, 196), (336, 230), (705, 621), (667, 658), (292, 264)]),
                "#10131e", "#10131e", 15)
        polygon(p([(260, 196), (336, 230), (705, 621), (667, 658), (292, 264)]),
                "#aeb9be", "#e1e6df", 5)
        polygon(p([(260, 196), (705, 621), (685, 639), (311, 247)]), "#edf0e5")
        polygon(p([(635, 608), (659, 584), (743, 662), (720, 686)]),
                "#ad7e38", "#f0d390", 6)
        polygon(p([(687, 666), (708, 645), (786, 725), (765, 747)]),
                "#713742", "#ca9f5d", 6)
        line(p([(720, 682), (704, 697)]), "#cba661", 4)
        line(p([(739, 702), (724, 717)]), "#cba661", 4)
        polygon(p([(758, 748), (786, 720), (807, 742), (779, 770)]),
                "#b88b46", "#f0d390", 5)
    sword()
    sword(True)
    # A burgundy shield joins the swords and board in one readable silhouette.
    shield = [(512, 297), (667, 345), (651, 489), (608, 552), (512, 635),
              (416, 552), (373, 489), (357, 345)]
    polygon([(x, y + 12) for x, y in shield], "#10131e", "#10131e", 22)
    polygon(shield, "#b88a43", "#f0d490", 7)
    polygon([(512, 320), (644, 361), (629, 482), (590, 537), (512, 606),
             (434, 537), (395, 482), (380, 361)], "#71343f", "#d2ae6a", 3)
    polygon([(512, 336), (512, 590), (450, 533), (411, 478), (398, 374)], "#803c47")
    polygon([(512, 394), (553, 455), (512, 516), (471, 455)],
            "#d6b36d", "#f2db9e", 4)
    polygon([(512, 411), (538, 455), (512, 494), (486, 455)], "#242837")
    line([(512, 353), (512, 375)], "#e4c587", 4)
    return image.resize((size, size), Image.Resampling.LANCZOS)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    outputs = {"branding/tactical-rpg-1024.png": 1024,
               "branding/curseforge-icon.png": 400,
               "neoforge-1.21.1/src/main/resources/logo.png": 256}
    master = icon(1024)
    for name, size in outputs.items():
        image = master if size == 1024 else master.resize((size, size), Image.Resampling.LANCZOS)
        data = io.BytesIO()
        image.save(data, format="PNG", optimize=True)
        payload = data.getvalue()
        path = ROOT / name
        if args.check:
            if not path.is_file() or path.read_bytes() != payload:
                raise SystemExit(f"Missing or stale release icon: {name}")
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(payload)
        print(f"{'PASS' if args.check else 'WROTE'} {name}: {size}x{size}, "
              f"{len(payload)} bytes, SHA256 {hashlib.sha256(payload).hexdigest()}")


if __name__ == "__main__":
    main()
