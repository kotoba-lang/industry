#!/usr/bin/env python3
"""Generate 8 cyber-drill scenes as 2:1 equirectangular 360° panoramas via FLUX-Krea-dev."""
import os, time, shutil
from gradio_client import Client
from pathlib import Path

OUT = Path('/tmp/tanabe-3d/output/equirect')
OUT.mkdir(parents=True, exist_ok=True)

hf_token = open(os.path.expanduser('~/.cache/huggingface/token')).read().strip()

PROMPTS = {
    'op-room': '360 degree equirectangular HDR panorama, photorealistic photograph, RAW photo, full 360 view, '
        'interior of a Japanese semiconductor + chemical plant 24-7 operations center, '
        'U-shaped video wall with SCADA dashboards wrapping around, central round briefing table, '
        '6 operator chairs surrounding the table, cyan accent lighting, night shift, no people, '
        'monoscopic equirectangular projection, 2:1 aspect ratio, professional 360 photography',
    'scada': '360 degree equirectangular HDR panorama, photorealistic photograph, RAW photo, full 360 view, '
        'wide interior of a Japanese semiconductor fab SCADA control room at 02:14 night, '
        'three large HMI workstations with red alarm overlays wrapping around, blue ambient light, '
        'no people, monoscopic equirectangular projection, 2:1 aspect ratio',
    'cleanroom': '360 degree equirectangular HDR panorama, photorealistic photograph, RAW photo, full 360 view, '
        'Japanese semiconductor cleanroom interior, ISO 5 yellow-light lithography area, '
        'EUV scanners and CVD clusters wrapping around, overhead HEPA filter ceiling, no people, '
        'monoscopic equirectangular projection, 2:1 aspect ratio',
    'chemical-yard': '360 degree equirectangular HDR panorama, photorealistic photograph, RAW photo, full 360 view, '
        'outdoor chemical tank yard of a Japanese photoresist plant, large stainless steel tanks all around, '
        'overhead pipe rack, safety yellow paint stripes on concrete, midday daylight, no people, '
        'monoscopic equirectangular projection, 2:1 aspect ratio',
    'utility-room': '360 degree equirectangular HDR panorama, photorealistic photograph, RAW photo, full 360 view, '
        'Japanese factory utility room interior, ultra-pure water DI columns and gas cabinets wrapping around, '
        'exhaust scrubber piping, gray epoxy floor, fluorescent light, no people, '
        'monoscopic equirectangular projection, 2:1 aspect ratio',
    'server-room': '360 degree equirectangular HDR panorama, photorealistic photograph, RAW photo, full 360 view, '
        'industrial server room cold aisle, two rows of 42U black racks with green LED indicators wrapping around, '
        'raised floor, blue-teal mood, no people, monoscopic equirectangular projection, 2:1 aspect ratio',
    'exec-room': '360 degree equirectangular HDR panorama, photorealistic photograph, RAW photo, full 360 view, '
        'Japanese corporate executive boardroom at night, long oval table center, leather chairs all around, '
        'amber pendant lights, projection screen with red alert dashboard, wood-paneled walls, no people, '
        'monoscopic equirectangular projection, 2:1 aspect ratio, cinematic',
    'press-room': '360 degree equirectangular HDR panorama, photorealistic photograph, RAW photo, full 360 view, '
        'Japanese press conference room interior, podium with microphones center, '
        'blue curtain backdrop wrapping around, rows of empty chairs, TV lighting, no people, '
        'monoscopic equirectangular projection, 2:1 aspect ratio',
}

print(f'[krea-eq] connect, HF len={len(hf_token)}')
client = Client('black-forest-labs/FLUX.1-Krea-dev', token=hf_token, verbose=False)

for slug, prompt in PROMPTS.items():
    out_path = OUT / f'plant-{slug}.png'
    if out_path.exists() and out_path.stat().st_size > 30_000:
        print(f'[skip] {out_path.name}')
        continue
    print(f'\n[gen ] {slug}')
    t0 = time.time()
    try:
        # 2:1 ratio: 1536x768
        result = client.predict(
            prompt=prompt, seed=42 + abs(hash(slug)) % 100, randomize_seed=False,
            width=1536, height=768, guidance_scale=4.5, num_inference_steps=28,
            api_name='/infer',
        )
        img_path = result[0] if isinstance(result, tuple) else result
        if isinstance(img_path, dict) and 'path' in img_path: img_path = img_path['path']
        shutil.copy(img_path, out_path)
        print(f'[ok  ] {out_path.name} {out_path.stat().st_size // 1024} KB in {time.time()-t0:.1f}s')
    except Exception as e:
        print(f'[err ] {slug}: {type(e).__name__}: {str(e)[:300]}')

print('\n---all done---')
for slug in PROMPTS:
    p = OUT / f'plant-{slug}.png'
    print(f'  {p.name}: {"OK " + str(p.stat().st_size // 1024) + " KB" if p.exists() else "MISSING"}')
