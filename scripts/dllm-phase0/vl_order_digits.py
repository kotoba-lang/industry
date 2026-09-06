"""Separate "the model cannot read order" from "my stimulus was too subtle".

The moving-dot test failed, but a 48px disc survives LLaVA-OneVision video
pooling as roughly 1.75 cells of a 14x14 grid -- weak evidence either way.
Digits are the most salient thing a VLM can be shown, and asking for the LAST
frame requires order by construction: both clips contain exactly the same eight
frames, so a model that pools order-free cannot answer them differently.

  clip UP   : 1 2 3 4 5 6 7 8   -> last frame is 8
  clip DOWN : 8 7 6 5 4 3 2 1   -> last frame is 1

If this fails too, the failure is the model and not the stimulus, and a world
model cannot sit on this path without adding temporal structure.
"""

import re

import numpy as np
import torch
from PIL import Image, ImageDraw, ImageFont
from transformers import AutoProcessor, AutoModelForCausalLM

M = "/home/gad/dllm-phase0/SDAR-VL-Think-8B"
MASK = 151669
EOS = (151645, 151643)


def digit(n, size=384):
    im = Image.new("RGB", (size, size), (255, 255, 255))
    d = ImageDraw.Draw(im)
    try:
        f = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", size // 2)
    except Exception:
        f = ImageFont.load_default()
    t = str(n)
    bb = d.textbbox((0, 0), t, font=f)
    d.text(((size - (bb[2] - bb[0])) // 2 - bb[0], (size - (bb[3] - bb[1])) // 2 - bb[1]),
           t, fill=(0, 0, 0), font=f)
    return im


def bcm(n_prompt, n_gen, b, dev):
    L = n_prompt + n_gen
    i = torch.arange(L, device=dev)
    allow = i[None, :] <= i[:, None]
    g = i - n_prompt
    ing = g >= 0
    blk = torch.where(ing, torch.div(g, b, rounding_mode="floor"), -1)
    return (allow | ((blk[:, None] == blk[None, :]) & ing[:, None] & ing[None, :]))[None, None]


proc = AutoProcessor.from_pretrained(M, trust_remote_code=True)
model = AutoModelForCausalLM.from_pretrained(
    M, trust_remote_code=True, attn_implementation="eager", torch_dtype=torch.bfloat16,
).to("cuda").eval()

# The SDAR trunk needs eager (its own attention reads the 4D bool mask directly),
# but SigLIP is a stock transformers tower and eager there materialises the full
# attention matrix for every frame -- that is what ran the GPU out of memory on an
# 8-frame clip while ComfyUI held the rest of the card. Only the vision tower is
# switched; the trunk's mask contract is untouched.
try:
    vt = model.model.vision_tower
    vt.config._attn_implementation = "sdpa"
    print("vision tower attention ->", vt.config._attn_implementation, flush=True)
except Exception as e:
    print("could not switch vision tower attention:", e, flush=True)

Q = ("This video shows a sequence of single digits, one per frame. "
     "What digit is shown on the LAST frame? Answer with just the digit.")

res = {}
for kind, order in (("up", [1, 2, 3, 4, 5, 6, 7, 8]), ("down", [8, 7, 6, 5, 4, 3, 2, 1])):
    vid = np.stack([np.array(digit(n)) for n in order])
    msgs = [{"role": "user", "content": [{"type": "video"}, {"type": "text", "text": Q}]}]
    p = proc.apply_chat_template(msgs, add_generation_prompt=True, tokenize=False)
    inp = proc(videos=[vid], text=p, return_tensors="pt")
    inp = {k: (v.to("cuda") if hasattr(v, "to") else v) for k, v in inp.items()}
    if "pixel_values_videos" in inp:
        shape = tuple(inp["pixel_values_videos"].shape)
        print("  {}: pixel_values_videos {}".format(kind, shape), flush=True)

    ids = inp["input_ids"]
    n_prompt = ids.shape[1]
    kw = {k: v for k, v in inp.items() if k not in ("input_ids", "attention_mask")}
    seq, got, hit, fwd = ids, [], False, 0
    with torch.no_grad():
        while len(got) < 200 and not hit:
            b = 4
            cur = torch.cat([seq, torch.full((1, b), MASK, device="cuda", dtype=ids.dtype)], 1)
            ng = cur.shape[1] - n_prompt
            pend = list(range(cur.shape[1] - b, cur.shape[1]))
            while pend:
                lg = model(input_ids=cur, attention_mask=bcm(n_prompt, ng, b, "cuda"), **kw).logits
                fwd += 1
                pr = torch.softmax(lg[0, pend].float(), -1)
                cf, pk = pr.max(-1)
                take = (cf >= 0.9).nonzero(as_tuple=True)[0].tolist() or [int(cf.argmax())]
                for j in sorted(take, reverse=True):
                    cur[0, pend[j]] = pk[j]
                for j in sorted(take, reverse=True):
                    pend.pop(j)
            for t in cur[0, seq.shape[1]:].tolist():
                got.append(t)
                if t in EOS:
                    hit = True
                    break
            seq = cur
    txt = proc.tokenizer.decode(got, skip_special_tokens=True)
    res[kind] = txt
    tail = txt.split("</think>")[-1].strip()
    print("[{} last={}] answer-part: {!r}".format(kind, order[-1], tail[:140]), flush=True)


def final_digit(t):
    tail = t.split("</think>")[-1]
    ds = re.findall(r"[1-8]", tail)
    return ds[-1] if ds else "?"


a, b = final_digit(res["up"]), final_digit(res["down"])
print("\nparsed final digit: up->{} (truth 8) | down->{} (truth 1)".format(a, b))
if a == b:
    print("FAIL: identical answer for opposite orders -- order-free. "
          "The stimulus was not the problem; the model cannot read temporal order.")
elif a == "8" and b == "1":
    print("PASS: order is read correctly. The dot test failed on stimulus salience, "
          "not on capability.")
else:
    print("PARTIAL: answers differ but are not both correct "
          "-- order reaches the output but is not read reliably.")
