"""
Modal app: serve MiniMax-M2.7 with a vLLM OpenAI-compatible endpoint.

Why M2.7 (not M3): MiniMax-M3 uses the new MSA (sparse attention) architecture
(`minimax_m3_vl`) which, as of June 2026, is NOT implemented in vLLM or SGLang,
and the HF repo ships no modeling code. M2.7 is the same family, fully supported
by vLLM, strong on agentic / tool-calling, and only ~230GB in weights.

Usage:
  # 1) one-time: pull the 230GB weights into a Modal Volume (CPU-only, cheap)
  modal run minimax_m2_modal.py::download

  # 2) deploy the GPU endpoint (4x H100). Prints a public *.modal.run URL.
  modal deploy minimax_m2_modal.py

  # 3) point client.py at the URL (see client.py)
"""
import os
import subprocess

import modal

MODEL = "MiniMaxAI/MiniMax-M2.7"
# Simple shared secret so the public endpoint isn't fully open. Change if you like;
# client.py reads the same value.
API_KEY = "minimax-m2-7-testkey-7f3a"

vllm_image = (
    modal.Image.debian_slim(python_version="3.12")
    .pip_install("vllm", "huggingface_hub[hf_transfer]")
    .env({"HF_HUB_ENABLE_HF_TRANSFER": "1", "VLLM_USE_V1": "1"})
)

hf_cache = modal.Volume.from_name("hf-cache-minimax", create_if_missing=True)
CACHE_DIR = "/root/.cache/huggingface"

app = modal.App("minimax-m2-7")


@app.function(
    image=vllm_image,
    volumes={CACHE_DIR: hf_cache},
    cpu=8,
    memory=32 * 1024,
    timeout=60 * 60,
)
def download():
    """Download the model into the Volume once so serve cold-starts are fast."""
    from huggingface_hub import snapshot_download

    print(f"Downloading {MODEL} (~230GB) into volume...")
    snapshot_download(MODEL, cache_dir=CACHE_DIR)
    hf_cache.commit()
    print("Download complete and committed to volume.")


@app.function(
    image=vllm_image,
    gpu="H100:4",
    volumes={CACHE_DIR: hf_cache},
    timeout=60 * 60,
    scaledown_window=300,  # keep warm 5 min after last request
)
@modal.concurrent(max_inputs=32)
@modal.web_server(port=8000, startup_timeout=60 * 60)
def serve():
    cmd = [
        "vllm", "serve", MODEL,
        "--served-model-name", MODEL,
        "--trust-remote-code",
        "--tensor-parallel-size", "4",
        "--enable-auto-tool-choice",
        "--tool-call-parser", "minimax_m2",
        "--reasoning-parser", "minimax_m2_append_think",
        "--max-model-len", "65536",
        "--gpu-memory-utilization", "0.92",
        # Modal Volume is a 9P FS where vLLM disables auto-prefetch -> force it,
        # else loading 214GB is extremely slow.
        "--safetensors-load-strategy", "prefetch",
        # Skip torch.compile + cudagraph capture to cut cold-start by ~10 min.
        "--enforce-eager",
        "--api-key", API_KEY,
        "--host", "0.0.0.0",
        "--port", "8000",
    ]
    # DeepGEMM FP8 kernels aren't bundled in this image; fall back to Triton FP8.
    env = {
        **os.environ,
        "SAFETENSORS_FAST_GPU": "1",
        "HF_HOME": CACHE_DIR,
        "VLLM_USE_DEEP_GEMM": "0",
    }
    subprocess.Popen(" ".join(cmd), shell=True, env=env)
