"""datalake_catalog.py — Cloudflare R2 Data Catalog への接続だけを共有する。

## 何を共有し、何を共有しないか

共有するのは **接続**（account / bucket / warehouse / URI / token の解決）だけ。
**書き方は共有しない。** 現在 2 つの consumer があり、両者は本当に別の書き方を
している:

  datalake-sync.py       spec が表を決める / 型は推論（string と int32）/
                         毎回 overwrite（全量入れ替え）
  ses-mail-datalake-sync.py
                         明示 pyarrow schema（list<string> と int64 を含む）/
                         record_id で upsert / 未知フィールドを拒否 /
                         CommitFailedException に backoff

これを 1 本に畳むと、どちらかが機能を失う（型付き schema と list 列を捨てるか、
汎用 loader に upsert 鍵と検証規則を持ち込むか）。**重複していたのは接続の
15 行だけで、その 15 行が両方に同じ Keychain 参照と同じ account id を
コピーさせていた** —— account を移したときに片方だけ直る形。共有すべきはそこ。

## token

R2 Data Catalog は Cloudflare API token を Bearer で受ける。必要な権限は 2 つ:
**R2 Data Catalog: Edit** と **Workers R2 Storage: Edit**。

⚠ **wrangler の OAuth session は使えない。metadata 面だけ通る。**
`GET /v1/config` は 200 を返し `create_namespace` も成功するが、**storage 面で
401** になり `create_table` が落ちる（catalog 側が R2 を list できない）。
`wrangler whoami` の scope に `r2` が無いことと整合する。**metadata が通ることを
「使える」と読まない** —— 面が 2 つある。だからここは OAuth token へ
フォールバックしない: 通らない資格情報に静かに落ちると、失敗が「書けなかった」
ではなく「認証は済んでいるのに謎の 401」に見える。

解決順は CF_CATALOG_TOKEN 環境変数 → macOS Keychain `service=gftd.cf` /
`account=API_TOKEN`（2026-08-25 実測、この鍵で create_table と append が通る）。
token は argv に載せない（ps 露出）。
"""

from __future__ import annotations

import os
import subprocess
import sys

ACCOUNT_DEFAULT = "4da88288dc30d9ee257f319d3c33ecf0"
BUCKET_DEFAULT = "cloud-itonami-datalake"

_NO_TOKEN = (
    "no catalog token. Set CF_CATALOG_TOKEN, or store a Cloudflare API token with\n"
    "  'R2 Data Catalog: Edit' + 'Workers R2 Storage: Edit' in Keychain\n"
    "  service=gftd.cf account=API_TOKEN.\n"
    "  (wrangler's OAuth session is NOT enough: it passes /v1/config and fails create_table.)"
)


def load_token(*, exit_on_missing: bool = True) -> str:
    """CF_CATALOG_TOKEN -> Keychain gftd.cf/API_TOKEN. No other fallback."""
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
    if exit_on_missing:
        print(_NO_TOKEN, file=sys.stderr)
        sys.exit(2)
    raise RuntimeError(_NO_TOKEN)


def connect(account: str = ACCOUNT_DEFAULT, bucket: str = BUCKET_DEFAULT, *, token: str | None = None):
    from pyiceberg.catalog.rest import RestCatalog

    return RestCatalog(
        name="cloud_itonami_datalake",
        warehouse=f"{account}_{bucket}",
        uri=f"https://catalog.cloudflarestorage.com/{account}/{bucket}",
        token=token or load_token(),
    )


def ensure_namespace(catalog, namespace: str) -> None:
    """Create the namespace unless it already exists.

    The already-exists case is reported, not swallowed silently: "created it"
    and "it was already there" are different facts, and a run log that shows
    neither cannot tell you which catalog you were pointed at.
    """
    try:
        catalog.create_namespace(namespace)
        print(f"created namespace {namespace}")
    except Exception as e:  # NamespaceAlreadyExistsError, and anything the REST layer wraps it in
        print(f"namespace {namespace}: {type(e).__name__} (assuming it exists)")
