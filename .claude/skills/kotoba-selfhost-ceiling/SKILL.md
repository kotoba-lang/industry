---
name: kotoba-selfhost-ceiling
description: JVM / Clojure に依存している toolchain（amu・kotoba-sema・kbb・kagi・fleet gate）を Kotoba で動くようにする「天井突破」を 1 反復ぶん進める。1 反復 = 測る → 一番大きい壁 1 つ → compiler を広げるか source を直す → 着地 → pin → 測り直す。「selfhost」「天井突破」「JVM を外す」「amu が自分を compile できない」「kotoba で動くように」で発火。手で `/kotoba-selfhost-ceiling` と打つ。
---

# Kotoba の天井を 1 段突破する

**正本は superproject ADR `adr-2609111900-jvm-free-toolchain-selfhost-ceiling-loop`。**
この skill はその 1 反復ぶんの手順書で、会話履歴を持たない fresh context から
読めるように書いてある。前の反復が何をしたかは会話ではなく
**`orgs/kotoba-lang/kotoba-lang/lang/selfhost-distance.edn` の差分**から読む。

## なぜ要るか（2026-09-11 実測）

全 Clojure source を `.cljk` に改名した（adr-2609111500）。nbb は fork
（`kotoba-lang/org-babashka-nbb`）で `.cljk` を classpath 解決するが、
**JVM Clojure（`clojure.lang.RT.load`）は `.clj` `.cljc` 固定で逃げ道が無い。**
だから JVM にしか無い経路（kagi の PQC、kbb の JVM bootstrap、amu の
`cljs-browser` target、fleet の jvm-test gate、JVM test suite）は改名の瞬間に
止まった。owner の決定は「shim ではなく Kotoba で動くようにする」。

**それまでの間、JVM tool は「最後の改名前 sha の worktree」から動かす。**
`git worktree add --detach <tmp> <pre-rename-sha>`（sha は各 repo の
`cljk-origin.edn` の隣、または改名 commit の親）。sibling の `:local/root`
依存も同じ配置で切る。これは historical build であって、main を戻すことではない。
実例: kagi をこれで起動して fleet 署名鍵を vault から復元した（2026-09-11）。

## 1 反復の手順

1. **測る。** kotoba-lang の worktree で:
   ```bash
   R=/Users/junkawasaki/github/com-junkawasaki/orgs/kotoba-lang
   cd $R/amu && kbb --backend sci --classpath "<kotoba-lang wt>/src:<kotoba-lang wt>/scripts:$R/text/src:$R/kotoba-sema/src:$R/amu/src" \
     <kotoba-lang wt>/scripts/measure-selfhost-distance.cljk --amu $R/amu --output <kotoba-lang wt>/lang/selfhost-distance.edn
   ```
   読むのは **`:project-check-gate :by-message`**（single-file の
   `:namespace-require-needs-project` は harness であって言語の壁ではない）。
   ⚠ `:passed 0` は「何も admit されない」であって「測れなかった」ではない ——
   `:source-count` が 0 なら測れていない。
2. **一番大きい壁を 1 つ選ぶ。** 件数順。ただし **壁が「性質」か「実装状態」かを
   判定してから**（CLAUDE.md「規則を制約として持ち出す前に」）。性質（例: ambient
   authority）なら source 側を直す。実装状態（例: reader が `#?` を知らない）なら
   compiler 側を広げる。**dual-runtime を壊す形は選ばない** —— source は改名後も
   nbb で動き続けなければ compiler を build できない（`(:export …)` を ns に書くと
   nbb が `No matching clause: export` で拒否する、が最初の実例）。
3. **広げるなら authority から。** `kotoba-lang/lang/guest-grammar.edn` →
   `lang/vendored-copies.edn` が名指す copy 全部 → `kotoba-sema` の frontend →
   `amu` の project loop → amu の sema pin を進める。**frontend が受理することと
   authority が認めることは別**（ADR-2607279200）。
4. **両方向を出す。** 正: 新しい綴りが admit され、旧綴りと同じ `:exports` /
   同じ DefCID。負: 未知 key は拒否、二重宣言は拒否、位置違いは拒否。
   test は nbb の suite（`run-tests.cljk`）で緑にする —— JVM suite は動かない。
5. **実 source で証明する。** amu 自身の module を 3 本以上、新しい綴りで書いて
   `amu check --source-path … --jvm-free` が **次の壁に進む**ことと、
   `kbb --backend sci --classpath src -e "(require 'ns)"` が**まだ通る**ことの両方を見る。
6. **着地 → pin → 測り直し。** branch push + `POST /merges`、
   `scripts/west-pin-put-batch.cljk`（または scratchpad の memo 化版）で pin、
   手順 1 を再実行して `selfhost-distance.edn` を更新して commit。
   **件数が動かなかった反復は失敗として記録する**（緑でも赤でもなく「変わらなかった」）。

## 壁の順番（2026-09-11 夜、amu 79e07a5a / 210 source で測り直した分。数は毎回測り直す）

同日中に突破済み（compiler 側）: ns attr-map `{:kotoba/export …}` / reader の `#_` `#?@` `#:ns{}` `\uXXXX`
`'form` `\c` `0N` `1.5M` 9+ 要素 int set、`#"…"` `#js` は tagged form として**読んで**選ばれたら名指し拒否 /
`:refer` / `:refer-clojure` no-op / docstring bound 64 KiB / keyword-key map literal の値型混在 → closed record /
`into` の 1 段 transducer / `#=` の拒否を reader dispatch へ。read gate は 103/178 → 201/210。

| 壁 | 件数 | 種別 | 手 |
|---|---|---|---|
| `:export` 未宣言 | 58 | source | `scripts/annotate-kotoba-export.cljk`（amu）を未 annotate の repo / pin hold（io-ipld, json）に当てる |
| `qualified call is not an admitted exported import` | 24 | source | `(:require …)` が `#?@(:clj … :cljs …)` の中で `:kotoba` 枝が無い → `:kotoba` 枝を書く |
| `:import` / host module string require | 23 + 17 | 性質 | host 層を module 分割（capability import） |
| missing module（`clojure.set` `clojure.walk` …） | 22 | 言語 stdlib | `kotoba-lang/lang/compat/clojure/*.kotoba` に**厳密同値**だけ足す（近似を本名で置かない） |
| reader（`:clj` 専用 key で奇数になる map 等） | 19 | source | `:kotoba` 枝 |
| `def` の非定数（純粋式） | 9 | 実装状態 | compile-time folding（反復進行中） |
| `ex-info` 呼び出し | 5 | 性質（untracked control effect） | 境界を `[:result T E]` に |
| `record-get … got :i64`（keyword→record table の動的 key lookup） | 2+ | 実装状態 | `def` table を `[:map :keyword R]` に型付ける経路 |

## JVM tool の側（compiler の外）

| tool | JVM に残る理由 | Kotoba 化の入口 |
|---|---|---|
| kagi | JDK24 PQC provider、Java import 83 file | capability kit（keychain-text / crypto）が qualified になってから whole-component |
| kbb | JVM bootstrap は interpreter。`--backend native` / `--backend sci` は JVM 不要 | 既に JVM-free 経路がある。bootstrap を退役させる |
| amu | `cljs-browser` target だけ `clojure` を起こす | 新規はその target を選ばない |
| fleet jvm-test gate | node で `kbb -M:test` | gate を nbb-test か kotoba-test に置き換える（1 gate = 1 反復） |
