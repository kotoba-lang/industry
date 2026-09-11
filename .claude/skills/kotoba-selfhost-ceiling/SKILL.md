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
   A=<amu worktree at origin/main, node_modules symlinked from $R/amu>
   cd $A && nbb --classpath "<kotoba-lang wt>/src:<kotoba-lang wt>/scripts:$R/text/src:$R/kotoba-sema/src:$A/src" \
     <kotoba-lang wt>/scripts/measure-selfhost-distance.cljk --amu $A \
     --compat <kotoba-lang wt>/lang/compat --output <kotoba-lang wt>/lang/selfhost-distance.edn
   ```
   ⚠ `--compat` は**絶対 path で必ず渡す**。cwd 相対の既定が amu の cwd から解決されて
   「project path is not readable」を 225 件、*拒否の顔で*出した（2026-09-11、9 回目）。
   script は今は compat root 不在で refuse するが、refuse は測定ではない。
   ⚠ `--amu` は**共有 checkout ではなく worktree**。共有 amu checkout は bot が commit を積んで
   pin から外れており、古い sema を classpath に載せたまま測った回がある（4 回目）。
   ⚠ この block の `nbb` を `kbb --backend sci` に**書き換えない**。measure script は nbb で動く
   運用 tool で、一括書き換えが 3 回この loop の測定を壊した（detector: `verify-kbb-rewrite-path-literals`）。
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

## 壁の順番（2026-09-12 03:40、amu b6052761 / 225 source、測定 17。数は毎回測り直す）

前段（〜01:40）の突破は `git log` の測定 1〜13 に在る。02:00〜03:40 に入ったもの: reader `\b` `\f`（sema d17a490）/
amu `cli.cljk` の括弧過多（ce8d7236 混入、JVM は `.cljk` を読めないので Kotoba reader だけが検出）/ missing module の名指し
（amu 5d8e23e4）/ `kotoba.lang.coll` guest twin（kotoba-lang 75bb3c13、set 4 名 + superset?）/ **defn / defn- / fn の implicit
body sequencing**（authority 89e26ec7、sema b82c7bb3、digest 5b8af0f9、wasm32 で実測）/ 空 body 拒否文が `#?(:clj …)` 専用
body を名指し（sema 99f03fc8）。read gate 214 → 215。project route の admit はまだ 0。

**読み方の罠（測定 17 で踏んだ）**: 壁の件数は「その拒否文で止まった module 数」であって、その形の出現数ではない。
body sequencing は corpus の 272 defn に効くが、histogram では 14 → 13 しか動かなかった —— 残り 13 は「複数式」ではなく
**`#?(:clj …)` だけの body が空として読まれた**もので、拒否文が同じだった。**壁を選ぶ前に、その拒否文を出している source の
形を reader で数える**（scratchpad の `multi-body.cljk` / `empty-body.cljk` の形）。

| 壁 | 件数 | 種別 | 手 |
|---|---|---|---|
| reader（依存の中の `#?(:clj …)` が map 値位置で奇数） | 27 | 性質（features `#{:kotoba}`）→ source | 8 file / 7 repo に `:default` 節（進行中、amu dep pin 6 本） |
| `:import` / host string require | 14 + 16 | 性質 | host 層を分割（capability import） |
| missing `clojure.walk` / `clojure.edn` | 15 / 8 | 言語 compat | `lang/compat/clojure/walk.kotoba`（`:document` 上の厳密同値、進行中）/ `clojure.edn` は `document-read` の twin 候補 |
| 空 body = `#?(:clj …)` 専用 body | 13 | 性質 → source | `:default` 節（io-ipld / edn / abi / artifact / dev-protobuf の host bytes 関数）|
| `#"…"` regex literal | 9 | 性質 | source 側で `kotoba.string` へ |
| map callback の destructuring `[[k v]]` / vector-i64 got string | 9 / 9 | 値モデル | pair source / 型注釈 |
| qualified call | 9 | source | `:kotoba` 枝 / absent compat 名 |
| `some` = Clojure の述語 vs Kotoba の option 構築子 | 8 | 名前衝突（性質） | 拒否文に衝突を名指し + source 側 |
| `\c` char literal | 7 | 性質 | byte 整数へ |
| `ex-info` / `(atom …)` in def / hetero vector index | 5 / 5 / 5 | 性質 | `[:result T E]` / capability / literal index |
## JVM tool の側（compiler の外）

| tool | JVM に残る理由 | Kotoba 化の入口 |
|---|---|---|
| kagi | JDK24 PQC provider、Java import 83 file | capability kit（keychain-text / crypto）が qualified になってから whole-component |
| kbb | JVM bootstrap は interpreter。`--backend native` / `--backend sci` は JVM 不要 | 既に JVM-free 経路がある。bootstrap を退役させる |
| amu | `cljs-browser` target だけ `clojure` を起こす | 新規はその target を選ばない |
| fleet jvm-test gate | node で `kbb -M:test` | gate を nbb-test か kotoba-test に置き換える（1 gate = 1 反復） |
