"""Inline the canonical journal CSS without external <link> support in AUI 1.2.8.

The ten owned pages use the canonical CSS only. The independently owned turn bar
receives only the marked block; its local CSS and DOM are never migrated/rewritten.
Use --check to validate no changes are required, all images resolve and IDs are unique.
"""
from pathlib import Path
from html.parser import HTMLParser
import argparse
import re

ROOT = Path(__file__).resolve().parent.parent
UI = ROOT / "ui"
START = "<!-- BEGIN TACTICAL JOURNAL THEME -->"
END = "<!-- END TACTICAL JOURNAL THEME -->"
BLOCK = re.compile(re.escape(START) + r".*?" + re.escape(END), re.S)


class Inventory(HTMLParser):
    def __init__(self):
        super().__init__()
        self.ids = []
        self.bindings = []

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if "id" in attrs:
            self.ids.append(attrs["id"])
        if tag in ("slot", "container"):
            self.bindings.append((tag, tuple(sorted(attrs.items()))))


def parsed(text):
    inventory = Inventory()
    inventory.feed(text)
    return inventory


def migrate(text, block, turn_bar):
    if BLOCK.search(text):
        return BLOCK.sub(lambda _: block, text)
    if turn_bar:
        # Insert before local overrides: the independently owned rail owns its geometry.
        return text.replace("<style>", block + "\n<style>", 1)
    text = re.sub(r"<style\b[^>]*>.*?</style>", "", text, flags=re.S)
    text = re.sub(r'<link\b[^>]*rel=["\']stylesheet["\'][^>]*>', "", text)
    text = re.sub(r'<meta name="aui-font-mode" content="[^"]*">',
                  '<meta name="aui-font-mode" content="mc">', text)
    if 'name="aui-mc-font-scale-policy"' not in text:
        text = text.replace('<meta name="aui-font-mode" content="mc">',
                            '<meta name="aui-font-mode" content="mc">\n<meta name="aui-mc-font-scale-policy" content="font-size">')
    if 'name="tactical-theme"' in text:
        text = re.sub(r'<meta name="tactical-theme" content="[^"]*">',
                      '<meta name="tactical-theme" content="gilded-journal">', text)
    else:
        text = text.replace("</head>", '<meta name="tactical-theme" content="gilded-journal">\n</head>')
    text = text.replace('class="ore-theme"', 'class="journal-test-page"')
    return text.replace("</head>", block + "\n</head>")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    css = (UI / "theme.css").read_text(encoding="utf-8")
    block = START + '\n<style data-tactical-theme="gilded-journal">\n' + css.replace('url("textures/', 'url("../textures/') + "</style>\n" + END
    pages = sorted((UI / "screens").glob("*.html"))
    pages += [UI / "overlays" / name for name in ("battle_hud.html", "battle_log.html", "battle_turn_bar.html")]
    failures = []
    for page in pages:
        text = page.read_text(encoding="utf-8")
        new = migrate(text, block, page.name == "battle_turn_bar.html")
        before, after = parsed(text), parsed(new)
        if before.ids != after.ids or before.bindings != after.bindings:
            failures.append(f"{page.name}: DOM IDs or slot/container binding changed")
        if len(after.ids) != len(set(after.ids)):
            failures.append(f"{page.name}: duplicate DOM IDs")
        if new.count(START) != 1 or new.count(END) != 1:
            failures.append(f"{page.name}: invalid theme block count")
        for reference in re.findall(r'url\(["\']?([^\)"\']+)', new):
            if not (page.parent / reference).resolve().is_file():
                failures.append(f"{page.name}: missing resource {reference}")
        if page.name != "battle_turn_bar.html":
            for required in ('name="aui-font-mode" content="mc"',
                             'name="aui-mc-font-scale-policy" content="font-size"',
                             'name="tactical-theme" content="gilded-journal"'):
                if required not in new:
                    failures.append(f"{page.name}: missing {required}")
            if "tactical-cjk" in new or "dungeons-2" in new or "!important" in new:
                failures.append(f"{page.name}: legacy font/palette or forced geometry")
        if args.check and new != text:
            failures.append(f"{page.name}: inline theme is stale")
        elif not args.check and new != text:
            page.write_text(new, encoding="utf-8")
        print(f"{'checked' if args.check else 'inlined'} {page.relative_to(ROOT)}: {len(after.ids)} IDs, {len(after.bindings)} bindings")
    if failures:
        raise SystemExit("\n".join(failures))
    print(f"PASS: {len(pages)} pages, canonical CSS, unique IDs, preserved slot/container attributes and resolved materials")


if __name__ == "__main__":
    main()
