#!/usr/bin/env python3
"""Generate FLUX panoramas for ALL 7 remaining cyber-drill scenes."""
import os, time, shutil
from gradio_client import Client
from pathlib import Path

OUT = Path('/tmp/tanabe-3d/output')
OUT.mkdir(parents=True, exist_ok=True)

hf_token = open(os.path.expanduser('~/.cache/huggingface/token')).read().strip()

prompts = {
    'scada': (
        'Photorealistic interior of a Japanese semiconductor fab SCADA control room at night, '
        'three large HMI workstations with red alarm overlays on the displays, '
        'rolling chairs, blue ambient light, fluorescent ceiling, '
        'industrial photography, 1024x768, no people, sharp focus'
    ),
    'cleanroom': (
        'Photorealistic Japanese semiconductor cleanroom interior, '
        'ISO 5 yellow-light lithography area, EUV scanners and CVD clusters in rows, '
        'overhead HEPA filter ceiling, polished epoxy floor, '
        'no people, 1024x768, industrial photography'
    ),
    'chemical-yard': (
        'Photorealistic outdoor chemical tank yard of a Japanese photoresist plant, '
        'large stainless steel storage tanks with bilingual safety labels, '
        'overhead pipe rack, safety yellow paint stripes on concrete, '
        'midday daylight, no people, no clouds, 1024x768'
    ),
    'utility-room': (
        'Photorealistic Japanese factory utility room interior, '
        'rows of ultra-pure water DI columns, gas cabinets, exhaust scrubber piping, '
        'gray epoxy floor, fluorescent light, mist, no people, 1024x768'
    ),
    'server-room': (
        'Photorealistic industrial server room cold aisle, '
        'two rows of 42U black racks with green and amber LED indicators, '
        'raised floor, blue-teal mood, fine particulate haze, no people, 1024x768'
    ),
    'exec-room': (
        'Photorealistic Japanese corporate executive boardroom at night, '
        'long oval table, leather chairs, dimmed amber pendant lights, '
        'projection screen showing red alert dashboard, wood-paneled walls, '
        'no people, 1024x768, cinematic'
    ),
    'press-room': (
        'Photorealistic Japanese press conference room interior, '
        'long podium with microphones, blue curtain backdrop with company logo, '
        'rows of empty chairs, harsh tv lighting, no people yet, 1024x768'
    ),
}

print(f'[flux] connecting with HF_TOKEN length={len(hf_token)}')
client = Client('multimodalart/FLUX.1-merged', token=hf_token)

for slug, prompt in prompts.items():
    out_path = OUT / f'plant-{slug}.png'
    if out_path.exists() and out_path.stat().st_size > 30_000:
        print(f'[skip] {out_path.name} {out_path.stat().st_size // 1024} KB')
        continue
    print(f'[gen ] {slug}')
    t0 = time.time()
    try:
        result = client.predict(
            prompt=prompt,
            seed=42 + hash(slug) % 100,
            randomize_seed=False,
            width=1024,
            height=768,
            guidance_scale=3.5,
            num_inference_steps=8,
            api_name='/infer'
        )
        img_path = result[0] if isinstance(result, tuple) else result
        shutil.copy(img_path, out_path)
        print(f'[ok  ] {out_path.name} {out_path.stat().st_size // 1024} KB in {time.time()-t0:.1f}s')
    except Exception as e:
        print(f'[err ] {slug}: {type(e).__name__}: {str(e)[:200]}')

print('---done---')
for slug in prompts:
    p = OUT / f'plant-{slug}.png'
    print(f'  {p.name}: {"OK " + str(p.stat().st_size // 1024) + " KB" if p.exists() else "MISSING"}')
