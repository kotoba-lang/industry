#!/usr/bin/env python3
"""Maxwell RSi loop — Recursive Self-Improvement orchestrator.

One iteration:
  1. HARVEST  — fleet generates new validated Clojure pairs → corpus
  2. CHECK    — is corpus delta ≥ TRAIN_TRIGGER_DELTA?
  3. TRAIN    — SFT-LoRA on EVO-X2
  4. EVAL     — A/B held-out comparison
  5. GATE     — delta_pp ≥ DEPLOY_THRESHOLD_PP?
  6. DEPLOY   — Ollama fleet swap → Maxwell N+1
  7. FEEDBACK — improved Maxwell becomes the next harvest generator

The loop is honest (smoke=destructive lesson):
  - No deployment without empirical improvement
  - Every event is kotoba-persisted (tamper-evident Datom log)
  - Charter scan gates every corpus entry

Usage:
  python -m rsi.loop                   # one full iteration
  python -m rsi.loop --harvest-only    # accumulate corpus, no train
  python -m rsi.loop --train-only      # train + eval on current corpus
  python -m rsi.loop --dry-run         # full pipeline, no writes
  python -m rsi.loop --status          # print current state
"""
from __future__ import annotations
import argparse, json, sys
from pathlib import Path

from .config import TRAIN_TRIGGER_DELTA, MODELS_LOG, BAIEN_DIR
from .corpus import corpus_size, corpus_cid, ingest_jsonl
from .harvest import harvest_python_files, discover_unharvested_py
from .train import run_training
from .eval import run_eval
from .deploy import deploy


# ---------------------------------------------------------------------------
# State: which run number are we on?
# ---------------------------------------------------------------------------

def _next_run_number() -> int:
    if not Path(MODELS_LOG).exists():
        return 1
    lines = [l for l in Path(MODELS_LOG).read_text().splitlines() if l.strip()]
    return len(lines) + 1


def _last_corpus_size_at_train() -> int:
    """Return corpus size at the last training run (to compute delta)."""
    if not Path(MODELS_LOG).exists():
        return 0
    lines = [l for l in Path(MODELS_LOG).read_text().splitlines() if l.strip()]
    if not lines:
        return 0
    try:
        return json.loads(lines[-1]).get("corpus_size", 0)
    except Exception:
        return 0


# ---------------------------------------------------------------------------
# Status report
# ---------------------------------------------------------------------------

def print_status() -> None:
    size = corpus_size()
    last = _last_corpus_size_at_train()
    delta = size - last
    run_n = _next_run_number()
    cid   = corpus_cid()

    print(f"Maxwell RSi — status")
    print(f"  corpus:      {size} pairs  (CID {cid[:20]}…)")
    print(f"  last train:  {last} pairs")
    print(f"  delta:       {delta}  (trigger @ {TRAIN_TRIGGER_DELTA})")
    print(f"  next run#:   {run_n}")

    if Path(MODELS_LOG).exists():
        lines = [l for l in Path(MODELS_LOG).read_text().splitlines() if l.strip()]
        print(f"  checkpoints: {len(lines)}")
        for line in lines[-3:]:
            rec = json.loads(line)
            avail = "✓" if rec.get("available") else "○"
            print(f"    {avail} {rec['run_id']}  loss={rec.get('final_loss','?'):.4f}"
                  f"  {rec.get('ollama_model','(not deployed)')}")


# ---------------------------------------------------------------------------
# Main loop iteration
# ---------------------------------------------------------------------------

def run_iteration(*, harvest_only: bool = False, train_only: bool = False,
                  dry_run: bool = False, confirm_deploy: bool = False) -> int:
    """Run one RSi iteration. Returns exit code."""

    # --- HARVEST ---
    if not train_only:
        candidates = discover_unharvested_py()
        if candidates:
            print(f"[loop] harvest: {len(candidates)} unharvested Python files")
            new = harvest_python_files(candidates[:50], dry_run=dry_run)
            print(f"[loop] corpus grew by {new}  total={corpus_size()}")
        else:
            print(f"[loop] harvest: nothing new  corpus={corpus_size()}")

        if harvest_only:
            return 0

    # --- CHECK trigger ---
    size  = corpus_size()
    delta = size - _last_corpus_size_at_train()
    print(f"[loop] corpus={size}  delta={delta}/{TRAIN_TRIGGER_DELTA}")

    if delta < TRAIN_TRIGGER_DELTA and not train_only:
        print(f"[loop] below trigger ({delta} < {TRAIN_TRIGGER_DELTA}) — skip train")
        return 0

    # --- TRAIN ---
    run_n  = _next_run_number()
    cc     = corpus_cid()
    print(f"[loop] training run #{run_n}  corpus_cid={cc[:20]}…")
    run_rec = run_training(cc, run_n)
    if run_rec is None:
        print("[loop] training failed — aborting iteration")
        return 1

    # --- EVAL ---
    print(f"[loop] evaluating {run_rec['run_id']}…")
    eval_rec = run_eval(run_rec)
    if eval_rec is None:
        print("[loop] eval failed — keeping current checkpoint")
        return 1

    # --- GATE ---
    if eval_rec["verdict"] != "deploy":
        print(f"[loop] verdict={eval_rec['verdict']}  Δ={eval_rec['delta_pp']:+.1f}pp"
              f" < 0.5pp — discarding checkpoint")
        return 0

    # --- DEPLOY ---
    if not confirm_deploy and not dry_run:
        print(f"[loop] verdict=deploy  Δ={eval_rec['delta_pp']:+.1f}pp")
        print("[loop] re-run with --confirm-deploy to push to fleet")
        return 0

    ok = deploy(run_rec, eval_rec, run_n, dry_run=dry_run)
    if ok:
        print(f"[loop] ✓ maxwell-{run_n:04d} live — next harvest uses improved model")
    else:
        print("[loop] deploy failed — fleet unchanged")
        return 1

    return 0


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

def main() -> int:
    ap = argparse.ArgumentParser(description="Maxwell RSi loop")
    ap.add_argument("--harvest-only",   action="store_true")
    ap.add_argument("--train-only",     action="store_true")
    ap.add_argument("--dry-run",        action="store_true")
    ap.add_argument("--confirm-deploy", action="store_true",
                    help="Actually push to fleet (required for deploy step)")
    ap.add_argument("--status",         action="store_true")
    args = ap.parse_args()

    if args.status:
        print_status()
        return 0

    return run_iteration(
        harvest_only   = args.harvest_only,
        train_only     = args.train_only,
        dry_run        = args.dry_run,
        confirm_deploy = args.confirm_deploy,
    )


if __name__ == "__main__":
    raise SystemExit(main())
