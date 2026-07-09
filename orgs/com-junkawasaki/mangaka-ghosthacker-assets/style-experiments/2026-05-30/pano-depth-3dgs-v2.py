import os, time, struct
from pathlib import Path
import numpy as np
from PIL import Image
import torch
from transformers import pipeline

PANO_PATH = '/tmp/tanabe-3d/output/equirect/plant-op-room.png'
OUT_PLY = '/tmp/tanabe-3d/output/equirect/plant-op-room-pano3dgs-sh3.ply'

img = Image.open(PANO_PATH).convert('RGB')
W, H = img.size
print(f'panorama: {W}x{H}')

device = 'mps' if torch.backends.mps.is_available() else 'cpu'
pipe = pipeline(task='depth-estimation', model='depth-anything/Depth-Anything-V2-Small-hf', device=device)
result = pipe(img)
depth = np.array(result['depth'])
depth_norm = depth.astype(np.float32) / depth.max()
radius = 4.0 + (1.0 - depth_norm) * 12.0

target_n = 200_000
stride = max(1, int(np.sqrt(W * H / target_n)))
print(f'stride={stride}, target≈{W*H//(stride*stride)}')

rgb = np.array(img).astype(np.float32) / 255.0
SH_C0 = 0.28209479177387814

# Build splat array
splats = []
for v in range(0, H, stride):
    lat = (0.5 - v / H) * np.pi
    sin_lat = float(np.sin(lat)); cos_lat = float(np.cos(lat))
    for u in range(0, W, stride):
        lng = (u / W - 0.5) * 2 * np.pi
        r = float(radius[v, u])
        x = r * cos_lat * float(np.sin(lng))
        y = r * sin_lat
        z = r * cos_lat * float(np.cos(lng)) * -1
        c = rgb[v, u]
        # SH degree 0 RGB → DC
        dc = ((c.astype(np.float64) - 0.5) / SH_C0).astype(np.float32)
        splats.append([x, y, z, 0, 0, 0,
                       float(dc[0]), float(dc[1]), float(dc[2])])

N = len(splats)
print(f'{N} splats')

# Add 45 f_rest_*=0 + opacity + 3 scale + 4 rot = 17+45=62 props total (matches InstantSplat)
# Order: x y z nx ny nz f_dc_0 f_dc_1 f_dc_2 f_rest_0..44 opacity scale_0..2 rot_0..3
prop_names = ['x','y','z','nx','ny','nz','f_dc_0','f_dc_1','f_dc_2']
prop_names += [f'f_rest_{i}' for i in range(45)]
prop_names += ['opacity','scale_0','scale_1','scale_2','rot_0','rot_1','rot_2','rot_3']
n_props = len(prop_names)
assert n_props == 62

with open(OUT_PLY, 'wb') as f:
    header = b'ply\nformat binary_little_endian 1.0\nelement vertex ' + str(N).encode() + b'\n'
    for p in prop_names:
        header += b'property float ' + p.encode() + b'\n'
    header += b'end_header\n'
    f.write(header)
    for s in splats:
        # s = [x, y, z, 0, 0, 0, dc0, dc1, dc2]
        row = list(s) + [0.0]*45 + [2.2, -3.0, -3.0, -3.0, 1.0, 0.0, 0.0, 0.0]
        f.write(struct.pack(f'<{n_props}f', *row))
print(f'wrote {os.path.getsize(OUT_PLY)//1024} KB')
