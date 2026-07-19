"""Write :maxwell.* datoms to the live kotoba engine (:8077).

Schema (append-only, per ADR-2606130900):
  :maxwell.corpus/*    — validated training pair (keyed by CID)
  :maxwell.run/*       — training run record
  :maxwell.eval/*      — A/B eval result
  :maxwell.checkpoint/* — deployed checkpoint record

Reuses the ibuki bridge pattern (exactly-once cursor, :maxwell.tx/* provenance).
"""
from __future__ import annotations
import json, time, urllib.request, urllib.error
from typing import Any
from .config import KOTOBA_URL


def _tx(datoms: list[dict]) -> dict | None:
    """POST a single transaction to kotoba. Returns response or None on failure."""
    body = json.dumps({"tx-data": datoms}).encode()
    req = urllib.request.Request(
        f"{KOTOBA_URL}/datomic/transact",
        data=body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return json.loads(r.read())
    except Exception as e:
        print(f"[kotoba_bridge] tx failed: {e}")
        return None


def write_corpus_pair(cid: str, pair_id: str, actor: str, fn_name: str,
                      charter_scan: str, gate: str) -> bool:
    """Record a validated SFT pair as a :maxwell.corpus datom."""
    now = int(time.time() * 1000)
    datoms = [
        {":db/id": f"maxwell.corpus.{cid}",
         ":maxwell.corpus/cid":    cid,
         ":maxwell.corpus/pair-id": pair_id,
         ":maxwell.corpus/actor":  actor,
         ":maxwell.corpus/fn":     fn_name,
         ":maxwell.corpus/charter-scan": charter_scan,
         ":maxwell.corpus/gate":   gate,
         ":maxwell.corpus/at":     now},
        {":db/id": f"maxwell.tx.{now}",
         ":maxwell.tx/kind":  ":corpus",
         ":maxwell.tx/cid":   cid,
         ":maxwell.tx/at":    now},
    ]
    return _tx(datoms) is not None


def write_run(run_id: str, corpus_cid: str, base_model: str,
              epochs: float, lora_r: int, corpus_size: int,
              checkpoint_path: str, checkpoint_cid: str,
              lora_b_mass: float, final_loss: float) -> bool:
    now = int(time.time() * 1000)
    datoms = [
        {":db/id": f"maxwell.run.{run_id}",
         ":maxwell.run/id":            run_id,
         ":maxwell.run/corpus-cid":    corpus_cid,
         ":maxwell.run/base-model":    base_model,
         ":maxwell.run/epochs":        epochs,
         ":maxwell.run/lora-r":        lora_r,
         ":maxwell.run/corpus-size":   corpus_size,
         ":maxwell.run/checkpoint-path": checkpoint_path,
         ":maxwell.run/checkpoint-cid": checkpoint_cid,
         ":maxwell.run/lora-b-mass":   lora_b_mass,
         ":maxwell.run/final-loss":    final_loss,
         ":maxwell.run/at":            now},
        {":db/id": f"maxwell.tx.{now}",
         ":maxwell.tx/kind": ":run",
         ":maxwell.tx/run-id": run_id,
         ":maxwell.tx/at":    now},
    ]
    return _tx(datoms) is not None


def write_eval(eval_id: str, run_id: str,
               base_pp: float, new_pp: float, delta_pp: float,
               n_units: int, verdict: str) -> bool:
    now = int(time.time() * 1000)
    datoms = [
        {":db/id": f"maxwell.eval.{eval_id}",
         ":maxwell.eval/id":       eval_id,
         ":maxwell.eval/run-id":   run_id,
         ":maxwell.eval/base-pp":  base_pp,
         ":maxwell.eval/new-pp":   new_pp,
         ":maxwell.eval/delta-pp": delta_pp,
         ":maxwell.eval/n-units":  n_units,
         ":maxwell.eval/verdict":  verdict,
         ":maxwell.eval/at":       now},
        {":db/id": f"maxwell.tx.{now}",
         ":maxwell.tx/kind":     ":eval",
         ":maxwell.tx/eval-id":  eval_id,
         ":maxwell.tx/at":       now},
    ]
    return _tx(datoms) is not None


def write_checkpoint(checkpoint_id: str, run_id: str,
                     ollama_model: str, hf_model: str,
                     base_pp: float, deployed_pp: float) -> bool:
    now = int(time.time() * 1000)
    datoms = [
        {":db/id": f"maxwell.checkpoint.{checkpoint_id}",
         ":maxwell.checkpoint/id":           checkpoint_id,
         ":maxwell.checkpoint/run-id":        run_id,
         ":maxwell.checkpoint/ollama-model":  ollama_model,
         ":maxwell.checkpoint/hf-model":      hf_model,
         ":maxwell.checkpoint/base-pp":       base_pp,
         ":maxwell.checkpoint/deployed-pp":   deployed_pp,
         ":maxwell.checkpoint/at":            now},
        {":db/id": f"maxwell.tx.{now}",
         ":maxwell.tx/kind":           ":checkpoint",
         ":maxwell.tx/checkpoint-id":  checkpoint_id,
         ":maxwell.tx/at":             now},
    ]
    return _tx(datoms) is not None
