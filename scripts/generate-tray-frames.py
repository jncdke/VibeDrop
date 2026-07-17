#!/usr/bin/env python3
"""生成托盘涟漪动画帧和无连接置灰图标。

输入: desktop/src-tauri/icons/tray-icon.png (44x44 template 图标)
输出: 同目录下 tray-dim.png / tray-frame-1.png / tray-frame-2.png / tray-frame-3.png

涟漪 = 以图标中心为圆心的扩散圆环，半径渐大、透明度渐消，
配合 macOS template 渲染(只看 alpha)呈现"水滴入水"效果。
"""

from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
ICON_DIR = ROOT / "desktop" / "src-tauri" / "icons"
BASE = ICON_DIR / "tray-icon.png"

# (半径, 线宽, alpha) — 半径逐帧扩大, alpha 逐帧衰减
RIPPLE_FRAMES = [
    (12, 3.0, 200),
    (16, 2.5, 140),
    (20, 2.0, 80),
]

DIM_ALPHA_FACTOR = 0.55


def scaled_base(shrink: float) -> Image.Image:
    """把基础图标按比例缩小后居中放回原尺寸画布(涟漪帧里水滴略缩,强化动感)。"""
    base = Image.open(BASE).convert("RGBA")
    w, h = base.size
    sw, sh = int(w * shrink), int(h * shrink)
    small = base.resize((sw, sh), Image.LANCZOS)
    canvas = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    canvas.paste(small, ((w - sw) // 2, (h - sh) // 2), small)
    return canvas


def draw_ring(img: Image.Image, radius: float, width: float, alpha: int) -> None:
    # 4x 超采样画圆环再缩回，避免 44px 下锯齿
    scale = 4
    w, h = img.size
    overlay = Image.new("RGBA", (w * scale, h * scale), (0, 0, 0, 0))
    draw = ImageDraw.Draw(overlay)
    cx, cy = w * scale / 2, h * scale / 2
    r = radius * scale
    draw.ellipse(
        (cx - r, cy - r, cx + r, cy + r),
        outline=(0, 0, 0, alpha),
        width=max(1, round(width * scale)),
    )
    overlay = overlay.resize((w, h), Image.LANCZOS)
    img.alpha_composite(overlay)


def main() -> None:
    base = Image.open(BASE).convert("RGBA")

    # 置灰(降 alpha)版
    dim = base.copy()
    alpha = dim.getchannel("A").point(lambda a: int(a * DIM_ALPHA_FACTOR))
    dim.putalpha(alpha)
    dim.save(ICON_DIR / "tray-dim.png")

    # 涟漪帧: 水滴略缩 + 扩散圆环
    for i, (radius, width, ring_alpha) in enumerate(RIPPLE_FRAMES, start=1):
        frame = scaled_base(0.88)
        draw_ring(frame, radius, width, ring_alpha)
        frame.save(ICON_DIR / f"tray-frame-{i}.png")

    print(f"generated tray-dim.png + {len(RIPPLE_FRAMES)} ripple frames in {ICON_DIR}")


if __name__ == "__main__":
    main()
