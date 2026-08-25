#!/usr/bin/env python3
"""lei-datalake-sync.py — cloud-itonami の LEI カタログを Cloudflare R2 Data Catalog
(Apache Iceberg) の表として公開する。

## この面の位置づけ

**projection であって正本ではない。** 正本は各 cloud-itonami-lei-<LEI> repo の
facts.edn（git 管理、出典 URL と取得時刻つき、`scripts/verify-facts.cljs` が
live registry に対して再検証できる）。この表を全部消しても
`lei-datalake-export.cljs` → この script でそのまま作り直せる。作り直せなくなった
時点で projection ではなく premise になっているので、そうしない
（CLAUDE.md「消して再構築できるか」）。

同じ catalog の別の投影として D1（`scripts/d1-ingest-cloud-itonami-lei.cljs`）と
kotobase（`scripts/kotobase-ingest-cloud-itonami-lei.cljs`）が既にある。これは
3 本目で、分析クエリ（DuckDB / Spark / PyIceberg）向けの面。

## なぜ Python なのか（この workspace の script host は nbb）

Iceberg の commit は Avro manifest / manifest-list / metadata.json / snapshot を
書く作業で、nbb にその writer は無い。**境界は JSON** ——
`scripts/lei-datalake-export.cljs` が repo の facts.edn を JSON に落とし、この
script はそれを Iceberg に載せるだけで、EDN も repo 構造も知らない。

## 認証

R2 Data Catalog は Cloudflare API token を Bearer で受ける。実測 2026-08-25:
**wrangler の OAuth token がそのまま通る**（GET /v1/config が 200）ので、専用
token を新たに発行していない。優先順は CF_CATALOG_TOKEN 環境変数 →
~/Library/Preferences/.wrangler/config/default.toml の oauth_token。
トークンは argv に載せない（ps 露出）。

## 測れなかったことを clean と書かない

- 行が 0 の表は commit せず、その表だけを失敗として数える。
- commit の後に **読み戻して**行数を数え、入力と一致しなければ exit 1。
  「書いた」と「入っている」を別に測る。
- どの表も commit できなければ exit 2（0 でも 1 でもない）。

Run:
  python3 scripts/lei-datalake-sync.py --in-dir /tmp [--dry-run]
"""

import argparse
import json
import os
import pathlib
import re
import sys

ACCOUNT_DEFAULT = "4da88288dc30d9ee257f319d3c33ecf0"
BUCKET_DEFAULT = "cloud-itonami-datalake"
NAMESPACE = "cloud_itonami"

# (json file, iceberg table, integer columns)
TABLES = [
    ("lei_facts.json", "lei_facts", {"value_index"}),
    ("lei_entity.json", "lei_entity", set()),
]


def load_token() -> str:
    tok = os.environ.get("CF_CATALOG_TOKEN")
    if tok:
        return tok.strip()
    cfg = pathlib.Path.home() / "Library/Preferences/.wrangler/config/default.toml"
    if cfg.exists():
        m = re.search(r'^oauth_token\s*=\s*"([^"]+)"', cfg.read_text(), re.M)
        if m:
            return m.group(1)
    print("no catalog token: set CF_CATALOG_TOKEN, or log in with wrangler", file=sys.stderr)
    sys.exit(2)


def to_arrow(rows, int_cols):
    import pyarrow as pa

    cols = sorted({k for r in rows for k in r.keys()})
    arrays, names = [], []
    for c in cols:
        vals = [r.get(c) for r in rows]
        if c in int_cols:
            arrays.append(pa.array([None if v is None else int(v) for v in vals], pa.int32()))
        else:
            arrays.append(pa.array([None if v is None else str(v) for v in vals], pa.string()))
        names.append(c)
    return pa.Table.from_arrays(arrays, names=names)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--in-dir", default="/tmp")
    ap.add_argument("--account", default=ACCOUNT_DEFAULT)
    ap.add_argument("--bucket", default=BUCKET_DEFAULT)
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    loaded = []
    for fname, table, int_cols in TABLES:
        p = pathlib.Path(args.in_dir) / fname
        if not p.exists():
            print(f"MISSING {p} -- run scripts/lei-datalake-export.cljs first")
            continue
        rows = json.loads(p.read_text())
        print(f"SCANNED\t{len(rows)}\t{table}")
        if not rows:
            print(f"SKIP {table}: 0 rows -- refusing to commit an empty snapshot")
            continue
        loaded.append((table, to_arrow(rows, int_cols)))

    if not loaded:
        print("no table had rows -- refusing to report a pass")
        return 2

    for table, tbl in loaded:
        print(f"arrow: {table} rows={tbl.num_rows} columns={tbl.num_columns}")

    if args.dry_run:
        for table, tbl in loaded:
            print(f"dry-run {table}: " + ", ".join(tbl.schema.names))
        return 0

    from pyiceberg.catalog.rest import RestCatalog

    uri = f"https://catalog.cloudflarestorage.com/{args.account}/{args.bucket}"
    catalog = RestCatalog(
        name="cloud_itonami_datalake",
        warehouse=f"{args.account}_{args.bucket}",
        uri=uri,
        token=load_token(),
    )

    try:
        catalog.create_namespace(NAMESPACE)
        print(f"created namespace {NAMESPACE}")
    except Exception as e:
        print(f"namespace {NAMESPACE}: {type(e).__name__} (assuming it exists)")

    failed = 0
    for table, tbl in loaded:
        ident = f"{NAMESPACE}.{table}"
        try:
            t = catalog.load_table(ident)
            print(f"{ident}: loaded")
        except Exception:
            t = catalog.create_table(ident, schema=tbl.schema)
            print(f"{ident}: created")

        # 全量入れ替え。Iceberg は snapshot を残すので前回の中身は time-travel で読める。
        t.overwrite(tbl)
        back = catalog.load_table(ident).scan().to_arrow()
        snap = catalog.load_table(ident).current_snapshot()
        print(f"{ident}: committed  read-back-rows={back.num_rows}"
              + (f"  snapshot={snap.snapshot_id}" if snap else ""))
        if back.num_rows != tbl.num_rows:
            print(f"{ident}: MISMATCH wrote {tbl.num_rows} read back {back.num_rows}")
            failed += 1

    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
