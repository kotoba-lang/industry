"""IPFS publish — local kubo add + gftd permanent pin XRPC.

Pattern per ADR-2604261936 (IPFS self-hosted Vultr+B2) + ADR-2605262200
(animeka v9 mmaudio IPFS scene pipeline):

  Stage 1: local `ipfs add --cid-version 1 --pin` → CIDv1, kubo local pin
  Stage 2: ai.gftd.apps.ipfsIngest.pin XRPC → gftd-managed Filecoin anchor

Requires:
  - `ipfs` (kubo) installed locally and `ipfs daemon` running, or `ipfs init` done
  - For Stage 2: gftd agent token (gftd auth login) — optional, skipped if unavailable
"""
from __future__ import annotations

import json
import subprocess
import shutil
from pathlib import Path


class IpfsError(RuntimeError):
    pass


def _ipfs_bin() -> str:
    p = shutil.which("ipfs")
    if not p:
        raise IpfsError("ipfs (kubo) not in PATH — install via `brew install ipfs`")
    return p


def ensure_repo() -> None:
    """Init the kubo repo if not yet done. Idempotent."""
    ipfs = _ipfs_bin()
    # `ipfs repo stat` exits 0 if initialised
    r = subprocess.run([ipfs, "repo", "stat"], capture_output=True, text=True)
    if r.returncode != 0:
        subprocess.run([ipfs, "init"], check=True)


def add_file(path: Path, *, cid_version: int = 1, pin: bool = True) -> str:
    """Add a file to local kubo. Returns CIDv1 string."""
    ensure_repo()
    ipfs = _ipfs_bin()
    cmd = [ipfs, "add", "-Q", f"--cid-version={cid_version}"]
    if not pin:
        cmd.append("--pin=false")
    cmd.append(str(path))
    r = subprocess.run(cmd, capture_output=True, text=True, check=True)
    cid = r.stdout.strip()
    if not cid:
        raise IpfsError(f"ipfs add produced no CID for {path}")
    return cid


def add_dir(path: Path, *, cid_version: int = 1) -> str:
    """Add a directory recursively. Returns CIDv1 of the dir wrapper."""
    ensure_repo()
    ipfs = _ipfs_bin()
    r = subprocess.run(
        [_ipfs_bin(), "add", "-Q", "-r", f"--cid-version={cid_version}", str(path)],
        capture_output=True, text=True, check=True,
    )
    return r.stdout.strip().split("\n")[-1]


def pin_remote_gftd(cid: str, *, scene: str, source: str = "marble-1.1") -> dict:
    """Stage 2: ask the gftd cluster to permanently pin this CID via XRPC.

    XRPC: ai.gftd.apps.ipfsIngest.pin (gftd-managed Filecoin backing)
    Requires `gftd` CLI in PATH and `gftd auth login` already done.

    Returns the XRPC response dict, or {'skipped': '<reason>'} if env is incomplete.
    """
    gftd = shutil.which("gftd")
    if not gftd:
        return {"skipped": "gftd CLI not in PATH (run from gftd repo or install)"}
    # gftd CLI conventionally accepts XRPC payload via subcommand; if not, fall
    # back to a direct REST call. Adapt to your CLI version.
    cmd = [
        gftd, "xrpc", "call", "ai.gftd.apps.ipfsIngest.pin",
        "--input", json.dumps({"cid": cid, "scene": scene, "source": source}),
    ]
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        return {"skipped": f"xrpc call failed: {r.stderr[:200]}", "cid": cid}
    try:
        return json.loads(r.stdout)
    except json.JSONDecodeError:
        return {"raw": r.stdout, "cid": cid}


def write_manifest(out_path: Path, entries: list[dict]) -> None:
    """Write the ipfs-cid-manifest.json that downstream consumers read."""
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps({
        "schema": "ipfs-cid-manifest/v1",
        "source": "marble.worldlabs.ai",
        "entries": entries,
    }, indent=2, ensure_ascii=False))
