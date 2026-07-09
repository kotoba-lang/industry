import json
from pathlib import Path
import numpy as np

SCENES = ['op-room', 'plant-scada', 'plant-cleanroom', 'plant-chemical-yard',
          'plant-utility-room', 'plant-server-room', 'plant-exec-room', 'plant-press-room']

result = {}
for s in SCENES:
    name = 'instantsplat-' + s + '.ply'
    f = open(f'/tmp/tanabe-3d/output/instantsplat/{name}', 'rb')
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
    ix, iy, iz = props.index('x'), props.index('y'), props.index('z')
    mn = arr[:, [ix, iy, iz]].min(axis=0)
    mx = arr[:, [ix, iy, iz]].max(axis=0)
    size = mx - mn
    center = (mn + mx) / 2
    maxDim = float(max(size))
    targetMax = 8.0  # 8m room
    scale = round(targetMax / maxDim, 2)
    # Position so the scene center is at (0, 1.0, -4.0) in world coords
    pos = [round(-float(center[0]) * scale, 2),
           round(-float(center[1]) * scale + 1.0, 2),
           round(-float(center[2]) * scale - 4.0, 2)]
    result[s] = {
        'splatCount': N,
        'bbox_size': size.tolist(),
        'bbox_center': center.tolist(),
        'computed_position': pos,
        'computed_scale': scale,
    }
    print(f'{s}: N={N}, bbox={size.round(2).tolist()}, center={center.round(2).tolist()}, scale={scale}, pos={pos}')

with open('/tmp/instantsplat-bboxes.json', 'w') as f:
    json.dump(result, f, indent=2)
print('\nsaved → /tmp/instantsplat-bboxes.json')
