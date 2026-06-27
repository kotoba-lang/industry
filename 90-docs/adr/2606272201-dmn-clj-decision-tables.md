---
id: adr-2606272201-dmn-clj-decision-tables
title: "ADR-2606272201: dmn-clj — DMN 決定表と DRG を EDN/Clojure で扱う再利用ライブラリ。model(id-keyed DRG) + validate + 純粋評価器(IExpression/IUnary ports)。third-party dep ゼロの portable .cljc"
status: proposed
doc_type: adr
topic: library-design
authoritative: true
last_verified: 2026-06-27
authoritative_for:
  - DMN(決定モデル記法)を Clojure で第一級の EDN データとして表現する正準モデルの定義
  - dmn-clj の責務境界(model/validate/ports/execute)と host-injected ports の設計
  - ヒットポリシー(:unique/:first/:any/:collect/:priority/:rule-order)の EDN ネイティブな意味論
  - DRG のトポロジカル評価と depends-on 伝播によるコンテキスト合成の方式
  - プロセス成果物の3-org 配置(共通=com-junkawasaki / 公益=etzhayyim / 事業=gftdcojp)
related:
  - orgs/com-junkawasaki/dmn-clj                        # 本 ADR のライブラリ
  - orgs/com-junkawasaki/bpmn-clj                       # 姉妹ライブラリ(business-rule-task の委譲先)
supersedes: []
superseded_by: []
---

# ADR-2606272201: dmn-clj — DMN 決定表と DRG を EDN/Clojure で扱う再利用ライブラリ

**Status**: proposed（実装済み・テスト緑。ports を注入する actor 側は別 PR）
**Date**: 2026-06-27
**Deciders**: Jun Kawasaki

## Context

BPMN の `business-rule-task` は決定ロジックを外部委譲する。OMG の標準的な委譲先は
DMN (Decision Model and Notation) だが、既存の DMN エンジンは (1) FEEL パーサ等の
重い実行時依存を引き込み、(2) 決定表を XML/バイナリの不透明な状態に閉じ込め、
Clojure の `assoc`/`diff`/Datomic と噛み合わない。本リポの方針(portable `.cljc`・
third-party dep ゼロ・host-injected ports — bpmn-clj / koe-clj の先例)に沿う
**DMN を素の EDN として扱う軽量ライブラリ**が無かった。XML 入力は仕様にあるが本ライブ
ラリの主眼はデータ構造と評価器であり、XML I/O は意図的に含めない。

## Decision

`com-junkawasaki/dmn-clj` を新設する。**third-party 実行時依存ゼロ**、全 namespace
`.cljc`(JVM/CLJS/SCI)。責務を4層に分離する:

- **`dmn.model`** — DRG-as-EDN の正準モデル。決定は **id-keyed map**(O(1) 参照)、
  トポロジは `:dmn/requires` エッジが持ち document order に依存しない。threadable
  builder(`drg`/`decision`/`add-decision`/`add-input`/`add-output`/`add-rule`/`requires`)
  とグラフ query(`topo-order` は id 順 Kahn 法で決定的)。
- **`dmn.validate`** — 構造検証。`{:dmn/severity :dmn/code :dmn/id :dmn/msg}` の
  vector を返す純関数。error(when/then アリティ不整合 / 不明 hit-policy / 不明 requires
  id / DRG サイクル)と warn(:unique 複数 wildcard ルール)を分離。`valid?` は error
  無しで真。
- **`dmn.ports`** — `IExpression`(`eval-input`: 入力式 + コンテキスト → 値)と
  `IUnary`(`eval-cell`: セル文字列 + 入力値 → boolean)の2プロトコル。kernel は
  SDK/IO/資格情報を持たない。
- **`dmn.execute`** — **純粋評価器**。`evaluate`(ports drg decision-id context) が
  DRG をトポ順に歩き、各依存決定の出力をコンテキストにマージしてから対象決定を評価。
  ヒットポリシー: `:unique`(1件; >1 でエラーデータ)、`:first`(行順先頭)、`:any`
  (先頭を返す)、`:collect`(全件ベクタ)、`:priority`/`:rule-order`(行順)。
  `default-ports` でホスト無しでも動く(wildcard / 比較演算子 / 範囲 / カンマ論理和)。

## Rationale

- **データ第一**: 決定表が EDN なので生成・差分・バージョニング・Datomic 格納が自明。
  XML/バイナリの不透明ストアを持たない。
- **依存ゼロ × 可搬**: FEEL 等の重い式言語パーサは ports で注入する形にし、kernel は
  WASM/SCI host でも動く。XML I/O は bpmn-clj と異なり最初から対象外(EDN-first)。
- **host-injected ports**: bpmn-clj と同じ分離。オーケストレーションのみが kernel に
  残り、式評価は host 責務 → fixture ports でオフラインテスト可能。
- **決定的グラフ**: Kahn 法の各ステップで id をソートすることで実行順とトレースを再現的にする。
- **bpmn-clj との対称性**: 同一の key 命名規約(`dmn/*`)・builder パターン・validate
  構造・default-ports 方式を踏襲し、2ライブラリを同じ学習コストで扱える。

## Consequences

- `default-ports` の FEEL サブセットは wildcard / 四則比較 / 数値範囲 / カンマ論理和に
  限定。完全 FEEL(date/time/list 演算・context 投影等)は host が注入する実装で対応する。
- `:collect`/`:rule-order` の出力は vector — 単一 map を期待するコードは型を確認する。
- OR-join(inclusive gateway join) 相当の意味論は DRG には無いため対象外。
- 最初の消費者は別 org の actor が ports を注入して実決定サービスを駆動する
  (本ライブラリにドメインルールは入れない)。

## Verification

`clojure -X:test` 緑(12 tests / 38 assertions)。:unique 単一マッチ・wildcard `-`・
:collect 全件・比較演算子セル・範囲セル(4括弧バリアント)・:requires による出力マージ・
DRG サイクル検出・when/then アリティ検証・カンマ論理和・:unique 複数マッチエラー・
valid?/unknown-requires を確認。
