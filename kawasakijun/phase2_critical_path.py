"""
phase2_critical_path.py

Critical Path Method (CPM) + スラック解析プランナー。

reverse_topo_pregel.py が greedy ROI で並走させていたのを改良:
  - 各ノードの最早開始 (ES) / 最早完了 (EF) / 最遅開始 (LS) / 最遅完了 (LF)
  - スラック (LS - ES) でクリティカル経路を識別
  - クリティカルノードを優先的に確保し、フリースラックがあるノードは後回し
  - 結果: より長い水平線で達成可能 (理論最短時間)

入力: reverse_topo_pregel.py の GOALS / EDGES を再利用
出力: クリティカル経路、各ノードの ES/EF/LS/LF/Slack、推奨開始月
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import networkx as nx

import sys
sys.path.insert(0, str(Path(__file__).parent))
from reverse_topo_pregel import GOALS, EDGES, build_dag  # noqa: E402


def cpm_forward(dag: nx.DiGraph) -> dict[str, dict]:
    """各ノードに ES (Earliest Start), EF (Earliest Finish) を割り付け。"""
    schedule = {}
    for nid in nx.topological_sort(dag):
        preds = list(dag.predecessors(nid))
        es = max((schedule[p]["EF"] for p in preds), default=0.0)
        ef = es + GOALS[nid].est_years
        schedule[nid] = {"ES": es, "EF": ef}
    return schedule


def cpm_backward(dag: nx.DiGraph, schedule: dict[str, dict],
                 project_duration: float) -> dict[str, dict]:
    """各ノードに LS (Latest Start), LF (Latest Finish), Slack を逆順で割り付け。"""
    for nid in reversed(list(nx.topological_sort(dag))):
        succs = list(dag.successors(nid))
        lf = min((schedule[s]["LS"] for s in succs), default=project_duration)
        ls = lf - GOALS[nid].est_years
        schedule[nid].update({"LS": ls, "LF": lf, "Slack": ls - schedule[nid]["ES"]})
    return schedule


def critical_path(dag: nx.DiGraph, schedule: dict[str, dict]) -> list[str]:
    """Slack = 0 のノード列 = クリティカル経路。"""
    return [
        nid for nid in nx.topological_sort(dag)
        if abs(schedule[nid]["Slack"]) < 1e-6
    ]


def analyze() -> tuple[dict, list[str], float]:
    dag = build_dag()
    schedule = cpm_forward(dag)
    duration = max(s["EF"] for s in schedule.values())
    schedule = cpm_backward(dag, schedule, duration)
    cp = critical_path(dag, schedule)
    return schedule, cp, duration


def report_markdown(schedule: dict, cp: list[str], duration: float) -> str:
    lines = [f"# Critical Path Analysis\n"]
    lines.append(f"- **理論最短プロジェクト期間**: **{duration:.2f} 年**")
    lines.append(f"- **クリティカル経路 (slack=0)**: {len(cp)} / {len(GOALS)} ノード\n")

    lines.append("## クリティカル経路 (これを縮めれば全体が縮む)\n")
    for nid in cp:
        s = schedule[nid]
        g = GOALS[nid]
        lines.append(
            f"- **[{g.tau}/{g.domain}] {g.title}** "
            f"({g.est_years}y, W={g.W_contribution})  "
            f"ES={s['ES']:.2f} → EF={s['EF']:.2f}"
        )

    lines.append("\n## 全ノードの ES/EF/Slack (Slack 昇順)\n")
    lines.append("| Node | ES (yr) | EF (yr) | Slack (yr) | CP? |")
    lines.append("|---|---:|---:|---:|---|")
    rows = sorted(GOALS.keys(), key=lambda n: schedule[n]["Slack"])
    for nid in rows:
        s = schedule[nid]
        g = GOALS[nid]
        cp_mark = "★" if nid in cp else ""
        lines.append(
            f"| {g.title} | {s['ES']:.2f} | {s['EF']:.2f} | {s['Slack']:.2f} | {cp_mark} |"
        )

    lines.append("\n## クリティカル経路の加速候補\n")
    for nid in cp:
        g = GOALS[nid]
        if g.external_dependency:
            lines.append(
                f"- **{g.title}** ({g.est_years}y) — 外部依存: {g.external_dependency}"
            )
            if g.fallback:
                lines.append(f"  - **代替経路**: {g.fallback}")
        else:
            lines.append(f"- **{g.title}** ({g.est_years}y) — 内部最適化で短縮可能")

    lines.append("\n## ガントチャート (ASCII)\n")
    scale = 50.0 / duration
    for nid in sorted(GOALS.keys(), key=lambda n: schedule[n]["ES"]):
        s = schedule[nid]
        g = GOALS[nid]
        pad = " " * int(s["ES"] * scale)
        bar = "█" * max(1, int(g.est_years * scale))
        slack_bar = "·" * int(s["Slack"] * scale)
        marker = "★" if nid in cp else " "
        lines.append(f"`{marker} {pad}{bar}{slack_bar}` {g.title}")

    return "\n".join(lines)


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--out", default=None)
    args = p.parse_args()
    schedule, cp, duration = analyze()
    text = report_markdown(schedule, cp, duration)
    if args.out:
        Path(args.out).write_text(text)
        print(f"wrote {args.out}")
    else:
        print(text)
