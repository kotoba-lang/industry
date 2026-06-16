---
id: adr-2606141500-keiei-arbor-coscientist-engine
title: "ADR-2606141500: 経営を Arbor HTR × AI Co-Scientist で進める仮説ツリー経営エンジン"
status: active
doc_type: adr
topic: keiei-arbor-coscientist
authoritative: true
last_verified: 2026-06-14
authoritative_for:
  - gftd-keiei-sim の意思決定ループのアーキテクチャ (Arbor HTR サイクル)
  - 経営仮説の永続化スキーマ :keiei.htr/* (datomic)
  - Executor サンドボックス(シミュレーション検証)と Elo トーナメントによるランク付け
  - merge / prune / continue の Decide ゲートと human-in-the-loop 境界
  - 役割分担 (スコア合成式 = Rust / 制御フロー = Clojure)
related:
  - adr-2606130000-gftd-revenue-portfolio-pivot
supersedes: []
superseded_by: []
---

# ADR-2606141500: 経営を Arbor HTR × AI Co-Scientist で進める仮説ツリー経営エンジン

**Status**: accepted
**Date**: 2026-06-14
**Deciders**: Jun Kawasaki

## Context

`gftd-keiei-sim` の経営ループは「各部門エージェント(営業/開発/財務/法務/CEO補佐)が四半期ごとに
施策を1つ提案 → 財務が反論 → CEO補佐が統括 → 人間(CEO)が承認/却下 → `apply_effect` で KPI 反映 →
意思決定台帳に記録」という **線形ターン**だった。提案は揮発的で、案同士の比較も検証も行われず、
過去の判断が次の判断に構造的に引き継がれない(組織記憶が台帳の平坦なログに留まる)。

自律研究の2フレームワークが、この経営ループを「累積的探索」へ格上げする骨格を与える:

- **Arbor** (Hypothesis-Tree Refinement) — 長寿命 Coordinator + 短命 Executor + 仮説の永続ツリー。
  `Ideate → Select → Dispatch → Backpropagate → Decide(merge/prune/continue/pending/stop)` を回し、
  held-out 検証で merge を判定する。
- **AI Co-Scientist** (Google) — Generation / Reflection / Ranking / Evolution / Proximity / Meta-review /
  Supervisor の連合。**Elo トーナメント**でランク付けし、generate-debate-evolve ループで自己改善する。

## Decision

経営判断を **「反証可能な経営仮説」の永続ツリー(HTR)上の探索**として進める。人間(CEO)は
Arbor の **merge ゲート**に残り、既存の承認/却下 UX を壊さない。pruned/continued なノードも
ツリーに残り **組織記憶**(過去の探索木)になる。

### 1. エージェント連合への写像 (既存資産を再利用)

| Arbor / Co-Scientist | keiei | 実装 |
|---|---|---|
| Coordinator(長寿命)/Supervisor | CEO補佐(経営参謀) | `agents/turn.clj` |
| Generation | 営業/開発/財務/法務 | 既存部門エージェント(intel を kqe で接地) |
| Reflection | Reviewer | 既存「財務 critique」 |
| Ranking(Elo) | `htr::tournament` | pending leaf の round-robin self-play |
| Evolution | `node-evolve` (turn.clj) | 上位案を結合・先鋭化した「進化版」を生成 |
| Executor(隔離) | `htr::simulate` | `Kpis` クローン上のフォワードシム(非commit サンドボックス) |

### 2. 1ターン = 1 Arbor サイクル

1. **生成**: 部門エージェントが intel 接地で仮説を提案
2. **進化**: `node-evolve` が上位案を結合・先鋭化した進化版を leaf 化
3. **検証(Executor)**: `htr::simulate` が `Kpis` クローンを **runway 連動ホライズン**でフォワードシムし、
   **dev-score**(名目改善, 億円相当)と **worst-case**(バーン+20%・受注率半減の held-out ストレス)を算出
4. **ランク付け(Elo)**: `htr::tournament` が pending leaf(前ターン生存分を含む)を round-robin self-play
   (3ラウンド = test-time compute ノブ)でランク付け。提案は Elo 降順(= Select 順序)で提示
5. **Decide**: 承認=**merge**(`apply_effect` + 意思決定台帳)/ 却下=**prune** / continue(次ターン再探索)

### 3. 永続化スキーマ (datomic)

`:keiei.htr/*` を新設。ノードは不変属性(id / turn / role / hypothesis / parent / dev-score / worst-case)、
ステータスと Elo は **append-only イベント**(`:keiei.htr.status/*` / `:keiei.htr.elo/*`)で積み、
最新値を畳み込んで復元する(既存 `:gftd.progress/*` と同型)。merge は既存の意思決定台帳 transact +
`apply_effect` が兼ねる。

### 4. 役割分担 (Rust は薄く、ロジックは Clojure)

- **Rust** (`src/htr.rs`): Executor のスコア合成式 + Elo 算術 + HTR の datomic 永続化/クエリ。
  `src/server.rs` がサイクルを配線、`src/model.rs` に evolution 役割の効果。
- **Clojure**: 制御フロー(生成・批評・進化)は `agents/turn.clj`(defgraph)。
- **ClojureScript**: 🌳 仮説ツリータブ(`views.cljs`/`subs.cljs`/`events.cljs` — Elo バー・dev/worst・
  continue/prune)。
- 設計の全体像は `orgs/gftdcojp/gftd-keiei-sim/docs/keiei-arbor-coscientist.md`。

実 API: `POST /api/turn/advance`(1サイクル)/ `GET /api/htr` / `POST /api/htr/:id/{prune,continue}`
(merge は既存 `POST /api/proposal/:id/approve` が兼ねる)。

## Consequences

- (+) 経営判断が比較・検証・進化される。Executor の held-out worst-case が **頑健な手と脆い手を分離**する
  (実測: 低ランウェイ時 finance のコスト削減 worst-case −0.72 vs 増員 eng −7.97)
- (+) 過去の探索木が組織記憶として永続化され、backprop で次の生成にバイアスをかけられる
- (+) 既存の承認/却下 UX・台帳・intel・ファネルを温存(後方互換)。各段は単体で価値を出す
- (−) 現状トーナメントの勝敗は dev-score の決定論比較(LLM judge は未)。Proximity(dedup)と
  Meta-review の明示 backprop も未実装
- (−) Executor の効果モデルは `apply_effect` の固定ヒューリスティック由来。実LLM提案からの構造化
  アクション抽出による動的化が今後の精度の鍵
- リスク: シミュレーションのスコア式が現実から乖離すると merge 判断を誤誘導しうる。held-out を実 facts
  再生シナリオで強化し、compute-budget を stakes(残ランウェイ)連動にするのが次の堅牢化

## References

- Arbor — Toward Generalist Autonomous Research via Hypothesis-Tree Refinement (RUC-NLPIR, arXiv:2606.11926)
- Accelerating scientific breakthroughs with an AI co-scientist (Google Research, 2025)
- 実装 PR: com-junkawasaki/root #47 — `feat(gftd-keiei-sim): 経営を Arbor HTR × AI Co-Scientist で進める仮説ツリー化`
