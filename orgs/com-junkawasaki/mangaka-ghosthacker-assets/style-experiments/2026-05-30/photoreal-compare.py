#!/usr/bin/env python3
"""Generate op-room comparison samples from 3 photoreal models."""
import os, time, shutil, traceback
from gradio_client import Client
from pathlib import Path

OUT = Path('/tmp/tanabe-3d/output/compare')
OUT.mkdir(parents=True, exist_ok=True)

hf_token = open(os.path.expanduser('~/.cache/huggingface/token')).read().strip()

PROMPT = (
    'Photorealistic photograph, RAW photo, Canon EOS R5, 35mm lens, ISO 400, '
    'professional industrial photography, sharp focus, '
    '24-7 industrial operations center inside a Japanese semiconductor + chemical plant, '
    'U-shaped video wall with SCADA dashboards and CCTV grids, central round briefing table, '
    '6 operator chairs, clean cyan accent lighting, night shift atmosphere, '
    'wide-angle 16:9, no people, NO anime, NO illustration, NO cartoon, photorealism'
)
NEGATIVE = 'anime, cartoon, illustration, painting, drawing, sketch, cel shading, manga, watercolor'

models = [
    {
        'slug': 'krea',
        'space': 'prithivMLmods/FLUX.1-Krea-Image-Generation',
        'kwargs': {
            'prompt': PROMPT, 'negative_prompt': NEGATIVE,
            'use_negative_prompt': True, 'seed': 42,
            'width': 1024, 'height': 768, 'guidance_scale': 3.5,
            'randomize_seed': False, 'num_inference_steps': 20,
            'api_name': '/run',
        },
    },
    {
        'slug': 'hidream',
        'space': 'HiDream-ai/HiDream-I1-Full',
        'kwargs': {
            'prompt': PROMPT, 'negative_prompt': NEGATIVE,
            'seed': 42, 'width': 1024, 'height': 768,
            'guidance_scale': 5.0, 'num_inference_steps': 28,
            'api_name': '/predict',
        },
    },
    {
        'slug': 'sd35l',
        'space': 'stabilityai/stable-diffusion-3.5-large',
        'kwargs': {
            'prompt': PROMPT, 'negative_prompt': NEGATIVE,
            'seed': 42, 'randomize_seed': False,
            'width': 1024, 'height': 768, 'guidance_scale': 4.5,
            'num_inference_steps': 28, 'api_name': '/infer',
        },
    },
]

for m in models:
    out_path = OUT / f'op-room-{m["slug"]}.png'
    if out_path.exists() and out_path.stat().st_size > 30_000:
        print(f'[skip] {out_path.name}')
        continue
    print(f'\n=== {m["slug"]} ({m["space"]}) ===')
    t0 = time.time()
    try:
        c = Client(m['space'], token=hf_token, verbose=False)
        result = c.predict(**m['kwargs'])
        # result types: str path, tuple (path, seed), dict
        img_path = result
        if isinstance(result, tuple): img_path = result[0]
        elif isinstance(result, list): img_path = result[0]
        elif isinstance(result, dict) and 'path' in result: img_path = result['path']
        if isinstance(img_path, dict) and 'path' in img_path: img_path = img_path['path']
        shutil.copy(img_path, out_path)
        print(f'[ok  ] {out_path.name} {out_path.stat().st_size // 1024} KB in {time.time()-t0:.1f}s')
    except Exception as e:
        print(f'[err ] {m["slug"]}: {type(e).__name__}: {str(e)[:300]}')

print('\n---compare results---')
for f in sorted(OUT.glob('op-room-*.png')):
    print(f'  {f.name}: {f.stat().st_size // 1024} KB')
