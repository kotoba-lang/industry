#!/usr/bin/env python3
"""sync_catalog_r2.py — publish the social platform catalog to R2 (private).

Execution-only, whitelist-fixed paths (headless cron cannot do approved rm etc.).
Pushes the local catalog to two keys in bucket internal-social-catalog:
  catalog/social-platform-catalog.edn        (latest)
  snapshots/<YYYY-MM-DD>/social-platform-catalog.edn
Then reads back both and verifies sha256 == local. exit 0 = both verified.

Canonical copy: superproject scripts/hermes-social-catalog/sync_catalog_r2.py
"""
import datetime
import hashlib
import os
import re
import subprocess
import sys
import tempfile

WORKTREE = os.path.expanduser("~/.gftd/worktrees/social-catalog-maint")
CATALOG = os.path.join(WORKTREE, "80-data", "system", "social-platform-catalog.edn")
BUCKET = "internal-social-catalog"
KEY_LATEST = "catalog/social-platform-catalog.edn"


def sha256(path):
    with open(path, "rb") as f:
        return hashlib.sha256(f.read()).hexdigest()


def run(cmd, timeout=180):
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        return r.returncode, (r.stdout + r.stderr).strip()
    except Exception as e:  # noqa: BLE001
        return 1, f"{type(e).__name__}: {e}"


def main():
    if not os.path.isfile(CATALOG):
        print("FAIL: catalog missing:", CATALOG)
        return 1
    text = open(CATALOG, encoding="utf-8").read()
    m = re.search(r':measured-at\s+"(\d{4}-\d{2}-\d{2})', text)
    date = m.group(1) if m else datetime.date.today().isoformat()
    keys = [KEY_LATEST, f"snapshots/{date}/social-platform-catalog.edn"]
    want = sha256(CATALOG)
    print(f"local sha256: {want}")
    ok = True
    for key in keys:
        rc, out = run(["wrangler", "r2", "object", "put", f"{BUCKET}/{key}",
                       "--file", CATALOG, "--content-type", "application/edn", "-y"])
        if rc != 0:
            print(f"PUT FAIL {key}: {out[-200:]}")
            ok = False
            continue
        fd, tmp = tempfile.mkstemp(suffix=".edn")
        os.close(fd)
        rc, out = run(["wrangler", "r2", "object", "get", f"{BUCKET}/{key}", "--file", tmp])
        got = sha256(tmp) if rc == 0 and os.path.getsize(tmp) > 0 else "ERR"
        os.remove(tmp)
        if got == want:
            print(f"READBACK MATCH  {key}")
        else:
            print(f"READBACK MISMATCH {key}: {got}")
            ok = False
    print("SYNC " + ("OK" if ok else "FAILED"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
