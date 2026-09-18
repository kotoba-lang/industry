#!/usr/bin/env python3
"""CISA KEV -> internal-security-nvd:kev/  (daily).

Fetches the Known Exploited Vulnerabilities catalog JSON, validates it
(count >= 1000, catalogVersion present), stages kev.json, writes
kev/manifest.json, publishes with read-back hash verification.
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from kbds_common import http_get, out_dir, publish, write_manifest  # noqa: E402

SOURCE = ("https://www.cisa.gov/sites/default/files/feeds/"
          "known_exploited_vulnerabilities.json")


def main():
    local, size = http_get(SOURCE, min_bytes=500_000)
    with open(local) as f:
        data = json.load(f)
    rows = len(data.get("vulnerabilities", []))
    if rows < 1000 or not data.get("catalogVersion"):
        print(f"FAIL: KEV invalid (rows={rows}, version={data.get('catalogVersion')})")
        return 1
    dest = os.path.join(out_dir("kev"), "kev.json")
    os.replace(local, dest)
    write_manifest("kev", SOURCE,
                   [{"file": "kev.json", "rows": rows}],
                   extra={"catalogVersion": data.get("catalogVersion"),
                          "countReleasedDate": data.get("dateReleased")})
    return publish("kev", None)


if __name__ == "__main__":
    sys.exit(main())
