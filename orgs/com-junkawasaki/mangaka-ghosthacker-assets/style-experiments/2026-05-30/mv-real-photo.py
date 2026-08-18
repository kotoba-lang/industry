import os, time, shutil
from gradio_client import Client, handle_file
from pathlib import Path
from PIL import Image

OUT = Path('/tmp/tanabe-3d/output/mv-real')
OUT.mkdir(parents=True, exist_ok=True)
hf_token = open(os.path.expanduser('~/.cache/huggingface/token')).read().strip()

# Real photo of tanabe chemical plant (the original input that started this project)
REAL_SRC = '/tmp/tanabe-3d/tanabe-plant-1024.png'
if not Path(REAL_SRC).exists():
    # check other tanabe paths
    for cand in ['/tmp/tanabe-3d/tanabe-plant.png', '/tmp/tanabe-3d/output/sunny-yard.png']:
        if Path(cand).exists():
            REAL_SRC = cand
            break
print(f'[src] {REAL_SRC}: {Path(REAL_SRC).stat().st_size//1024} KB')

print('[mv] gen 6 views from real photo')
mv = Client('VAST-AI/MV-Adapter-I2MV-SDXL', token=hf_token, verbose=False)
result = mv.predict(
    prompt='photorealistic, photograph, RAW, industrial photography, sharp focus, real building',
    image=handle_file(REAL_SRC),
    do_rembg=False, seed=42, randomize_seed=False,
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
        tmp = OUT / f'view-{i+1}{Path(p).suffix}'
        shutil.copy(p, tmp)
        if Path(p).suffix == '.webp':
            pngp = OUT / f'view-{i+1}.png'
            Image.open(tmp).convert('RGB').save(pngp)
            view_paths.append(pngp)
        else:
            view_paths.append(tmp)
print(f'  {len(view_paths)} views')

print('[isp] InstantSplat on real-photo views')
isp = Client('multimodalart/InstantSplat-zerogpu', token=hf_token, verbose=False)
inputs = [handle_file(str(p)) for p in view_paths]
t0 = time.time()
result = isp.predict(inputfiles=inputs, api_name='/process')
if isinstance(result, tuple):
    _v, ply, _m = result
    p = ply if isinstance(ply, str) else ply.get('path', '')
    if p and Path(p).exists():
        out_ply = OUT / 'tanabe-real-mv6.ply'
        shutil.copy(p, out_ply)
        print(f'[ok] {out_ply.name}: {out_ply.stat().st_size//1024//1024} MB in {time.time()-t0:.1f}s')
