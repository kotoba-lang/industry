"""
Modal app: serve moonshotai/Kimi-K2.7-Code with a vLLM OpenAI-compatible endpoint.

Kimi-K2.7-Code: 1T total / 32B active MoE, native INT4 weights (~595GB), 256K ctx.
Per the official deploy guide: vLLM >= 0.19.1, TP=8 on 8x H200, tool-call parser
`kimi_k2`, reasoning parser `kimi_k2`.

Usage:
  modal run kimi_modal.py::download         # pull ~595GB into a Volume (CPU, cheap)
  modal deploy kimi_modal.py                # 8x H200 endpoint -> *.modal.run URL
"""
import os
import subprocess

import modal

MODEL = "moonshotai/Kimi-K2.7-Code"
API_KEY = "kimi-k27-code-testkey-7f3a"  # client.py reads the same value

vllm_image = (
    modal.Image.debian_slim(python_version="3.12")
    .pip_install("vllm==0.19.1", "huggingface_hub[hf_transfer]")
    .env({"HF_HUB_ENABLE_HF_TRANSFER": "1", "VLLM_USE_V1": "1"})
)

hf_cache = modal.Volume.from_name("hf-cache-kimi", create_if_missing=True)
CACHE_DIR = "/root/.cache/huggingface"

app = modal.App("kimi-k27-code")


@app.function(
    image=vllm_image,
    volumes={CACHE_DIR: hf_cache},
    cpu=8,
    memory=32 * 1024,
    timeout=2 * 60 * 60,
)
def download():
    from huggingface_hub import snapshot_download

    print(f"Downloading {MODEL} (~595GB) into volume...")
    snapshot_download(MODEL, cache_dir=CACHE_DIR)
    hf_cache.commit()
    print("Download complete and committed to volume.")


@app.function(
    image=vllm_image,
    gpu="H200:8",
    volumes={CACHE_DIR: hf_cache},
    timeout=60 * 60,
    scaledown_window=300,
)
@modal.concurrent(max_inputs=32)
@modal.web_server(port=8000, startup_timeout=60 * 60)
def serve():
    cmd = [
        "vllm", "serve", MODEL,
        "--served-model-name", MODEL,
        "--trust-remote-code",
        "--tensor-parallel-size", "8",
        "--mm-encoder-tp-mode", "data",
        "--enable-auto-tool-choice",
        "--tool-call-parser", "kimi_k2",
        "--reasoning-parser", "kimi_k2",
        "--max-model-len", "65536",
        "--gpu-memory-utilization", "0.92",
        "--safetensors-load-strategy", "prefetch",
        "--enforce-eager",
        "--api-key", API_KEY,
        "--host", "0.0.0.0",
        "--port", "8000",
    ]
    env = {
        **os.environ,
        "SAFETENSORS_FAST_GPU": "1",
        "HF_HOME": CACHE_DIR,
        "VLLM_USE_DEEP_GEMM": "0",
    }
    subprocess.Popen(" ".join(cmd), shell=True, env=env)
