#!/usr/bin/env python3
"""MV-Adapter: 1 image → 6 consistent views → InstantSplat → high-quality navigable 3DGS"""
import os, time, shutil
from gradio_client import Client, handle_file
from pathlib import Path

OUT = Path('/tmp/tanabe-3d/output/mv-adapter')
OUT.mkdir(parents=True, exist_ok=True)
hf_token = open(os.path.expanduser('~/.cache/huggingface/token')).read().strip()

src_image = '/tmp/tanabe-3d/output/photoreal/plant-op-room.png'

# 1) Check MV-Adapter API
print('[mv-adapter] connecting')
try:
    mv = Client('VAST-AI/MV-Adapter-I2MV-SDXL', token=hf_token, verbose=False)
    print(f'  api: {mv.view_api(return_format="str")[:400]}')
except Exception as e:
    print(f'[err] connect: {type(e).__name__}: {str(e)[:300]}')
    raise SystemExit(1)

# 2) Run multi-view generation
print(f'\n[mv-adapter] /infer on {src_image}')
t0 = time.time()
try:
    result = mv.predict(
        prompt='photorealistic, photograph, RAW, industrial photography, sharp focus',
        image=handle_file(src_image),
        do_rembg=False,
        seed=42,
        guidance_scale=3.0,
        num_inference_steps=30,
        reference_conditioning_scale=1.0,
        negative_prompt='anime, illustration, cartoon, painting',
        api_name='/infer',
    )
    print(f'[ok ] type={type(result)}, elapsed={time.time()-t0:.1f}s')
    print(f'  result repr (head): {str(result)[:600]}')
    if isinstance(result, tuple):
        for i, item in enumerate(result):
            if isinstance(item, list):
                print(f'  result[{i}] = list of {len(item)} items')
                for j, im in enumerate(item):
                    p = im.get('path') if isinstance(im, dict) else im
                    if p and Path(p).exists():
                        shutil.copy(p, OUT / f'view-{j+1}.png')
                        print(f'    view-{j+1}.png saved ({(OUT/f"view-{j+1}.png").stat().st_size//1024} KB)')
            elif isinstance(item, dict) and 'path' in item:
                p = item['path']
                shutil.copy(p, OUT / f'item-{i}.png')
                print(f'  item-{i}.png saved')
            elif isinstance(item, str) and Path(item).exists():
                shutil.copy(item, OUT / f'item-{i}.png')
                print(f'  item-{i}.png saved')
except Exception as e:
    print(f'[err] mv-adapter: {type(e).__name__}: {str(e)[:400]}')

print(f'\n[outputs in {OUT}]:')
for f in sorted(OUT.glob('*.png')):
    print(f'  {f.name}: {f.stat().st_size//1024} KB')
