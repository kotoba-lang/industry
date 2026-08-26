#!/usr/bin/env python3
"""SES / 人材紹介メールの構造化 JSON を R2 Data Catalog に同期する。

メールボックスは正本で、この Iceberg 表は分析用 projection。本文、電話番号、署名欄、
私用メールアドレスは保存しない。入力は Codex のメール connector が作る一時 JSON で、
同じ record_id は後勝ちで upsert する。

Input:
  {
    "generated_at": "2026-08-26T10:00:00+09:00",
    "messages": [...],
    "opportunities": [...],
    "candidates": [...]
  }

Run:
  python3 scripts/ses-mail-datalake-sync.py --input /tmp/cloud-itonami-ses-mail.json
  python3 scripts/ses-mail-datalake-sync.py --input ... --dry-run
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import pathlib
import subprocess
import sys
import time
from typing import Any, Iterable

import pyarrow as pa


from datalake_catalog import ACCOUNT_DEFAULT, BUCKET_DEFAULT, connect, ensure_namespace

NAMESPACE = "cloud_itonami_private"

STR = pa.string()
I64 = pa.int64()
STRS = pa.list_(STR)

SCHEMAS: dict[str, pa.Schema] = {
    "ses_mail": pa.schema([
        ("record_id", STR), ("source", STR), ("message_id", STR),
        ("thread_id", STR), ("received_at", STR), ("sender_address", STR),
        ("sender_name", STR), ("recipients", STRS), ("subject", STR),
        ("category", STR), ("provider", STR), ("source_url", STR),
        ("body_sha256", STR), ("summary", STR), ("ingested_at", STR),
    ]),
    "ses_opportunity": pa.schema([
        ("record_id", STR), ("source", STR), ("message_id", STR),
        ("provider", STR), ("project_name", STR), ("industry", STR),
        ("start", STR), ("location", STR), ("remote", STR),
        ("work_hours", STR), ("contract", STR), ("commercial_chain", STR),
        ("rate_min_jpy", I64), ("rate_max_jpy", I64), ("settlement", STR),
        ("headcount", I64), ("age_limit", STR), ("nationality", STR),
        ("required_skills", STRS), ("preferred_skills", STRS),
        ("notes", STR), ("source_url", STR), ("ingested_at", STR),
    ]),
    "ses_candidate": pa.schema([
        ("record_id", STR), ("source", STR), ("message_id", STR),
        ("provider", STR), ("candidate_alias", STR), ("age", I64),
        ("gender", STR), ("employment", STR), ("nearest_station", STR),
        ("available_from", STR), ("rate_min_jpy", I64),
        ("rate_max_jpy", I64), ("years_experience", STR),
        ("skills", STRS), ("workstyle", STR), ("nationality", STR),
        ("notes", STR), ("source_url", STR), ("ingested_at", STR),
    ]),
}

INPUT_KEYS = {
    "ses_mail": "messages",
    "ses_opportunity": "opportunities",
    "ses_candidate": "candidates",
}


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat().replace("+00:00", "Z")


def _clean_scalar(value: Any, typ: pa.DataType) -> Any:
    if value is None or value == "":
        return None
    if pa.types.is_int64(typ):
        if isinstance(value, bool):
            raise ValueError("boolean is not an integer field")
        return int(value)
    if pa.types.is_list(typ):
        if not isinstance(value, list):
            raise ValueError("list field must be a JSON array")
        return [str(item).strip() for item in value if str(item).strip()]
    return str(value).strip()


def normalize_rows(rows: Any, schema: pa.Schema, ingested_at: str) -> list[dict[str, Any]]:
    if rows is None:
        return []
    if not isinstance(rows, list):
        raise ValueError("table input must be a JSON array")
    normalized: list[dict[str, Any]] = []
    allowed = set(schema.names)
    for index, row in enumerate(rows):
        if not isinstance(row, dict):
            raise ValueError(f"row {index} is not an object")
        unknown = set(row) - allowed
        if unknown:
            raise ValueError(f"row {index} has unknown fields: {sorted(unknown)}")
        record_id = str(row.get("record_id") or "").strip()
        source = str(row.get("source") or "").strip()
        message_id = str(row.get("message_id") or "").strip()
        if not record_id or not source or not message_id:
            raise ValueError(f"row {index} requires record_id, source, and message_id")
        out: dict[str, Any] = {}
        for field in schema:
            value = ingested_at if field.name == "ingested_at" else row.get(field.name)
            out[field.name] = _clean_scalar(value, field.type)
        normalized.append(out)
    return normalized


def dedupe_rows(rows: Iterable[dict[str, Any]]) -> list[dict[str, Any]]:
    by_id: dict[str, dict[str, Any]] = {}
    for row in rows:
        by_id[row["record_id"]] = row
    return [by_id[key] for key in sorted(by_id)]


def arrow(rows: list[dict[str, Any]], schema: pa.Schema) -> pa.Table:
    return pa.Table.from_pylist(rows, schema=schema)


def load_or_create(catalog, table_name: str, schema: pa.Schema):
    from pyiceberg.exceptions import NoSuchTableError

    ident = f"{NAMESPACE}.{table_name}"
    try:
        return catalog.load_table(ident), False
    except NoSuchTableError:
        return catalog.create_table(ident, schema=schema), True


def merge_table(existing: pa.Table | None, incoming: list[dict[str, Any]], schema: pa.Schema) -> pa.Table:
    rows = [] if existing is None else existing.to_pylist()
    rows.extend(incoming)
    return arrow(dedupe_rows(rows), schema)


def commit_with_retry(table, data: pa.Table, *, created: bool, retries: int = 3) -> None:
    from pyiceberg.exceptions import CommitFailedException

    for attempt in range(retries):
        try:
            if created:
                table.append(data)
            else:
                table.overwrite(data)
            return
        except CommitFailedException:
            if attempt + 1 == retries:
                raise
            time.sleep(2 ** attempt)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--account", default=ACCOUNT_DEFAULT)
    parser.add_argument("--bucket", default=BUCKET_DEFAULT)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    payload = json.loads(pathlib.Path(args.input).read_text())
    if not isinstance(payload, dict):
        print("input top level must be an object", file=sys.stderr)
        return 2
    ingested_at = utc_now()
    batches: dict[str, list[dict[str, Any]]] = {}
    for table_name, input_key in INPUT_KEYS.items():
        batches[table_name] = normalize_rows(payload.get(input_key, []), SCHEMAS[table_name], ingested_at)
        print(f"SCANNED\t{table_name}\t{len(batches[table_name])}")
    if not any(batches.values()):
        print("no rows: refusing to report a successful empty sync", file=sys.stderr)
        return 2
    if args.dry_run:
        for name, rows in batches.items():
            if rows:
                print(f"DRY-RUN\t{name}\tunique={len(dedupe_rows(rows))}\tcolumns={len(SCHEMAS[name])}")
        return 0

    catalog = connect(args.account, args.bucket)
    ensure_namespace(catalog, NAMESPACE)

    failures = 0
    for name, incoming in batches.items():
        if not incoming:
            print(f"SKIP\t{name}\t0 incoming rows")
            continue
        ident = f"{NAMESPACE}.{name}"
        try:
            table, created = load_or_create(catalog, name, SCHEMAS[name])
            existing = None if created else table.scan().to_arrow()
            merged = merge_table(existing, incoming, SCHEMAS[name])
            commit_with_retry(table, merged, created=created)
            reread = catalog.load_table(ident).scan().to_arrow()
            ids = set(reread.column("record_id").to_pylist())
            missing = [row["record_id"] for row in incoming if row["record_id"] not in ids]
            print(
                f"COMMITTED\t{ident}\tincoming={len(incoming)}\t"
                f"rows={reread.num_rows}\tmissing={len(missing)}"
            )
            if missing or reread.num_rows != merged.num_rows:
                failures += 1
        except Exception as exc:
            failures += 1
            print(f"FAILED\t{ident}\t{type(exc).__name__}: {exc}", file=sys.stderr)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
