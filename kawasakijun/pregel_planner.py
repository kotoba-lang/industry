"""
kawasakijun/pregel_planner.py

LangGraph Pregel ベースの人生経路プランナー。

設計:
  - vertex = 活動領域 (physics / fiction / research / infra / health / relations / spirit)
  - super-step = τ ∈ {day, week, month, year, decade, generation, cosmos}
  - message    = {I, R, O, cost} (領域間の影響)
  - state      = 各 τ・各 vertex の W(τ) と推奨アクション

LangGraph の StateGraph は内部で Pregel-inspired BSP を実行する。
ここでは明示的に Pregel として書くため、`langgraph.pregel.Pregel` の
低レベル API を使う構成 (代替として StateGraph でも同等のことが可能)。

参考: 0.md の τ-CMMI プロセスと W(τ) = α·I + β·R + γ·O。
"""

from __future__ import annotations

import json
import math
import os
from dataclasses import dataclass, field
from pathlib import Path
from typing import Annotated, Any, Literal, TypedDict

# LangGraph の低レベル Pregel と StateGraph どちらでも書ける。
# ここでは依存を軽くするため、StateGraph で BSP を模倣しつつ、
# Pregel super-step を τ レイヤーに割り当てる。
try:
    from langgraph.graph import END, START, StateGraph
    from langgraph.channels import LastValue, Topic
except ImportError as e:
    raise SystemExit(
        "langgraph が必要: `pip install langgraph langchain-core`"
    ) from e


HERE = Path(__file__).parent
PROFILE = json.loads((HERE / "profile.jsonld").read_text())
ACTIVITIES = json.loads((HERE / "activities.jsonld").read_text())
ROADMAP = json.loads((HERE / "roadmap.jsonld").read_text())


TauScale = Literal[
    "day", "week", "month", "year", "decade", "generation", "cosmos"
]
TAU_ORDER: list[TauScale] = [
    "day", "week", "month", "year", "decade", "generation", "cosmos"
]

DOMAINS = [
    "physics", "fiction", "research", "infra",
    "health", "relations", "spirit",
    "social", "did",
]

INFLUENCE: dict[str, list[str]] = {
    "physics":   ["fiction", "research", "spirit"],
    "fiction":   ["relations", "spirit", "infra"],
    "research":  ["physics", "infra", "health"],
    "infra":     ["research", "fiction", "did"],
    "health":    ["research", "relations", "spirit"],
    "relations": ["fiction", "health", "spirit", "social"],
    "spirit":    ["physics", "fiction", "relations", "social"],
    "social":    ["relations", "spirit", "did", "infra"],
    "did":       ["infra", "social", "research"],
}


@dataclass
class VertexState:
    domain: str
    I: float = 0.0
    R: float = 0.0
    O: float = 0.0
    actions: list[str] = field(default_factory=list)
    inbox: list[dict[str, float]] = field(default_factory=list)

    def W(self, w: dict[str, float]) -> float:
        return w["alpha"] * self.I + w["beta"] * self.R + w["gamma"] * self.O


class PlannerState(TypedDict):
    tau: TauScale
    tau_idx: int
    vertices: dict[str, VertexState]
    W_history: list[dict[str, float]]
    horizon: int
    super_step: int


def _init_vertices() -> dict[str, VertexState]:
    """activities.jsonld からの impact を集約して初期値に。"""
    v = {d: VertexState(domain=d) for d in DOMAINS}
    for item in ACTIVITIES["itemListElement"]:
        d = item.get("domain")
        imp = item.get("impact", {})
        if d in v:
            v[d].I += imp.get("I", 0.0)
            v[d].R += imp.get("R", 0.0)
            v[d].O += imp.get("O", 0.0)
    # 正規化 (各 vertex を [0,1] レンジに緩く)
    for vs in v.values():
        vs.I = math.tanh(vs.I)
        vs.R = math.tanh(vs.R)
        vs.O = math.tanh(vs.O)
    return v


def _goals_for_tau(tau: TauScale) -> dict[str, Any]:
    for entry in ROADMAP["itemListElement"]:
        if entry["tau"] == tau:
            return entry
    return {"goals": [], "drivers": {}}


def step_vertex(state: PlannerState, domain: str) -> dict[str, Any]:
    """1 super-step での 1 vertex 更新 (Pregel の compute() に相当)。"""
    vs = state["vertices"][domain]
    tau = state["tau"]
    w = PROFILE["kj:valueFunction"]["weights"]

    # 受信メッセージを統合
    for msg in vs.inbox:
        vs.I += 0.10 * msg.get("I", 0.0)
        vs.R += 0.15 * msg.get("R", 0.0)
        vs.O += 0.10 * msg.get("O", 0.0)
    vs.inbox.clear()

    # τ レイヤーの目標から駆動
    goals = _goals_for_tau(tau)
    relevant = [g for g in goals["goals"] if domain in g.lower()
                or domain == "spirit" and "霊" in g]
    if relevant:
        vs.actions.extend(relevant)
        vs.O += 0.05 * len(relevant)

    # 飽和
    vs.I = max(-1.0, min(1.0, vs.I))
    vs.R = max(-1.0, min(1.0, vs.R))
    vs.O = max(-1.0, min(1.0, vs.O))

    # 隣接 vertex にメッセージ送信
    out_msg = {"I": vs.I * 0.2, "R": vs.R * 0.2, "O": vs.O * 0.2}
    for nb in INFLUENCE.get(domain, []):
        state["vertices"][nb].inbox.append(out_msg)

    return {"vertices": state["vertices"]}


def super_step(state: PlannerState) -> PlannerState:
    """全 vertex を 1 τ 分だけ進める (BSP の 1 ステップ)。"""
    for d in DOMAINS:
        step_vertex(state, d)

    w = PROFILE["kj:valueFunction"]["weights"]
    W_snapshot = {d: state["vertices"][d].W(w) for d in DOMAINS}
    W_snapshot["_total"] = sum(W_snapshot.values()) / len(DOMAINS)
    W_snapshot["_tau"] = state["tau"]
    state["W_history"].append(W_snapshot)

    state["super_step"] += 1
    # τ を昇順に進める
    if state["super_step"] % 3 == 0 and state["tau_idx"] < len(TAU_ORDER) - 1:
        state["tau_idx"] += 1
        state["tau"] = TAU_ORDER[state["tau_idx"]]

    return state


def should_continue(state: PlannerState) -> str:
    if state["super_step"] >= state["horizon"]:
        return "end"
    if state["tau"] == "cosmos" and state["super_step"] >= state["horizon"] - 1:
        return "end"
    return "loop"


def build_graph():
    g = StateGraph(PlannerState)
    g.add_node("super_step", super_step)
    g.add_edge(START, "super_step")
    g.add_conditional_edges(
        "super_step", should_continue, {"loop": "super_step", "end": END}
    )
    return g.compile()


def run(tau_start: TauScale = "day", horizon: int = 12) -> PlannerState:
    initial: PlannerState = {
        "tau": tau_start,
        "tau_idx": TAU_ORDER.index(tau_start),
        "vertices": _init_vertices(),
        "W_history": [],
        "horizon": horizon,
        "super_step": 0,
    }
    graph = build_graph()
    final = graph.invoke(initial)
    return final


def report(state: PlannerState) -> str:
    lines = ["# Pregel 計画レポート\n"]
    lines.append(f"super-steps: {state['super_step']}  final τ: {state['tau']}\n")
    lines.append("## W(τ) 推移 (平均)\n")
    for snap in state["W_history"]:
        lines.append(
            f"- τ={snap['_tau']}  W̄={snap['_total']:+.3f}  "
            + "  ".join(f"{d}={snap[d]:+.2f}" for d in DOMAINS)
        )
    lines.append("\n## 各 domain の推奨アクション\n")
    for d in DOMAINS:
        vs = state["vertices"][d]
        if vs.actions:
            lines.append(f"### {d}")
            for a in dict.fromkeys(vs.actions):
                lines.append(f"- {a}")
    return "\n".join(lines)


if __name__ == "__main__":
    import argparse

    p = argparse.ArgumentParser()
    p.add_argument("--tau", default="day", choices=TAU_ORDER)
    p.add_argument("--horizon", type=int, default=12)
    p.add_argument("--out", default=None, help="Markdown 出力先")
    args = p.parse_args()

    final = run(tau_start=args.tau, horizon=args.horizon)
    text = report(final)
    if args.out:
        Path(args.out).write_text(text)
        print(f"wrote {args.out}")
    else:
        print(text)
