#!/usr/bin/env python3
"""ipfs add all animeka mp4s + keyframes, write cid manifest."""
import subprocess, json, os
from pathlib import Path

GALLERY = Path("/private/tmp/mangaka-gallery/img")
MANIFEST = GALLERY / "ipfs-cid-manifest.json"

# Categorize
videos = sorted(list(GALLERY.glob("animeka-scene-*.mp4")) + list(GALLERY.glob("animeka-cut*-mma.mp4")) + list(GALLERY.glob("animeka-cut01.mp4")))
images = sorted(list(GALLERY.glob("animeka-cut*-f*.png")))

print(f"=== videos ({len(videos)}) ===")
manifest = {"generated_at": int(__import__("time").time()), "files": {}}
for f in videos + images:
    rel = f.name
    # ipfs add returns "added <CID> <name>"
    r = subprocess.run(["ipfs", "add", "-Q", "--cid-version", "1", str(f)],
                       capture_output=True, text=True)
    if r.returncode != 0:
        print(f"  ✗ {rel}: {r.stderr.strip()}"); continue
    cid = r.stdout.strip()
    size = f.stat().st_size
    manifest["files"][rel] = {"cid": cid, "size": size,
                              "kind": "video" if rel.endswith(".mp4") else "image"}
    print(f"  ✓ {rel:<35s} cid={cid}  ({size//1024}KB)")
    # explicit pin (ipfs add already pins by default, but be explicit)
    subprocess.run(["ipfs", "pin", "add", cid], capture_output=True)

# Also create a directory listing (single CID for the whole bundle)
print(f"\n=== bundle dir ===")
# Use a temp staging dir with just our files via symlinks
import shutil, tempfile
stage = Path(tempfile.mkdtemp(prefix="animeka_bundle_"))
for f in videos + images:
    try: (stage / f.name).symlink_to(f)
    except FileExistsError: pass
r = subprocess.run(["ipfs", "add", "-Q", "-r", "--cid-version", "1", str(stage)],
                   capture_output=True, text=True)
if r.returncode == 0:
    bundle_cid = r.stdout.strip().splitlines()[-1]
    manifest["bundle_cid"] = bundle_cid
    print(f"  ✓ bundle CID: {bundle_cid}")
shutil.rmtree(stage)

MANIFEST.write_text(json.dumps(manifest, indent=2))
print(f"\nmanifest → {MANIFEST}")
print(f"file count: {len(manifest['files'])}")
total_mb = sum(v['size'] for v in manifest['files'].values()) / 1024 / 1024
print(f"total: {total_mb:.1f}MB pinned to local IPFS node")
