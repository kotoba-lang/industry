"""Run SDAR-30B-A3B on one 48 GiB card by placing experts individually.

The model is 56.8 GiB in bf16 and the card is 48 GiB, but 95% of those weights
are experts (54.0 of 56.8), and the index shows every expert is its own module:

    model.layers.{0..47}.mlp.experts.{0..127}.{gate,up,down}_proj.weight
    18,432 expert tensors vs 435 shared

So accelerate can place them one at a time. Shared weights and as many experts
as fit go to the GPU; the rest sit in host RAM and are fetched on use. No
quantization, so the numbers stay comparable to the bf16 1.7B and 8B points in
ADR-2609063000 -- which matters, because DLLMQuant measured that quantization
error ACCUMULATES across denoising steps in a dLLM, exactly where this
measurement lives.

Top-8-of-128 routing means a given forward touches 8 experts per layer, so most
forwards should hit resident ones. And block diffusion helps here for the same
reason it helps when sharded: ADR-2609063000 measured 1146 forwards for AR
against 492 for block=4 on the same work, so offload traffic rounds drop 2.36x
too.

REFUSALS
  * If the model lands entirely on CPU the run is aborted rather than reported
    slowly -- a CPU-only forward would produce a real number that answers a
    different question.
  * The placement actually chosen is printed and counted, so "it fit" is a
    measurement rather than an assumption.
"""

import argparse
import json
import time

import torch


def report_placement(model):
    """Count where parameters actually landed. Refuse a CPU-only placement."""
    per = {}
    expert_dev = {}
    for name, p in model.named_parameters():
        d = str(p.device)
        per[d] = per.get(d, 0) + p.numel()
        if ".experts." in name:
            expert_dev[d] = expert_dev.get(d, 0) + p.numel()
    tot = sum(per.values())
    print("  placement:", flush=True)
    for d, n in sorted(per.items(), key=lambda kv: -kv[1]):
        print(f"    {d:<8} {n/1e9:6.2f}B  ({100*n/tot:5.1f}%)  bf16 {n*2/2**30:6.1f} GiB", flush=True)
    etot = sum(expert_dev.values())
    if etot:
        on_gpu = sum(v for k, v in expert_dev.items() if "cuda" in k)
        print(f"    experts on GPU: {100*on_gpu/etot:.1f}%", flush=True)
    gpu = sum(v for k, v in per.items() if "cuda" in k)
    if gpu == 0:
        raise SystemExit("REFUSING: nothing landed on the GPU. A CPU-only run would "
                         "produce a real number for a different question.")
    return per


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="/home/gad/dllm-phase0/SDAR-30B-A3B-Chat")
    ap.add_argument("--gpu-gib", type=int, default=40)
    ap.add_argument("--cpu-gib", type=int, default=16)
    ap.add_argument("--blocks", default="1,4")
    ap.add_argument("--tasks", default="/home/gad/dllm-phase0/tasks-python-30.json")
    ap.add_argument("--max-new", type=int, default=256)
    ap.add_argument("--threshold", type=float, default=0.9)
    ap.add_argument("--out", default="/home/gad/dllm-phase0/sdar30b-results.json")
    args = ap.parse_args()

    import sys
    sys.path.insert(0, "/home/gad/dllm-phase0")
    from phase0_exec_eval import decode, extract_code, run_tests
    from transformers import AutoModelForCausalLM, AutoTokenizer

    tok = AutoTokenizer.from_pretrained(args.model, trust_remote_code=True)
    t0 = time.time()
    model = AutoModelForCausalLM.from_pretrained(
        args.model, trust_remote_code=True, torch_dtype=torch.bfloat16,
        attn_implementation="eager",
        device_map="auto",
        max_memory={0: f"{args.gpu_gib}GiB", "cpu": f"{args.cpu_gib}GiB"},
    ).eval()
    print(f"loaded in {time.time()-t0:.0f}s: "
          f"{sum(p.numel() for p in model.parameters())/1e9:.2f}B params", flush=True)
    report_placement(model)

    tasks = json.load(open(args.tasks))
    print(f"loaded {len(tasks)} tasks", flush=True)
    rows = []
    for block in [int(x) for x in args.blocks.split(",")]:
        passed, toks, fwds, notes = 0, 0, 0, []
        t1 = time.time()
        for t in tasks:
            ids = tok.apply_chat_template(
                [{"role": "user", "content": t["prompt"]}],
                add_generation_prompt=True, tokenize=True)
            out, f = decode(model, ids, block, args.max_new, args.threshold, "cuda")
            toks += len(out); fwds += f
            ok, err = run_tests(extract_code(tok.decode(out, skip_special_tokens=True)), t["tests"])
            passed += int(ok)
            notes.append(f"{t['name']}={'PASS' if ok else 'fail'}")
        row = {"block": block, "threshold": args.threshold, "pass": passed, "of": len(tasks),
               "tok_per_forward": round(toks / max(fwds, 1), 3), "tokens": toks,
               "forwards": fwds, "wall_s": round(time.time() - t1), "detail": notes}
        rows.append(row)
        print(f"block={block:<3} pass={passed}/{len(tasks)} "
              f"tok/forward={row['tok_per_forward']:<6} forwards={fwds} "
              f"wall={row['wall_s']}s", flush=True)

    json.dump({"model": args.model, "rows": rows}, open(args.out, "w"), indent=1, ensure_ascii=False)
    print("wrote", args.out, flush=True)


if __name__ == "__main__":
    main()
