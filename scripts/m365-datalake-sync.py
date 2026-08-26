#!/usr/bin/env python3
"""M365 Archive を cloud-itonami-datalake へ投影し、原本を段階ミラーする。

全 annex path を Iceberg manifest に載せる。原本は content-addressed key で R2 に
保存し、同じ annex key を複数 path が参照しても一度だけ転送する。B2/git-annex は
既存の正本・復旧面として維持する。
"""

from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import datetime as dt
import hashlib
import json
import mimetypes
import os
from pathlib import Path
import re
import subprocess
import sys
from typing import Iterable

import pyarrow as pa

from datalake_catalog import ACCOUNT_DEFAULT, BUCKET_DEFAULT, connect, ensure_namespace, load_token


NAMESPACE = "cloud_itonami_private"
RAW_ROOTS = ("mail", "onedrive", "sharepoint", "teams", "calendar", "contacts", "facts")
ANNEX_KEY = re.compile(r"^(?P<backend>[A-Z0-9]+)-s(?P<size>\d+)--(?P<digest>[0-9a-f]+)(?:\..*)?$")
MAIL_TS = re.compile(r"^(\d{8}T\d{6}Z)_")
UPN = re.compile(r':upn\s+"([^"]+)"')
SIGNED_IN = re.compile(r':signed-in\s+"([^"]+)"')

STR = pa.string()
I64 = pa.int64()

ASSET_SCHEMA = pa.schema([
    ("record_id", STR), ("dataset", STR), ("source_system", STR),
    ("source_path", STR), ("annex_key", STR), ("annex_backend", STR),
    ("content_digest", STR), ("size_bytes", I64), ("extension", STR),
    ("media_type", STR), ("mailbox", STR), ("captured_at", STR),
    ("source_commit", STR), ("source_storage", STR), ("r2_object_key", STR),
    ("r2_status", STR), ("manifested_at", STR),
])

RECEIPT_SCHEMA = pa.schema([
    ("record_id", STR), ("annex_key", STR), ("r2_object_key", STR),
    ("size_bytes", I64), ("content_digest", STR), ("source_path", STR),
    ("uploaded_at", STR), ("verified_at", STR),
])

SUMMARY_SCHEMA = pa.schema([
    ("record_id", STR), ("source_system", STR), ("asset_count", I64),
    ("unique_content_count", I64), ("size_bytes", I64),
    ("mirrored_asset_count", I64), ("manifested_at", STR),
])


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat().replace("+00:00", "Z")


def sha256_text(value: str) -> str:
    return hashlib.sha256(value.encode()).hexdigest()


def object_key(annex_key: str) -> str:
    return f"raw/m365/annex/{sha256_text(annex_key)}"


def parse_annex_key(key: str) -> tuple[str, int, str]:
    match = ANNEX_KEY.match(key)
    if not match:
        raise ValueError(f"unsupported annex key: {key}")
    return match.group("backend"), int(match.group("size")), match.group("digest")


def load_mailboxes(archive: Path) -> tuple[str, dict[str, str]]:
    path = archive / "bin" / "mailboxes.edn"
    text = path.read_text(encoding="utf-8")
    signed = SIGNED_IN.search(text)
    signed_in = signed.group(1) if signed else "j.kawasaki@gftd.co.jp"
    by_local = {address.split("@", 1)[0]: address for address in UPN.findall(text)}
    return signed_in, by_local


def source_fields(relative: str, signed_in: str, mailbox_by_local: dict[str, str]) -> tuple[str, str | None, str | None]:
    parts = Path(relative).parts
    root = parts[0]
    mailbox = None
    captured = None
    if root == "mail":
        system = "m365-mail"
        if len(parts) > 1 and parts[1] in mailbox_by_local:
            mailbox = mailbox_by_local[parts[1]]
        else:
            mailbox = signed_in
        stamp = MAIL_TS.match(Path(relative).name)
        captured = stamp.group(1) if stamp else None
    elif root == "onedrive":
        system = "m365-onedrive"
    elif root == "sharepoint":
        system = "m365-sharepoint"
    elif root == "teams":
        system = "m365-teams"
    elif root == "calendar":
        system = "m365-calendar"
    elif root == "contacts":
        system = "m365-contacts"
    else:
        system = "m365-facts"
    return system, mailbox, captured


def local_annex_keys(archive: Path) -> dict[str, str]:
    result = subprocess.run(
        ["git", "annex", "find", "--format=${file}\t${key}\n"],
        cwd=archive, capture_output=True, text=True, check=True,
    )
    return dict(line.split("\t", 1) for line in result.stdout.splitlines() if "\t" in line)


def annex_key_for(path: Path, relative: str, local_keys: dict[str, str]) -> str | None:
    if relative in local_keys:
        return local_keys[relative]
    if not path.is_symlink():
        try:
            if path.stat().st_size <= 512:
                marker = path.read_text(encoding="utf-8").strip()
                if marker.startswith("/annex/objects/"):
                    return Path(marker).name
        except (OSError, UnicodeDecodeError):
            pass
        return None
    return Path(os.readlink(path)).name


def scan_assets(archive: Path, receipts: set[str], manifested_at: str) -> list[dict]:
    signed_in, mailbox_by_local = load_mailboxes(archive)
    local_keys = local_annex_keys(archive)
    source_commit = subprocess.run(
        ["git", "rev-parse", "HEAD"], cwd=archive, capture_output=True, text=True, check=True,
    ).stdout.strip()
    rows = []
    for root_name in RAW_ROOTS:
        root = archive / root_name
        if not root.exists():
            continue
        for dirpath, dirnames, filenames in os.walk(root, followlinks=False):
            dirnames.sort()
            for filename in sorted(filenames):
                path = Path(dirpath) / filename
                relative = path.relative_to(archive).as_posix()
                key = annex_key_for(path, relative, local_keys)
                if not key:
                    continue
                try:
                    backend, size, digest = parse_annex_key(key)
                except ValueError:
                    continue
                system, mailbox, captured = source_fields(relative, signed_in, mailbox_by_local)
                extension = path.suffix.lower().lstrip(".") or None
                media_type = mimetypes.guess_type(path.name)[0]
                rows.append({
                    "record_id": sha256_text(relative), "dataset": "gftdcojp/m365-archive",
                    "source_system": system, "source_path": relative, "annex_key": key,
                    "annex_backend": backend, "content_digest": digest, "size_bytes": size,
                    "extension": extension, "media_type": media_type, "mailbox": mailbox,
                    "captured_at": captured, "source_commit": source_commit,
                    "source_storage": "git-annex+b2", "r2_object_key": object_key(key),
                    "r2_status": "mirrored" if key in receipts else "pending",
                    "manifested_at": manifested_at,
                })
    return rows


def local_candidates(archive: Path) -> list[tuple[str, str, int]]:
    result = subprocess.run(
        ["git", "annex", "find", "--in=here", "--format=${file}\t${key}\t${bytesize}\n"],
        cwd=archive, capture_output=True, text=True, check=True,
    )
    candidates = []
    for line in result.stdout.splitlines():
        parts = line.split("\t")
        if len(parts) != 3 or parts[0].split("/", 1)[0] not in RAW_ROOTS:
            continue
        candidates.append((parts[0], parts[1], int(parts[2])))
    return sorted(candidates, key=lambda item: (item[2], item[0]))


def digest_file(path: Path, backend: str) -> str:
    algorithm = "md5" if backend.startswith("MD5") else "sha256"
    digest = hashlib.new(algorithm)
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def wrangler(env: dict[str, str], *args: str, stdout=None):
    return subprocess.run(["wrangler", *args], env=env, stdout=stdout, stderr=subprocess.PIPE, check=True)


def mirror_one(archive: Path, env: dict[str, str], relative: str, key: str, size: int) -> dict | None:
    backend, expected_size, expected_digest = parse_annex_key(key)
    path = archive / relative
    if not path.exists() or path.stat().st_size != expected_size:
        return None
    actual_digest = digest_file(path, backend)
    if actual_digest != expected_digest:
        raise RuntimeError(f"source digest mismatch: {relative}")
    target = f"{BUCKET_DEFAULT}/{object_key(key)}"
    wrangler(env, "r2", "object", "put", target, "--remote", "-y", "--file", str(path), stdout=subprocess.DEVNULL)
    check = subprocess.run(
        ["wrangler", "r2", "object", "get", target, "--remote", "--pipe"],
        env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True,
    ).stdout
    algorithm = "md5" if backend.startswith("MD5") else "sha256"
    if len(check) != expected_size or hashlib.new(algorithm, check).hexdigest() != expected_digest:
        raise RuntimeError(f"R2 readback mismatch: {relative}")
    now = utc_now()
    return {
        "record_id": sha256_text(key), "annex_key": key, "r2_object_key": object_key(key),
        "size_bytes": size, "content_digest": expected_digest, "source_path": relative,
        "uploaded_at": now, "verified_at": now,
    }


def mirror_local(
    archive: Path, known: dict[str, dict], max_files: int, max_bytes: int, workers: int = 8,
) -> list[dict]:
    if max_files <= 0 or max_bytes <= 0:
        return list(known.values())
    env = os.environ.copy()
    env["CLOUDFLARE_API_TOKEN"] = load_token()
    receipts = dict(known)
    selected = []
    selected_keys = set()
    planned_bytes = 0
    for relative, key, size in local_candidates(archive):
        if key in receipts or key in selected_keys or len(selected) >= max_files or planned_bytes + size > max_bytes:
            continue
        selected.append((relative, key, size))
        selected_keys.add(key)
        planned_bytes += size
    uploaded = 0
    uploaded_bytes = 0
    with ThreadPoolExecutor(max_workers=max(1, workers)) as pool:
        futures = {
            pool.submit(mirror_one, archive, env, relative, key, size): (relative, key, size)
            for relative, key, size in selected
        }
        for future in as_completed(futures):
            row = future.result()
            if row is None:
                continue
            receipts[row["annex_key"]] = row
            uploaded += 1
            uploaded_bytes += row["size_bytes"]
            print(f"MIRRORED\tfiles={uploaded}\tbytes={uploaded_bytes}", flush=True)
    return [receipts[key] for key in sorted(receipts)]


def load_receipts(catalog) -> dict[str, dict]:
    from pyiceberg.exceptions import NoSuchTableError
    try:
        table = catalog.load_table(f"{NAMESPACE}.m365_mirror_receipt").scan().to_arrow()
    except NoSuchTableError:
        return {}
    return {row["annex_key"]: row for row in table.to_pylist()}


def summaries(assets: Iterable[dict], manifested_at: str) -> list[dict]:
    grouped = {}
    for row in assets:
        group = grouped.setdefault(row["source_system"], {"assets": 0, "keys": set(), "bytes": 0, "mirrored": 0})
        group["assets"] += 1
        group["keys"].add(row["annex_key"])
        group["bytes"] += row["size_bytes"]
        group["mirrored"] += row["r2_status"] == "mirrored"
    return [{
        "record_id": source, "source_system": source, "asset_count": value["assets"],
        "unique_content_count": len(value["keys"]), "size_bytes": value["bytes"],
        "mirrored_asset_count": value["mirrored"], "manifested_at": manifested_at,
    } for source, value in sorted(grouped.items())]


def commit(catalog, name: str, rows: list[dict], schema: pa.Schema) -> int:
    from pyiceberg.exceptions import NoSuchTableError
    ident = f"{NAMESPACE}.{name}"
    data = pa.Table.from_pylist(rows, schema=schema)
    try:
        table = catalog.load_table(ident)
        table.overwrite(data)
    except NoSuchTableError:
        table = catalog.create_table(ident, schema=schema)
        if rows:
            table.append(data)
    reread = catalog.load_table(ident).scan().to_arrow()
    if reread.num_rows != len(rows):
        raise RuntimeError(f"{ident}: wrote {len(rows)} read {reread.num_rows}")
    print(f"COMMITTED\t{ident}\trows={reread.num_rows}")
    return reread.num_rows


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--archive-root", default="orgs/gftdcojp/m365-archive")
    parser.add_argument("--mirror-local-max-files", type=int, default=0)
    parser.add_argument("--mirror-local-max-bytes", type=int, default=0)
    parser.add_argument("--workers", type=int, default=8)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    archive = Path(args.archive_root).resolve()
    manifested_at = utc_now()
    if args.dry_run:
        receipts = {}
    else:
        catalog = connect(ACCOUNT_DEFAULT, BUCKET_DEFAULT)
        ensure_namespace(catalog, NAMESPACE)
        receipts = load_receipts(catalog)
    receipt_rows = mirror_local(
        archive, receipts, args.mirror_local_max_files, args.mirror_local_max_bytes, args.workers,
    )
    receipt_keys = {row["annex_key"] for row in receipt_rows}
    assets = scan_assets(archive, receipt_keys, manifested_at)
    summary_rows = summaries(assets, manifested_at)
    print(f"SCANNED\tassets={len(assets)}\tunique_keys={len({row['annex_key'] for row in assets})}\treceipts={len(receipt_rows)}")
    if args.dry_run:
        print(json.dumps(summary_rows, ensure_ascii=False, indent=2))
        return 0 if assets else 2
    commit(catalog, "m365_mirror_receipt", receipt_rows, RECEIPT_SCHEMA)
    commit(catalog, "m365_asset_manifest", assets, ASSET_SCHEMA)
    commit(catalog, "m365_asset_summary", summary_rows, SUMMARY_SCHEMA)
    return 0


if __name__ == "__main__":
    sys.exit(main())
