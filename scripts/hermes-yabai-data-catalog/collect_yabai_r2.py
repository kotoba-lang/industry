#!/usr/bin/env python3
"""Collect the three public /yabai-data catalogs (yabai / crypto / waterplum)
from kotoba.cloud and normalise them into one Parquet table per domain plus a
manifest, for the yabai-data-catalog-sync no_agent bot.

Source of record is the live public face served by the app-kotoba-cloud worker
(https://kotoba.cloud/yabai-data/<domain>/index.json). Those are the SAME IPLD
blocks the site itself renders from, so the bucket is a faithful mirror of what
is published — never a divergent copy. The waterplum / crypto / yabai source
JSON (assets/yabai-*-src/*.json) lives in a PRIVATE repo, so this collector
reads the public data plane instead (raw.githubusercontent 404s unauthenticated;
verified).

Mechanical collector: no judgment here. The catalog bots own the underlying
observations via PRs; this only mirrors what is already published and records
evidence (http status, sha256, row counts, IPLD head).

Output (default ~/.gftd/yabai-data-catalog):
  parquet/yabai_<domain>.parquet   one row per record (flattened) per run
  raw/<domain>/index.json          raw snapshot of the published index
  raw/<domain>/relations.json      raw snapshot of claims (if present)
  manifest.json                    catalog/manifest.json shape for the gateway
"""
import datetime
import hashlib
import json
import os
import sys
import urllib.request

import pyarrow as pa
import pyarrow.parquet as pq

OUT = os.environ.get("YABAI_OUT", os.path.expanduser("~/.gftd/yabai-data-catalog"))
BASE = "https://kotoba.cloud/yabai-data"
UA = {"User-Agent": "Mozilla/5.0 (yabai-data-catalog-sync; contact: ops@kotoba.cloud)"}

# each domain -> {list-key: (kind, id-field, name-field)}. "record" CID + provenance
# are read generically when present.
KIND_MAP = {
    "yabai": {
        "sites": "site",
        "signals": "signal",
        "clusters": "cluster",
        "sources": "source",
    },
    "crypto": {
        "sites": "site",
        "signals": "signal",
        "clusters": "cluster",
        "sources": "source",
    },
    "waterplum": {
        "campaigns": "campaign",
        "entities": "entity",
        "aliases": "alias",
        "tactics": "tactic",
        "signals": "signal",
        "sources": "source",
    },
}
DOMAINS = list(KIND_MAP)


def fetch(url, timeout=30):
    req = urllib.request.Request(url, headers=UA)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read()
    except Exception as e:  # noqa: BLE001
        return 0, f"{type(e).__name__}: {e}".encode()


def first(*vals):
    for v in vals:
        if v:
            return v
    return ""


def rows_for(domain, index):
    """Flatten every list-kind into generic (kind,id,name,url,...) rows."""
    observed = index.get("observedAt") or index.get("generatedAt") or ""
    head = index.get("head") or ""
    prov = json.dumps(index.get("provenance", ""), ensure_ascii=False)
    out = []
    for key, kind in KIND_MAP[domain].items():
        for rec in index.get(key, []) or []:
            if not isinstance(rec, dict):
                continue
            rid = first(rec.get("id"), rec.get("domain"), rec.get("alias"))
            name = first(rec.get("name"), rec.get("summary"), rec.get("alias"),
                         rec.get("verdict"), rec.get("entity"))
            url = first(rec.get("url"))
            if kind == "alias":
                url = first(rec.get("url"))
            cid = ""
            rec_ref = rec.get("record")
            if isinstance(rec_ref, dict):
                cid = rec_ref.get("/", "")
            srcs = rec.get("sources") or rec.get("source") or ""
            def s(v):
                return "" if v in (None, "", []) else (json.dumps(v, ensure_ascii=False) if isinstance(v, (dict, list)) else str(v))
            out.append({
                "domain": domain,
                "kind": kind,
                "id": s(rid),
                "name": s(name)[:600],
                "url": s(url),
                "observed_at": s(first(rec.get("observedAt"), rec.get("attributionDate"),
                                    rec.get("firstSeen"), observed)),
                "cid": s(cid),
                "sources_json": json.dumps(srcs, ensure_ascii=False)[:2000],
                "record_json": json.dumps(rec, ensure_ascii=False),
                "index_head": s(head),
                "provenance_json": prov[:4000],
            })
    return out


def main():
    os.makedirs(os.path.join(OUT, "parquet"), exist_ok=True)
    os.makedirs(os.path.join(OUT, "raw"), exist_ok=True)
    schema = pa.schema([
        ("domain", pa.string()), ("kind", pa.string()), ("id", pa.string()),
        ("name", pa.string()), ("url", pa.string()), ("observed_at", pa.string()),
        ("cid", pa.string()), ("sources_json", pa.string()),
        ("record_json", pa.string()), ("index_head", pa.string()),
        ("provenance_json", pa.string()),
    ])
    fetched_at = datetime.datetime.now(datetime.timezone.utc).isoformat()
    per_domain = {}
    ok_all = True
    for domain in DOMAINS:
        status, body = fetch(f"{BASE}/{domain}/index.json")
        rdir = os.path.join(OUT, "raw", domain)
        os.makedirs(rdir, exist_ok=True)
        if status != 200:
            print(f"{domain}: index fetch FAILED status={status} {body[:120]!r}")
            per_domain[domain] = {"rows": 0, "error": f"status={status}"}
            ok_all = False
            continue
        with open(os.path.join(rdir, "index.json"), "wb") as f:
            f.write(body)
        index = json.loads(body)
        # relations.json (claims) — best-effort companion raw snapshot
        rstatus, rbody = fetch(f"{BASE}/{domain}/relations.json")
        if rstatus == 200:
            with open(os.path.join(rdir, "relations.json"), "wb") as f:
                f.write(rbody)
        rows = rows_for(domain, index)
        # row-count floor gate: a published catalog with records but zero
        # flattened rows means a shape change silently nil-keyed us -> fail loud.
        non_empty = sum(1 for key in KIND_MAP[domain] if index.get(key))
        if not rows and non_empty:
            print(f"{domain}: GATE FAIL — index has {non_empty} populated kinds but "
                  f"0 flattened rows (schema drift?)")
            per_domain[domain] = {"rows": 0, "error": "shape_drift_gate"}
            ok_all = False
            continue
        table = pa.Table.from_pylist(rows, schema=schema)
        pf = os.path.join(OUT, "parquet", f"yabai_{domain}.parquet")
        pq.write_table(table, pf, compression="zstd")
        per_domain[domain] = {
            "rows": len(rows),
            "sha256": hashlib.sha256(body).hexdigest(),
            "generatedAt": index.get("generatedAt", ""),
            "head": index.get("head", ""),
            "counts": {k: len(index.get(k, []) or []) for k in KIND_MAP[domain]},
            "provenance": index.get("provenance", "") if isinstance(index.get("provenance"), str) else "",
        }
        print(f"{domain}: {len(rows)} rows; head={str(index.get('head',''))[:24]}")
    manifest = {
        "generatedAt": fetched_at,
        "source": BASE,
        "bucket": "kotoba-open-data",
        "namespace": "yabai",
        "domains": per_domain,
        "total_rows": sum(d.get("rows", 0) for d in per_domain.values()),
    }
    with open(os.path.join(OUT, "manifest.json"), "w") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=1)
    print("COLLECT " + ("OK" if ok_all else "DEGRADED"),
          json.dumps({d: per_domain[d].get("rows") for d in DOMAINS}))
    return 0 if ok_all else 1


if __name__ == "__main__":
    sys.exit(main())
