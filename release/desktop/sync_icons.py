#!/usr/bin/env python3
"""Regenerate desktop launcher icons from the Android mipmap launcher asset."""

from __future__ import annotations

import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "composeApp/src/androidMain/res/mipmap-xxxhdpi/ic_launcher.png"
OUT_PNG = Path(__file__).resolve().parent / "icon.png"
OUT_ICO = ROOT / "launcher/icon.ico"
OUT_ICNS = ROOT / "release/macos/desktop/icon.icns"
PNG_SIZE = 512
ICO_SIZES = (16, 32, 48, 256)


def load_source() -> Image.Image:
    if not SOURCE.is_file():
        raise SystemExit(f"Missing Android launcher icon: {SOURCE}")
    img = Image.open(SOURCE).convert("RGBA")
    if img.size != (PNG_SIZE, PNG_SIZE):
        img = img.resize((PNG_SIZE, PNG_SIZE), Image.Resampling.LANCZOS)
    return img


def write_png(img: Image.Image) -> None:
    OUT_PNG.parent.mkdir(parents=True, exist_ok=True)
    img.save(OUT_PNG, format="PNG", optimize=True)


def write_ico(img: Image.Image) -> None:
    frames = [img.resize((size, size), Image.Resampling.LANCZOS) for size in ICO_SIZES]
    OUT_ICO.parent.mkdir(parents=True, exist_ok=True)
    frames[0].save(
        OUT_ICO,
        format="ICO",
        sizes=[frame.size for frame in frames],
        append_images=frames[1:],
    )


def write_icns(img: Image.Image) -> None:
    try:
        import icnsutil  # type: ignore
    except ImportError as exc:
        raise SystemExit("icnsutil is required for icon.icns: pip install icnsutil") from exc

    import tempfile

    icns = icnsutil.IcnsFile()
    with tempfile.NamedTemporaryFile(suffix=".png", delete=False) as tmp_file:
        tmp_path = Path(tmp_file.name)
    try:
        img.save(tmp_path, format="PNG")
        icns.add_media(file=str(tmp_path))
    finally:
        tmp_path.unlink(missing_ok=True)
    OUT_ICNS.parent.mkdir(parents=True, exist_ok=True)
    icns.write(OUT_ICNS)


def main() -> int:
    img = load_source()
    write_png(img)
    write_ico(img)
    write_icns(img)
    print(f"Wrote {OUT_PNG.relative_to(ROOT)}")
    print(f"Wrote {OUT_ICO.relative_to(ROOT)}")
    print(f"Wrote {OUT_ICNS.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
