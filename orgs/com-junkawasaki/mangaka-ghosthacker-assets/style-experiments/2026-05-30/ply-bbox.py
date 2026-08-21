import struct
f = open('/tmp/tanabe-3d/output/instantsplat/instantsplat-op-room.ply', 'rb')
header = b''
while True:
    c = f.read(1)
    header += c
    if header.endswith(b'end_header\n'): break
# count props by parsing header
text = header.decode('utf-8', 'replace')
props = []
for line in text.split('\n'):
    if line.startswith('property '):
        props.append(line.split()[1])
print(f'props ({len(props)}): {props[:5]}...{props[-3:]}')
N = 334909
stride = len(props) * 4
# read first 1000 to sample
import struct
data = f.read(stride * N)
print(f'data bytes: {len(data)}, expected {stride*N}')
ix, iy, iz = props.index('x'), props.index('y'), props.index('z')
mn = [9e9, 9e9, 9e9]; mx = [-9e9, -9e9, -9e9]
import numpy as np
arr = np.frombuffer(data, dtype=np.float32).reshape(N, len(props))
mn = arr[:, [ix, iy, iz]].min(axis=0)
mx = arr[:, [ix, iy, iz]].max(axis=0)
print(f'bbox min: {mn}')
print(f'bbox max: {mx}')
print(f'bbox size: {mx-mn}')
print(f'bbox center: {(mn+mx)/2}')
