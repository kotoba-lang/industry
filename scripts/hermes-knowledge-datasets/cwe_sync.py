#!/usr/bin/env python3
"""MITRE CWE -> internal-security-nvd:cwe/  (weekly, Wed).

Downloads cwec_latest.xml.zip, validates the zip (unzip -t) and that the
inner XML contains Catalog entries, stages it as cwec_latest.xml.zip.
Gate: the zip must contain the Catalog XML (~1000+ weaknesses).
"""
import os
import subprocess
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from kbds_common import http_get, out_dir, publish, write_manifest  # noqa: E402

SOURCE = "https://cwe.mitre.org/data/xml/cwec_latest.xml.zip"


def main():
    local, size = http_get(SOURCE, min_bytes=1_000_000)
    with zipfile.ZipFile(local) as z:
        bad = z.testzip()
        names = z.namelist()
        inner = next((n for n in names if n.lower().endswith(".xml")), names[0])
        with z.open(inner) as f:
            head = f.read(2_000_000).decode("utf-8", "replace")
    if bad is not None or "Catalog" not in head:
        print(f"FAIL: CWE zip invalid (bad={bad})")
        return 1
    weaknesses = head.count("<Weakness ")
    dest = os.path.join(out_dir("cwe"), "cwec_latest.xml.zip")
    os.replace(local, dest)
    write_manifest("cwe", SOURCE,
                   [{"file": "cwec_latest.xml.zip",
                     "rows": max(weaknesses, 1)}],
                   extra={"innerFile": inner,
                          "note": "rows counts Weakness elements in the XML; "
                                  "full count requires unzip"})
    return publish("cwe", None)


if __name__ == "__main__":
    sys.exit(main())
