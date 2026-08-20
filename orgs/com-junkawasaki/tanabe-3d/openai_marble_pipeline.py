"""Generate 5 photoreal industrial plant photos via OpenAI gpt-image-1,
then feed each into Marble worldlabs as image input to regenerate the
cyber-drill v20 scenes.

Pipeline:
  1. OpenAI gpt-image-1 → 1024×1024 high-quality plant photos (×5)
  2. Marble media-assets:prepare_upload → media_asset_id + signed PUT URL
  3. PUT image bytes to signed URL
  4. Marble worlds:generate with image_prompt + media_asset_id source
  5. Poll until done → download SPZ 500k tier
  6. Update scenes.json + IPFS pin + deploy v20

Cost (estimate):
  - OpenAI: 5 × $0.167 high-quality 1024² = $0.84
  - Marble:  5 × 1,500 cred = 7,500 cred = $6.00 (need credits in account)
  - Total ~$6.84

Usage:
  source ~/.gftd/worldlabs.env
  source ~/.gftd/openai.env
  export WLT_API_KEY OPENAI_API_KEY
  python3 openai_marble_pipeline.py
"""
from __future__ import annotations

import base64
import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path.home() / "tanabe-3d"))
from marble_client import generate_world, poll_until_done, get_world, find_splat_url, find_thumbnail_url, find_caption, download, _request

OPENAI_API = "https://api.openai.com"

OUT_ROOT = Path.home() / "tanabe-3d/output"
PLANT_DIR = OUT_ROOT / "openai-plants"
DEPLOY_DIR = Path.home() / "tanabe-3d/cyber-drill-marble-v20/public"
PLANT_DIR.mkdir(parents=True, exist_ok=True)

# 5 photoreal industrial plant scene prompts for OpenAI gpt-image-1.
# Aim: a single dominant "scene plate" image that Marble can lift into 3D.
SCENES = [
    ("op-room",
     "photoreal interior of an industrial operating control room at night, multiple control consoles facing forward in a semi-circle, "
     "panels with hundreds of small blinking cyan and amber indicator LEDs, thick cable trays running along the floor and ceiling, "
     "subtle dust on surfaces, cool ambient lighting from monitor glow, no people, high resolution photograph, 50mm lens, neutral white balance, "
     "Sony A7R IV style, sharp focus throughout, no logos, no text overlays"),
    ("scada",
     "photoreal interior of a SCADA monitoring station, wide desk with 8 CRT and LCD displays showing process diagrams of pipe networks and tank levels, "
     "beige ergonomic chairs in front of workstations, fluorescent overhead light, dust on monitor edges, beige cable management trays along the back wall, "
     "industrial flooring with anti-static finish, no people in frame, high resolution architectural photograph, ultra detailed, 35mm lens, neutral lighting"),
    ("server-room",
     "photoreal interior of a large server room, parallel rows of black server racks stretching into the distance, cold blue LED indicator lights along every rack, "
     "raised floor with cable cutouts visible, light vapor diffusing the LEDs, ceiling cable trays, dim ambient blue and white lighting, no people, "
     "data center photography in the style of Google data center, high resolution, sharp perspective lines, photoreal"),
    ("chemical-yard",
     "photoreal outdoor view of a chemical processing yard, several tall white-painted steel storage tanks with weathered rust streaks, "
     "exposed steel pipe networks with insulation and yellow valve handles, hazard warning signs in yellow on chain-link fences, "
     "gravel and concrete ground, distant industrial smokestacks under an overcast grey sky, no people, no vehicles, "
     "industrial photography style, ultra high resolution, sharp focus, photoreal documentary look"),
    ("cleanroom",
     "photoreal interior of a semiconductor cleanroom, white epoxy floor and wall panels, FFU (fan filter unit) ceiling grid with bright fluorescent panels, "
     "equipment racks lining the walls with HEPA filter doors visible, yellow caution lines on the floor, no people, ultra clean look, "
     "bright even illumination, high resolution architectural photograph, no logos, photoreal interior"),
]


def _openai_key() -> str:
    k = os.environ.get("OPENAI_API_KEY", "").strip()
    if not k:
        sys.exit("OPENAI_API_KEY not set — `source ~/.gftd/openai.env && export OPENAI_API_KEY`")
    return k


def gen_image_openai(prompt: str, dst: Path, quality: str = "high", size: str = "1024x1024") -> int:
    """Call gpt-image-1, save PNG to dst, return bytes written."""
    if dst.exists() and dst.stat().st_size > 50_000:
        return dst.stat().st_size  # skip — already generated
    body = json.dumps({
        "model": "gpt-image-1",
        "prompt": prompt,
        "n": 1,
        "size": size,
        "quality": quality,
    }).encode()
    req = urllib.request.Request(
        f"{OPENAI_API}/v1/images/generations",
        method="POST",
        data=body,
        headers={
            "Authorization": f"Bearer {_openai_key()}",
            "Content-Type": "application/json",
        },
    )
    with urllib.request.urlopen(req, timeout=180) as r:
        resp = json.loads(r.read())
    b64 = resp["data"][0]["b64_json"]
    raw = base64.b64decode(b64)
    dst.write_bytes(raw)
    return len(raw)


def marble_upload_image(image_path: Path) -> str:
    """Returns media_asset_id usable in image_prompt.media_asset source."""
    prep = _request(
        "POST",
        "/marble/v1/media-assets:prepare_upload",
        body={"file_name": image_path.name, "kind": "image", "extension": "png"},
    )
    media_asset_id = prep["media_asset"]["media_asset_id"]
    upload_url = prep["upload_info"]["upload_url"]
    required_headers = prep["upload_info"].get("required_headers", {}) or {}

    # PUT the image bytes
    headers = dict(required_headers)
    headers.setdefault("Content-Type", "image/png")
    req = urllib.request.Request(
        upload_url, method="PUT", data=image_path.read_bytes(), headers=headers,
    )
    with urllib.request.urlopen(req, timeout=120) as r:
        # Should be 200/201 with empty body
        if r.status not in (200, 201, 204):
            raise RuntimeError(f"upload failed {r.status}: {r.read()[:200]}")
    return media_asset_id


def generate_world_from_image(media_asset_id: str, display_name: str, *, model: str = "marble-1.1", text_hint: str | None = None) -> dict:
    body = {
        "display_name": display_name[:64],
        "model": model,
        "world_prompt": {
            "type": "image",
            "image_prompt": {"source": "media_asset", "media_asset_id": media_asset_id},
            "is_pano": False,
        },
    }
    if text_hint:
        body["world_prompt"]["text_prompt"] = text_hint
    return _request("POST", "/marble/v1/worlds:generate", body)


def main():
    if not os.environ.get("WLT_API_KEY"):
        sys.exit("WLT_API_KEY not set — `source ~/.gftd/worldlabs.env && export WLT_API_KEY`")
    _openai_key()  # fail-fast if openai key missing

    print(f"Pipeline: 5 scenes × (OpenAI gpt-image-1 → Marble image-input)")
    print(f"  plants: {PLANT_DIR}")
    print(f"  deploy: {DEPLOY_DIR}")
    print()

    state_path = OUT_ROOT / "openai-marble-state.json"
    state = json.loads(state_path.read_text()) if state_path.exists() else {}
    manifest = []

    for name, prompt in SCENES:
        entry = {"scene": name}
        try:
            # 1. OpenAI photo
            img_path = PLANT_DIR / f"{name}.png"
            print(f"[{name}] generating photo via gpt-image-1…", flush=True)
            sz = gen_image_openai(prompt, img_path, quality="high", size="1024x1024")
            entry["openai_image"] = str(img_path)
            entry["openai_image_size"] = sz
            print(f"[{name}]   ✓ {sz//1024} KB → {img_path.name}", flush=True)

            # 2. Marble upload
            mid = state.get(name, {}).get("media_asset_id")
            if not mid:
                print(f"[{name}] uploading to Marble…", flush=True)
                mid = marble_upload_image(img_path)
                state.setdefault(name, {})["media_asset_id"] = mid
                state_path.write_text(json.dumps(state, indent=2))
                print(f"[{name}]   media_asset_id={mid[:18]}…", flush=True)

            # 3. World generation
            wid = state[name].get("world_id")
            if not wid:
                print(f"[{name}] generating Marble 1.1 world from image…", flush=True)
                op = generate_world_from_image(mid, display_name=f"cyber-drill image: {name}")
                opid = op["operation_id"]
                state[name]["operation_id"] = opid
                state_path.write_text(json.dumps(state, indent=2))
                op = poll_until_done(opid, interval_s=20, timeout_s=1500)
                w = op.get("response", {})
                wid = w.get("world_id")
                state[name]["world_id"] = wid
                state_path.write_text(json.dumps(state, indent=2))
            else:
                w = get_world(wid)
            print(f"[{name}]   world_id={wid}", flush=True)

            # 4. Download SPZ 500k + thumbnail
            spz_url = find_splat_url(w, prefer="500k")
            spz_dst = DEPLOY_DIR / "spz" / f"{name}.spz"
            spz_dst.parent.mkdir(parents=True, exist_ok=True)
            print(f"[{name}] downloading SPZ 500k…", flush=True)
            n = download(spz_url, spz_dst)
            entry["spz_size"] = n
            print(f"[{name}]   ✓ {n//1024} KB → {spz_dst.name}", flush=True)

            thumb_url = find_thumbnail_url(w)
            if thumb_url:
                thumb_dst = DEPLOY_DIR / "thumb" / f"{name}.webp"
                download(thumb_url, thumb_dst)

            entry["world_id"] = wid
            entry["world_marble_url"] = w.get("world_marble_url")
            entry["caption"] = find_caption(w)
            entry["thumb"] = f"./thumb/{name}.webp"
            manifest.append(entry)

        except Exception as e:
            entry["error"] = f"{type(e).__name__}: {e}"
            print(f"[{name}]   ✗ FAIL: {e}", flush=True)
            manifest.append(entry)

    # Rewrite scenes.json for the v20 viewer
    scenes_payload = [
        {
            "scene": m["scene"],
            "world_id": m.get("world_id"),
            "world_url": m.get("world_marble_url"),
            "thumb": m.get("thumb"),
            "caption": m.get("caption", ""),
            "cid": "openai-marble-v2-pending",  # IPFS pin can be re-run later
        }
        for m in manifest if "spz_size" in m
    ]
    (DEPLOY_DIR / "scenes.json").write_text(json.dumps(scenes_payload, indent=2))
    print(f"\nWrote {len(scenes_payload)} scenes to scenes.json")
    print("Next: cd ~/tanabe-3d/cyber-drill-marble-v20 && wrangler deploy")


if __name__ == "__main__":
    main()
