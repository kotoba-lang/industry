#!/usr/bin/env python3
"""Re-generate all 8 cyber-drill panoramas with FLUX.1-Krea-dev (photoreal)."""
import os, time, shutil
from gradio_client import Client
from pathlib import Path

OUT = Path('/tmp/tanabe-3d/output/photoreal')
OUT.mkdir(parents=True, exist_ok=True)

hf_token = open(os.path.expanduser('~/.cache/huggingface/token')).read().strip()

PROMPTS = {
    'op-room': (
        'Photorealistic photograph, RAW photo, Canon EOS R5, 35mm lens, ISO 400, '
        'professional industrial photography, sharp focus, '
        '24-7 industrial operations center inside a Japanese semiconductor + chemical plant, '
        'U-shaped video wall with SCADA dashboards and CCTV grids, central round briefing table, '
        '6 operator chairs, clean cyan accent lighting, night shift atmosphere, '
        'wide-angle 16:9, no people, photorealism'
    ),
    'scada': (
        'Photorealistic photograph, RAW photo, Canon EOS R5, 35mm lens, ISO 800, '
        'industrial photography, sharp focus, photorealism, '
        'wide interior of a Japanese semiconductor fab SCADA control room at 02:14 night, '
        'three large HMI workstations with red alarm overlays on the displays, '
        'rolling chairs, blue ambient light, fluorescent ceiling, 1024x768, no people'
    ),
    'cleanroom': (
        'Photorealistic photograph, RAW photo, Canon EOS R5, 35mm lens, ISO 400, '
        'industrial photography, photorealism, sharp focus, '
        'Japanese semiconductor cleanroom interior, ISO 5 yellow-light lithography area, '
        'EUV scanners and CVD clusters in rows, overhead HEPA filter ceiling, '
        'polished epoxy floor, no people, 1024x768'
    ),
    'chemical-yard': (
        'Photorealistic photograph, RAW photo, Canon EOS R5, 24mm lens, ISO 200, '
        'industrial photography, photorealism, sharp focus, midday daylight, '
        'outdoor chemical tank yard of a Japanese photoresist plant, '
        'large stainless steel storage tanks with bilingual safety labels, '
        'overhead pipe rack, safety yellow paint stripes on concrete, '
        'no people, 1024x768'
    ),
    'utility-room': (
        'Photorealistic photograph, RAW photo, Canon EOS R5, 35mm lens, ISO 800, '
        'industrial photography, photorealism, sharp focus, '
        'Japanese factory utility room interior, '
        'rows of ultra-pure water DI columns, gas cabinets, exhaust scrubber piping, '
        'gray epoxy floor, fluorescent light, faint mist, no people, 1024x768'
    ),
    'server-room': (
        'Photorealistic photograph, RAW photo, Canon EOS R5, 24mm lens, ISO 800, '
        'datacenter photography, photorealism, sharp focus, '
        'industrial server room cold aisle, '
        'two rows of 42U black racks with green and amber LED indicators, '
        'raised floor, blue-teal mood, fine particulate haze, no people, 1024x768'
    ),
    'exec-room': (
        'Photorealistic photograph, RAW photo, Canon EOS R5, 35mm lens, ISO 200, '
        'architectural photography, photorealism, sharp focus, '
        'Japanese corporate executive boardroom at night, '
        'long oval table, leather chairs, dimmed amber pendant lights, '
        'projection screen showing red alert dashboard, wood-paneled walls, '
        'no people, 1024x768, cinematic'
    ),
    'press-room': (
        'Photorealistic photograph, RAW photo, Canon EOS R5, 35mm lens, ISO 800, '
        'event photography, photorealism, sharp focus, '
        'Japanese press conference room interior, '
        'long podium with microphones, blue curtain backdrop, '
        'rows of empty chairs, harsh TV lighting, no people, 1024x768'
    ),
}

print(f'[krea] connecting to black-forest-labs/FLUX.1-Krea-dev, token len={len(hf_token)}')
client = Client('black-forest-labs/FLUX.1-Krea-dev', token=hf_token, verbose=False)

for slug, prompt in PROMPTS.items():
    out_path = OUT / f'plant-{slug}.png'
    if out_path.exists() and out_path.stat().st_size > 30_000:
        print(f'[skip] {out_path.name}')
        continue
    print(f'\n[gen ] {slug}')
    t0 = time.time()
    try:
        result = client.predict(
            prompt=prompt,
            seed=42 + abs(hash(slug)) % 100,
            randomize_seed=False,
            width=1024, height=768,
            guidance_scale=4.5,
            num_inference_steps=28,
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
