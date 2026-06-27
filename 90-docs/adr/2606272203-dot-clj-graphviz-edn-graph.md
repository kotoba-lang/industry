---
id: adr-2606272203-dot-clj-graphviz-edn-graph
title: "ADR-2606272203: dot-clj — Graphviz DOT 言語を EDN/Clojure データとして扱う再利用ライブラリ。model(id-keyed nodes + ordered edges) + graph algorithms(topo-order/reachable/roots/leaves) + validate + 自前 minimal DOT I/O。third-party dep ゼロの portable .cljc"
status: proposed
doc_type: adr
topic: library-design
authoritative: true
last_verified: 2026-06-27
authoritative_for:
  - Graphviz DOT 言語を Clojure で第一級の EDN データとして表現する正準モデルの定義
  - dot-clj の責務境界(モデル/グラフアルゴリズム/検証/DOT I/O)の設計
  - 大容量・非可搬なパーサ依存を避けるための minimal tokeniser + round-trip emitter 戦略
  - グラフ成果物の3-org 配置(共通=com-junkawasaki / 公益=etzhayyim / 事業=gftdcojp)
related:
  - orgs/com-junkawasaki/dot-clj                         # 本 ADR のライブラリ
  - orgs/com-junkawasaki/svgraph                         # DOT EDN を SVG に描画する姉妹ライブラリ
  - orgs/com-junkawasaki/bpmn-clj                        # 同型の再利用 kernel(設計先例)
  - orgs/com-junkawasaki/koe-clj                         # host-injected ports の先例
supersedes: []
superseded_by: []
---

# ADR-2606272203: dot-clj — Graphviz DOT を EDN/Clojure で扱う再利用ライブラリ

**Status**: proposed（実装済み・テスト緑。SVG 描画は svgraph が担う）
**Date**: 2026-06-27
**Deciders**: Jun Kawasaki

## Context

グラフ構造（依存グラフ・パイプライン・アーキテクチャ図等）を成果物として扱う需要が
あるが、既存の Graphviz クライアントライブラリは (1) JVM 専用の重い native binding
(graphviz-java / batik 等) を引き、(2) グラフを不透明な AST に閉じ込め、Clojure の
`assoc`/`diff`/Datomic と噛み合わない。本リポの方針(shallow 既定・大容量 dep 回避・
portable `.cljc`・host-injected ports — koe-clj / bpmn-clj の先例)に沿う、
**DOT を素の EDN として扱う軽量ライブラリ**が無かった。

また svgraph ライブラリが EDN グラフモデルを入力として想定しており、そのデータ層として
dot-clj が必要とされた。

## Decision

`com-junkawasaki/dot-clj` を新設する。**third-party 実行時依存ゼロ**、全 namespace
`.cljc`(JVM/CLJS/SCI)。責務を3層に分離する:

- **`dot.model`** — DOT-as-EDN の正準モデル。node は **id-keyed map**(O(1) 参照)、
  edge は **ordered vector**(DOT のレンダリング順序を保持)、graph attrs は
  string→string map。threading-friendly builder(`digraph`/`graph`/`node`/`edge`)と
  グラフアルゴリズム群:
  - `out-edges`/`in-edges`/`neighbors`/`successors`/`predecessors` — 隣接クエリ
  - `roots`/`leaves` — 入次数ゼロ/出次数ゼロのノード集合
  - `reachable` — 任意始点からの到達可能集合(BFS)
  - `topo-order` — Kahn アルゴリズム。DAG は id ソートで決定的な vector、閉路があれば
    `{:dot/cycle true}` を返す（例外を投げない）。
- **`dot.validate`** — 構造検証。`{:dot/severity :dot/code :dot/id :dot/msg}` の
  vector を返す純関数。error(辺の演算子誤り: 有向グラフに `--` / 無向グラフに `->`)と
  warn(自己ループ)を分離、`valid?` は error 無しで真。
- **`dot.dot`** — DOT 文字列 ⇄ model。**自前の minimal tokeniser/parser/emitter**:
  - `parse-str`: `strict? (digraph|graph) id { stmts }` の well-formed subset を解析。
    node 文/edge 文(chain `a -> b -> c` 対応)/graph-attr/default-attr ブロック/
    quoted id/`//` ・ `/* */` コメント/任意セミコロンをカバー。
    辺の auto-declaration(未宣言ノードを空 attrs で生成)を実装。
  - `emit-str`: canonical DOT(graph attrs → id ソート済み nodes → 宣言順 edges)で
    `parse-str` を往復する。

## Rationale

- **データ第一**: グラフが EDN なので生成・差分・バージョニング・Datomic 格納が自明。
  AST の不透明 blob を持たない。bpmn-clj と同じ設計哲学を DOT ドメインに適用。
- **依存ゼロ × 可搬**: 重い native Graphviz binding を避ける本リポ方針と、WASM/SCI
  host での実行要件を両立。DOT パーサは「well-formed subset 自前」で十分な用途をカバー。
- **svgraph との役割分担**: dot-clj は EDN グラフモデル（フォーマット + アルゴリズム）、
  svgraph は EDN モデルを受け取って SVG を生成する。レイヤーが明確に分離される。
- **決定的アルゴリズム**: topo-order は同次数ノードを id ソートで破って決定的にする。
  `{:dot/cycle true}` sentinel で例外なしのエラー報告——呼び出し側が nil/map で
  判定できる。

## Consequences

- 自前 DOT tokeniser は well-formed subset 限定(サブグラフ/クラスタ/HTML ラベル/
  ポート記法は非対応)。限界は README/docstring に明記。
- edge の演算子誤り検知は parse 時ではなく validate 時(`:dot/edge-op` フラグ経由)。
  parse-str 自体は寛容に受け入れ、validate で意味検査する設計。
- 最初の消費者は svgraph(描画)と各 org の graph-aware actor が dot-clj モデルを
  データとして操作する用途（本ライブラリにドメインは入れない）。

## Verification

`clojure -X:test` 緑(13 tests / 48 assertions)。`resources/dot/sample.dot` の
parse + attrs 確認、emit→parse round-trip、edge auto-declaration、chain エッジ
`a -> b -> c` の2辺生成、node default-attr 適用、neighbors/successors、topo-order
(DAG + 閉路検出)、validate wrong-operator error、self-loop warn を確認。
