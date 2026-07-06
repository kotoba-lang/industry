# ADR-2607061850: kototama の browser WASM AOT WebComponent PoC を `kotoba-lang/wasm-webcomponent` ライブラリへ切り出す

**Status**: accepted (landed)
**Date**: 2026-07-06
**Deciders**: Jun Kawasaki

## Context

ADR-2607061630 で `kotoba-lang/kototama` に browser-native WASM AOT 実行 +
WebComponent ホスティングの最小 PoC（`<kototama-wasm-run>`）を実装し、続く
follow-up で `kgraph-*` host-import ABI のブラウザ移植（`<kototama-wasm-kgraph-demo>`）
を追加した。オーナーから「kotoba-lang の全リポが基本的に wasm webcomponent で
動くように lib 化してほしい」という指示があった。

調査の結果、前提が思っていたより複雑だった:

1. **`.kotoba` 拡張子の名前衝突。** `com-*` 系ベンダーアダプタリポ
   （`com-anthropic`/`com-adyen` 等）に `schema/*.kotoba` というファイルが
   **781 個**存在するが、中身は `namespace X { entity Y { field: type
   @unique } }` という別ジェネレータ（`deepen_actors.py`）製の**全く無関係な
   スキーマ DSL** で、kotoba-lang 本来の実行言語（`def`/`defn`/`ns`/`if` 等の
   極小サブセット）とは別物。CLAUDE.md の「`.kotoba` 拡張子は kototama WASM
   ランタイム向け」という規約に反する名前衝突であり、これらは対象外。
2. **`kotoba-*` と名の付く他リポ**（`lint-kotoba`/`kotoba-code`/
   `kotoba-procedure-clj`/`kotoba-issue-clj` 等）は全て `.cljc`/`.clj` の
   通常の Clojure ライブラリで、`.kotoba` 言語のソースを一切持たない。しかも
   実装は map/require/third-party lib を使う通常の Clojure であり、
   `.kotoba` の極小サブセット（map 無し・Java interop 無し・サードパーティ
   lib 不可）には収まらない。「既存リポをコンパイラに通す」だけでは動かず、
   実質的な書き直しが要る。
3. よって「kotoba-lang の全リポを wasm webcomponent 化する」を字面通り行うのは
   的外れ。まず**再利用可能なライブラリへの切り出し**を行い、**既存リポの
   移植ではなく新規に書いた小さな `.kotoba` プログラム**でその使い方を証明する
   方針に絞った（オーナー確認済み）。

## Decision

1. **`kotoba-lang/wasm-webcomponent` を新規 public リポとして作成した。**
   `kototama/web/` にあった PoC 実装を移設・一般化:
   - `src/kotoba-wasm-element.js` — `KotobaWasmElement`。`customElements.define`
     ベースの再利用可能な base class + `.define(tagName, {exportName,
     createImports, render})` ファクトリ。`fetch` → `WebAssembly.
     instantiateStreaming` → 指定 export 呼び出し → shadow DOM 描画 →
     `kotoba-wasm:done`/`kotoba-wasm:error` イベント発火、という一連を
     1 箇所に集約。
   - `src/kgraph.js` — `kotoba.kgraph.clj`（EAVT datom store）+ `kgraph-*`
     host-import ABI 用の最小 EDN reader/writer のブラウザ移植（ADR-2607061630
     follow-up からそのまま移設、ロジック変更なし）。
   - `examples/hello`（zero-import）、`examples/kgraph`（host-import あり）、
     `examples/gcd`（**新規に書いた** `.kotoba` プログラム — ユークリッドの
     互除法、実行時再帰を伴う。既存リポの移植ではなく「新規に書けば動く」ことの
     証明として選定）。
   - `test/verify-*.mjs` — Node のネイティブ `WebAssembly`（ブラウザと同じ
     V8）で各 example を検証する依存ゼロのスモークテスト。
2. **`kotoba-lang/kototama` の `web/` をこのライブラリの consumer に refactor
   した（dogfooding）。** 独自コピーだった `kototama-wasm-run.js`/
   `kototama-wasm-kgraph-demo.js`/`kgraph.js` を削除し、`index.html` は
   jsdelivr 経由でこのライブラリの pin 済み commit から `KotobaWasmElement`/
   `kgraph.js` を import するだけになった。Node 側テストは `https:` URL の
   flag-free `import()` が無いため、fetch した本体を `data:` URL 経由で
   import するパターンを採用（依存ゼロを維持）。
3. **manifest 登録。** `repos.edn :extra-projects` に追加 → `west.yml` に
   `--entry wasm-webcomponent` で最小 diff 登録。

## Consequences

- (+) 「browser WASM AOT + WebComponent」パターンが、kototama に埋め込まれた
  PoC から、任意の kotoba-lang リポが読み込める独立ライブラリになった。
- (+) kototama 自身がこのライブラリの最初の consumer になり（自己適用）、
  ライブラリの実用性を実証した。CI（`web` job）も新設し、pin 済み外部 URL
  経由の import を含めて検証している。
- (+) 「全リポ wasm webcomponent 化」という当初のオーダーに対し、**現実的な
  スコープの再定義**（既存 Clojure 実装の移植ではなく、新規 `.kotoba` ソース
  を書いてこのライブラリに載せる、という形）を行い、それを `examples/gcd`
  で実証した。
- (−) `kse`/`auth`/`llm`/`evm`/`btc`/`egress`/`chain` 等の host-import は
  依然ブラウザ未実装（`kgraph-*` のみ）。
- (−) capability/policy のロード時再検証は無し（`kotoba wasm emit --policy`/
  `--package-lock` はビルド時チェックのみ）。
- (−) 「kotoba-lang の全リポ」を対象にする話は事実上ペンディング
  ——`.kotoba` 実行ソースを持つリポが（デモ以外に）他に無く、`lint-kotoba` 等
  既存リポをこのライブラリに載せるには、それぞれの本体ロジックを `.kotoba`
  極小サブセットで書き直す作業が別途必要（本 ADR のスコープ外、follow-up）。
- (−) `com-*` 系リポの `schema/*.kotoba`（781 ファイル）拡張子衝突は、この
  ADR では是正しない。将来の整理は別 ADR マター。

## References

- ADR-2607061630（kototama browser WASM AOT + WebComponent PoC、第一段階）
- `kotoba-lang/wasm-webcomponent`（新規リポ）
- `kotoba-lang/kototama` PR #18（web/ を consumer へ refactor）
- `kotoba-lang/kotoba` PR #284（`--package-lock` の zero-dependency 対応 —
  `examples/gcd` のビルドに必要だった前提修正）
