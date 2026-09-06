"""Graft an audio input path onto SDAR-VL's diffusion trunk, and prove it is real.

Design follows the vision path already in the checkpoint rather than inventing a
new one: an encoder produces continuous features, a two-layer projector maps them
into the trunk's width, and they are scattered into inputs_embeds at placeholder
positions.  The trunk, the diffusion mask and the block decode are untouched.

  Whisper-large-v3 encoder (d_model 1280)  ->  Linear 1280->4096, GELU,
  Linear 4096->4096  ->  scattered at <|audio_pad|> positions  ->  SDAR trunk

The placeholder costs no vocabulary change.  The trunk declares vocab_size
151936 while the highest token actually used is 151669, so 266 embedding rows
are already allocated and unused; this claims one of them (151670) instead of
resizing anything.

WHAT THIS CAN AND CANNOT SHOW.  The projector is randomly initialised, so the
model does not understand the audio and no amount of prompting will make it.
The claim under test is only that the path CARRIES AUDIO-DEPENDENT INFORMATION
into the trunk.  Three checks, two of which can fail:

  1. count      the number of projected audio vectors must equal the number of
                placeholder positions exactly.  Off by one and the scatter is
                silently mixing audio into text positions.
  2. dependence two different sounds must give different logits.  Identical
                logits mean the audio never arrived.
  3. NEGATIVE   with the projector's output layer zeroed, the same two sounds
     CONTROL    must give IDENTICAL logits.  If they still differ, check 2 was
                measuring something other than the audio -- a difference in
                sequence length, in the prompt, in sampling -- and proves nothing.

Check 3 is the one that makes check 2 mean anything.  Without it, any two
forwards that differ for any reason look like a working audio path.
"""

import argparse

import numpy as np
import torch
import torch.nn as nn

MASK_ID = 151669
AUDIO_PAD_ID = 151670          # allocated, previously unused; no resize
AUDIO_TOKEN = "<|audio_pad|>"
SR = 16000


class AudioProjector(nn.Module):
    """Same shape as LlavaOnevisionMultiModalProjector, at the audio encoder's width."""

    def __init__(self, in_dim=1280, out_dim=4096):
        super().__init__()
        self.linear_1 = nn.Linear(in_dim, out_dim)
        self.act = nn.GELU()
        self.linear_2 = nn.Linear(out_dim, out_dim)

    def forward(self, x):
        return self.linear_2(self.act(self.linear_1(x)))


def tone(freq, seconds=2.0, sr=SR):
    t = np.arange(int(seconds * sr), dtype=np.float32) / sr
    return (0.5 * np.sin(2 * np.pi * freq * t)).astype(np.float32)


def noise(seconds=2.0, sr=SR, seed=0):
    g = np.random.default_rng(seed)
    return (0.3 * g.standard_normal(int(seconds * sr))).astype(np.float32)


@torch.no_grad()
def encode_audio(enc, fe, wave, device, n_tokens=64):
    """Whisper encoder -> (n_tokens, 1280), average-pooled to a fixed budget."""
    feats = fe(wave, sampling_rate=SR, return_tensors="pt").input_features.to(device, torch.bfloat16)
    h = enc(feats).last_hidden_state[0]                    # (1500, 1280)
    pooled = torch.nn.functional.adaptive_avg_pool1d(
        h.t().float().unsqueeze(0), n_tokens).squeeze(0).t()
    return pooled.to(torch.bfloat16)                       # (n_tokens, 1280)


def block_causal_mask(n_prompt, n_gen, block, device):
    L = n_prompt + n_gen
    i = torch.arange(L, device=device)
    allow = i[None, :] <= i[:, None]
    g = i - n_prompt
    ing = g >= 0
    blk = torch.where(ing, torch.div(g, block, rounding_mode="floor"), -1)
    return (allow | ((blk[:, None] == blk[None, :]) & ing[:, None] & ing[None, :]))[None, None]


@torch.no_grad()
def logits_with_audio(model, tok, proj, audio_feats, device, n_mask=8, block=4):
    """Build a prompt with n audio placeholders, replace their embeddings, forward once."""
    n_audio = audio_feats.shape[0]
    text = ("<|im_start|>user\n" + AUDIO_TOKEN * n_audio +
            "\nWhat do you hear?<|im_end|>\n<|im_start|>assistant\n")
    ids = tok(text, return_tensors="pt", add_special_tokens=False).input_ids.to(device)
    n_prompt = ids.shape[1]
    cur = torch.cat([ids, torch.full((1, n_mask), MASK_ID, device=device, dtype=ids.dtype)], 1)

    embed = model.get_input_embeddings()
    inputs_embeds = embed(cur)
    slots = (cur[0] == AUDIO_PAD_ID).nonzero(as_tuple=True)[0]

    if slots.numel() != n_audio:
        raise AssertionError(
            f"count check FAILED: {n_audio} projected audio vectors but "
            f"{slots.numel()} placeholder positions. The scatter would write audio "
            f"into text positions or drop audio silently.")

    projected = proj(audio_feats.to(torch.bfloat16))
    inputs_embeds[0, slots] = projected.to(inputs_embeds.dtype)

    out = model(inputs_embeds=inputs_embeds,
                attention_mask=block_causal_mask(n_prompt, n_mask, block, device))
    return out.logits[0, n_prompt:].float(), n_prompt, int(slots.numel())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="/home/gad/dllm-phase0/SDAR-VL-Think-8B")
    ap.add_argument("--whisper", default="/home/gad/dllm-phase0/whisper-large-v3")
    ap.add_argument("--n-audio", type=int, default=64)
    ap.add_argument("--seed", type=int, default=0)
    args = ap.parse_args()

    from transformers import (AutoProcessor, AutoModelForCausalLM,
                              WhisperFeatureExtractor, WhisperModel)
    device = "cuda"
    torch.manual_seed(args.seed)

    proc = AutoProcessor.from_pretrained(args.model, trust_remote_code=True)
    tok = proc.tokenizer
    added = tok.add_special_tokens({"additional_special_tokens": [AUDIO_TOKEN]})
    got = tok.convert_tokens_to_ids(AUDIO_TOKEN)
    print(f"audio placeholder {AUDIO_TOKEN} -> id {got} "
          f"({'claimed an existing free row' if added == 0 or got == AUDIO_PAD_ID else 'NEW ROW ADDED'})",
          flush=True)
    if got != AUDIO_PAD_ID:
        print(f"  note: tokenizer assigned {got}, not the intended {AUDIO_PAD_ID}; using {got}.")
        globals()["AUDIO_PAD_ID"] = got

    model = AutoModelForCausalLM.from_pretrained(
        args.model, trust_remote_code=True, attn_implementation="eager",
        torch_dtype=torch.bfloat16).to(device).eval()
    vocab = model.get_input_embeddings().weight.shape[0]
    print(f"trunk embedding rows: {vocab} (placeholder id {AUDIO_PAD_ID} is "
          f"{'within' if AUDIO_PAD_ID < vocab else 'OUTSIDE'} the existing matrix -- "
          f"no resize {'needed' if AUDIO_PAD_ID < vocab else 'AVOIDED'})", flush=True)

    fe = WhisperFeatureExtractor.from_pretrained(args.whisper)
    enc = WhisperModel.from_pretrained(args.whisper, torch_dtype=torch.bfloat16).encoder.to(device).eval()
    print(f"whisper encoder loaded: d_model {enc.config.d_model}", flush=True)

    proj = AudioProjector(enc.config.d_model, model.config.text_config.hidden_size).to(device, torch.bfloat16)
    print(f"projector {enc.config.d_model} -> {model.config.text_config.hidden_size} "
          f"({sum(p.numel() for p in proj.parameters())/1e6:.1f}M params, randomly initialised)", flush=True)

    a = encode_audio(enc, fe, tone(440.0), device, args.n_audio)
    b = encode_audio(enc, fe, noise(seed=1), device, args.n_audio)
    print(f"encoded: {tuple(a.shape)} per clip", flush=True)

    print("\n--- check 1+2: count, and does the trunk depend on the audio? ---", flush=True)
    la, npr, ns = logits_with_audio(model, tok, proj, a, device)
    lb, _, _ = logits_with_audio(model, tok, proj, b, device)
    print(f"  count check PASSED: {ns} audio vectors == {ns} placeholder positions "
          f"(prompt {npr} tokens)")
    d_live = (la - lb).abs().max().item()
    print(f"  max|logit delta| tone440 vs noise = {d_live:.4f}")
    print("  PASS: audio reaches the trunk." if d_live > 1e-3 else
          "  FAIL: identical logits -- audio is NOT reaching the trunk.")

    print("\n--- check 3 (negative control): zero the projector output ---", flush=True)
    with torch.no_grad():
        proj.linear_2.weight.zero_()
        proj.linear_2.bias.zero_()
    la0, _, _ = logits_with_audio(model, tok, proj, a, device)
    lb0, _, _ = logits_with_audio(model, tok, proj, b, device)
    d_zero = (la0 - lb0).abs().max().item()
    print(f"  max|logit delta| with a silenced projector = {d_zero:.6f}")
    if d_zero < 1e-6:
        print("  PASS: with the projector silenced the two clips are indistinguishable, "
              "so the delta above came from the audio and nothing else.")
    else:
        print("  FAIL: the clips still differ with no audio signal getting through. "
              "Check 2 was measuring something other than the audio; it proves nothing.")

    print(f"\nsummary: count ok, live delta {d_live:.4f}, silenced delta {d_zero:.6f}")
    print("NOTE: the projector is untrained. This shows the path CARRIES audio, "
          "not that the model understands it. Understanding needs paired audio-text "
          "training of these 21M parameters.")


if __name__ == "__main__":
    main()
