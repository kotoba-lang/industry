#!/usr/bin/env python3
"""4-view InstantSplat for op-room (proof of concept). Test if more views improve fidelity."""
import os, time, shutil
from gradio_client import Client, handle_file
from pathlib import Path

OUT = Path('/tmp/tanabe-3d/output/instantsplat-4view')
OUT.mkdir(parents=True, exist_ok=True)
hf_token = open(os.path.expanduser('~/.cache/huggingface/token')).read().strip()

PROMPTS_4VIEW = {
    'view1': 'centered front view of operations center',
    'view2': 'shifted 1.5 meters to the left of view 1, slight rotation',
    'view3': 'shifted 1.5 meters to the right of view 1, slight rotation',
    'view4': 'elevated camera position 0.8 meters higher, slight downward tilt',
}
BASE = ('Photorealistic photograph, RAW photo, Canon EOS R5, 35mm lens, ISO 400, '
        'industrial photography, sharp focus, '
        '24-7 industrial operations center, Japanese semiconductor plant, '
        'U-shaped video wall SCADA dashboards, central round briefing table, 6 operator chairs, '
        'cyan accent lighting, night shift, no people, photorealism, ')

print('[flux-4view] connecting')
flux = Client('black-forest-labs/FLUX.1-Krea-dev', token=hf_token, verbose=False)

for slug, prompt_suffix in PROMPTS_4VIEW.items():
    out = OUT / f'{slug}.png'
    if out.exists() and out.stat().st_size > 20_000:
        print(f'[skip] {out.name}')
        continue
    print(f'[gen ] {slug}')
    t0 = time.time()
    try:
        res = flux.predict(
            prompt=BASE + prompt_suffix,
            seed=200 + abs(hash(slug)) % 100, randomize_seed=False,
            width=1024, height=768, guidance_scale=4.5, num_inference_steps=28,
            api_name='/infer',
        )
        img = res[0] if isinstance(res, tuple) else res
        if isinstance(img, dict) and 'path' in img: img = img['path']
        shutil.copy(img, out)
        print(f'[ok  ] {out.name} {out.stat().st_size//1024} KB in {time.time()-t0:.1f}s')
    except Exception as e:
        print(f'[err ] {slug}: {type(e).__name__}: {str(e)[:200]}')

print('\n[isp] 4-view InstantSplat')
isp = Client('multimodalart/InstantSplat-zerogpu', token=hf_token, verbose=False)
inputs = [handle_file(str(OUT / f'view{i}.png')) for i in range(1, 5)]
out_ply = OUT / 'instantsplat-op-room-4view.ply'
print(f'[isp ] processing {len(inputs)} views')
t0 = time.time()
try:
    result = isp.predict(inputfiles=inputs, api_name='/process')
    if isinstance(result, tuple):
        _, ply, _ = result
        p = ply if isinstance(ply, str) else ply.get('path', '')
        if p and Path(p).exists():
            shutil.copy(p, out_ply)
            print(f'[ok  ] {out_ply.name} {out_ply.stat().st_size//1024} KB in {time.time()-t0:.1f}s')
        else:
            print(f'[err ] no ply path')
except Exception as e:
    print(f'[err ] {type(e).__name__}: {str(e)[:300]}')
