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
        ("09_ghosthacker_scenarios", "21b_gftd_anime_vertical",
         "アニメ事業を Ghost Hacker IP 完成を待たずに既存 animeka pipeline で先行"),
        ("15_remarriage", "16_more_children",
         "子供を再婚と切り離す (養子 / 共同養育)"),
        ("17_internal_control", "18_family_office_conversion",
         "家族オフィス化を内部統制と並走 (定款変更のみで実行可)"),
        ("08_gftd_breakeven", "14_loan_acceleration",
         "Etzhayyim/Cyber Treaty 単独で返済原資を作る"),
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
        ("14_loan_acceleration", 0.5, "借入返済 7y→3.5y (Gftd law+anime + Etzhayyim + Cyber Treaty)"),
        ("21_gftd_lawfirm_vertical", 0.6, "Gftd 法律事務所バーティカル 1.5y→0.9y (LegalTech + AMT/ZeLo 関係活用)"),
        ("21b_gftd_anime_vertical", 0.6, "Gftd アニメ IP バーティカル 2y→1.2y (Ghost Hacker IP 早期立ち上げ)"),
        ("22_etzhayyim_ops", 0.5, "Etzhayyim 1.5y→0.75y (既存運用; org/monorepo は live)"),
        ("09_ghosthacker_scenarios", 0.5, "Ghost Hacker 残巻シナリオを 2y→1y (集中執筆)"),
        ("24_cyber_treaty", 0.6, "サイバー条約 3y→1.8y (経産省 SSS 既登録の追い風)"),
        ("18_family_office_conversion", 0.5, "Family Office 化 1y→0.5y (株主総会即決+登記)"),
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

    SALES_NODES = ["21_gftd_lawfirm_vertical", "21b_gftd_anime_vertical",
                   "22_etzhayyim_ops", "24_cyber_treaty"]

    lines.append("**シナリオ S1 (売上加速)**: "
                 "Gftd law/anime + Etzhayyim + Cyber Treaty を 0.6 倍速 "
                 "→ 借入返済 7y→3.5y")
    dag = build_dag()
    for nid in SALES_NODES:
        GOALS[nid].est_years *= 0.6
    GOALS["14_loan_acceleration"].est_years *= 0.5
    schedule = cpm_forward(dag)
    s1_dur = max(s["EF"] for s in schedule.values())
    for nid in SALES_NODES:
        GOALS[nid].est_years /= 0.6
    GOALS["14_loan_acceleration"].est_years /= 0.5
    lines.append(f"  → **{s1_dur:.2f} 年** ({baseline - s1_dur:+.2f} 短縮)\n")

    lines.append("**シナリオ S2 (S1 + Ghost Hacker 圧縮)**: "
                 "+ アニメ前提の Ghost Hacker シナリオを集中執筆")
    dag = build_dag()
    for nid in SALES_NODES:
        GOALS[nid].est_years *= 0.6
    GOALS["14_loan_acceleration"].est_years *= 0.5
    GOALS["09_ghosthacker_scenarios"].est_years *= 0.5
    GOALS["21b_gftd_anime_vertical"].est_years *= 0.6
    schedule = cpm_forward(dag)
    s2_dur = max(s["EF"] for s in schedule.values())
    for nid in SALES_NODES:
        GOALS[nid].est_years /= 0.6
    GOALS["14_loan_acceleration"].est_years /= 0.5
    GOALS["09_ghosthacker_scenarios"].est_years /= 0.5
    GOALS["21b_gftd_anime_vertical"].est_years /= 0.6
    lines.append(f"  → **{s2_dur:.2f} 年** ({baseline - s2_dur:+.2f} 短縮)\n")

    lines.append("**シナリオ S3 (Aggressive: + family office 即決)**: "
                 "+ Family Office 化を株主総会即決 (1y→0.5y) + animeka 即時復旧")
    dag = build_dag()
    for nid in SALES_NODES:
        GOALS[nid].est_years *= 0.6
    GOALS["14_loan_acceleration"].est_years *= 0.5
    GOALS["09_ghosthacker_scenarios"].est_years *= 0.5
    GOALS["21b_gftd_anime_vertical"].est_years *= 0.5
    GOALS["18_family_office_conversion"].est_years *= 0.5
    schedule = cpm_forward(dag)
    s3_dur = max(s["EF"] for s in schedule.values())
    for nid in SALES_NODES:
        GOALS[nid].est_years /= 0.6
    GOALS["14_loan_acceleration"].est_years /= 0.5
    GOALS["09_ghosthacker_scenarios"].est_years /= 0.5
    GOALS["21b_gftd_anime_vertical"].est_years /= 0.5
    GOALS["18_family_office_conversion"].est_years /= 0.5
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

    lines.append("\n## E. 結論 (Phase 2.3)\n")
    lines.append(
        "- IPO 削除 + Family Office 化 (ADR-2605111000) + animeka 復旧フォーカス + "
        "lawfirm.gftd.ai 既存運用利用 → ベースライン **12.00y**\n"
        "- **再婚・子供は CP から外れたまま**\n"
        "- 売上 4 軸 + Ghost Hacker 圧縮で **S1/S2 で 7-10 年圏**\n"
        "- S3 で animeka 即時復旧 + family office 株主総会即決 で更に短縮\n"
        "- 新 CP: Ghost Hacker → animeka → Gftd 黒字 → 借入返済 (Family Office 化は並走)\n"
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
