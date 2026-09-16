#!/usr/bin/env python3
"""Publish the per-year NVD Parquet dataset + manifest to the PRIVATE
internal-security-nvd R2 bucket: catalog/<file> (latest) plus a dated
snapshots/<YYYY-MM-DD>/<file> copy. Every PUT is verified by a --remote GET
read-back whose sha256 must equal the local file (same --remote trap and
readback discipline as hermes-sales-catalog).

Precondition: fetch_nvd.py full completed (parquet/ + manifest.json exist).
"""
import datetime
import hashlib
import os
import subprocess
import sys
import tempfile

HOME_DIR = os.path.expanduser("~")
OUT = os.environ.get("NVD_OUT", os.path.expanduser("~/nvd-dataset"))
BUCKET = "internal-security-nvd"
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


def main():
    files = sorted(f for f in os.listdir(os.path.join(OUT, "parquet"))
                   if f.endswith(".parquet"))
    manifest = os.path.join(OUT, "manifest.json")
    if not files or not os.path.isfile(manifest):
        print("FAIL: nothing built yet (run fetch_nvd.py full)", file=sys.stderr)
        return 1
    date = datetime.date.today().isoformat()
    ok = True
    for f in [os.path.join("parquet", x) for x in files] + ["manifest.json"]:
        local = os.path.join(OUT, f)
        base = os.path.basename(f)
        want = sha256(local)
        for key in (f"catalog/{base}", f"snapshots/{date}/{base}"):
            rc, out = wrangler(["r2", "object", "put", f"{BUCKET}/{key}",
                                "--file", local,
                                "--content-type",
                                "application/octet-stream" if base.endswith(".parquet")
                                else "application/json"])
            if rc != 0:
                print(f"PUT FAIL {key}: {out[-200:]}")
                ok = False
                continue
            fd, tmp = tempfile.mkstemp(suffix="." + base.rsplit(".", 1)[-1])
            os.close(fd)
            rc, out = wrangler(["r2", "object", "get", f"{BUCKET}/{key}",
                                "--file", tmp])
            got = sha256(tmp) if rc == 0 and os.path.getsize(tmp) > 0 else "ERR"
            os.remove(tmp)
            if got == want:
                print(f"READBACK MATCH  {key}  ({os.path.getsize(local)} bytes)")
            else:
                print(f"READBACK MISMATCH {key}: {got}")
                ok = False
    print("PUBLISH " + ("OK" if ok else "FAILED"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
