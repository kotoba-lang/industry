#!/usr/bin/env python3
"""Fusion test: combine M-gekiga (heavy/noir media) + A-togashi (scratchy artist).

Goal: find the highest-distinction style for ghosthacker by stacking the two winners.

Variations:
  F1: gekiga + togashi (base fusion)
  F2: gekiga + togashi + crosshatch (maximum darkness)
  F3: gekiga + togashi + thin_lines (sharpened lines)
  F4: togashi-dominant (togashi heavy + light gekiga)
  F5: gekiga-dominant (gekiga heavy + light togashi)
  F6: gekiga + togashi + obata (3-mangaka mix)

Same locked scene: ghosthacker p01 — Nei entering classroom 3-B.
"""
from __future__ import annotations
import time, modal

SCENE = (
    "1girl, solo, japanese teenage girl, "
    "platinum blonde hair, long hair, "
    "sailor school uniform, navy uniform with red ribbon, "
    "walking forward, entering classroom, holding paper bag, "
    "classroom interior, school, afternoon sunlight, "
    "wooden desks, blackboard"
)

QUALITY = "masterpiece, best quality, very aesthetic, absurdres"

NEG = (
    "lowres, bad anatomy, bad hands, text, error, missing fingers, "
    "extra digit, fewer digits, cropped, worst quality, low quality, "
    "normal quality, jpeg artifacts, signature, watermark, username, "
    "blurry, artist name, "
    "color, watercolor, photograph, 3d, sepia, photorealistic, "
    "multiple_persons, 2girls, 2boys"
)

# Reference winners for comparison anchor
REF = {
    "ref-M-gekiga": (
        "monochrome, manga, (gekiga:1.4), (heavy_shading:1.4), "
        "(rough_lines:1.3), (dramatic_lighting:1.3), serious_face, "
        "noir_atmosphere"
    ),
    "ref-A-togashi": (
        "(togashi_yoshihiro:1.3), monochrome, manga, traditional_media"
    ),
}

# Fusion variants
FUSION = {
    # F1: equal weight gekiga + togashi
    "F1-gekiga+togashi": (
        "(togashi_yoshihiro:1.2), monochrome, manga, "
        "(gekiga:1.3), (heavy_shading:1.3), (rough_lines:1.2), "
        "(dramatic_lighting:1.2), noir_atmosphere"
    ),
    # F2: + crosshatch (maximum darkness)
    "F2-gekiga+togashi+xhatch": (
        "(togashi_yoshihiro:1.2), monochrome, manga, "
        "(gekiga:1.3), (heavy_shading:1.3), (crosshatch:1.4), "
        "(deep_shadow:1.4), (dramatic_lighting:1.3), noir"
    ),
    # F3: + thin_lines (sharpened lines, togashi-feel)
    "F3-gekiga+togashi+thin": (
        "(togashi_yoshihiro:1.3), monochrome, manga, "
        "(gekiga:1.2), (heavy_shading:1.2), (thin_lines:1.3), "
        "(sharp_lines:1.3), (dramatic_lighting:1.2), serious"
    ),
    # F4: togashi-dominant
    "F4-togashi-dom": (
        "(togashi_yoshihiro:1.4), monochrome, manga, traditional_media, "
        "(scratchy_lines:1.3), (rough_lines:1.2), (gekiga:1.1), serious"
    ),
    # F5: gekiga-dominant
    "F5-gekiga-dom": (
        "monochrome, manga, "
        "(gekiga:1.5), (heavy_shading:1.4), (rough_lines:1.3), "
        "(dramatic_lighting:1.4), (togashi_yoshihiro:1.1), "
        "noir_atmosphere, serious_face"
    ),
    # F6: 3-mangaka mix
    "F6-3mangaka": (
        "(togashi_yoshihiro:1.2), (obata_takeshi:1.1), "
        "monochrome, manga, "
        "(gekiga:1.3), (crosshatch:1.3), (deep_shadow:1.3), "
        "dramatic_lighting, noir"
    ),
}

TESTS = [(k, v) for k, v in REF.items()] + [(k, v) for k, v in FUSION.items()]

if __name__ == "__main__":
    render = modal.Function.from_name("mangaka-kaizen", "render_panel_h100")
    print(f"=== Fusion test — {len(TESTS)} panels (2 refs + 6 fusions) ===")
    print("CFG=7.0, seed=42")
    print()
    for tag, style_part in TESTS:
        prompt = f"{QUALITY}, {style_part}, {SCENE}"
        t0 = time.time()
        png = render.remote(prompt, NEG, width=896, height=1280,
                             steps=28, cfg=7.0, seed=42)
        dt = time.time() - t0
        out = f"/tmp/fusion-{tag}.png"
        with open(out, "wb") as f:
            f.write(png)
        print(f"  {tag:32s} → {dt:5.1f}s")
    print()
    print(f"{len(TESTS)} panels saved to /tmp/fusion-*.png")
