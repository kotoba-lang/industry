"""Does the vision path of SDAR-VL actually reach the diffusion trunk?

A vision-language model that silently ignores its image still produces fluent,
plausible text -- the same failure shape that made an inverted attention mask
look like a working measurement earlier in this work.  So the check here is not
"did it answer" but "does the answer DEPEND on the image".

Two probes, in increasing cost:

  1. logit divergence.  Same prompt, two different images, compare the logits at
     the masked positions.  If the image never reaches the trunk the two forwards
     are bit-identical.  This is cheap and decisive and needs no generation.
  2. generation.  Block-diffusion decode for each image, printed for reading.

The negative control is built into probe 1: identical logits FAIL the probe.
Without it, two fluent answers about circles and squares would be indistinguishable
from a model reciting priors.

Also reports where an audio encoder would attach, since that is the actual gap:
image and video placeholders are already wired in this checkpoint's config.
"""

import argparse
import io
import sys

import torch
from PIL import Image, ImageDraw

MASK_ID = 151669
EOS_IDS = (151645, 151643)


def make_image(kind, size=384):
    """Deterministic, no external asset, visibly different at any resolution."""
    im = Image.new("RGB", (size, size), (255, 255, 255))
    d = ImageDraw.Draw(im)
    m = size // 6
    if kind == "red_circle":
        d.ellipse([m, m, size - m, size - m], fill=(220, 30, 30))
    elif kind == "blue_square":
        d.rectangle([m, m, size - m, size - m], fill=(30, 60, 220))
    else:
        raise ValueError(kind)
    return im


def block_causal_mask(n_prompt, n_gen, block, device):
    L = n_prompt + n_gen
    i = torch.arange(L, device=device)
    allow = i[None, :] <= i[:, None]
    g = i - n_prompt
    in_gen = g >= 0
    blk = torch.where(in_gen, torch.div(g, block, rounding_mode="floor"), -1)
    return (allow | ((blk[:, None] == blk[None, :]) & in_gen[:, None] & in_gen[None, :]))[None, None]


@torch.no_grad()
def forward_with_masks(model, inputs, n_mask, block, device):
    """Append n_mask <MASK> positions and run one forward. Returns (logits_at_masks, n_prompt)."""
    ids = inputs["input_ids"]
    n_prompt = ids.shape[1]
    cur = torch.cat([ids, torch.full((1, n_mask), MASK_ID, device=device, dtype=ids.dtype)], 1)
    kw = {k: v for k, v in inputs.items() if k != "input_ids" and k != "attention_mask"}
    out = model(input_ids=cur,
                attention_mask=block_causal_mask(n_prompt, n_mask, block, device),
                **kw)
    return out.logits[0, n_prompt:].float(), n_prompt


@torch.no_grad()
def decode(model, tok, inputs, block, max_new, threshold, device):
    ids = inputs["input_ids"]
    n_prompt = ids.shape[1]
    kw = {k: v for k, v in inputs.items() if k not in ("input_ids", "attention_mask")}
    seq, committed, forwards, hit = ids, [], 0, False
    while len(committed) < max_new and not hit:
        blk = min(block, max_new - len(committed))
        cur = torch.cat([seq, torch.full((1, blk), MASK_ID, device=device, dtype=ids.dtype)], 1)
        n_gen = cur.shape[1] - n_prompt
        pending = list(range(cur.shape[1] - blk, cur.shape[1]))
        while pending:
            logits = model(input_ids=cur,
                           attention_mask=block_causal_mask(n_prompt, n_gen, block, device),
                           **kw).logits
            forwards += 1
            probs = torch.softmax(logits[0, pending].float(), -1)
            conf, pick = probs.max(-1)
            take = (conf >= threshold).nonzero(as_tuple=True)[0].tolist() or [int(conf.argmax())]
            for j in sorted(take, reverse=True):
                cur[0, pending[j]] = pick[j]
            for j in sorted(take, reverse=True):
                pending.pop(j)
        for t in cur[0, seq.shape[1]:].tolist():
            committed.append(t)
            if t in EOS_IDS:
                hit = True
                break
        seq = cur
    return tok.decode(committed, skip_special_tokens=True), forwards, len(committed)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="/home/gad/dllm-phase0/SDAR-VL-Think-8B")
    ap.add_argument("--block", type=int, default=4)
    ap.add_argument("--threshold", type=float, default=0.9)
    ap.add_argument("--max-new", type=int, default=64)
    args = ap.parse_args()

    from transformers import AutoProcessor, AutoModelForCausalLM
    import transformers as _tf
    dtype_kw = "dtype" if int(_tf.__version__.split(".")[0]) >= 5 else "torch_dtype"
    print(f"transformers {_tf.__version__}", flush=True)

    proc = AutoProcessor.from_pretrained(args.model, trust_remote_code=True)
    model = AutoModelForCausalLM.from_pretrained(
        args.model, trust_remote_code=True, attn_implementation="eager",
        **{dtype_kw: torch.bfloat16},
    ).to("cuda").eval()
    tok = proc.tokenizer if hasattr(proc, "tokenizer") else None
    print("loaded", type(model).__name__,
          f"{sum(p.numel() for p in model.parameters())/1e9:.2f}B params", flush=True)

    question = "What shape and what color is in this image? Answer in a few words."
    packed = {}
    for kind in ("red_circle", "blue_square"):
        msgs = [{"role": "user", "content": [{"type": "image"}, {"type": "text", "text": question}]}]
        prompt = proc.apply_chat_template(msgs, add_generation_prompt=True, tokenize=False)
        inputs = proc(images=make_image(kind), text=prompt, return_tensors="pt")
        packed[kind] = {k: (v.to("cuda") if hasattr(v, "to") else v) for k, v in inputs.items()}
        print(f"  {kind}: input_ids {packed[kind]['input_ids'].shape[1]} tokens, "
              f"keys={sorted(packed[kind].keys())}", flush=True)

    print("\n--- probe 1: does the answer depend on the image? ---", flush=True)
    la, _ = forward_with_masks(model, packed["red_circle"], 8, args.block, "cuda")
    lb, _ = forward_with_masks(model, packed["blue_square"], 8, args.block, "cuda")
    delta = (la - lb).abs().max().item()
    print(f"max|logit delta| between the two images = {delta:.4f}")
    if delta < 1e-3:
        print("FAIL: identical logits -- the image is NOT reaching the trunk. "
              "Any text produced below would be the model reciting priors, not seeing.")
        sys.exit(1)
    print("PASS: the image changes the trunk's distribution.")

    print("\n--- probe 2: what does it actually say? ---", flush=True)
    for kind in ("red_circle", "blue_square"):
        txt, fwd, n = decode(model, proc.tokenizer, packed[kind],
                             args.block, args.max_new, args.threshold, "cuda")
        print(f"  [{kind}] ({n} tok / {fwd} fwd = {n/max(fwd,1):.3f}) {txt!r}", flush=True)


if __name__ == "__main__":
    main()
