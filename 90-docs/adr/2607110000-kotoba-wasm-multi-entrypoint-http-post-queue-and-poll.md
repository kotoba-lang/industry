# ADR-2607110000: kotoba wasm 複数エントリポイント export + host主導再呼び出し（http-post queue-and-poll化）の設計

**Status**: proposed（未承認 — 実装前にオーナーレビュー必須）
**Date**: 2026-07-10
**Deciders**: Jun Kawasaki

## Context

`http-post` はブラウザの `actor:host`（`wasm-webcomponent`/`actor-host.js`）で
恒常的に未実装だった: `fetch` は本質的に非同期な網I/Oであり、WASMホスト
インポート呼び出しは同期で値を返す必要がある（JSPIはChrome 137+限定で
Firefox/Safari未対応）。この gap の解消策として2方式を検討した:

1. **SharedArrayBuffer + Atomics.wait ブリッジ**（guest側ABI・kotoba
   コンパイラ・kototama/actor-hostの呼び出しモデルは一切変更不要。
   実装済み: `kotoba-lang/wasm-webcomponent` PR #6）。
2. **queue-and-poll**（guestがまず`http-post-start`で非同期にリクエストを
   開始し、handleを受け取り、host主導で複数回呼び直されながら
   `http-post-poll`で結果を取得する方式）。

本ADRは(2)の設計を記録する。**訂正**: 当初、この方式は「ADR-2607072530の
follow-up方針」として提示されたが、これは誤りだった——同ADR本文
（cloud-itonami-kototama-wasm-llm-infer-poc-isic-6511.md）を実際に確認した
ところ、queue-and-pollという語も設計も存在せず、"http-postの非同期未対応は
今回も未解消"と記すのみだった。既存ADRに存在しない実装方針を存在するかの
ように報告した誤り（ADR-2607062330 addendum 6がaiueosについて記録した
「同種の誤り」と同型）であり、ここに訂正として記録する。queue-and-poll案
自体はこのセッションでの新規設計提案であり、既存の承認済み計画ではない。

### 実現可能性の調査結果（重要な制約）

queue-and-pollを実装するには、想定より深い変更が必要なことが判明した:

- **kotoba wasm コンパイラは `main` 以外を一切exportしない。**
  `kotoba-lang/kotoba/src/kotoba/runtime.clj:1592` の `export-section` は
  `"main"` をハードコードしており、`main` は zero-arity 必須
  （`:missing-main`/`:main-arity` チェックあり、同ファイル1522-1531行）。
  他の top-level `defn` はコンパイルされるが export されない。
- **kototama.tender（JVM）・actor-host.js（ブラウザ）とも呼び出しは
  完全にone-shot。** `kototama/src/kototama/tender.clj:438-442`の
  `call-main`は`"main"`という固定名を1回だけ呼び、Instanceの参照は
  `run-main`が`long`だけを返して破棄する（444-450行）。
  `wasm-webcomponent/src/kotoba-wasm-element.js:82-97`も同様、
  `instance`はローカル変数で`connectedCallback`終了後に破棄される。
  tick/poll/resume用のエントリポイントはABI（`kototama/lib/kototama/host.edn`、
  `kototama/src/kototama/contract.cljc`）のどこにも定義されていない。
- **guest主導のbusy-loop pollingは構造的に成立しない。** WASM実行は
  同期呼び出しなので、guestが`main()`内でpollを繰り返している間は
  ブラウザのイベントループが一切回らず、`fetch()`のPromiseは
  永遠に解決しない（デッドロック）。host側がguestを複数回呼び直す
  設計に変える必要がある——guest側で「待つ」ことはできない。

## Decision（提案 — 未承認）

以下を実装する（オーナー承認後に着手）:

1. **kotoba wasm コンパイラに複数エントリポイントexportを追加。**
   `export-section`（`runtime.clj:1592`）を`fn-indexes`全体（または
   `main`+明示的に指定された追加エントリポイント名の集合）を対象に
   一般化する。`main`のzero-arity必須制約は維持しつつ、追加
   エントリポイントは任意個数の`:i32`引数を取れるようにする
   （host側から前回の戻り値・handleを渡し直すため）。

2. **`kotoba-core-contracts`の capability_contract.edn に2つの新規
   capabilityを追加**（既存`http/post`はJVM/一発同期呼び出し用として
   維持、廃止しない）:
   - `http/post-start`: `(url-ptr url-len body-ptr body-len) -> handle:i32`
     （grant拒否/quota超過時は`-1`）
   - `http/post-poll`: `(handle out-ptr out-cap) -> status:i32`
     （`-2`=pending、`-1`=error、`>=0`=HTTPステータス、応答本体は
     `out-ptr`へ書き込み）

3. **kototama.tender（JVM）に`call-export`（名前+引数を取る一般化版
   `call-main`）を追加**し、`http-post-start-host-fn`/
   `http-post-poll-host-fn`を実装。JVMは既に同期ブロッキングI/Oが
   可能なため、`start`が実際のHTTP呼び出しを即座に行い結果を
   handleキーでキャッシュ、`poll`はキャッシュを返すだけでよい
   （常に即pending解消）——guest側コードがJVM/ブラウザ両ホストで
   移植可能であることを保証する目的が主眼で、JVM側に真の非同期実装は
   不要。

4. **actor-host.js / KotobaWasmElementに host主導の再呼び出しloopを
   追加。** guestをWorker内でインスタンス化し（PR #6のSAB bridge同様、
   Atomics.waitのmain thread制約とは別に、host主導呼び出しモデルは
   Worker/main threadどちらでも成立するが、`http-post-poll`実装は
   PR #6の`createHttpPostBridge`を再利用できる）、`start`エントリ
   ポイントの戻り値（handle）を次回呼び出し時に`poll`エントリ
   ポイントの引数として渡し直す。`status === -2`（pending）の間は
   `setTimeout`等でリスケジュールし、`status >= 0`または`-1`で確定。

## 未承認・オーナー判断が必要な論点

- **多エントリポイントexportを`.kotoba`言語の一般機能として受け入れるか、
  http-post専用の狭いスコープに留めるか。** 前者は`.kotoba`の言語面
  （現状「zero-arity `main`のみ」という単純な不変条件）を恒久的に拡張し、
  3リポ（kotoba/kotoba-core-contracts/kototama/wasm-webcomponent）に
  またがるABI変更になる。後者は`start`/`poll`という固定名2エントリ
  ポイントのみサポートする狭い特例にできる（実装は小さいが、将来他の
  非同期capability——例えば将来のUnix domain socket接続等——に同じ
  パターンが必要になった際に再度同じ議論をすることになる）。
- **PR #6（SharedArrayBuffer+Atomics bridge）が当面の要求を満たすなら、
  queue-and-pollの優先度を下げてよいか。** PR #6は3リポにまたがる
  ABI変更を一切必要とせず、COOP/COEPヘッダ配信環境という運用上の
  制約はあるものの既に動作確認済み。queue-and-pollは配信環境に依存
  しない代わりに言語機能追加級の実装コストがかかる。
- **`start`/`poll`エントリポイント名・pending sentinel値
  （`-2`）の最終確定。** `kotoba-core-contracts`の
  capability_contract.edn は他の全リポの正本なので、ここでの命名は
  実質的に確定を意味する。

## Consequences

- 承認・実装されれば、COOP/COEPヘッダに依存しない、より汎用的な
  非同期capabilityパターンが`.kotoba`エコシステム全体に導入される
  （http-post以外の将来の非同期capabilityにも再利用可能）。
- 実装コストは`kotoba`（コンパイラのexport一般化）・
  `kotoba-core-contracts`（ABI追加）・`kototama`（JVM
  call-export一般化+2 host-fn）・`wasm-webcomponent`（host主導
  再呼び出しloop）の4リポにまたがり、PR #6単体より大幅に大きい。
- 承認までは実装に着手しない（オーナー指示: 2026-07-10、http-post対応の
  方針確認時）。
