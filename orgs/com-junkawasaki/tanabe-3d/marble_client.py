"""Marble API thin client — generate + poll + download splats.

API docs: https://docs.worldlabs.ai/api/
Base URL: https://api.worldlabs.ai
Auth:     WLT-Api-Key header

Key acquisition (one-time per user):
  1. Sign in to https://platform.worldlabs.ai/
  2. Purchase credits (min $5 = 6,250 cred; $10 = 12,500 cred fits 8×1,500)
  3. Generate key at https://platform.worldlabs.ai/api-keys
  4. Save to 1Password: gftd.worldlabs/WLT_API_KEY
  5. Export: export WLT_API_KEY=$(op read 'op://gftdcojp/gftd.worldlabs/WLT_API_KEY/password')
"""
from __future__ import annotations

import os
import time
import json
import urllib.request
import urllib.error
from pathlib import Path
from typing import Any

BASE = "https://api.worldlabs.ai"
API_KEY_ENV = "WLT_API_KEY"


class MarbleError(RuntimeError):
    pass


def _key() -> str:
    k = os.environ.get(API_KEY_ENV, "").strip()
    if not k:
        raise MarbleError(f"{API_KEY_ENV} not set — see marble_client docstring")
    return k


def _request(method: str, path: str, body: dict | None = None, timeout: int = 60) -> dict:
    url = BASE + path
    headers = {
        "WLT-Api-Key": _key(),
        "Content-Type": "application/json",
        "Accept": "application/json",
    }
    data = json.dumps(body).encode() if body else None
    req = urllib.request.Request(url, method=method, headers=headers, data=data)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            txt = resp.read().decode("utf-8", errors="replace")
            return json.loads(txt) if txt else {}
    except urllib.error.HTTPError as e:
        body_txt = e.read().decode("utf-8", errors="replace")
        raise MarbleError(f"HTTP {e.code} {method} {path}: {body_txt[:500]}") from e
    except urllib.error.URLError as e:
        raise MarbleError(f"URL error {method} {path}: {e.reason}") from e


def generate_world(
    text_prompt: str,
    display_name: str,
    *,
    model: str = "marble-1.1",   # or "marble-1.1-plus" for higher quality
    seed: int | None = None,
    tags: list[str] | None = None,
) -> dict:
    """POST /marble/v1/worlds:generate. Returns operation envelope (operation_id, done=False)."""
    body: dict[str, Any] = {
        "display_name": display_name[:64],
        "model": model,
        "world_prompt": {"type": "text", "text_prompt": text_prompt},
    }
    if seed is not None:
        body["seed"] = seed
    if tags:
        body["tags"] = tags[:10]
    return _request("POST", "/marble/v1/worlds:generate", body)


def get_operation(operation_id: str) -> dict:
    return _request("GET", f"/marble/v1/operations/{operation_id}")


def get_world(world_id: str) -> dict:
    return _request("GET", f"/marble/v1/worlds/{world_id}")


def poll_until_done(operation_id: str, *, interval_s: int = 20, timeout_s: int = 900) -> dict:
    """Poll an operation until done=True or fail. ~5 min average per Marble docs."""
    t0 = time.time()
    last_progress = None
    while True:
        op = get_operation(operation_id)
        if op.get("done"):
            if op.get("error"):
                raise MarbleError(f"operation {operation_id} failed: {op['error']}")
            return op
        # progress is in metadata
        meta = op.get("metadata", {}) or {}
        progress = meta.get("progress")
        if progress is not None and progress != last_progress:
            print(f"  [{operation_id[:8]}] progress {progress}", flush=True)
            last_progress = progress
        if time.time() - t0 > timeout_s:
            raise MarbleError(f"operation {operation_id} timed out after {timeout_s}s")
        time.sleep(interval_s)


def download(url: str, dst: Path, *, chunk: int = 1 << 20) -> int:
    dst.parent.mkdir(parents=True, exist_ok=True)
    req = urllib.request.Request(url, headers={"WLT-Api-Key": _key()})
    total = 0
    with urllib.request.urlopen(req, timeout=600) as resp, open(dst, "wb") as f:
        while True:
            buf = resp.read(chunk)
            if not buf:
                break
            f.write(buf)
            total += len(buf)
    return total


def find_splat_url(world: dict, *, prefer: str = "full_res") -> str | None:
    """Inspect world response for a downloadable splat URL.

    Marble shape (verified 2026-05-29):
      world.assets.splats.spz_urls = {
        "500k":     "...sand_500k.spz",
        "100k":     "...sand_100k.spz",
        "full_res": "...sand.spz",
      }

    `prefer` selects the tier: full_res | 500k | 100k. Falls back to any
    available tier if the preferred is missing.
    """
    splats = (world.get("assets") or {}).get("splats") or {}
    spz_urls = splats.get("spz_urls") or {}
    if isinstance(spz_urls, dict) and spz_urls:
        if prefer in spz_urls and spz_urls[prefer]:
            return spz_urls[prefer]
        # fallback: any non-empty tier (full_res > 500k > 100k)
        for k in ("full_res", "500k", "100k"):
            if spz_urls.get(k):
                return spz_urls[k]
    # legacy / future: recursive walk for *.spz or *.splat
    def walk(o):
        if isinstance(o, dict):
            for v in o.values():
                if isinstance(v, str) and (v.endswith(".spz") or v.endswith(".splat")):
                    return v
                r = walk(v)
                if r:
                    return r
        elif isinstance(o, list):
            for x in o:
                r = walk(x)
                if r:
                    return r
        return None
    return walk(world)


def find_mesh_url(world: dict) -> str | None:
    mesh = (world.get("assets") or {}).get("mesh") or {}
    return mesh.get("collider_mesh_url") or mesh.get("url")


def find_pano_url(world: dict) -> str | None:
    imagery = (world.get("assets") or {}).get("imagery") or {}
    return imagery.get("pano_url")


def find_thumbnail_url(world: dict) -> str | None:
    return (world.get("assets") or {}).get("thumbnail_url")


def find_caption(world: dict) -> str | None:
    return (world.get("assets") or {}).get("caption")
