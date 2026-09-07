"""SDAR-30B-A3B on one H200, in bf16, so the point lands on the same curve.

gad could not do this. 56.9 GiB of weights against 48 GiB of VRAM means experts
spill, and top-8-of-128 routing makes the spill fatal rather than merely slow:
with ~74% of experts resident, the chance that all eight experts a layer needs
are on the GPU is 0.74^8 ~ 9%, so ~91% of layers touch host memory every forward.
The observed result was GPU at 0%, 32 GiB of swap, and no output after 11 hours.

An H200 has 141 GB, so the whole model is resident and none of that applies.

Kept identical to the 1.7B and 8B runs so the numbers are comparable:
  - bf16, no quantization (DLLMQuant measured that quantization error accumulates
    across denoising steps, which is exactly where this measurement lives)
  - transformers 4.52.4 (the modeling file imports LossKwargs, gone in 5.x)
  - the same pure-PyTorch rms_norm shim, verified bit-identical to the reference,
    rather than real flash-attn -- a different RMSNorm would be a different model
  - the same 30-task set, threshold 0.9, greedy, max_new 256

REFUSALS
  - if any parameter lands off-GPU the run aborts; a partly-offloaded run would
    reproduce the failure this exists to escape, and report it as a slow success
  - placement is counted and printed, so "it fit" is measured
"""

import modal

MODEL = "JetLM/SDAR-30B-A3B-Chat"
VOL = modal.Volume.from_name("sdar-30b", create_if_missing=True)
CACHE = "/models"

image = (
    modal.Image.debian_slim(python_version="3.12")
    .pip_install(
        "torch==2.6.0",
        "transformers==4.52.4",
        "accelerate",
        "huggingface_hub",
        "safetensors",
        "numpy",
    )
    # The modeling file does `from flash_attn.ops.triton.layer_norm import rms_norm_fn`
    # at import time and uses it as its RMSNorm.  Same shim as the gad runs, so the
    # numerical path matches the 1.7B and 8B points rather than merely resembling them.
    .run_commands(
        "mkdir -p /usr/local/lib/python3.12/site-packages/flash_attn/ops/triton",
        "printf '__version__ = \"0.0.0-shim\"\\n' > /usr/local/lib/python3.12/site-packages/flash_attn/__init__.py",
        "touch /usr/local/lib/python3.12/site-packages/flash_attn/ops/__init__.py",
        "touch /usr/local/lib/python3.12/site-packages/flash_attn/ops/triton/__init__.py",
        "printf 'import torch\\n"
        "def rms_norm_fn(x, weight=None, bias=None, eps=1e-6, residual=None, prenorm=False,"
        " residual_in_fp32=False, dropout_p=0.0, **kw):\\n"
        "    if residual is not None or prenorm or dropout_p:\\n"
        "        raise NotImplementedError(\"shim implements plain RMSNorm only\")\\n"
        "    dtype = x.dtype\\n"
        "    xf = x.float()\\n"
        "    xf = xf * torch.rsqrt(xf.pow(2).mean(-1, keepdim=True) + eps)\\n"
        "    out = xf.to(dtype)\\n"
        "    if weight is not None: out = out * weight\\n"
        "    if bias is not None: out = out + bias\\n"
        "    return out\\n' > /usr/local/lib/python3.12/site-packages/flash_attn/ops/triton/layer_norm.py",
        "mkdir -p /usr/local/lib/python3.12/site-packages/flash_attn-0.0.0.dist-info",
        "printf 'Metadata-Version: 2.1\\nName: flash-attn\\nVersion: 0.0.0\\n' > /usr/local/lib/python3.12/site-packages/flash_attn-0.0.0.dist-info/METADATA",
        "printf 'flash_attn\\n' > /usr/local/lib/python3.12/site-packages/flash_attn-0.0.0.dist-info/top_level.txt",
        "touch /usr/local/lib/python3.12/site-packages/flash_attn-0.0.0.dist-info/RECORD",
    )
)

app = modal.App("sdar-30b-eval", image=image)

MASK_ID = 151669
EOS_IDS = (151645, 151643)


@app.function(volumes={CACHE: VOL}, timeout=60 * 60, cpu=4.0)
def fetch():
    from huggingface_hub import snapshot_download
    import os
    p = snapshot_download(MODEL, local_dir=f"{CACHE}/SDAR-30B-A3B-Chat")
    tot = sum(os.path.getsize(os.path.join(a, f)) for a, _, fs in os.walk(p) for f in fs)
    VOL.commit()
    return f"{tot/2**30:.2f} GiB"


@app.function(gpu="H200", volumes={CACHE: VOL}, timeout=3 * 60 * 60, memory=32768)
def evaluate(tasks_json: str, blocks: str = "1,4", max_new: int = 256, threshold: float = 0.9):
    import json, re, subprocess, sys, tempfile, textwrap, time
    import torch
    from transformers import AutoModelForCausalLM, AutoTokenizer

    path = f"{CACHE}/SDAR-30B-A3B-Chat"
    tok = AutoTokenizer.from_pretrained(path, trust_remote_code=True)
    t0 = time.time()
    model = AutoModelForCausalLM.from_pretrained(
        path, trust_remote_code=True, torch_dtype=torch.bfloat16,
        attn_implementation="eager",
    ).to("cuda").eval()
    n_par = sum(p.numel() for p in model.parameters())
    off = [n for n, p in model.named_parameters() if "cuda" not in str(p.device)]
    if off:
        raise SystemExit(f"REFUSING: {len(off)} parameters are not on the GPU "
                         f"(e.g. {off[0]}). A partly offloaded run would reproduce "
                         f"the failure this job exists to escape.")
    free, total = torch.cuda.mem_get_info()
    print(f"loaded {n_par/1e9:.2f}B params in {time.time()-t0:.0f}s, all on GPU; "
          f"VRAM {(total-free)/2**30:.1f}/{total/2**30:.1f} GiB used", flush=True)

    def bcm(n_prompt, n_gen, b):
        L = n_prompt + n_gen
        i = torch.arange(L, device="cuda")
        allow = i[None, :] <= i[:, None]
        g = i - n_prompt
        ing = g >= 0
        blk = torch.where(ing, torch.div(g, b, rounding_mode="floor"), -1)
        return (allow | ((blk[:, None] == blk[None, :]) & ing[:, None] & ing[None, :]))[None, None]

    @torch.no_grad()
    def decode(ids, block):
        n_prompt = len(ids)
        seq = torch.tensor([ids], device="cuda")
        got, fwd, hit = [], 0, False
        while len(got) < max_new and not hit:
            b = min(block, max_new - len(got))
            cur = torch.cat([seq, torch.full((1, b), MASK_ID, device="cuda", dtype=seq.dtype)], 1)
            ng = cur.shape[1] - n_prompt
            pend = list(range(cur.shape[1] - b, cur.shape[1]))
            while pend:
                lg = model(input_ids=cur, attention_mask=bcm(n_prompt, ng, block)).logits
                fwd += 1
                pr = torch.softmax(lg[0, pend].float(), -1)
                cf, pk = pr.max(-1)
                take = (cf >= threshold).nonzero(as_tuple=True)[0].tolist() or [int(cf.argmax())]
                for j in sorted(take, reverse=True):
                    cur[0, pend[j]] = pk[j]
                for j in sorted(take, reverse=True):
                    pend.pop(j)
            for t in cur[0, seq.shape[1]:].tolist():
                got.append(t)
                if t in EOS_IDS:
                    hit = True
                    break
            seq = cur
        return got, fwd

    def extract(text):
        m = re.search(r"```(?:python)?\n(.*?)```", text, re.S)
        body = m.group(1) if m else text
        out, started = [], False
        for ln in body.split("\n"):
            if ln.startswith(("def ", "import ", "from ", "class ")):
                started = True
            if started:
                out.append(ln)
        return "\n".join(out).strip()

    def check(code, tests):
        if not code or ("def " not in code and "class " not in code):
            return False
        prog = code + "\n" + textwrap.dedent(tests) + "\nprint('OK')\n"
        with tempfile.NamedTemporaryFile("w", suffix=".py", delete=False) as f:
            f.write(prog); p = f.name
        try:
            r = subprocess.run([sys.executable, p], capture_output=True, text=True, timeout=15)
            return "OK" in r.stdout
        except subprocess.TimeoutExpired:
            return False

    tasks = json.loads(tasks_json)
    print(f"loaded {len(tasks)} tasks", flush=True)
    rows = []
    for block in [int(x) for x in blocks.split(",")]:
        passed = toks = fwds = 0
        t1 = time.time()
        detail = []
        for t in tasks:
            ids = tok.apply_chat_template(
                [{"role": "user", "content": t["prompt"]}],
                add_generation_prompt=True, tokenize=True)
            out, f = decode(ids, block)
            toks += len(out); fwds += f
            ok = check(extract(tok.decode(out, skip_special_tokens=True)), t["tests"])
            passed += int(ok)
            detail.append(f"{t['name']}={'PASS' if ok else 'fail'}")
        row = {"block": block, "pass": passed, "of": len(tasks),
               "tok_per_forward": round(toks / max(fwds, 1), 3),
               "tokens": toks, "forwards": fwds,
               "wall_s": round(time.time() - t1), "detail": detail}
        rows.append(row)
        print(f"block={block:<3} pass={passed}/{len(tasks)} "
              f"tok/forward={row['tok_per_forward']:<6} forwards={fwds} "
              f"wall={row['wall_s']}s", flush=True)
    return rows


@app.local_entrypoint()
def main(tasks: str = "tasks-python-30.json", blocks: str = "1,4"):
    import json, pathlib
    print("fetching weights into the volume (cached across runs)...")
    print("  ", fetch.remote())
    payload = pathlib.Path(tasks).read_text()
    rows = evaluate.remote(payload, blocks)
    print("\n=== SDAR-30B-A3B, bf16, H200, 30 tasks ===")
    for r in rows:
        print(f"  block={r['block']:<3} pass={r['pass']}/{r['of']}  "
              f"tok/forward={r['tok_per_forward']:<6} forwards={r['forwards']}  "
              f"wall={r['wall_s']}s")
    pathlib.Path("sdar30b-modal-results.json").write_text(json.dumps(rows, indent=1))
    print("wrote sdar30b-modal-results.json")
