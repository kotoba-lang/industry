#!/usr/bin/env python3
"""Ren scene test — 6 finalists from poptokyo winners.

User feedback: A4 posuka + A6 nihei + C2 obata-cool are Best.
Test these + 3 next-tier in Ren CU scene from p01.

Scene: Ren sleeping at window-side desk, 3-B classroom, lunch break.
"""
from __future__ import annotations
import time, modal

SCENE_REN = (
    "1boy, solo, japanese teenage boy, "
    "messy dark hair, oversized grey hoodie, "
    "sleeping at wooden classroom desk, head down on folded arms, "
    "closed eyes, peaceful expression, "
    "wooden classroom desk, mechanical pencil rolled off edge, "
    "fallen earphones with white wires, unopened donut bag in plastic, "
    "afternoon light from west window, "
    "close-up shot, slightly dutch angle, classroom 3-B"
)

QUALITY = "masterpiece, best quality, very aesthetic, absurdres"

NEG = (
    "lowres, bad anatomy, bad hands, text, error, missing fingers, "
    "extra digit, fewer digits, cropped, worst quality, low quality, "
    "normal quality, jpeg artifacts, signature, watermark, username, "
    "blurry, artist name, "
    "color, watercolor, photograph, 3d, sepia, photorealistic, "
    "multiple_persons, gekiga, dark_atmosphere, noir, heavy_shading"
)

# 6 finalists from poptokyo verdict
TESTS = [
    # ★ Best winners
    ("R1-posuka",       "(posuka_demizu:1.3), monochrome, manga, traditional_media"),
    ("R2-nihei",        "(nihei_tsutomu:1.3), monochrome, manga, traditional_media"),
    ("R3-obata-cool",
        "(obata_takeshi:1.3), monochrome, manga, "
        "(cool:1.2), (stylish:1.2), (suspense:1.3), (detailed_lineart:1.2)"),
    # + Good top 3
    ("R4-obata-pure",   "(obata_takeshi:1.3), monochrome, manga, traditional_media"),
    ("R5-asano",        "(asano_inio:1.3), monochrome, manga, traditional_media"),
    ("R6-togashi-y2022", "(togashi_yoshihiro:1.3), year 2022, monochrome, manga, traditional_media"),
]

if __name__ == "__main__":
    render = modal.Function.from_name("mangaka-kaizen", "render_panel_h100")
    print(f"=== Ren scene test — {len(TESTS)} finalists ===")
    print()
    for tag, style in TESTS:
        prompt = f"{QUALITY}, {style}, {SCENE_REN}"
        t0 = time.time()
        png = render.remote(prompt, NEG, width=896, height=1280,
                             steps=28, cfg=7.0, seed=42)
        dt = time.time() - t0
        out = f"/tmp/ren-{tag}.png"
        with open(out, "wb") as f:
            f.write(png)
        print(f"  {tag:20s} → {dt:5.1f}s")
    print()
    print("6 panels saved to /tmp/ren-*.png")
