#!/usr/bin/env python3
"""hermes-cron-export — 全 Hermes profile の cron job 定義を台帳 JSON に export する。

正本台帳: 90-docs/business/hermes-cron-jobs.json
- schedule / prompt / deliver / script を記録する（re-register 可能な粒度）。
- 実行状態（state / last_run / next_run / execution id）は記録しない — それは
  端末固有の稼働状態であり、台帳の価値は「定義の再現」にある。
- keychain / credential の値は絶対に含めない（prompt 内に credential 値が
  侵入したら台帳に載せず skill 側の参照に置換する）。

使い方:
  python3 scripts/hermes-cron-jobs/export_cron.py            # 台帳を再生成
  python3 scripts/hermes-cron-jobs/export_cron.py --check    # 差分があれば exit 1
"""
import json
import glob
import os
import re
import sys

HERMES_ROOT = os.path.expanduser("~/.hermes/profiles")


def _now():
    """UTC, second resolution. A ledger whose age cannot be read is
    indistinguishable from a current one -- measured 2026-09-04, this file
    covered 21 of 98 scheduled profiles and said nothing about when it had
    last been written."""
    import datetime
    return datetime.datetime.now(datetime.timezone.utc).replace(
        microsecond=0).isoformat().replace("+00:00", "Z")


def _undated(text):
    """`text` with the generated_at line removed, for --check's comparison."""
    return "\n".join(l for l in text.splitlines()
                     if '"generated_at"' not in l)
LEDGER = os.path.join(os.path.dirname(__file__), "hermes-cron-jobs.json")

# credential に見える文字列の検出（値そのものを台帳に焼かないための衛生検査）。
# 短い部分一致は誤検知を量産する（実測: 'task_comments' が 'sk_' を含み警告した）。
# 長さしきい値つきの正規表現で実キー形状だけを拾う。誤検知は review 用 warning。
CRED_RES = [
    re.compile(r"sk-[A-Za-z0-9_-]{20,}"),        # OpenAI-style
    re.compile(r"sk_[A-Za-z0-9_-]{20,}"),        # alt prefix
    re.compile(r"ghp_[A-Za-z0-9]{20,}"),          # GitHub PAT
    re.compile(r"github_pat_[A-Za-z0-9_]{20,}"),
    re.compile(r"xoxb-[A-Za-z0-9-]{20,}"),        # Slack
    re.compile(r"AKIA[0-9A-Z]{16}"),              # AWS access key
    re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"),
    re.compile(r"(?i)(password|token|secret)\s*[=:]\s*['\"]?[A-Za-z0-9_\-]{16,}"),
]


def redact_check(text):
    hits = []
    for rx in CRED_RES:
        m = rx.search(text)
        if m:
            # never bake the matched value itself into the ledger
            hits.append(rx.pattern)
    return hits


def main():
    check = "--check" in sys.argv
    ledger = {"schema": "itonami.hermes-cron-jobs.v1",
              "note": "Re-registerable definition ledger for every Hermes "
                      "profile cron job. State fields are intentionally absent "
                      "(terminal-local). Regenerate with export_cron.py.",
              "generated_at": _now(),
              "profiles": {}}

    for jobs_file in sorted(glob.glob(os.path.join(HERMES_ROOT, "*", "cron", "jobs.json"))):
        profile = os.path.basename(os.path.dirname(os.path.dirname(jobs_file)))
        try:
            with open(jobs_file) as f:
                d = json.load(f)
        except Exception:
            continue
        jobs = d.get("jobs", d) if isinstance(d, dict) else d
        out = []
        for j in jobs:
            if not isinstance(j, dict):
                continue
            sched = j.get("schedule") or {}
            entry = {
                "id": j.get("id"),
                "name": j.get("name"),
                "schedule": sched if isinstance(sched, dict) else str(sched),
                "prompt": j.get("prompt") or "",
                "script": j.get("script"),
                "workdir": j.get("workdir"),
                "deliver": j.get("deliver"),
                "no_agent": j.get("no_agent"),
                "enabled_toolsets": j.get("enabled_toolsets"),
                "context_from": j.get("context_from"),
                "repeat": j.get("repeat"),
            }
            full_text = json.dumps(entry, ensure_ascii=False)
            creds = redact_check(full_text)
            if creds:
                entry["_credential_warning"] = creds
            out.append(entry)
        if out:
            ledger["profiles"][profile] = out

    body = json.dumps(ledger, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    if check:
        with open(LEDGER) as f:
            current = f.read()
        # Compare the DEFINITIONS, not the timestamp. `generated_at` used to be
        # the constant "regenerated-on-run" precisely so this byte comparison
        # would not fire on every run -- which bought a working --check at the
        # price of a ledger nobody could date. Normalising the one field that is
        # expected to differ buys both: --check stays quiet when the definitions
        # match, and the file says when it was last written.
        if _undated(current) == _undated(body):
            print("hermes-cron-jobs.json is up to date.")
            return 0
        print("STALE: ledger differs from live cron definitions. Re-run without --check.")
        return 1
    with open(LEDGER, "w") as f:
        f.write(body)
    n = sum(len(v) for v in ledger["profiles"].values())
    print(f"wrote {LEDGER}: {len(ledger['profiles'])} profiles, {n} jobs")
    return 0


if __name__ == "__main__":
    sys.exit(main())
