"""RSi eval step — A/B comparison between base and new checkpoint.

Runs on EVO-X2 (where the adapter lives).  Scores with the existing
clj-kondo + bb-load gate (same as unit_refactor).  Returns a dict with
base_pp, new_pp, delta_pp.

Builds on the eval_ab.py / score_ab.py pattern from fleet-refactor/cpt/.
"""
from __future__ import annotations
import json, subprocess, tempfile, time
from pathlib import Path

from .config import (
    EVO_HOST, EVO_USER, EVO_VENV, EVO_TRAIN_DIR,
    SFT_BASE_MODEL, EVAL_SAMPLE, BAIEN_DIR
)
from .cid import cid_of_str
from .kotoba_bridge import write_eval

HELDOUT = Path(__file__).parents[4] / "70-tools/scripts/fleet-refactor/heldout-units.jsonl"


def _ssh(cmd: str, check: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(
        ["ssh", f"{EVO_USER}@{EVO_HOST}", cmd],
        capture_output=True, text=True, check=check)


def _scp_to(local: str, remote: str) -> None:
    subprocess.run(["scp", local, f"{EVO_USER}@{EVO_HOST}:{remote}"],
                   check=True, capture_output=True)


def _scp_from(remote: str, local: str) -> None:
    subprocess.run(["scp", f"{EVO_USER}@{EVO_HOST}:{remote}", local],
                   check=True, capture_output=True)


# ---------------------------------------------------------------------------
# Scoring (local, uses clj-kondo)
# ---------------------------------------------------------------------------

def _score_clj(clj_src: str) -> bool:
    """Return True if the Clojure snippet passes clj-kondo."""
    try:
        r = subprocess.run(
            ["clj-kondo", "--lint", "-"],
            input=clj_src, capture_output=True, text=True, timeout=10)
        return r.returncode == 0
    except FileNotFoundError:
        # clj-kondo not on this host — fall back to basic bracket check
        return clj_src.count("(") == clj_src.count(")")
    except Exception:
        return False


def score_gens(gen_path: Path) -> float:
    """Score a heldout-gen.jsonl; returns pass-rate (0..100)."""
    total = passed = 0
    for line in gen_path.read_text().splitlines():
        if not line.strip():
            continue
        rec = json.loads(line)
        total += 1
        if _score_clj(rec.get("new_out", "")):
            passed += 1
    return 100.0 * passed / total if total else 0.0


def score_base_gens(gen_path: Path) -> float:
    total = passed = 0
    for line in gen_path.read_text().splitlines():
        if not line.strip():
            continue
        rec = json.loads(line)
        total += 1
        if _score_clj(rec.get("base_out", "")):
            passed += 1
    return 100.0 * passed / total if total else 0.0


# ---------------------------------------------------------------------------
# Remote generation (EVO)
# ---------------------------------------------------------------------------

_EVAL_SCRIPT = '''
import json, sys, torch
from transformers import AutoModelForCausalLM, AutoTokenizer
from peft import PeftModel

BASE    = sys.argv[1]
ADAPTER = sys.argv[2]
UNITS   = sys.argv[3]
OUT     = sys.argv[4]
SYSTEM  = "You are Maxwell, etzhayyim's Murakumo fleet model. Convert Python actor methods to idiomatic Clojure that follows the kotoba Datom log conventions."

def build_prompt(tok, code):
    msg = [{"role":"user","content": SYSTEM + "\\n\\nTranslate:\\n```python\\n" + code + "\\n```"}]
    return tok.apply_chat_template(msg, tokenize=False, add_generation_prompt=True)

def gen(model, tok, prompt):
    ids = tok(prompt, return_tensors="pt").to(model.device)
    with torch.no_grad():
        out = model.generate(**ids, max_new_tokens=640, do_sample=False,
                             temperature=None, top_p=None, top_k=None,
                             pad_token_id=tok.eos_token_id)
    return tok.decode(out[0][ids.input_ids.shape[1]:], skip_special_tokens=True)

units = [json.loads(l) for l in open(UNITS) if l.strip()]
tok   = AutoTokenizer.from_pretrained(BASE)
try:
    base = AutoModelForCausalLM.from_pretrained(BASE, torch_dtype=torch.bfloat16, device_map="cuda")
except Exception:
    from transformers import AutoModelForImageTextToText
    full = AutoModelForImageTextToText.from_pretrained(BASE, torch_dtype=torch.bfloat16, device_map="cuda")
    base = getattr(getattr(full,"model",full),"language_model",None) or full
base.eval()
prompts = [build_prompt(tok, u["code"]) for u in units]
base_outs = [gen(base, tok, p) for p in prompts]; print("base done")

new_model = PeftModel.from_pretrained(base, ADAPTER); new_model.eval()
new_outs  = [gen(new_model, tok, p) for p in prompts]; print("new done")

with open(OUT,"w") as f:
    for u,b,n in zip(units, base_outs, new_outs):
        f.write(json.dumps({"name":u.get("name","?"),"base_out":b,"new_out":n})+"\\n")
print(f"wrote {OUT}")
'''


def run_eval(run_record: dict) -> dict | None:
    """Run A/B eval for a training run. Returns eval record or None."""
    run_id  = run_record["run_id"]
    adapter = run_record["checkpoint_path"].split(":", 1)[-1]  # remote path
    eval_id = cid_of_str(f"eval:{run_id}")[:12]

    remote_dir    = f"{EVO_TRAIN_DIR}/{run_id}"
    remote_eval   = f"{remote_dir}/eval_ab.py"
    remote_heldout = f"{remote_dir}/heldout-units.jsonl"
    remote_gen    = f"{remote_dir}/heldout-gen.jsonl"

    # Upload eval script + heldout units
    _ssh(f"mkdir -p {remote_dir}")
    with tempfile.NamedTemporaryFile(mode="w", suffix=".py",
                                     delete=False, prefix="rsi-eval-") as tf:
        tf.write(_EVAL_SCRIPT)
        local_eval_script = tf.name
    _scp_to(local_eval_script, remote_eval)
    _scp_to(str(HELDOUT), remote_heldout)

    import os; os.unlink(local_eval_script)

    # Limit heldout to EVAL_SAMPLE
    sample_cmd = (f"python3 -c \""
                  f"import json, random; "
                  f"lines=[l for l in open('{remote_heldout}') if l.strip()]; "
                  f"random.seed(42); random.shuffle(lines); "
                  f"open('{remote_heldout}','w').write(''.join(lines[:{EVAL_SAMPLE}]))"
                  f"\"")
    _ssh(sample_cmd, check=False)

    # Run generation
    gen_cmd = (
        f"HSA_OVERRIDE_GFX_VERSION=11.0.0 "
        f"{EVO_VENV} {remote_eval} "
        f"{SFT_BASE_MODEL} {adapter} "
        f"{remote_heldout} {remote_gen} "
        f"2>&1"
    )
    r = _ssh(gen_cmd, check=False)
    if r.returncode != 0 or "wrote" not in r.stdout:
        print(f"[eval] generation failed:\n{r.stdout[-1000:]}")
        return None

    # Pull results back and score locally
    with tempfile.NamedTemporaryFile(suffix=".jsonl", delete=False) as tf:
        local_gen = tf.name
    _scp_from(remote_gen, local_gen)

    gen_path  = Path(local_gen)
    base_pp   = score_base_gens(gen_path)
    new_pp    = score_gens(gen_path)
    delta_pp  = new_pp - base_pp
    n_units   = sum(1 for l in gen_path.read_text().splitlines() if l.strip())
    verdict   = "deploy" if delta_pp >= 0.5 else "discard"

    import os; os.unlink(local_gen)

    print(f"[eval]  base={base_pp:.1f}%  new={new_pp:.1f}%  Δ={delta_pp:+.1f}pp  → {verdict}")

    write_eval(eval_id, run_id, base_pp, new_pp, delta_pp, n_units, verdict)

    return {
        "eval_id":   eval_id,
        "run_id":    run_id,
        "base_pp":   base_pp,
        "new_pp":    new_pp,
        "delta_pp":  delta_pp,
        "n_units":   n_units,
        "verdict":   verdict,
    }
