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
        ("23_jk_wellness_lab", "10_commons_revenue",
         "宿泊業を JK Wellness ピボット完了を待たずに並走"),
        ("15_remarriage", "16_more_children",
         "子供を再婚と切り離す (養子 / 共同養育)"),
        ("17_internal_control", "18_ipo_or_foundation",
         "IPO を内部統制と並走"),
        ("10_commons_revenue", "14_loan_acceleration",
         "借入返済の原資を COMMONS 以外に移す (Gftd 単独で返済)"),
        ("14_loan_acceleration", "18_ipo_or_foundation",
         "IPO を借入完済前に実施 (一部負債を抱えたまま上場)"),
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
        ("14_loan_acceleration", 0.5, "借入返済 7y→3.5y (Gftd+etzhayyim+cyber treaty による売上加速)"),
        ("21_gftd_sales_pipeline", 0.6, "Gftd 営業 1.5y→0.9y (厚労省+Apple+鹿大+理研の同時攻略)"),
        ("22_etzhayyim", 0.5, "Etzhayyim 2y→1y (オランダ拠点 + 01 Zen 既存資産活用)"),
        ("23_jk_wellness_lab", 0.6, "JK Wellness 1.5y→0.9y (顧客 96→200/月)"),
        ("10_commons_revenue", 0.5, "宿泊業を 2y→1y (集中投資)"),
        ("24_cyber_treaty", 0.6, "サイバー条約 3y→1.8y (経産省 SSS 既登録の追い風)"),
        ("18_ipo_or_foundation", 0.5, "IPO 7y→3.5y (PE/VC 巻き込み)"),
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

    lines.append("**シナリオ S1 (売上加速)**: "
                 "Gftd 営業 + Etzhayyim + JK Wellness + Cyber Treaty で売上 2 倍速 "
                 "→ 借入返済 7y→3.5y")
    dag = build_dag()
    for nid in ["21_gftd_sales_pipeline", "22_etzhayyim",
                "23_jk_wellness_lab", "24_cyber_treaty"]:
        GOALS[nid].est_years *= 0.6
    GOALS["14_loan_acceleration"].est_years *= 0.5
    schedule = cpm_forward(dag)
    s1_dur = max(s["EF"] for s in schedule.values())
    for nid in ["21_gftd_sales_pipeline", "22_etzhayyim",
                "23_jk_wellness_lab", "24_cyber_treaty"]:
        GOALS[nid].est_years /= 0.6
    GOALS["14_loan_acceleration"].est_years /= 0.5
    lines.append(f"  → **{s1_dur:.2f} 年** ({baseline - s1_dur:+.2f} 短縮)\n")

    lines.append("**シナリオ S2 (S1 + 並走)**: "
                 "+ COMMONS 宿泊業を JK Wellness と並走 (10 → 14 エッジを弱める)")
    dag = build_dag()
    for nid in ["21_gftd_sales_pipeline", "22_etzhayyim",
                "23_jk_wellness_lab", "24_cyber_treaty"]:
        GOALS[nid].est_years *= 0.6
    GOALS["14_loan_acceleration"].est_years *= 0.5
    if ("23_jk_wellness_lab", "10_commons_revenue") in dag.edges():
        dag.remove_edge("23_jk_wellness_lab", "10_commons_revenue")
    schedule = cpm_forward(dag)
    s2_dur = max(s["EF"] for s in schedule.values())
    for nid in ["21_gftd_sales_pipeline", "22_etzhayyim",
                "23_jk_wellness_lab", "24_cyber_treaty"]:
        GOALS[nid].est_years /= 0.6
    GOALS["14_loan_acceleration"].est_years /= 0.5
    lines.append(f"  → **{s2_dur:.2f} 年** ({baseline - s2_dur:+.2f} 短縮)\n")

    lines.append("**シナリオ S3 (Aggressive: + IPO 並走)**: "
                 "+ IPO を借入完済前に実施 (一部負債抱えたまま)")
    dag = build_dag()
    for nid in ["21_gftd_sales_pipeline", "22_etzhayyim",
                "23_jk_wellness_lab", "24_cyber_treaty"]:
        GOALS[nid].est_years *= 0.6
    GOALS["14_loan_acceleration"].est_years *= 0.5
    GOALS["18_ipo_or_foundation"].est_years *= 0.5
    if ("23_jk_wellness_lab", "10_commons_revenue") in dag.edges():
        dag.remove_edge("23_jk_wellness_lab", "10_commons_revenue")
    if ("14_loan_acceleration", "18_ipo_or_foundation") in dag.edges():
        dag.remove_edge("14_loan_acceleration", "18_ipo_or_foundation")
    schedule = cpm_forward(dag)
    s3_dur = max(s["EF"] for s in schedule.values())
    for nid in ["21_gftd_sales_pipeline", "22_etzhayyim",
                "23_jk_wellness_lab", "24_cyber_treaty"]:
        GOALS[nid].est_years /= 0.6
    GOALS["14_loan_acceleration"].est_years /= 0.5
    GOALS["18_ipo_or_foundation"].est_years /= 0.5
    lines.append(f"  → **{s3_dur:.2f} 年** ({baseline - s3_dur:+.2f} 短縮)\n")

    lines.append("\n## D. 再婚 / 子供 の到達タイミング (新 DAG)\n")
    dag = build_dag()
    schedule = cpm_forward(dag)
    for nid in ["15_remarriage", "16_more_children"]:
        s = schedule[nid]
        g = GOALS[nid]
        lines.append(
            f"- **{g.title}**: ES={s['EF'] - g.est_years:.2f}y → EF={s['EF']:.2f}y"
        )

    lines.append("\n## E. 結論\n")
    lines.append(
        "- 過剰なブロッカー除去 + 売上加速ノード追加で **19.25y → 17.50y** (-1.75y)\n"
        "- **再婚・子供は CP から外れた** (経済/健康/訴訟を待たない)\n"
        "- 売上ノード 4 件 (Gftd/Etzhayyim/JK Wellness/Treaty) の達成で借入返済を半減 → "
        "**S1 シナリオで 12-14 年圏**\n"
        "- IPO も並走させる S3 では更に短縮可能\n"
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
