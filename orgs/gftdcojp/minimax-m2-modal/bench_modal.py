"""
Robust head-to-head bench, run entirely inside one Modal container per model.

Why this design: serving via modal.web_server fought with vLLM's long cold start
(214-595GB read from a 9P Volume) — Modal's port-readiness timeout kept killing the
container mid-load, cycling forever. Here we start vLLM as a subprocess, wait for
readiness *inside* the container (no Modal port timeout), run the 3 bench tasks, and
print results. One cold start, deterministic, no external polling.

  modal run bench_modal.py::bench_m27     # MiniMax-M2.7  (4x H100)
  modal run bench_modal.py::bench_kimi    # Kimi-K2.7-Code (8x H200)
"""
import json
import os
import subprocess
import time

import modal

vllm_image = (
    modal.Image.debian_slim(python_version="3.12")
    .pip_install("vllm==0.22.1", "huggingface_hub[hf_transfer]", "openai",
                 "runai-model-streamer")
    .env({"HF_HUB_ENABLE_HF_TRANSFER": "1"})
)

CACHE_DIR = "/root/.cache/huggingface"
minimax_vol = modal.Volume.from_name("hf-cache-minimax", create_if_missing=True)
kimi_vol = modal.Volume.from_name("hf-cache-kimi", create_if_missing=True)

app = modal.App("llm-bench")

PROMPTS = {
    "reasoning": (
        "A farmer has 17 sheep. All but 9 run away. Then he buys 5 more, and twice as "
        "many goats as the sheep he now has. How many animals total? Show your reasoning "
        "step by step, then give the final number."),
    "coding": (
        "Write a Python function `merge_intervals(intervals)` that merges overlapping "
        "intervals (list of [start, end]). Include 3 doctest examples and state the time "
        "complexity."),
}


def _serve_and_bench(model, tp, tool_parser, reasoning_parser, extra_args=None):
    from openai import OpenAI

    cmd = [
        "vllm", "serve", model,
        "--served-model-name", model,
        "--trust-remote-code",
        "--tensor-parallel-size", str(tp),
        "--enable-auto-tool-choice",
        "--tool-call-parser", tool_parser,
        "--reasoning-parser", reasoning_parser,
        "--max-model-len", "65536",
        "--gpu-memory-utilization", "0.92",
        # Run:AI Model Streamer: concurrent streaming load (replaces the default
        # safetensors loader; faster than prefetch and config-agnostic).
        "--load-format", "runai_streamer",
        "--enforce-eager",
        "--host", "0.0.0.0", "--port", "8000",
    ] + (extra_args or [])
    # No CUDA toolkit (nvcc) in this image, so FlashInfer can't JIT-build its sampler
    # kernels -> disable FlashInfer sampler (use native torch) and DeepGEMM FP8 kernels.
    env = {**os.environ, "SAFETENSORS_FAST_GPU": "1", "HF_HOME": CACHE_DIR,
           "VLLM_USE_DEEP_GEMM": "0", "VLLM_USE_FLASHINFER_SAMPLER": "0"}
    print(f"[launch] {' '.join(cmd)}", flush=True)
    proc = subprocess.Popen(cmd, env=env)

    client = OpenAI(base_url="http://127.0.0.1:8000/v1", api_key="local", timeout=600)
    t0 = time.time()
    ready = False
    while time.time() - t0 < 3000:  # up to 50 min for cold load
        if proc.poll() is not None:
            raise RuntimeError(f"vllm exited early with code {proc.returncode}")
        try:
            client.models.list()
            ready = True
            break
        except Exception:
            time.sleep(10)
    if not ready:
        raise RuntimeError("vLLM never became ready")
    print(f"[ready] vLLM up after {time.time()-t0:.0f}s\n", flush=True)

    out = {"model": model, "load_seconds": round(time.time() - t0)}

    def gen(label, **kw):
        t = time.time()
        r = client.chat.completions.create(model=model, **kw)
        m = r.choices[0].message
        rc = getattr(m, "reasoning_content", None)
        print(f"\n===== {label} ({time.time()-t:.1f}s, "
              f"compl={r.usage.completion_tokens if r.usage else '?'} tok) =====", flush=True)
        if rc:
            print(f"[reasoning {len(rc)} chars]\n{rc[:800]}\n", flush=True)
        print(m.content or "(no content)", flush=True)
        return m

    # 1) reasoning
    gen("1) REASONING", temperature=1.0, top_p=0.95, max_tokens=1500,
        messages=[{"role": "user", "content": PROMPTS["reasoning"]}])

    # 2) tool calling
    tools = [{"type": "function", "function": {
        "name": "get_weather", "description": "Get current weather for a city.",
        "parameters": {"type": "object",
                       "properties": {"city": {"type": "string"},
                                      "unit": {"type": "string", "enum": ["c", "f"]}},
                       "required": ["city"]}}}]
    msgs = [{"role": "user", "content": "What's the weather in Tokyo? Use the tool, then answer in Celsius."}]
    m = gen("2) TOOL CALL r1", messages=msgs, tools=tools, temperature=1.0)
    tool_ok = bool(m.tool_calls)
    print(f">>> tool_called = {tool_ok}", flush=True)
    if tool_ok:
        msgs.append(m)
        for tc in m.tool_calls:
            args = json.loads(tc.function.arguments)
            print(f">>> {tc.function.name}({args})", flush=True)
            msgs.append({"role": "tool", "tool_call_id": tc.id,
                         "content": json.dumps({"city": args.get("city"), "temp_c": 22, "condition": "clear"})})
        gen("2) TOOL CALL final", messages=msgs, tools=tools, temperature=1.0)

    # 3) coding
    gen("3) CODING", temperature=1.0, top_p=0.95, max_tokens=2000,
        messages=[{"role": "user", "content": PROMPTS["coding"]}])

    out["tool_called"] = tool_ok
    print(f"\n### SUMMARY {json.dumps(out)}", flush=True)
    proc.terminate()
    return out


@app.function(image=vllm_image, gpu="H100:4", volumes={CACHE_DIR: minimax_vol}, timeout=60 * 60)
def bench_m27():
    return _serve_and_bench("MiniMaxAI/MiniMax-M2.7", 4, "minimax_m2", "minimax_m2_append_think")


@app.function(image=vllm_image, gpu="H200:8", volumes={CACHE_DIR: kimi_vol}, timeout=60 * 60)
def bench_kimi():
    return _serve_and_bench("moonshotai/Kimi-K2.7-Code", 8, "kimi_k2", "kimi_k2",
                            extra_args=["--mm-encoder-tp-mode", "data"])
