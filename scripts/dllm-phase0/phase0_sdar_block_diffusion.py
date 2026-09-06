"""Phase 0 gate: does a genuine block-diffusion trunk commit >1 token per forward?

ADR-2609040130 measured SDAR-4B on MLX with *causal* weights (config rewritten to
Qwen3-4B) and found k=4/8/16 all degenerate with tok/forward < 1.  Its conclusion
was that the gain depends structurally on a bidirectional block-diffusion trunk.

This script tests that conclusion on the real SDAR weights with the real
block-causal attention mask.  The primary metric is a COUNT (tokens committed per
forward pass), not wall clock -- gad runs ComfyUI and llama-server concurrently,
so wall clock here is a contended lower bound and is reported as such.

What would make this measurement worthless, and how each is refused:

  * Counting a run that produced garbage as a "gain".  -> every run prints its
    full decoded text, and a degeneracy check (distinct-token ratio, longest
    immediate repeat) is computed and printed next to the ratio.
  * Reporting a pass when the mask never actually differed from causal.  -> the
    mask builder is asserted against a hand-computed reference, and the AR
    control (block=1) must come out at exactly 1.000 tok/forward.
  * Silently falling back to causal attention if the 4D mask is ignored.  -> a
    probe asserts that a bidirectional block mask changes the logits.
"""

import argparse
import json
import time

import torch
from transformers import AutoModelForCausalLM, AutoTokenizer

MASK_ID = 151669  # <MASK> in added_tokens.json
EOS_IDS = (151645, 151643)


def block_causal_mask(n_prompt, n_gen, block, dtype, device):
    """Additive 4D mask: causal everywhere, bidirectional inside each gen block.

    Returns bool (1, 1, L, L).  True = attend.  Rides the 4D passthrough in
    modeling_sdar.py (`if attention_mask.dim() == 4: causal_mask = attention_mask`)
    down to SDARAttention, which calls .bool() on it and gives it to SDPA.
    """
    L = n_prompt + n_gen
    idx = torch.arange(L, device=device)
    allow = idx[None, :] <= idx[:, None]  # causal

    if n_gen > 0:
        g = idx - n_prompt  # position within the generation region
        in_gen = g >= 0
        blk = torch.where(in_gen, torch.div(g, block, rounding_mode="floor"), -1)
        same_block = (blk[:, None] == blk[None, :]) & in_gen[:, None] & in_gen[None, :]
        allow = allow | same_block  # bidirectional within a block

    # SDARAttention.forward does `attention_mask.bool()` and hands the result to
    # F.scaled_dot_product_attention as `attn_mask` with is_causal=False, so the
    # contract is a BOOLEAN mask, True = attend.  Passing the usual additive float
    # mask inverts it silently (0.0 -> False, -inf -> True): measured 2026-09-06,
    # it produced fluent-looking machinery and degenerate text.
    return allow[None, None]


def _assert_mask_is_right():
    a = block_causal_mask(2, 4, 2, torch.float32, "cpu")[0, 0]
    assert a.dtype == torch.bool, "SDAR wants a bool mask, not an additive one"
    # prompt stays causal
    assert a[0, 0] and not a[0, 1], "prompt row 0 must not see the future"
    # inside block 0 (gen positions 2,3) attention is symmetric
    assert a[2, 3] and a[3, 2], "block 0 must be bidirectional"
    # block 1 (gen 4,5) sees block 0, block 0 does not see block 1
    assert a[4, 2] and a[5, 3], "later block must see earlier block"
    assert not a[2, 4] and not a[3, 5], "earlier block must not see later block"
    assert a[4, 5] and a[5, 4], "block 1 must be bidirectional"
    # block=1 degenerates to pure causal
    m1 = block_causal_mask(2, 4, 1, torch.float32, "cpu")[0, 0]
    ref = torch.arange(6)[None, :] <= torch.arange(6)[:, None]
    assert torch.equal(m1, ref), "block=1 must be exactly causal"


def degeneracy(ids):
    """Cheap structural checks for the failure mode ADR-2609040130 hit."""
    if not ids:
        return {"distinct_ratio": 0.0, "longest_immediate_repeat": 0}
    distinct = len(set(ids)) / len(ids)
    best = cur = 1
    for a, b in zip(ids, ids[1:]):
        cur = cur + 1 if a == b else 1
        best = max(best, cur)
    return {"distinct_ratio": round(distinct, 3), "longest_immediate_repeat": best}


@torch.no_grad()
def decode(model, tok, prompt_ids, block, max_new, threshold, device, greedy=True):
    """Block-diffusion decode with low-confidence-dynamic remasking.

    No KV cache: every denoising step is a full forward.  That makes wall clock
    pessimistic but leaves tok/forward -- the number this gate is about --
    exactly right, and keeps the mask logic auditable.
    """
    dtype = next(model.parameters()).dtype
    n_prompt = len(prompt_ids)
    seq = torch.tensor([prompt_ids], device=device)
    committed = []
    forwards = 0
    hit_eos = False
    t0 = time.time()

    while len(committed) < max_new and not hit_eos:
        blk = min(block, max_new - len(committed))
        cur = torch.cat(
            [seq, torch.full((1, blk), MASK_ID, device=device, dtype=seq.dtype)], dim=1
        )
        n_gen = cur.shape[1] - n_prompt
        pending = list(range(cur.shape[1] - blk, cur.shape[1]))

        while pending:
            mask = block_causal_mask(n_prompt, n_gen, block, dtype, device)
            logits = model(input_ids=cur, attention_mask=mask).logits
            forwards += 1
            probs = torch.softmax(logits[0, pending].float(), dim=-1)
            conf, pick = probs.max(dim=-1)
            if greedy:
                chosen = pick
            else:
                chosen = torch.multinomial(probs, 1).squeeze(-1)

            take = (conf >= threshold).nonzero(as_tuple=True)[0].tolist()
            if not take:  # guarantee progress: commit the single most confident
                take = [int(conf.argmax())]

            for j in sorted(take, reverse=True):
                cur[0, pending[j]] = chosen[j]
            for j in sorted(take, reverse=True):
                pending.pop(j)

        new = cur[0, seq.shape[1]:].tolist()
        for t in new:
            committed.append(t)
            if t in EOS_IDS:
                hit_eos = True
                break
        seq = cur

    wall = time.time() - t0
    return {
        "tokens": len(committed),
        "forwards": forwards,
        "tok_per_forward": round(len(committed) / max(forwards, 1), 3),
        "wall_s": round(wall, 2),
        "tok_per_s_contended": round(len(committed) / wall, 2) if wall else None,
        "text": tok.decode(committed, skip_special_tokens=True),
        **degeneracy(committed),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="/home/gad/dllm-phase0/SDAR-1.7B-Chat")
    ap.add_argument("--blocks", default="1,4,8,16")
    ap.add_argument("--max-new", type=int, default=96)
    ap.add_argument("--threshold", type=float, default=0.9)
    ap.add_argument("--out", default="/home/gad/dllm-phase0/phase0-results.json")
    args = ap.parse_args()

    _assert_mask_is_right()
    print("mask reference check: ok", flush=True)

    device = "cuda"
    tok = AutoTokenizer.from_pretrained(args.model, trust_remote_code=True)
    model = AutoModelForCausalLM.from_pretrained(
        args.model, trust_remote_code=True, torch_dtype=torch.bfloat16,
        attn_implementation="eager",
    ).to(device).eval()
    print("loaded", model.config.model_type, sum(p.numel() for p in model.parameters()) / 1e9, "B params", flush=True)

    prompts = {
        "code": "Write a Python function `merge_intervals(intervals)` that merges overlapping intervals. Return only the code.",
        "ja": "拡散言語モデルが自己回帰モデルより速くなりうる理由を、3行で説明してください。",
    }

    # Probe: the 4D mask must actually reach attention.  If it were ignored, a
    # bidirectional block mask and a causal mask would give identical logits.
    ids = tok.apply_chat_template(
        [{"role": "user", "content": prompts["code"]}], add_generation_prompt=True, tokenize=True,
    )
    probe = torch.cat(
        [torch.tensor([ids], device=device), torch.full((1, 8), MASK_ID, device=device)], dim=1
    )
    with torch.no_grad():
        la = model(input_ids=probe,
                   attention_mask=block_causal_mask(len(ids), 8, 8, torch.bfloat16, device)).logits
        lb = model(input_ids=probe,
                   attention_mask=block_causal_mask(len(ids), 8, 1, torch.bfloat16, device)).logits
    delta = (la - lb).abs().max().item()
    print(f"mask reaches attention: max|logit delta| = {delta:.4f}", flush=True)
    assert delta > 1e-3, "4D mask had no effect -- attention ignored it; measurement is void"

    results = []
    for name, text in prompts.items():
        pid = tok.apply_chat_template(
            [{"role": "user", "content": text}], add_generation_prompt=True, tokenize=True,
        )
        for b in [int(x) for x in args.blocks.split(",")]:
            r = decode(model, tok, pid, b, args.max_new, args.threshold, device)
            r.update(prompt=name, block=b, threshold=args.threshold)
            results.append(r)
            print(
                f"[{name}] block={b:>2} tok/forward={r['tok_per_forward']:<6} "
                f"tokens={r['tokens']:<4} forwards={r['forwards']:<4} "
                f"distinct={r['distinct_ratio']:<6} rep={r['longest_immediate_repeat']:<3} "
                f"wall={r['wall_s']}s",
                flush=True,
            )
            print("    " + r["text"][:200].replace("\n", "\\n"), flush=True)

    with open(args.out, "w") as f:
        json.dump({"model": args.model, "results": results}, f, indent=1, ensure_ascii=False)
    print("wrote", args.out, flush=True)


if __name__ == "__main__":
    main()
