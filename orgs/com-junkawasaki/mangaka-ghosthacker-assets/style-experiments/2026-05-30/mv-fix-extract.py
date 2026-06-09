import os, time, shutil
from gradio_client import Client, handle_file
from pathlib import Path

OUT = Path('/tmp/tanabe-3d/output/mv-adapter')
hf_token = open(os.path.expanduser('~/.cache/huggingface/token')).read().strip()
src = '/tmp/tanabe-3d/output/photoreal/plant-op-room.png'
mv = Client('VAST-AI/MV-Adapter-I2MV-SDXL', token=hf_token, verbose=False)

print('[mv] re-running and extracting all 6 views properly')
result = mv.predict(
    prompt='photorealistic, photograph, RAW, industrial photography, sharp focus',
    image=handle_file(src),
    do_rembg=False, seed=42, randomize_seed=False,
    guidance_scale=3.0, num_inference_steps=30,
    reference_conditioning_scale=1.0,
    negative_prompt='anime, illustration, cartoon, painting, blurry',
    api_name='/infer',
)
gallery, preproc, seed = result
print(f'gallery {len(gallery)} items')
for i, item in enumerate(gallery):
    p = item.get('image') if isinstance(item, dict) else item
    if isinstance(p, dict): p = p.get('path')
    if p and Path(p).exists():
        # webp may need conversion to png
        ext = Path(p).suffix
        out_path = OUT / f'view-{i+1}{ext}'
        shutil.copy(p, out_path)
        print(f'  view-{i+1}{ext}: {out_path.stat().st_size//1024} KB')
        # also convert to png for compatibility with InstantSplat
        if ext == '.webp':
            from PIL import Image
            Image.open(out_path).convert('RGB').save(OUT / f'view-{i+1}.png')
            print(f'  view-{i+1}.png converted from webp')
