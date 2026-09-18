#!/usr/bin/env python3
"""HIBP breach corpus -> internal-security-nvd:pwned/  (monthly).

Fetches the Have I Been Pwned public breach corpus (no API key for the
breaches/dataclasses metadata endpoints), validates it (>= 800 breaches),
and stages THREE files under pwned/:
  breaches.json       compact per-breach records (description stripped of
                      HTML and truncated; flags kept verbatim)
  by-domain.json      {domain: [breach, ...]} — the whole lookup the browser
                      UI needs; no second fetch, no external call at read time
  dataclasses.json    the data-class vocabulary (what "leaked" can mean)
Writes pwned/manifest.json and publishes with read-back hash verification.

The pwned.kotoba.cloud app answers "was X exposed" ONLY from these objects —
nothing at request time touches haveibeenpwned.com.
"""
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from kbds_common import http_get, out_dir, publish, write_manifest  # noqa: E402

UA = {"User-Agent": "kotoba-knowledge-datasets/1.0 (pwned corpus sync)"}
BREACHES_URL = "https://haveibeenpwned.com/api/v3/breaches"
DATACLASSES_URL = "https://haveibeenpwned.com/api/v3/dataclasses"


def get_json(url):
    import urllib.request

    req = urllib.request.Request(url, headers=UA)
    import tempfile

    with urllib.request.urlopen(req, timeout=120) as r, \
            tempfile.NamedTemporaryFile(delete=False, suffix=".json") as f:
        while True:
            chunk = r.read(1 << 20)
            if not chunk:
                break
            f.write(chunk)
        path = f.name
    with open(path) as fh:
        return json.load(fh)


def strip_html(s):
    return re.sub(r"<[^>]+>", "", s or "").strip()


def compact(b):
    return {
        "name": b["Name"],
        "title": b["Title"],
        "domain": b.get("Domain"),
        "breachDate": b.get("BreachDate"),
        "addedDate": b.get("AddedDate"),
        "pwnCount": b.get("PwnCount"),
        "dataClasses": b.get("DataClasses") or [],
        "verified": bool(b.get("IsVerified")),
        "sensitive": bool(b.get("IsSensitive")),
        "retired": bool(b.get("IsRetired")),
        "fabricated": bool(b.get("IsFabricated")),
        "spamList": bool(b.get("IsSpamList")),
        "stealerLog": bool(b.get("IsStealerLog")),
        "malware": bool(b.get("IsMalware")),
        "description": strip_html(b.get("Description"))[:400],
    }


def main():
    breaches_raw = get_json(BREACHES_URL)
    if not isinstance(breaches_raw, list) or len(breaches_raw) < 800:
        print(f"FAIL: breaches corpus invalid (n={len(breaches_raw)})")
        return 1
    dataclasses = get_json(DATACLASSES_URL)
    if not isinstance(dataclasses, list) or len(dataclasses) < 50:
        print(f"FAIL: dataclasses invalid (n={len(dataclasses)})")
        return 1

    base = out_dir("pwned")
    comp = [compact(b) for b in breaches_raw]
    by_domain = {}
    for b, c in zip(breaches_raw, comp):
        doms = set()
        if b.get("Domain"):
            doms.add(b["Domain"].lower())
        for d in b.get("Domains") or []:
            doms.add(d.lower())
        for d in doms:
            by_domain.setdefault(d, []).append(c)

    with open(os.path.join(base, "breaches.json"), "w") as f:
        json.dump(comp, f, ensure_ascii=False, separators=(",", ":"))
    with open(os.path.join(base, "by-domain.json"), "w") as f:
        json.dump(by_domain, f, ensure_ascii=False, separators=(",", ":"))
    with open(os.path.join(base, "dataclasses.json"), "w") as f:
        json.dump(dataclasses, f, ensure_ascii=False)

    stealer = sum(1 for c in comp if c["stealerLog"])
    write_manifest(
        "pwned", BREACHES_URL,
        [{"file": "breaches.json", "rows": len(comp)},
         {"file": "by-domain.json", "rows": len(by_domain)},
         {"file": "dataclasses.json", "rows": len(dataclasses)}],
        extra={"breachCount": len(comp),
               "domainCount": len(by_domain),
               "stealerLogCount": stealer})
    print(f"staged pwned: breaches={len(comp)} domains={len(by_domain)} "
          f"dataclasses={len(dataclasses)} stealerLogs={stealer}")
    return publish("pwned", None)


if __name__ == "__main__":
    sys.exit(main())
