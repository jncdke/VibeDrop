#!/usr/bin/env python3

import math
import os
import shutil
import subprocess
import tempfile
from pathlib import Path

from PIL import Image, ImageDraw, ImageOps


ROOT = Path(__file__).resolve().parents[1]
APP_ICON_SOURCE_OVERRIDE = os.environ.get("APP_ICON_SOURCE")
APP_ICON_MASTER_OUTPUT = ROOT / "图标.png"
APP_ICON_COLOR_REFERENCE = ROOT / "图标.jpg"
LEGACY_ICON_FALLBACKS = (
    ROOT / "图标_v2.png",
    ROOT / "状态栏图标.png",
)
TRAY_TEMPLATE_SOURCE = ROOT / "状态栏图标.png"
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
APPLE_XCODE_APPICON_ROOT = (
    MOBILE_ICON_ROOT / "src-tauri/gen/apple/Assets.xcassets/AppIcon.appiconset"
)
MOBILE_LAUNCHER_SOURCE_SCALE = 0.824
IOS_LAUNCHER_SOURCE_SCALE = 1.0
TRAY_TEMPLATE_CANVAS_SIZE = 44
TRAY_TEMPLATE_CONTENT_SCALE = 0.9
SHARED_ICON_OUTPUTS = {
    MOBILE_ICON_ROOT / "src/icon.png": 1024,
    MOBILE_ICON_ROOT / "src/icon-192.png": 192,
    MOBILE_ICON_ROOT / "src/icon-512.png": 512,
    DESKTOP_ICON_ROOT / "src/icon.png": 1024,
    DESKTOP_ICON_ROOT / "static/icon.png": 1024,
    DESKTOP_ICON_ROOT / "static/icon-192.png": 192,
    DESKTOP_ICON_ROOT / "static/icon-512.png": 512,
}
MOBILE_TAURI_ICON_OUTPUTS = {
    MOBILE_ICON_ROOT / "src-tauri/icons/icon.png": 1024,
    MOBILE_ICON_ROOT / "src-tauri/icons/32x32.png": 32,
    MOBILE_ICON_ROOT / "src-tauri/icons/64x64.png": 64,
    MOBILE_ICON_ROOT / "src-tauri/icons/128x128.png": 128,
    MOBILE_ICON_ROOT / "src-tauri/icons/128x128@2x.png": 256,
}
DESKTOP_TAURI_ICON_OUTPUTS = {
    DESKTOP_ICON_ROOT / "src-tauri/icons/icon.png": 1024,
    DESKTOP_ICON_ROOT / "src-tauri/icons/32x32.png": 32,
    DESKTOP_ICON_ROOT / "src-tauri/icons/64x64.png": 64,
    DESKTOP_ICON_ROOT / "src-tauri/icons/128x128.png": 128,
    DESKTOP_ICON_ROOT / "src-tauri/icons/128x128@2x.png": 256,
}
TRAY_TEMPLATE_OUTPUTS = {
    MOBILE_ICON_ROOT / "src-tauri/icons/tray-icon.png": TRAY_TEMPLATE_CANVAS_SIZE,
    DESKTOP_ICON_ROOT / "src-tauri/icons/tray-icon.png": TRAY_TEMPLATE_CANVAS_SIZE,
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
APPLE_XCODE_ICON_OUTPUTS = {
    APPLE_XCODE_APPICON_ROOT / path.name: size
    for path, size in IOS_ICON_OUTPUTS.items()
    if path.is_relative_to(MOBILE_ICON_ROOT / "src-tauri/icons/ios")
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


def build_lossless_app_icon_master(
    color_reference_path: Path, glyph_reference_path: Path, size: int = 1024
) -> Image.Image:
    color_reference = prepare_square_source(color_reference_path, size)
    glyph_reference = prepare_square_source(glyph_reference_path, size)
    background = sample_corner_background(color_reference)
    target_bbox = find_light_content_bbox(color_reference)
    alpha = ImageOps.grayscale(glyph_reference).point(lambda value: 0 if value < 8 else value)
    glyph = render_masked_symbol_into_bbox(alpha, target_bbox, size)
    master = Image.new("RGBA", (size, size), background)
    master.alpha_composite(glyph)
    return master


def find_light_content_bbox(
    source: Image.Image,
    threshold: int = 220,
) -> tuple[int, int, int, int]:
    rgb = source.convert("RGB")
    xs: list[int] = []
    ys: list[int] = []
    for y in range(rgb.height):
        for x in range(rgb.width):
            r, g, b = rgb.getpixel((x, y))
            if r > threshold and g > threshold and b > threshold:
                xs.append(x)
                ys.append(y)
    if not xs or not ys:
        raise SystemExit("unable to locate light icon content in color reference")
    return min(xs), min(ys), max(xs) + 1, max(ys) + 1


def render_masked_symbol_into_bbox(
    alpha_mask: Image.Image,
    target_bbox: tuple[int, int, int, int],
    canvas_size: int,
) -> Image.Image:
    bbox = alpha_mask.getbbox()
    if bbox is None:
        raise SystemExit("template source does not contain visible content")
    trimmed = alpha_mask.crop(bbox)
    target_width = max(1, target_bbox[2] - target_bbox[0])
    target_height = max(1, target_bbox[3] - target_bbox[1])
    resized_alpha = trimmed.resize((target_width, target_height), Image.Resampling.LANCZOS)
    canvas = Image.new("RGBA", (canvas_size, canvas_size), (255, 255, 255, 0))
    glyph = Image.new("RGBA", (target_width, target_height), (255, 255, 255, 255))
    canvas.paste(glyph, (target_bbox[0], target_bbox[1]), resized_alpha)
    return canvas


def resolve_app_icon_source() -> tuple[Image.Image, Path]:
    if APP_ICON_SOURCE_OVERRIDE:
        source_path = Path(APP_ICON_SOURCE_OVERRIDE).expanduser()
        return prepare_square_source(source_path), source_path

    if APP_ICON_COLOR_REFERENCE.exists() and TRAY_TEMPLATE_SOURCE.exists():
        master = build_lossless_app_icon_master(APP_ICON_COLOR_REFERENCE, TRAY_TEMPLATE_SOURCE)
        APP_ICON_MASTER_OUTPUT.parent.mkdir(parents=True, exist_ok=True)
        master.save(APP_ICON_MASTER_OUTPUT)
        return master, APP_ICON_MASTER_OUTPUT

    if APP_ICON_MASTER_OUTPUT.exists():
        return prepare_square_source(APP_ICON_MASTER_OUTPUT), APP_ICON_MASTER_OUTPUT

    for candidate in LEGACY_ICON_FALLBACKS:
        if candidate.exists():
            return prepare_square_source(candidate), candidate

    return prepare_square_source(APP_ICON_COLOR_REFERENCE), APP_ICON_COLOR_REFERENCE


def prepare_template_source(path: Path) -> Image.Image:
    if not path.exists():
        raise SystemExit(f"missing tray template source: {path}")
    with Image.open(path) as image:
        image = ImageOps.exif_transpose(image).convert("RGBA")
        alpha = ImageOps.grayscale(image).point(lambda value: 0 if value < 8 else value)
        template = Image.new("RGBA", image.size, (255, 255, 255, 0))
        template.putalpha(alpha)
        return template


def resize_icon(source: Image.Image, size: int) -> Image.Image:
    return source.resize((size, size), Image.Resampling.LANCZOS)


def trim_transparent_bounds(source: Image.Image) -> Image.Image:
    bbox = source.getchannel("A").getbbox()
    if bbox is None:
        return source.copy()
    return source.crop(bbox)


def sample_corner_background(source: Image.Image, inset: int = 10) -> tuple[int, int, int, int]:
    width, height = source.size
    points = (
        (inset, inset),
        (width - inset - 1, inset),
        (inset, height - inset - 1),
        (width - inset - 1, height - inset - 1),
    )
    samples = [source.getpixel(point) for point in points]
    return tuple(round(sum(color[i] for color in samples) / len(samples)) for i in range(4))


def render_inset_square_source(source: Image.Image, canvas_size: int, content_scale: float) -> Image.Image:
    background = sample_corner_background(source)
    canvas = Image.new("RGBA", (canvas_size, canvas_size), background)
    icon_size = round(canvas_size * content_scale)
    icon = resize_icon(source, icon_size)
    offset = ((canvas_size - icon_size) // 2, (canvas_size - icon_size) // 2)
    canvas.alpha_composite(icon, offset)
    return canvas


def render_tray_template_icon(source: Image.Image, canvas_size: int, content_scale: float) -> Image.Image:
    trimmed = trim_transparent_bounds(source)
    target_max = max(1, round(canvas_size * content_scale))
    scale = target_max / max(trimmed.size)
    icon_width = max(1, round(trimmed.width * scale))
    icon_height = max(1, round(trimmed.height * scale))
    icon = trimmed.resize((icon_width, icon_height), Image.Resampling.LANCZOS)
    canvas = Image.new("RGBA", (canvas_size, canvas_size), (255, 255, 255, 0))
    offset = ((canvas_size - icon_width) // 2, (canvas_size - icon_height) // 2)
    canvas.alpha_composite(icon, offset)
    return canvas


def create_superellipse_mask(size: int, exponent: float = 4.8, oversample: int = 4) -> Image.Image:
    render_size = size * oversample
    half = render_size / 2
    radius = render_size / 2
    steps = max(160, size // 2)
    points = []
    for i in range(steps):
        theta = (math.tau * i) / steps
        cos_theta = math.cos(theta)
        sin_theta = math.sin(theta)
        x = abs(cos_theta) ** (2 / exponent) * radius * (1 if cos_theta >= 0 else -1)
        y = abs(sin_theta) ** (2 / exponent) * radius * (1 if sin_theta >= 0 else -1)
        points.append((half + x, half + y))
    mask = Image.new("L", (render_size, render_size), 0)
    draw = ImageDraw.Draw(mask)
    draw.polygon(points, fill=255)
    return mask.resize((size, size), Image.Resampling.LANCZOS)


def render_macos_app_icon(source: Image.Image, canvas_size: int) -> Image.Image:
    canvas = Image.new("RGBA", (canvas_size, canvas_size), (0, 0, 0, 0))
    tile_size = round(canvas_size * 0.824)
    icon_tile = resize_icon(source, tile_size)
    tile_mask = create_superellipse_mask(tile_size)
    tile_x = (canvas_size - tile_size) // 2
    tile_y = (canvas_size - tile_size) // 2
    canvas.paste(icon_tile, (tile_x, tile_y), tile_mask)
    return canvas


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


def save_icns_outputs(source: Image.Image, outputs: tuple[Path, ...]) -> None:
    if shutil.which("iconutil") is None:
        raise SystemExit("missing required command: iconutil")

    for path in outputs:
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
    source, source_label = resolve_app_icon_source()
    tray_template_source = prepare_template_source(TRAY_TEMPLATE_SOURCE)
    mobile_launcher_source = render_inset_square_source(source, 1024, MOBILE_LAUNCHER_SOURCE_SCALE)
    ios_launcher_source = render_inset_square_source(source, 1024, IOS_LAUNCHER_SOURCE_SCALE)
    macos_source = render_macos_app_icon(source, 1024)
    tray_template_icon = render_tray_template_icon(
        tray_template_source,
        TRAY_TEMPLATE_CANVAS_SIZE,
        TRAY_TEMPLATE_CONTENT_SCALE,
    )
    remove_obsolete_outputs()
    save_png_outputs(source, SHARED_ICON_OUTPUTS)
    save_png_outputs(mobile_launcher_source, MOBILE_TAURI_ICON_OUTPUTS)
    save_png_outputs(macos_source, DESKTOP_TAURI_ICON_OUTPUTS)
    save_png_outputs(tray_template_icon, TRAY_TEMPLATE_OUTPUTS)
    save_png_outputs(source, WINDOWS_STORE_OUTPUTS)
    save_png_outputs(ios_launcher_source, IOS_ICON_OUTPUTS)
    if (APPLE_XCODE_APPICON_ROOT / "Contents.json").exists():
        save_png_outputs(ios_launcher_source, APPLE_XCODE_ICON_OUTPUTS)
    save_ico_outputs(source)
    save_icns_outputs(source, (MOBILE_ICON_ROOT / "src-tauri/icons/icon.icns",))
    save_icns_outputs(macos_source, (DESKTOP_ICON_ROOT / "src-tauri/icons/icon.icns",))
    save_android_outputs(mobile_launcher_source)
    print(f"generated app icon assets from {source_label}")


if __name__ == "__main__":
    main()
