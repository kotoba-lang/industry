"""
Optimized throughput/cost bench for MiniMax-M2.7 on Modal.

vs bench_modal.py (which was tuned for *fast cold start*, hence ~10 tok/s):
  - CUDA-devel base image WITH nvcc  -> FlashInfer / DeepGEMM FP8 kernels can JIT
  - CUDA graphs ENABLED (no --enforce-eager)        -> faster decode
  - DeepGEMM + FlashInfer sampler ENABLED (not disabled)
  - measures AGGREGATE throughput under concurrency (the real cost driver),
    not just single-stream latency, and prints $ / 1M tokens.

  modal run bench_opt.py::bench_m27_opt
"""
import concurrent.futures
import os
import subprocess
import time

import modal

MODEL = "MiniMaxAI/MiniMax-M2.7"
COST_PER_HR = 16.0  # ~4x H100 on Modal, approximate -> tune to your real rate

# CUDA-devel image provides nvcc so vLLM's FP8 / FlashInfer JIT kernels build.
vllm_image = (
    modal.Image.from_registry("nvidia/cuda:12.8.0-devel-ubuntu22.04", add_python="3.12")
    .pip_install("vllm==0.22.1", "huggingface_hub[hf_transfer]", "openai")
    .env({"HF_HUB_ENABLE_HF_TRANSFER": "1"})
)

CACHE_DIR = "/root/.cache/huggingface"
minimax_vol = modal.Volume.from_name("hf-cache-minimax", create_if_missing=True)
app = modal.App("llm-bench-opt")


@app.function(image=vllm_image, gpu="H100:4", volumes={CACHE_DIR: minimax_vol}, timeout=60 * 60)
def bench_m27_opt():
    from openai import OpenAI

    cmd = [
        "vllm", "serve", MODEL,
        "--served-model-name", MODEL,
        "--trust-remote-code",
        "--tensor-parallel-size", "4",
        "--max-model-len", "32768",
        "--gpu-memory-utilization", "0.92",
        "--max-num-seqs", "128",
        "--safetensors-load-strategy", "prefetch",
        # NOTE: cudagraphs ON (no --enforce-eager), FP8 kernels ON (nvcc present).
        "--host", "0.0.0.0", "--port", "8000",
    ]
    env = {**os.environ, "SAFETENSORS_FAST_GPU": "1", "HF_HOME": CACHE_DIR}
    print(f"[launch] {' '.join(cmd)}", flush=True)
    proc = subprocess.Popen(cmd, env=env)

    client = OpenAI(base_url="http://127.0.0.1:8000/v1", api_key="local", timeout=600)
    t0 = time.time()
    while time.time() - t0 < 3000:
        if proc.poll() is not None:
            raise RuntimeError(f"vllm exited early with code {proc.returncode}")
        try:
            client.models.list(); break
        except Exception:
            time.sleep(10)
    else:
        raise RuntimeError("vLLM never became ready")
    print(f"[ready] vLLM up after {time.time()-t0:.0f}s\n", flush=True)

    PROMPT = ("Explain in detail how TCP congestion control works, covering slow start, "
              "congestion avoidance, fast retransmit and fast recovery.")
    MAX_TOK = 256

    def one(i):
        r = client.chat.completions.create(
            model=MODEL, max_tokens=MAX_TOK, temperature=0.7,
            messages=[{"role": "user", "content": f"{PROMPT} (variant {i})"}])
        return r.usage.completion_tokens if r.usage else 0

    # warmup (also triggers any first-call JIT)
    one(0)

    results = []
    for n in [1, 8, 32, 64]:
        t = time.time()
        with concurrent.futures.ThreadPoolExecutor(max_workers=n) as ex:
            toks = list(ex.map(one, range(n)))
        dt = time.time() - t
        total = sum(toks)
        agg = total / dt
        per_req = (total / n) / dt
        cost_per_1m = COST_PER_HR / 3600 / agg * 1e6 if agg else 0
        row = {"concurrency": n, "agg_tok_s": round(agg), "per_req_tok_s": round(per_req, 1),
               "cost_per_1M_usd": round(cost_per_1m, 2), "wall_s": round(dt, 1)}
        results.append(row)
        print(f"concurrency={n:>3}: aggregate={agg:7.0f} tok/s | per-req={per_req:5.1f} tok/s "
              f"| ${cost_per_1m:7.2f}/1M tok | {dt:.1f}s", flush=True)

    print(f"\n### SUMMARY {results}", flush=True)
    proc.terminate()
    return results
