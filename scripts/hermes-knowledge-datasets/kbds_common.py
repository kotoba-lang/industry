#!/usr/bin/env python3
"""Shared plumbing for knowledge.kotoba.cloud dataset syncs.

Layout in the PRIVATE R2 bucket internal-security-nvd (same bucket NVD uses):
  <ds>/<file>            latest object(s) for the dataset (ds: kev|epss|cwe|attack)
  <ds>/manifest.json     per-dataset manifest (files[], sha256, rows, generatedAt)
  snapshots/<YYYY-MM-DD>/<ds>/<file>   dated copy (skipped when include_snapshots=False)

Every PUT is verified by a --remote GET read-back whose sha256 must equal the
local file. wrangler is ALWAYS run with cwd=$HOME and --persist-to=$HOME/.wrangler/remotepersist
(wrangler 4.x silently switches to a LOCAL miniflare store otherwise).
"""
import datetime
import hashlib
import json
import os
import subprocess
import sys
import tempfile

HOME_DIR = os.path.expanduser("~")
BUCKET = os.environ.get("KBDS_BUCKET", "internal-security-nvd")
PERSIST = "--persist-to=" + os.path.join(HOME_DIR, ".wrangler", "remotepersist")
OUT_ROOT = os.environ.get("KBDS_OUT", os.path.expanduser("~/knowledge-datasets"))

CONTENT_TYPES = {
    ".json": "application/json",
    ".csv": "text/csv",
    ".zip": "application/zip",
    ".gz": "application/gzip",
}


def out_dir(ds):
    p = os.path.join(OUT_ROOT, ds)
    os.makedirs(p, exist_ok=True)
    return p


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def http_get(url, timeout=300, min_bytes=1):
    """GET url -> (local_path, bytes). urllib is fine; sources are static."""
    import urllib.request

    req = urllib.request.Request(url, headers={"User-Agent": "kotoba-knowledge-datasets/1.0"})
    name = url.rsplit("/", 1)[-1].split("?")[0] or "download.bin"
    local = os.path.join(tempfile.mkdtemp(prefix="kbds-fetch-"), name)
    with urllib.request.urlopen(req, timeout=timeout) as r, open(local, "wb") as f:
        while True:
            chunk = r.read(1 << 20)
            if not chunk:
                break
            f.write(chunk)
    size = os.path.getsize(local)
    if size < min_bytes:
        raise RuntimeError(f"download too small: {size} bytes ({url})")
    return local, size


def publish(ds, _opts=None):
    """PUT each manifest file + the manifest itself; verify read-back hashes."""
    date = datetime.date.today().isoformat()
    ok = True
    base_dir = out_dir(ds)
    with open(os.path.join(base_dir, "manifest.json")) as fh:
        manifest = json.load(fh)
    entries = [(f["file"], f["sha256"]) for f in manifest["files"]]
    entries.append(("manifest.json", None))
    base_dir = out_dir(ds)
    for rel, _ in entries:
        local = os.path.join(base_dir, rel)
        if not os.path.isfile(local):
            print(f"FAIL missing {ds}/{rel}")
            ok = False
            continue
        want = sha256(local)
        ctype = CONTENT_TYPES.get("." + rel.rsplit(".", 1)[-1], "application/octet-stream")
        for key in (f"{ds}/{rel}", f"snapshots/{date}/{ds}/{rel}"):
            if key.startswith(f"snapshots/") and rel == "manifest.json":
                continue
            rc, outp = wrangler(["r2", "object", "put", f"{BUCKET}/{key}",
                                 "--file", local, "--content-type", ctype])
            if rc != 0:
                print(f"PUT FAIL {key}: {outp[-200:]}")
                ok = False
                continue
            fd, tmp = tempfile.mkstemp(suffix=os.path.splitext(rel)[1])
            os.close(fd)
            rc, outp = wrangler(["r2", "object", "get", f"{BUCKET}/{key}", "--file", tmp])
            got = sha256(tmp) if rc == 0 and os.path.getsize(tmp) > 0 else "ERR"
            os.remove(tmp)
            if got == want:
                print(f"READBACK MATCH  {key}  ({os.path.getsize(local)} bytes)")
            else:
                print(f"READBACK MISMATCH {key}: {got}")
                ok = False
    print(f"PUBLISH {ds} " + ("OK" if ok else "FAILED"))
    return 0 if ok else 1


def wrangler(args, timeout=600):
    cmd = ["wrangler"] + args + ["--remote", PERSIST]
    try:
        r = subprocess.run(cmd, capture_output=True, text=True,
                           timeout=timeout, cwd=HOME_DIR)
        return r.returncode, (r.stdout + r.stderr).strip()
    except Exception as e:  # noqa: BLE001
        return 1, f"{type(e).__name__}: {e}"


def write_manifest(ds, source_url, files, extra=None):
    """files: list of {file, rows} already staged in out_dir(ds)."""
    base = out_dir(ds)
    m = {
        "dataset": ds,
        "generatedAt": datetime.datetime.now(datetime.timezone.utc)
                       .replace(microsecond=0).isoformat().replace("+00:00", "Z"),
        "sourceUrl": source_url,
        "files": [],
    }
    if extra:
        m.update(extra)
    for f in files:
        p = os.path.join(base, f["file"])
        m["files"].append({
            "file": f["file"],
            "bytes": os.path.getsize(p),
            "sha256": sha256(p),
            "rows": f["rows"],
        })
    with open(os.path.join(base, "manifest.json"), "w") as fh:
        json.dump(m, fh, indent=2)
    return m
