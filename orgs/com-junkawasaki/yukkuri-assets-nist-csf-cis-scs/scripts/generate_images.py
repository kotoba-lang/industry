#!/usr/bin/env python3
"""Generate yukkuri scene BG + L/R character placeholders.

Disk space did not permit local diffusers SDXL turbo (2.6 GB+ model). Until
ComfyUI host (192.168.1.70:8188) is reachable or RW write path recovers so the
in-cluster generate_visual graph can run, this fallback paints reproducible
PIL placeholder images that convey scene mood + location, sized to match the
final 16:9 cinematic frame.

Each placeholder is marked `image_kind="placeholder"` in the manifest so a
downstream re-run can swap real SD outputs in place by ipfs_cid replacement.

Copyright invariant (CLAUDE.md §yukkuri):
  - GL-clean original "ゆきり" / "まりり" — no 東方 official naming or design.

Usage:
  python3 scripts/generate_images.py --out-dir images
"""
from __future__ import annotations

import argparse
import colorsys
import json
import subprocess
import sys
import time
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

FONT_BOLD = "/System/Library/Fonts/ヒラギノ角ゴシック W6.ttc"
FONT_REG = "/System/Library/Fonts/ヒラギノ角ゴシック W3.ttc"

# (scene_key, location_jp, action_jp, palette_top_hsv, palette_bottom_hsv, accent_rgb)
SCENES = [
    ("scene-00-sunset-shrine",
     "夕暮れの神社の境内、紅葉の絨毯",
     "L=ゆきり / R=まりり 縁側で雑談から本題へ",
     (0.04, 0.55, 1.00), (0.95, 0.45, 0.55), (255, 220, 130)),
    ("scene-01-western-library",
     "古い洋書が並ぶ書斎、机にアメリカ国旗の置物",
     "NIST CSF v2 を紹介",
     (0.10, 0.55, 0.85), (0.07, 0.65, 0.35), (255, 230, 180)),
    ("scene-02-data-center",
     "現代的なオフィスのサーバールーム、ラックの隙間にチェックリスト",
     "CIS Controls を紹介",
     (0.58, 0.85, 0.55), (0.60, 0.95, 0.18), (120, 220, 255)),
    ("scene-03-jp-gov-room",
     "日本の経産省風の会議室、机に★の評価カード",
     "SCS制度 を紹介",
     (0.13, 0.30, 0.95), (0.12, 0.50, 0.65), (255, 215, 100)),
    ("scene-04-classroom-venn",
     "黒板の前、3つの円が重なるベン図",
     "3つの違いと使い分け (mid-video surprising fact)",
     (0.42, 0.55, 0.45), (0.38, 0.75, 0.22), (235, 235, 215)),
    ("scene-05-night-shrine",
     "最初の神社の境内、夜空に星が出始める",
     "まとめ",
     (0.66, 0.85, 0.30), (0.70, 0.95, 0.10), (255, 235, 180)),
]

CHARACTERS = [
    ("char-yukiri-left",  "ゆきり",  "left  (L) — 四国めたん style_id 2",
     (200, 60, 70), (255, 240, 245)),
    ("char-mariri-right", "まりり",  "right (R) — ずんだもん style_id 3",
     (210, 170, 40), (45, 50, 65)),
]


def hsv_to_rgb(h: float, s: float, v: float) -> tuple[int, int, int]:
    r, g, b = colorsys.hsv_to_rgb(h, s, v)
    return int(r * 255), int(g * 255), int(b * 255)


def vertical_gradient(w: int, h: int, top_hsv, bottom_hsv) -> Image.Image:
    top = hsv_to_rgb(*top_hsv)
    bot = hsv_to_rgb(*bottom_hsv)
    img = Image.new("RGB", (w, h), top)
    px = img.load()
    for y in range(h):
        t = y / max(1, h - 1)
        r = int(top[0] * (1 - t) + bot[0] * t)
        g = int(top[1] * (1 - t) + bot[1] * t)
        b = int(top[2] * (1 - t) + bot[2] * t)
        for x in range(w):
            px[x, y] = (r, g, b)
    return img


def soft_vignette(img: Image.Image, strength: float = 0.35) -> Image.Image:
    w, h = img.size
    mask = Image.new("L", (w, h), 0)
    draw = ImageDraw.Draw(mask)
    margin_x = int(w * 0.05)
    margin_y = int(h * 0.05)
    draw.rounded_rectangle([margin_x, margin_y, w - margin_x, h - margin_y],
                           radius=int(min(w, h) * 0.06), fill=255)
    mask = mask.filter(ImageFilter.GaussianBlur(radius=int(min(w, h) * 0.08)))
    darker = Image.new("RGB", (w, h), (0, 0, 0))
    blended = Image.composite(img, Image.blend(img, darker, strength), mask)
    return blended


def text_box(draw: ImageDraw.ImageDraw, xy, text: str, font: ImageFont.FreeTypeFont,
             fill=(255, 255, 255), pad: int = 18,
             bg=(0, 0, 0, 150)) -> None:
    bbox = draw.textbbox(xy, text, font=font)
    box = (bbox[0] - pad, bbox[1] - pad, bbox[2] + pad, bbox[3] + pad)
    overlay = Image.new("RGBA", draw.im.size, (0, 0, 0, 0))
    od = ImageDraw.Draw(overlay)
    od.rounded_rectangle(box, radius=10, fill=bg)
    draw._image.alpha_composite(overlay)
    draw.text(xy, text, font=font, fill=fill)


def render_scene(width: int, height: int, scene_idx: int) -> Image.Image:
    key, location, action, top, bot, accent = SCENES[scene_idx]
    bg = vertical_gradient(width, height, top, bot)
    bg = bg.convert("RGBA")
    bg = soft_vignette(bg.convert("RGB"), strength=0.40).convert("RGBA")

    draw = ImageDraw.Draw(bg)
    f_index = ImageFont.truetype(FONT_BOLD, size=max(38, height // 14))
    f_loc = ImageFont.truetype(FONT_BOLD, size=max(30, height // 18))
    f_act = ImageFont.truetype(FONT_REG, size=max(22, height // 24))
    f_brand = ImageFont.truetype(FONT_REG, size=max(18, height // 30))

    # accent bar (top-left)
    bar_w = max(70, width // 12)
    draw.rectangle([0, 0, bar_w, height // 4], fill=accent)
    draw.text((bar_w // 2 - 18, height // 16), f"{scene_idx+1:02d}",
              font=f_index, fill=(20, 20, 20))

    # location text (lower-left band, semi-transparent)
    text_box(draw, (bar_w + 30, int(height * 0.62)), location,
             font=f_loc, fill=(255, 255, 255), pad=20, bg=(0, 0, 0, 170))
    text_box(draw, (bar_w + 30, int(height * 0.62) + max(50, height // 12) + 8),
             action, font=f_act, fill=(245, 245, 245), pad=14, bg=(0, 0, 0, 130))

    # corner brand
    brand = "yukkuri • NIST CSF v2 / CIS / SCS"
    draw.text((width - 14 - draw.textlength(brand, font=f_brand), height - 32),
              brand, font=f_brand, fill=(255, 255, 255, 220))
    return bg.convert("RGB")


def render_character(width: int, height: int, idx: int) -> Image.Image:
    key, name, sub, primary, secondary = CHARACTERS[idx]
    img = Image.new("RGB", (width, height), secondary)
    draw = ImageDraw.Draw(img)
    # circular silhouette
    cx, cy = width // 2, int(height * 0.40)
    r = int(min(width, height) * 0.30)
    draw.ellipse([cx - r, cy - r, cx + r, cy + r], fill=primary)
    # body trapezoid
    draw.polygon([(cx - r * 1.4, height),
                  (cx + r * 1.4, height),
                  (cx + r * 0.7, cy + r // 2),
                  (cx - r * 0.7, cy + r // 2)], fill=primary)
    # name
    f_name = ImageFont.truetype(FONT_BOLD, size=72)
    f_sub = ImageFont.truetype(FONT_REG, size=22)
    f_tag = ImageFont.truetype(FONT_REG, size=18)
    tw = draw.textlength(name, font=f_name)
    draw.text((cx - tw // 2, int(height * 0.78)), name, font=f_name,
              fill=(20, 20, 20))
    sw = draw.textlength(sub, font=f_sub)
    draw.text((cx - sw // 2, int(height * 0.90)), sub, font=f_sub,
              fill=(60, 60, 60))
    # placeholder tag
    draw.rectangle([10, 10, 220, 40], fill=(0, 0, 0, 200))
    draw.text((20, 14), "PLACEHOLDER (PIL)", font=f_tag, fill=(255, 220, 80))
    return img


def ipfs_add(path: Path) -> str:
    r = subprocess.run(
        ["ipfs", "add", "--cid-version", "1", "--quieter", str(path)],
        check=True, capture_output=True, text=True,
    )
    return r.stdout.strip()


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out-dir", type=Path, default=Path("images"))
    ap.add_argument("--manifest", type=Path, default=Path("manifest.json"))
    ap.add_argument("--bg-w", type=int, default=1024)
    ap.add_argument("--bg-h", type=int, default=576)
    ap.add_argument("--char-w", type=int, default=512)
    ap.add_argument("--char-h", type=int, default=768)
    args = ap.parse_args()

    args.out_dir.mkdir(parents=True, exist_ok=True)
    print(f"out={args.out_dir.resolve()} bg={args.bg_w}x{args.bg_h} "
          f"char={args.char_w}x{args.char_h}")

    images: list[dict] = []
    t0 = time.monotonic()
    for i, scene in enumerate(SCENES):
        key = scene[0]
        img = render_scene(args.bg_w, args.bg_h, i)
        out = args.out_dir / f"{key}.png"
        img.save(out, format="PNG", optimize=True)
        cid = ipfs_add(out)
        size = out.stat().st_size
        print(f"  [bg {i+1}/6] {key} {size/1024:.1f}KB cid={cid}")
        images.append({
            "kind": "background", "scene_index": i, "key": key,
            "location": scene[1], "action": scene[2],
            "width": args.bg_w, "height": args.bg_h,
            "path": str(out), "bytes": size, "ipfs_cid": cid,
            "image_kind": "placeholder",
            "generator": "PIL gradient + Hiragino label",
        })

    for j, char in enumerate(CHARACTERS):
        key = char[0]
        img = render_character(args.char_w, args.char_h, j)
        out = args.out_dir / f"{key}.png"
        img.save(out, format="PNG", optimize=True)
        cid = ipfs_add(out)
        size = out.stat().st_size
        side = "left" if j == 0 else "right"
        print(f"  [char {j+1}/2] {key} {size/1024:.1f}KB cid={cid}")
        images.append({
            "kind": "character", "side": side, "key": key,
            "display_name": char[1], "subtitle": char[2],
            "width": args.char_w, "height": args.char_h,
            "path": str(out), "bytes": size, "ipfs_cid": cid,
            "image_kind": "placeholder",
            "generator": "PIL silhouette + Hiragino label",
        })

    dir_result = subprocess.run(
        ["ipfs", "add", "-r", "--cid-version", "1", "--quieter", str(args.out_dir)],
        check=True, capture_output=True, text=True,
    )
    dir_cid = dir_result.stdout.strip().splitlines()[-1]

    manifest = json.loads(args.manifest.read_text(encoding="utf-8")) if args.manifest.exists() else {}
    manifest["images"] = images
    manifest["images_dir_cid"] = dir_cid
    manifest["images_generator"] = "PIL placeholders (disk full prevented diffusers SD-turbo)"

    args.manifest.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")

    total = sum(im["bytes"] for im in images)
    print(f"== done: {len(images)} images, {total/1024:.1f}KB total, "
          f"{time.monotonic()-t0:.1f}s, dir_cid={dir_cid} ==")
    return 0


if __name__ == "__main__":
    sys.exit(main())
