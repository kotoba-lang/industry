"""3DGS .ply (Inria SH3, 62 props) → antimatter15 .splat (32 bytes/splat).

Per splat layout (32 bytes):
  position  f32 × 3        12
  scale     f32 × 3        12  (exp() applied)
  color     u8  × 4         4  (R,G,B,A; A from sigmoid(opacity))
  rotation  u8  × 4         4  (quat wxyz, normalized, mapped (q+1)/2 → 255)
"""
import sys
import struct
import numpy as np
from plyfile import PlyData

SH_C0 = 0.28209479177387814

def sigmoid(x):
    return 1.0 / (1.0 + np.exp(-x))

def convert(ply_path, splat_path):
    print(f"reading {ply_path}", flush=True)
    ply = PlyData.read(ply_path)
    v = ply['vertex']
    n = len(v)
    print(f"  vertices: {n}", flush=True)

    # Sort by scale × opacity descending (antimatter15 convention — largest splats first for fast culling)
    scale_x = np.exp(v['scale_0'])
    scale_y = np.exp(v['scale_1'])
    scale_z = np.exp(v['scale_2'])
    opacity = sigmoid(v['opacity'])
    importance = scale_x * scale_y * scale_z * opacity
    order = np.argsort(-importance)

    # Allocate output buffer
    out = np.zeros(n * 32, dtype=np.uint8)
    pos = np.stack([v['x'], v['y'], v['z']], axis=1).astype(np.float32)
    scale = np.stack([scale_x, scale_y, scale_z], axis=1).astype(np.float32)
    color_r = np.clip((v['f_dc_0'] * SH_C0 + 0.5) * 255, 0, 255).astype(np.uint8)
    color_g = np.clip((v['f_dc_1'] * SH_C0 + 0.5) * 255, 0, 255).astype(np.uint8)
    color_b = np.clip((v['f_dc_2'] * SH_C0 + 0.5) * 255, 0, 255).astype(np.uint8)
    alpha = np.clip(opacity * 255, 0, 255).astype(np.uint8)

    rot = np.stack([v['rot_0'], v['rot_1'], v['rot_2'], v['rot_3']], axis=1).astype(np.float32)
    rot_norm = np.linalg.norm(rot, axis=1, keepdims=True)
    rot_norm[rot_norm == 0] = 1
    rot_n = rot / rot_norm
    rot_u8 = np.clip(((rot_n + 1) * 0.5) * 255, 0, 255).astype(np.uint8)

    pos_b = pos[order].tobytes()
    scale_b = scale[order].tobytes()
    color_b_arr = np.stack([color_r[order], color_g[order], color_b[order], alpha[order]], axis=1).astype(np.uint8).tobytes()
    rot_b = rot_u8[order].tobytes()

    # Interleave: each splat = pos(12) + scale(12) + color(4) + rot(4)
    pos_arr = np.frombuffer(pos_b, dtype=np.uint8).reshape(n, 12)
    scale_arr = np.frombuffer(scale_b, dtype=np.uint8).reshape(n, 12)
    color_arr = np.frombuffer(color_b_arr, dtype=np.uint8).reshape(n, 4)
    rot_arr = np.frombuffer(rot_b, dtype=np.uint8).reshape(n, 4)
    out_buf = np.concatenate([pos_arr, scale_arr, color_arr, rot_arr], axis=1).tobytes()

    with open(splat_path, 'wb') as f:
        f.write(out_buf)
    print(f"  wrote {splat_path}: {len(out_buf)} bytes ({len(out_buf)//1024//1024} MB)", flush=True)
    return len(out_buf)

if __name__ == '__main__':
    convert(sys.argv[1], sys.argv[2])
