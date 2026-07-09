# Fixed: parse property names not types
f = open('/tmp/tanabe-3d/output/instantsplat/instantsplat-op-room.ply', 'rb')
header = b''
while True:
    c = f.read(1)
    header += c
    if header.endswith(b'end_header\n'): break
text = header.decode('utf-8', 'replace')
props = []
for line in text.split('\n'):
    parts = line.split()
    if len(parts) >= 3 and parts[0] == 'property':
        props.append(parts[2])  # name
print(f'props ({len(props)}): {props[:8]}')
N = 334909
stride = len(props) * 4
data = f.read(stride * N)
import numpy as np
arr = np.frombuffer(data, dtype=np.float32).reshape(N, len(props))
ix, iy, iz = props.index('x'), props.index('y'), props.index('z')
mn = arr[:, [ix, iy, iz]].min(axis=0)
mx = arr[:, [ix, iy, iz]].max(axis=0)
print(f'bbox min: {mn}')
print(f'bbox max: {mx}')
print(f'bbox size: {mx-mn}')
print(f'bbox center: {(mn+mx)/2}')
