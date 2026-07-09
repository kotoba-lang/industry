"""12-scene Marble world generation using OpenAI plant photos as image input.

Per-scene flow:
  1. media-assets:prepare_upload → upload_url + media_asset_id
  2. PUT image bytes
  3. worlds:generate (type=image, source=media_asset)
  4. poll → world response
  5. download SPZ 500k + thumbnail

State (~/tanabe-3d/output/marble-12-state.json) is updated after every step so a
re-run resumes mid-flight.

Usage:
  source ~/.gftd/worldlabs.env && export WLT_API_KEY
  python3 marble_12scene.py [--only scene_name] [--model marble-1.1]
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path.home() / "tanabe-3d"))
from marble_client import (
    poll_until_done, get_world, find_splat_url, find_thumbnail_url, find_caption,
    download, _request, MarbleError,
)

PLANT_DIR = Path.home() / "tanabe-3d/output/openai-plants"
DEPLOY_DIR = Path.home() / "tanabe-3d/cyber-drill-marble-v20/public"
STATE_PATH = Path.home() / "tanabe-3d/output/marble-12-state.json"

SCENE_ORDER = [
    "gate-guard", "central-control-room", "engineering-ws", "soc-monitoring",
    "server-room", "plc-panel-room", "field-junction", "reactor-floor",
    "tank-yard", "esd-station", "cleanroom", "incident-room",
]


def load_state() -> dict:
    return json.loads(STATE_PATH.read_text()) if STATE_PATH.exists() else {}


def save_state(state: dict) -> None:
    STATE_PATH.parent.mkdir(parents=True, exist_ok=True)
    STATE_PATH.write_text(json.dumps(state, indent=2))


def marble_upload_image(image_path: Path) -> str:
    prep = _request(
        "POST", "/marble/v1/media-assets:prepare_upload",
        body={"file_name": image_path.name, "kind": "image", "extension": "png"},
    )
    media_asset_id = prep["media_asset"]["media_asset_id"]
    upload_url = prep["upload_info"]["upload_url"]
    required_headers = prep["upload_info"].get("required_headers", {}) or {}

    import urllib.request
    headers = dict(required_headers)
    headers.setdefault("Content-Type", "image/png")
    req = urllib.request.Request(
        upload_url, method="PUT", data=image_path.read_bytes(), headers=headers,
    )
    with urllib.request.urlopen(req, timeout=120) as r:
        if r.status not in (200, 201, 204):
            raise RuntimeError(f"upload failed {r.status}: {r.read()[:200]}")
    return media_asset_id


def generate_world_from_image(media_asset_id: str, display_name: str, *, model: str) -> dict:
    body = {
        "display_name": display_name[:64],
        "model": model,
        "world_prompt": {
            "type": "image",
            "image_prompt": {"source": "media_asset", "media_asset_id": media_asset_id},
            "is_pano": False,
        },
    }
    return _request("POST", "/marble/v1/worlds:generate", body)


def process(name: str, *, model: str, state: dict) -> dict:
    entry = state.setdefault(name, {})
    img_path = PLANT_DIR / f"{name}.png"
    if not img_path.exists():
        return {"error": f"missing photo {img_path}"}

    # 1. Upload (skip if media_asset_id already saved)
    if not entry.get("media_asset_id"):
        print(f"[{name}] uploading image…", flush=True)
        try:
            mid = marble_upload_image(img_path)
        except Exception as e:
            return {"error": f"upload: {type(e).__name__}: {e}"}
        entry["media_asset_id"] = mid
        save_state(state)
        print(f"[{name}]   media_asset_id={mid[:18]}…", flush=True)
    mid = entry["media_asset_id"]

    # 2. Submit generation (skip if world_id already known)
    if not entry.get("world_id"):
        print(f"[{name}] submitting world generation ({model})…", flush=True)
        try:
            op = generate_world_from_image(mid, display_name=f"cyber-drill 12: {name}", model=model)
        except Exception as e:
            return {"error": f"generate: {type(e).__name__}: {e}"}
        opid = op["operation_id"]
        entry["operation_id"] = opid
        save_state(state)
        print(f"[{name}]   operation_id={opid} — polling…", flush=True)
        try:
            op = poll_until_done(opid, interval_s=20, timeout_s=1800)
        except Exception as e:
            return {"error": f"poll: {type(e).__name__}: {e}"}
        w = op.get("response", {})
        wid = w.get("world_id")
        entry["world_id"] = wid
        save_state(state)
    else:
        w = get_world(entry["world_id"])
    wid = entry["world_id"]

    # 3. Download SPZ 500k + thumbnail (skip if file exists)
    spz_dst = DEPLOY_DIR / "spz" / f"{name}.spz"
    spz_dst.parent.mkdir(parents=True, exist_ok=True)
    if not spz_dst.exists() or spz_dst.stat().st_size < 1_000_000:
        spz_url = find_splat_url(w, prefer="500k")
        if not spz_url:
            return {"error": "no spz url"}
        print(f"[{name}] downloading SPZ 500k…", flush=True)
        n = download(spz_url, spz_dst)
        entry["spz_size"] = n
        print(f"[{name}]   {n//1024} KB", flush=True)
    thumb_url = find_thumbnail_url(w)
    if thumb_url:
        thumb_dst = DEPLOY_DIR / "thumb" / f"{name}.webp"
        if not thumb_dst.exists():
            try:
                download(thumb_url, thumb_dst)
            except Exception:
                pass

    entry["world_marble_url"] = w.get("world_marble_url")
    entry["caption"] = find_caption(w) or ""
    save_state(state)
    return {"ok": True, "world_id": wid, "spz_size": entry.get("spz_size", 0)}


def main():
    if not os.environ.get("WLT_API_KEY"):
        sys.exit("WLT_API_KEY not set")
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="marble-1.1",
                    choices=["marble-1.0-draft", "marble-1.0", "marble-1.1", "marble-1.1-plus"])
    ap.add_argument("--only", default=None, help="comma-separated scene names to limit")
    args = ap.parse_args()

    only = set(args.only.split(",")) if args.only else None
    scenes = [s for s in SCENE_ORDER if (not only or s in only)]
    state = load_state()

    print(f"Generating {len(scenes)} Marble worlds (model={args.model}, ~{len(scenes)*1500} cred)")
    print(f"State: {STATE_PATH}")
    print()

    results = []
    for name in scenes:
        try:
            r = process(name, model=args.model, state=state)
        except MarbleError as e:
            r = {"error": str(e)}
        results.append({"scene": name, **r})
        if "error" in r:
            print(f"[{name}] ✗ {r['error']}", flush=True)
            # If credits hit, stop early — no point continuing
            if "402" in str(r.get("error", "")) or "Insufficient credits" in str(r.get("error", "")):
                print("\nCredits exhausted — stopping further generations.")
                break
        else:
            print(f"[{name}] ✓ world_id={r['world_id']}  spz={r['spz_size']//1024} KB", flush=True)

    ok = [r for r in results if r.get("ok")]
    print(f"\n=== {len(ok)}/{len(scenes)} succeeded ===")
    print(f"State saved to {STATE_PATH}")
    print("Next: rebuild public/scenes.json from state, then wrangler deploy")


if __name__ == "__main__":
    main()
