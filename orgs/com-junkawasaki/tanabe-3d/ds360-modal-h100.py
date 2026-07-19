"""DreamScene360 8-scene parallel runner on Modal — H100 + kernel-optimized.

Changes vs A100 9k iter version:
  - gpu="H100"                       (sm_90, $3.95/hr, 3350 GB/s)
  - TORCH_CUDA_ARCH_LIST="9.0"       (Hopper-only, no fat binary)
  - PyTorch 2.5 + CUDA 12.6 base     (Hopper TMA / WGMMA path)
  - TF32 matmul + cudnn.benchmark    (faster optim steps)
  - --iterations 30000               (proper densification)
"""
import os
import modal

app = modal.App("dreamscene360-h100")
vol = modal.Volume.from_name("ds360-vol", create_if_missing=True)

# PyTorch 2.5.1 + CUDA 12.6 — Hopper-native path
image = (
    modal.Image.from_registry(
        "nvidia/cuda:12.6.2-devel-ubuntu22.04",
        add_python="3.11",
    )
    .apt_install("libglm-dev", "git", "wget", "build-essential", "libgl1", "libglib2.0-0")
    .pip_install(
        "torch==2.5.1", "torchvision==0.20.1",
        index_url="https://download.pytorch.org/whl/cu124",  # 12.4 wheels work on 12.6 runtime
    )
    .pip_install(
        "transformers>=4.26.0,<5.0", "diffusers==0.10.2", "torchmetrics<1.0",
        "accelerate==0.27.2", "huggingface_hub<0.27", "pytorch-lightning<2.0",
        "tokenizers>=0.13", "plyfile==1.0.3",
        "tqdm", "timm", "opencv-python", "trimesh", "h5py", "kornia", "ninja",
        "matplotlib", "datasets", "toml", "albumentations", "einops", "scipy", "gdown",
    )
    .run_commands(
        "cd /opt && git clone --depth 1 --recursive https://github.com/ShijieZhou-UCLA/DreamScene360",
    )
    .run_commands(
        # Hopper-only CUDA arch — eliminates 8.0/8.6/8.9 binary bloat
        "cd /opt/DreamScene360 && "
        "TORCH_CUDA_ARCH_LIST='9.0' CUDA_HOME=/usr/local/cuda "
        "pip install --no-build-isolation --no-deps ./submodules/diff-gaussian-rasterization-depth/ && "
        "pip install --no-build-isolation --no-deps ./submodules/simple-knn/",
        gpu="H100",
    )
    .run_commands(
        "TORCH_CUDA_ARCH_LIST='9.0' CUDA_HOME=/usr/local/cuda "
        "pip install --no-build-isolation --no-deps "
        "git+https://github.com/NVlabs/tiny-cuda-nn/#subdirectory=bindings/torch",
        gpu="H100",
    )
)

LOCAL_PANORAMA_DIR = "/Users/junkawasaki/tanabe-3d/output/equirect"
LOCAL_OUTPUT_DIR = "/Users/junkawasaki/tanabe-3d/output/ds360-h100"
os.makedirs(LOCAL_OUTPUT_DIR, exist_ok=True)

# Check panoramas exist
if not os.path.isdir(LOCAL_PANORAMA_DIR):
    raise SystemExit(f"missing panorama dir: {LOCAL_PANORAMA_DIR}")
image = image.add_local_dir(LOCAL_PANORAMA_DIR, "/panoramas")


@app.function(
    image=image,
    gpu="H100",
    volumes={"/cache": vol},
    timeout=90 * 60,  # 90 min budget per scene
)
def train_scene(scene: str, iters: int = 30000):
    import os
    import subprocess
    from pathlib import Path

    # Hopper-native kernel hints
    os.environ["TORCH_CUDA_ARCH_LIST"] = "9.0"

    work = Path(f"/work/{scene}")
    work.mkdir(parents=True, exist_ok=True)

    # Stage panorama
    src = Path(f"/panoramas/plant-{scene}.png")
    target_data = Path(f"/opt/DreamScene360/data/{scene}")
    target_data.mkdir(parents=True, exist_ok=True)
    target_pano = target_data / f"{scene}_PANORAMA.png"
    if not target_pano.exists():
        target_pano.write_bytes(src.read_bytes())
    print(f"[{scene}] panorama staged ({target_pano.stat().st_size} bytes)", flush=True)

    # Stage omnidata ckpt from Volume cache
    ckpt_cache = Path("/cache/omnidata_dpt_depth_v2.ckpt")
    if not ckpt_cache.exists() or ckpt_cache.stat().st_size < 1_000_000_000:
        print(f"[{scene}] downloading omnidata ckpt...", flush=True)
        subprocess.check_call([
            "wget", "-q", "-O", str(ckpt_cache),
            "https://huggingface.co/Shumin001/omnidata_depth/resolve/main/omnidata_dpt_depth_v2.ckpt",
        ])
        vol.commit()
    ckpt_link = Path("/opt/DreamScene360/pre_checkpoints/omnidata_dpt_depth_v2.ckpt")
    ckpt_link.parent.mkdir(exist_ok=True)
    if ckpt_link.exists() or ckpt_link.is_symlink():
        ckpt_link.unlink()
    os.symlink(ckpt_cache, ckpt_link)
    print(f"[{scene}] omnidata ckpt ready ({ckpt_cache.stat().st_size} bytes)", flush=True)

    # Run training — TF32 + cudnn.benchmark applied via env
    output_dir = Path(f"/opt/DreamScene360/output/{scene}")
    env = os.environ.copy()
    env["PATH"] = "/usr/local/cuda/bin:" + env.get("PATH", "")
    env["CUDA_HOME"] = "/usr/local/cuda"
    env["NVIDIA_TF32_OVERRIDE"] = "1"      # force TF32 path
    env["CUDA_VISIBLE_DEVICES"] = "0"
    env["PYTORCH_CUDA_ALLOC_CONF"] = "expandable_segments:True"

    # Patch torch defaults via a tiny prelude — injected as a wrapper module
    prelude = """
import torch
torch.set_float32_matmul_precision('high')
torch.backends.cudnn.benchmark = True
torch.backends.cuda.matmul.allow_tf32 = True
torch.backends.cudnn.allow_tf32 = True
"""
    prelude_path = Path("/opt/DreamScene360/_torch_prelude.py")
    prelude_path.write_text(prelude)

    cmd = [
        "python", "-c",
        f"import sys; exec(open('/opt/DreamScene360/_torch_prelude.py').read()); "
        f"sys.argv = ['train.py', '-s', '{target_data}', '-m', '{output_dir}', "
        f"'--iterations', '{iters}']; "
        f"exec(open('/opt/DreamScene360/train.py').read())",
    ]
    print(f"[{scene}] launching with {iters} iter (H100, sm_90, TF32 enabled)", flush=True)
    proc = subprocess.run(
        cmd, cwd="/opt/DreamScene360", env=env,
        capture_output=True, text=True, timeout=85 * 60,
    )
    print(f"[{scene}] return code: {proc.returncode}", flush=True)
    print(f"[{scene}] stdout tail:\n{proc.stdout[-2000:]}", flush=True)
    if proc.returncode != 0:
        print(f"[{scene}] stderr tail:\n{proc.stderr[-2000:]}", flush=True)
        return {"scene": scene, "ok": False, "stderr": proc.stderr[-2000:]}

    # Pick iter_30000 if available else best fallback
    candidates = sorted(output_dir.rglob("point_cloud.ply"))
    target = output_dir / "point_cloud" / f"iteration_{iters}" / "point_cloud.ply"
    ply_path = target if target.exists() else (candidates[-1] if candidates else None)
    if ply_path is None:
        return {"scene": scene, "ok": False, "stderr": "no .ply"}

    out_dir = Path("/cache/output-h100")
    out_dir.mkdir(parents=True, exist_ok=True)
    dst = out_dir / f"{scene}.ply"
    dst.write_bytes(ply_path.read_bytes())
    vol.commit()
    size = dst.stat().st_size
    print(f"[{scene}] OK → volume: {dst} ({size} bytes)", flush=True)
    return {"scene": scene, "ok": True, "ply_path": str(ply_path), "size": size}


@app.local_entrypoint()
def main(iters: int = 30000):
    scenes = ['op-room', 'scada', 'cleanroom', 'chemical-yard',
              'server-room', 'exec-room', 'utility-room', 'press-room']
    print(f"H100 launch — {len(scenes)} scenes × {iters} iter", flush=True)
    ok = 0
    args = [(s, iters) for s in scenes]
    for r in train_scene.starmap(args):
        scene = r["scene"]
        if r.get("ok"):
            ok += 1
            print(f"✓ {scene}: {r.get('size',0)//1024} KB on volume")
        else:
            print(f"✗ {scene}: {r.get('stderr','')[-300:]}")
    print(f"\n=== {ok}/{len(scenes)} done — `modal volume get ds360-vol output-h100 ./output/ds360-h100` ===")
