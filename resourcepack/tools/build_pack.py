"""Builds the CartZone resource pack (UI-3) and zips it with its SHA-1.

    py resourcepack/tools/build_pack.py

Contents, all original art drawn here:
- Rank badges (resourcepack/ranks/, his art) as U+E100-E104 in the default font.
- IP bar: the PINK boss bar is made invisible and its title draws a red rounded bar with a
  minecart icon (font cartzone:hud). Nothing else on the server uses a pink bar.
- Upgrade books: armor / quickcharge / sharpness textures from resourcepack/books/ (his art).
- Kill sound: resourcepack/sounds/kill.ogg (kill1 from the Mace.VIP pack), event cartzone:kill.
- Zone wall: the hexagon circle-border shader from the Mace.VIP pack, copied from
  resourcepack/zone-wall/ (see SOURCE.txt there). Item model lodestone:border.
Output: resourcepack/pack/ (the files) and cartzone-pack.zip + SHA-1 in docs/RESOURCEPACK.md.
"""
import hashlib
import json
import os
import shutil
import zipfile

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)                 # cartroyale/resourcepack
PROJECT = os.path.dirname(ROOT)              # cartroyale
OUT = os.path.join(ROOT, "pack")
ZIP = os.path.join(ROOT, "cartzone-pack.zip")
ZONE_WALL = os.path.join(ROOT, "zone-wall")

# Keep in sync with plugin/src/main/java/com/cartroyale/lobby/HudText.java
BAR_W, BAR_H, BAR_ASCENT = 150, 13, 9
ICON_W, ICON_H, ICON_ASCENT = 9, 8, 7

RED = (255, 0, 0, 255)
TNT = (232, 55, 28, 255)
DARK_RED = (140, 14, 6, 255)
DEEP = (70, 8, 4, 255)
IRON = (65, 57, 47, 255)
IRON_LIGHT = (154, 144, 133, 255)
FUSE = (255, 138, 31, 255)
POWDER = (255, 201, 60, 255)
CHAR = (20, 17, 16, 255)
WHITE = (243, 236, 223, 255)


def write_json(rel, data):
    path = os.path.join(OUT, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, indent=2, ensure_ascii=True)
        f.write("\n")


def save_png(rel, img):
    path = os.path.join(OUT, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, optimize=True)


# ---------------------------------------------------------------- shield

def shield_texture():
    """32x32. Front face 12x22 at (0,0), back 12x22 at (12,0), rim strip at (24,0), handle at (26,0)."""
    img = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # front: iron rim, TNT face, darker lower half, minecart emblem, crack
    d.rectangle([0, 0, 11, 21], fill=IRON)
    d.rectangle([1, 1, 10, 20], fill=TNT)
    d.rectangle([1, 11, 10, 20], fill=DARK_RED)
    d.line([1, 1, 10, 1], fill=(255, 110, 80, 255))           # top highlight
    # emblem: a small cart body with two wheels
    d.rectangle([3, 7, 8, 10], fill=CHAR)
    d.rectangle([4, 8, 7, 9], fill=FUSE)
    d.point([(4, 11), (7, 11)], fill=IRON_LIGHT)
    # the crack that says "breaks once"
    for x, y in [(6, 2), (6, 3), (5, 4), (5, 5), (6, 6), (7, 12), (6, 13), (6, 14), (5, 15), (5, 16), (6, 17)]:
        d.point((x, y), fill=CHAR)
    # rivets
    for p in [(2, 2), (9, 2), (2, 19), (9, 19)]:
        d.point(p, fill=POWDER)
    # back: plain iron with planks tone
    d.rectangle([12, 0, 23, 21], fill=IRON)
    d.rectangle([13, 1, 22, 20], fill=(86, 64, 44, 255))
    for y in range(3, 20, 4):
        d.line([13, y, 22, y], fill=(66, 48, 32, 255))
    # rim strip and handle
    d.rectangle([24, 0, 25, 21], fill=IRON)
    d.rectangle([26, 0, 29, 11], fill=(52, 44, 36, 255))
    return img


def u(px):
    """Texture pixel (32px sheet) to model UV units (0-16)."""
    return px / 2


def shield_model():
    rim = [u(24), u(0), u(25), u(22)]
    plate = {
        "from": [-6, -11, 1], "to": [6, 11, 2],
        "faces": {
            "north": {"uv": [u(0), u(0), u(12), u(22)], "texture": "#face"},
            "south": {"uv": [u(12), u(0), u(24), u(22)], "texture": "#face"},
            "east": {"uv": rim, "texture": "#face"},
            "west": {"uv": rim, "texture": "#face"},
            "up": {"uv": [u(0), u(0), u(12), u(1)], "texture": "#face"},
            "down": {"uv": [u(0), u(21), u(12), u(22)], "texture": "#face"},
        },
    }
    handle_uv = [u(26), u(0), u(28), u(6)]
    handle = {
        "from": [-1, -3, -5], "to": [1, 3, 1],
        "faces": {f: {"uv": handle_uv, "texture": "#face"} for f in ["north", "south", "east", "west", "up", "down"]},
    }
    return {
        "gui_light": "front",
        "textures": {"face": "cartzone:item/one_time_shield", "particle": "cartzone:item/one_time_shield"},
        "elements": [plate, handle],
        # Same poses as the vanilla shield so it sits in the offhand the way players expect.
        "display": {
            "thirdperson_righthand": {"rotation": [0, 90, 0], "translation": [10, 6, -4], "scale": [1, 1, 1]},
            "thirdperson_lefthand": {"rotation": [0, 90, 0], "translation": [10, 6, 12], "scale": [1, 1, 1]},
            "firstperson_righthand": {"rotation": [0, 180, 5], "translation": [-10, 2, -10], "scale": [1.25, 1.25, 1.25]},
            "firstperson_lefthand": {"rotation": [0, 180, 5], "translation": [10, 0, -10], "scale": [1.25, 1.25, 1.25]},
            "gui": {"rotation": [15, -25, -5], "translation": [2, 3, 0], "scale": [0.65, 0.65, 0.65]},
            "fixed": {"rotation": [0, 180, 0], "translation": [-4.5, 4.5, -5], "scale": [0.55, 0.55, 0.55]},
            "ground": {"rotation": [0, 0, 0], "translation": [2, 4, 2], "scale": [0.25, 0.25, 0.25]},
        },
    }


def shield_blocking_model():
    return {
        "parent": "cartzone:item/one_time_shield",
        "display": {
            "thirdperson_righthand": {"rotation": [45, 135, 0], "translation": [3.51, 11, -2], "scale": [1, 1, 1]},
            "thirdperson_lefthand": {"rotation": [45, 135, 0], "translation": [13.51, 3, 5], "scale": [1, 1, 1]},
            "firstperson_righthand": {"rotation": [0, 180, -5], "translation": [-15, 5, -11], "scale": [1.25, 1.25, 1.25]},
            "firstperson_lefthand": {"rotation": [0, 180, -5], "translation": [5, 5, -11], "scale": [1.25, 1.25, 1.25]},
            "gui": {"rotation": [15, -25, -5], "translation": [2, 3, 0], "scale": [0.65, 0.65, 0.65]},
        },
    }


# ---------------------------------------------------------------- HUD

def bar_background():
    """Black at 50% opacity, corners cut by one pixel."""
    img = Image.new("RGBA", (BAR_W, BAR_H), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    w, h = BAR_W - 1, BAR_H - 1
    d.rectangle([1, 0, w - 1, h], fill=(0, 0, 0, 128))
    d.point([(0, y) for y in range(1, h)] + [(w, y) for y in range(1, h)], fill=(0, 0, 0, 128))
    return img


def cart_icon():
    """His TNT minecart picture, kept at 4x (42x40) so the font scales it down cleanly to 10 px."""
    return Image.open(os.path.join(ROOT, "ranks", "cart_icon.png")).convert("RGBA")


# Zone boss bar: a row of hexagon cells drawn where the (invisible PINK) bar would be.
HEX_ROWS = [1, 5, 9, 9, 9, 9, 9, 9, 5, 1]   # pointy-top hexagon, 9 x 10


def hex_cell(fill, top, rim):
    """One 9x10 hexagon: rim colour on the outline, fill inside, a lighter band near the top."""
    w, h = 9, len(HEX_ROWS)
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    for y, rw in enumerate(HEX_ROWS):
        x0 = (w - rw) // 2
        prev = HEX_ROWS[y - 1] if y > 0 else 0
        nxt = HEX_ROWS[y + 1] if y < h - 1 else 0
        for x in range(x0, x0 + rw):
            d = abs(x - w // 2)
            edge = y in (0, h - 1) or x in (x0, x0 + rw - 1) or d > prev // 2 or d > nxt // 2
            img.putpixel((x, y), rim if edge else (top if y == 2 else fill))
    return img


def hex_cells():
    return {
        "hex_full.png": hex_cell((214, 32, 20, 235), (255, 92, 70, 235), (90, 8, 4, 235)),     # zone time left
        "hex_empty.png": hex_cell((0, 0, 0, 110), (0, 0, 0, 110), (0, 0, 0, 170)),             # time used up
        "hex_grace.png": hex_cell((52, 190, 70, 235), (130, 240, 140, 235), (12, 70, 20, 235)),  # grace period
        "hex_edge.png": hex_cell((255, 150, 40, 240), (255, 210, 120, 240), (110, 50, 6, 240)),  # the cell draining now
    }


def invisible_bar():
    return Image.new("RGBA", (182, 5), (0, 0, 0, 0))


def hud_font():
    advances = {}
    # Negative and positive spacing, powers of two. HudText.java composes any shift from these.
    for i in range(8):
        advances[chr(0xF801 + i)] = -(1 << i)
        advances[chr(0xF821 + i)] = 1 << i
    return {
        "providers": [
            {"type": "bitmap", "file": "cartzone:font/cart.png", "ascent": 8, "height": 10, "chars": [""]},
            {"type": "bitmap", "file": "cartzone:font/bar.png", "ascent": BAR_ASCENT, "height": BAR_H, "chars": [""]},
            # Hex cells sit 9 px below the title's top, exactly where the vanilla bar would be.
            {"type": "bitmap", "file": "cartzone:font/hex_full.png", "ascent": -2, "height": 10, "chars": [""]},
            {"type": "bitmap", "file": "cartzone:font/hex_empty.png", "ascent": -2, "height": 10, "chars": [""]},
            {"type": "bitmap", "file": "cartzone:font/hex_grace.png", "ascent": -2, "height": 10, "chars": [""]},
            {"type": "bitmap", "file": "cartzone:font/hex_edge.png", "ascent": -2, "height": 10, "chars": [""]},
            {"type": "space", "advances": advances},
        ]
    }


# ---------------------------------------------------------------- build

def main():
    if os.path.isdir(OUT):
        shutil.rmtree(OUT)
    write_json("pack.mcmeta", {"pack": {
        "description": "CartZone - IP bar, shield, books, zone wall",
        # 1.21.11 is format 75; 1.21.9+ reads the min/max range, older clients read pack_format.
        "pack_format": 75, "min_format": 64, "max_format": 99},
        # 26.x moved item rendering to core/item, so newer clients get the ported wall shader.
        "overlays": {"entries": [
            {"min_format": 84, "max_format": 87, "directory": "overlay_26_1"},
            {"min_format": 88, "max_format": 1000, "directory": "overlay_26"}]}})
    save_png("pack.png", Image.open(os.path.join(PROJECT, "site", "assets", "logo.png")).convert("RGBA").resize((128, 128), Image.LANCZOS))

    # The One Time Shield uses the vanilla shield look again (his call), so no shield model here.

    save_png("assets/cartzone/textures/font/bar.png", bar_background())
    for name, img in hex_cells().items():
        save_png("assets/cartzone/textures/font/" + name, img)

    # Rank badges in the DEFAULT font, so they work as plain characters anywhere: chat,
    # nametags, the tab list and the TAB plugin. Keep the chars in sync with config.yml rank-tags.
    for name, ch in [("vip", ""), ("vipplus", ""), ("host", ""), ("mod", ""), ("admin", ""), ("media", ""), ("creator", "")]:
        shutil.copyfile(os.path.join(ROOT, "ranks", name + ".png"), os.path.join(OUT, "assets", "cartzone", "textures", "font", "rank_" + name + ".png"))
    write_json("assets/minecraft/font/default.json", {"providers": [
        {"type": "bitmap", "file": "cartzone:font/rank_" + n + ".png", "ascent": 8, "height": 9, "chars": [c]}
        for n, c in [("vip", ""), ("vipplus", ""), ("host", ""), ("mod", ""), ("admin", ""), ("media", ""), ("creator", "")]]})
    save_png("assets/cartzone/textures/font/cart.png", cart_icon())
    write_json("assets/cartzone/font/hud.json", hud_font())
    save_png("assets/minecraft/textures/gui/sprites/boss_bar/pink_background.png", invisible_bar())
    save_png("assets/minecraft/textures/gui/sprites/boss_bar/pink_progress.png", invisible_bar())

    # Upgrade books: his 16x16 textures from resourcepack/books/, flat item models.
    os.makedirs(os.path.join(OUT, "assets", "cartzone", "textures", "item"), exist_ok=True)
    for name in ("armor_upgrade_book", "quickcharge_book", "sharpness_book"):
        shutil.copyfile(os.path.join(ROOT, "books", name + ".png"), os.path.join(OUT, "assets", "cartzone", "textures", "item", name + ".png"))
        write_json("assets/cartzone/models/item/" + name + ".json", {"parent": "minecraft:item/generated", "textures": {"layer0": "cartzone:item/" + name}})
        write_json("assets/cartzone/items/" + name + ".json", {"model": {"type": "minecraft:model", "model": "cartzone:item/" + name}})

    # Kill sound (kill1.ogg from the Mace.VIP pack), played to the killer as cartzone:kill.
    os.makedirs(os.path.join(OUT, "assets", "cartzone", "sounds"), exist_ok=True)
    for snd in ("kill", "purge"):
        shutil.copyfile(os.path.join(ROOT, "sounds", snd + ".ogg"), os.path.join(OUT, "assets", "cartzone", "sounds", snd + ".ogg"))
    write_json("assets/cartzone/sounds.json", {snd: {"category": "master", "sounds": ["cartzone:" + snd]} for snd in ("kill", "purge")})

    for base, _, names in os.walk(ZONE_WALL):
        for n in names:
            if n == "SOURCE.txt":
                continue
            rel = os.path.relpath(os.path.join(base, n), ZONE_WALL)
            os.makedirs(os.path.dirname(os.path.join(OUT, rel)), exist_ok=True)
            shutil.copyfile(os.path.join(base, n), os.path.join(OUT, rel))

    # Deterministic zip (fixed timestamps, sorted) so the SHA-1 only changes when content does.
    if os.path.exists(ZIP):
        os.remove(ZIP)
    with zipfile.ZipFile(ZIP, "w", zipfile.ZIP_DEFLATED) as z:
        files = []
        for base, _, names in os.walk(OUT):
            for n in names:
                full = os.path.join(base, n)
                files.append((os.path.relpath(full, OUT).replace(os.sep, "/"), full))
        for rel, full in sorted(files):
            info = zipfile.ZipInfo(rel, date_time=(2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            with open(full, "rb") as f:
                z.writestr(info, f.read())
    sha1 = hashlib.sha1(open(ZIP, "rb").read()).hexdigest()
    size = os.path.getsize(ZIP)

    doc = os.path.join(PROJECT, "plugin", "docs", "RESOURCEPACK.md")
    os.makedirs(os.path.dirname(doc), exist_ok=True)
    with open(doc, "w", encoding="utf-8", newline="\n") as f:
        f.write(f"""# Resource pack

Built by `py resourcepack/tools/build_pack.py` (needs Pillow). Output: `resourcepack/cartzone-pack.zip`.

| | |
|---|---|
| SHA-1 | `{sha1}` |
| Size | {size} bytes |
| pack_format | 75 (1.21.11), accepted range 64-99 |

## What is in it
- **One Time Shield** model (`item_model` `cartzone:one_time_shield`), with a separate blocking pose.
- **IP bar**: the pink boss bar sprites are transparent, and the bar's title draws a red rounded bar
  and a minecart icon from the `cartzone:hud` font. Only the plugin's IP bar uses a pink boss bar.
- `pack.png` is the CartZone logo.

It changes no vanilla textures other than the two pink boss bar sprites.

## Hosting (mcpacks)
1. Upload `cartzone-pack.zip` to mcpacks and copy the **direct download** link (it must download the zip itself, not a web page).
2. In `plugins/CartZone/config.yml`:
   ```yaml
   resource-pack:
     enabled: true
     url: '<the direct link>'
     sha1: '{sha1}'
     required: true
   ```
3. `/cart reload`, then rejoin. `/cart diag` shows whether the pack is configured.

Rebuilding changes the SHA-1 only if the content changed. Always paste the new SHA-1 after a rebuild,
or clients keep the old cached pack.
""")
    print(f"pack: {ZIP}\nsha1: {sha1}\nsize: {size}")


if __name__ == "__main__":
    main()
