#!/usr/bin/env python3
"""datalake-sync.py — JSON の表を Cloudflare R2 Data Catalog (Apache Iceberg)
に載せる。どの表を載せるかは `--spec` の JSON が決める。

  python3 scripts/datalake-sync.py --spec scripts/datalake-specs/lei.json --in-dir /tmp
  python3 scripts/datalake-sync.py --spec /tmp/watchlist.spec.json --in-dir /tmp

spec は {"namespace": ..., "tables": [{"file", "table", "int_columns"}]}。

⚠ 2026-08-26 に `lei-datalake-sync.py` から改名・一般化した。LEI 専用だった
TABLES は `scripts/datalake-specs/lei.json` に出した —— 2 本目の consumer
(watchlist-screen) が現れたとき、200 行の Iceberg loader を複製するか
一般化するかの二択で、後者を選んだ。**古い名前の呼び出しは残っていない**
（改名前に grep で確認、caller 0 件）。

## この面の位置づけ

**projection であって正本ではない。** どの spec についても、正本は git 管理の
EDN 側にある —— LEI は各 cloud-itonami-lei-<LEI> repo の facts.edn、watchlist は
kotoba-lang/watchlist-screen の resources/watchlist/lists/*.edn。表を全部消しても
export script → この script でそのまま作り直せる。作り直せなくなった時点で
projection ではなく premise になっているので、そうしない
（CLAUDE.md「消して再構築できるか」）。

LEI については同じ正本の別の投影として D1
（`scripts/d1-ingest-cloud-itonami-lei.cljs`）と kotobase
（`scripts/kotobase-ingest-cloud-itonami-lei.cljs`）が既にある。この面は
分析クエリ（DuckDB / Spark / PyIceberg）向け。

## なぜ Python なのか（この workspace の script host は nbb）

Iceberg の commit は Avro manifest / manifest-list / metadata.json / snapshot を
書く作業で、nbb にその writer は無い。**境界は JSON** —— `*-datalake-export.cljs`
が repo の EDN を JSON に落とし、この script はそれを Iceberg に載せるだけで、
EDN も repo 構造も知らない。

## 認証

`datalake_catalog.load_token()` に委譲する（CF_CATALOG_TOKEN -> Keychain
gftd.cf/API_TOKEN、wrangler の OAuth へは落とさない）。理由と実測はそちらの
docstring に 1 箇所だけ置いてある。

## 測れなかったことを clean と書かない

- 行が 0 の表は commit せず、その表だけを失敗として数える。
- commit の後に **読み戻して**行数を数え、入力と一致しなければ exit 1。
  「書いた」と「入っている」を別に測る。
- どの表も commit できなければ exit 2（0 でも 1 でもない）。

- **spec が 0 表なら exit 2。** 表を 1 枚も持たない spec は「全部成功した」ではない。

Run:
  python3 scripts/datalake-sync.py --spec <spec.json> --in-dir /tmp [--dry-run]
"""

import argparse
import json
import os
import pathlib
import subprocess
import sys

from datalake_catalog import ACCOUNT_DEFAULT, BUCKET_DEFAULT, connect, ensure_namespace


def load_spec(path: str):
    """-> (namespace, [(file, table, int_columns), ...]).

    No default spec and no fallback table list. A loader that guesses which
    tables to write would, on a typo in --spec, silently overwrite whichever
    tables it happens to remember -- and `overwrite` on an Iceberg table is a
    new snapshot, not a no-op.
    """
    with open(path, encoding="utf-8") as f:
        spec = json.load(f)
    ns = spec.get("namespace")
    tables = spec.get("tables") or []
    if not ns:
        print(f"{path}: no 'namespace'", file=sys.stderr)
        sys.exit(2)
    if not tables:
        print(f"{path}: no tables -- refusing to report a pass", file=sys.stderr)
        sys.exit(2)
    return ns, [(t["file"], t["table"], set(t.get("int_columns") or [])) for t in tables]


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
    ap.add_argument("--spec", required=True, help="JSON: {namespace, tables:[{file,table,int_columns}]}")
    ap.add_argument("--in-dir", default="/tmp")
    ap.add_argument("--account", default=ACCOUNT_DEFAULT)
    ap.add_argument("--bucket", default=BUCKET_DEFAULT)
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    namespace, tables = load_spec(args.spec)
    print(f"SPEC\t{len(tables)}\ttables\tnamespace={namespace}")

    loaded = []
    for fname, table, int_cols in tables:
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

    catalog = connect(args.account, args.bucket)
    ensure_namespace(catalog, namespace)

    failed = 0
    for table, tbl in loaded:
        ident = f"{namespace}.{table}"
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
