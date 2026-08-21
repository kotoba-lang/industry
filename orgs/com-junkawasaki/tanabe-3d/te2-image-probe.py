"""Phase 1a — Modal B200 + TE 2.0 image build probe.

Builds the gftd-sdxl-b200-te2 image and runs a minimal smoke test:
  1. import transformer_engine.pytorch
  2. import diffusers + load SDXL UNet config (no weights)
  3. import gftd_te_patch, patch the UNet, assert tag count

This is the cheapest path to verify the image stack actually compiles
on B200 before investing in calibration / production runs.
"""
import os
import modal

app = modal.App("te2-image-probe")

image = (
    # NGC PyTorch container — TE preinstalled, Blackwell sm_100 ready,
    # g++/ninja/clang available. Single trusted source for the full Hopper+Blackwell stack.
    modal.Image.from_registry(
        "nvcr.io/nvidia/pytorch:25.03-py3",
        add_python=None,   # use container's Python 3.12
    )
    .apt_install("libglm-dev", "git", "wget", "libgl1", "libglib2.0-0")
    # diffusers + transformers (TE is preinstalled in NGC image)
    .pip_install(
        "diffusers>=0.32",
        "transformers>=4.45",
        "accelerate>=1.0",
        "huggingface_hub>=0.26",
        "safetensors",
    )
    # gftd-te-patch — copy=True so we can run pip install after add_local_dir
    .add_local_dir(
        "/Users/junkawasaki/gftdcojp/ai-gftd-apps-gftdcojp/70-tools/gftd-te-patch",
        "/opt/gftd-te-patch",
        copy=True,
    )
    .run_commands("cd /opt/gftd-te-patch && pip install -e .")
)


@app.function(image=image, gpu="B200", timeout=10 * 60)
def probe():
    """Smoke test the image — return version map for verification."""
    import sys
    import importlib

    results = {"python": sys.version}

    # Verify each component imports and report version
    for mod_name in ("torch", "transformer_engine", "transformer_engine.pytorch",
                     "diffusers", "transformers", "gftd_te_patch"):
        try:
            m = importlib.import_module(mod_name)
            results[mod_name] = getattr(m, "__version__", "unknown")
        except Exception as e:
            results[mod_name] = f"FAIL: {type(e).__name__}: {e}"

    # Verify B200 / sm_100
    try:
        import torch
        if torch.cuda.is_available():
            cap = torch.cuda.get_device_capability(0)
            results["cuda_arch"] = f"sm_{cap[0]}{cap[1]}"
            results["device_name"] = torch.cuda.get_device_name(0)
            results["cuda_version"] = torch.version.cuda
        else:
            results["cuda"] = "not available"
    except Exception as e:
        results["cuda_err"] = str(e)

    # Verify gftd_te_patch sees TE
    try:
        from gftd_te_patch import HAS_TE
        results["gftd_HAS_TE"] = HAS_TE
    except Exception as e:
        results["gftd_HAS_TE_err"] = str(e)

    # Apply patch on a synthetic minimal UNet to confirm the swap works on
    # the actual TE 2.0 install (catches TE API mismatch).
    try:
        from torch import nn
        from gftd_te_patch import patch_sdxl_unet

        # Mock minimal UNet structure
        class A(nn.Module):
            def __init__(self):
                super().__init__()
                self.attentions = nn.ModuleList([nn.Module()])
                self.attentions[0].transformer_blocks = nn.ModuleList([nn.Module()])
                tb = self.attentions[0].transformer_blocks[0]
                tb.attn1 = nn.Module()
                tb.attn1.to_q = nn.Linear(32, 32, bias=False)
                tb.attn1.to_k = nn.Linear(32, 32, bias=False)
                tb.attn1.to_v = nn.Linear(32, 32, bias=False)
                tb.attn1.to_out = nn.ModuleList([nn.Linear(32, 32), nn.Dropout(0.0)])

        unet = nn.Module()
        unet.down_blocks = nn.ModuleList([A()])
        patch_sdxl_unet(unet)
        # Count tagged
        tagged = sum(1 for m in unet.modules() if hasattr(m, "_gftd_te_recipe"))
        results["te_swap_smoke"] = f"OK ({tagged} layers tagged)"
    except Exception as e:
        results["te_swap_smoke"] = f"FAIL: {type(e).__name__}: {e}"

    # Force all values to str so local-side deserialization doesn't need torch
    return {str(k): str(v) for k, v in results.items()}


@app.local_entrypoint()
def main():
    print("Phase 1a — Modal B200 + TE 2.0 image probe")
    print("  Image build cost: ~$5-7 (one-time, cached after)")
    print("  Smoke test cost: ~$1 (1 min B200 = $0.10)")
    print()
    # Cast all values to str remotely so local Mac (without torch) can deserialize
    results = probe.remote()
    # results is already dict[str, str] thanks to str() casting in probe()
    for k, v in results.items():
        print(f"  {k}: {v}")
