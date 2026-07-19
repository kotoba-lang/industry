#!/usr/bin/env python3
"""Expand InstantSplat 2-view reconstruction to all 7 remaining scenes."""
import os, time, shutil
from gradio_client import Client, handle_file
from pathlib import Path

OUT_BASE = Path('/tmp/tanabe-3d/output/instantsplat')
OUT_BASE.mkdir(parents=True, exist_ok=True)

hf_token = open(os.path.expanduser('~/.cache/huggingface/token')).read().strip()

# Source view-1 (FLUX-Krea photoreal originals from /tmp/tanabe-3d/output/photoreal/)
PHOTOREAL = Path('/tmp/tanabe-3d/output/photoreal')

# Scenes already done: op-room
# Remaining: scada, cleanroom, chemical-yard, utility-room, server-room, exec-room, press-room
SCENES = ['scada', 'cleanroom', 'chemical-yard', 'utility-room', 'server-room', 'exec-room', 'press-room']

PROMPT_SHIFT = {
    'scada': 'Photorealistic photograph, RAW photo, Canon EOS R5, 35mm lens, ISO 800, industrial photography, '
        'wide interior of a Japanese semiconductor fab SCADA control room, three HMI workstations red alerts, '
        'rolling chairs, blue ambient light, fluorescent ceiling, '
        'photographer position SHIFTED 1.5 METERS TO THE LEFT (different viewpoint), no people, 1024x768',
    'cleanroom': 'Photorealistic, RAW photo, Canon R5, 35mm, industrial photography, '
        'Japanese semiconductor cleanroom ISO 5 yellow lithography, EUV scanners CVD clusters, HEPA ceiling, '
        'photographer position SHIFTED 1.5 METERS TO THE RIGHT, no people, 1024x768',
    'chemical-yard': 'Photorealistic, RAW photo, Canon R5, 24mm, industrial photography, midday daylight, '
        'outdoor chemical tank yard photoresist plant, stainless steel tanks, overhead pipe rack, '
        'photographer position SHIFTED 2 METERS TO THE LEFT, no people, 1024x768',
    'utility-room': 'Photorealistic, RAW photo, Canon R5, 35mm, industrial photography, '
        'Japanese factory utility room DI columns gas cabinets exhaust scrubber, fluorescent, '
        'photographer position SHIFTED 1.5 METERS TO THE LEFT, no people, 1024x768',
    'server-room': 'Photorealistic, RAW photo, Canon R5, 24mm, datacenter photography, '
        'industrial server room cold aisle 42U black racks green LED, raised floor, blue-teal mood, '
        'photographer position SHIFTED 1 METER TO THE RIGHT, no people, 1024x768',
    'exec-room': 'Photorealistic, RAW photo, Canon R5, 35mm, architectural photography, cinematic, '
        'Japanese corporate executive boardroom night, long oval table leather chairs amber pendants, '
        'photographer position SHIFTED 1.5 METERS TO THE LEFT, no people, 1024x768',
    'press-room': 'Photorealistic, RAW photo, Canon R5, 35mm, event photography, '
        'Japanese press conference room podium microphones blue curtain backdrop rows of empty chairs TV lighting, '
        'photographer position SHIFTED 1.5 METERS TO THE LEFT, no people, 1024x768',
}

print(f'[krea] connecting, HF token len={len(hf_token)}')
flux = Client('black-forest-labs/FLUX.1-Krea-dev', token=hf_token, verbose=False)

# Phase 1: Generate 2nd views
for slug in SCENES:
    out_v2 = OUT_BASE / f'{slug}-view-2.png'
    if out_v2.exists() and out_v2.stat().st_size > 20_000:
        print(f'[skip-v2] {out_v2.name}')
        continue
    print(f'\n[gen-v2] {slug}')
    t0 = time.time()
    try:
        res = flux.predict(
            prompt=PROMPT_SHIFT[slug],
            seed=77 + abs(hash(slug)) % 100, randomize_seed=False,
            width=1024, height=768, guidance_scale=4.5, num_inference_steps=28,
            api_name='/infer',
        )
        img2 = res[0] if isinstance(res, tuple) else res
        if isinstance(img2, dict) and 'path' in img2: img2 = img2['path']
        shutil.copy(img2, out_v2)
        print(f'[ok-v2 ] {out_v2.name} {out_v2.stat().st_size//1024} KB in {time.time()-t0:.1f}s')
    except Exception as e:
        print(f'[err-v2] {slug}: {type(e).__name__}: {str(e)[:300]}')

print('\n[isp] connecting to InstantSplat')
isp = Client('multimodalart/InstantSplat-zerogpu', token=hf_token, verbose=False)

# Phase 2: InstantSplat 2-view reconstruction per scene
for slug in SCENES:
    out_ply = OUT_BASE / f'instantsplat-plant-{slug}.ply'
    if out_ply.exists() and out_ply.stat().st_size > 1_000_000:
        print(f'[skip-isp] {out_ply.name}')
        continue
    v1 = PHOTOREAL / f'plant-{slug}.png'
    v2 = OUT_BASE / f'{slug}-view-2.png'
    if not v1.exists() or not v2.exists():
        print(f'[skip-isp] {slug}: missing views')
        continue
    print(f'\n[isp ] {slug}')
    t0 = time.time()
    try:
        result = isp.predict(
            inputfiles=[handle_file(str(v1)), handle_file(str(v2))],
            api_name='/process',
        )
        if isinstance(result, tuple):
            _video, ply, _m3d = result
            ply_path = ply if isinstance(ply, str) else ply.get('path', '')
            if ply_path and Path(ply_path).exists():
                shutil.copy(ply_path, out_ply)
                print(f'[ok-isp] {out_ply.name} {out_ply.stat().st_size//1024} KB in {time.time()-t0:.1f}s')
            else:
                print(f'[err-isp] {slug}: no ply path')
    except Exception as e:
        print(f'[err-isp] {slug}: {type(e).__name__}: {str(e)[:300]}')

print('\n---summary---')
for slug in ['op-room'] + SCENES:
    p = OUT_BASE / (f'instantsplat-{slug}.ply' if slug == 'op-room' else f'instantsplat-plant-{slug}.ply')
    print(f'  {p.name}: {"OK " + str(p.stat().st_size//1024) + " KB" if p.exists() else "MISSING"}')
