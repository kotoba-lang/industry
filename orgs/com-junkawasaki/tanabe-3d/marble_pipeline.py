"""End-to-end pipeline: Marble API → splat → kubo IPFS → gftd pin → datalad.

Usage:
  export WLT_API_KEY=$(op read 'op://gftdcojp/gftd.worldlabs/WLT_API_KEY/password')
  python3 marble_pipeline.py --model marble-1.1 [--scenes op-room,scada,...]
  python3 marble_pipeline.py --resume   # pick up worlds already generated

Output:
  ~/tanabe-3d/output/marble/<scene>.splat
  ~/tanabe-3d/output/marble/ipfs-cid-manifest.json
  ~/tanabe-3d/output/marble/marble-dataset/.datalad/   (git-annex)
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path.home() / "tanabe-3d"
OUT = ROOT / "output" / "marble"
OUT.mkdir(parents=True, exist_ok=True)
sys.path.insert(0, str(ROOT))

from marble_client import (
    generate_world, poll_until_done, get_world, get_operation,
    find_splat_url, find_mesh_url, find_pano_url, find_thumbnail_url, find_caption,
    download, MarbleError,
)
from ipfs_pin import add_file, pin_remote_gftd, write_manifest

# 8 cyber-drill scenes — same prompts as the UI run (op/scada/server/chemical already
# generated under the user's account; the API call can either re-generate or just
# resolve the existing world by ID). To re-use the 4 UI-generated worlds, pass
# --resume with their UUIDs.
SCENES = [
    ("op-room",
     "industrial operating control room, panels with blinking indicators, dim cyan and amber lighting, cables, dust, photoreal"),
    ("scada",
     "SCADA monitoring station, multiple CRT and LCD screens showing process diagrams and pipe flow, beige industrial chairs, fluorescent overhead lighting, dust on monitors, photoreal"),
    ("server-room",
     "server room with parallel server racks, cold blue LED indicators, raised floor with cable management, light vapor, dark industrial photoreal"),
    ("chemical-yard",
     "outdoor chemical processing yard with tall steel storage tanks, exposed pipes and valves, yellow hazard warning signs, gravel ground, overcast grey sky, weathered rust, photoreal industrial site"),
    ("cleanroom",
     "semiconductor cleanroom, white epoxy floor and walls, FFU ceiling, technicians in white bunny suits in background, equipment racks with HEPA filters, fluorescent bright lighting, photoreal"),
    ("exec-room",
     "executive operations room, large mahogany conference table, leather chairs, dark wood paneling, soft warm lamp light, large wall-mounted displays showing KPI dashboards, evening, photoreal"),
    ("utility-room",
     "industrial utility room, HVAC ducts overhead, water pipes along walls, electrical panels, concrete floor, fluorescent strip lighting, photoreal"),
    ("press-room",
     "industrial press room, large hydraulic stamping presses arranged in rows, yellow safety markings on floor, overhead crane rails, oil stains, dim industrial lighting, photoreal"),
]


def init_datalad(dataset_dir: Path) -> None:
    """Initialize a datalad dataset rooted at dataset_dir if missing."""
    if (dataset_dir / ".datalad").is_dir():
        return
    dataset_dir.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        ["datalad", "create", "--description", "Marble worldlabs 3D worlds — cyber-drill scenes", str(dataset_dir)],
        check=True,
    )


def datalad_save(dataset_dir: Path, message: str) -> None:
    subprocess.run(["datalad", "save", "-d", str(dataset_dir), "-m", message], check=True)


def process_scene(
    name: str,
    prompt: str,
    *,
    model: str,
    dataset_dir: Path,
    state: dict,
    skip_existing: bool = True,
) -> dict:
    """Generate one world, download splat, IPFS pin, return manifest entry."""
    entry = {"scene": name, "prompt": prompt, "model": model}
    splat_path = dataset_dir / f"{name}.spz"

    # Skip generation if local artefact already exists
    if skip_existing and splat_path.exists() and splat_path.stat().st_size > 1024:
        entry["splat_path"] = str(splat_path)
        entry["size"] = splat_path.stat().st_size
        entry["skipped"] = "exists"
        print(f"[{name}] skipped (already downloaded {entry['size']} bytes)", flush=True)
    else:
        # Resume support: if state[name] has world_id, fetch directly
        world_id = state.get(name, {}).get("world_id")
        if world_id:
            print(f"[{name}] resuming with world_id={world_id}", flush=True)
            world = get_world(world_id)
        else:
            print(f"[{name}] submitting prompt to Marble API ({model})...", flush=True)
            op = generate_world(prompt, display_name=f"cyber-drill: {name}", model=model)
            op_id = op["operation_id"]
            state.setdefault(name, {})["operation_id"] = op_id
            entry["cost_cred"] = op.get("cost", {}).get("credits") if isinstance(op.get("cost"), dict) else op.get("cost")
            print(f"[{name}] operation_id={op_id} — polling...", flush=True)
            op = poll_until_done(op_id, interval_s=20, timeout_s=1800)
            world = op.get("response", {})
            state[name]["world_id"] = world.get("world_id") or world.get("id")

        splat_url = find_splat_url(world, prefer="full_res")
        if not splat_url:
            entry["error"] = f"no splat URL in world response: {json.dumps(world)[:300]}"
            return entry
        entry["world_id"] = world.get("world_id")
        entry["caption"] = find_caption(world)
        entry["thumbnail_url"] = find_thumbnail_url(world)
        entry["mesh_url"] = find_mesh_url(world)
        entry["pano_url"] = find_pano_url(world)
        entry["world_marble_url"] = world.get("world_marble_url")
        print(f"[{name}] downloading splat ({splat_url[-40:]})...", flush=True)
        n = download(splat_url, splat_path)
        entry["splat_path"] = str(splat_path)
        entry["size"] = n
        print(f"[{name}] downloaded {n} bytes", flush=True)

    # IPFS local add + remote pin
    print(f"[{name}] ipfs add + pin...", flush=True)
    cid = add_file(splat_path)
    entry["cid"] = cid
    entry["cidv"] = 1
    entry["ipfs_url"] = f"https://ipfs.gftd.ai/ipfs/{cid}"
    entry["ipfs_public_url"] = f"https://ipfs.io/ipfs/{cid}"
    pin = pin_remote_gftd(cid, scene=name, source=f"marble.{model}")
    entry["remote_pin"] = pin
    print(f"[{name}] CID {cid}", flush=True)
    return entry


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="marble-1.1", choices=["marble-1.0-draft", "marble-1.0", "marble-1.1", "marble-1.1-plus"])
    ap.add_argument("--scenes", default=",".join(s for s, _ in SCENES))
    ap.add_argument("--resume", action="store_true", help="reuse state.json operation/world IDs")
    ap.add_argument("--no-skip", action="store_true", help="re-download even if local splat exists")
    args = ap.parse_args()

    selected = set(args.scenes.split(","))
    scenes = [(n, p) for n, p in SCENES if n in selected]
    print(f"Processing {len(scenes)} scenes × {args.model}", flush=True)

    dataset_dir = OUT / "marble-dataset"
    init_datalad(dataset_dir)

    state_path = OUT / "state.json"
    state = json.loads(state_path.read_text()) if (args.resume and state_path.exists()) else {}

    manifest: list[dict] = []
    try:
        for name, prompt in scenes:
            try:
                entry = process_scene(
                    name, prompt, model=args.model,
                    dataset_dir=dataset_dir, state=state,
                    skip_existing=not args.no_skip,
                )
            except MarbleError as e:
                entry = {"scene": name, "error": str(e)}
                print(f"[{name}] FAIL: {e}", flush=True)
            manifest.append(entry)
            # Checkpoint after each scene
            state_path.write_text(json.dumps(state, indent=2))
    finally:
        write_manifest(dataset_dir / "ipfs-cid-manifest.json", manifest)

    # Final datalad save
    datalad_save(dataset_dir, f"marble {args.model}: {len([m for m in manifest if 'cid' in m])} / {len(scenes)} scenes")

    ok = [m for m in manifest if "cid" in m]
    print(f"\n=== DONE: {len(ok)}/{len(scenes)} scenes pinned ===")
    for m in manifest:
        if "cid" in m:
            print(f"  ✓ {m['scene']}: {m['cid']}  ({m.get('size', 0)//1024} KB)")
        else:
            print(f"  ✗ {m['scene']}: {m.get('error', 'unknown')[:120]}")
    print(f"\nManifest: {dataset_dir / 'ipfs-cid-manifest.json'}")
    print(f"Dataset:  {dataset_dir} (datalad)")


if __name__ == "__main__":
    main()
