#!/usr/bin/env python3

import os
import shutil
import subprocess
import tempfile
from pathlib import Path

from PIL import Image, ImageOps


ROOT = Path(__file__).resolve().parents[1]
SOURCE_ICON = Path(os.environ.get("APP_ICON_SOURCE", ROOT / "图标.jpg")).expanduser()
MOBILE_ICON_ROOT = ROOT / "mobile"
DESKTOP_ICON_ROOT = ROOT / "desktop"
ANDROID_MASTER_ICON = MOBILE_ICON_ROOT / "src-tauri/icons/android-launcher-source.png"
ANDROID_OUTPUT_ROOTS = [
    MOBILE_ICON_ROOT / "src-tauri/icons/android",
    MOBILE_ICON_ROOT / "src-tauri/gen/android/app/src/main/res",
]
ANDROID_SIZES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}
ANDROID_OUTPUT_NAMES = (
    "ic_launcher.png",
    "ic_launcher_round.png",
    "ic_launcher_foreground.png",
)
SHARED_ICON_OUTPUTS = {
    MOBILE_ICON_ROOT / "src/icon.png": 1024,
    MOBILE_ICON_ROOT / "src/icon-192.png": 192,
    MOBILE_ICON_ROOT / "src/icon-512.png": 512,
    DESKTOP_ICON_ROOT / "src/icon.png": 1024,
    DESKTOP_ICON_ROOT / "static/icon.png": 1024,
    DESKTOP_ICON_ROOT / "static/icon-192.png": 192,
    DESKTOP_ICON_ROOT / "static/icon-512.png": 512,
}
TAURI_ICON_OUTPUTS = {
    MOBILE_ICON_ROOT / "src-tauri/icons/icon.png": 1024,
    MOBILE_ICON_ROOT / "src-tauri/icons/32x32.png": 32,
    MOBILE_ICON_ROOT / "src-tauri/icons/64x64.png": 64,
    MOBILE_ICON_ROOT / "src-tauri/icons/128x128.png": 128,
    MOBILE_ICON_ROOT / "src-tauri/icons/128x128@2x.png": 256,
    DESKTOP_ICON_ROOT / "src-tauri/icons/icon.png": 1024,
    DESKTOP_ICON_ROOT / "src-tauri/icons/32x32.png": 32,
    DESKTOP_ICON_ROOT / "src-tauri/icons/64x64.png": 64,
    DESKTOP_ICON_ROOT / "src-tauri/icons/128x128.png": 128,
    DESKTOP_ICON_ROOT / "src-tauri/icons/128x128@2x.png": 256,
}
WINDOWS_STORE_OUTPUTS = {
    MOBILE_ICON_ROOT / "src-tauri/icons/Square30x30Logo.png": 30,
    MOBILE_ICON_ROOT / "src-tauri/icons/Square44x44Logo.png": 44,
    MOBILE_ICON_ROOT / "src-tauri/icons/Square71x71Logo.png": 71,
    MOBILE_ICON_ROOT / "src-tauri/icons/Square89x89Logo.png": 89,
    MOBILE_ICON_ROOT / "src-tauri/icons/Square107x107Logo.png": 107,
    MOBILE_ICON_ROOT / "src-tauri/icons/Square142x142Logo.png": 142,
    MOBILE_ICON_ROOT / "src-tauri/icons/Square150x150Logo.png": 150,
    MOBILE_ICON_ROOT / "src-tauri/icons/Square284x284Logo.png": 284,
    MOBILE_ICON_ROOT / "src-tauri/icons/Square310x310Logo.png": 310,
    MOBILE_ICON_ROOT / "src-tauri/icons/StoreLogo.png": 50,
    DESKTOP_ICON_ROOT / "src-tauri/icons/Square30x30Logo.png": 30,
    DESKTOP_ICON_ROOT / "src-tauri/icons/Square44x44Logo.png": 44,
    DESKTOP_ICON_ROOT / "src-tauri/icons/Square71x71Logo.png": 71,
    DESKTOP_ICON_ROOT / "src-tauri/icons/Square89x89Logo.png": 89,
    DESKTOP_ICON_ROOT / "src-tauri/icons/Square107x107Logo.png": 107,
    DESKTOP_ICON_ROOT / "src-tauri/icons/Square142x142Logo.png": 142,
    DESKTOP_ICON_ROOT / "src-tauri/icons/Square150x150Logo.png": 150,
    DESKTOP_ICON_ROOT / "src-tauri/icons/Square284x284Logo.png": 284,
    DESKTOP_ICON_ROOT / "src-tauri/icons/Square310x310Logo.png": 310,
    DESKTOP_ICON_ROOT / "src-tauri/icons/StoreLogo.png": 50,
}
IOS_ICON_OUTPUTS = {
    MOBILE_ICON_ROOT / "src-tauri/icons/ios/AppIcon-20x20@1x.png": 20,
    MOBILE_ICON_ROOT / "src-tauri/icons/ios/AppIcon-20x20@2x.png": 40,
    MOBILE_ICON_ROOT / "src-tauri/icons/ios/AppIcon-20x20@2x-1.png": 40,
    MOBILE_ICON_ROOT / "src-tauri/icons/ios/AppIcon-20x20@3x.png": 60,
    MOBILE_ICON_ROOT / "src-tauri/icons/ios/AppIcon-29x29@1x.png": 29,
    MOBILE_ICON_ROOT / "src-tauri/icons/ios/AppIcon-29x29@2x.png": 58,
    MOBILE_ICON_ROOT / "src-tauri/icons/ios/AppIcon-29x29@2x-1.png": 58,
    MOBILE_ICON_ROOT / "src-tauri/icons/ios/AppIcon-29x29@3x.png": 87,
    MOBILE_ICON_ROOT / "src-tauri/icons/ios/AppIcon-40x40@1x.png": 40,
    MOBILE_ICON_ROOT / "src-tauri/icons/ios/AppIcon-40x40@2x.png": 80,
    MOBILE_ICON_ROOT / "src-tauri/icons/ios/AppIcon-40x40@2x-1.png": 80,
    MOBILE_ICON_ROOT / "src-tauri/icons/ios/AppIcon-40x40@3x.png": 120,
    MOBILE_ICON_ROOT / "src-tauri/icons/ios/AppIcon-60x60@2x.png": 120,
    MOBILE_ICON_ROOT / "src-tauri/icons/ios/AppIcon-60x60@3x.png": 180,
    MOBILE_ICON_ROOT / "src-tauri/icons/ios/AppIcon-76x76@1x.png": 76,
    MOBILE_ICON_ROOT / "src-tauri/icons/ios/AppIcon-76x76@2x.png": 152,
    MOBILE_ICON_ROOT / "src-tauri/icons/ios/AppIcon-83.5x83.5@2x.png": 167,
    MOBILE_ICON_ROOT / "src-tauri/icons/ios/AppIcon-512@2x.png": 1024,
    DESKTOP_ICON_ROOT / "src-tauri/icons/ios/AppIcon-20x20@1x.png": 20,
    DESKTOP_ICON_ROOT / "src-tauri/icons/ios/AppIcon-20x20@2x.png": 40,
    DESKTOP_ICON_ROOT / "src-tauri/icons/ios/AppIcon-20x20@2x-1.png": 40,
    DESKTOP_ICON_ROOT / "src-tauri/icons/ios/AppIcon-20x20@3x.png": 60,
    DESKTOP_ICON_ROOT / "src-tauri/icons/ios/AppIcon-29x29@1x.png": 29,
    DESKTOP_ICON_ROOT / "src-tauri/icons/ios/AppIcon-29x29@2x.png": 58,
    DESKTOP_ICON_ROOT / "src-tauri/icons/ios/AppIcon-29x29@2x-1.png": 58,
    DESKTOP_ICON_ROOT / "src-tauri/icons/ios/AppIcon-29x29@3x.png": 87,
    DESKTOP_ICON_ROOT / "src-tauri/icons/ios/AppIcon-40x40@1x.png": 40,
    DESKTOP_ICON_ROOT / "src-tauri/icons/ios/AppIcon-40x40@2x.png": 80,
    DESKTOP_ICON_ROOT / "src-tauri/icons/ios/AppIcon-40x40@2x-1.png": 80,
    DESKTOP_ICON_ROOT / "src-tauri/icons/ios/AppIcon-40x40@3x.png": 120,
    DESKTOP_ICON_ROOT / "src-tauri/icons/ios/AppIcon-60x60@2x.png": 120,
    DESKTOP_ICON_ROOT / "src-tauri/icons/ios/AppIcon-60x60@3x.png": 180,
    DESKTOP_ICON_ROOT / "src-tauri/icons/ios/AppIcon-76x76@1x.png": 76,
    DESKTOP_ICON_ROOT / "src-tauri/icons/ios/AppIcon-76x76@2x.png": 152,
    DESKTOP_ICON_ROOT / "src-tauri/icons/ios/AppIcon-83.5x83.5@2x.png": 167,
    DESKTOP_ICON_ROOT / "src-tauri/icons/ios/AppIcon-512@2x.png": 1024,
}
ICO_OUTPUTS = (
    MOBILE_ICON_ROOT / "src-tauri/icons/icon.ico",
    DESKTOP_ICON_ROOT / "src-tauri/icons/icon.ico",
)
ICNS_OUTPUTS = (
    MOBILE_ICON_ROOT / "src-tauri/icons/icon.icns",
    DESKTOP_ICON_ROOT / "src-tauri/icons/icon.icns",
)
ICNS_ICONSET_SPECS = {
    "icon_16x16.png": 16,
    "icon_16x16@2x.png": 32,
    "icon_32x32.png": 32,
    "icon_32x32@2x.png": 64,
    "icon_128x128.png": 128,
    "icon_128x128@2x.png": 256,
    "icon_256x256.png": 256,
    "icon_256x256@2x.png": 512,
    "icon_512x512.png": 512,
    "icon_512x512@2x.png": 1024,
}
ICO_SIZES = [(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)]
ANDROID_INSET_SCALE = 0.82
OBSOLETE_ANDROID_OUTPUTS = (
    MOBILE_ICON_ROOT / "src-tauri/icons/android/mipmap-mdpi/ic_launcher_background_layer.png",
    MOBILE_ICON_ROOT / "src-tauri/icons/android/mipmap-hdpi/ic_launcher_background_layer.png",
    MOBILE_ICON_ROOT / "src-tauri/icons/android/mipmap-xhdpi/ic_launcher_background_layer.png",
    MOBILE_ICON_ROOT / "src-tauri/icons/android/mipmap-xxhdpi/ic_launcher_background_layer.png",
    MOBILE_ICON_ROOT / "src-tauri/icons/android/mipmap-xxxhdpi/ic_launcher_background_layer.png",
    MOBILE_ICON_ROOT / "src-tauri/gen/android/app/src/main/res/mipmap-mdpi/ic_launcher_background_layer.png",
    MOBILE_ICON_ROOT / "src-tauri/gen/android/app/src/main/res/mipmap-hdpi/ic_launcher_background_layer.png",
    MOBILE_ICON_ROOT / "src-tauri/gen/android/app/src/main/res/mipmap-xhdpi/ic_launcher_background_layer.png",
    MOBILE_ICON_ROOT / "src-tauri/gen/android/app/src/main/res/mipmap-xxhdpi/ic_launcher_background_layer.png",
    MOBILE_ICON_ROOT / "src-tauri/gen/android/app/src/main/res/mipmap-xxxhdpi/ic_launcher_background_layer.png",
)


def prepare_square_source(path: Path, size: int = 1024) -> Image.Image:
    if not path.exists():
        raise SystemExit(f"missing source icon: {path}")
    with Image.open(path) as image:
        image = ImageOps.exif_transpose(image).convert("RGBA")
        return ImageOps.fit(
            image,
            (size, size),
            method=Image.Resampling.LANCZOS,
            centering=(0.5, 0.5),
        )


def resize_icon(source: Image.Image, size: int) -> Image.Image:
    return source.resize((size, size), Image.Resampling.LANCZOS)


def render_padded_android_icon(source: Image.Image, canvas_size: int) -> Image.Image:
    canvas = Image.new("RGBA", (canvas_size, canvas_size), (0, 0, 0, 0))
    icon_size = round(canvas_size * ANDROID_INSET_SCALE)
    icon = resize_icon(source, icon_size)
    offset = (canvas_size - icon_size) // 2
    canvas.alpha_composite(icon, (offset, offset))
    return canvas


def save_png_outputs(source: Image.Image, outputs: dict[Path, int]) -> None:
    for path, size in outputs.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        resize_icon(source, size).save(path)


def save_ico_outputs(source: Image.Image) -> None:
    for path in ICO_OUTPUTS:
        path.parent.mkdir(parents=True, exist_ok=True)
        source.save(path, format="ICO", sizes=ICO_SIZES)


def save_icns_outputs(source: Image.Image) -> None:
    if shutil.which("iconutil") is None:
        raise SystemExit("missing required command: iconutil")

    for path in ICNS_OUTPUTS:
        path.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory() as tempdir:
            iconset_dir = Path(tempdir) / "AppIcon.iconset"
            iconset_dir.mkdir()
            for filename, size in ICNS_ICONSET_SPECS.items():
                resize_icon(source, size).save(iconset_dir / filename)
            subprocess.run(
                ["iconutil", "-c", "icns", str(iconset_dir), "-o", str(path)],
                check=True,
            )


def save_android_outputs(source: Image.Image) -> None:
    master_icon = render_padded_android_icon(source, 1024)
    ANDROID_MASTER_ICON.parent.mkdir(parents=True, exist_ok=True)
    master_icon.save(ANDROID_MASTER_ICON)

    for output_root in ANDROID_OUTPUT_ROOTS:
        for density, size in ANDROID_SIZES.items():
            mipmap_dir = output_root / f"mipmap-{density}"
            mipmap_dir.mkdir(parents=True, exist_ok=True)
            padded_icon = render_padded_android_icon(source, size)
            for name in ANDROID_OUTPUT_NAMES:
                padded_icon.save(mipmap_dir / name)


def remove_obsolete_outputs() -> None:
    for path in OBSOLETE_ANDROID_OUTPUTS:
        path.unlink(missing_ok=True)


def main() -> None:
    source = prepare_square_source(SOURCE_ICON)
    remove_obsolete_outputs()
    save_png_outputs(source, SHARED_ICON_OUTPUTS)
    save_png_outputs(source, TAURI_ICON_OUTPUTS)
    save_png_outputs(source, WINDOWS_STORE_OUTPUTS)
    save_png_outputs(source, IOS_ICON_OUTPUTS)
    save_ico_outputs(source)
    save_icns_outputs(source)
    save_android_outputs(source)
    print(f"generated app icon assets from {SOURCE_ICON}")


if __name__ == "__main__":
    main()
