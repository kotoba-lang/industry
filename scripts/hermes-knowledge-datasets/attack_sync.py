#!/usr/bin/env python3
"""MITRE ATT&CK Enterprise STIX -> internal-security-nvd:attack/  (weekly, Mon).

Downloads enterprise-attack.json from mitre-attack/attack-stix-data
(master branch), validates it (type attack-pattern present, >= 1000
objects), stages it as enterprise-attack.json.
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from kbds_common import http_get, out_dir, publish, write_manifest  # noqa: E402

SOURCE = ("https://raw.githubusercontent.com/mitre-attack/attack-stix-data/"
          "master/enterprise-attack/enterprise-attack.json")


def main():
    local, size = http_get(SOURCE, min_bytes=5_000_000, timeout=900)
    with open(local) as f:
        data = json.load(f)
    objs = data.get("objects", [])
    if data.get("type") != "bundle" or len(objs) < 1000:
        print(f"FAIL: ATT&CK bundle invalid (type={data.get('type')}, objects={len(objs)})")
        return 1
    counts = {}
    for o in objs:
        counts[o.get("type", "?")] = counts.get(o.get("type", "?"), 0) + 1
    dest = os.path.join(out_dir("attack"), "enterprise-attack.json")
    os.replace(local, dest)
    write_manifest("attack", SOURCE,
                   [{"file": "enterprise-attack.json", "rows": len(objs)}],
                   extra={"attackVersion": (data.get("x_mitre_version")
                                            or data.get("spec_version") or "unknown"),
                          "objectTypeCounts": counts})
    return publish("attack", None)


if __name__ == "__main__":
    sys.exit(main())
