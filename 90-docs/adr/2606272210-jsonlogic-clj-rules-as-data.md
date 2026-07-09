---
id: adr-2606272210-jsonlogic-clj-rules-as-data
title: "ADR-2606272210: jsonlogic-clj — JSONLogic の rules-as-data を EDN/Clojure で評価する再利用ライブラリ。core(演算子評価器) + validate + host-injectable custom-op ports(IOp)。third-party dep ゼロの portable .cljc"
status: proposed
doc_type: adr
topic: library-design
authoritative: true
last_verified: 2026-06-27
authoritative_for:
  - JSONLogic(データとしてのルール)を Clojure で評価する正準的な演算子セマンティクスの定義
  - jsonlogic-clj の責務境界(core/validate/ports)と host-injected custom-operator(IOp)の設計
  - JSON パーサ出力(string-keyed map)をそのまま評価対象とする「テキストを解析しない」方針
  - JSONLogic 流の真偽値・緩い等価(==)と厳密等価(===)の意味論
  - 成果物の3-org 配置(共通=com-junkawasaki / 公益=etzhayyim / 事業=gftdcojp)
related:
  - orgs/com-junkawasaki/jsonlogic-clj                  # 本 ADR のライブラリ
  - orgs/com-junkawasaki/dmn-clj                        # 姉妹(決定表)— ルール評価の上位構造
  - orgs/com-junkawasaki/policy-clj                     # 姉妹(ABAC/RBAC)— ルール評価の認可特化
supersedes: []
superseded_by: []
---

# ADR-2606272210: jsonlogic-clj — JSONLogic rules-as-data を EDN/Clojure で評価する再利用ライブラリ

**Status**: proposed（実装済み・テスト緑。custom-op を注入する actor 側は別 PR）
**Date**: 2026-06-27
**Deciders**: Jun Kawasaki

## Context

feature flag・targeting・pricing・eligibility・routing 等で「ルールをデータとして
配り、サーバ/クライアント双方で同じ評価をする」需要がある。デファクトの可搬フォーマットは
[JSONLogic](https://jsonlogic.com)(ルールが純粋な JSON データ)だが、既存実装は
言語ごとにバラつき、Clojure では (1) JS ライブラリへの依存や (2) ルールを独自 AST に
変換して `assoc`/`diff` と噛み合わなくなる嫌いがあった。本リポの方針(portable `.cljc`・
third-party dep ゼロ・host-injected ports — bpmn-clj / dmn-clj の先例)に沿う、
**JSONLogic を素のデータのまま評価する軽量ライブラリ**が無かった。

## Decision

`com-junkawasaki/jsonlogic-clj` を新設する。**third-party 実行時依存ゼロ**、全 namespace
`.cljc`(JVM/CLJS/SCI)。ルールは **JSON パーサが返す string-keyed map のまま**評価対象と
し、テキスト(JSON)の解析はしない(bpmn.xml が neutral element を受けるのと同型)。責務を
3層に分離する:

- **`jsonlogic.core`** — 演算子評価器。`apply-logic`(rule data → 値; 組込み演算子のみ)と
  `run`(ports rule data → 値; host の custom-op を許す)。非 map(リテラル/数値/真偽/
  vector)は自身に評価。1キー map = 演算。実装演算子: `var`(ドット/添字パス・default・
  空→全体)、`missing`/`missing_some`、`==`(数値強制の緩い等価)/`===`(厳密)/`!=`/`!==`、
  `!`/`!!`、`and`/`or`(短絡・JSONLogic の戻り値規約)、`if`/`?:`、`>`/`>=`/`<`/`<=`
  (`<` `<=` は3引数 between)、`+`/`-`/`*`/`/`/`%`、`min`/`max`、`in`(部分文字列/配列)、
  `cat`/`substr`/`merge`、`map`/`filter`/`reduce`/`all`/`some`/`none`。真偽値は JSONLogic
  準拠(`0`/`""`/`[]`/`null` は偽)。
- **`jsonlogic.validate`** — 構造検証。`{:jsonlogic/severity :jsonlogic/code :jsonlogic/id
  :jsonlogic/msg}` の vector を返す純関数。不明演算子・明白なアリティ不整合を検出。
  `valid?` は error 無しで真。
- **`jsonlogic.ports`** — `IOp`(`custom-op?`: 名前 → bool / `apply-op`: 名前 args data
  → 値)。ホストが演算子を拡張するためのプロトコル。`default-ports` は組込みのみ
  (追加 op 無し)。

## Rationale

- **データ第一**: ルールが JSON/EDN データのまま。生成・差分・バージョニング・配信・
  Datomic 格納が自明で、サーバ/エッジ/ブラウザで同一評価を共有できる。
- **依存ゼロ × 可搬**: JS 実装や式言語に依存せず、WASM/SCI host でも同じ意味論で動く。
- **テキストを解析しない**: JSON のパースは host 任せ、本ライブラリは string-keyed map を
  受ける2層構成(bpmn.xml/from-elements と同型)。
- **host-injected ports**: ドメイン固有演算子は `IOp` で注入。kernel に業務ロジックを
  入れず、評価器だけが純粋に残る。
- **姉妹との整合**: dmn-clj(決定表)/policy-clj(ABAC/RBAC)と同じ key 規約・validate
  構造・default-ports 方式を踏襲。ルール評価の3粒度(汎用式/表/認可)を同じ作法で扱える。

## Consequences

- `==` の緩い等価は JSONLogic(JS 流)の数値強制に合わせるため、Clojure の `=` とは挙動が
  異なる(`1 == "1"` は真)。厳密比較が要るときは `===` を使う。
- custom-op を増やすほど可搬性(他言語 JSONLogic との相互運用)は下がる — 拡張は host 側の
  選択として明示する。
- 完全な JSONLogic 互換(全エッジケース)ではなく、実務でよく使う中核演算子セットに限定。
  未対応演算子は validate が報告し、`IOp` で補える。

## Verification

`clojure -X:test` 緑(12 tests / 51 assertions)。`var`(ドットパス+default)・`==` vs
`===`・`and`/`or` の短絡と戻り値・`if`/三項・四則・`min`/`max`・`in`(部分文字列/配列)・
`map`/`filter`/`reduce`・`missing`/`missing_some`・`IOp` 経由の custom-op・unknown-op
検証を確認。
