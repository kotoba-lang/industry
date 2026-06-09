import numpy as np

for label, path in [('instantsplat', '/tmp/tanabe-3d/output/instantsplat/instantsplat-op-room.ply'),
                     ('pano3dgs', '/tmp/tanabe-3d/output/equirect/plant-op-room-pano3dgs-sh3.ply')]:
    f = open(path, 'rb')
    header = b''
    while True:
        c = f.read(1); header += c
        if header.endswith(b'end_header\n'): break
    text = header.decode('utf-8', 'replace')
    props = [line.split()[2] for line in text.split('\n') if line.startswith('property')]
    N = int([l.split()[2] for l in text.split('\n') if l.startswith('element vertex')][0])
    stride = len(props) * 4
    data = f.read(stride * min(N, 50000))
    arr = np.frombuffer(data, dtype=np.float32).reshape(-1, len(props))
    dc0 = props.index('f_dc_0')
    op = props.index('opacity')
    s0 = props.index('scale_0')
    rot0 = props.index('rot_0')
    print(f'\n[{label}] N={N}, props={len(props)}')
    print(f'  f_dc_0: min={arr[:, dc0].min():.2f} max={arr[:, dc0].max():.2f} mean={arr[:, dc0].mean():.2f}')
    print(f'  opacity: min={arr[:, op].min():.2f} max={arr[:, op].max():.2f} mean={arr[:, op].mean():.2f}')
    print(f'  scale_0: min={arr[:, s0].min():.2f} max={arr[:, s0].max():.2f} mean={arr[:, s0].mean():.2f}')
    print(f'  rot_0: min={arr[:, rot0].min():.2f} max={arr[:, rot0].max():.2f} mean={arr[:, rot0].mean():.2f}')
