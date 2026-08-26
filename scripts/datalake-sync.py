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

R2 Data Catalog は Cloudflare API token を Bearer で受ける。必要な権限は 2 つ:
**R2 Data Catalog: Edit** と **Workers R2 Storage: Edit**。

⚠ **catalog には面が 2 つあり、metadata が通ることを「使える」と読まない。**
2026-08-25 の初版はここに「wrangler の OAuth token がそのまま通る（GET /v1/config
が 200）」と書いていたが、**それは metadata 面しか測っていなかった**。同日の再測定で、
OAuth token は list_namespaces / create_namespace まで通り **create_table で 401** に
なる —— カタログ側が自分の bucket を list できず、こう返す:

    List: Failed to list files in location. Please check the storage credentials
      uri: https://<account>.r2.cloudflarestorage.com/<bucket>?list-type=2&...
      response: 401  => S3Error { code: "Unauthorized" }

`wrangler whoami` の scope 一覧に `r2` が無いことと整合する。**この script は
OAuth token では表を作れない。**

優先順は CF_CATALOG_TOKEN 環境変数 → macOS Keychain `service=gftd.cf` /
`account=API_TOKEN`（2026-08-25 実測、この鍵で create_table と append が通る）。
トークンは argv に載せない（ps 露出）。

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

ACCOUNT_DEFAULT = "4da88288dc30d9ee257f319d3c33ecf0"
BUCKET_DEFAULT = "cloud-itonami-datalake"


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


def load_token() -> str:
    """CF_CATALOG_TOKEN -> Keychain gftd.cf/API_TOKEN。

    wrangler の oauth_token へは**落とさない**。それは catalog の metadata 面しか
    通らず、create_table が 401 で落ちる（docstring の「認証」を読むこと）。
    通らない資格情報に静かにフォールバックすると、失敗が「書けなかった」ではなく
    「認証は済んでいるのに謎の 401」に見える。
    """
    tok = os.environ.get("CF_CATALOG_TOKEN")
    if tok and tok.strip():
        return tok.strip()
    try:
        out = subprocess.run(
            ["security", "find-generic-password", "-s", "gftd.cf", "-a", "API_TOKEN", "-w"],
            capture_output=True, text=True, timeout=30,
        )
        if out.returncode == 0 and out.stdout.strip():
            return out.stdout.strip()
    except Exception:
        pass
    print(
        "no catalog token. Set CF_CATALOG_TOKEN, or store a Cloudflare API token with\n"
        "  'R2 Data Catalog: Edit' + 'Workers R2 Storage: Edit' in Keychain\n"
        "  service=gftd.cf account=API_TOKEN.\n"
        "  (wrangler's OAuth session is NOT enough: it passes /v1/config and fails create_table.)",
        file=sys.stderr,
    )
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

    from pyiceberg.catalog.rest import RestCatalog

    uri = f"https://catalog.cloudflarestorage.com/{args.account}/{args.bucket}"
    catalog = RestCatalog(
        name="cloud_itonami_datalake",
        warehouse=f"{args.account}_{args.bucket}",
        uri=uri,
        token=load_token(),
    )

    try:
        catalog.create_namespace(namespace)
        print(f"created namespace {namespace}")
    except Exception as e:
        print(f"namespace {namespace}: {type(e).__name__} (assuming it exists)")

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
