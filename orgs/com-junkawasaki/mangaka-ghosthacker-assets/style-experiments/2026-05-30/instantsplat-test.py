import os, time, shutil
from gradio_client import Client, handle_file
from pathlib import Path

OUT = Path('/tmp/tanabe-3d/output/instantsplat')
OUT.mkdir(parents=True, exist_ok=True)

hf_token = open(os.path.expanduser('~/.cache/huggingface/token')).read().strip()

# 1) Get a 2nd FLUX-Krea view of op-room with slight camera rotation in prompt
print('[flux] generating 2nd view of op-room with camera-shift prompt')
flux = Client('black-forest-labs/FLUX.1-Krea-dev', token=hf_token, verbose=False)
prompt_v2 = (
    'Photorealistic photograph, RAW photo, Canon EOS R5, 35mm lens, ISO 400, '
    'industrial photography, sharp focus, '
    '24-7 operations center inside a Japanese semiconductor + chemical plant, '
    'U-shaped video wall with SCADA dashboards, central round briefing table, '
    'photographer position SHIFTED 1 METER TO THE RIGHT (different viewpoint from a forward stance), '
    'cyan accent lighting, night shift, no people, photorealism'
)
try:
    res = flux.predict(prompt=prompt_v2, seed=99, randomize_seed=False,
                        width=1024, height=768, guidance_scale=4.5, num_inference_steps=28,
                        api_name='/infer')
    img2 = res[0] if isinstance(res, tuple) else res
    if isinstance(img2, dict) and 'path' in img2: img2 = img2['path']
    shutil.copy(img2, OUT / 'view-2.png')
    print(f'[ok ] view-2.png saved ({(OUT/"view-2.png").stat().st_size//1024} KB)')
except Exception as e:
    print(f'[err] view-2 generation failed: {type(e).__name__}: {str(e)[:200]}')
    raise SystemExit(1)

# 2) Use the original photoreal op-room as view-1
src1 = '/tmp/tanabe-3d/output/photoreal/plant-op-room.png'
if not Path(src1).exists():
    src1 = '/tmp/tanabe-3d/output/plant-op-room.png'
shutil.copy(src1, OUT / 'view-1.png')
print(f'[ok ] view-1 copied from {src1}')

# 3) Try InstantSplat-zerogpu Space
print('[instantsplat] connecting to multimodalart/InstantSplat-zerogpu')
try:
    isp = Client('multimodalart/InstantSplat-zerogpu', token=hf_token, verbose=False)
    print('[instantsplat] api_info:', isp.view_api(return_format='dict'))
except Exception as e:
    print(f'[err] connect failed: {type(e).__name__}: {str(e)[:500]}')
