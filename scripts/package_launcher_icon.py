"""Resize/composite the prepared artwork into Android launcher sizes. Requires Pillow."""
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"
art = Image.open(ROOT / "branding/brain-foreground.png").convert("RGBA")


def foreground(size, fraction):
    canvas = Image.new("RGBA", (size, size))
    side = round(size * fraction)
    mark = art.resize((side, side), Image.Resampling.LANCZOS)
    canvas.alpha_composite(mark, ((size - side) // 2, (size - side) // 2))
    return canvas


# 108 dp adaptive canvas: solid artwork stays within the 66 dp safe region.
target = RES / "drawable-nodpi"
target.mkdir(exist_ok=True)
foreground(432, .65).save(target / "ic_launcher_foreground.png", optimize=True)

for density, size in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
    folder = RES / f"mipmap-{density}"
    folder.mkdir(exist_ok=True)
    for suffix, rounded in [("", False), ("_round", True)]:
        canvas = Image.new("RGBA", (512, 512), "#090D20")
        canvas.alpha_composite(foreground(512, .84))
        mask = Image.new("L", canvas.size)
        draw = ImageDraw.Draw(mask)
        if rounded:
            draw.ellipse((0, 0, 511, 511), fill=255)
        else:
            draw.rounded_rectangle((0, 0, 511, 511), radius=108, fill=255)
        canvas.putalpha(mask)
        canvas.resize((size, size), Image.Resampling.LANCZOS).save(folder / f"ic_launcher{suffix}.webp", lossless=True)

# Play listing icon has no pre-applied rounded mask or outer white margin.
store = Image.new("RGBA", (512, 512), "#090D20")
store.alpha_composite(foreground(512, .84))
store.convert("RGB").save(ROOT / "branding/play-store-icon.png", optimize=True)
