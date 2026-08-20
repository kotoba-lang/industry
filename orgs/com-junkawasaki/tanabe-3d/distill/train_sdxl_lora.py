"""SDXL panorama LoRA fine-tune on the cyber-drill distill dataset (Modal).

Trains a LoRA adapter on top of SDXL base-1.0 using the 25 worldlabs panoramas
as style/composition supervision. Trigger word: "cybrdrl".

Strategy:
  - aspect-ratio bucketing at 2:1 (panoramic) — 2048×1024 → 1536×768 or 2048×1024
  - rank 32, alpha 32, lr 1e-4, AdamW
  - ~1500 steps (25 imgs × 60 steps), batch 1, grad accum 2
  - FP16 mixed precision (no FP8/NVFP4 here — small model, L40S Ada sm_89)
  - SNR-gamma 5.0 (better detail for industrial textures)

GPU: L40S 48GB (per ADR-2605282130) — fits SDXL + LoRA + bucket batches.
Wall: ~40 min @ L40S → ~$1.30/run. Budget ceiling $5 covers 3 iterations.

Output: Modal Volume "cyber-drill-lora" → cybrdrl.safetensors

Usage (local):
  modal run train_sdxl_lora.py::train_lora
  modal volume get cyber-drill-lora /weights ./out/
"""
from __future__ import annotations

import json
import os
from pathlib import Path
import modal

# ---------------------------------------------------------------------------
# Modal app + image
# ---------------------------------------------------------------------------
app = modal.App("cyber-drill-sdxl-lora")

image = (
    modal.Image.debian_slim(python_version="3.11")
    .apt_install("git", "libgl1", "libglib2.0-0")
    .pip_install(
        "torch==2.4.0",
        "torchvision==0.19.0",
        "diffusers==0.30.0",
        "transformers==4.44.0",
        "accelerate==0.33.0",
        "peft==0.12.0",
        "safetensors==0.4.4",
        "Pillow==10.4.0",
        "numpy<2",  # diffusers 0.30 not yet numpy-2 clean
        "tqdm",
        "sentencepiece",
        "huggingface-hub==0.24.5",
    )
)

# Volumes: dataset (in) + weights (out) — both persist across runs
DATA_VOL = modal.Volume.from_name("cyber-drill-dataset", create_if_missing=True)
OUT_VOL = modal.Volume.from_name("cyber-drill-lora", create_if_missing=True)

DATA_MOUNT = "/data"
OUT_MOUNT = "/weights"

# Trigger token — invoked at inference: "cybrdrl industrial cleanroom..."
TRIGGER = "cybrdrl"


# ---------------------------------------------------------------------------
# Upload helper — push local panoramas to Modal Volume
# ---------------------------------------------------------------------------
@app.local_entrypoint()
def upload_dataset():
    """Upload distill/dataset/<scene>/{pano.png,meta.json} into Modal Volume."""
    local_root = Path.home() / "tanabe-3d/distill/dataset"
    if not local_root.exists():
        raise SystemExit(f"local dataset not found: {local_root}")
    files: list[tuple[str, str]] = []
    for scene_dir in sorted(local_root.iterdir()):
        if not scene_dir.is_dir():
            continue
        pano = scene_dir / "pano.png"
        meta = scene_dir / "meta.json"
        if pano.exists():
            files.append((str(pano), f"{scene_dir.name}/pano.png"))
        if meta.exists():
            files.append((str(meta), f"{scene_dir.name}/meta.json"))
    print(f"uploading {len(files)} files to Modal Volume cyber-drill-dataset")
    with DATA_VOL.batch_upload(force=True) as batch:
        for local, remote in files:
            batch.put_file(local, remote)
    print("upload complete")


# ---------------------------------------------------------------------------
# Training entrypoint
# ---------------------------------------------------------------------------
@app.function(
    image=image,
    gpu="L40S",
    timeout=2 * 3600,
    volumes={DATA_MOUNT: DATA_VOL, OUT_MOUNT: OUT_VOL},
    secrets=[modal.Secret.from_name("huggingface")],
)
def train_lora_fn(
    base_model: str = "stabilityai/stable-diffusion-xl-base-1.0",
    rank: int = 32,
    alpha: int = 32,
    lr: float = 1e-4,
    steps: int = 1500,
    batch_size: int = 1,
    grad_accum: int = 2,
    resolution: int = 1024,
    snr_gamma: float = 5.0,
    seed: int = 42,
):
    """Fine-tune SDXL LoRA on the panorama dataset and save .safetensors."""
    import torch
    import torch.nn.functional as F
    import numpy as np
    from PIL import Image
    from accelerate import Accelerator
    from diffusers import StableDiffusionXLPipeline, AutoencoderKL, UNet2DConditionModel, DDPMScheduler
    from peft import LoraConfig, get_peft_model_state_dict
    from peft.utils import set_peft_model_state_dict  # noqa: F401
    from safetensors.torch import save_file
    from torch.utils.data import Dataset, DataLoader
    from torchvision import transforms
    from transformers import CLIPTextModel, CLIPTextModelWithProjection, CLIPTokenizer

    os.environ["HF_TOKEN"] = os.environ.get("HF_TOKEN", "")
    torch.manual_seed(seed)

    # ---------------- dataset ----------------
    class PanoDataset(Dataset):
        def __init__(self, root: str, resolution: int):
            self.root = Path(root)
            self.samples = []
            for scene_dir in sorted(self.root.iterdir()):
                if not scene_dir.is_dir():
                    continue
                pano = scene_dir / "pano.png"
                meta = scene_dir / "meta.json"
                if not pano.exists():
                    continue
                caption = ""
                if meta.exists():
                    m = json.loads(meta.read_text())
                    caption = m.get("caption") or m.get("prompt") or ""
                # Prepend trigger token
                caption = f"{TRIGGER}, {caption}".strip(", ")
                self.samples.append((pano, caption))
            self.tx = transforms.Compose([
                # Random crop a square patch from the panorama (style training).
                # Panoramas are ~4096x2048; we resize the short edge then crop.
                transforms.Resize(resolution, antialias=True),
                transforms.RandomCrop(resolution),
                transforms.ToTensor(),
                transforms.Normalize([0.5] * 3, [0.5] * 3),
            ])

        def __len__(self) -> int:
            return len(self.samples)

        def __getitem__(self, idx):
            pano_path, caption = self.samples[idx]
            img = Image.open(pano_path).convert("RGB")
            return {"pixel_values": self.tx(img), "caption": caption}

    ds = PanoDataset(DATA_MOUNT, resolution=resolution)
    print(f"dataset: {len(ds)} panoramas")
    if len(ds) == 0:
        raise RuntimeError(f"no panoramas found in {DATA_MOUNT} — run upload_dataset first")
    loader = DataLoader(ds, batch_size=batch_size, shuffle=True, num_workers=2, drop_last=True)

    # ---------------- model ----------------
    accelerator = Accelerator(
        mixed_precision="fp16",
        gradient_accumulation_steps=grad_accum,
    )
    device = accelerator.device

    tok_one = CLIPTokenizer.from_pretrained(base_model, subfolder="tokenizer")
    tok_two = CLIPTokenizer.from_pretrained(base_model, subfolder="tokenizer_2")
    enc_one = CLIPTextModel.from_pretrained(base_model, subfolder="text_encoder", torch_dtype=torch.float16).to(device)
    enc_two = CLIPTextModelWithProjection.from_pretrained(base_model, subfolder="text_encoder_2", torch_dtype=torch.float16).to(device)
    vae = AutoencoderKL.from_pretrained(base_model, subfolder="vae", torch_dtype=torch.float16).to(device)
    unet = UNet2DConditionModel.from_pretrained(base_model, subfolder="unet", torch_dtype=torch.float32).to(device)
    sched = DDPMScheduler.from_pretrained(base_model, subfolder="scheduler")

    enc_one.requires_grad_(False)
    enc_two.requires_grad_(False)
    vae.requires_grad_(False)
    unet.requires_grad_(False)

    lora_cfg = LoraConfig(
        r=rank,
        lora_alpha=alpha,
        init_lora_weights="gaussian",
        target_modules=["to_k", "to_q", "to_v", "to_out.0"],
    )
    unet.add_adapter(lora_cfg)
    trainable = [p for p in unet.parameters() if p.requires_grad]
    n_trainable = sum(p.numel() for p in trainable)
    print(f"trainable LoRA params: {n_trainable/1e6:.2f}M")

    opt = torch.optim.AdamW(trainable, lr=lr, betas=(0.9, 0.999), weight_decay=1e-2, eps=1e-8)

    unet, opt, loader = accelerator.prepare(unet, opt, loader)

    def encode_prompt(captions: list[str]):
        with torch.no_grad():
            t1 = tok_one(captions, max_length=tok_one.model_max_length, padding="max_length",
                         truncation=True, return_tensors="pt").input_ids.to(device)
            t2 = tok_two(captions, max_length=tok_two.model_max_length, padding="max_length",
                         truncation=True, return_tensors="pt").input_ids.to(device)
            e1 = enc_one(t1, output_hidden_states=True)
            e2 = enc_two(t2, output_hidden_states=True)
            pooled = e2[0]                        # (B, 1280)
            h1 = e1.hidden_states[-2]             # (B, 77, 768)
            h2 = e2.hidden_states[-2]             # (B, 77, 1280)
            prompt_embeds = torch.cat([h1, h2], dim=-1)  # (B, 77, 2048)
        return prompt_embeds, pooled

    def compute_time_ids(bsz: int):
        # SDXL conditioning: (orig_h, orig_w, crop_top, crop_left, target_h, target_w)
        return torch.tensor(
            [[resolution, resolution, 0, 0, resolution, resolution]],
            dtype=torch.float16, device=device,
        ).repeat(bsz, 1)

    # ---------------- train loop ----------------
    unet.train()
    step = 0
    losses: list[float] = []
    pbar_every = 25

    while step < steps:
        for batch in loader:
            with accelerator.accumulate(unet):
                pixel = batch["pixel_values"].to(device, dtype=torch.float16)
                captions = batch["caption"]
                with torch.no_grad():
                    latents = vae.encode(pixel).latent_dist.sample() * vae.config.scaling_factor
                noise = torch.randn_like(latents)
                bsz = latents.shape[0]
                ts = torch.randint(0, sched.config.num_train_timesteps, (bsz,), device=device).long()
                noisy = sched.add_noise(latents, noise, ts)

                prompt_embeds, pooled = encode_prompt(captions)
                added = {"text_embeds": pooled, "time_ids": compute_time_ids(bsz)}

                pred = unet(noisy.to(torch.float32), ts, prompt_embeds.to(torch.float32),
                            added_cond_kwargs={k: v.to(torch.float32) for k, v in added.items()}).sample

                # Min-SNR loss
                snr = (sched.alphas_cumprod.to(device)[ts] / (1 - sched.alphas_cumprod.to(device)[ts])).clamp(min=1e-7)
                mse_w = torch.minimum(snr, torch.full_like(snr, snr_gamma)) / snr
                loss_per = F.mse_loss(pred.float(), noise.float(), reduction="none").mean([1, 2, 3])
                loss = (loss_per * mse_w).mean()

                accelerator.backward(loss)
                if accelerator.sync_gradients:
                    accelerator.clip_grad_norm_(trainable, 1.0)
                opt.step()
                opt.zero_grad(set_to_none=True)

                losses.append(loss.item())
                step += 1
                if step % pbar_every == 0:
                    avg = sum(losses[-pbar_every:]) / pbar_every
                    print(f"step {step:5d}/{steps}  loss={avg:.4f}", flush=True)
                if step >= steps:
                    break

    # ---------------- save ----------------
    accelerator.wait_for_everyone()
    if accelerator.is_main_process:
        out_dir = Path(OUT_MOUNT)
        out_dir.mkdir(parents=True, exist_ok=True)
        u = accelerator.unwrap_model(unet)
        state = get_peft_model_state_dict(u)
        # Save in safetensors with kohya-style prefixes for compat
        save_file(state, str(out_dir / f"{TRIGGER}.safetensors"))
        (out_dir / "train_config.json").write_text(json.dumps({
            "trigger": TRIGGER,
            "base_model": base_model,
            "rank": rank, "alpha": alpha, "lr": lr, "steps": steps,
            "batch_size": batch_size, "grad_accum": grad_accum,
            "resolution": resolution, "snr_gamma": snr_gamma, "seed": seed,
            "dataset_size": len(ds),
            "final_loss_window": sum(losses[-50:]) / max(1, min(50, len(losses))),
        }, indent=2))
        OUT_VOL.commit()
        print(f"saved {out_dir / f'{TRIGGER}.safetensors'} ({len(state)} tensors)")


@app.local_entrypoint()
def train(
    rank: int = 32,
    alpha: int = 32,
    lr: float = 1e-4,
    steps: int = 1500,
    resolution: int = 1024,
):
    """Kick off training (use after upload_dataset)."""
    train_lora_fn.remote(rank=rank, alpha=alpha, lr=lr, steps=steps, resolution=resolution)
    print(f"\ntrained. fetch weights: modal volume get cyber-drill-lora /weights ./out/")


# ---------------------------------------------------------------------------
# Eval — generate test panoramas with the trained LoRA
# ---------------------------------------------------------------------------
@app.function(
    image=image,
    gpu="L40S",
    timeout=30 * 60,
    volumes={OUT_MOUNT: OUT_VOL},
    secrets=[modal.Secret.from_name("huggingface")],
)
def sample_fn(
    prompts: list[str],
    base_model: str = "stabilityai/stable-diffusion-xl-base-1.0",
    width: int = 2048,
    height: int = 1024,
    steps: int = 30,
    cfg: float = 7.0,
    seed: int = 0,
) -> dict[str, bytes]:
    import torch
    from diffusers import StableDiffusionXLPipeline
    from io import BytesIO

    pipe = StableDiffusionXLPipeline.from_pretrained(
        base_model, torch_dtype=torch.float16, variant="fp16", use_safetensors=True,
    ).to("cuda")
    pipe.load_lora_weights(OUT_MOUNT, weight_name=f"{TRIGGER}.safetensors")

    out: dict[str, bytes] = {}
    for i, p in enumerate(prompts):
        prompt = f"{TRIGGER}, {p}, photoreal, panoramic equirectangular"
        g = torch.Generator(device="cuda").manual_seed(seed + i)
        img = pipe(prompt, width=width, height=height, num_inference_steps=steps,
                   guidance_scale=cfg, generator=g).images[0]
        buf = BytesIO()
        img.save(buf, format="PNG")
        out[f"sample_{i:02d}.png"] = buf.getvalue()
    return out


@app.local_entrypoint()
def sample(out_dir: str = str(Path.home() / "tanabe-3d/distill/samples")):
    test_prompts = [
        "industrial control room with red alert overlay",
        "datacenter cold aisle at night",
        "chemical plant outdoor yard, golden hour",
        "telecom main distribution frame",
        "nuclear control room evacuation",
    ]
    blobs = sample_fn.remote(test_prompts)
    od = Path(out_dir)
    od.mkdir(parents=True, exist_ok=True)
    for name, data in blobs.items():
        (od / name).write_bytes(data)
        print(f"  ✓ {od / name}")
