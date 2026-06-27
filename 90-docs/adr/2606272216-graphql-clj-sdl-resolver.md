---
id: adr-2606272216-graphql-clj-sdl-resolver
title: "ADR-2606272216: graphql-clj — GraphQL SDL を EDN スキーマ AST として扱い、host-injected IResolver で純粋クエリ実行する再利用ライブラリ。sdl(トークナイザ) + query(セレクション AST) + validate + ports + execute の5層構成。third-party dep ゼロの portable .cljc"
status: proposed
doc_type: adr
topic: library-design
authoritative: true
last_verified: 2026-06-27
authoritative_for:
  - GraphQL SDL を Clojure で第一級の EDN データとして表現する正準モデルの定義
  - graphql-clj の責務境界(model/sdl/query/validate/ports/execute)と host-injected IResolver の設計
  - 重い GraphQL ランタイム依存を避けるための手書きトークナイザ + 再帰降下パーサ戦略
  - プロセス成果物の3-org 配置(共通=com-junkawasaki / 公益=etzhayyim / 事業=gftdcojp)
related:
  - orgs/com-junkawasaki/graphql-clj                      # 本 ADR のライブラリ
  - orgs/com-junkawasaki/bpmn-clj                         # 同型の再利用 kernel(host-injected ports の先例)
  - orgs/com-junkawasaki/koe-clj                          # host-injected ports パターンの原型
  - orgs/com-junkawasaki/langgraph-clj                    # graph 実行 kernel(姉妹)
supersedes: []
superseded_by: []
---

# ADR-2606272216: graphql-clj — GraphQL SDL → EDN AST + リゾルバ実行の再利用ライブラリ

**Status**: proposed（実装済み・テスト緑。IResolver を注入する actor 側は別 PR）
**Date**: 2026-06-27
**Deciders**: Jun Kawasaki

## Context

GraphQL API をホストする需要があるが、既存の Clojure/JVM 向け GraphQL ライブラリは
(1) lacinia 等が大量の third-party 依存を持ち、(2) スキーマを不透明な内部オブジェクトに
閉じ込め、Clojure の `assoc`/`diff`/Datomic と噛み合わない。本リポの方針
(shallow 既定・大容量 dep 回避・portable `.cljc`・host-injected ports — bpmn-clj /
koe-clj の先例)に沿う、**GraphQL SDL と実行を素の EDN として扱う軽量ライブラリ**が無かった。

## Decision

`com-junkawasaki/graphql-clj` を新設する。**third-party 実行時依存ゼロ**、全 namespace
`.cljc`(JVM/CLJS/SCI)。責務を5層に分離する:

- **`graphql.model`** — GraphQL スキーマ EDN の正準モデル。型定義は **名前キーの map**
  (O(1)参照)。フィールドは `{:graphql/name :graphql/type :graphql/list? :graphql/non-null?
  :graphql/args}` の plain map。threadable builder (`type-def`/`field-def`/`add-type`)
  とスキーマ query (`get-type`/`field-by-name`) を提供。
- **`graphql.sdl`** — GraphQL SDL 文字列 → model。**手書きトークナイザ**(正規表現で
  識別子・リテラル・区切り文字を分割、`# コメント`を除去)+ 再帰降下パーサで実行可能 SDL
  サブセット(`type` / `input` / `interface` / `enum` / `union` / `scalar` / `schema` /
  `!` / `[]` / `@directive` skip)を dep 無しで解析。GraphQL 仕様のデフォルト(schema ブロック
  なし → "Query" 型を query-root に自動設定)に対応。
- **`graphql.query`** — クエリ文字列 → セレクション AST。同じトークナイザ基盤で匿名/名前付き
  操作・ネスト選択・引数リテラル(文字列/整数/浮動小数点/真偽値/enum)をパース。セレクションは
  `{:graphql/field :graphql/args :graphql/selections}` の vector。
- **`graphql.validate`** — 構造検証。`{:graphql/severity :error|:warn :graphql/code
  :graphql/id :graphql/msg}` の vector を返す純関数。error は query-root 欠落・
  フィールド型未定義・フィールド名重複・引数型未定義。`valid?` は error 無しで真。
- **`graphql.execute` + `graphql.ports`** — **純粋フィールドリゾルバ**。host が
  `IResolver`(resolve: type-name field-name parent args ctx → value)を注入。
  フィールドごとに一度 resolve を呼び、サブセレクションがあれば戻り値の型で再帰。
  解決値が sequence の場合は各要素にサブセレクションをマップして vector を返す。
  `default-ports` で `(get parent (keyword field-name))` による plain map アクセスを提供。

## Rationale

- **データ第一**: スキーマとセレクションが EDN なので生成・差分・Datomic 格納・テストが自明。
  ランタイムの不透明オブジェクトを持たない。
- **依存ゼロ × 可搬**: 重い GraphQL ライブラリ dep を避ける本リポ方針と、WASM/SCI host での
  実行要件を両立。SDL パーサは「手書きサブセット + 本格派は host でパースして model に注入」
  の2段構成にするのが望ましい将来形(bpmn.xml の neutral-element 注入と同じパターン)。
- **host-injected IResolver**: bpmn-clj/koe-clj と同じ分離。kernel は DB/資格情報/
  スキーマレジストリを持たず、field resolution のオーケストレーションだけが純粋に残る
  → オフラインで fixture resolver によりテスト可能。
- **プロトコル名 `resolve`**: `clojure.core/resolve` と衝突するため `(:refer-clojure
  :exclude [resolve])` で除外し、同名のプロトコルメソッドを定義する(Clojure の慣用的解決策)。

## Consequences

- SDL パーサは実行可能サブセット限定。`extend type`・フラグメント定義・入力オブジェクト
  リテラル・ネスト list 型の完全な nullability は対応外。限界は README/docstring に明記。
- query パーサはエイリアスを実フィールド名に解決する(結果 map のキーはエイリアスではなく
  フィールド名)。完全な alias サポートは将来課題。
- OR-join・サブスクリプション・ミューテーション副作用は executor の責務外(host が
  IResolver の実装で処理する)。
- 最初の消費者は別 org(公益=etzhayyim / 事業=gftdcojp)の actor が IResolver を注入して
  実 GraphQL API を駆動する(本ライブラリにドメインスキーマは入れない)。

## Verification

`clojure -X:test` 緑(17 tests / 40 assertions)。SDL の type/enum/union/scalar パース、
`!` 非 null・`[]` リスト修飾子の正確な解析、フィールド引数(型・非 null フラグ)の保持、
query のショートハンド/名前付き操作・整数引数のパース、validate の missing-query-root /
unknown-type / duplicate-field / missing-defined-root エラー検出、execute でのデフォルト
リゾルバ動作、fixture IResolver によるネストオブジェクト解決、list フィールドの
サブセレクションマップ、引数の透過的渡しを確認。
