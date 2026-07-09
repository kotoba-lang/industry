"""DreamScene360 8-scene parallel on Modal B200 + TransformerEngine 2.0 NVFP4.

Phase 1a scaffold per ADR-2605282350.
Status: NOT EXECUTABLE — depends on Phase 0 (panorama recovery) + Phase 1c
        (gftd_te_patch impl). This file documents the target shape only.

When ready to run (Phase 5 in ADR-2605282350):
  1. Recover panoramas: ~/tanabe-3d/recover-panoramas.sh
  2. Build gftd-te-patch: cd 70-tools/gftd-te-patch && pip install -e .
  3. Wait until Phase 2c smoke test (1 scene) passes D-5 quality gate
  4. Run: modal run ~/tanabe-3d/ds360-modal-b200-te2.py
"""
import os
import modal

app = modal.App("dreamscene360-b200-te2")
vol = modal.Volume.from_name("ds360-vol", create_if_missing=True)

# Blackwell-native image stack per ADR-2605282350 D-1.
# NOTE: PyTorch 2.6 nightly cu128 wheels are runtime-fetched; if Modal's
# base layer cache is empty this will take ~10-15 min on first build.
image = (
    modal.Image.from_registry(
        "nvidia/cuda:12.8.1-devel-ubuntu22.04",
        add_python="3.11",
    )
    .apt_install("libglm-dev", "git", "wget", "build-essential", "libgl1", "libglib2.0-0")
    .pip_install(
        # PyTorch 2.6 nightly for Blackwell sm_100 — pinned date TBD on Phase 1a
        "torch", "torchvision",
        pre=True,
        index_url="https://download.pytorch.org/whl/nightly/cu128",
    )
    .pip_install(
        # TransformerEngine 2.0 + diffusers 0.32 + modern stack
        # (replaces DS360's diffusers 0.10.2 hardcode — handled in ds360-te2-blackwell fork)
        "transformer-engine[pytorch]>=2.0",
        "diffusers>=0.32",
        "transformers>=4.45",
        "accelerate>=1.0",
        "huggingface_hub>=0.26",
        "pytorch-lightning>=2.0",
        "plyfile==1.0.3",
        "tqdm", "timm", "opencv-python", "trimesh", "h5py", "kornia", "ninja",
        "matplotlib", "datasets", "toml", "albumentations", "einops", "scipy",
    )
    # ds360-te2-blackwell fork (PHASE 1c — repo to be created at
    # 60-apps/ai-gftd-project-cyber-drill/external/ds360-te2-blackwell/)
    .run_commands(
        "cd /opt && git clone --depth 1 --recursive "
        "https://github.com/gftdcojp/ds360-te2-blackwell || "
        "(echo 'PLACEHOLDER: fork not yet created — Phase 1c'; exit 1)",
    )
    .run_commands(
        # Blackwell-only kernel build
        "cd /opt/ds360-te2-blackwell && "
        "TORCH_CUDA_ARCH_LIST='10.0' CUDA_HOME=/usr/local/cuda "
        "pip install --no-build-isolation --no-deps ./submodules/diff-gaussian-rasterization-depth/ && "
        "pip install --no-build-isolation --no-deps ./submodules/simple-knn/",
        gpu="B200",
    )
    .run_commands(
        "TORCH_CUDA_ARCH_LIST='10.0' CUDA_HOME=/usr/local/cuda "
        "pip install --no-build-isolation --no-deps "
        "git+https://github.com/NVlabs/tiny-cuda-nn/#subdirectory=bindings/torch",
        gpu="B200",
    )
    # gftd-te-patch — shared TE 2.0 NVFP4 monkeypatch (PHASE 1b — scaffold; PHASE 1c — impl)
    .add_local_dir(
        "/Users/junkawasaki/gftdcojp/ai-gftd-apps-gftdcojp/70-tools/gftd-te-patch",
        "/opt/gftd-te-patch",
    )
    .run_commands("cd /opt/gftd-te-patch && pip install -e .")
)

LOCAL_PANORAMA_DIR = "/Users/junkawasaki/tanabe-3d/output/equirect"
if not os.path.isdir(LOCAL_PANORAMA_DIR) or len(os.listdir(LOCAL_PANORAMA_DIR)) < 8:
    raise SystemExit(
        f"PHASE 0 GATE: panoramas missing at {LOCAL_PANORAMA_DIR}. "
        f"Run ~/tanabe-3d/recover-panoramas.sh first."
    )
image = image.add_local_dir(LOCAL_PANORAMA_DIR, "/panoramas")


@app.function(
    image=image,
    gpu="B200",
    volumes={"/cache": vol},
    timeout=60 * 60,
)
def train_scene(scene: str, iters: int = 30000):
    """Phase 5 implementation — kept as scaffold for now.

    Target shape: omnidata DPT ViT + SDXL UNet are TE-patched on init;
    diff_gaussian_rasterization runs in BF16 (not TE-affected). Expected
    wall ~13-15 min/scene on B200 at 30k iter (per ADR-2605282350 §ROI).
    """
    raise NotImplementedError(
        "Phase 5 — execution gated on Phase 2c smoke test pass (D-5 quality gate). "
        "See ADR-2605282350."
    )


@app.local_entrypoint()
def main():
    raise SystemExit(
        "PHASE 5 GATE: this scaffold is not yet executable. "
        "Required prerequisites (per ADR-2605282350):\n"
        "  - Phase 0: recover-panoramas.sh\n"
        "  - Phase 1b: gftd-te-patch/__init__.py impl (currently NotImplementedError)\n"
        "  - Phase 1c: NVFP4 calibration for Animagine XL 4.0\n"
        "  - Phase 2a/2b/2c: quality gate (LPIPS<0.05, CLIP-T>0.95, jump_qa -3pt)\n"
        "Then re-run this script."
    )
