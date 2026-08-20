#!/usr/bin/env python3
"""train_sft — SFT-LoRA for Maxwell RSi (gfx1151 / ROCm).

Chat-format SFT on the accumulated kotoba-native Clojure corpus.
Based on the corrected train_cpt.py (lora_B nonzero guard, use_reentrant=False).

EVO usage:
  HSA_OVERRIDE_GFX_VERSION=11.0.0 ~/ComfyUI/venv/bin/python train_sft.py \
      --model google/gemma-4-E4B-it-qat-q4_0-unquantized \
      --data corpus.jsonl --out adapter --epochs 1.0
"""
from __future__ import annotations
import argparse, json


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model",   required=True)
    ap.add_argument("--data",    required=True)
    ap.add_argument("--out",     required=True)
    ap.add_argument("--epochs",  type=float, default=1.0)
    ap.add_argument("--lr",      type=float, default=2e-4)
    ap.add_argument("--seq-len", type=int,   default=1280)
    ap.add_argument("--batch",   type=int,   default=1)
    ap.add_argument("--grad-accum", type=int, default=8)
    ap.add_argument("--lora-r",  type=int,   default=16)
    ap.add_argument("--smoke",   action="store_true")
    args = ap.parse_args()

    import torch
    from datasets import Dataset
    from transformers import (AutoModelForCausalLM, AutoTokenizer,
                              TrainingArguments)
    from peft import LoraConfig, get_peft_model
    from trl import SFTTrainer, SFTConfig

    tok = AutoTokenizer.from_pretrained(args.model)
    if tok.pad_token is None:
        tok.pad_token = tok.eos_token

    # Load chat-format corpus
    raw = [json.loads(l) for l in open(args.data) if l.strip()]

    def fmt(ex):
        return {"text": tok.apply_chat_template(
            ex["messages"], tokenize=False, add_generation_prompt=False)}

    ds = Dataset.from_list([fmt(r) for r in raw])

    # Load model (Gemma4 is ImageTextToText; extract language_model)
    try:
        model = AutoModelForCausalLM.from_pretrained(
            args.model, torch_dtype=torch.bfloat16).cuda()
    except Exception:
        from transformers import AutoModelForImageTextToText
        full = AutoModelForImageTextToText.from_pretrained(
            args.model, torch_dtype=torch.bfloat16).cuda()
        model = getattr(getattr(full, "model", full), "language_model", None) or full
        print(f"text submodel: {type(model).__name__}")

    # use_reentrant=False is critical — prevents silent no-op on Gemma4
    model.gradient_checkpointing_enable(
        gradient_checkpointing_kwargs={"use_reentrant": False})
    model.enable_input_require_grads()

    # Scope LoRA to language_model decoder linears only (Gemma4 vision-tower trap)
    has_lm = any("language_model" in n for n, _ in model.named_modules())
    proj = "q_proj|k_proj|v_proj|o_proj|gate_proj|up_proj|down_proj"
    targets = (rf".*language_model\.layers\.\d+\.(self_attn|mlp)\.({proj})"
               if has_lm else proj.split("|"))

    lora = LoraConfig(
        r=args.lora_r, lora_alpha=args.lora_r * 2, lora_dropout=0.05,
        target_modules=targets, task_type="CAUSAL_LM")
    model = get_peft_model(model, lora)
    model.print_trainable_parameters()

    sft_cfg = SFTConfig(
        output_dir=args.out,
        num_train_epochs=args.epochs,
        per_device_train_batch_size=args.batch,
        gradient_accumulation_steps=args.grad_accum,
        learning_rate=args.lr,
        bf16=True,
        logging_steps=1,
        save_strategy="no" if args.smoke else "epoch",
        max_steps=2 if args.smoke else -1,
        report_to=[],
        warmup_ratio=0.03,
        lr_scheduler_type="cosine",
        max_seq_length=args.seq_len,
        dataset_text_field="text",
        # expandable_segments avoids 32GB iGPU OOM (ADR-2606120500 §env)
        torch_compile=False,
    )
    trainer = SFTTrainer(model=model, args=sft_cfg, train_dataset=ds,
                         tokenizer=tok)
    trainer.train()

    # Guard: lora_B must be nonzero
    nz_b = sum(float(p.detach().abs().sum())
               for n, p in model.named_parameters() if "lora_B" in n)
    if nz_b == 0.0:
        raise RuntimeError("lora_B all-zero — no-op. Check use_reentrant=False.")
    print(f"lora_B nonzero mass: {nz_b:.4f} — adapter learned")

    if not args.smoke:
        model.save_pretrained(args.out)
        tok.save_pretrained(args.out)
        print(f"SFT-LoRA saved → {args.out}")
    else:
        print("SMOKE OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
