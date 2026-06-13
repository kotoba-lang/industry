"""RSi harvest step — fleet 12b generates new Clojure units, gates them,
adds validated pairs to the corpus.

This is the "generate" phase of the Darwin Gödel Machine loop:
  fleet 12b → unit_refactor stage0 → clj-kondo + bb gate → corpus

The improved Maxwell (once deployed) replaces 12b as the generator,
completing the self-improvement feedback loop.
"""
from __future__ import annotations
import json, subprocess, sys, tempfile
from pathlib import Path

from .config import REPO_ROOT, FLEET_NODES, FLEET_BASE_MODEL, MAXWELL_SLOT
from .corpus import ingest_jsonl

# unit_refactor.py lives in fleet-refactor
UNIT_REFACTOR = REPO_ROOT / "70-tools" / "scripts" / "fleet-refactor" / "unit_refactor.py"


def _ollama_check(model: str, node: str) -> bool:
    """Check if a model is available on a fleet node."""
    import urllib.request
    host = node.replace("http://", "").split(":")[0]
    port = node.split(":")[-1]
    try:
        with urllib.request.urlopen(f"http://{host}:{port}/api/tags", timeout=5) as r:
            tags = json.loads(r.read())
        return any(m.get("name", "").startswith(model)
                   for m in tags.get("models", []))
    except Exception:
        return False


def _generator_model() -> str:
    """Use deployed maxwell-* if available on any node, else fall back to 12b."""
    for node in FLEET_NODES:
        if _ollama_check(MAXWELL_SLOT, node):
            return MAXWELL_SLOT
    return FLEET_BASE_MODEL


def harvest_python_files(py_files: list[Path], *,
                         dry_run: bool = False) -> int:
    """Run unit_refactor on a list of Python files, gate, ingest.

    Returns count of newly validated pairs added to corpus.
    """
    if not py_files:
        return 0

    generator = _generator_model()
    print(f"[harvest] generator={generator}  files={len(py_files)}")

    # Write file list to temp
    with tempfile.NamedTemporaryFile(mode="w", suffix=".txt",
                                     delete=False, prefix="rsi-harvest-") as tf:
        tf.write("\n".join(str(p) for p in py_files))
        file_list = tf.name

    # Run unit_refactor with fleet 12b (or maxwell if deployed)
    with tempfile.NamedTemporaryFile(mode="w", suffix=".jsonl",
                                     delete=False, prefix="rsi-harvest-out-") as tf:
        out_path = tf.name

    cmd = [
        sys.executable, str(UNIT_REFACTOR),
        "--file-list", file_list,
        "--out", out_path,
        "--model", generator,
        "--nodes", ",".join(FLEET_NODES),
    ]
    r = subprocess.run(cmd, capture_output=True, text=True)
    import os; os.unlink(file_list)

    if r.returncode != 0:
        print(f"[harvest] unit_refactor failed:\n{r.stderr[-500:]}")
        os.unlink(out_path)
        return 0

    # Ingest gated pairs into corpus + kotoba
    new = ingest_jsonl(Path(out_path), dry_run=dry_run)
    os.unlink(out_path)

    print(f"[harvest] +{new} new validated pairs")
    return new


def discover_unharvested_py(actor_dirs: list[Path] | None = None) -> list[Path]:
    """Find Python actor method files not yet in the corpus."""
    from .corpus import _seen_cids, SFT_CORPUS, _pair_cid
    import json as _json

    seen_ids: set[str] = set()
    if SFT_CORPUS.exists():
        for line in SFT_CORPUS.read_text().splitlines():
            if not line.strip():
                continue
            try:
                rec = _json.loads(line)
                # Record by src_py path to avoid re-harvesting same file
                src = rec.get("meta", {}).get("src_py", "")
                if src:
                    seen_ids.add(src)
            except Exception:
                pass

    if actor_dirs is None:
        actor_dirs = sorted((REPO_ROOT / "20-actors").glob("*/methods"))

    candidates = []
    for d in actor_dirs:
        if not d.is_dir():
            continue
        for py in sorted(d.glob("*.py")):
            if str(py) not in seen_ids:
                candidates.append(py)

    return candidates
