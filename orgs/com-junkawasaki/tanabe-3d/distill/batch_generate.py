"""Distill batch: generate the 20 missing worlds + download panoramas.

Reads prompts.json (25 scenes; 5 already done) and runs the 20 new ones via
worldlabs Marble API. For each world:
  - downloads SPZ (3D Gaussian Splat) for 3DGS pipeline reference
  - downloads panorama PNG (equirectangular) — the SDXL LoRA target
  - records caption + IPFS CID
  - resumable via state.json (operation_id checkpoint per scene)

Output:
  ~/tanabe-3d/distill/dataset/<scene>/{spz,pano.png,meta.json}
  ~/tanabe-3d/distill/state.json
  ~/tanabe-3d/distill/manifest.json

Usage:
  export WLT_API_KEY=$(op read 'op://gftdcojp/gftd.worldlabs/WLT_API_KEY/password')
  # or: source ~/.gftd/worldlabs.env
  python3 ~/tanabe-3d/distill/batch_generate.py --model marble-1.1 [--limit N]
  python3 ~/tanabe-3d/distill/batch_generate.py --resume
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

ROOT = Path.home() / "tanabe-3d"
DISTILL = ROOT / "distill"
DATASET = DISTILL / "dataset"
STATE_PATH = DISTILL / "state.json"
MANIFEST_PATH = DISTILL / "manifest.json"

sys.path.insert(0, str(ROOT))
from marble_client import (
    generate_world, poll_until_done, get_world,
    find_splat_url, find_pano_url, find_caption, find_thumbnail_url, find_mesh_url,
    download, MarbleError,
)


def load_prompts() -> list[dict]:
    return json.loads((DISTILL / "prompts.json").read_text())["scenes"]


def load_state() -> dict:
    return json.loads(STATE_PATH.read_text()) if STATE_PATH.exists() else {}


def save_state(state: dict) -> None:
    STATE_PATH.write_text(json.dumps(state, indent=2))


def process_scene(scene: dict, model: str, state: dict, *, no_skip: bool = False) -> dict:
    name = scene["id"]
    prompt = scene["prompt"]
    scene_dir = DATASET / name
    scene_dir.mkdir(parents=True, exist_ok=True)

    spz_path = scene_dir / "world.spz"
    pano_path = scene_dir / "pano.png"
    meta_path = scene_dir / "meta.json"

    entry: dict = {"scene": name, "domain": scene.get("domain"), "prompt": prompt, "model": model}

    # Skip if already complete
    if not no_skip and spz_path.exists() and pano_path.exists() and meta_path.exists():
        meta = json.loads(meta_path.read_text())
        entry.update(meta)
        entry["skipped"] = "complete"
        print(f"[{name}] skipped (complete)", flush=True)
        return entry

    # Generate or resume
    world_id = state.get(name, {}).get("world_id")
    op_id = state.get(name, {}).get("operation_id")
    world = None
    if world_id:
        print(f"[{name}] resume world_id={world_id}", flush=True)
        world = get_world(world_id)
    elif op_id:
        print(f"[{name}] resume op_id={op_id} — polling...", flush=True)
        op = poll_until_done(op_id, interval_s=20, timeout_s=1800)
        world = op.get("response", {})
        state.setdefault(name, {})["world_id"] = world.get("world_id") or world.get("id")
        save_state(state)
    else:
        print(f"[{name}] generate via {model}: {prompt[:80]}...", flush=True)
        op = generate_world(prompt, display_name=f"distill: {name}", model=model)
        op_id = op["operation_id"]
        state.setdefault(name, {})["operation_id"] = op_id
        save_state(state)
        print(f"[{name}] op_id={op_id} — polling (up to 30 min)...", flush=True)
        op = poll_until_done(op_id, interval_s=20, timeout_s=1800)
        world = op.get("response", {})
        state[name]["world_id"] = world.get("world_id") or world.get("id")
        save_state(state)

    if not world:
        entry["error"] = "no world response"
        return entry

    entry["world_id"] = world.get("world_id") or world.get("id")
    entry["caption"] = find_caption(world)
    entry["thumbnail_url"] = find_thumbnail_url(world)
    entry["mesh_url"] = find_mesh_url(world)
    entry["world_marble_url"] = world.get("world_marble_url")

    # Download SPZ (3DGS, 500k tier for size — full_res is 27-29MB)
    spz_url = find_splat_url(world, prefer="500k") or find_splat_url(world, prefer="full_res")
    if spz_url:
        print(f"[{name}] download SPZ...", flush=True)
        n = download(spz_url, spz_path)
        entry["spz_size"] = n
    else:
        entry["spz_error"] = "no SPZ URL"

    # Download panorama (the LoRA training target)
    pano_url = find_pano_url(world)
    if pano_url:
        print(f"[{name}] download panorama...", flush=True)
        n = download(pano_url, pano_path)
        entry["pano_size"] = n
        entry["pano_url"] = pano_url
    else:
        entry["pano_error"] = "no pano URL"

    meta_path.write_text(json.dumps(entry, indent=2))
    print(f"[{name}] OK — spz={entry.get('spz_size',0)//1024}KB pano={entry.get('pano_size',0)//1024}KB", flush=True)
    return entry


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="marble-1.1",
                    choices=["marble-1.0-draft", "marble-1.0", "marble-1.1", "marble-1.1-plus"])
    ap.add_argument("--limit", type=int, default=None,
                    help="only process N scenes from the to-generate list (for cost-controlled launches)")
    ap.add_argument("--resume", action="store_true", help="(no-op; resume is default — state.json always honored)")
    ap.add_argument("--no-skip", action="store_true", help="re-download even if files exist")
    ap.add_argument("--scenes", default=None, help="comma-separated scene IDs (overrides --limit)")
    args = ap.parse_args()

    prompts = load_prompts()
    state = load_state()

    if args.scenes:
        wanted = set(args.scenes.split(","))
        todo = [s for s in prompts if s["id"] in wanted]
    else:
        # Default: all 25 (skip-existing handles already-done)
        todo = prompts

    if args.limit:
        todo = todo[:args.limit]

    print(f"=== distill batch: {len(todo)} scene(s), model={args.model} ===", flush=True)
    print(f"   state: {STATE_PATH}", flush=True)
    print(f"   dataset: {DATASET}", flush=True)

    manifest: list[dict] = []
    t0 = time.time()
    for i, scene in enumerate(todo, 1):
        print(f"\n[{i}/{len(todo)}] {scene['id']} ({scene.get('domain','?')})", flush=True)
        try:
            entry = process_scene(scene, model=args.model, state=state, no_skip=args.no_skip)
        except MarbleError as e:
            entry = {"scene": scene["id"], "error": str(e)}
            print(f"[{scene['id']}] FAIL: {e}", flush=True)
        manifest.append(entry)
        MANIFEST_PATH.write_text(json.dumps(manifest, indent=2))

    dt = time.time() - t0
    ok = [m for m in manifest if "error" not in m]
    print(f"\n=== done in {dt/60:.1f} min: {len(ok)}/{len(manifest)} OK ===")
    for m in manifest:
        if "error" in m:
            print(f"  ✗ {m['scene']}: {m['error'][:120]}")
        else:
            print(f"  ✓ {m['scene']} (pano {m.get('pano_size',0)//1024}KB, spz {m.get('spz_size',0)//1024}KB)")


if __name__ == "__main__":
    main()
