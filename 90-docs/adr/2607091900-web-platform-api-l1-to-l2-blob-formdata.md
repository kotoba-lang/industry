# ADR-2607091900: Web platform API を L1 (stub) から L2 (Blob + File + FormData) に引き上げる — browser compat

**Status**: accepted — landed (2026-07-09)
**Date**: 2026-07-09
**Deciders**: Jun Kawasaki
**Scope**: `orgs/kotoba-lang/browser`（`compat/quickjs_wasm.cljc` に Blob/File/FormData shim を追加）・superproject `manifest/west.yml` pin

## Context

ブラウザエンジン成熟度マトリクスで Web platform API は L1（stub）だった。compat 層は実は広く（Document/Element/Text・events・timers・fetch・WebSocket・Workers・BroadcastChannel・storage・clipboard・geolocation・history・URL・Custom Elements・MutationObserver まで）対応済みで、オーナー行列の「MutationObserver 未実装」は陳腐化していた（**行列訂正**: MutationObserver は完全実装済み）。残る現実のギャップは ResizeObserver・IntersectionObserver・Shadow DOM・DnD・Selection/Range・FormData/Blob/File。

調査で決定的だった点: **Resize/Intersection observer は `getBoundingClientRect`/layout-box geometry を必要とするが、現状 script context に geometry が露出していない**（compat shim は geometry 無し、`quickjs_wasm.cljc` L2619 のコメントで確認）。これはより大きい host-side 前提（`:layout/box` capability + layout パスまたぎの配管）で、信頼性優先の L2 には重すぎる。

## Decision

L2 を「binary & form-data API（FormData + Blob + File）」で着地する — survey の #3 ギャップ。純粋なデータ構造でエンジンの deterministic・capability-based モデルに cleanly 適合し（geometry 不要）、実 QuickJS WASM 上の CLJS smoke test で本物検証できる。

- **Blob**: インライン UTF-8 codec で byte-faithful。String/Blob/ArrayBuffer/typed-array parts。`size`/`type`/`slice`（負の引数含む）/`text()`/`arrayBuffer()`。
- **File**: Blob + `name`/`lastModified`。`File.prototype = Object.create(Blob.prototype)` で `instanceof Blob/File` 両方成立。
- **FormData**: `append`/`set`/`get`/`getAll`/`has`/`delete`/`forEach`/`entries`/`keys`/`values`/`[Symbol.iterator]`。String 値と Blob/File 値を受け付け（filename 指定時、非-File Blob を File で包む＝仕様）。`new FormData(formEl)` はフォームの送信可能コントロールを列挙（既存の `__kotobaFormControl`/`__kotobaControlValue`/`__kotobaDisabledControl` を再利用）、unnamed/disabled/button/submit/image/reset/file 入力と未チェック checkbox/radio をスキップ。

## Consequences

- Web platform API L1→L2。`new Blob(['café']).size === 5`（UTF-8 byte fidelity）、`new FormData(formEl)` が checked checkbox と selected option を正しく拾い button/unchecked を除外する — すべて実 QuickJS WASM で PASS 確認。
- 整形式既存動作への回帰なし。MutationObserver を含む全 compat 機能は CLJS node-test 185/278/0 で再確認。

## Levels

- Web platform API: L1 (stub) → L2 (FormData + Blob + File; survey's #3 gap filled).
- L3（未着手）: Blob.stream()（ReadableStream 未実装）、fetch() の FormData body 対応（multipart/form-data encoding）、input[type=file] の実 File エントリ化、**ResizeObserver/IntersectionObserver**（`getBoundingClientRect`/layout-box の host-side capability が前提 — これが今回 observers でなく FormData/Blob/File を選んだ理由）、Shadow DOM、DnD、Selection/Range。

## Test status

- JVM: 700 tests / 3274 assertions / 0 fail（source-presence + 回帰なし）。lint 0 errors。
- CLJS node-test（実 QuickJS WASM）: 185 / 278 / 0、新規 Blob/File/FormData end-to-end smoke 含む（PASS）。
