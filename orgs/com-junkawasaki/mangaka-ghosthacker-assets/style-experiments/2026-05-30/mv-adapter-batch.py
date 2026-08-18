#!/usr/bin/env python3
"""MV-Adapter 6-view + InstantSplat for all 7 remaining scenes."""
import os, time, shutil
from gradio_client import Client, handle_file
from pathlib import Path
from PIL import Image

OUT = Path('/tmp/tanabe-3d/output/mv-adapter')
OUT.mkdir(parents=True, exist_ok=True)
hf_token = open(os.path.expanduser('~/.cache/huggingface/token')).read().strip()

SCENES = ['scada', 'cleanroom', 'chemical-yard', 'utility-room', 'server-room', 'exec-room', 'press-room']
PHOTOREAL = Path('/tmp/tanabe-3d/output/photoreal')

print('[mv-adapter] connecting')
mv = Client('VAST-AI/MV-Adapter-I2MV-SDXL', token=hf_token, verbose=False)
print('[isp] connecting')
isp = Client('multimodalart/InstantSplat-zerogpu', token=hf_token, verbose=False)

for slug in SCENES:
    src = PHOTOREAL / f'plant-{slug}.png'
    if not src.exists():
        print(f'[skip] {slug}: no src')
        continue
    out_ply = OUT / f'mv6-instantsplat-plant-{slug}.ply'
    if out_ply.exists() and out_ply.stat().st_size > 10_000_000:
        print(f'[skip] {out_ply.name}')
        continue

    # Phase 1: MV-Adapter 6 views
    scene_dir = OUT / slug
    scene_dir.mkdir(exist_ok=True)
    print(f'\n=== {slug} ===')
    print(f'[mv6] gen 6 views from {src.name}')
    t0 = time.time()
    try:
        result = mv.predict(
            prompt='photorealistic, photograph, RAW, industrial photography, sharp focus',
            image=handle_file(str(src)),
            do_rembg=False, seed=42 + abs(hash(slug))%100, randomize_seed=False,
            guidance_scale=3.0, num_inference_steps=30,
            reference_conditioning_scale=1.0,
            negative_prompt='anime, illustration, cartoon, painting, blurry',
            api_name='/infer',
        )
        gallery, preproc, seed = result
        view_paths = []
        for i, item in enumerate(gallery):
            p = item.get('image') if isinstance(item, dict) else item
            if isinstance(p, dict): p = p.get('path')
            if p and Path(p).exists():
                ext = Path(p).suffix
                tmp = scene_dir / f'view-{i+1}{ext}'
                shutil.copy(p, tmp)
                if ext == '.webp':
                    pngp = scene_dir / f'view-{i+1}.png'
                    Image.open(tmp).convert('RGB').save(pngp)
                    view_paths.append(pngp)
                else:
                    view_paths.append(tmp)
        print(f'[mv6] {len(view_paths)} views in {time.time()-t0:.1f}s')
    except Exception as e:
        print(f'[err-mv6] {slug}: {type(e).__name__}: {str(e)[:200]}')
        continue

    # Phase 2: InstantSplat 6-view
    print(f'[isp] processing 6 views')
    t0 = time.time()
    try:
        inputs = [handle_file(str(p)) for p in view_paths]
        result = isp.predict(inputfiles=inputs, api_name='/process')
        if isinstance(result, tuple):
            _v, ply, _m = result
            p = ply if isinstance(ply, str) else ply.get('path', '')
            if p and Path(p).exists():
                shutil.copy(p, out_ply)
                print(f'[ok ] {out_ply.name} {out_ply.stat().st_size//(1024*1024)} MB in {time.time()-t0:.1f}s')
            else:
                print(f'[err-isp] {slug}: no ply path')
    except Exception as e:
        print(f'[err-isp] {slug}: {type(e).__name__}: {str(e)[:200]}')

print('\n---summary---')
for slug in SCENES:
    p = OUT / f'mv6-instantsplat-plant-{slug}.ply'
    print(f'  {p.name}: {"OK " + str(p.stat().st_size//(1024*1024)) + " MB" if p.exists() else "MISSING"}')
