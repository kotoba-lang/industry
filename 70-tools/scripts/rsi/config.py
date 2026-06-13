"""RSi loop configuration — all tunables in one place."""
from __future__ import annotations
import os
from pathlib import Path

# --- paths (relative to repo root) ---
REPO_ROOT = Path(__file__).resolve().parents[4]
BAIEN_DIR  = REPO_ROOT / "90-docs" / "baien"
SFT_CORPUS = BAIEN_DIR / "maxwell-sft-corpus.jsonl"
MODELS_LOG = BAIEN_DIR / "maxwell-models.jsonl"
HELDOUT    = REPO_ROOT / "70-tools" / "scripts" / "fleet-refactor" / "heldout-units.jsonl"
GOLD_CORPUS = REPO_ROOT / "70-tools" / "scripts" / "fleet-refactor" / "gold-corpus"

# kotoba engine (local, :8077)
KOTOBA_URL = os.getenv("KOTOBA_URL", "http://127.0.0.1:8077")

# EVO-X2 (gad) — dynamic IP; run discover.py if stale
EVO_HOST   = os.getenv("EVO_HOST", "192.168.1.16")
EVO_USER   = os.getenv("EVO_USER", "gad")
EVO_VENV   = os.getenv("EVO_VENV", "~/ComfyUI/venv/bin/python")
EVO_TRAIN_DIR = os.getenv("EVO_TRAIN_DIR", "~/rsi")

# Murakumo fleet Ollama nodes (round-robin, same as murakumo-langchain)
FLEET_NODES = os.getenv("FLEET_NODES",
    "http://localhost:11434,"
    "http://192.168.1.101:11434,"
    "http://192.168.1.102:11434,"
    "http://192.168.1.103:11434,"
    "http://192.168.1.104:11434,"
    "http://192.168.1.105:11434,"
    "http://192.168.1.106:11434,"
    "http://192.168.1.107:11434,"
    "http://192.168.1.108:11434,"
    "http://192.168.1.109:11434"
).split(",")
FLEET_BASE_MODEL = os.getenv("FLEET_BASE_MODEL", "gemma4:12b-it-qat")  # generator
MAXWELL_SLOT     = os.getenv("MAXWELL_SLOT",     "maxwell-1")           # deployed slot

# --- RSi loop thresholds ---
# Minimum new validated corpus pairs before triggering a training run
TRAIN_TRIGGER_DELTA = int(os.getenv("RSI_TRAIN_TRIGGER_DELTA", "100"))
# Minimum held-out improvement (pp) to deploy new checkpoint
DEPLOY_THRESHOLD_PP = float(os.getenv("RSI_DEPLOY_THRESHOLD_PP", "0.5"))
# Maximum training epochs per RSi iteration (keep small for incremental)
TRAIN_EPOCHS        = float(os.getenv("RSI_TRAIN_EPOCHS", "1.0"))
# LoRA rank
LORA_R              = int(os.getenv("RSI_LORA_R", "16"))
# Held-out evaluation sample size
EVAL_SAMPLE         = int(os.getenv("RSI_EVAL_SAMPLE", "54"))

# Base model for SFT (must match fleet generator weight family)
SFT_BASE_MODEL = os.getenv("RSI_SFT_BASE_MODEL",
    "google/gemma-4-E4B-it-qat-q4_0-unquantized")
