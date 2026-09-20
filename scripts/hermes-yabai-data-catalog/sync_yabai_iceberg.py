#!/usr/bin/env python3
"""Sync the three yabai-data per-domain Parquet files into Iceberg tables
yabai.sites / yabai.signals / ... — no: one table per domain
(yabai.yabai, yabai.crypto, yabai.waterplum) in kotoba-open-data (R2 Data
Catalog), so the published YABAI / crypto-fraud / WaterPlum observations are
queryable with wrangler r2 sql or any Iceberg engine (same pattern as
hermes-osint-catalog's osint.chain/ip/appstore).

Full overwrite each sync (the tables are tiny; read-back verified).
Token: CF_CATALOG_TOKEN env else Keychain gftd.cf/API_TOKEN.
"""
import os
import subprocess
import sys

import pyarrow.parquet as pq
from pyiceberg.catalog.rest import RestCatalog

ACCOUNT = "4da88288dc30d9ee257f319d3c33ecf0"
BUCKET = "kotoba-open-data"
NAMESPACE = "yabai"
OUT = os.environ.get("YABAI_OUT", os.path.expanduser("~/.gftd/yabai-data-catalog"))
PARQUET_DIR = os.path.join(OUT, "parquet")
DOMAINS = {"yabai": "yabai_sites", "crypto": "crypto_fraud", "waterplum": "waterplum_actors"}


def load_token() -> str:
    tok = os.environ.get("CF_CATALOG_TOKEN")
    if tok and tok.strip():
        return tok.strip()
    out = subprocess.run(
        ["security", "find-generic-password", "-s", "gftd.cf", "-a", "API_TOKEN", "-w"],
        capture_output=True, text=True, timeout=30,
    )
    if out.returncode == 0 and out.stdout.strip():
        return out.stdout.strip()
    print("no catalog token", file=sys.stderr)
    sys.exit(2)


def connect() -> RestCatalog:
    return RestCatalog(
        name="kotoba_open_data_catalog",
        warehouse=f"{ACCOUNT}_{BUCKET}",
        uri=f"https://catalog.cloudflarestorage.com/{ACCOUNT}/{BUCKET}",
        token=load_token(),
    )


def main() -> int:
    files = sorted(f for f in os.listdir(PARQUET_DIR) if f.endswith(".parquet"))
    if not files:
        print("no parquet files built yet", file=sys.stderr)
        return 1
    cat = connect()
    try:
        cat.create_namespace(NAMESPACE)
        print(f"created namespace {NAMESPACE}")
    except Exception as e:
        print(f"namespace {NAMESPACE}: {type(e).__name__} (assuming it exists)")
    total = 0
    for f in files:
        table = pq.read_table(os.path.join(PARQUET_DIR, f))
        domain = f.removeprefix("yabai_").removesuffix(".parquet")
        if domain not in DOMAINS:
            print(f"skip unknown domain file {f}")
            continue
        ident = f"{NAMESPACE}.{DOMAINS[domain]}"
        try:
            t = cat.load_table(ident)
            # field-ids legitimately differ between the parquet round-trip and
            # the persisted Iceberg schema, so only names+types must match.
            got = [(f.name, str(f.field_type)) for f in t.schema().fields]
            want = [(f.name, str(f.type)) for f in table.schema]
            if got != want:
                raise SystemExit(f"{ident}: schema drift (names/types) — refusing to overwrite")
        except SystemExit:
            raise
        except Exception:
            t = cat.create_table(ident, schema=table.schema)
        t.overwrite(table)
        back = cat.load_table(ident).scan().to_arrow().num_rows
        print(f"{ident}: read-back {back} rows (wrote {table.num_rows})")
        if back != table.num_rows:
            raise SystemExit(f"{ident}: read-back mismatch")
        total += back
    print(f"ICEBERG SYNC OK total={total}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
