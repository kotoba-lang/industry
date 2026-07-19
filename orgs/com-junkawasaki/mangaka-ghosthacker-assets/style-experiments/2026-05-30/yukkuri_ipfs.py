#!/usr/bin/env python3
"""ipfs add yukkuri outputs + manifest + bundle CID."""
import subprocess, json, time, tempfile, shutil
from pathlib import Path

GALLERY = Path("/private/tmp/yukkuri-gallery")
IMG = GALLERY / "img"
MANIFEST = IMG / "ipfs-cid-manifest.json"

files = sorted(list(IMG.glob("yukkuri-*.mp4")) + list(IMG.glob("yukkuri-*.png")))
manifest = {"generated_at": int(time.time()),
            "project": "yukkuri",
            "topic": "IPFS とは何か",
            "files": {}}
print(f"=== ipfs add {len(files)} files ===")
for f in files:
    r = subprocess.run(["ipfs","add","-Q","--cid-version","1",str(f)],
                       capture_output=True, text=True)
    if r.returncode != 0:
        print(f"  ✗ {f.name}: {r.stderr.strip()}"); continue
    cid = r.stdout.strip()
    size = f.stat().st_size
    manifest["files"][f.name] = {"cid": cid, "size": size,
                                 "kind": "video" if f.suffix == ".mp4" else "image"}
    print(f"  ✓ {f.name:<35s} cid={cid}  ({size//1024}KB)")
    subprocess.run(["ipfs","pin","add",cid], capture_output=True)

# bundle dir
stage = Path(tempfile.mkdtemp(prefix="yukkuri_bundle_"))
for f in files:
    try: (stage/f.name).symlink_to(f)
    except FileExistsError: pass
r = subprocess.run(["ipfs","add","-Q","-r","--cid-version","1",str(stage)],
                   capture_output=True, text=True)
if r.returncode == 0:
    bundle = r.stdout.strip().splitlines()[-1]
    manifest["bundle_cid"] = bundle
    print(f"\n  ✓ bundle CID: {bundle}")
shutil.rmtree(stage)

MANIFEST.write_text(json.dumps(manifest, indent=2, ensure_ascii=False))
total_mb = sum(v['size'] for v in manifest['files'].values()) / 1024 / 1024
print(f"\nmanifest → {MANIFEST}")
print(f"total: {total_mb:.1f}MB pinned to local IPFS node")
