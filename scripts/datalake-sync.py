#!/usr/bin/env python3
"""datalake-sync.py — JSON の表を Cloudflare R2 Data Catalog (Apache Iceberg)
に載せる。どの表を載せるかは `--spec` の JSON が決める。

  python3 scripts/datalake-sync.py --spec scripts/datalake-specs/lei.json --in-dir /tmp
  python3 scripts/datalake-sync.py --spec /tmp/watchlist.spec.json --in-dir /tmp

spec は {"namespace": ..., "tables": [{"file", "table", "int_columns"}]}。
各 table entry は以下も任意で持てる（無ければ従来どおりの挙動 -- 既存の LEI /
watchlist / hyakka spec は 1 バイトも変えなくてよい）:

  "mode"              "overwrite"（既定）| "append" | "upsert"。どちらも表が
                      既に在る時だけ効く（表がまだ無い初回は、指定に関わらず
                      create_table + overwrite で作る -- 無い表には
                      append/upsert できない）。**append/upsert を選べるのは、
                      入力ファイルが「今回新しく分かった行だけ」（前回 sync
                      以降の差分）を持つ場合だけ**。呼び出し側が累積表全体を
                      毎回渡すなら overwrite のまま（append/upsert すると
                      同じ行が重複/再照合コミットされる）。
                      **append と upsert の違いは再送耐性**: この script は
                      commit の成否を呼び出し元（cljs 側）に返すだけで、
                      「Iceberg には書けたが呼び出し元がそれを記録する前に
                      落ちた」場合、次回同じ差分がもう一度送られてくる。
                      append はそれを**そのまま重複行として追加する**。
                      upsert は `join_columns` で一致した行を上書きするだけ
                      なので、同じ差分の再送は no-op になる（実測: retry で
                      rows_updated=0 rows_inserted=0）。**新しく `mode` を
                      選ぶときは append ではなく upsert を既定にする** ——
                      append が正当化されるのは、そもそも重複が起きても
                      安全な追記専用ログの場合だけ。
  "join_columns"      upsert 専用・必須。この列の値が一致する既存行を更新、
                      無ければ挿入する（`t.upsert(tbl, join_cols=join_columns)`）。
  "partition_by"      列名のリスト（identity transform）。表が無ければ作成時に、
                      在ってまだその列で分割されていなければ `update_spec()` で
                      追加する -- 既存ファイルの書き直しは発生しない
                      （Iceberg のpartition evolution。過去分は旧spec のまま、
                      以降のcommitだけ新specで書かれる）。
  "float_columns"     列名のリスト。`float64` 型で持つ（`int_columns` と同じ形の
                      姉妹オプション）。**int で足りるなら int_columns を使う** ——
                      float は等値比較が効かないので、識別子や件数を入れる列では
                      ない。これが要るのは confidence や rate のように、値そのものが
                      小数である列だけ。無指定なら従来どおり string になる（黙って
                      0 や nan にはしない）。
  "timestamp_columns"  列名のリスト。ISO8601 文字列を `timestamp(us, UTC)` 型で
                      持つ（`int_columns` と同じ形の姉妹オプション）。**新規に
                      作る表にだけ効く** -- 既存表の列型を string → timestamp へ
                      変えるのは Iceberg の型昇格が対応しない向きなので、この
                      script は既存列の型を変更しない（そうしたければ表を
                      作り直す判断が要る。この script は黙ってはやらない）。

snapshot は毎回の commit 後に `--snapshot-retention-days`（既定 7）より古い
ものを自動で expire する（`t.maintenance.expire_snapshots()`）。全 spec 共通 --
overwrite を毎時走らせる呼び出し元ほど snapshot が積み上がるので、個別の
opt-in を要求せず既定で効かせる。`--no-expire-snapshots` で無効化できる。

⚠ **古い loader に新しい spec を渡すと、`mode` は黙って無視され全量 overwrite に
なる。** 実測 2026-08-29: catch-up 実行中に superproject の checkout を
`git checkout --` で一時的に旧版へ戻してしまい、次の chunk が `mode: upsert` の
つもりで **1,377 万行を消して 36 万行で置き換えた**（Iceberg の snapshot から
rollback して復旧）。呼び出し側は `--require-incremental-support` を渡すこと ——
旧 loader は argparse が `unrecognized arguments` で exit 2 にするので、
**破壊的 overwrite ではなく失敗として現れる**。

commit 後の read-back 検証（`row_count`）は Iceberg snapshot summary の
`total-records` を読むだけで、`.scan().to_arrow()` によるテーブル全体の
再ダウンロードはしない（測定: dns_resolution の 2300万行超で、この
read-back 自体が commit そのものより重かった）。`total-records` は
overwrite/append/upsert のどれでも実測で正確——無い場合だけ従来の
scan にフォールバックする（検査を静かに弱めない）。

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
（`scripts/d1-ingest-cloud-itonami-lei.cljk`）と kotobase
（`scripts/kotobase-ingest-cloud-itonami-lei.cljk`）が既にある。この面は
分析クエリ（DuckDB / Spark / PyIceberg）向け。

## なぜ Python なのか（この workspace の script host は nbb）

**境界は JSON** —— `*-datalake-export.cljs` が repo の EDN を JSON に落とし、
この script はそれを Iceberg に載せるだけで、EDN も repo 構造も知らない。

⚠ **この節は長く「nbb に Iceberg writer は無い」と書いていた。それは大雑把すぎて、
実際より Python 側を広く正当化していた**（2026-08-29 に実測して訂正）。Iceberg の
commit が要求するものを 1 つずつ見ると、欠けているのは 1 つだけ:

| commit に要るもの | portable `.cljc` に在るか |
|---|---|
| data file (Parquet) | **在る** —— `kotoba-lang/org-apache-parquet` は reader **と writer**（`parquet/write.cljc`） |
| metadata.json / snapshot | 在る（ただの JSON） |
| REST catalog protocol | 在る（ただの HTTP） |
| **manifest / manifest-list (Avro)** | **在る**（2026-08-29〜） —— `kotoba-lang/org-apache-avro` に writer を実装した（`avro.file/write`。fastavro が読めることを実測、`test/fixtures/verify_written.py`） |

**つまり 4 つとも揃っており、この script を nbb へ移せない技術的理由はもう無い。**
2026-08-29 より前はここに「Avro writer が無い」と書いてあり、それが唯一の理由だった。
移植そのものはまだやっていない —— **これは「移植は不要」ではなく「未着手」である。**
着手するときの形は今と同じで、境界の JSON はそのまま、`pyiceberg` がやっている
manifest/metadata/snapshot の組み立てを `avro.file/write` + `org-apache-parquet` +
素の HTTP で書き直すことになる。

**kotoba（`.kotoba`）は今日は対象外。** script host `kbb` は west に 0 件（未実装、
CLAUDE.md の記述どおり）で、capability kit にも **fs / process / exec は無い**
（在るのは clock / dataspace / http / http-ingress / llm / log / state / storage /
stream-ingress / stream-object / ui。実測 2026-08-29）。運用 script の正本は
引き続き nbb であり、kbb の存在を前提にしたコードは書かない。

なお `kotoba-lang/tana`（棚）は columnar object 群の上に content-addressed な
table plane を置くもので、**Iceberg とは別解**。この面を Iceberg のままにするか
tana に寄せるかは、この loader の言語選択とは独立した設計判断。

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
from datetime import datetime, timedelta, timezone

from datalake_catalog import ACCOUNT_DEFAULT, BUCKET_DEFAULT, connect, ensure_namespace


def load_spec(path: str):
    """-> (namespace, [table-spec dict, ...]).

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
    out = []
    for t in tables:
        mode = t.get("mode") or "overwrite"
        if mode not in ("overwrite", "append", "upsert"):
            print(f"{path}: table {t.get('table')!r} has unknown mode {mode!r}", file=sys.stderr)
            sys.exit(2)
        join_columns = list(t.get("join_columns") or [])
        if mode == "upsert" and not join_columns:
            print(f"{path}: table {t.get('table')!r} has mode upsert but no join_columns",
                  file=sys.stderr)
            sys.exit(2)
        out.append({
            "file": t["file"],
            "table": t["table"],
            "int_columns": set(t.get("int_columns") or []),
            "float_columns": set(t.get("float_columns") or []),
            "timestamp_columns": set(t.get("timestamp_columns") or []),
            "mode": mode,
            "partition_by": list(t.get("partition_by") or []),
            "join_columns": join_columns,
        })
    return ns, out


def to_arrow(rows, int_cols, timestamp_cols=frozenset(), float_cols=frozenset()):
    import pyarrow as pa

    cols = sorted({k for r in rows for k in r.keys()})
    arrays, names = [], []
    for c in cols:
        vals = [r.get(c) for r in rows]
        if c in int_cols:
            arrays.append(pa.array([None if v is None else int(v) for v in vals], pa.int32()))
        elif c in float_cols:
            arrays.append(pa.array([None if v is None else float(v) for v in vals], pa.float64()))
        elif c in timestamp_cols:
            parsed = []
            for v in vals:
                if v is None or v == "":
                    parsed.append(None)
                else:
                    # ISO8601 with a trailing "Z" -- Python's fromisoformat only
                    # accepted "+00:00" before 3.11, so normalize explicitly
                    # rather than assume the running interpreter's version.
                    parsed.append(datetime.fromisoformat(str(v).replace("Z", "+00:00")))
            arrays.append(pa.array(parsed, pa.timestamp("us", tz="UTC")))
        else:
            arrays.append(pa.array([None if v is None else str(v) for v in vals], pa.string()))
        names.append(c)
    return pa.Table.from_arrays(arrays, names=names)


def ensure_partitioned(catalog, ident, t, partition_by):
    """Add any requested identity-partition columns the table doesn't have yet.

    Non-destructive: Iceberg partition evolution only changes how FUTURE
    commits are organized. Files already written under the old (or no)
    partition spec are untouched and stay readable. Returns the (possibly
    reloaded) table.
    """
    if not partition_by:
        return t
    existing = {f.name for f in t.spec().fields}
    missing = [c for c in partition_by if c not in existing]
    if not missing:
        return t
    with t.update_spec() as us:
        for c in missing:
            us.add_identity(c)
    t = catalog.load_table(ident)
    print(f"{ident}: partitioned by {sorted(existing | set(missing))} (added {missing})")
    return t


def row_count(t):
    """Row count without reading a single data file.

    Iceberg's snapshot summary already carries `total-records` (verified
    live against overwrite/append/upsert -- all three keep it exact). The
    obvious way to verify a commit -- `t.scan().to_arrow().num_rows` --
    re-downloads and re-materializes the ENTIRE table just to count rows,
    on every commit, forever. At 23M+ rows that read-back was itself a
    bigger cost than the commit it was checking. Falls back to the
    expensive scan only if a table somehow has no snapshot summary (e.g.
    very old metadata) -- the correctness check this backs must never go
    silent, only cheap. Takes an already-loaded Table (not catalog+ident)
    so a caller that needs the snapshot too doesn't pay for two loads.
    """
    snap = t.current_snapshot()
    if snap is not None and snap.summary is not None and "total-records" in snap.summary:
        return int(snap.summary["total-records"])
    return t.scan().to_arrow().num_rows


def expire_old_snapshots(catalog, ident, retention_days):
    """Best-effort: drop unprotected snapshots older than retention_days.

    Applied uniformly (every table, every run) rather than as an opt-in --
    an analytics projection never needs more than a retention_days-long
    time-travel window, since the real source of truth lives in the git
    ledger this table was exported from, not in Iceberg snapshot history.
    A caller that syncs hourly via overwrite/append accumulates one
    snapshot per run indefinitely without this.
    """
    if retention_days is None:
        return
    t = catalog.load_table(ident)
    before = len(list(t.snapshots()))
    cutoff = datetime.now(timezone.utc) - timedelta(days=retention_days)
    t.maintenance.expire_snapshots().older_than(cutoff).commit()
    after = len(list(catalog.load_table(ident).snapshots()))
    if after != before:
        print(f"{ident}: expired {before - after} snapshot(s) older than {retention_days}d "
              f"({before} -> {after})")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--spec", required=True,
                    help="JSON: {namespace, tables:[{file,table,int_columns,float_columns,timestamp_columns,mode,partition_by}]}")
    ap.add_argument("--in-dir", default="/tmp")
    ap.add_argument("--account", default=ACCOUNT_DEFAULT)
    ap.add_argument("--bucket", default=BUCKET_DEFAULT)
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--evolve-schema", action="store_true",
                    help="入力に既存表より多い列があるとき、表の schema を広げてから "
                         "書く（Iceberg の union_by_name）。**列が減る向きには効かない** "
                         "-- それは refuse する（下記）")
    ap.add_argument("--snapshot-retention-days", type=int, default=7,
                    help="commit 後、この日数より古い snapshot を expire する（既定 7）")
    ap.add_argument("--no-expire-snapshots", action="store_true",
                    help="snapshot expire を今回だけ止める")
    # VERSION HANDSHAKE -- see the "stale loader" note in the module docstring.
    # Callers whose spec relies on `mode` (append/upsert) MUST pass this. An
    # older copy of this script has no such option, so argparse rejects it and
    # exits 2 instead of silently ignoring `mode` and doing a full overwrite.
    ap.add_argument("--require-incremental-support", action="store_true",
                    help="この loader が mode(append/upsert) を解釈できることを"
                         "呼び出し側が要求する。古い loader は argparse が"
                         "unrecognized arguments で exit 2 にするので、"
                         "**黙って overwrite に落ちる事故が構造的に起きない**")
    args = ap.parse_args()
    retention_days = None if args.no_expire_snapshots else args.snapshot_retention_days

    namespace, tables = load_spec(args.spec)
    print(f"SPEC\t{len(tables)}\ttables\tnamespace={namespace}")

    # The other half of the handshake: a spec that asks for incremental
    # behaviour from a caller that did not assert it understands the
    # contract is a caller/loader mismatch, not a request to fall back to
    # overwrite. Fail closed -- falling back is exactly the 13.4M-row
    # deletion this guard exists to prevent.
    incremental = sorted({t["table"] for t in tables if t["mode"] != "overwrite"})
    if incremental and not args.require_incremental_support:
        print(f"REFUSING: spec asks for non-overwrite mode on {incremental} but the caller "
              "did not pass --require-incremental-support. Pass it (and make sure every "
              "caller of this loader is the version that supports it).", file=sys.stderr)
        return 2

    loaded = []
    for spec in tables:
        table = spec["table"]
        p = pathlib.Path(args.in_dir) / spec["file"]
        if not p.exists():
            print(f"MISSING {p} -- run the export step first")
            continue
        rows = json.loads(p.read_text())
        print(f"SCANNED\t{len(rows)}\t{table}")
        if not rows:
            print(f"SKIP {table}: 0 rows -- refusing to commit an empty snapshot")
            continue
        loaded.append((spec, to_arrow(rows, spec["int_columns"], spec["timestamp_columns"],
                                      spec["float_columns"])))

    if not loaded:
        print("no table had rows -- refusing to report a pass")
        return 2

    for spec, tbl in loaded:
        print(f"arrow: {spec['table']} rows={tbl.num_rows} columns={tbl.num_columns} mode={spec['mode']}")

    if args.dry_run:
        for spec, tbl in loaded:
            print(f"dry-run {spec['table']}: " + ", ".join(tbl.schema.names))
        return 0

    catalog = connect(args.account, args.bucket)
    ensure_namespace(catalog, namespace)

    failed = 0
    for spec, tbl in loaded:
        table, mode, partition_by = spec["table"], spec["mode"], spec["partition_by"]
        join_columns = spec["join_columns"]
        ident = f"{namespace}.{table}"
        try:
            t = catalog.load_table(ident)
            print(f"{ident}: loaded")
        except Exception:
            # First-time bootstrap: `mode: append` cannot append to a table
            # that doesn't exist yet, so the first commit is always a plain
            # overwrite regardless of the spec's mode.
            t = catalog.create_table(ident, schema=tbl.schema)
            print(f"{ident}: created")
            t = ensure_partitioned(catalog, ident, t, partition_by)
            t.overwrite(tbl)
            back_rows = row_count(catalog.load_table(ident))
            print(f"{ident}: committed  read-back-rows={back_rows}")
            if back_rows != tbl.num_rows:
                print(f"{ident}: MISMATCH wrote {tbl.num_rows} read back {back_rows}")
                failed += 1
            else:
                expire_old_snapshots(catalog, ident, retention_days)
            continue

        t = ensure_partitioned(catalog, ident, t, partition_by)

        # 列の増減は 2 つの別の話で、危険さが違う。
        #
        #   増えた   新しい列が足された。既存行はその列を null で持つ -- 正しい
        #            （その行が書かれた時点でその値は存在しなかった）。
        #   減った   入力がその列を表現できなかった。null 埋めすると
        #            「値が無かった」として読めてしまう -- refuse する。
        #
        # 増えた側も既定では通さない: --evolve-schema を明示したときだけ広げる。
        # 黙って広がる loader は、typo で足された列を本物の列として固定する。
        existing = set(t.schema().column_names)
        incoming = set(tbl.schema.names)
        if incoming - existing:
            if not args.evolve_schema:
                print(f"{ident}: REFUSING -- input has columns the table does not: "
                      f"{sorted(incoming - existing)}. Pass --evolve-schema if that is intended.")
                failed += 1
                continue
            with t.update_schema() as us:
                us.union_by_name(tbl.schema)
            t = catalog.load_table(ident)
            print(f"{ident}: schema widened by {sorted(incoming - existing)}")
        if existing - incoming:
            print(f"{ident}: REFUSING -- table has columns the input does not: "
                  f"{sorted(existing - incoming)}. Null-filling them would record "
                  "'no value' where the truth is 'not representable'.")
            failed += 1
            continue

        before = row_count(t)
        detail = ""
        if mode == "upsert":
            # Caller's contract: tbl holds ONLY rows new/changed since the
            # last successful sync. Idempotent under retry -- if this
            # script's exit code never made it back to the caller (crash
            # between Iceberg commit and the caller recording success), the
            # SAME delta sent again matches by join_columns and updates in
            # place instead of duplicating (measured: a same-delta retry
            # yields rows_updated=0 rows_inserted=0).
            result = t.upsert(tbl, join_cols=join_columns)
            expected = before + result.rows_inserted
            detail = f"  updated={result.rows_updated} inserted={result.rows_inserted}"
        elif mode == "append":
            # Caller's contract: tbl holds ONLY rows new since the last
            # successful sync. Appending the full accumulator here would
            # silently duplicate every already-committed row. Unlike
            # upsert, a retried append DOES duplicate -- only use this mode
            # for a payload where duplicate rows are harmless (e.g. a raw
            # event/observation log where the same fact appearing twice is
            # not a correctness problem).
            t.append(tbl)
            expected = before + tbl.num_rows
        else:
            # 全量入れ替え。Iceberg は snapshot を残すので前回の中身は time-travel で読める。
            t.overwrite(tbl)
            expected = tbl.num_rows
        t = catalog.load_table(ident)
        back_rows = row_count(t)
        snap = t.current_snapshot()
        print(f"{ident}: committed ({mode})  read-back-rows={back_rows}"
              + detail + (f"  snapshot={snap.snapshot_id}" if snap else ""))
        if back_rows != expected:
            print(f"{ident}: MISMATCH expected {expected} read back {back_rows}")
            failed += 1
        else:
            expire_old_snapshots(catalog, ident, retention_days)

    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
