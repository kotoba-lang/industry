#!/usr/bin/env python3
"""ghosthacker pop-tokyo-hacker style test.

User correction: ghosthacker is NOT noir/gekiga.
It IS: pop, tokyo, culture, harajuku, hacker, cool, white-hat, suspense.

Tests artist tags that match modern urban youth + suspense + tech aesthetic.

Scene: p01 P3 (Nei entering 3-B with donut bag).
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
    "color, watercolor, photograph, 3d, sepia, photorealistic, "
    "multiple_persons, gekiga, dark_atmosphere, noir, heavy_shading"
)

# Pure artist tests
ARTIST_TESTS = [
    ("A1-asano-inio",        "(asano_inio:1.3), monochrome, manga, traditional_media"),
    ("A2-wakui-ken",         "(wakui_ken:1.3), monochrome, manga, traditional_media"),
    ("A3-obata-takeshi",     "(obata_takeshi:1.3), monochrome, manga, traditional_media"),
    ("A4-posuka-demizu",     "(posuka_demizu:1.3), monochrome, manga, traditional_media"),
    ("A5-itagaki-paru",      "(itagaki_paru:1.3), monochrome, manga, traditional_media"),
    ("A6-nihei-tsutomu",     "(nihei_tsutomu:1.3), monochrome, manga, traditional_media"),
    ("A7-ishida-sui",        "(ishida_sui:1.3), monochrome, manga, traditional_media"),
    ("A8-togashi-y2022",     "(togashi_yoshihiro:1.3), year 2022, monochrome, manga, traditional_media"),
]

# Combined: best artist candidates + pop/tokyo/hacker modifiers
COMBINED_TESTS = [
    ("C1-asano+urban",
        "(asano_inio:1.3), monochrome, manga, "
        "(urban:1.2), (modern:1.2), (youth_culture:1.2), suspense"),
    ("C2-obata+cool",
        "(obata_takeshi:1.3), monochrome, manga, "
        "(cool:1.2), (stylish:1.2), (suspense:1.3), (detailed_lineart:1.2)"),
    ("C3-wakui+harajuku",
        "(wakui_ken:1.3), monochrome, manga, "
        "(harajuku:1.2), (tokyo_street:1.2), (fashion:1.2), modern"),
    ("C4-posuka+suspense",
        "(posuka_demizu:1.3), monochrome, manga, "
        "(suspense:1.3), (mystery:1.2), (thriller:1.2), detailed"),
]

TESTS = ARTIST_TESTS + COMBINED_TESTS

if __name__ == "__main__":
    render = modal.Function.from_name("mangaka-kaizen", "render_panel_h100")
    print(f"=== pop-tokyo-hacker style test — {len(TESTS)} panels ===")
    print()
    for tag, style in TESTS:
        prompt = f"{QUALITY}, {style}, {SCENE_P3}"
        t0 = time.time()
        png = render.remote(prompt, NEG, width=896, height=1280,
                             steps=28, cfg=7.0, seed=42)
        dt = time.time() - t0
        out = f"/tmp/poptokyo-{tag}.png"
        with open(out, "wb") as f:
            f.write(png)
        print(f"  {tag:24s} → {dt:5.1f}s")
    print()
    print(f"{len(TESTS)} panels saved to /tmp/poptokyo-*.png")
