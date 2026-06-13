"""RSi harvest step — fleet generates new Clojure units, gates, adds to corpus.

Wraps unit_refactor.py (stage0: function-unit decomposition → fleet → clj-kondo gate)
and ingests validated SFT pairs into the corpus + kotoba Datom log.

The feedback loop: once Maxwell is deployed on fleet, _generator_model() returns
the Maxwell slot instead of the 12b baseline → improved Maxwell generates
better training data → stronger next checkpoint.
"""
from __future__ import annotations
import json, os, subprocess, sys, tempfile, shutil
from pathlib import Path

from .config import REPO_ROOT, FLEET_BASE_MODEL, MAXWELL_SLOT
from . import corpus as _corpus_mod
from .corpus import ingest_jsonl

FLEET_REFACTOR_DIR = REPO_ROOT / "70-tools" / "scripts" / "fleet-refactor"
UNIT_REFACTOR      = FLEET_REFACTOR_DIR / "unit_refactor.py"
SFT_LOG_NAME       = "fleet-refactor-sft.jsonl"   # written by unit_refactor in CWD

# Fleet node to probe for Maxwell deployment
_FLEET_PROBE_NODE = "http://100.101.27.85:11434"


def _ollama_models(node_url: str) -> list[str]:
    import urllib.request
    try:
        with urllib.request.urlopen(f"{node_url}/api/tags", timeout=5) as r:
            return [m["name"] for m in json.loads(r.read()).get("models", [])]
    except Exception:
        return []


def _generator_model() -> str:
    """Return maxwell-N if deployed on fleet, else fall back to 12b baseline."""
    models = _ollama_models(_FLEET_PROBE_NODE)
    # Check for any deployed maxwell-* slot
    maxwell_slots = [m for m in models if m.startswith("maxwell-") or m == MAXWELL_SLOT]
    if maxwell_slots:
        return sorted(maxwell_slots)[-1]   # latest deployed
    # Fall back to 12b (better generator per A/B empirical result +7.4pp)
    if any("12b" in m for m in models):
        return next(m for m in models if "12b" in m)
    return FLEET_BASE_MODEL


def harvest_python_files(py_files: list[Path], *,
                         dry_run: bool = False,
                         batch_size: int = 50) -> int:
    """Run unit_refactor on py_files in batches, gate, ingest. Returns new pair count."""
    if not py_files:
        return 0

    generator = _generator_model()
    total_new = 0

    for i in range(0, len(py_files), batch_size):
        batch = py_files[i:i + batch_size]
        print(f"[harvest] batch {i//batch_size + 1}: {len(batch)} files  generator={generator}")

        # Run unit_refactor from its own directory so SFT log lands in the right place
        # unit_refactor.py writes SFT pairs to fleet-refactor-sft.jsonl in CWD
        with tempfile.TemporaryDirectory(prefix="rsi-harvest-") as tmpdir:
            sft_out = Path(tmpdir) / SFT_LOG_NAME

            cmd = [
                sys.executable, str(UNIT_REFACTOR),
                "--model", generator,
            ] + [str(p) for p in batch]

            r = subprocess.run(
                cmd,
                capture_output=False,   # stream to stdout for visibility
                cwd=str(FLEET_REFACTOR_DIR),
                env={**os.environ},
            )

            # SFT log is written to CWD (fleet-refactor dir); copy to tmp
            cwd_sft = FLEET_REFACTOR_DIR / SFT_LOG_NAME
            if not cwd_sft.exists():
                print(f"[harvest] no SFT log produced")
                continue
            shutil.copy(cwd_sft, sft_out)

            new = ingest_jsonl(sft_out, dry_run=dry_run)
            total_new += new
            print(f"[harvest] batch done: +{new} new  total={total_new}")

    return total_new


def discover_unharvested_py(actor_dirs: list[Path] | None = None) -> list[Path]:
    """Find Python actor method files not yet represented in the corpus."""
    seen_paths: set[str] = set()
    corpus_path = _corpus_mod.SFT_CORPUS
    if corpus_path.exists():
        for line in corpus_path.read_text().splitlines():
            if not line.strip():
                continue
            try:
                rec = json.loads(line)
                src = rec.get("meta", {}).get("src_py", "")
                if src:
                    seen_paths.add(src)
            except Exception:
                pass

    if actor_dirs is None:
        actor_dirs = sorted((REPO_ROOT / "20-actors").glob("*/methods"))

    candidates: list[Path] = []
    for d in actor_dirs:
        if not d.is_dir():
            continue
        for py in sorted(d.glob("*.py")):
            if str(py) not in seen_paths:
                candidates.append(py)

    return candidates
