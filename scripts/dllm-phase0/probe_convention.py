"""Which logit position predicts a masked token in SDAR?

Run after fixing the mask contract (SDAR wants a BOOLEAN attn_mask, True = attend;
an additive float mask inverts under its `.bool()` call).  This model has no
working no-mask path -- SDARAttention does `torch.all(attention_mask)` and would
crash on None -- so every forward here passes an explicit bool mask.

Two candidates for where a masked position's prediction lives:
  diffusion convention: logits[i]   -> token at position i   (LLaDA / Dream / MMaDA)
  AR convention:        logits[i-1] -> token at position i   (next-token)
"""

import torch
from transformers import AutoModelForCausalLM, AutoTokenizer

M = "/home/gad/dllm-phase0/SDAR-1.7B-Chat"
MASK_ID = 151669
EOS = (151645, 151643)

tok = AutoTokenizer.from_pretrained(M, trust_remote_code=True)
model = AutoModelForCausalLM.from_pretrained(
    M, trust_remote_code=True, torch_dtype=torch.bfloat16, attn_implementation="eager"
).to("cuda").eval()


def causal(L):
    i = torch.arange(L, device="cuda")
    return (i[None, :] <= i[:, None])[None, None]


def block_causal(n_prompt, n_gen, block):
    L = n_prompt + n_gen
    i = torch.arange(L, device="cuda")
    allow = i[None, :] <= i[:, None]
    g = i - n_prompt
    in_gen = g >= 0
    blk = torch.where(in_gen, torch.div(g, block, rounding_mode="floor"), -1)
    allow = allow | ((blk[:, None] == blk[None, :]) & in_gen[:, None] & in_gen[None, :])
    return allow[None, None]


ids = tok.apply_chat_template(
    [{"role": "user", "content": "Write a Python function that adds two numbers."}],
    add_generation_prompt=True, tokenize=True,
)
P = len(ids)
print("prompt tokens:", P, "| last 4 decoded:", [tok.decode([i]) for i in ids[-4:]])

B = 8
seq = torch.tensor([ids + [MASK_ID] * B], device="cuda")

with torch.no_grad():
    lg = model(input_ids=seq, attention_mask=block_causal(P, B, B)).logits[0].float()
    lg_nm = model(input_ids=torch.tensor([ids], device="cuda"),
                  attention_mask=causal(P)).logits[0].float()


def top(v, k=5):
    p = torch.softmax(v, -1)
    c, i = p.topk(k)
    return [(repr(tok.decode([int(a)])), round(float(b), 3)) for a, b in zip(i, c)]


print("\n--- 8 <MASK> appended; first mask sits at index", P)
print("diffusion  logits[P]   ->", top(lg[P]))
print("AR         logits[P-1] ->", top(lg[P - 1]))
print("\n--- no masks at all, pure AR next-token reference")
print("logits[-1] ->", top(lg_nm[-1]))

print("\n--- greedy AR rollout (causal mask, next-token, no <MASK>): weights sanity floor")
cur = torch.tensor([ids], device="cuda")
out = []
with torch.no_grad():
    for _ in range(32):
        nxt = int(model(input_ids=cur, attention_mask=causal(cur.shape[1])).logits[0, -1].argmax())
        out.append(nxt)
        cur = torch.cat([cur, torch.tensor([[nxt]], device="cuda")], 1)
        if nxt in EOS:
            break
print(repr(tok.decode(out)))

print("\n--- one-shot diffusion denoise of a whole 8-token block (argmax at each mask)")
print(repr(tok.decode([int(x) for x in lg[P:P + B].argmax(-1)])))
