import os, time, shutil
from gradio_client import Client, handle_file
from pathlib import Path

OUT = Path('/tmp/tanabe-3d/output/mv-adapter')
hf_token = open(os.path.expanduser('~/.cache/huggingface/token')).read().strip()

inputs = [handle_file(str(OUT / f'view-{i}.png')) for i in range(1, 7)]
print(f'[isp-mv6] {len(inputs)} consistent views → InstantSplat')

isp = Client('multimodalart/InstantSplat-zerogpu', token=hf_token, verbose=False)
t0 = time.time()
try:
    result = isp.predict(inputfiles=inputs, api_name='/process')
    if isinstance(result, tuple):
        video, ply, m3d = result
        # save video
        if isinstance(video, dict):
            vp = video.get('video', '')
            if vp and Path(vp).exists():
                shutil.copy(vp, OUT / 'turntable-6view.mp4')
                print(f'  turntable-6view.mp4 ({(OUT/"turntable-6view.mp4").stat().st_size//1024} KB)')
        # save ply
        p = ply if isinstance(ply, str) else ply.get('path', '')
        if p and Path(p).exists():
            shutil.copy(p, OUT / 'instantsplat-op-room-mv6.ply')
            sz = (OUT/"instantsplat-op-room-mv6.ply").stat().st_size
            print(f'  instantsplat-op-room-mv6.ply ({sz//1024} KB) in {time.time()-t0:.1f}s')
        else:
            print('  [err] no ply path')
except Exception as e:
    import traceback
    print(f'[err] {type(e).__name__}: {str(e)[:300]}')
    traceback.print_exc()
