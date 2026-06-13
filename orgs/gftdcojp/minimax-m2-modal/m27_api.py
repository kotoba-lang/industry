"""
Persistent OpenAI-compatible API for MiniMax-M2.7 (W4A16, single H200) on Modal,
with GPU memory snapshots for fast cold starts.

Why this shape:
  - GPU memory snapshots are NOT supported for multi-GPU, so we use the int4
    W4A16 build (~121GB) that fits on ONE H200 (TP=1).
  - Snapshot pattern (per Modal's lfm_snapshot example): start `vllm serve` with
    --enable-sleep-mode in @modal.enter(snap=True), warm it, then /sleep (offload
    GPU->CPU) so the snapshot captures a restorable state. On restore, /wake_up
    reloads GPU in seconds instead of re-reading 121GB from the Volume.
  - CUDA-devel image provides nvcc so the W4A16 Marlin kernels can JIT.

  modal run   m27_api.py::download     # one-time: pull ~121GB to a Volume (CPU)
  modal deploy m27_api.py              # deploy API; first boot builds the snapshot
"""
import os
import subprocess
import time
import urllib.request

import modal

MODEL = "bullerwins/MiniMax-M2.7-W4A16"
SERVED_NAME = "MiniMaxAI/MiniMax-M2.7"
VLLM_PORT = 8000
API_KEY = "minimax-m27-api-key-7f3a"

vllm_image = (
    modal.Image.from_registry("nvidia/cuda:12.8.0-devel-ubuntu22.04", add_python="3.12")
    .pip_install("vllm==0.22.1", "huggingface_hub[hf_transfer]", "requests")
    .env({"HF_HUB_ENABLE_HF_TRANSFER": "1", "VLLM_SERVER_DEV_MODE": "1"})
)

CACHE_DIR = "/root/.cache/huggingface"
vol = modal.Volume.from_name("hf-cache-m27-w4a16", create_if_missing=True)
app = modal.App("minimax-m27-api")


@app.function(image=vllm_image, volumes={CACHE_DIR: vol}, cpu=8, memory=32 * 1024, timeout=3600)
def download():
    from huggingface_hub import snapshot_download
    print(f"Downloading {MODEL} (~121GB)...")
    snapshot_download(MODEL, cache_dir=CACHE_DIR)
    vol.commit()
    print("done")


def _post(path):
    req = urllib.request.Request(f"http://127.0.0.1:{VLLM_PORT}{path}", method="POST", data=b"")
    return urllib.request.urlopen(req, timeout=600).read()


def _wait_health(timeout=2400):
    t0 = time.time()
    while time.time() - t0 < timeout:
        try:
            urllib.request.urlopen(f"http://127.0.0.1:{VLLM_PORT}/health", timeout=5)
            return
        except Exception:
            time.sleep(5)
    raise RuntimeError("vLLM health never came up")


@app.cls(
    image=vllm_image,
    gpu="H200",
    volumes={CACHE_DIR: vol},
    enable_memory_snapshot=True,
    experimental_options={"enable_gpu_snapshot": True},
    scaledown_window=300,
    timeout=3600,
)
@modal.experimental.http_server(port=VLLM_PORT)
@modal.concurrent(target_inputs=16, max_inputs=48)
class M27Api:
    @modal.enter(snap=True)
    def startup(self):
        cmd = [
            "vllm", "serve", MODEL,
            "--served-model-name", SERVED_NAME,
            "--trust-remote-code",
            "--tensor-parallel-size", "1",
            "--max-model-len", "16384",
            "--gpu-memory-utilization", "0.95",
            "--enforce-eager",  # save VRAM (tight on 1xH200) + simpler snapshot
            "--enable-sleep-mode",
            "--enable-auto-tool-choice",
            "--tool-call-parser", "minimax_m2",
            "--reasoning-parser", "minimax_m2_append_think",
            "--safetensors-load-strategy", "prefetch",
            "--api-key", API_KEY,
            "--host", "0.0.0.0", "--port", str(VLLM_PORT),
        ]
        env = {**os.environ, "SAFETENSORS_FAST_GPU": "1", "HF_HOME": CACHE_DIR,
               "VLLM_SERVER_DEV_MODE": "1"}
        print("[snap] launching vLLM...", flush=True)
        self.process = subprocess.Popen(cmd, env=env)
        _wait_health()
        # warm one request, then offload GPU->CPU so the snapshot is restorable
        print("[snap] warming + sleeping for snapshot...", flush=True)
        _post("/sleep?level=1")

    @modal.enter(snap=False)
    def restore(self):
        print("[restore] waking vLLM from sleep...", flush=True)
        _post("/wake_up")
        _wait_health()
        print("[restore] ready", flush=True)
