# ADR-2607122300: kotoba 言語の bool 演算子ギャップ完全解消 — WASM compiler に pos?/neg?、interpreter に when/and/or

**Status**: accepted
**Date**: 2026-07-12
**Deciders**: Jun Kawasaki
**Scope**: `orgs/kotoba-lang/kotoba`（`src/kotoba/runtime.clj`）, `manifest/west.yml`（kotoba pin）

## Context

ADR-2607072530（6511）と ADR-2607072600（6492）は、`.kotoba` 金融 kernel の移植で
`pos?`/`neg?`/`and`/`or`/`when` の `:unsupported-op` に独立に遭遇し、nested `if` で
回避していた（両 ADR に「本 ADR では修正しない」と記録された既知の実バグ）。
ADR-2607122100 Track A の wasm 工場前作業として今日このギャップを閉じに行ったところ、
上流 main では `and`/`or`/`when` の **WASM 側**は既に実装済み（`desugar-and`/`desugar-or`
+ `when`→`if` 縮約、`kotoba.wasm-and-or-when-test` あり、bitwise ops と同期に着地）で、
残る非対称は (1) WASM compiler の `pos?`/`neg?` 欠落、(2) **interpreter 側**の
`when`/`and`/`or` 欠落（`eval-form` の special form に無く unknown symbol で落ちる）、
の 2 つだった。

## Decision

`kotoba-lang/kotoba` main `a532780a` として着地:

1. **WASM compiler に `pos?`/`neg?`** — `(> x 0)` / `(< x 0)` への縮約
   （既存 `result-ok?`/`result-err?` と同形式、`zero?`/`not` の隣）。
2. **interpreter に `when`/`and`/`or`** — `truthy?` ベース（nil/false のみ falsy）で
   `if` と同じ真偽則。Clojure 同様 `and`/`or` は値返し・短絡。
3. **backend 間の真偽則乖離を docstring に明文化** — 整数 0 は interpreter では
   truthy、WASM i32 では falsy。これは既存 `if` と同じ注意点で、新設の parity
   fixture `src/demo_bool.kotoba` は比較結果のみで分岐して両 backend 一致（=221）を
   テストで固定。
4. テスト: 対象 3 namespace 81 tests / 412 assertions green、full suite 0 failures
   （5 errors は素の main でも再現する sandbox ネットワーク起因の mesh-node HTTP
   既存事象）。clj-kondo 0 errors / 0 warnings。
5. west pin 前進: kotoba `546b28e2` → `a532780a`（API single-entry `88586c18`、
   compare 検証 ahead 12 / behind 0。初回 PUT は 409 → 楽観ロック通り取得し直して成功）。

## 工場方針との整合（重要）

本 ADR は「`.kotoba` emit の一斉再開」ではない。**cloud-itonami の safety kernel
群はオーナー決定（2026-07-12）により ClojureScript + kotoba-datomic first** で、
`.kotoba`/wasm emission は意図的に未配線（各 `kernels/gate.cljc` の docstring と
ADR-2607121200 系の cljs-first suite に明記。本日 fleet が wave-0 金融 18 衛星中
9 本に kernel 抽出を実施中: 6419/6492/6499/6511/6512/6520/6611/6621/6622）。
本 ADR の位置づけは「**emit の扉を開けておく**」側の言語整備 — kernel が safe-kotoba
subset に留まる限り、将来 emit を配線する時に nested-if への书き直しが不要になる。
6611 等の個別 kernel を今 `.kotoba` 化する作業は、この決定に逆行するため**行わない**。

## Consequences

- (+) `.kotoba` 言語表面（if/when/let/do/and/or/not/比較/算術）が interpreter と
  WASM compiler で初めて対称になった。ADR-2607072530/2607072600 の既知バグ 2 件解消。
- (+) 将来の kernel → `.kotoba` emit 移行時、safe-kotoba subset のコードがほぼ
  そのままコンパイル可能（nested-if 回避策が不要に）。
- (−) 整数 0 の真偽則乖離は解消していない（意図的仕様。fixture の書き方で回避 —
  比較結果でのみ分岐する）。
- (−) 共有 checkout `orgs/kotoba-lang/kotoba` は別セッションの WIP（`1bca9fa3`
  typed language contracts）が居るため FF 追従は見送り（pin は API 経路なので不影響）。

## Artifacts

- kotoba-lang/kotoba main `a532780a`（feature branch はマージ後削除、worktree 撤去済み）
- `src/demo_bool.kotoba`（parity fixture）+ `wasm_exec_test.clj` 追加 2 deftest
- superproject west.yml `88586c18`（kotoba pin 前進）
- 本 ADR とペアの `.edn`

## References

- ADR-2607072530 / ADR-2607072600（バグの初出と回避実装）
- ADR-2607101200(kotoba = language / kototama = runtime の vocabulary lock)
- ADR-2607122100(労働解放 SD モデル — Track A wasm 工場の文脈)
- 6419 の `Merge feat/cljs-first-safety-kernel`(cc3e5b2, ADR-2607121200 系)
