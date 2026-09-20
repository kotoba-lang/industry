#!/usr/bin/env python3
"""Publish the yabai-data catalog (3 per-domain Parquet + manifest + raw
snapshots) to the kotoba-open-data R2 bucket: catalog/<file> (latest), plus
dated snapshots/<YYYY-MM-DD>/ copies. Every PUT is verified by a --remote GET
read-back whose sha256 must equal the local file (same discipline as
hermes-sales-catalog / hermes-nvd-dataset / hermes-osint-catalog; account-scope
verification is NOT trustworthy, always --remote).
"""
import datetime
import hashlib
import os
import subprocess
import sys
import tempfile

HOME_DIR = os.path.expanduser("~")
OUT = os.environ.get("YABAI_OUT", os.path.join(HOME_DIR, ".gftd", "yabai-data-catalog"))
BUCKET = "kotoba-open-data"
PERSIST = "--persist-to=" + os.path.join(HOME_DIR, ".wrangler", "remotepersist")


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def wrangler(args, timeout=600):
    cmd = ["wrangler"] + args + ["--remote", PERSIST]
    try:
        r = subprocess.run(cmd, capture_output=True, text=True,
                           timeout=timeout, cwd=HOME_DIR)
        return r.returncode, (r.stdout + r.stderr).strip()
    except Exception as e:  # noqa: BLE001
        return 1, f"{type(e).__name__}: {e}"


def put_verify(local, key, ctype):
    rc, out = wrangler(["r2", "object", "put", f"{BUCKET}/{key}",
                        "--file", local, "--content-type", ctype])
    if rc != 0:
        print(f"PUT FAIL {key}: {out[-200:]}")
        return False
    fd, tmp = tempfile.mkstemp(suffix=".bin")
    os.close(fd)
    rc, out = wrangler(["r2", "object", "get", f"{BUCKET}/{key}", "--file", tmp])
    got = sha256(tmp) if rc == 0 and os.path.getsize(tmp) > 0 else "ERR"
    os.remove(tmp)
    if got == sha256(local):
        print(f"READBACK MATCH  {key}  ({os.path.getsize(local)} bytes)")
        return True
    print(f"READBACK MISMATCH {key}: {got}")
    return False


def main():
    pdir = os.path.join(OUT, "parquet")
    manifest = os.path.join(OUT, "manifest.json")
    files = sorted(f for f in os.listdir(pdir) if f.endswith(".parquet")) if os.path.isdir(pdir) else []
    if not files or not os.path.isfile(manifest):
        print("FAIL: nothing built yet (run collect_yabai_r2.py)", file=sys.stderr)
        return 1
    date = datetime.date.today().isoformat()
    ok = True
    for f in ["parquet/" + x for x in files] + ["manifest.json"]:
        local = os.path.join(OUT, f)
        base = os.path.basename(f)
        ctype = ("application/vnd.apache.parquet" if base.endswith(".parquet")
                 else "application/json")
        for key in (f"catalog/{base}", f"snapshots/{date}/{base}"):
            ok = put_verify(local, key, ctype) and ok
    # raw snapshots (published beside the parquet, same mirror discipline)
    for root, _, names in os.walk(os.path.join(OUT, "raw")):
        for n in sorted(names):
            local = os.path.join(root, n)
            key = os.path.relpath(local, OUT)
            ok = put_verify(local, key, "application/json") and ok
    print("PUBLISH " + ("OK" if ok else "FAILED"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
