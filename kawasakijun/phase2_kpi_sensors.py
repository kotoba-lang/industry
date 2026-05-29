"""
phase2_kpi_sensors.py

Gmail / Calendar / git / Drive を読んで、各クリティカルノードの完了 / 進捗を
自動判定する。`pregel_planner.py` や `reverse_topo_pregel.py` の vertex state を
ここで観測値で更新する想定。

CLI 起動でレポートを出すか、Cron で日次実行する。

依存:
  - 既存の Gmail/Drive MCP は対話セッション内のみ。本ファイルは
    オフライン用に SQLite キャッシュ + IMAP/CalDAV を想定したスタブ。
  - 実装は段階的に拡張。最初は git log と Calendar ローカル DB から始める。
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import subprocess
from dataclasses import dataclass, asdict
from pathlib import Path

REPO = Path(__file__).parent.parent


@dataclass
class KPI:
    name: str
    value: float | int | str
    threshold: str
    status: str  # green | yellow | red | unknown
    evidence: str = ""


# === センサ実装 ===

def kpi_physics_commits(days: int = 30) -> KPI:
    """git log で kawasakijun/ + projects/2604-linde の最近のコミット数。"""
    since = (dt.date.today() - dt.timedelta(days=days)).isoformat()
    try:
        out = subprocess.check_output([
            "git", "-C", str(REPO), "log",
            f"--since={since}", "--oneline",
            "--", "kawasakijun/", "projects/2604-linde/"
        ], text=True)
    except subprocess.CalledProcessError:
        out = ""
    n = sum(1 for line in out.splitlines() if line.strip())
    threshold = 4 * (days / 30)
    status = "green" if n >= threshold else ("yellow" if n >= threshold / 2 else "red")
    return KPI(
        name="physics.commits",
        value=n,
        threshold=f">= {threshold:.0f}/{days}d",
        status=status,
        evidence=f"git log --since={since}",
    )


def kpi_paidy_overdue() -> KPI:
    """Paidy 滞納件数 (Gmail MCP のキャッシュを期待 / なければ unknown)。"""
    cache = REPO / "kawasakijun" / ".sensors_cache" / "paidy_overdue.json"
    if not cache.exists():
        return KPI(
            "paidy.overdue", "unknown", "= 0", "unknown",
            "Gmail センサが未起動。phase2_kpi_sensors.py --collect で取得"
        )
    data = json.loads(cache.read_text())
    n = len(data.get("overdue_threads", []))
    return KPI(
        "paidy.overdue", n, "= 0",
        "green" if n == 0 else "red",
        f"{cache}",
    )


def kpi_lingling_brief() -> KPI:
    """LingLing 準備書面の最新提出を Gmail キャッシュから検出。"""
    cache = REPO / "kawasakijun" / ".sensors_cache" / "lingling_briefs.json"
    if not cache.exists():
        return KPI(
            "lingling.brief_last_seen", "unknown",
            "<= 30 days", "unknown",
            "Gmail センサ未起動",
        )
    data = json.loads(cache.read_text())
    last = data.get("last_brief_date")
    if not last:
        return KPI(
            "lingling.brief_last_seen", "none", "<= 30 days", "red",
        )
    days_ago = (dt.date.today() - dt.date.fromisoformat(last)).days
    status = "green" if days_ago <= 30 else "yellow" if days_ago <= 60 else "red"
    return KPI(
        "lingling.brief_last_seen", f"{days_ago}d ago",
        "<= 30 days", status, cache.as_posix(),
    )


def kpi_aishi_disclosure() -> KPI:
    """アイシステム送金履歴の TOTAL 受領を Drive/Gmail キャッシュから検出。"""
    cache = REPO / "kawasakijun" / ".sensors_cache" / "aishi_disclosure.json"
    if not cache.exists():
        return KPI(
            "aishi.disclosure", "unknown", "received & shared",
            "unknown", "未起動",
        )
    data = json.loads(cache.read_text())
    return KPI(
        "aishi.disclosure",
        data.get("status", "unknown"),
        "received & shared",
        "green" if data.get("status") == "shared" else "yellow",
        cache.as_posix(),
    )


def kpi_health_clinic() -> KPI:
    """渋谷こころのクリニック の月次通院を Calendar キャッシュから。"""
    cache = REPO / "kawasakijun" / ".sensors_cache" / "clinic_visits.json"
    if not cache.exists():
        return KPI(
            "health.clinic_visit", "unknown", "1+/month",
            "unknown", "未起動",
        )
    data = json.loads(cache.read_text())
    n_this_month = data.get("this_month_visits", 0)
    return KPI(
        "health.clinic_visit", n_this_month, "1+/month",
        "green" if n_this_month >= 1 else "red",
        cache.as_posix(),
    )


def kpi_nodoka_time(days: int = 7) -> KPI:
    """娘との時間 — Calendar + 写真撮影頻度から推定。"""
    cache = REPO / "kawasakijun" / ".sensors_cache" / "nodoka_time.json"
    if not cache.exists():
        return KPI(
            "nodoka.time_with", "unknown", ">= 8h/week",
            "unknown", "未起動",
        )
    data = json.loads(cache.read_text())
    hours = data.get(f"hours_last_{days}d", 0)
    return KPI(
        "nodoka.time_with", f"{hours}h",
        ">= 8h/week",
        "green" if hours >= 8 else "yellow" if hours >= 4 else "red",
        cache.as_posix(),
    )


SENSORS = [
    kpi_physics_commits,
    kpi_paidy_overdue,
    kpi_lingling_brief,
    kpi_aishi_disclosure,
    kpi_health_clinic,
    kpi_nodoka_time,
]


def collect_all() -> list[KPI]:
    return [s() for s in SENSORS]


def report(kpis: list[KPI]) -> str:
    icon = {"green": "🟢", "yellow": "🟡", "red": "🔴", "unknown": "⚪"}
    lines = ["# KPI Sensor Report — " + dt.date.today().isoformat() + "\n"]
    lines.append("| Status | KPI | Value | Threshold | Evidence |")
    lines.append("|---|---|---|---|---|")
    for k in kpis:
        lines.append(
            f"| {icon[k.status]} | `{k.name}` | {k.value} | "
            f"{k.threshold} | {k.evidence} |"
        )
    lines.append("\n## Pregel 反映用 state パッチ\n")
    patch = {k.name: {"value": k.value, "status": k.status} for k in kpis}
    lines.append("```json\n" + json.dumps(patch, indent=2, ensure_ascii=False) + "\n```")
    return "\n".join(lines)


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--json", action="store_true", help="JSON で出力")
    p.add_argument("--out", default=None)
    args = p.parse_args()
    kpis = collect_all()
    if args.json:
        out = json.dumps([asdict(k) for k in kpis], indent=2, ensure_ascii=False)
    else:
        out = report(kpis)
    if args.out:
        Path(args.out).write_text(out)
        print(f"wrote {args.out}")
    else:
        print(out)
