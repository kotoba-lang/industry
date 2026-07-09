"""3DGS .ply → antimatter15 .splat with low-opacity / tiny-scale culling.

DreamScene360 at 9000 iter produces 2M splats where ~80-90% have
near-zero opacity (still in densification). Cull them to make the
visible structure pop.
"""
import sys
import numpy as np
from plyfile import PlyData

SH_C0 = 0.28209479177387814

def sigmoid(x):
    return 1.0 / (1.0 + np.exp(-x))

def convert(ply_path, splat_path, alpha_min=50, scale_min=0.002):
    print(f"reading {ply_path}", flush=True)
    ply = PlyData.read(ply_path)
    v = ply['vertex']
    n_total = len(v)

    scale_x = np.exp(v['scale_0'])
    scale_y = np.exp(v['scale_1'])
    scale_z = np.exp(v['scale_2'])
    opacity = sigmoid(v['opacity'])
    alpha_u8 = (opacity * 255).astype(np.int32)
    max_scale = np.maximum(np.maximum(scale_x, scale_y), scale_z)

    # Cull: alpha < threshold OR scale < threshold
    keep = (alpha_u8 >= alpha_min) & (max_scale >= scale_min)
    n = int(keep.sum())
    print(f"  vertices: {n_total} → {n} ({100*n/n_total:.1f}% kept)", flush=True)

    if n == 0:
        print("  ERROR: zero splats kept, lower thresholds", flush=True)
        sys.exit(1)

    pos = np.stack([v['x'], v['y'], v['z']], axis=1).astype(np.float32)[keep]
    scale = np.stack([scale_x, scale_y, scale_z], axis=1).astype(np.float32)[keep]
    color_r = np.clip((v['f_dc_0'][keep] * SH_C0 + 0.5) * 255, 0, 255).astype(np.uint8)
    color_g = np.clip((v['f_dc_1'][keep] * SH_C0 + 0.5) * 255, 0, 255).astype(np.uint8)
    color_b = np.clip((v['f_dc_2'][keep] * SH_C0 + 0.5) * 255, 0, 255).astype(np.uint8)
    alpha = np.clip(opacity[keep] * 255, 0, 255).astype(np.uint8)

    rot = np.stack([v['rot_0'], v['rot_1'], v['rot_2'], v['rot_3']], axis=1).astype(np.float32)[keep]
    rot_norm = np.linalg.norm(rot, axis=1, keepdims=True)
    rot_norm[rot_norm == 0] = 1
    rot_u8 = np.clip(((rot / rot_norm + 1) * 0.5) * 255, 0, 255).astype(np.uint8)

    # Sort by importance descending
    importance = scale[:,0] * scale[:,1] * scale[:,2] * (alpha.astype(np.float32) / 255)
    order = np.argsort(-importance)

    out = np.concatenate([
        pos[order].view(np.uint8).reshape(n, 12),
        scale[order].view(np.uint8).reshape(n, 12),
        np.stack([color_r[order], color_g[order], color_b[order], alpha[order]], axis=1),
        rot_u8[order],
    ], axis=1).tobytes()

    with open(splat_path, 'wb') as f:
        f.write(out)
    size_mb = len(out)/1024/1024
    print(f"  wrote {splat_path}: {len(out)} bytes ({size_mb:.1f} MB)", flush=True)

if __name__ == '__main__':
    if len(sys.argv) < 3:
        print("usage: ply_to_splat_filtered.py <ply> <splat> [alpha_min=50] [scale_min=0.002]")
        sys.exit(1)
    a = int(sys.argv[3]) if len(sys.argv) > 3 else 50
    s = float(sys.argv[4]) if len(sys.argv) > 4 else 0.002
    convert(sys.argv[1], sys.argv[2], alpha_min=a, scale_min=s)
