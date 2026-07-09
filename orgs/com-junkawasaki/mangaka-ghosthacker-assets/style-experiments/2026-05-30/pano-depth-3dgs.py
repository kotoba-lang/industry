#!/usr/bin/env python3
"""Equirect panorama → Depth Anything V2 → 3DGS Inria PLY (each pixel becomes a splat in 3D)."""
import os, time, struct
from pathlib import Path
import numpy as np
from PIL import Image
import torch
from transformers import pipeline

PANO_PATH = '/tmp/tanabe-3d/output/equirect/plant-op-room.png'
OUT_PLY = '/tmp/tanabe-3d/output/equirect/plant-op-room-pano3dgs.ply'

print(f'[load] {PANO_PATH}')
img = Image.open(PANO_PATH).convert('RGB')
W, H = img.size  # ~1536x768 expected
print(f'  panorama size: {W}x{H}')

# Depth Anything V2 (small for speed)
print('[depth] running Depth-Anything-V2-Small on MPS')
device = 'mps' if torch.backends.mps.is_available() else 'cpu'
pipe = pipeline(task='depth-estimation', model='depth-anything/Depth-Anything-V2-Small-hf', device=device)
t0 = time.time()
result = pipe(img)
depth = np.array(result['depth'])  # H x W, larger = farther by default
print(f'  depth shape: {depth.shape}, min={depth.min():.3f}, max={depth.max():.3f}, elapsed={time.time()-t0:.1f}s')

# Normalize depth (Depth Anything V2 outputs inverse depth: larger = closer)
# We want radius: larger depth value = farther radius
# Convert: depth → radius for sphere placement
depth_norm = depth.astype(np.float32) / depth.max()  # 0..1
radius = 4.0 + (1.0 - depth_norm) * 12.0  # 4-16m, far=16m

# Subsample for splat count (~250k splats target)
target_n = 250_000
stride = max(1, int(np.sqrt(W * H / target_n)))
print(f'[grid] stride={stride}, target={W*H//(stride*stride)} splats')

rgb = np.array(img).astype(np.float32) / 255.0

splats = []
SH_C0 = 0.28209479177387814
for v in range(0, H, stride):
    # spherical lat: v=0 top → lat=+π/2, v=H-1 bottom → lat=-π/2
    lat = (0.5 - v / H) * np.pi
    sin_lat = np.sin(lat); cos_lat = np.cos(lat)
    for u in range(0, W, stride):
        # spherical lng: u=0 → lng=-π, u=W-1 → lng=+π
        lng = (u / W - 0.5) * 2 * np.pi
        r = radius[v, u]
        x = r * cos_lat * np.sin(lng)
        y = r * sin_lat
        z = r * cos_lat * np.cos(lng) * -1  # invert Z so +z is forward
        c = rgb[v, u]
        # convert RGB to SH degree 0 DC: dc = (rgb - 0.5) / SH_C0
        f_dc = ((c - 0.5) / SH_C0).tolist()
        # logit opacity ~ 2.2 (=sigmoid 0.9)
        opacity = 2.2
        # log-scale ~ -3 (=0.05m per direction)
        scale_log = -3.0
        # identity quaternion
        rot = [1.0, 0.0, 0.0, 0.0]
        splats.append((x, y, z, 0, 0, 0, f_dc[0], f_dc[1], f_dc[2], opacity, scale_log, scale_log, scale_log, rot[0], rot[1], rot[2], rot[3]))

N = len(splats)
print(f'[write] {N} splats → {OUT_PLY}')
with open(OUT_PLY, 'wb') as f:
    header = (
        b'ply\nformat binary_little_endian 1.0\n'
        b'element vertex ' + str(N).encode() + b'\n'
        b'property float x\nproperty float y\nproperty float z\n'
        b'property float nx\nproperty float ny\nproperty float nz\n'
        b'property float f_dc_0\nproperty float f_dc_1\nproperty float f_dc_2\n'
        b'property float opacity\n'
        b'property float scale_0\nproperty float scale_1\nproperty float scale_2\n'
        b'property float rot_0\nproperty float rot_1\nproperty float rot_2\nproperty float rot_3\n'
        b'end_header\n'
    )
    f.write(header)
    for s in splats:
        f.write(struct.pack('<17f', *s))
print(f'[done] {os.path.getsize(OUT_PLY)//1024} KB')
