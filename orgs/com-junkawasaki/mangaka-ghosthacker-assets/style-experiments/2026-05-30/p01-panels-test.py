#!/usr/bin/env python3
"""p01 multi-panel test in 3 finalists (gekiga / togashi / F5).

Generates 4 representative panels from page 1 of ghosthacker:
  P1: wide establishing — classroom 3-B with desks
  P2: CU on Ren sleeping at window-side desk
  P3: MS on Nei entering with donut bag
  P4: insert — donut, earphone, mechanical pencil on desk

Each panel × 3 styles = 12 renders. Shows how each style handles
different shot types + characters before committing to 46p batch.
"""
from __future__ import annotations
import time, modal

QUALITY = "masterpiece, best quality, very aesthetic, absurdres"

NEG = (
    "lowres, bad anatomy, bad hands, text, error, missing fingers, "
    "extra digit, fewer digits, cropped, worst quality, low quality, "
    "normal quality, jpeg artifacts, signature, watermark, username, "
    "blurry, artist name, "
    "color, watercolor, photograph, 3d, sepia, photorealistic"
)

# 3 finalist styles (from user verdicts)
STYLES = {
    "gekiga": (
        "monochrome, manga, (gekiga:1.4), (heavy_shading:1.4), "
        "(rough_lines:1.3), (dramatic_lighting:1.3), noir_atmosphere"
    ),
    "togashi": (
        "(togashi_yoshihiro:1.3), monochrome, manga, traditional_media"
    ),
    "F5-fusion": (
        "monochrome, manga, "
        "(gekiga:1.5), (heavy_shading:1.4), (rough_lines:1.3), "
        "(dramatic_lighting:1.4), (togashi_yoshihiro:1.1), "
        "noir_atmosphere, serious_face"
    ),
}

# 4 panels from p01 (different shots, different subjects)
PANELS = {
    "P1-wide-classroom": (
        "no_humans, empty classroom, japanese highschool 3-B, "
        "wooden desks in rows, large blackboard, west window, "
        "afternoon sunlight, scattered chairs, indoors, "
        "wide angle, establishing shot, scene_focus"
    ),
    "P2-CU-ren-sleeping": (
        "1boy, solo, japanese teenage boy, "
        "messy dark hair, oversized grey hoodie, "
        "sleeping at desk, head down on arm, closed eyes, "
        "wooden classroom desk, mechanical pencil on desk, fallen earphone, "
        "close-up shot, dramatic_angle"
    ),
    "P3-MS-nei-entering": (
        "1girl, solo, japanese teenage girl, "
        "platinum blonde hair, long hair, sailor school uniform, navy uniform with red ribbon, "
        "walking forward, entering classroom from hallway, holding paper bag, "
        "confident expression, looking_forward, "
        "medium shot, classroom interior, afternoon sunlight"
    ),
    "P4-insert-donut-desk": (
        "no_humans, still_life, close-up, insert_shot, "
        "wooden classroom desk, unopened donut bag in plastic wrapper, "
        "fallen earphones with white wires, mechanical pencil rolled off the edge, "
        "afternoon light, focused composition"
    ),
}

if __name__ == "__main__":
    render = modal.Function.from_name("mangaka-kaizen", "render_panel_h100")
    print(f"=== p01 multi-panel test — 3 styles × 4 panels = 12 renders ===")
    print()
    for panel_name, panel_scene in PANELS.items():
        for style_name, style_tags in STYLES.items():
            tag = f"{panel_name}-{style_name}"
            prompt = f"{QUALITY}, {style_tags}, {panel_scene}"
            t0 = time.time()
            png = render.remote(prompt, NEG, width=896, height=1280,
                                 steps=28, cfg=7.0, seed=42)
            dt = time.time() - t0
            out = f"/tmp/p01panel-{tag}.png"
            with open(out, "wb") as f:
                f.write(png)
            print(f"  {tag:42s} → {dt:5.1f}s")
    print()
    print("12 panels saved to /tmp/p01panel-*.png")
