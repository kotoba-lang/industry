import numpy as np
from pathlib import Path

def load_ply(path):
    f = open(path, 'rb')
    header = b''
    while True:
        c = f.read(1); header += c
        if header.endswith(b'end_header\n'): break
    text = header.decode('utf-8', 'replace')
    props = [line.split()[2] for line in text.split('\n') if line.startswith('property')]
    N = int([l.split()[2] for l in text.split('\n') if l.startswith('element vertex')][0])
    stride = len(props) * 4
    data = f.read(stride * N)
    arr = np.frombuffer(data, dtype=np.float32).reshape(N, len(props))
    return props, arr

for label, p in [('2view-WORKS', '/tmp/tanabe-3d/output/instantsplat/instantsplat-op-room.ply'),
                  ('MV6-INVISIBLE', '/tmp/tanabe-3d/output/mv-adapter/instantsplat-op-room-mv6.ply')]:
    props, arr = load_ply(p)
    print(f'\n[{label}] N={arr.shape[0]}, props={len(props)}')
    # Sample stats per prop group
    for pg in ['x', 'f_dc_0', 'f_rest_0', 'f_rest_22', 'f_rest_44', 'opacity', 'scale_0', 'rot_0', 'rot_3']:
        if pg in props:
            i = props.index(pg)
            v = arr[:, i]
            print(f'  {pg:>10}: min={v.min():+.3f} max={v.max():+.3f} mean={v.mean():+.3f} std={v.std():.3f}')
