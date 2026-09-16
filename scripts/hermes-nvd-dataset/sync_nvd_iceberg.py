#!/usr/bin/env python3
"""Sync the per-year NVD Parquet files into an Iceberg table
security.nvd_cve in internal-security-nvd (R2 Data Catalog), so the full
~393k CVE history is queryable with wrangler r2 sql / any Iceberg engine.

Full overwrite each sync (the whole table is a few hundred MB; the sales
catalog precedent does the same). Token: CF_CATALOG_TOKEN env else Keychain
gftd.cf/API_TOKEN (same resolution as hermes-sales-catalog).
"""
import json
import os
import subprocess
import sys

import pyarrow as pa
import pyarrow.parquet as pq
from pyiceberg.catalog.rest import RestCatalog

ACCOUNT = "4da88288dc30d9ee257f319d3c33ecf0"
BUCKET = "internal-security-nvd"
NAMESPACE = "security"
TABLE = "nvd_cve"
OUT = os.environ.get("NVD_OUT", os.path.expanduser("~/nvd-dataset"))
PARQUET_DIR = os.path.join(OUT, "parquet")


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
        name="internal_nvd_catalog",
        warehouse=f"{ACCOUNT}_{BUCKET}",
        uri=f"https://catalog.cloudflarestorage.com/{ACCOUNT}/{BUCKET}",
        token=load_token(),
    )


def main() -> int:
    files = sorted(f for f in os.listdir(PARQUET_DIR) if f.endswith(".parquet"))
    if not files:
        print("no parquet files built yet", file=sys.stderr)
        return 1
    tables = [pq.read_table(os.path.join(PARQUET_DIR, f)) for f in files]
    schema = tables[0].schema
    total = sum(t.num_rows for t in tables)
    table = pa.concat_tables([t.cast(schema) for t in tables])
    print(f"concat {len(files)} year files = {total} rows")

    cat = connect()
    try:
        cat.create_namespace(NAMESPACE)
        print(f"created namespace {NAMESPACE}")
    except Exception as e:
        print(f"namespace {NAMESPACE}: {type(e).__name__} (assuming it exists)")
    ident = f"{NAMESPACE}.{TABLE}"
    try:
        t = cat.load_table(ident)
    except Exception:
        t = cat.create_table(ident, schema=table.schema)
    t.overwrite(table)
    back = cat.load_table(ident).scan().to_arrow().num_rows
    print(f"{ident}: read-back {back} rows (wrote {table.num_rows})")
    if back != table.num_rows:
        raise SystemExit("read-back mismatch")
    print("ICEBERG SYNC OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
