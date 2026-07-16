---
id: adr-2606272206-states-clj-asl-state-machine
title: "ADR-2606272206: states-clj — AWS Step Functions ASL (Amazon States Language) を EDN/Clojure データとして扱う再利用ライブラリ。model(name-keyed map) + json(from/to-data) + validate + 純粋ステートマシンインタプリタ(ITask port)。third-party dep ゼロの portable .cljc"
status: proposed
doc_type: adr
topic: library-design
authoritative: true
last_verified: 2026-06-27
authoritative_for:
  - AWS Step Functions ASL(Amazon States Language)を Clojure で第一級の EDN データとして表現する正準モデルの定義
  - states-clj の責務境界(モデル/JSON I/O/検証/実行)と host-injected ITask port の設計
  - choice ルール(compound :and/:or/:not + 型別比較演算子)の EDN 表現と評価戦略
  - 成果物の3-org 配置(共通=com-junkawasaki / 公益=etzhayyim / 事業=gftdcojp)
related:
  - orgs/kotoba-lang/states                       # 本 ADR のライブラリ
  - orgs/kotoba-lang/org-omg-bpmn                        # 同型の再利用 kernel(host-injected ports の先例)
  - orgs/kotoba-lang/org-omg-dmn                         # 同型の再利用 kernel(decision table)
  - orgs/kotoba-lang/koe                         # host-injected ports パターンの起源
supersedes: []
superseded_by: []
---

# ADR-2606272206: states-clj — AWS ASL を EDN/Clojure で扱う再利用ライブラリ

**Status**: proposed（実装済み・テスト緑。ITask を注入する actor 側は別 PR）
**Date**: 2026-06-27
**Deciders**: Jun Kawasaki

## Context

AWS Step Functions の Amazon States Language (ASL) を成果物として扱う需要がある。
既存の AWS SDK や Step Functions ローカル実行ツールは
(1) JVM 専用の重い依存や外部プロセス起動を要し、(2) ステートマシンを JSON/DynamoDB の
不透明な状態に閉じ込め、Clojure の `assoc`/`diff`/Datomic と噛み合わない。
本リポの方針（shallow 既定・大容量 dep 回避・portable `.cljc`・host-injected ports —
koe-clj / bpmn-clj / dmn-clj の先例）に沿う、**ASL を素の EDN として扱う軽量ライブラリ**が無かった。

## Decision

`com-junkawasaki/states-clj` を新設する。**third-party 実行時依存ゼロ**、全 namespace
`.cljc`（JVM/CLJS/SCI）。責務を4層に分離する:

- **`states.model`** — ASL-as-EDN の正準モデル。state は **name-keyed map**（O(1) 参照）、
  トポロジは `:states/next` / `:states/choices` が持ち document order に依存しない。
  ヘルパ query（`state-ids`/`state`/`start-at`/`terminal?`）と型分類定数。
  型: `:task` `:choice` `:pass` `:wait` `:succeed` `:fail` `:parallel` `:map`。
- **`states.json`** — ASL JSON ⇄ EDN。**`from-data`** は既解析 Clojure map（string key）を
  namespaced EDN に変換。**`to-data`** は逆。キー対応: `"StartAt"` ↔ `:states/start-at`、
  `"Type"` ↔ `:states/type`（keyword lowercase）、choice 演算子は `:states/op`/`:states/value`
  ペアへ正規化（`NumericEquals`→`:numeric-equals` 等）。compound は `:states/and`/`:states/or`/`:states/not`（再帰）。
- **`states.validate`** — 構造検証。`{:states/severity :states/code :states/id :states/msg}` の
  vector を返す純関数。error（StartAt 欠落/未定義・Next/Default/choice-Next の無効参照・
  到達可能な terminal state が無い）と warn（choice に Default 無し・到達不能 state）を分離、
  `valid?` は error 無しで真。
- **`states.execute` + `states.ports`** — **純粋ステートマシンインタプリタ**。state は素データで
  inspectable/replayable。host が `ITask`（run: state-map→input→output）を注入。
  choice は `$.field` / `$.a.b` パス getter で data を参照し first-match で routing。
  `:parallel` は全 branch を sub-interpret して vector に収集。`default-ports`（identity task）で
  host 無しでも制御フローを動かせる。

## Rationale

- **データ第一**: ステートマシンが EDN なので生成・差分・バージョニング・Datomic 格納が自明。
  Step Functions の外部状態(DynamoDB/Execution history)に依存しない。
- **依存ゼロ × 可搬**: 重い AWS SDK dep を避ける本リポ方針と、WASM/SCI host での実行要件を両立。
  JSON の解析は host の任意ライブラリに委ねる（from-data は already-parsed map を受け取る設計）。
- **host-injected ports**: koe-clj / bpmn-clj と同じ分離。kernel は AWS 資格情報/Lambda SDK を
  持たず、オーケストレーションだけが純粋に残る → オフラインで fixture ITask によりテスト可能。
- **choice rule の正規化**: ASL は同一 rule map に `Variable`/`NumericEquals`/`Next` を平置きする
  混在構造だが、EDN では `:states/op`/`:states/value` ペアへ正規化し compound を再帰構造とすることで
  クエリ・変換が均質になる。

## Consequences

- 本インタプリタは ASL の**実行セマンティクス近似**であり、AWS 本番 Step Functions の完全互換ではない
  (Retry/Catch/Activity/Event-driven 等は未実装)。制御フロー routing とデータ変換の検証用途に適する。
- `:map` state のイテレータ並行実行は逐次で近似（ASL の MaxConcurrency 未対応）。
- 最初の消費者は別 org（公益=etzhayyim / 事業=gftdcojp）の actor が ITask ports を注入して
  実ワークフローを駆動する（本ライブラリにドメインは入れない）。

## Verification

`clojure -X:test` 緑（14 tests / 34 assertions）。線形 task chain・choice 数値ルーティング・
choice default fallback・:and/:or/:not の複合 choice・parallel branch 収集・fail 終端・
pass result merge・JSON round-trip・validate エラー/警告（Next 欠落・到達不能・default 無し）・
warnings のみでも `valid?` = true を確認。
