"""
kawasakijun/reverse_topo_pregel.py

現状 (X_now) → 理想 (X_star) を達成するための、
依存関係 DAG の **逆トポロジカルソート** + **Pregel BSP** プランナー。

設計思想:
  1. ゴール DAG (gap_analysis.md §4) を node + edge で記述
  2. 逆トポロジカル順 = 末端 (依存先) から実行
  3. Pregel 風 super-step で「完了 → 依存解除」を伝播
  4. budget / 外部依存 / 代替経路を考慮
  5. 各ノードを (τ-scale, domain, est_years) でタグ付け
  6. W(τ) への寄与をスコア化

`pregel_planner.py` が「日次ループの最適化」だったのに対し、
本ファイルは「人生 10–20 年の経路探索」を担う。

依存:
  pip install langgraph langchain-core networkx
"""

from __future__ import annotations

import argparse
import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Annotated, Literal, TypedDict

try:
    import networkx as nx
except ImportError as e:
    raise SystemExit("networkx が必要: `pip install networkx`") from e

try:
    from langgraph.graph import END, START, StateGraph
except ImportError as e:
    raise SystemExit("langgraph が必要: `pip install langgraph`") from e


TauScale = Literal["week", "month", "year", "decade", "generation"]
Domain = Literal[
    "physics", "fiction", "research", "infra",
    "health", "relations", "spirit", "social", "did",
]


@dataclass
class Goal:
    id: str
    title: str
    tau: TauScale
    domain: Domain
    est_years: float
    W_contribution: float  # 0..1 — W(τ) への期待寄与
    external_dependency: str = ""  # 外部依存の説明 (相手・市場・裁判所など)
    fallback: str = ""  # 代替経路


# === ゴール定義 (gap_analysis.md §5 の 20 ノード + Phase 2.1 で追加 4 ノード) ===
# Phase 2.1 更新 (2026-05):
#   - 過剰モデリング除去: (14_loan, 15_remarriage), (12_legal, 13_meeting),
#     (12_legal, 11_health), (11_health, 15_remarriage) を削除
#     → 再婚は経済・健康にゲートされない (ブロッカー除去)
#   - 売上加速ノード 4 件追加 (21–24): Gftd 営業 / etzhayyim / JK Wellness /
#     サイバー条約 — 借入返済の est_years を短縮する原資
GOALS: dict[str, Goal] = {
    "01_aishi_disclosure": Goal(
        "01_aishi_disclosure", "アイシステム送金履歴の完全開示",
        "month", "social", 0.25, 0.10,
    ),
    "02_2021_reconcile": Goal(
        "02_2021_reconcile", "2021年 預かり資金移動 全件突合",
        "month", "social", 0.5, 0.15,
    ),
    "03_lean_mathlib": Goal(
        "03_lean_mathlib", "Lean4 mathlib v4.25 互換性検証",
        "month", "physics", 0.25, 0.05,
    ),
    "04_accounting_proof": Goal(
        "04_accounting_proof", "経理データ整合性証明書 (ZeLo + TOTAL 連名)",
        "month", "social", 0.5, 0.20,
    ),
    "05_adr_0003": Goal(
        "05_adr_0003", "ADR-0003 + GenerativeStructure 完成",
        "month", "physics", 0.5, 0.10,
    ),
    "06_lingling_brief": Goal(
        "06_lingling_brief", "LingLing 準備書面 完成 + 期日対応",
        "month", "social", 1.0, 0.25,
    ),
    "07_arxiv_submit": Goal(
        "07_arxiv_submit", "Wheeler-DeWitt 検証 → arXiv 投稿",
        "year", "physics", 1.5, 0.20,
    ),
    "08_gftd_breakeven": Goal(
        "08_gftd_breakeven", "Gftd Japan 月次黒字化 (営業案件積上げ)",
        "year", "infra", 1.5, 0.30,
    ),
    "09_ghosthacker_scenarios": Goal(
        "09_ghosthacker_scenarios", "Ghost Hacker 残巻シナリオ (3–8 巻)",
        "year", "fiction", 2.0, 0.15,
    ),
    "10_commons_revenue": Goal(
        "10_commons_revenue", "COMMONS 宿泊業 営業 CF 達成",
        "year", "social", 2.0, 0.20,
    ),
    "11_health_steady": Goal(
        "11_health_steady", "健康指標安定化 (通院隔月化)",
        "year", "health", 1.0, 0.25,
        external_dependency="医師判断",
    ),
    "12_legal_offload": Goal(
        "12_legal_offload", "訴訟負荷の弁護団移譲 + 自己時間確保",
        "year", "social", 1.5, 0.20,
    ),
    "13_meeting_partners": Goal(
        "13_meeting_partners", "出会いの場形成 (国際出張 / コミュニティ)",
        "year", "relations", 2.0, 0.20,
        fallback="既存ネットワーク内の再評価",
    ),
    "14_loan_acceleration": Goal(
        "14_loan_acceleration", "借入返済加速 + COMMONS 貸付段階的回収",
        "decade", "social", 7.0, 0.40,
    ),
    "15_remarriage": Goal(
        "15_remarriage", "再婚",
        "decade", "relations", 3.0, 0.50,
        external_dependency="パートナー",
        fallback="事実婚 / パートナーシップ",
    ),
    "16_more_children": Goal(
        "16_more_children", "子供 +2–3 人 (合計 3–4 人)",
        "decade", "relations", 5.0, 0.50,
        external_dependency="パートナー + 健康",
        fallback="養子 / 教育投資による次世代育成",
    ),
    "17_internal_control": Goal(
        "17_internal_control", "内部統制 + 監査法人選定",
        "decade", "infra", 3.0, 0.20,
    ),
    "18_ipo_or_foundation": Goal(
        "18_ipo_or_foundation", "Gftd IPO or 持続可能財団化",
        "decade", "infra", 7.0, 0.40,
        external_dependency="市場環境",
        fallback="持続可能財団化",
    ),
    "19_ghosthacker_film": Goal(
        "19_ghosthacker_film", "Ghost Hacker 映像化",
        "decade", "fiction", 5.0, 0.25,
        external_dependency="制作会社",
    ),
    "20_zen_oss": Goal(
        "20_zen_oss", "01 Zen OSS 公開 + 非分離コード共有",
        "decade", "spirit", 4.0, 0.20,
    ),

    # === Phase 2.1 追加: 売上加速ノード ===
    "21_gftd_sales_pipeline": Goal(
        "21_gftd_sales_pipeline",
        "Gftd Japan 営業パイプライン拡大 (厚労省 / Apple / 鹿大 / 理研 横展開)",
        "year", "infra", 1.5, 0.35,
    ),
    "22_etzhayyim": Goal(
        "22_etzhayyim",
        "Etzhayyim (Tree of Life) — オランダ拠点 spirit-tech 事業立ち上げ",
        "year", "spirit", 2.0, 0.25,
    ),
    "23_jk_wellness_lab": Goal(
        "23_jk_wellness_lab",
        "JK Wellness Research Lab (COMMONS 改称) 集中投資 + 単価向上",
        "year", "social", 1.5, 0.25,
    ),
    "24_cyber_treaty": Goal(
        "24_cyber_treaty",
        "US×JP Mutual Cyber Security Treaty 提案 → 政府案件化",
        "decade", "infra", 3.0, 0.35,
        external_dependency="日米政府",
        fallback="経産省 / 防衛省 単独案件化",
    ),
}


# === 依存エッジ (prerequisite → dependent) ===
# Phase 2.1 で削除した過剰エッジ:
#   - (14_loan_acceleration, 15_remarriage)  ← 再婚は経済にゲートされない
#   - (12_legal_offload, 13_meeting_partners) ← 出会いは訴訟解決を待たない
#   - (12_legal_offload, 11_health_steady)   ← 健康は訴訟解決を待たない
#   - (11_health_steady, 15_remarriage)      ← 再婚は完璧な健康を要求しない
#   - (06_lingling_brief, 10_commons_revenue) ← COMMONS 宿泊業は並走可能
EDGES: list[tuple[str, str]] = [
    # 訴訟チェイン (証拠 → 書面)
    ("01_aishi_disclosure", "02_2021_reconcile"),
    ("02_2021_reconcile", "04_accounting_proof"),
    ("04_accounting_proof", "06_lingling_brief"),
    ("06_lingling_brief", "12_legal_offload"),

    # 売上 → 借入返済原資
    ("08_gftd_breakeven", "14_loan_acceleration"),
    ("10_commons_revenue", "14_loan_acceleration"),
    ("21_gftd_sales_pipeline", "08_gftd_breakeven"),
    ("23_jk_wellness_lab", "10_commons_revenue"),
    ("22_etzhayyim", "14_loan_acceleration"),
    ("24_cyber_treaty", "14_loan_acceleration"),

    # 借入返済 → IPO (内部統制と並列)
    ("14_loan_acceleration", "18_ipo_or_foundation"),
    ("08_gftd_breakeven", "17_internal_control"),
    ("17_internal_control", "18_ipo_or_foundation"),

    # 再婚チェイン (出会いだけが前提)
    ("13_meeting_partners", "15_remarriage"),
    ("15_remarriage", "16_more_children"),

    # 物理 / fiction / spirit
    ("03_lean_mathlib", "05_adr_0003"),
    ("05_adr_0003", "07_arxiv_submit"),
    ("09_ghosthacker_scenarios", "19_ghosthacker_film"),
    ("07_arxiv_submit", "20_zen_oss"),
    ("22_etzhayyim", "20_zen_oss"),
]


def build_dag() -> nx.DiGraph:
    g = nx.DiGraph()
    for goal in GOALS.values():
        g.add_node(goal.id, goal=goal)
    g.add_edges_from(EDGES)
    if not nx.is_directed_acyclic_graph(g):
        cycle = nx.find_cycle(g)
        raise RuntimeError(f"DAG にサイクル: {cycle}")
    return g


# === Pregel state ===
class PlanState(TypedDict):
    completed: list[str]
    pending: list[str]
    super_step: int
    cumulative_years: float
    budget_years: float
    log: list[dict]
    halted: bool


def init_state(budget_years: float, dag: nx.DiGraph) -> PlanState:
    return {
        "completed": [],
        "pending": list(GOALS.keys()),
        "super_step": 0,
        "cumulative_years": 0.0,
        "budget_years": budget_years,
        "log": [],
        "halted": False,
    }


def ready_nodes(state: PlanState, dag: nx.DiGraph) -> list[str]:
    """前提を満たしたゴール (= 全 prerequisites が completed)。"""
    completed = set(state["completed"])
    return [
        nid for nid in state["pending"]
        if all(p in completed for p in dag.predecessors(nid))
    ]


def parallel_executable(ready: list[str], state: PlanState) -> list[str]:
    """同一 super-step で並走可能なゴール選択 (年予算内で)。"""
    remaining_budget = state["budget_years"] - state["cumulative_years"]
    ranked = sorted(
        ready,
        key=lambda nid: GOALS[nid].W_contribution / max(GOALS[nid].est_years, 0.1),
        reverse=True,
    )
    return [nid for nid in ranked if GOALS[nid].est_years <= remaining_budget]


def super_step(state: PlanState) -> PlanState:
    dag = build_dag()
    ready = ready_nodes(state, dag)
    selected = parallel_executable(ready, state)
    if not selected:
        state["log"].append({
            "step": state["super_step"],
            "event": "halt_no_progress",
            "ready_but_unaffordable": ready,
            "still_pending": list(state["pending"]),
        })
        state["halted"] = True
        return state
    step_duration = max(GOALS[n].est_years for n in selected)
    completed = set(state["completed"])
    pending = set(state["pending"])
    for nid in selected:
        completed.add(nid)
        pending.discard(nid)
    state["completed"] = list(completed)
    state["pending"] = list(pending)
    state["cumulative_years"] += step_duration
    state["log"].append({
        "step": state["super_step"],
        "cumulative_years": round(state["cumulative_years"], 2),
        "duration": step_duration,
        "executed": selected,
        "ready_pool": ready,
    })
    state["super_step"] += 1
    return state


def should_continue(state: PlanState) -> str:
    if state["halted"]:
        return "end"
    if not state["pending"]:
        return "end"
    if state["cumulative_years"] >= state["budget_years"]:
        return "end"
    if state["super_step"] >= 25:
        return "end"
    return "loop"


def build_graph():
    g = StateGraph(PlanState)
    g.add_node("super_step", super_step)
    g.add_edge(START, "super_step")
    g.add_conditional_edges(
        "super_step", should_continue, {"loop": "super_step", "end": END}
    )
    return g.compile()


def run(budget_years: float = 10.0) -> PlanState:
    dag = build_dag()
    state = init_state(budget_years, dag)
    graph = build_graph()
    return graph.invoke(state)


def reverse_topo_order(dag: nx.DiGraph) -> list[str]:
    """逆トポロジカルソート = 末端 (depended-on) から実行。"""
    return list(nx.topological_sort(dag))


def report_markdown(state: PlanState) -> str:
    dag = build_dag()
    lines = ["# Reverse Topological Plan — 実行レポート\n"]
    lines.append(f"- 累積年数: **{state['cumulative_years']:.2f}** / "
                 f"{state['budget_years']:.1f} 年予算\n")
    lines.append(f"- 完了: **{len(state['completed'])} / {len(GOALS)}** ノード\n")
    lines.append(f"- 残り: {sorted(state['pending'])}\n\n")
    lines.append("## 逆トポロジカル順 (実行順序)\n")
    for i, nid in enumerate(reverse_topo_order(dag), 1):
        g = GOALS[nid]
        status = "✓" if nid in state["completed"] else "○"
        lines.append(
            f"{i:2d}. {status} [{g.tau}/{g.domain}] **{g.title}** "
            f"({g.est_years} 年, W={g.W_contribution})"
        )
        if g.external_dependency:
            lines.append(f"    - 外部依存: {g.external_dependency}")
        if g.fallback:
            lines.append(f"    - 代替: {g.fallback}")
    lines.append("\n## Pregel super-step 実行ログ\n")
    for entry in state["log"]:
        if "executed" in entry:
            lines.append(
                f"- step {entry['step']}: t+{entry['cumulative_years']}yr "
                f"({entry['duration']}yr) → " +
                ", ".join(GOALS[n].title for n in entry["executed"])
            )
        else:
            lines.append(f"- step {entry['step']}: {entry['event']}")
    return "\n".join(lines)


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--budget-years", type=float, default=10.0)
    p.add_argument("--report", choices=["markdown", "json"], default="markdown")
    p.add_argument("--out", default=None)
    args = p.parse_args()

    final = run(budget_years=args.budget_years)
    if args.report == "json":
        out = json.dumps(
            {k: (list(v) if isinstance(v, set) else v) for k, v in final.items()},
            indent=2, ensure_ascii=False,
        )
    else:
        out = report_markdown(final)
    if args.out:
        Path(args.out).write_text(out)
        print(f"wrote {args.out}")
    else:
        print(out)
