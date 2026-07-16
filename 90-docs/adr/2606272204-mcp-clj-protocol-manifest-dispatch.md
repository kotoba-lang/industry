---
id: adr-2606272204-mcp-clj-protocol-manifest-dispatch
title: "ADR-2606272204: mcp-clj — Model Context Protocol サーバマニフェストを EDN/Clojure データとして扱う再利用ライブラリ。model(name-keyed map) + validate + JSON I/O(host-injectable / 解析済みマップ受け取り) + 純粋 JSON-RPC ディスパッチャ(ITool/ITransport ports)。third-party dep ゼロの portable .cljc"
status: proposed
doc_type: adr
topic: library-design
authoritative: true
last_verified: 2026-06-27
authoritative_for:
  - MCP (Model Context Protocol) サーバマニフェスト(tool/resource/prompt)を Clojure で第一級の EDN データとして表現する正準モデルの定義
  - mcp-clj の責務境界(モデル/検証/JSON I/O/実行)と host-injected ports の設計
  - JSON テキスト非解析方針 — ホストが解析済みマップを渡す2層構成(bpmn.xml/from-elements と同型)
  - JSON-RPC メソッドディスパッチとバリデーション(-32601/-32602)の実装境界
  - ライブラリ成果物の3-org 配置(共通=com-junkawasaki / 公益=etzhayyim / 事業=gftdcojp)
related:
  - orgs/kotoba-lang/org-anthropic-mcp    # 本 ADR のライブラリ（通称 mcp-clj；rename chain は末尾 addendum）
  - orgs/com-junkawasaki/langchain-clj    # LLM chain kernel(姉妹); Claude+MCP スタックで連携
  - orgs/com-junkawasaki/langgraph-clj    # graph 実行 kernel(姉妹); エージェントから本 lib を呼ぶ
  - orgs/com-junkawasaki/bpmn-clj         # 同型設計の先例(model/validate/ports/execute 分離)
supersedes: []
superseded_by: []
---

# ADR-2606272204: mcp-clj — MCP マニフェストを EDN/Clojure で扱う再利用ライブラリ

**Status**: proposed（実装済み・テスト緑。ports を注入する actor 側は別 PR）
**Date**: 2026-06-27
**Deciders**: Jun Kawasaki

## Context

Model Context Protocol (MCP) は LLM ホスト(Claude Desktop/API)が外部ツール・
リソース・プロンプトを発見・呼び出すための JSON-RPC 規約であり、langchain-clj /
langgraph-clj と組み合わせて Claude+MCP エージェントスタックを構成する上で
サーバ側の manifest 定義とディスパッチが必要になった。

既存の MCP サーバ実装(Python SDK / TypeScript SDK)は(1)言語固有の重い依存を引き、
(2)設定を不透明な Python/TS オブジェクトに閉じ込め、Clojure の `assoc`/`diff`/
Datomic と噛み合わない。本リポの方針(shallow 既定・大容量 dep 回避・portable
`.cljc`・host-injected ports — bpmn-clj / koe-clj の先例)に沿う、**MCP マニフェストを
素の EDN として扱う軽量ライブラリ**が無かった。

## Decision

`com-junkawasaki/mcp-clj` を新設する。**third-party 実行時依存ゼロ**、全 namespace
`.cljc`(JVM/CLJS/SCI)。責務を5層に分離する:

- **`mcp.model`** — MCP-as-EDN の正準モデル。tool/resource/prompt は **name/uri-keyed
  map**(O(1) 参照)。JSON-Schema-as-EDN(`:type`/`:properties`/`:required` はキーワードキー)
  を tool `:mcp/input-schema` として埋め込む。threadable builder
  (`server`/`add-tool`/`add-resource`/`add-prompt`)とクエリ(`tools`/`resources`/`prompts`
  は name/uri 順で決定的)。
- **`mcp.validate`** — 構造検証。`{:mcp/severity :mcp/code :mcp/id :mcp/msg}` の
  vector を返す純関数。error(schema の `:type` 欠落 / `:properties` 非マップ /
  `:required` に存在しない property 名 / key/name 不一致 / resource `:mcp/uri` 欠落)
  と warn(ツール定義なし)を分離、`valid?` は error 無しで真。
- **`mcp.json`** — JSON ⇄ EDN 変換。**JSON テキストは一切解析しない**。ホストが JSON
  パーサ(clojure.data.json / js/JSON.parse)の出力を *解析済みマップ*(string-keyed
  Clojure map/vector)として `from-data` に渡し、`to-data` が JSON-ready マップを返す。
  `bpmn.xml/from-elements` が neutral element を受け取る2層構成と同型で、コアに
  JSON パーサ依存を持ち込まない。JSON-Schema の string-key ↔ keyword-key 変換も本層が担う。
- **`mcp.ports`** — host-injected protocols。`ITool`(`invoke`: tool-name→args→result) と
  `ITransport`(`call`: method→params→result)。インタプリタはこれらを呼ぶだけで I/O を持たない。
- **`mcp.execute`** — **純粋 JSON-RPC ディスパッチャ**。`handle`(ports model request)は
  string-keyed JSON-RPC リクエストを受け取り、string-keyed レスポンスを返す。
  `initialize` / `tools/list` / `tools/call` / `resources/list` / `prompts/list` を実装。
  `tools/call` は `:required` バリデーションを行い(不足 → -32602)、通過したら
  `ITool/invoke` を呼ぶ。未知メソッド → -32601。`default-ports` でホスト無しでも動作確認可。

## Rationale

- **データ第一**: マニフェストが EDN なので生成・差分・バージョニング・Datomic 格納が自明。
  Python/TS SDK のオブジェクト状態を持たない。
- **依存ゼロ × 可搬**: JSON パーサ非同梱の本リポ方針と、WASM/SCI host での実行要件を両立。
  bpmn.xml と同じ「テキスト解析はホストへ、変換だけコアが担う」2層設計。
- **host-injected ports**: bpmn-clj / koe-clj と同じ分離。kernel は HTTP/WebSocket/
  SDK/認証情報を持たず、ディスパッチだけが純粋に残る → オフラインで fixture port によりテスト可能。
- **JSON-RPC 仕様準拠**: -32601(Method Not Found)/-32602(Invalid Params) を仕様通りに返す。
  バリデーションを dispatch 層に内包することでホスト実装の手間を減らす。
- **langchain-clj / langgraph-clj との連携**: langgraph-clj のノードから `e/handle` を
  呼ぶ形で MCP over in-process call を実現できる。Claude API + MCP スタックでは
  ITransport/call に HTTP JSON-RPC クライアントを注入して上位ホストへ転送する。

## Consequences

- JSON テキストの往復(serialize/deserialize)はホストが担う。clojure.data.json /
  cheshire / js/JSON はホスト層に留まり mcp-clj には入らない。
- JSON-Schema サポートは small subset 限定(object/string/integer/boolean の `:type`、
  `:properties`、`:required`)。フル JSON-Schema バリデーション($ref / allOf / if-then 等)
  は将来のホスト拡張または別ライブラリへ。
- `ITransport` は接続済みクライアントを想定する汎用 port であり、WebSocket / stdio /
  SSE などのトランスポート詳細はホスト実装に委ねる(mcp-clj は知らない)。
- 最初の消費者は別 org(公益=etzhayyim / 事業=gftdcojp)の actor が ports を注入して
  実ツールを駆動する(本ライブラリにドメインロジックは入れない)。

## Verification

`clojure -X:test` 緑(11 tests / 25 assertions)。builder でマニフェストを構築し、
:required に存在しない property 名を持つスキーマは validate が reject することを確認。
from-data/to-data の round-trip(JSON-Schema の string↔keyword 変換含む)、
tools/list が名前順ソートで返ること、tools/call で必須引数欠落 → -32602、
fixture ITool で有効引数 → ITool result、未知メソッド → -32601、
resources/list・prompts/list・initialize の各メソッドを確認。

## Addendum — rename chain: com-junkawasaki/mcp-clj → kotoba-lang/mcp → kotoba-lang/org-anthropic-mcp（記録 2026-07-16）

本ライブラリは rename chain を経て現行 `kotoba-lang/org-anthropic-mcp` に至る:

  `com-junkawasaki/mcp-clj`  ──(org 移行 + 2026-07-10 naming rule で `-clj` 廃止)──▶  `kotoba-lang/mcp`  ──(spec-org 系命名へ統合)──▶  `kotoba-lang/org-anthropic-mcp`

- **現行の正本 repo/path は `kotoba-lang/org-anthropic-mcp`（`orgs/kotoba-lang/org-anthropic-mcp`）。**
  west.yml はこの名前で pin 登録する。`kotoba-lang/mcp` は GitHub rename-redirect（旧名）として残存するが正本ではない。
- **通称 `mcp-clj` は不変。** 本 ADR の title・本文中の `mcp-clj` はライブラリ nickname としてそのまま使用（`= mcp.model/validate/json/ports/execute` の portable `.cljc` kernel）。
- 本文 Decision や他 ADR の `related:` に残る `com-junkawasaki/mcp-clj` / `kotoba-lang/mcp` は歴史的表記であり、本 addendum が優先する。
- 姉妹 lib（langchain-clj / langgraph-clj / bpmn-clj / torch-clj / vllm-clj / jsonlogic-clj 等）の org・rename 状態は本 ADR の対象外（`authoritative_for` の 3-org 配置ルール自体は不変）。
