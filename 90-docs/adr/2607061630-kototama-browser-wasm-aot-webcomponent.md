# ADR-2607061630: kototama の実行前提を「JVM+Chicory」から「ブラウザ native WASM AOT + WebComponent ホスティング」へ拡張する（第一段階・PoC）

**Status**: accepted (PoC landed)
**Date**: 2026-07-06
**Deciders**: Jun Kawasaki

## Context

ADR-2607022400（accepted, 2026-07-02）は `kotoba-lang`/`kototama`/`aiueos` の役割を
確定し、kototama の native runtime 設計に Solo5 の tender パターンを採用する方針を
決めたが、tender/wasmtime ホスティングの実装自体は follow-up のまま残っていた。

一方、`kotoba-lang/kotoba`（JVM Clojure 実装）は `ADR-kotoba-wasm-clj-execution.md`
（Accepted, implemented, 2026-07-02）で「`kotoba wasm emit` が生成する WASM MVP
バイナリを `com.dylibso.chicory`（pure-JVM WebAssembly ランタイム）で実際に実行する」
という compile→check→emit→execute のラウンドトリップを実証済みだが、同 ADR の
Consequences で明記される通り **「browser や Cloudflare Worker 向けの host はこの
repo にまだ存在しない」**。

オーナーから「JVM 前提ではなく WASM AOT 前提に、かつ WebComponent モデルで実行できる
ように update してほしい」という指示があった。調査の結果:

- `kotoba wasm emit` が出す WASM バイナリは正規の MVP バイナリ（section layout/
  LEB128/imports/exports/memory/data segments が正しい実バイナリ）であり、**ブラウザの
  ネイティブ `WebAssembly` API でそのまま instantiate 可能**（追加コンパイルや変換は
  不要 — WASM バイナリは元から AOT 済みの機械語相当であり、ブラウザ側エンジン
  （V8 等）がロード時にネイティブコードへ変換する。JVM+Chicory はこの「ブラウザが
  本来担う」役割を JVM 側で肩代わりしているに過ぎない）。
- `customElements.define`/`extends HTMLElement`（WebComponent）を使った実行モデルは
  `kotoba-lang` org 全体を検索してもゼロヒットで、影も形も無かった。
- `kotoba-lang/kototama-clj` という名前の似たリポジトリが存在するが、**manifest
  （`repos.edn`/`west.yml`）に未登録・GitHub 上に remote 実体が存在しない（`git
  ls-remote` が `Repository not found`）ローカル限定の孤立チェックアウト**で、中身も
  UNSPSC organism actor framework（無関係な別プロジェクト）だった。本 ADR のスコープ
  からは除外し、変更しない（別途棚卸しが必要ならユーザーの指示を待つ）。

## Decision

1. **kototama の実行前提を段階的に拡張する。** 「JVM が Wasm インタプリタをホストする」
   （`kotoba wasm run` + Chicory、`kotoba-lang/kotoba` 側）を置き換えるのではなく、
   「ブラウザ自身のエンジンが AOT 済みバイナリを直接実行する」を**追加の前提**として
   kototama に持ち込む。JVM+Chicory パスは compile-time/test-time の証明として維持し、
   無変更。
2. **kototama に `web/` を新設し、WebComponent ホスティングの最小 PoC を置く。**
   `<kototama-wasm-run src="./demo.wasm">` という custom element が `fetch` +
   `WebAssembly.instantiateStreaming` で zero-import の `.wasm` をロードし、指定
   export（既定 `main`）を呼んで shadow DOM に結果を描画する。デモ対象は
   `kotoba-lang/kotoba` の `src/demo.kotoba`（`(+ 40 2)`）を `kotoba wasm emit` した
   73 バイトの実バイナリそのもの（コピーのみ、再生成ロジックの変更なし）。
3. **R0 のスコープを明示する（honest scope）**:
   - host-import ABI（`kgraph-assert!` 等、`kotoba.wasm-exec` が JVM 側で実装している
     `(module "kotoba")` の wire contract）はブラウザ側に**まだ実装しない**。import
     を持つモジュールは今回のページでは instantiate に失敗する。
   - capability/policy gate（`kotoba wasm emit --policy`）は build 時の静的検証で
     あり、ロード時にブラウザ側で再検証する仕組みは無い。sandboxed multi-tenant host
     として扱わない。
4. **検証方法**: ブラウザ拡張(`claude-in-chrome`)経由でのライブ検証を試みたが、
   このセッションのサンドボックスから起動したローカル HTTP サーバに拡張側の Chrome
   から到達できず（`file://` も拡張側で完全ブロック）、DOM/customElements 経路の
   実ブラウザ確認は不可だった。代わりに **Node.js のネイティブ `WebAssembly` API
   （ブラウザと同じ V8 エンジン）で `demo.wasm` を instantiate し、`main() === 42` を
   確認**（`web/verify.mjs`、依存ゼロで再実行可能）。DOM/customElements のラッパー
   自体は標準 Web Platform API の素直な使用であり、個別のブラウザ実証は followup。

## Consequences

- (+) 「`.kotoba` を安全に WASM にコンパイルし WebComponent モデルで実行する」という
  問いに対し、**コンパイル部分（`kotoba wasm emit`）は元から実装済み**、**ブラウザ
  native 実行 + WebComponent ホスティングの最小疎通**が今回新たに実証された。
- (+) `kotoba-lang/kotoba` の `ADR-kotoba-wasm-clj-execution.md` に、ブラウザ host が
  実在するようになった事実を追記（同 repo の docs のみ変更、実装コードは無変更）。
- (−) host-import ABI・capability 再検証・複数モジュール構成・kototama の当初の
  「Solo5 tender」native runtime 設計（ADR-2607022400）とは**まだ接続していない**。
  本 ADR は「ブラウザという実行環境の追加」であり、tender/wasmtime ホスティングの
  follow-up をこの PoC が代替するわけではない。
- (−) 実ブラウザでの DOM 挙動確認は環境制約により未実施（Node の WebAssembly 実行の
  みで検証）。実ブラウザでの再確認は followup。
- (−) `kototama-clj` の孤立状態（manifest 未登録・GitHub 実体無し）はこの ADR では
  是正しない。将来「kototama-clj をどうするか」（正式登録するか、削除するか）は
  別途オーナー判断が必要。

## References

- ADR-2607022400（kotoba-lang/kototama/aiueos 用語確定 + Solo5 tender 方針）
- `kotoba-lang/kotoba/docs/ADR-kotoba-wasm-clj-execution.md`（JVM+Chicory 実行の実証、
  Accepted/implemented, 2026-07-02）
- `kotoba-lang/kototama/web/README.md`（本 PoC のスコープと honest R0 制約）
- `kotoba-lang/kototama/README.md`（Browser WASM AOT PoC 節）
