---
id: adr-2606272214-sigma-clj-detection-rules-edn
title: "ADR-2606272214: sigma-clj — Sigma 検知ルールを EDN/Clojure データとして扱う再利用ライブラリ。model(namespaced :sigma/* keys) + validate + yaml(from-data/to-data) + 純粋イベントマッチャ(IField port)。third-party dep ゼロの portable .cljc"
status: proposed
doc_type: adr
topic: library-design
authoritative: true
last_verified: 2026-06-27
authoritative_for:
  - Sigma 検知ルールを Clojure で第一級の EDN データとして表現する正準モデルの定義
  - sigma-clj の責務境界(model/validate/yaml変換/実行)と host-injected IField port の設計
  - YAML パーサ依存を持ち込まずに「解析済みマップを受け取る」2段構成の採用
  - 検知成果物の3-org 配置(共通=com-junkawasaki / 公益=etzhayyim / 事業=gftdcojp)
related:
  - orgs/com-junkawasaki/sigma-clj      # 本 ADR のライブラリ
  - orgs/com-junkawasaki/ghosthacker    # セキュリティツール(sigma-clj の主要消費者)
  - orgs/com-junkawasaki/bpmn-clj       # 同型の再利用 kernel(host-injected ports の先例)
  - orgs/com-junkawasaki/dmn-clj        # 同型の再利用 kernel(姉妹)
supersedes: []
superseded_by: []
---

# ADR-2606272214: sigma-clj — Sigma 検知ルールを EDN/Clojure で扱う再利用ライブラリ

**Status**: proposed（実装済み・テスト緑。IField を注入する actor 側は別 PR）
**Date**: 2026-06-27
**Deciders**: Jun Kawasaki

## Context

SIEM/EDR の検知ルールを共通フォーマット(Sigma)で記述・評価したい需要がある。既存の
Sigma 実装は (1) Python ベースのコンバータ (`sigma-cli`) が中心で Clojure/JVM ネイティブな
ライブラリが存在せず、(2) YAML パーサへの強い依存がホスト環境を選び、portable `.cljc`
と噛み合わない。本リポの方針(shallow 既定・大容量 dep 回避・portable `.cljc`・
host-injected ports — bpmn-clj / dmn-clj の先例)に沿う、**Sigma ルールを素の EDN として
扱う軽量ライブラリ**が無かった。

## Decision

`com-junkawasaki/sigma-clj` を新設する。**third-party 実行時依存ゼロ**、全 namespace
`.cljc`(JVM/CLJS/SCI)。責務を5層に分離する:

- **`sigma.model`** — Sigma-as-EDN の正準モデル。ルールは `{:sigma/id :sigma/title
  :sigma/level :sigma/logsource :sigma/detection}` の namespaced map。detection は
  `{:sigma/selections {"sel" {field-key [values...] ...} ...} :sigma/condition "..."}` で
  選択マップ名はそのまま文字列キー(フィールドキーの `|modifier` サフィックスも保持)。
  threadable builder(`rule`/`add-selection`/`set-condition`)とクエリ関数。
- **`sigma.validate`** — 構造検証。`{:sigma/severity :error|:warn :sigma/code :sigma/id
  :sigma/msg}` の vector を返す純関数。error(未定義選択参照・不明 modifier)と
  warn(:sigma/level 未知・glob が選択にマッチしない)を分離、`valid?` は error 無しで真。
- **`sigma.yaml`** — YAML 解析済みマップ(文字列キー)⇄ EDN モデル。`from-data`/`to-data`
  の純変換のみ。**YAML テキストを直接パースしない**(ホストが `clj-yaml` 等でパースした
  マップを受け取る)ことで、パーサ依存を完全に排除。round-trip 無損失保証。
- **`sigma.ports`** — `IField` プロトコル(`extract: field-name event → value`)。ホストが
  イベントスキーマをマップするために注入する。`default-ports` は文字列キー→キーワードキー
  のフォールバック付き素実装で、ホスト無しでも動作可能。
- **`sigma.execute`** — **純粋イベントマッチャ**。state は素データで inspectable/replayable。
  選択ごとにプレディケートをコンパイルし、condition 式(再帰降下パーサ)を評価。modifier
  対応: `contains`/`startswith`/`endswith`/`re`(re-find)/`gt`/`lt`/`all`。quantifier:
  `1 of <glob>`/`all of them`/`all of <glob>`。`default-ports` で host 無しでもルールを
  評価可能。

## Rationale

- **データ第一**: Sigma ルールが EDN なので生成・差分・バージョニング・Datomic 格納が
  自明。YAML をソース of truth にしつつ、評価は純粋な Clojure データで行う。
- **依存ゼロ × 可搬**: YAML パーサ依存を「解析済みマップを受け取る」境界で完全に排除し、
  WASM/SCI host での実行要件を満たす。bpmn-clj の「neutral-element 注入」と同じ思想。
- **host-injected IField port**: イベントスキーマは環境依存(Windows EventLog / CEF /
  JSON Lines など多様)なので、kernel がスキーマを持たず、extraction ロジックだけをホスト
  が注入する設計にした。ghosthacker が具体的なイベント構造を IField で提供する想定。
- **再帰降下パーサ**: condition 式の `letfn` による相互再帰で、`declare` の前方参照を
  回避しつつ JVM/CLJS/SCI すべてで動作する portable な実装を実現した。

## Consequences

- `|re` modifier は `re-find`(部分マッチ)を採用(Sigma 仕様準拠)。完全マッチが必要な
  場合は pattern に `^...$` を付けるよう README に明記する。
- OR-join / correlations(複数ルール相関)は本ライブラリの範囲外。単一ルール×単一イベント
  の評価を担い、correlate は ghosthacker 等の actor 側で実装する。
- 最初の消費者は ghosthacker(com-junkawasaki)が IField を注入してセキュリティイベントを
  評価し、公益・事業 org の actor が sigma-clj を dep に加えて実運用する想定。

## Verification

`clojure -X:test` 緑(14 tests / 39 assertions)。単一選択マッチ・拒否、`sel and not filt`
条件、`|contains`/`|startswith`/`|endswith`/`|re` modifier、multi-value OR、`|all` AND、
`1 of sel*` quantifier、`all of them`、カスタム IField 抽出、未定義選択参照の validate
error、from-data round-trip、unknown level の warn を確認。
