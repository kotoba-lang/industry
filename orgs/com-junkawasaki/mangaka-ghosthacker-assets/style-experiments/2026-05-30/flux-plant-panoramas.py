#!/usr/bin/env python3
"""Generate plant interior facility panoramas via HF Spaces FLUX.1-merged.
4 prompts: operation room, scada control, server room, chemical yard interior.
"""
import os, sys, time
from gradio_client import Client
from pathlib import Path

OUT = Path('/tmp/tanabe-3d/output')
OUT.mkdir(parents=True, exist_ok=True)

# HF token (optional — anonymous works with rate limits)
hf_token = os.environ.get('HF_TOKEN')
if not hf_token:
    try:
        hf_token = open(os.path.expanduser('~/.cache/huggingface/token')).read().strip()
    except FileNotFoundError:
        hf_token = None

prompts = {
    'op-room': (
        '24-7 industrial operations center inside a Japanese semiconductor + chemical plant, '
        'U-shaped video wall with SCADA dashboards and CCTV grids, central round briefing table, '
        '6 operator chairs, clean cyan accent lighting, night shift atmosphere, '
        'photorealistic, wide-angle 16:9, no people, blueprint-meets-cyberpunk mood'
    ),
    'scada-interior': (
        'Wide interior view of a semiconductor fab SCADA control room at 02:14, '
        'three large HMI workstations with red alarm overlays on the displays, '
        'rolling chairs, blue ambient light, photorealistic, 16:9'
    ),
    'server-interior': (
        'Industrial server room inside a chemical plant facility, '
        'two cold aisles of black 42U racks with green and amber LED indicators, '
        'tile floor, mist/haze, cool teal ambient, photorealistic 16:9'
    ),
    'chemical-yard-interior': (
        'Photorealistic interior view amid stainless steel chemical tanks of a Japanese photoresist plant, '
        'tanks with bilingual warning labels, overhead pipe rack, '
        'safety yellow paint stripes on floor, midday bright daylight, '
        'no people, no clouds in sky window, 16:9 wide angle'
    ),
}

# multimodalart/FLUX.1-merged endpoint
print(f'[flux] connecting to multimodalart/FLUX.1-merged …')
client = Client('multimodalart/FLUX.1-merged', hf_token=hf_token) if hf_token else Client('multimodalart/FLUX.1-merged')

for slug, prompt in prompts.items():
    out_path = OUT / f'plant-{slug}.png'
    if out_path.exists():
        print(f'[skip] {out_path.name} exists ({out_path.stat().st_size // 1024} KB)')
        continue
    print(f'[gen ] {slug} ← {prompt[:80]}…')
    t0 = time.time()
    try:
        result = client.predict(
            prompt=prompt,
            seed=42 if slug == 'op-room' else 7,
            randomize_seed=False,
            width=1024,
            height=576,
            guidance_scale=3.5,
            num_inference_steps=8,
            api_name='/infer'
        )
        # result is tuple: (image_path, seed_used)
        if isinstance(result, tuple):
            img_path = result[0]
        elif isinstance(result, dict) and 'path' in result:
            img_path = result['path']
        else:
            img_path = result
        import shutil
        shutil.copy(img_path, out_path)
        print(f'[ok  ] {out_path.name} {out_path.stat().st_size // 1024} KB in {time.time()-t0:.1f}s')
    except Exception as e:
        print(f'[err ] {slug}: {type(e).__name__}: {e}')

print('---all done---')
for slug in prompts:
    p = OUT / f'plant-{slug}.png'
    if p.exists():
        print(f'  {p.name}: {p.stat().st_size // 1024} KB')
