"""IPFS publish — local kubo add + gateway URL.

Pattern per ADR-2604261936 (IPFS self-hosted Vultr+B2) + ADR-2605262200
(animeka v9 mmaudio IPFS scene pipeline):

  Stage 1: local `ipfs add --cid-version 1 --pin` → CIDv1, kubo local pin
  Stage 2: RETIRED (ADR-2607300100) — the ai.gftd.apps.ipfsIngest.pin XRPC
    went through the gftd PDS, which that ADR retired; the lexicon answers 404
    at the successor. pin_remote_gftd skips with that reason. A permanent-pin
    successor (kotobase pinning) is an open decision, not wired here.

Requires:
  - `ipfs` (kubo) installed locally and `ipfs daemon` running, or `ipfs init` done
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
    """Stage 2 (retired): permanent pin via the gftd PDS XRPC.

    The endpoint ai.gftd.apps.ipfsIngest.pin lived on the gftd PDS, which
    ADR-2607300100 retired — lexicons answer 404 at the successor
    (pds.aozora.app) and the gftd CLI is retired tooling. The call is skipped
    with that reason; entries carry {"skipped": ...} exactly as they did when
    the CLI was missing, so callers keep working unchanged.
    """
    return {"skipped": "gftd PDS retired (ADR-2607300100); permanent-pin successor is an open decision"}


def write_manifest(out_path: Path, entries: list[dict]) -> None:
    """Write the ipfs-cid-manifest.json that downstream consumers read."""
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps({
        "schema": "ipfs-cid-manifest/v1",
        "source": "marble.worldlabs.ai",
        "entries": entries,
    }, indent=2, ensure_ascii=False))
