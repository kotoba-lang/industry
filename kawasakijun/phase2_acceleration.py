"""
phase2_acceleration.py

「クリティカル経路の各エッジを切ったら全体期間がどれだけ縮むか」を
網羅シミュレーションし、レバレッジが大きい施策を可視化。

戦略:
  - 14_loan_acceleration → 15_remarriage エッジを切る = 借入完済を待たずに再婚
  - 16_more_children → 11_health_steady だけ残す = パートナーに頼らず養子
  - 06_lingling_brief → 10_commons_revenue = LingLing 結着前に宿泊業を加速

各施策の効果 = ベースライン (19.25y) - 修正後のクリティカル経路長
"""

from __future__ import annotations

import argparse
from pathlib import Path

import networkx as nx

import sys
sys.path.insert(0, str(Path(__file__).parent))
from reverse_topo_pregel import GOALS, EDGES, build_dag  # noqa: E402
from phase2_critical_path import cpm_forward, cpm_backward, critical_path  # noqa: E402


def project_duration(dag: nx.DiGraph) -> float:
    schedule = cpm_forward(dag)
    return max(s["EF"] for s in schedule.values())


def what_if_remove_edge(src: str, dst: str) -> tuple[float, list[str]]:
    """指定エッジを除去した場合の最短期間と新しい CP を返す。"""
    dag = build_dag()
    if (src, dst) not in dag.edges():
        return project_duration(dag), []
    dag.remove_edge(src, dst)
    schedule = cpm_forward(dag)
    duration = max(s["EF"] for s in schedule.values())
    schedule = cpm_backward(dag, schedule, duration)
    return duration, critical_path(dag, schedule)


def what_if_shrink_node(nid: str, factor: float) -> tuple[float, list[str]]:
    """指定ノードの est_years を factor 倍に縮めた場合の効果。"""
    dag = build_dag()
    original = GOALS[nid].est_years
    GOALS[nid].est_years = original * factor
    schedule = cpm_forward(dag)
    duration = max(s["EF"] for s in schedule.values())
    schedule = cpm_backward(dag, schedule, duration)
    cp = critical_path(dag, schedule)
    GOALS[nid].est_years = original
    return duration, cp


def report() -> str:
    baseline = project_duration(build_dag())
    lines = ["# Acceleration Scenarios — 加速シナリオ分析\n"]
    lines.append(f"- **ベースライン**: {baseline:.2f} 年\n")

    lines.append("## A. エッジ削除 (依存を切る)\n")
    interesting_edges = [
        ("14_loan_acceleration", "15_remarriage",
         "再婚を借入完済の待ち期間から外す (パートナー要件を経済より関係性主導に)"),
        ("15_remarriage", "16_more_children",
         "子供を再婚と切り離す (養子 / 共同養育 / シングルファーザー継続)"),
        ("11_health_steady", "16_more_children",
         "子供を健康改善と切り離す"),
        ("06_lingling_brief", "10_commons_revenue",
         "COMMONS 宿泊業を LingLing 結着前に並走"),
        ("17_internal_control", "18_ipo_or_foundation",
         "IPO を内部統制と並走 (リスク高、要監査法人協議)"),
    ]
    lines.append("| 切るエッジ | 効果 | 短縮 (年) | 新 CP 長 |")
    lines.append("|---|---|---:|---:|")
    for src, dst, desc in interesting_edges:
        new_dur, _ = what_if_remove_edge(src, dst)
        saving = baseline - new_dur
        s_name = GOALS[src].title
        d_name = GOALS[dst].title
        lines.append(
            f"| **{s_name}** → **{d_name}** | {desc} | "
            f"{saving:+.2f} | {new_dur:.2f} |"
        )

    lines.append("\n## B. ノード短縮 (実行を圧縮)\n")
    shrinks = [
        ("14_loan_acceleration", 0.5, "借入返済を 7y→3.5y (Gftd 売上倍増 + COMMONS 早期回収)"),
        ("06_lingling_brief", 0.5, "LingLing を 1y→0.5y (和解戦略 + 簡易裁判)"),
        ("10_commons_revenue", 0.5, "COMMONS 宿泊業を 2y→1y (集中投資)"),
        ("15_remarriage", 0.5, "再婚を 3y→1.5y (国際的に積極的なマッチング)"),
        ("18_ipo_or_foundation", 0.5, "IPO を 7y→3.5y (PE/VC 巻き込み)"),
    ]
    lines.append("| 縮めるノード | 戦略 | 元 → 新 (年) | 短縮 (年) | 新 CP 長 |")
    lines.append("|---|---|---|---:|---:|")
    for nid, factor, desc in shrinks:
        new_dur, _ = what_if_shrink_node(nid, factor)
        saving = baseline - new_dur
        orig = GOALS[nid].est_years
        new = orig * factor
        lines.append(
            f"| {GOALS[nid].title} | {desc} | {orig:.1f}→{new:.1f} | "
            f"{saving:+.2f} | {new_dur:.2f} |"
        )

    lines.append("\n## C. 推奨組合せ (現実的な圧縮シナリオ)\n")
    lines.append("**シナリオ S1**: LingLing 早期和解 + 借入返済加速 + 再婚を経済から切り離す")

    dag = build_dag()
    GOALS["06_lingling_brief"].est_years *= 0.5
    GOALS["14_loan_acceleration"].est_years *= 0.7
    dag.remove_edge("14_loan_acceleration", "15_remarriage")
    schedule = cpm_forward(dag)
    s1_dur = max(s["EF"] for s in schedule.values())
    GOALS["06_lingling_brief"].est_years *= 2.0
    GOALS["14_loan_acceleration"].est_years /= 0.7
    lines.append(f"  → **{s1_dur:.2f} 年** ({baseline - s1_dur:+.2f} 短縮)\n")

    lines.append("**シナリオ S2 (積極)**: + 子供を再婚から切り離し養子経路を併走")
    dag = build_dag()
    GOALS["06_lingling_brief"].est_years *= 0.5
    GOALS["14_loan_acceleration"].est_years *= 0.7
    dag.remove_edge("14_loan_acceleration", "15_remarriage")
    dag.remove_edge("15_remarriage", "16_more_children")
    schedule = cpm_forward(dag)
    s2_dur = max(s["EF"] for s in schedule.values())
    GOALS["06_lingling_brief"].est_years *= 2.0
    GOALS["14_loan_acceleration"].est_years /= 0.7
    lines.append(f"  → **{s2_dur:.2f} 年** ({baseline - s2_dur:+.2f} 短縮)\n")

    lines.append("\n## D. 結論\n")
    lines.append(
        "- ベースライン **19.25 年** は 50代後半まで子供が完結しない\n"
        "- **S1** で借入と LingLing を圧縮するだけで大幅短縮\n"
        "- **S2** で家族と財務を切り離せばさらに短縮\n"
        "- 物理 / fiction / infra / spirit は 12+ 年スラックがあるので焦らず並行\n"
    )
    return "\n".join(lines)


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--out", default=None)
    args = p.parse_args()
    text = report()
    if args.out:
        Path(args.out).write_text(text)
        print(f"wrote {args.out}")
    else:
        print(text)
