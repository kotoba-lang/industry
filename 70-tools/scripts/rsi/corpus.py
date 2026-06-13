"""RSi corpus accumulator.

Reads maxwell-sft-corpus.jsonl, deduplicates against previously persisted CIDs,
runs charter_rider.scan on any new pairs, and writes validated entries to the
kotoba Datom log.  Returns the count of newly persisted pairs.

Can also be driven as a harvest loop: invoke unit_refactor.py on new Python
source files (fleet 12b generator) and stream results into the corpus.
"""
from __future__ import annotations
import json, hashlib, subprocess, sys
from pathlib import Path
from typing import Iterator

from .cid import cid_of_str
from .kotoba_bridge import write_corpus_pair
from .config import SFT_CORPUS, REPO_ROOT


# ---------------------------------------------------------------------------
# Charter scan (reuses organism pattern)
# ---------------------------------------------------------------------------

def _charter_scan(text: str) -> str:
    """Return 'ok' or a violation tag.  Fail-open: 'skip' if scanner unavailable."""
    try:
        sys.path.insert(0, str(REPO_ROOT))
        from etzhayyim_organism.sensors.charter_rider import scan  # type: ignore
        result = scan(text)
        return "ok" if result.get("passed") else f"fail:{result.get('tag','unknown')}"
    except Exception:
        return "skip"


# ---------------------------------------------------------------------------
# Functional gate: clj-kondo + bb load
# ---------------------------------------------------------------------------

def _clj_gate(clj_src: str) -> str:
    """Gate a Clojure snippet through clj-kondo lint.  Returns 'ok' or 'fail'."""
    try:
        r = subprocess.run(
            ["clj-kondo", "--lint", "-"],
            input=clj_src, capture_output=True, text=True, timeout=10)
        return "ok" if r.returncode == 0 else "fail"
    except FileNotFoundError:
        return "skip"   # clj-kondo not installed on this host
    except Exception:
        return "fail"


# ---------------------------------------------------------------------------
# CID-based deduplication
# ---------------------------------------------------------------------------

def _seen_cids(corpus_path: Path) -> set[str]:
    """Return the set of CIDs already in the corpus (from meta.cid if present)."""
    seen: set[str] = set()
    if not corpus_path.exists():
        return seen
    for line in corpus_path.read_text().splitlines():
        if not line.strip():
            continue
        try:
            rec = json.loads(line)
            cid = rec.get("meta", {}).get("cid")
            if cid:
                seen.add(cid)
        except json.JSONDecodeError:
            pass
    return seen


def _pair_cid(pair: dict) -> str:
    key = pair.get("id", "") + "|" + json.dumps(pair.get("messages", []))
    return cid_of_str(key)


# ---------------------------------------------------------------------------
# Main: ingest new pairs from a source jsonl into the corpus + kotoba
# ---------------------------------------------------------------------------

def ingest_jsonl(source: Path, *, dry_run: bool = False) -> int:
    """Ingest validated pairs from source into SFT_CORPUS and kotoba.

    Returns number of newly accepted pairs.
    """
    seen = _seen_cids(SFT_CORPUS)
    new_count = 0

    with open(SFT_CORPUS, "a") as out_f:
        for line in source.read_text().splitlines():
            if not line.strip():
                continue
            try:
                pair = json.loads(line)
            except json.JSONDecodeError:
                continue

            cid = _pair_cid(pair)
            if cid in seen:
                continue

            # extract clj output from last assistant message
            clj_src = ""
            for msg in reversed(pair.get("messages", [])):
                if msg.get("role") in ("assistant", "model"):
                    clj_src = msg.get("content", "")
                    break

            charter = _charter_scan(clj_src + " " + pair.get("id", ""))
            gate    = _clj_gate(clj_src)

            if charter.startswith("fail") or gate == "fail":
                print(f"  SKIP {pair.get('id','?')}  charter={charter} gate={gate}")
                continue

            pair.setdefault("meta", {})["cid"] = cid
            pair["meta"]["charter_scan"] = charter
            pair["meta"]["clj_gate"]     = gate

            if not dry_run:
                out_f.write(json.dumps(pair, ensure_ascii=False) + "\n")
                actor   = pair.get("meta", {}).get("actor",
                            pair.get("id", "unknown").split("/")[0])
                fn_name = pair.get("meta", {}).get("fn",
                            pair.get("id", "?").split("/")[-1])
                write_corpus_pair(cid, pair.get("id", cid),
                                  actor, fn_name, charter, gate)

            seen.add(cid)
            new_count += 1
            print(f"  + {pair.get('id', cid)}")

    return new_count


def corpus_size() -> int:
    """Return current number of validated pairs in SFT_CORPUS."""
    if not SFT_CORPUS.exists():
        return 0
    return sum(1 for l in SFT_CORPUS.read_text().splitlines() if l.strip())


def corpus_cid() -> str:
    """Content-address the whole corpus for provenance."""
    if not SFT_CORPUS.exists():
        return cid_of_str("")
    return cid_of_str(SFT_CORPUS.read_text())
