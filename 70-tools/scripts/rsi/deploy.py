"""RSi deploy step — Ollama model swap on Murakumo fleet.

On a passing eval (verdict=deploy), merges the LoRA adapter into the base
weight, creates an Ollama Modelfile, and pushes it as `maxwell-<N>` to every
fleet node.  Updates maxwell-models.jsonl with available: true.

Gate: deploy is gated on eval verdict AND explicit --confirm flag to prevent
accidental fleet swaps.
"""
from __future__ import annotations
import json, subprocess, tempfile, os
from pathlib import Path

from .config import (
    EVO_HOST, EVO_USER, EVO_VENV, EVO_TRAIN_DIR,
    FLEET_NODES, MAXWELL_SLOT, MODELS_LOG, SFT_BASE_MODEL
)
from .kotoba_bridge import write_checkpoint


def _ssh(cmd: str, host: str = EVO_HOST, user: str = EVO_USER,
         check: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(
        ["ssh", f"{user}@{host}", cmd],
        capture_output=True, text=True, check=check)


def _ollama_create(node_url: str, model_name: str, modelfile: str) -> bool:
    """Push a Modelfile to an Ollama node via the REST API."""
    import urllib.request, urllib.error
    host = node_url.replace("http://", "").split(":")[0]
    port = node_url.split(":")[-1]
    payload = json.dumps({"name": model_name, "modelfile": modelfile,
                           "stream": False}).encode()
    req = urllib.request.Request(
        f"http://{host}:{port}/api/create",
        data=payload,
        headers={"Content-Type": "application/json"},
        method="POST")
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            return r.status == 200
    except Exception as e:
        print(f"  [deploy] {node_url}: {e}")
        return False


_MERGE_SCRIPT = '''
import sys, torch
BASE    = sys.argv[1]
ADAPTER = sys.argv[2]
OUT     = sys.argv[3]

from transformers import AutoModelForCausalLM, AutoTokenizer
from peft import PeftModel

tok = AutoTokenizer.from_pretrained(BASE)
try:
    base = AutoModelForCausalLM.from_pretrained(BASE, torch_dtype=torch.bfloat16)
except Exception:
    from transformers import AutoModelForImageTextToText
    full = AutoModelForImageTextToText.from_pretrained(BASE, torch_dtype=torch.bfloat16)
    base = getattr(getattr(full,"model",full),"language_model",None) or full

model = PeftModel.from_pretrained(base, ADAPTER)
merged = model.merge_and_unload()
merged.save_pretrained(OUT)
tok.save_pretrained(OUT)
print(f"merged → {OUT}")
'''


def deploy(run_record: dict, eval_record: dict,
           run_number: int, *, dry_run: bool = False) -> bool:
    """Merge adapter, build Ollama Modelfile, push to all fleet nodes.

    Returns True on success.
    """
    run_id   = run_record["run_id"]
    adapter  = run_record["checkpoint_path"].split(":", 1)[-1]
    model_name = f"maxwell-{run_number:04d}"

    print(f"[deploy] {model_name}  base={eval_record['base_pp']:.1f}%"
          f" → new={eval_record['new_pp']:.1f}% (+{eval_record['delta_pp']:+.1f}pp)")

    if dry_run:
        print("[deploy] DRY RUN — skipping fleet push")
        return True

    # 1. Merge adapter on EVO
    remote_dir    = f"{EVO_TRAIN_DIR}/{run_id}"
    remote_merged = f"{remote_dir}/merged"
    merge_script  = f"{remote_dir}/merge.py"

    with tempfile.NamedTemporaryFile(mode="w", suffix=".py", delete=False) as tf:
        tf.write(_MERGE_SCRIPT)
        local_merge = tf.name
    subprocess.run(["scp", local_merge, f"{EVO_USER}@{EVO_HOST}:{merge_script}"],
                   check=True, capture_output=True)
    os.unlink(local_merge)

    merge_cmd = (
        f"HSA_OVERRIDE_GFX_VERSION=11.0.0 "
        f"{EVO_VENV} {merge_script} "
        f"{SFT_BASE_MODEL} {adapter} {remote_merged} 2>&1"
    )
    r = _ssh(merge_cmd, check=False)
    if r.returncode != 0 or "merged →" not in r.stdout:
        print(f"[deploy] merge failed:\n{r.stdout[-500:]}")
        return False

    # 2. Build Ollama Modelfile on EVO and register
    modelfile = (
        f"FROM {remote_merged}\n"
        f"SYSTEM \"You are Maxwell, etzhayyim's Murakumo fleet model "
        f"(Charter-aligned instruction fine-tune of Gemma 4 E4B, {model_name}).\"\n"
    )
    remote_modelfile = f"{remote_dir}/Modelfile"
    _ssh(f"cat > {remote_modelfile} << 'EOF'\n{modelfile}\nEOF", check=False)
    _ssh(f"ollama create {model_name} -f {remote_modelfile} 2>&1", check=False)

    # 3. Push to each fleet node via Ollama REST
    ok_nodes = 0
    for node in FLEET_NODES:
        if _ollama_create(node, model_name, modelfile):
            ok_nodes += 1
            print(f"  [deploy] {node} ✓")
        else:
            print(f"  [deploy] {node} ✗")

    if ok_nodes == 0:
        print("[deploy] no nodes accepted the model — aborting")
        return False

    # 4. Update maxwell-models.jsonl — mark available: true
    lines = []
    if Path(MODELS_LOG).exists():
        for line in Path(MODELS_LOG).read_text().splitlines():
            if not line.strip():
                continue
            rec = json.loads(line)
            if rec.get("run_id") == run_id:
                rec["available"] = True
                rec["ollama_model"] = model_name
                rec["deployed_nodes"] = ok_nodes
            lines.append(json.dumps(rec, ensure_ascii=False))
    Path(MODELS_LOG).write_text("\n".join(lines) + "\n")

    # 5. Kotoba checkpoint record
    checkpoint_id = f"{model_name}-{eval_record['eval_id']}"
    write_checkpoint(
        checkpoint_id   = checkpoint_id,
        run_id          = run_id,
        ollama_model    = model_name,
        hf_model        = f"etzhayyim/{model_name}-gemma4-e4b",
        base_pp         = eval_record["base_pp"],
        deployed_pp     = eval_record["new_pp"],
    )

    print(f"[deploy] {model_name} live on {ok_nodes}/{len(FLEET_NODES)} nodes")
    return True
