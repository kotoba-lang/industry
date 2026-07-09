#!/usr/bin/env python3
"""Stage Ollama models (manifest + blobs) into the warehouse via HARDLINKS so
they can be annex-encrypted to B2 before `ollama rm`. Hardlinks share inodes on
the same FS, so staging adds ~0 disk; space is only reclaimed at `ollama rm`.

Restore: copy <stage>/<model>/manifest.json back to
  ~/.ollama/models/manifests/<registry-path>  and the sha256-* blobs to
  ~/.ollama/models/blobs/  (ollama reads them directly).

usage: archive-ollama-models.py <stage-dir> <model-manifest-relpath> [...]
  manifest relpath = path under ~/.ollama/models/manifests/
prints JSON {model: {repull, blobs:[...], bytes}} for the EDN ledger.
"""
import os, sys, json, hashlib

HOME = os.path.expanduser("~")
MAN = os.path.join(HOME, ".ollama/models/manifests")
BLOBS = os.path.join(HOME, ".ollama/models/blobs")
stage = sys.argv[1]
models = sys.argv[2:]

# re-pull command hints
def repull(relpath):
    # registry.ollama.ai/library/gemma3/4b -> gemma3:4b
    # hf.co/unsloth/X/TAG -> hf.co/unsloth/X:TAG
    parts = relpath.split("/")
    if parts[0] == "registry.ollama.ai" and parts[1] == "library":
        return f"ollama pull {parts[2]}:{parts[3]}"
    if parts[0] == "hf.co":
        return f"ollama pull hf.co/{'/'.join(parts[1:-1])}:{parts[-1]}"
    return f"ollama pull {relpath}"

out = {}
for rel in models:
    man_path = os.path.join(MAN, rel)
    if not os.path.exists(man_path):
        print(f"SKIP {rel}: manifest not found", file=sys.stderr); continue
    man = json.load(open(man_path))
    digests = [man["config"]["digest"]] + [l["digest"] for l in man["layers"]]
    safe = rel.replace("/", "__")
    d = os.path.join(stage, safe)
    os.makedirs(os.path.join(d, "blobs"), exist_ok=True)
    # copy manifest (small) + record its registry path
    json.dump({"_manifest_relpath": rel, "manifest": man},
              open(os.path.join(d, "manifest.json"), "w"), indent=1)
    total = 0
    for dg in digests:
        blob = os.path.join(BLOBS, dg.replace(":", "-"))
        if not os.path.exists(blob):
            print(f"  WARN missing blob {dg} for {rel}", file=sys.stderr); continue
        total += os.path.getsize(blob)
        link = os.path.join(d, "blobs", dg.replace(":", "-"))
        if not os.path.exists(link):
            os.link(blob, link)   # hardlink: no extra disk
    out[rel] = {"repull": repull(rel), "blobs": len(digests),
                "bytes": total, "gb": round(total/1073741824, 2),
                "stage": os.path.relpath(d)}
print(json.dumps(out, ensure_ascii=False, indent=2))
