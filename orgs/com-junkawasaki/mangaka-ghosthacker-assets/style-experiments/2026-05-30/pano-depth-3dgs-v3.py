import os, struct
import numpy as np
from PIL import Image
import torch
from transformers import pipeline

PANO_PATH = '/tmp/tanabe-3d/output/equirect/plant-op-room.png'
OUT_PLY = '/tmp/tanabe-3d/output/equirect/plant-op-room-pano3dgs-v3.ply'

img = Image.open(PANO_PATH).convert('RGB')
W, H = img.size

device = 'mps' if torch.backends.mps.is_available() else 'cpu'
pipe = pipeline(task='depth-estimation', model='depth-anything/Depth-Anything-V2-Small-hf', device=device)
depth = np.array(pipe(img)['depth'])
depth_norm = depth.astype(np.float32) / depth.max()
radius = 4.0 + (1.0 - depth_norm) * 12.0

target_n = 400_000
stride = max(1, int(np.sqrt(W * H / target_n)))
print(f'stride={stride}, target≈{W*H//(stride*stride)}')

rgb = np.array(img).astype(np.float32) / 255.0
SH_C0 = 0.28209479177387814

# vectorize: build all splats at once
ys, xs = np.meshgrid(np.arange(0, H, stride), np.arange(0, W, stride), indexing='ij')
ys = ys.flatten(); xs = xs.flatten()
lat = (0.5 - ys / H) * np.pi
lng = (xs / W - 0.5) * 2 * np.pi
sin_lat = np.sin(lat); cos_lat = np.cos(lat)
r = radius[ys, xs]
x = r * cos_lat * np.sin(lng)
y = r * sin_lat
z = r * cos_lat * np.cos(lng) * -1
c = rgb[ys, xs]
dc = ((c - 0.5) / SH_C0).astype(np.float32)
N = len(x)
print(f'{N} splats')

# scale_log = -1 means sigma = 0.37m → big visible splats at 4-16m radius
# opacity logit = 5.0 → sigmoid ≈ 0.993
# rotation = wxyz identity (1,0,0,0)
scale_val = -1.5  # sigma = exp(-1.5) = 0.22m
opacity_val = 5.0
prop_names = ['x','y','z','nx','ny','nz','f_dc_0','f_dc_1','f_dc_2']
prop_names += [f'f_rest_{i}' for i in range(45)]
prop_names += ['opacity','scale_0','scale_1','scale_2','rot_0','rot_1','rot_2','rot_3']
n_props = len(prop_names)

# Build buffer
buf = np.zeros((N, n_props), dtype=np.float32)
buf[:, 0] = x; buf[:, 1] = y; buf[:, 2] = z
buf[:, 6:9] = dc
# f_rest = 0 (default)
buf[:, 54] = opacity_val
buf[:, 55] = scale_val
buf[:, 56] = scale_val
buf[:, 57] = scale_val
buf[:, 58] = 1.0  # rot_0 = w
# rot_1..3 = 0 (default)

with open(OUT_PLY, 'wb') as f:
    header = b'ply\nformat binary_little_endian 1.0\nelement vertex ' + str(N).encode() + b'\n'
    for p in prop_names:
        header += b'property float ' + p.encode() + b'\n'
    header += b'end_header\n'
    f.write(header)
    f.write(buf.tobytes())
print(f'wrote {os.path.getsize(OUT_PLY)//1024} KB')
