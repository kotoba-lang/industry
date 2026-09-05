#!/usr/bin/env python3
"""adnetwork-scout の広告主観測を R2 Data Catalog (Iceberg) に同期する。

ses-mail-datalake-sync.py と同じ契約: 明示 pyarrow schema、stable record_id
の upsert（後勝ち）、未知フィールド拒否、書き込み後 readback。

Input JSON:
  {
    "observations": [
      {
        "network":        "exoclick",            # 広告ネットワーク識別子
        "advertiser_key": "brand.example.com",   # stable key (ドメイン等)
        "advertiser_name": "Example Brand",      # nullable
        "evidence_url":   "https://...",         # 観測の一次出所
        "observed_at":    "2026-09-05T09:00:00+09:00",
        "kind":           "advertiser-page",     # 観測種別
        "notes":          null,                  # nullable
        "raw_json":       null                   # 観測 raw (nullable)
      }
    ]
  }

Run:
  python3 scripts/adnetwork_datalake_sync.py --input /tmp/adnetwork-obs.json
  python3 scripts/adnetwork_datalake_sync.py --input ... --dry-run
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys

import pyarrow as pa

from datalake_catalog import connect, ensure_namespace

NAMESPACE = "cloud_itonami"
TABLE = "adnetwork_advertiser"

STR = pa.string()

SCHEMA = pa.schema([
    ("record_id", STR),
    ("network", STR),
    ("advertiser_key", STR),
    ("advertiser_name", STR),
    ("evidence_url", STR),
    ("observed_at", STR),
    ("kind", STR),
    ("notes", STR),
    ("raw_json", STR),
    ("ingested_at", STR),
])

ALLOWED = set(SCHEMA.names)


def record_id(obs: dict) -> str:
    """network + advertiser_key + kind + evidence_url の stable hash。"""
    basis = "|".join([
        obs.get("network") or "",
        obs.get("advertiser_key") or "",
        obs.get("kind") or "",
        obs.get("evidence_url") or "",
    ])
    return hashlib.sha256(basis.encode()).hexdigest()


def rows_of(payload: dict, now: str) -> list[dict]:
    out = []
    for obs in payload.get("observations", []):
        unknown = set(obs) - ALLOWED
        if unknown:
            raise ValueError(f"unknown fields: {sorted(unknown)}")
        for req in ("network", "advertiser_key", "evidence_url", "observed_at"):
            if not obs.get(req):
                raise ValueError(f"missing required field: {req}")
        out.append({
            "record_id": record_id(obs),
            "network": obs["network"],
            "advertiser_key": obs["advertiser_key"],
            "advertiser_name": obs.get("advertiser_name"),
            "evidence_url": obs["evidence_url"],
            "observed_at": obs["observed_at"],
            "kind": obs.get("kind"),
            "notes": obs.get("notes"),
            "raw_json": obs.get("raw_json"),
            "ingested_at": now,
        })
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", required=True)
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    import datetime as dt
    now = dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")

    with open(args.input) as f:
        payload = json.load(f)
    rows = rows_of(payload, now)
    print(f"rows\t{len(rows)}")
    if args.dry_run:
        return 0
    if not rows:
        print("0 rows — not writing (0 件は成功として報告しない)")
        return 1

    import pyarrow as pa
    table = pa.Table.from_pylist(rows, schema=SCHEMA)

    cat = connect()
    ensure_namespace(cat, NAMESPACE)
    ident = (NAMESPACE, TABLE)
    try:
        t = cat.load_table(ident)
        print("table\texists")
    except Exception:
        t = cat.create_table(ident, schema=SCHEMA)
        print("table\tcreated")

    # upsert: 既存 record_id は置換（後勝ち）
    import pyarrow.compute as pc
    existing = t.scan().to_arrow()
    old_ids = set(existing.column("record_id").to_pylist()) if existing.num_rows else set()
    new_ids = {r["record_id"] for r in rows}
    overwritten = len(old_ids & new_ids)
    appended = len(new_ids - old_ids)

    # upsert: 既存 record_id は置換（後勝ち）。delete_filter は
    # pyiceberg expression（In）で渡す — 文字列 SQL を渡すと
    # NotImplementedError で落ちる（実測 2026-09-05）。
    from pyiceberg.expressions import In
    new_ids = {r["record_id"] for r in rows}
    overwritten = 0
    if existing.num_rows:
        old_id_list = existing.column("record_id").to_pylist()
        overwritten = len(set(old_id_list) & new_ids)
        if overwritten:
            t.delete(delete_filter=In(term="record_id", values=list(new_ids)))
    t.append(table)

    # readback
    rb = cat.load_table(ident).scan().to_arrow()
    missing = [r["record_id"] for r in rows
               if r["record_id"] not in set(rb.column("record_id").to_pylist())]
    print(f"commit\tappended={appended}\toverwritten={overwritten}")
    print(f"readback\ttotal={rb.num_rows}\tmissing={len(missing)}")
    if missing:
        print("MISSING\t" + ",".join(missing[:20]))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
