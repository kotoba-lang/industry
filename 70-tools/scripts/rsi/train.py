"""RSi training step — SFT-LoRA on the accumulated corpus.

Wraps the proven train_cpt.py logic (peft+trl, gfx1151 ROCm) and adds:
  - corpus-to-SFT JSONL conversion (chat-format messages)
  - run-ID + checkpoint-CID generation
  - kotoba provenance write
  - incremental: adapters are named maxwell-<N> and layered on the base

Runs the actual training on EVO-X2 over SSH (same pattern as fleet-refactor).
"""
from __future__ import annotations
import json, os, subprocess, sys, tempfile, time
from pathlib import Path

from .config import (
    EVO_HOST, EVO_USER, EVO_VENV, EVO_TRAIN_DIR,
    SFT_BASE_MODEL, TRAIN_EPOCHS, LORA_R,
    BAIEN_DIR, MODELS_LOG
)
from .cid import cid_of_str
from .kotoba_bridge import write_run
from .corpus import SFT_CORPUS


TRAIN_SCRIPT = Path(__file__).parent / "train_sft.py"


def _run_id(corpus_cid: str) -> str:
    ts = int(time.time())
    return "maxwell-" + cid_of_str(f"{ts}:{corpus_cid}")[:8]


def _sft_jsonl(pairs: list[dict]) -> str:
    """Convert SFT corpus pairs to a JSONL the trainer understands."""
    lines = []
    for p in pairs:
        msgs = p.get("messages", [])
        lines.append(json.dumps({"messages": msgs}, ensure_ascii=False))
    return "\n".join(lines)


def _ssh(cmd: str, *, check: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(
        ["ssh", f"{EVO_USER}@{EVO_HOST}", cmd],
        capture_output=True, text=True, check=check)


def _scp_to(local: str, remote: str) -> None:
    subprocess.run(
        ["scp", local, f"{EVO_USER}@{EVO_HOST}:{remote}"],
        check=True, capture_output=True)


def _scp_from(remote: str, local: str) -> None:
    subprocess.run(
        ["scp", f"{EVO_USER}@{EVO_HOST}:{remote}", local],
        check=True, capture_output=True)


def run_training(corpus_cid: str, run_number: int) -> dict | None:
    """Run SFT-LoRA on EVO-X2 for the current corpus.

    Returns a run-record dict or None on failure.
    """
    run_id = f"maxwell-{run_number:04d}"
    print(f"[train] run_id={run_id}  corpus_cid={corpus_cid[:16]}…")

    # Build SFT JSONL from corpus
    pairs = [json.loads(l) for l in SFT_CORPUS.read_text().splitlines() if l.strip()]
    sft_text = _sft_jsonl(pairs)

    with tempfile.NamedTemporaryFile(mode="w", suffix=".jsonl",
                                     delete=False, prefix="maxwell-sft-") as f:
        f.write(sft_text)
        local_sft = f.name

    remote_dir  = f"{EVO_TRAIN_DIR}/{run_id}"
    remote_sft  = f"{remote_dir}/corpus.jsonl"
    remote_out  = f"{remote_dir}/adapter"
    remote_log  = f"{remote_dir}/train.log"

    # Prepare EVO dir
    _ssh(f"mkdir -p {remote_dir}")
    _scp_to(str(TRAIN_SCRIPT), f"{remote_dir}/train_sft.py")
    _scp_to(local_sft, remote_sft)
    os.unlink(local_sft)

    # Run training (HSA_OVERRIDE required for gfx1151)
    train_cmd = (
        f"HSA_OVERRIDE_GFX_VERSION=11.0.0 "
        f"{EVO_VENV} {remote_dir}/train_sft.py "
        f"--model {SFT_BASE_MODEL} "
        f"--data {remote_sft} "
        f"--out {remote_out} "
        f"--epochs {TRAIN_EPOCHS} "
        f"--lora-r {LORA_R} "
        f"2>&1 | tee {remote_log}"
    )
    print(f"[train] running on {EVO_HOST}…")
    result = _ssh(train_cmd, check=False)

    if result.returncode != 0:
        print(f"[train] FAILED:\n{result.stdout[-2000:]}")
        return None

    # Parse lora_B mass and final loss from log
    lora_b = 0.0
    final_loss = 9.9
    for line in result.stdout.splitlines():
        if "lora_B nonzero mass:" in line:
            try:
                lora_b = float(line.split(":")[-1].strip().split()[0])
            except Exception:
                pass
        if "'loss':" in line or '"loss":' in line:
            try:
                import re
                m = re.search(r"'loss':\s*([\d.]+)", line)
                if m:
                    final_loss = float(m.group(1))
            except Exception:
                pass

    if lora_b == 0.0:
        print("[train] lora_B all-zero — silent no-op. Aborting.")
        return None

    # Fetch adapter manifest back (adapter_config.json as CID probe)
    with tempfile.NamedTemporaryFile(suffix=".json", delete=False) as tf:
        local_manifest = tf.name
    try:
        _scp_from(f"{remote_out}/adapter_config.json", local_manifest)
        manifest_text = Path(local_manifest).read_text()
    except Exception:
        manifest_text = run_id
    finally:
        os.unlink(local_manifest)

    checkpoint_cid = cid_of_str(manifest_text)
    record = {
        "run_id":          run_id,
        "corpus_cid":      corpus_cid,
        "base_model":      SFT_BASE_MODEL,
        "epochs":          TRAIN_EPOCHS,
        "lora_r":          LORA_R,
        "corpus_size":     len(pairs),
        "checkpoint_path": f"{EVO_HOST}:{remote_out}",
        "checkpoint_cid":  checkpoint_cid,
        "lora_b_mass":     lora_b,
        "final_loss":      final_loss,
    }

    # Persist to kotoba
    write_run(**{k: record[k] for k in [
        "run_id", "corpus_cid", "base_model", "epochs", "lora_r",
        "corpus_size", "checkpoint_path", "checkpoint_cid",
        "lora_b_mass", "final_loss"]})

    # Append to maxwell-models.jsonl
    with open(MODELS_LOG, "a") as f:
        f.write(json.dumps(record, ensure_ascii=False) + "\n")

    print(f"[train] done  lora_B={lora_b:.2f}  loss={final_loss:.4f}  cid={checkpoint_cid[:16]}…")
    return record
