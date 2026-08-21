import os, time
from gradio_client import Client, handle_file
from pathlib import Path
import shutil

OUT = Path('/tmp/tanabe-3d/output/instantsplat')
hf_token = open(os.path.expanduser('~/.cache/huggingface/token')).read().strip()

view1 = OUT / 'view-1.png'
view2 = OUT / 'view-2.png'
print(f'inputs: {view1} ({view1.stat().st_size//1024} KB), {view2} ({view2.stat().st_size//1024} KB)')

print('[instantsplat] processing (zero-a10g, may take 1-5 min)')
isp = Client('multimodalart/InstantSplat-zerogpu', token=hf_token, verbose=True)
t0 = time.time()
try:
    result = isp.predict(
        inputfiles=[handle_file(str(view1)), handle_file(str(view2))],
        api_name='/process',
    )
    print(f'[ok ] result type: {type(result)}, elapsed: {time.time()-t0:.1f}s')
    print(f'  result repr (head): {str(result)[:600]}')
    if isinstance(result, tuple):
        video, ply, m3d = result
        if video and isinstance(video, dict):
            shutil.copy(video.get('video', ''), OUT / 'turntable.mp4')
            print(f'  video → turntable.mp4 ({(OUT/"turntable.mp4").stat().st_size//1024} KB)')
        if ply:
            ply_path = ply if isinstance(ply, str) else ply.get('path', '')
            if ply_path and Path(ply_path).exists():
                shutil.copy(ply_path, OUT / 'instantsplat-op-room.ply')
                print(f'  ply → instantsplat-op-room.ply ({(OUT/"instantsplat-op-room.ply").stat().st_size//1024} KB)')
        if m3d:
            m3d_path = m3d if isinstance(m3d, str) else m3d.get('path', '')
            if m3d_path and Path(m3d_path).exists():
                shutil.copy(m3d_path, OUT / 'instantsplat-op-room-dense.ply')
                print(f'  m3d → instantsplat-op-room-dense.ply ({(OUT/"instantsplat-op-room-dense.ply").stat().st_size//1024} KB)')
except Exception as e:
    import traceback
    print(f'[err] {type(e).__name__}: {str(e)[:400]}')
    traceback.print_exc()
