#!/usr/bin/env python3
"""Strict A/B: lock scene + composition + mood. Vary ONLY artist + era.

Goal: find which artist tag Animagine XL 4.0 actually has strong learned style for.

Same: scene/composition/mood/character/seed/CFG/steps.
Differ: ONLY artist tag (1 per cell).
"""
from __future__ import annotations
import time, modal

# Locked scene (NO mood/composition/expression tags here — those are style-specific)
LOCKED_SCENE = (
    "1boy, solo, japanese male teenager, "
    "sitting on bed, looking at smartphone, bedroom interior, indoors, "
    "smartphone screen glow, nighttime, dim lighting"
)

# Locked quality + media (B&W manga base)
QUALITY_MEDIA = (
    "masterpiece, best quality, very aesthetic, absurdres, "
    "monochrome, greyscale, manga, comic, traditional_media, ink_(medium)"
)

NEG = (
    "lowres, bad anatomy, bad hands, text, error, missing fingers, "
    "extra digit, fewer digits, cropped, worst quality, low quality, "
    "normal quality, jpeg artifacts, signature, watermark, username, "
    "blurry, artist name, "
    "color, watercolor, photograph, 3d, sepia, photorealistic, multiple_persons"
)

# Vary ONLY: artist tag + optional year
ARTIST_TESTS = [
    ("01-baseline-no-artist",  "",                                    ""),
    ("02-araki-hirohiko",      "araki_hirohiko",                      ""),
    ("03-obata-takeshi",       "obata_takeshi",                       ""),
    ("04-togashi-yoshihiro",   "togashi_yoshihiro",                   ""),
    ("05-kishimoto-masashi",   "kishimoto_masashi",                   ""),
    ("06-oda-eiichirou",       "oda_eiichirou",                       ""),
    ("07-ishida-sui",          "ishida_sui",                          ""),
    ("08-isayama-hajime",      "isayama_hajime",                      ""),
    ("09-urasawa-naoki",       "urasawa_naoki",                       ""),
    ("10-inoue-takehiko",      "inoue_takehiko",                      ""),
    ("11-amano-kozue",         "amano_kozue",                         ""),
    ("12-shinohara-kenta",     "shinohara_kenta",                     ""),
    # Era variations on best candidates
    ("13-araki-y1990",         "araki_hirohiko",                      "year 1990"),
    ("14-obata-y2010",         "obata_takeshi",                       "year 2010"),
    ("15-ishida-y2014",        "ishida_sui",                          "year 2014"),
    ("16-amano-y2005",         "amano_kozue",                         "year 2005"),
]

if __name__ == "__main__":
    render = modal.Function.from_name("mangaka-kaizen", "render_panel_h100")
    print("=== Strict artist-tag isolation test on Animagine XL 4.0 ===")
    print(f"Locked: same scene + same character + same composition + seed=42")
    print(f"Varying: ONLY artist tag (+ era where noted)")
    print()
    for tag, artist, era in ARTIST_TESTS:
        bits = [QUALITY_MEDIA]
        if era: bits.append(era)
        if artist: bits.append(artist)
        bits.append(LOCKED_SCENE)
        prompt = ", ".join(bits)
        t0 = time.time()
        png = render.remote(prompt, NEG, width=896, height=1280,
                             steps=28, cfg=5.5, seed=42)
        dt = time.time() - t0
        out = f"/tmp/artist-{tag}.png"
        with open(out, "wb") as f:
            f.write(png)
        label = artist or "(none)"
        if era: label += f" + {era}"
        print(f"  {tag:30s} → {dt:5.1f}s  [{label}]")
    print()
    print("16 panels saved to /tmp/artist-*.png")
