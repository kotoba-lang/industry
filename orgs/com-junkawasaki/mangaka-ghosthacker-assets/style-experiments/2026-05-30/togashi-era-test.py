#!/usr/bin/env python3
"""togashi era-anchor test.

User feedback: togashi is the right direction but era is ambiguous.
togashi has 4 distinct era styles in Animagine training:
  1990-1994: 幽☆遊☆白書 (YYH) — early shounen, simpler line
  1998-2010: HUNTER×HUNTER early — refined shounen
  2010-2020: HxH dark arc (Chimera Ant / Election) — more detailed, gritty
  2020+:     HxH return — sparse pen-and-ink, sketchy

Test 6 era variations on Nei entering classroom (P3):
  E1: no era (baseline)
  E2: year 1993 — YYH peak
  E3: year 1999 — HxH start
  E4: year 2010 — HxH dark arc
  E5: year 2022 — HxH return (sketchy)
  E6: + line emphasis (sharpest)
"""
from __future__ import annotations
import time, modal

SCENE_P3 = (
    "1girl, solo, japanese teenage girl, "
    "platinum blonde hair, long hair, "
    "sailor school uniform, navy uniform with red ribbon, "
    "walking forward, entering classroom from hallway, holding paper bag, "
    "confident expression, looking_forward, "
    "medium shot, classroom interior, afternoon sunlight"
)

QUALITY = "masterpiece, best quality, very aesthetic, absurdres"

NEG = (
    "lowres, bad anatomy, bad hands, text, error, missing fingers, "
    "extra digit, fewer digits, cropped, worst quality, low quality, "
    "normal quality, jpeg artifacts, signature, watermark, username, "
    "blurry, artist name, "
    "color, watercolor, photograph, 3d, sepia, photorealistic, multiple_persons"
)

TESTS = [
    ("E1-baseline",         "(togashi_yoshihiro:1.3), monochrome, manga, traditional_media"),
    ("E2-y1993-YYH",        "(togashi_yoshihiro:1.3), year 1993, monochrome, manga, traditional_media"),
    ("E3-y1999-HxH-early",  "(togashi_yoshihiro:1.3), year 1999, monochrome, manga, traditional_media"),
    ("E4-y2010-HxH-dark",   "(togashi_yoshihiro:1.3), year 2010, monochrome, manga, traditional_media"),
    ("E5-y2022-HxH-sketch", "(togashi_yoshihiro:1.3), year 2022, monochrome, manga, traditional_media"),
    ("E6-y2010-sharp",      "(togashi_yoshihiro:1.4), year 2010, monochrome, manga, traditional_media, "
                              "(scratchy_lines:1.3), (sharp_lines:1.3), (detailed_lineart:1.2)"),
]

if __name__ == "__main__":
    render = modal.Function.from_name("mangaka-kaizen", "render_panel_h100")
    print("=== togashi era-anchor test — 6 variants ===")
    print()
    for tag, style in TESTS:
        prompt = f"{QUALITY}, {style}, {SCENE_P3}"
        t0 = time.time()
        png = render.remote(prompt, NEG, width=896, height=1280,
                             steps=28, cfg=7.0, seed=42)
        dt = time.time() - t0
        out = f"/tmp/togashi-{tag}.png"
        with open(out, "wb") as f:
            f.write(png)
        print(f"  {tag:24s} → {dt:5.1f}s")
    print()
    print("6 panels saved to /tmp/togashi-*.png")
