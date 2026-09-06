"""Is SDAR-VL's video path actually usable, or only configured?

config.json sets video_token_index 151656 and the repo ships a
video_preprocessor_config.json.  That is a reading, not a measurement -- a
declared path that raises, or that silently averages frames into mush, looks
the same from the config.  So measure two separate things:

  A. Does video input reach the trunk at all?
     Two clips that differ in content (red circle vs blue square).  Identical
     logits => the video never arrived.

  B. Does TEMPORAL ORDER reach the trunk?
     The same frames in forward vs reversed order.  Identical logits => frames
     are being pooled order-free, and the model cannot represent motion.  This
     is the difference between "reads a video" and "reads a bag of stills", and
     it decides whether a world-model path can sit on this trunk at all.

B is the one worth having: a model can pass A while failing B, and a config can
never tell you which.
"""

import argparse
import sys

import numpy as np
import torch
from PIL import Image, ImageDraw

MASK_ID = 151669


def frame(x_frac, color, size=384):
    """A single frame with a disc at horizontal position x_frac in [0,1]."""
    im = Image.new("RGB", (size, size), (255, 255, 255))
    d = ImageDraw.Draw(im)
    r = size // 8
    cx = int(r + x_frac * (size - 2 * r))
    cy = size // 2
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=color)
    return im


def clip(kind, n=8, size=384):
    if kind == "red_circle":
        return [frame(0.5, (220, 30, 30), size) for _ in range(n)]
    if kind == "blue_square":
        ims = []
        for _ in range(n):
            im = Image.new("RGB", (size, size), (255, 255, 255))
            m = size // 6
            ImageDraw.Draw(im).rectangle([m, m, size - m, size - m], fill=(30, 60, 220))
            ims.append(im)
        return ims
    if kind == "move_right":
        return [frame(i / (n - 1), (20, 20, 20), size) for i in range(n)]
    if kind == "move_left":
        return [frame(1 - i / (n - 1), (20, 20, 20), size) for i in range(n)]
    raise ValueError(kind)


def block_causal_mask(n_prompt, n_gen, block, device):
    L = n_prompt + n_gen
    i = torch.arange(L, device=device)
    allow = i[None, :] <= i[:, None]
    g = i - n_prompt
    in_gen = g >= 0
    blk = torch.where(in_gen, torch.div(g, block, rounding_mode="floor"), -1)
    return (allow | ((blk[:, None] == blk[None, :]) & in_gen[:, None] & in_gen[None, :]))[None, None]


@torch.no_grad()
def mask_logits(model, inputs, n_mask, block, device):
    ids = inputs["input_ids"]
    n_prompt = ids.shape[1]
    cur = torch.cat([ids, torch.full((1, n_mask), MASK_ID, device=device, dtype=ids.dtype)], 1)
    kw = {k: v for k, v in inputs.items() if k not in ("input_ids", "attention_mask")}
    out = model(input_ids=cur,
                attention_mask=block_causal_mask(n_prompt, n_mask, block, device), **kw)
    return out.logits[0, n_prompt:].float()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="/home/gad/dllm-phase0/SDAR-VL-Think-8B")
    ap.add_argument("--block", type=int, default=4)
    ap.add_argument("--frames", type=int, default=8)
    args = ap.parse_args()

    from transformers import AutoProcessor, AutoModelForCausalLM
    import transformers as _tf
    dk = "dtype" if int(_tf.__version__.split(".")[0]) >= 5 else "torch_dtype"

    proc = AutoProcessor.from_pretrained(args.model, trust_remote_code=True)
    model = AutoModelForCausalLM.from_pretrained(
        args.model, trust_remote_code=True, attn_implementation="eager", **{dk: torch.bfloat16},
    ).to("cuda").eval()
    print("loaded", type(model).__name__, flush=True)

    q = "Describe what happens in this video in one sentence."

    def pack(kind):
        vid = np.stack([np.array(im) for im in clip(kind, args.frames)])  # (T,H,W,C)
        msgs = [{"role": "user", "content": [{"type": "video"}, {"type": "text", "text": q}]}]
        prompt = proc.apply_chat_template(msgs, add_generation_prompt=True, tokenize=False)
        inp = proc(videos=[vid], text=prompt, return_tensors="pt")
        return {k: (v.to("cuda") if hasattr(v, "to") else v) for k, v in inp.items()}

    print("\n--- A: does video input reach the trunk at all? ---", flush=True)
    try:
        a, b = pack("red_circle"), pack("blue_square")
    except Exception as e:
        print(f"REFUSED: the processor could not build a video input: {type(e).__name__}: {e}")
        print("=> the video path is CONFIGURED but not USABLE through this processor.")
        sys.exit(2)
    print(f"  input_ids: {a['input_ids'].shape[1]} tokens, keys={sorted(a.keys())}", flush=True)
    da = (mask_logits(model, a, 8, args.block, "cuda")
          - mask_logits(model, b, 8, args.block, "cuda")).abs().max().item()
    print(f"  max|logit delta| content A vs B = {da:.4f}")
    print("  PASS: video content reaches the trunk." if da > 1e-3 else
          "  FAIL: identical logits -- video is NOT reaching the trunk.")

    print("\n--- B: does temporal ORDER reach the trunk? ---", flush=True)
    r, l = pack("move_right"), pack("move_left")
    db = (mask_logits(model, r, 8, args.block, "cuda")
          - mask_logits(model, l, 8, args.block, "cuda")).abs().max().item()
    print(f"  max|logit delta| forward vs reversed motion = {db:.4f}")
    if db > 1e-3:
        print("  PASS: frame order changes the distribution -- motion is representable.")
    else:
        print("  FAIL: order-free. The trunk sees a bag of stills, not a video.")
        print("  => a world model cannot sit on this path without adding temporal structure.")


if __name__ == "__main__":
    main()
