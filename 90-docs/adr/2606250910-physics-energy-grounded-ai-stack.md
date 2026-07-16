---
id: adr-2606250910-physics-energy-grounded-ai-stack
title: "ADR-2606250910: 物理（エネルギー）接地で AI スタックを統一する — LLM / ReAct ループ / 学習 / ライブラリ / アクタ"
status: active
doc_type: adr
topic: physics-energy-grounded-ai
authoritative: true
last_verified: 2026-06-25
authoritative_for:
  - kotoba AI スタックの設計原理（エネルギーベース／保存量を帰納バイアスにする）
  - LLM 推論・学習を Boltzmann 分布／自由エネルギー最小化として扱う方針 (kotoba-llm)
  - ReAct（reason+act）ループをエネルギー降下／勾配流として実装する方針
  - アクタ基盤の不変量を Hamiltonian/Lagrangian の保存量として定義する方針 (kotoba-kotodama)
  - 学習を contrastive divergence / エネルギー最小化として位置づける方針 (kotoba-llm/train, lora)
related:
  - orgs/kotoba-lang/kotoba/crates/kotoba-llm
  - orgs/kotoba-lang/kotoba/crates/kotoba-kotodama
  - orgs/kotoba-lang/kotoba/crates/kotoba-rt
  - adr-2606241700-kotoba-clj-runtime-kotoba-ext
  - adr-2606141500-keiei-arbor-coscientist-engine
supersedes: []
superseded_by: []
---

# ADR-2606250910: 物理（エネルギー）接地で AI スタックを統一する — LLM / ReAct ループ / 学習 / ライブラリ / アクタ

**Status**: accepted
**Date**: 2026-06-25
**Deciders**: Jun Kawasaki

## Context

参考: CompuFlair「Famous AI Models Secretly Built on Physics」
（YouTube, https://www.youtube.com/watch?v=8VGu8PQSiyo ）。

この動画の主張は「有名な AI モデルは“こっそり”物理の上に建っている」というもの:

- **Hopfield ネットワーク / Boltzmann マシン** — エネルギー関数 \(E(x)\) を持ち、
  状態は低エネルギーへ落ちる。Boltzmann マシンは温度付きの確率
  \(p(x) \propto e^{-E(x)/T}\)（Boltzmann 分布）で学習・生成する初期の生成 AI。
- **拡散モデル（diffusion）** — 非平衡熱力学（流体・気体の拡散）に着想を得て、
  ノイズ注入と逆過程による生成を行う。現代の Hopfield ネットの一種とも見なせる。
- **Hamiltonian / Lagrangian Neural Network** — エネルギー保存則を構造に埋め込み、
  系の運動方程式（保存量）を帰納バイアスとして学習する。

共通項は **「エネルギー」と「保存量」を第一級の設計対象にする** こと。
softmax/cross-entropy はそのまま Boltzmann 分布・自由エネルギーであり、
学習は自由エネルギー最小化、推論は低エネルギー状態へのサンプリングである。

一方、本リポジトリの AI 資産は機能別に分散しており、設計原理が暗黙だった:

- `kotoba-llm` — LLM 推論・学習（`gemma` / `infer` / `embed` / `kvcache` /
  `lora` / `train` / `train_gpu` / `weight`、WebGPU 推論・学習 feature 付き）。
- `kotoba-kotodama` — 分散アクタ基盤（cells / inference / mcp / hosts、
  TS-native + Python worker 層）。ADR-2606241700 で CLJ ランタイムと別系統と確定済み。
- `kotoba-rt` — ランタイム（`sim` / `rollback` / `room` / `p2p` / `wasm_sim`）。
- 経営側には ADR-2606141500 の Arbor HTR（仮説ツリー上の探索ループ）が既にある。

これらを **物理（エネルギー）接地** という単一原理で束ね、LLM・ReAct ループ・
学習・ライブラリ・アクタの境界と語彙を揃える。

## Decision

kotoba AI スタックを **エネルギーベース（保存量を帰納バイアスにする）** 設計で統一する。
各機能を「エネルギー関数 \(E\)・温度 \(T\)・保存量 \(H\)」の語彙に写像し、既存クレートを
再利用したまま意味づけを固定する。

### 1. AI 機能 → 物理アナロジー → クレートの写像

| AI 機能 | 物理アナロジー | 実体（再利用） |
|---|---|---|
| **LLM 推論** | Boltzmann 分布からのサンプリング \(p \propto e^{-E/T}\)（softmax = 低エネルギー選択、temperature = \(T\)） | `kotoba-llm`（`infer` / `gemma` / `kvcache` / `infer_gpu`） |
| **LLM 学習** | 自由エネルギー最小化 / contrastive divergence（cross-entropy = 負の対数尤度 = エネルギー） | `kotoba-llm`（`train` / `train_gpu` / `lora` / `weight`） |
| **ReAct ループ** | エネルギー降下 / 勾配流（観測→反証→低エネルギー化）。Arbor HTR の Decide ゲートと同型 | `kotoba-rt` + 新設の reason+act 制御（下記 §3） |
| **ライブラリ** | 場とポテンシャルの定義群（埋め込み = 配位空間、重み = ポテンシャル） | `kotoba-llm` / `kotoba-edn` / `kotoba-clj`（`.kotoba` ロジック） |
| **アクタ** | Hamiltonian/Lagrangian 系（各アクタが保存量 \(H\) を保ちメッセージで相互作用） | `kotoba-kotodama`（cells / inference / mcp） |

### 2. LLM と学習を「エネルギー」で語る (kotoba-llm)

- 推論の温度 \(T\) を **明示的な制御ノブ**として一級化する（決定論＝低温極限、
  探索＝高温）。`infer` のサンプラに `T` を通す。
- 学習は **自由エネルギー最小化**として記述する。cross-entropy 損失 = エネルギー、
  正則化（LoRA の低ランク制約 = ポテンシャルの平滑化）を「保存量を壊さない更新」
  として位置づける。`train` / `lora` の API ドキュメントにこの語彙を採用する。
- これは**新規実装の強制ではなく語彙と境界の固定**。既存の `softmax` /
  `cross-entropy` / `Adam`（`AdamMoments` / `OptimizerStep`）はそのまま使う。

### 3. ReAct ループをエネルギー降下として実装する

ReAct（**Reason + Act**）の 1 ステップを「現状態のエネルギーを下げる遷移」として定義:

```text
observe → reason(評価 E) → act(遷移) → observe …  while ΔE < 0（または fuel 上限）
```

- **状態**: タスク文脈 + アクタ世界（kotodama）の観測。
- **エネルギー** \(E\): 目標との不一致（未解決サブゴール・反証された仮説）。
- **遷移**: ツール呼び出し（MCP）/ アクタへのメッセージ / `.kotoba` ロジック実行。
- **停止条件**: \(E\) が下がらない（局所最小）か fuel 上限。**温度付きで escape**
  （高温で探索＝Boltzmann 的に局所最小を抜ける）を許す。
- 経営の Arbor HTR（ADR-2606141500）の `Ideate→Select→Dispatch→Backprop→Decide`
  は本ループの**多腕版**であり、Decide ゲート＝merge は「エネルギー最小ノードの採用」。
  両者を同じ語彙（エネルギー・温度・保存量）で記述し、実装を共有可能にする。

### 4. アクタの不変量を保存量として定義する (kotoba-kotodama)

各アクタ（cell / worker）は **保存量 \(H\)** を宣言し、メッセージ処理（相互作用）を
通じて \(H\) を保つ（Hamiltonian 的）か、目標汎関数を単調に下げる（Lagrangian/勾配流的）:

- 例: BeliefStore 系 worker の **整合性不変量**（信念集合の矛盾ゼロ）＝保存量。
- 例: 台帳・残高系の **総量保存**（移動はゼロサム）＝Hamiltonian 保存量。
- これにより「アクタが守るべき不変量」をテスト可能な述語として明文化でき、
  ReAct ループの遷移が保存量を壊さないことを検証ゲートにできる。

### 5. 非ゴール（やらないこと）

- 物理シミュレーション専用エンジン（HNN/LNN の数値積分器）を**新規に作らない**。
  本 ADR は **設計語彙と境界の統一**であって、物理ソルバの導入ではない。
- 既存 API の破壊的リネームはしない。語彙はドキュメント／新規 API から導入する。
- 拡散モデル本体の実装はスコープ外（将来の別 ADR で判断）。

## Consequences

**Pros**

- LLM・ReAct ループ・学習・ライブラリ・アクタが **単一原理（エネルギー／保存量）**で
  語られ、クレート間の境界と責務が一貫する。
- ReAct ループと Arbor HTR が同型（多腕版）と明示され、制御フローと検証ゲートを
  共有できる（実装重複を抑制）。
- アクタの不変量が **保存量**として述語化され、ReAct 遷移の安全性を機械検証できる。
- 温度 \(T\) を一級ノブにすることで「決定論 ↔ 探索」を連続パラメータで制御できる。

**Cons / 留意点**

- アナロジーの過剰適用リスク。エネルギー語彙が実装の厳密な性質（真の保存則）を
  保証するわけではない。**メタファとして使い、保証は個別テストで担保**する。
- 既存コードと新語彙の二重管理が一時的に生じる（ドキュメント追従が必要）。
- ReAct ループの「エネルギー」は多くの場合ヒューリスティック（未解決数など）であり、
  真の勾配ではない。局所最小・停止条件の設計が品質を左右する。

## References

- CompuFlair, "Famous AI Models Secretly Built on Physics", YouTube (2026):
  https://www.youtube.com/watch?v=8VGu8PQSiyo
- Greydanus et al., "Hamiltonian Neural Networks" (NeurIPS 2019)
- Sohl-Dickstein et al., "Deep Unsupervised Learning using Nonequilibrium
  Thermodynamics" (拡散モデルの起源, ICML 2015)
- Hopfield (1982) / Ackley-Hinton-Sejnowski "Boltzmann Machines" (1985)
- AI meets physics: a comprehensive survey, Artificial Intelligence Review (2024)
- 関連 ADR: ADR-2606241700（kotoba CLJ ランタイム）, ADR-2606141500（Arbor HTR 経営エンジン）
