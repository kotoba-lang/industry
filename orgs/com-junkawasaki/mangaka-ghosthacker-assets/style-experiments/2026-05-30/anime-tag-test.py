#!/usr/bin/env python3
"""Quick A/B/C test: description vs Danbooru-tag prompts on Modal H100.

Tests whether Animagine XL 4.0 reacts more strongly to Danbooru-style
artist + media tags than to descriptive English.

Generates 6 panels:
  shokugeki-B  shokugeki-C
  ishida-B     ishida-C
  amano-B      amano-C

Where:
  B = pure Danbooru tags (artist + media + content)
  C = B + year + very aesthetic (era + quality boost)

Common scene anchor: p17 Yuto in bedroom — single character, recognizable subject.
"""
from __future__ import annotations
import time, modal

SCENE = (
    "1boy, solo, japanese teen, looking_down, bedroom, indoors, sitting, "
    "smartphone, blue_light, dark_room, nighttime, focused, intense"
)

# Negative — Animagine 4.0 canonical
NEG_BASE = ("lowres, bad anatomy, bad hands, text, error, missing fingers, "
            "extra digit, fewer digits, cropped, worst quality, low quality, "
            "normal quality, jpeg artifacts, signature, watermark, username, "
            "blurry, artist name, "
            "color, watercolor, photograph, 3d, sepia, photorealistic")

# B = pure Danbooru tags
PROMPTS_B = {
    "shokugeki": (
        "masterpiece, best quality, "
        "monochrome, manga, comic, screentone, traditional_media, "
        "ink_(medium), heavy_shading, speed_lines, motion_lines, "
        "dramatic_angle, shounen_manga, intense_expression, "
        + SCENE
    ),
    "ishida": (
        "masterpiece, best quality, "
        "monochrome, manga, comic, traditional_media, ink_(medium), "
        "ishida_sui, crosshatch, hatching, deep_shadow, dutch_angle, "
        "moody, dark_atmosphere, asymmetric_composition, "
        + SCENE
    ),
    "amano": (
        "masterpiece, best quality, "
        "monochrome, manga, screentone, traditional_media, pen_(medium), "
        "thin_lines, gentle, soft_shading, peaceful, calm_atmosphere, "
        "delicate_linework, simple_background, ample_white_space, "
        + SCENE
    ),
}

# C = B + era + aesthetic boost
ERA = {"shokugeki": "year 2018", "ishida": "year 2014", "amano": "year 2005"}
PROMPTS_C = {k: f"{ERA[k]}, very aesthetic, absurdres, {v}"
              for k, v in PROMPTS_B.items()}

if __name__ == "__main__":
    render = modal.Function.from_name("mangaka-kaizen", "render_panel_h100")
    print("=== Animagine XL 4.0 Danbooru-tag A/B/C test ===")
    print()
    for style in ("shokugeki", "ishida", "amano"):
        for variant, prompts in (("B", PROMPTS_B), ("C", PROMPTS_C)):
            pr = prompts[style]
            t0 = time.time()
            png = render.remote(pr, NEG_BASE,
                                 width=896, height=1280, steps=28,
                                 cfg=5.5, seed=42)
            dt = time.time() - t0
            out = f"/tmp/danbtag-{style}-{variant}.png"
            with open(out, "wb") as f:
                f.write(png)
            print(f"  {style:10s} {variant}: {dt:5.1f}s → {out} ({len(png)//1024} KB)")
    print()
    print("Open all 6 for visual A/B/C comparison.")
