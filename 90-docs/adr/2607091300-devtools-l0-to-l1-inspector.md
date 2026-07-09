# ADR-2607091300: DevTools を L0 (absent) から L1 (純データインスペクタ) に引き上げる — `devtools.inspect` + `browser.devtools`

**Status**: accepted — landed (2026-07-09)
**Date**: 2026-07-09
**Deciders**: Jun Kawasaki
**Scope**: `orgs/kotoba-lang/devtools`（新 `devtools.inspect` namespace）・`orgs/kotoba-lang/browser`（新 `browser.devtools` adapter + `quickjs_runner` の console 永続化）・superproject `manifest/west.yml` pin

## Context

ブラウザエンジン成熟度マトリクスで DevTools は L0 (absent) だった。`devtools` repo
には KAMI 自動化契約（element-snapshot / hit-test / 合成入力 / UIUX 評価）しかなく、
ブラウザの DOM・スタイル・レイアウト・console・network を統合的に inspect する表面は
存在しなかった。一方 `browser` 側には `browser.dom-bridge/document-snapshot`・
`browser.audit/events`・`browser.browser-use/debug-state`・QuickJS 実行状態の
`:console/messages` などデバッグ断片は揃っていたが、統合インスペクタにまとまる経路が
なかった。オーナー課題「実 Chrome での検証が長らく不能」の足場にもなる位置づけ。

## Decision

L1 を「確実に検証できる純データインスペクタ」として着地する。CDP/HTTP トランスポート
は L2 に回す。

1. **`devtools.inspect`**（ゼロ依存・純データ CLJC）— 6 パネルの純関数 + テキスト
   レンダラ: DOM ツリー・計算スタイル・レイアウトボックス・コンソール・ネットワーク・
   イベントタイムライン。入力形状は `dom-bridge`/`audit`/QuickJS 実行状態が既に生成する
   plain-data に duck-type（hard dependency なし）。統合 `inspector-snapshot`/
   `render-inspector`/`inspect-node`。
2. **`browser.devtools`**（薄いアダプタ）— セッション/ページから実状態を
   `devtools.inspect` 形状へ接続。`document-snapshot`・`:browser/draw-ops`・
   `audit/events`・`:console/messages` を再利用（新規 capture なし）。IO なし。
3. **`quickjs_runner`** — `:console/messages` を `persistent-execution-keys` に追加。
   ページ世代内で console が累積しセッションから取得可能に（navigation でクリア＝
   実ブラウザの console lifetime に合致）。これが console パネルのデータソース。

## Consequences

- DevTools L0→L1。REPL/テストから `(browser.devtools/render-session session)` で
  エンジン状態を確定論的にダンプできる。後続の HTML パーサ L1→L2・Web platform API
  L1→L2 作業の検証足場になる。
- 純データ層と host アダプタの分離により、`devtools.inspect` はエンジン無しで
  unit test 可能（plain-data fixture）。将来の CDP/HTTP トランスポート（L2）は
  `inspection-input` を取り回すだけで乗る。
- 既存テストへの回帰なし。

## Levels

- DevTools: L0 (absent) → L1 (real, tested pure-data inspection surface)
- L2（未着手）: CDP 互換サブセット / HTTP inspector トランスポート。`inspection-input`
  が既に transport 非依存の境界。

## Test status

- `devtools.inspect-test`: 22 tests（plain-data、エンジン不要）
- `browser.devtools-test`: 5 integration tests（scriptless HTML、QuickJS WASM 不要）
- browser full suite: 675 / 3176 assertions / 0 fail。devtools: 27 / 103 / 0 fail。lint 0 errors。
