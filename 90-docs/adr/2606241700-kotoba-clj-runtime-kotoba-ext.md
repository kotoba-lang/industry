---
id: adr-2606241700-kotoba-clj-runtime-kotoba-ext
title: "ADR-2606241700: kotoba の CLJ ランタイム — .kotoba 拡張子の正準化と kotodama との分離"
status: active
doc_type: adr
topic: kotoba-runtime
authoritative: true
last_verified: 2026-06-24
authoritative_for:
  - kotoba が認識する Clojure 系ソース拡張子（.kotoba / .clj / .cljc / .cljs）
  - .cljr / .cljw というエイリアスを採用しない判断
  - kotoba-clj（CLJ→WASM コンパイラ）と kotoba-kotodama（kototama アクタ基盤）の境界
related:
  - kotoba/crates/kotoba-clj
  - kotoba/crates/kotoba-lattice/src/manifest.rs
  - kotoba/crates/kotoba-kotodama
  - kotoba commit 7515cd74 "feat(kotoba): recognize + process .kotoba (#206)"
supersedes: []
superseded_by: []
---

# ADR-2606241700: kotoba の CLJ ランタイム — .kotoba 拡張子の正準化と kotodama との分離

**Status**: accepted
**Date**: 2026-06-24
**Deciders**: Jun Kawasaki

## Context

kotoba submodule における Clojure サポートと「kototama」周りの実態について、
以下の 3 点を確認・整理する必要があった。

1. `.kotoba` ファイル形式に対応しているか。
2. `.cljr`（clj rust）/ `.cljw`（clj wasm）は拡張子エイリアスになっているか。
3. CLJ ランタイムと「kototama」の関係。

コードベース調査（commit `7515cd74`「feat(kotoba): recognize + process .kotoba —
explicit lang, mesh.compile/run XRPC, editor cell (#206)」時点）で判明した実態は次のとおり。

- **`.kotoba` は実装済み。** Clojure/EDN サブセットのソースを WebAssembly に
  コンパイルして実行する正準ソース形式。
- **`.cljr` / `.cljw` は存在しない。** コードベース全体で該当文字列はヒットゼロ。
  エイリアスにはなっていない。
- **「kototama」= `kotoba-kotodama` は CLJ ランタイムとは別系統。** CLJ コンパイラ
  （`kotoba-clj`）とは直接の依存関係を持たない、独立した分散アクタ基盤。

これらが口頭・記憶ベースで曖昧になっていたため、現状の事実を ADR として固定する。

## Decision

### 1. `.kotoba` を Clojure 系ソースの正準拡張子とする

`.kotoba` は **Clojure/EDN サブセットのソースプログラム**であり、**WASM に
コンパイル**して実行する。`Lang::Clojure` に集約され、`.clj` / `.cljc` / `.cljs`
と同列に扱う（`.kotoba` が正準、他は互換）。

認識箇所（実装済み）:

- `crates/kotoba-clj/src/main.rs` — CLI が `.kotoba` / `.clj` / `.cljc` / `.cljs`
  を受理。それ以外は `--allow-any-ext` 指定時のみ許可。
- `crates/kotoba-lattice/src/manifest.rs` — `Lang::from_token`(`:kotoba`) と
  `Lang::from_ext`(`.kotoba`) がともに `Lang::Clojure` にマップ（テスト追加済み）。

サポートする言語サブセット: `def` / `defn` / `ns` / `defonce` / `do`、`if` /
`when` / `cond` / `case` / `let` / `loop` / `recur`、算術（`+ - * / quot mod rem
inc dec abs min max`）、比較（`= not= < > <= >=`）、論理（`and or not`）、文字列・
ベクタ/マップリテラル、分配束縛、相互再帰のユーザー定義関数。

処理経路:

```text
CLI   : kotoba-clj app.kotoba [args...]      # shebang を剥がして compile → run
XRPC  : .mesh.compile   (source → wasm)      # --features clj-mesh
        .mesh.run       (source → compile → run → i64, operator-gate + fuel 上限)
Editor: .kotoba コードセル（live compile + run、mesh.run 経由）
```

```clojure
#!/usr/bin/env kotoba-clj
(defn main [x] (clojure.core/inc x))
```

### 2. `.cljr` / `.cljw` というエイリアスは採用しない

現状、認識される Clojure 系拡張子は **`.kotoba`（正準）/ `.clj` / `.cljc` /
`.cljs` のみ**。`.cljr`（clj rust）/ `.cljw`（clj wasm）は実装されておらず、
**エイリアスとして追加しない**。

理由: `.kotoba` ソースは単一の言語サブセットからコンパイル先（core wasm /
WASM Component）を選ぶモデルであり、**拡張子でターゲット（rust/wasm）を分けない**。
ターゲット選択はコンパイラ/ホスト側のオプションで表現する。拡張子の増殖は
ディスパッチと UX を複雑化させるだけで便益が薄い。

> 将来追加する場合の変更箇所は 2 か所のみ:
> `kotoba-lattice/src/manifest.rs` の `from_token` / `from_ext`、
> および `kotoba-clj/src/main.rs` の拡張子バリデーション。

### 3. CLJ ランタイムと kotodama（kototama）を別系統として分離する

「kototama」はコード上は **`kotoba-kotodama`**（Kotodama runtime root）であり、
CLJ ランタイムとは**直接の依存関係を持たない別系統**として維持する。

| | 実体 | 役割 | ビルド |
|---|---|---|---|
| **CLJ ランタイム** | `kotoba-clj`（+ `kotoba-edn` reader / `kotoba-runtime` WASM host） | Clojure サブセット → WASM コンパイラ。kotoba DB 上のグラフロジックを WASM guest として書くための内部サブストレート | メイン Rust workspace の一部 |
| **kototama** | `kotoba-kotodama` | 独立した分散アクタ基盤（TS-native / T3 = Cloudflare Workers + esbuild）。lexicon contract・MCP・推論ランタイム・host SDK を含むユーザー向けプラットフォーム | 自前の `[workspace]` root を持ち独立ビルド |

`Cargo.toml` に明記のとおり、`kotoba-kotodama` は自身の `[workspace]` table を
持つネスト workspace のため、Rust workspace の member からは**除外**し独立に
ビルドする（"multiple workspace roots" 衝突回避）。

一言で言うと: `kotoba-clj` は kotoba DB の中で動く**サブストレート・コンパイラ**、
`kotoba-kotodama` はその外側にあるユーザー向け**アクタ・プラットフォーム**であり、
**「CLJ runtime = kototama」ではない**。

## Consequences

**Pros**

- `.kotoba` を正準とすることで「kotoba のソース形式」が一意になり、CLI / XRPC /
  エディタの 3 経路で一貫して扱える。
- 拡張子を増やさない判断により、ディスパッチ層がシンプルなまま保たれる。
- CLJ コンパイラとアクタ基盤の境界が明文化され、ビルド構成（独立 workspace）の
  理由が追える。

**Cons / 留意点**

- ターゲット（core wasm / Component）の区別が拡張子に現れないため、選択は
  コンパイラ/ホストのオプションを参照する必要がある。
- `kotoba-kotodama` が独立 workspace である分、Rust workspace 一括ビルドに含まれ
  ない（個別にビルド・CI する必要がある）。

## Notes

- 本 ADR は調査時点（commit `7515cd74`, 2026-06-24）の**実装事実**を固定したもの。
  `.cljr` / `.cljw` やターゲット別拡張子を将来導入する場合は、本 ADR を
  supersede する新 ADR で判断を更新すること。
- `kotoba-kotodama` 内部の設計詳細は `crates/kotoba-kotodama/CLAUDE.md` を参照。
