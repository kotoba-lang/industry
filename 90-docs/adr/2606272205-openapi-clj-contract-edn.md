---
id: adr-2606272205-openapi-clj-contract-edn
title: "ADR-2606272205: openapi-clj — OpenAPI 3 仕様を EDN/Clojure データとして扱う再利用ライブラリ。model(path×method-keyed ops) + validate + bidirectional JSON converter + pure request builder(IHttp port)。third-party dep ゼロの portable .cljc"
status: proposed
doc_type: adr
topic: library-design
authoritative: true
last_verified: 2026-06-27
authoritative_for:
  - OpenAPI 3 仕様を Clojure で第一級の EDN データとして表現する正準モデルの定義
  - openapi-clj の責務境界(モデル/検証/JSON変換/リクエスト構築)と host-injected IHttp port の設計
  - string-keyed JSON パース済みマップと名前空間付き EDN モデルの双方向変換戦略
  - API 成果物の 3-org 配置(共通=com-junkawasaki / 公益=etzhayyim / 事業=gftdcojp)
related:
  - orgs/com-junkawasaki/openapi-clj                     # 本 ADR のライブラリ
  - orgs/com-junkawasaki/godaddy-dns-clj                 # 同型の IHttp port 消費者の候補
  - orgs/com-junkawasaki/bpmn-clj                        # 同型の再利用 kernel(host-injected ports の先例)
  - orgs/com-junkawasaki/dmn-clj                         # 姉妹ライブラリ(決定表 EDN)
supersedes: []
superseded_by: []
---

# ADR-2606272205: openapi-clj — OpenAPI 3 を EDN/Clojure で扱う再利用ライブラリ

**Status**: proposed（実装済み・テスト緑。IHttp を注入する actor 側は別 PR）
**Date**: 2026-06-27
**Deciders**: Jun Kawasaki

## Context

API 契約(OpenAPI 3)をコード生成・型検査・リクエストバリデーション等の用途で扱う需要がある。
既存のアプローチには次の問題がある:

1. **重い JVM 専用依存**: swagger-parser / openapi4j 等は JVM 専用かつ多数の推移的依存を持ち、
   本リポの shallow 既定・portable `.cljc` 方針と噛み合わない。
2. **不透明な状態**: ほとんどの OAS ライブラリは仕様を自前の Java オブジェクトグラフに
   変換して閉じ込め、Clojure の `assoc`/`diff`/Datomic 格納と相性が悪い。
3. **I/O 混入**: HTTP クライアントと仕様解析が同一ライブラリに混在し、テスト時にネットワークを
   必要とする。

本リポの方針(shallow 既定・大容量 dep 回避・portable `.cljc`・host-injected ports —
bpmn-clj / dmn-clj の先例)に沿う、**OpenAPI 3 を素の EDN として扱う軽量ライブラリ**が無かった。

## Decision

`com-junkawasaki/openapi-clj` を新設する。**third-party 実行時依存ゼロ**、全 namespace
`.cljc`(JVM/CLJS/SCI)。責務を5層に分離する:

- **`openapi.model`** — OpenAPI 3-as-EDN の正準モデル。操作は `path × method-kw`
  でアドレス可能、`:openapi/*` 名前空間付きキーで自己文書化。グラフ query
  (`operations`/`operation-by-id`/`parameters-for`/`path-params`) は純関数。
- **`openapi.validate`** — 構造検証。`{:openapi/severity :openapi/code :openapi/id :openapi/msg}`
  の vector を返す純関数。error(重複 operationId / 未宣言 path param / レスポンス無し /
  未解決ローカル `$ref`)と warn(外部 `$ref`)を分離、`valid?` は error 無しで真。
- **`openapi.json`** — string-keyed 解析済みマップ ⇄ 名前空間付き EDN モデルの双方向変換。
  `from-data` / `to-data` の2関数で round-trip 保証。ホストは任意の JSON パーサを使用し
  結果をここに渡す—パーサ自体は依存に含まない。
- **`openapi.ports`** — `IHttp` プロトコル(request: req-map → resp-map)。
  `default-ports` はリクエストをエコーするフィクスチャ実装で、ネットワーク無しでも
  リクエスト構築ロジックを全テストできる。
- **`openapi.execute`** — **純粋リクエストビルダー**。`request-for` が path param 置換・
  query/body 分離・必須パラム不足のエラーデータ返却を行う(例外を投げない)。`invoke` が
  `IHttp/request` へ委譲。I/O ゼロ。

## Rationale

- **データ第一**: API 契約が EDN なので生成・差分・バージョニング・Datomic 格納が自明。
  OAS オブジェクトグラフの不透明 blob を持たない。
- **依存ゼロ × 可搬**: 重い OAS パーサ dep を避ける本リポ方針と、WASM/SCI host での
  実行要件を両立。JSON パーサは「ホストが選択し結果を渡す」設計で過剰実装を避ける。
- **host-injected IHttp**: bpmn-clj の `IActivity/ICondition` と同じ分離パターン。
  kernel は HTTP クライアント・認証・再試行を持たず、リクエスト構築だけが純粋に残る
  → オフラインで fixture port によりテスト可能。godaddy-dns-clj 等が IHttp 実装を注入する。
- **エラーデータ返却**: 必須パラム不足は例外ではなく `{:openapi/error :missing-required-params}`
  を返す。`.cljc` 全体で例外スタックを使わず、CLJS/SCI でも同じ制御フローになる。

## Consequences

- OpenAPI 3 の全仕様(allOf/oneOf/anyOf/discriminator/callbacks/links 等)は未実装。
  本ライブラリは「路線図」レベルの構造検証とリクエスト構築に特化し、
  完全な JSON Schema バリデーションは将来の拡張課題とする。
- `$ref` の解決はローカル `#/components/...` のみ。循環 `$ref` は未検出(検出は将来課題)。
- external `$ref` は :warn 止まり(ローカル解決不能のため :error にしない)。
- 最初の消費者は別 org の actor が IHttp を注入して実 API を呼び出す(本ライブラリにドメインは入れない)。

## Verification

`clojure -X:test` 緑(12 tests / 42 assertions)。from-data/to-data round-trip、
path param 置換、query param 付与、必須パラム欠落エラー、重複 operationId 検出、
未宣言 path param 検出、レスポンス無し検出、fixture IHttp による invoke を確認。
