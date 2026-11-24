"""Rebuild launcher resources with Pillow and rsvg-convert."""
from io import BytesIO
from pathlib import Path
import subprocess

from PIL import Image, ImageDraw, ImageOps

HERE = Path(__file__).resolve().parent
RES = HERE.parents[1] / "app/src/main/res"
RESAMPLE = Image.Resampling.LANCZOS


def save(image, resource):
    path = RES / resource
    path.parent.mkdir(exist_ok=True, parents=True)
    image.save(path, optimize=True)


crystal = Image.open(HERE / "aragonite-crystal-master-v1.png").convert("RGBA")
crystal = ImageOps.contain(crystal.crop(crystal.getbbox()), (690, 770), RESAMPLE)
sheet = Image.open(BytesIO(subprocess.check_output([
    "rsvg-convert", "-w", "500", "-h", "500", str(HERE / "sheet.svg")
]))).convert("RGBA")

art = Image.new("RGBA", (1010, 930))
art.alpha_composite(crystal, (35, 45))
art.alpha_composite(sheet, (500, 410))
art = art.crop(art.getbbox())
art.save(HERE / "papermill-preview.png", optimize=True)

# Center the visible artwork inside the adaptive icon's 66 dp safe circle.
visible = art.getchannel("A")
cx, cy = art.width / 2, art.height / 2
radius = max(((x - cx) ** 2 + (y - cy) ** 2) ** 0.5
             for y in range(art.height) for x in range(art.width) if visible.getpixel((x, y)))
scale = 128 / radius
adaptive = Image.new("RGBA", (432, 432))
mark = art.resize((round(art.width * scale), round(art.height * scale)), RESAMPLE)
adaptive.alpha_composite(mark, ((432 - mark.width) // 2, (432 - mark.height) // 2))
save(adaptive, "drawable-nodpi/ic_papermill_foreground.png")

legacy = Image.new("RGBA", (512, 512))
ImageDraw.Draw(legacy).rounded_rectangle((8, 8, 503, 503), radius=100, fill="#fffdf7")
mark = ImageOps.contain(art, (445, 445), RESAMPLE)
legacy.alpha_composite(mark, ((512 - mark.width) // 2, (512 - mark.height) // 2))
for density, size in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144),
                      ("xxxhdpi", 192)):
    save(legacy.resize((size, size), RESAMPLE), f"mipmap-{density}/ic_launcher.png")
