"""Build the web and QuPath branding exports from assets/brand sources.

Requires Pillow. If rsvg-convert is available, the 256 px mark is refreshed from SVG.
"""

from pathlib import Path
import base64
import re
import shutil
import subprocess

from PIL import Image, ImageDraw, ImageFont, ImageOps


ROOT = Path(__file__).resolve().parents[1]
BRAND = ROOT / "assets" / "brand"
SOURCE = BRAND / "source"
ICON = BRAND / "atlas-mark-256.png"


def font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont:
    candidates = (
        [Path("C:/Windows/Fonts/seguisb.ttf"), Path("C:/Windows/Fonts/arialbd.ttf")]
        if bold else
        [Path("C:/Windows/Fonts/segoeui.ttf"), Path("C:/Windows/Fonts/arial.ttf")]
    )
    candidates += [
        Path("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf") if bold
        else Path("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf")
    ]
    for path in candidates:
        if path.exists():
            return ImageFont.truetype(str(path), size)
    raise RuntimeError("No supported system font found for the social image")


def build_icons() -> None:
    renderer = shutil.which("rsvg-convert")
    if renderer:
        subprocess.run(
            [renderer, "-w", "256", "-h", "256", "-o", str(ICON),
             str(BRAND / "atlas-mark.svg")], check=True
        )
    if not ICON.exists():
        raise RuntimeError("Render atlas-mark.svg to atlas-mark-256.png first")

    with Image.open(ICON) as original:
        mark = original.convert("RGBA")
        for size in (16, 32, 64, 128, 180):
            mark.resize((size, size), Image.Resampling.LANCZOS).save(
                BRAND / f"atlas-mark-{size}.png", optimize=True
            )
        mark.save(BRAND / "favicon.ico", sizes=[(16, 16), (32, 32), (48, 48)])
        resource = ROOT / "src" / "main" / "resources" / "icons"
        resource.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ICON, resource / "atlas-mark-256.png")


def build_web_images() -> None:
    for stem in ("atlas-hero", "atlas-stain-comparison"):
        with Image.open(SOURCE / f"{stem}-master.png") as original:
            original.convert("RGB").save(
                BRAND / f"{stem}.webp", format="WEBP", quality=86, method=6
            )


def build_social_image() -> None:
    with Image.open(SOURCE / "atlas-hero-master.png") as original:
        canvas = ImageOps.fit(original.convert("RGB"), (1200, 630), Image.Resampling.LANCZOS)
    fade = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    pixels = fade.load()
    for x in range(canvas.width):
        opacity = round(246 * max(0, 1 - x / 920) ** 1.7)
        for y in range(canvas.height):
            pixels[x, y] = (255, 251, 249, opacity)
    canvas = Image.alpha_composite(canvas.convert("RGBA"), fade)
    draw = ImageDraw.Draw(canvas)
    ink = "#35263e"
    with Image.open(ICON) as mark:
        canvas.alpha_composite(mark.convert("RGBA").resize((84, 84), Image.Resampling.LANCZOS), (60, 56))
    draw.text((166, 82), "QUPATH EXTENSION", font=font(25, True), fill="#8d557a")
    draw.text((60, 187), "Patoloji", font=font(85, True), fill=ink)
    draw.text((60, 283), "Atlası", font=font(85, True), fill=ink)
    draw.line((62, 398, 522, 398), fill="#c286a4", width=4)
    draw.text((60, 431), "Whole-slide teaching,", font=font(34), fill=ink)
    draw.text((60, 478), "directly in QuPath", font=font(34), fill=ink)
    draw.text((62, 568), "patolojiatlasi.com", font=font(24), fill="#71536c")
    canvas.convert("RGB").save(BRAND / "atlas-social-1200x630.jpg", quality=91, optimize=True, subsampling=0)


def embed_player_favicon() -> None:
    """Keep the standalone web player self-contained while using the same icon."""
    page = ROOT / "web-tour-player" / "tour-player.html"
    html = page.read_text(encoding="utf-8")
    encoded = base64.b64encode((BRAND / "atlas-mark-32.png").read_bytes()).decode("ascii")
    replacement = ("<!-- BEGIN ATLAS FAVICON -->\n"
                   f'<link rel="icon" type="image/png" href="data:image/png;base64,{encoded}">\n'
                   "<!-- END ATLAS FAVICON -->")
    pattern = r"<!-- BEGIN ATLAS FAVICON -->.*?<!-- END ATLAS FAVICON -->"
    if not re.search(pattern, html, flags=re.DOTALL):
        raise RuntimeError("Favicon markers missing from web-tour-player/tour-player.html")
    page.write_text(re.sub(pattern, replacement, html, count=1, flags=re.DOTALL), encoding="utf-8")


if __name__ == "__main__":
    build_icons()
    build_web_images()
    build_social_image()
    embed_player_favicon()
