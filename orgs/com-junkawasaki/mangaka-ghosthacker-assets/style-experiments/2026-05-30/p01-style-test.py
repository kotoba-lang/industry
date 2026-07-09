#!/usr/bin/env python3
"""ghosthacker p01 scene — comprehensive style tag test.

Scene: Nei walking into classroom 3-B at lunchtime, holding donut bag.

Tests 3 categories with stronger CFG + emphasis weighting:
  A. Artist tags only      (5 top-tier mangaka)
  B. Media tags only       (style via media descriptors — like amano-B winner)
  C. Combined (artist+media) (best chance for distinctive style)

CFG=7.0 (was 5.5) — stronger prompt adherence.
"""
from __future__ import annotations
import time, modal

# page 1 scene — Nei entering classroom
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
    "color, watercolor, photograph, 3d, sepia, photorealistic, multiple_persons, 2girls, 2boys"
)

# Media-only "styles" (like the amano-B that user liked)
MEDIA_STYLES = {
    "amano-B": (
        # User-confirmed best — gentle, thin, peaceful
        "monochrome, manga, (screentone:1.3), traditional_media, "
        "(pen_(medium):1.3), (thin_lines:1.4), gentle, soft_shading, "
        "peaceful, calm_atmosphere, (delicate_linework:1.3), "
        "simple_background, (ample_white_space:1.3)"
    ),
    "shokugeki-media": (
        "monochrome, manga, comic, (ink_(medium):1.4), "
        "(speed_lines:1.4), (motion_lines:1.3), (heavy_shading:1.3), "
        "(dramatic_angle:1.2), shounen_manga, intense_expression"
    ),
    "ishida-media": (
        "monochrome, manga, (ink_(medium):1.4), (crosshatch:1.4), "
        "(hatching:1.3), (deep_shadow:1.4), (dutch_angle:1.2), moody, "
        "dark_atmosphere, asymmetric_composition"
    ),
    "death-note-media": (
        "monochrome, manga, (ink_(medium):1.4), (crosshatch:1.5), "
        "(deep_shadow:1.4), (chiaroscuro:1.4), dramatic_lighting, "
        "thin_lines, detailed_lineart, sinister"
    ),
    "gekiga-heavy": (
        "monochrome, manga, (gekiga:1.4), (heavy_shading:1.4), "
        "(rough_lines:1.3), (dramatic_lighting:1.3), serious_face, "
        "noir_atmosphere"
    ),
}

# Artist-only (top tier candidates)
ARTIST_STYLES = {
    "araki": "(araki_hirohiko:1.3), monochrome, manga, traditional_media",
    "obata": "(obata_takeshi:1.3), monochrome, manga, traditional_media",
    "kishimoto": "(kishimoto_masashi:1.3), monochrome, manga, traditional_media",
    "ishida-artist": "(ishida_sui:1.3), monochrome, manga, traditional_media",
    "togashi": "(togashi_yoshihiro:1.3), monochrome, manga, traditional_media",
}

# Combined (artist + matching media)
COMBINED_STYLES = {
    "obata+ink": (
        "(obata_takeshi:1.3), monochrome, manga, (ink_(medium):1.3), "
        "(crosshatch:1.3), (deep_shadow:1.3), detailed_lineart, dramatic_lighting"
    ),
    "araki+heavy": (
        "(araki_hirohiko:1.3), monochrome, manga, (ink_(medium):1.3), "
        "(heavy_shading:1.3), (dramatic_angle:1.3), intense_expression"
    ),
    "amano-kozue+gentle": (
        "(amano_kozue:1.3), monochrome, manga, (screentone:1.3), "
        "(thin_lines:1.4), gentle, peaceful, ample_white_space"
    ),
    "shinohara+light": (
        "(shinohara_kenta:1.3), monochrome, manga, (thin_lines:1.3), "
        "(screentone:1.3), light_inking, comedic_style, simple_background"
    ),
}

TESTS = []
for k, p in MEDIA_STYLES.items():     TESTS.append((f"M-{k}", p))
for k, p in ARTIST_STYLES.items():    TESTS.append((f"A-{k}", p))
for k, p in COMBINED_STYLES.items():  TESTS.append((f"C-{k}", p))

if __name__ == "__main__":
    render = modal.Function.from_name("mangaka-kaizen", "render_panel_h100")
    print(f"=== ghosthacker p01 (Nei entering 3-B) — {len(TESTS)} panels ===")
    print("CFG=7.0, seed=42, emphasis weights ()")
    print()
    for tag, style_part in TESTS:
        prompt = f"{QUALITY}, {style_part}, {SCENE}"
        t0 = time.time()
        png = render.remote(prompt, NEG, width=896, height=1280,
                             steps=28, cfg=7.0, seed=42)
        dt = time.time() - t0
        out = f"/tmp/p01style-{tag}.png"
        with open(out, "wb") as f:
            f.write(png)
        print(f"  {tag:30s} → {dt:5.1f}s")
    print()
    print(f"{len(TESTS)} panels saved to /tmp/p01style-*.png")
