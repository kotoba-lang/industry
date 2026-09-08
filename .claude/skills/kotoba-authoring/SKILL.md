---
name: kotoba-authoring
description: "`.kotoba` / `.cljk` を書く・読む・移行する・その天井を判定するときの正本。runtime 優先順位（kotoba wasm → clojurewasm → cljs → nbb →（降格）JVM/bb）、Rust/sh/.mjs を新規に書かない規則、legacy emitter ではなく amu compile 経路を使う理由と実測済みブロッカー、`.kotoba` で「書けない」の恒久（安全設計）と一時（backend 未達）の分類法、native AOT の現在地の読み方、design system 5 repo の移行順、JVM-free acceptance（Q9）。「kotoba を書く」「cljk」「amu compile」「kotoba wasm」「capability kit」「native backend」「kbb」「.kotoba に移行」「この機能は kotoba にあるか」で発火。CLAUDE.md の runtime 節から切り出した正本。"
---

# `.kotoba` を書く

**CLAUDE.md の「`.kotoba` / runtime」節はここへ委譲している。** CLAUDE.md 側には
skill を読まなくても効く不変条件だけが残っており、手順・実測・現在地の読み方は
この文書が正本。

以下は CLAUDE.md から**逐語で**移した本文である（2026-09-08、ADR-2609081000）。

## `.cljc` / `.kotoba` ランタイム優先順位（2026-07-10 改訂。2026-07-07 改訂・初版は2026-07-06）

### Kotoba は safe application language とする（repo-wide mandatory rule、2026-07-20）

- **`.kotoba` を純粋な narrow-slice decision function だけに限定しない。**
  ADR-2607201300 に従い、`kotoba/pure`、`kotoba/cell`、`kotoba/app`、
  `kotoba/host` の4 profile を区別する。新規アプリの product logic、workflow、
  UI view/event reducer、LLM/tool loop、明示的 state machine、actor behavior、
  supervision policy は、必要な capability が実装済みなら `kotoba/app` を
  第一候補にする。ADR-2607141900 は削除済みであり、ADR-2607150000/
  2607151500 内の narrow-slice/general-application exclusion も superseded である。
  これらを active policy や application-scope ceiling として引用してはならない。
- **安全性の境界は purity ではなく ambient authority の排除である。** 外部から
  観測可能な effect は、型付き capability value、静的 effect set、package lock、
  deny-by-default policy、quota/fuel/memory、audit を必ず通す。`atom`/`ref`、process
  global、任意 `require`、`eval`、reflection、Java/JS interop、直接 DOM/SDK/socket/
  credential access を application code に追加して穴を埋めない。
- **状態は `state + event -> next-state + effects` として記述する。** 永続化、
  transaction、queue、timer、actor placement/recovery、DOM/WebGPU/native mutation、
  LLM provider transport は `kotoba/host` provider が担当し、結果を typed event として
  app に戻す。host が機構を所有しても product semantics は `.kotoba` が正本である。
- **UI/LLM/state/actor/lifecycle capability は descriptor、effect inference、compiler
  admission、policy-gated provider、positive/deny fixtures、quota/audit、2 runtime parity
  が揃うまで「実装済み」と扱わない。** unrestricted interop や doc だけで readiness
  を宣言しない。最初の vertical proving slice は shiropico の
  state → LLM/ComfyUI effect → result event → governor → UI → checkpoint とする。
- 言語側の詳細規則は `orgs/kotoba-lang/kotoba/docs/lang/application-profile.md` を参照。

- **repo wide のルール: app の互換性と「第一の runtime」の順序は
  `kotoba wasm runtime` > `clojurewasm` > `ClojureScript` > `nbb` とし、
  `JVM` と `bb`（babashka）はその下に降格する（どちらも最後の手段。
  2026-07-10 オーナー指示）。** 新しく書く app / library / `.cljc` /
  `.kotoba` は、この順で「どの runtime を第一級に据えるか」を決め、
  reader-conditional 分岐・依存選定・テストの正本もこの順序に合わせる。
  上位 runtime で動くものを JVM / bb 前提で書かない。JVM / bb にしか無い
  経路（Chicory テストハーネス、既存 JVM 専用 lib への互換層など）は
  「互換 (compat) 層」として明示的に隔離し、設計の前提にしない。
  実例（この規則の模範実装）: `kotoba.kami-host`（ADR-2607100030
  addendum 2）— ECS core を portable `.cljc` に置き、第一の実行経路は
  ClojureScript（browser ESM / nbb ネイティブ WebAssembly）、`:clj`/
  Chicory 層は互換スイート専用と docstring に明記。
- **Rust（`kami-render`/`kami-app` 等の既存エンジン）を、個別 app/game の
  描画要件を満たすために新規 crate として書き足さない（2026-07-10 追記、
  オーナー指示: 「rust は使わないで、これはちゃんと rule に」）。** 上記の
  runtime 優先順位はいずれも「app/game 側」コードの選び方であり、Rust
  エンジン本体は所与のインフラとして**消費するだけ**の対象——個別アプリの
  描画ニーズのために新しい Rust crate（`#[wasm_bindgen]` エントリポイント
  + カスタムレンダーパイプライン等）を書き起こす選択肢は、この優先順位
  チェーンに含まれない。実例: `kami-app-animeka-timeline`
  （`orgs/etzhayyim/root/40-engine/kami-apps/`）は Rust crate を新規に
  書いた先行実装だが、これは本規則の明文化前のパターンであり、以後の
  新規タスクでこれを模倣しない。描画が要る app/game は、既存 Rust エンジン
  が既に露出済みの WASM/JS 境界があればそれをそのまま呼ぶだけに留め、
  無ければ上記 kotoba wasm → clojurewasm → ClojureScript → nbb の範囲内で
  実現方法を探す——それでも描画ニーズを満たせない場合、**「Rust を新規に
  書く」ことで穴を埋めない**。スコープを絞る（例: 当面は DOM/CSS の視覚
  表現に留める）か、対象を決めて別途 ADR 化しオーナー判断を仰ぐ。
- **運用 tooling の script host は nbb のみ（ADR-2607173000、2026-07-17）— ただし将来
  優先順位は `kbb`（Kotoba script host）→ `nbb` →（退役: `bb`）（ADR-2607181900、
  2026-07-18 roadmap 決定）。`kbb` は **実在する**（`kotoba-lang/kotoba` の `bin/kbb`。
  backend は interpreter = JVM bootstrap / `--backend native` = amu KEXE + kexe_loader /
  `bin/kbb_js.cljs` = amu `--target js` + Node host、ADR-2609051100・ADR-2609062200）が、
  ADR-2607181900 の readiness gate（nbb スクリプト代表サブセットの移植 = 条件②）を
  通過するまでは以下の nbb-only ルールがそのまま正本のまま変わらない。
  運用スクリプトを kbb 前提で書かない。**
  ⚠ **例外（owner 指示 2026-09-07）: 新規に書く運用 tooling は kbb-first。**
  nbb は既存資産の実行環境として残るが、新規 script host としては禁止。
  手順は skill `nbb-to-kbb-migration`。kbb に無い op は guest を縮めず
  kbb 側に足す（capability extension）。既存 nbb スクリプトは
  migration candidate として列挙し、readiness が追いついたところから順に移行。
  **kbb 用の `.kotoba` script を書くときの実測済み規則**（2026-09-07、ADR-2609062200）:
  ①capability は `lib/kbb/{fs,env,browse,proc,str}.kotoba` 経由で呼び、script に
  `typed-cap-call` や wire id を書かない（`--source-path lib`）。②policy は kbb v1 と
  同形（`:kotoba.policy/forbid-wildcard true` 必須、resource scope 必須）。③**effect の
  無い i64 `main` は compile 時に KIR oracle でも実行される**ので、その fuel には
  `--fuel` が届かない —— probe は capability を 1 回は呼ぶ形にする。④fs write は
  `kbb.fs/write-file`（request `<path>WRITE_SEP<content>`、native loader と同じ契約）で、
  path + 9 + content が 65536 byte を超えると provider の前に `string-too-large` で止まる。
  ⑤backend は `bin/kbb … --backend js|native|interpreter`。js は oracle、配布形は
  native。同じ script・policy で両方を走らせて同じ値になることが parity の証拠
  （kotoba `test/kotoba/kbb_js_test.clj` の形）。
  `scripts/*.cljs`・`.claude/hooks/*.cljs`・west 拡張・child repo の
  task/test オーケストレーションは **`bb` バイナリを使わない**。新規に
  `bb.edn` / `#!/usr/bin/env bb` を置かない。残存は Wave 1–4 で削除中
  （共有 `.bb` 族 → scaffold `bb.edn` → 大型 `bb.edn` → ゲート）。
  `scripts/nbb_compat` の `babashka.*` **名前空間**は Node 互換シムであり、
  `bb` 実行を意味しない。app runtime としての `bb` 降格（JVM と並ぶ最下位）
  は従来どおり維持。
- **新規の運用 tooling を nbb で書かない — kbb-first（owner 指示 2026-09-07）。**
  新規に書く運用スクリプトは kbb（`.kotoba` / `.cljk` + kbb runtime）で書く。
  手順は skill `nbb-to-kbb-migration`。既存 nbb スクリプトは migration candidate。
- **Node 側の検証/テストハーネス（Playwright driver、静的サーバ、E2E
  スクリプト等）も新規に書く場合は nbb（`.cljs`）で書く — 生 JS の
  `.mjs`/`.cjs` を新規に書かない。** シェルスクリプト（`.sh`）も同様に
  **新規作成禁止 — nbb で書く**（2026-07-14 オーナー指示「sh は prohibit, nbb にして」。
  既存の実例移行: itad `tools/subset_font.cljs`、jp-go-dds `scripts/vendor.cljs`）。 既存 repo に `.mjs` の先行実装
  （例: `wasm-webcomponent/test/render/lib/webgpu-harness.mjs`）があっ
  ても、それは「対象を決めて ADR 化してから移行する既存資産」（既存の
  JVM 専用ライブラリを書き直さない原則と同型）であって、新規タスクで
  それをコピー/踏襲してよい前例にはならない — 中身のロジック（技術的
  knowledge: full Chromium 実行パス解決・静的サーバ・`navigator.gpu`
  可用性チェック等）は参照してよいが、新規に書く実装は必ず nbb に翻訳
  する（実例: ADR-2607100100 M2、2026-07-10 owner 指摘で `.mjs` harness
  を nbb 版に置き換え）。
- **`kotoba wasm`** — `.kotoba` 拡張子（legacy emitter の基礎サブセット —
  `def`/`defn`/`ns`/`if`/`when`/`let`/`do`/算術/比較/`and`/`or`/`not`/
  文字列基本操作 + 再帰のみ、Java/JS interop 一切なし、サードパーティ lib
  不可。Application Profile の effect は閉じた capability import として段階追加）を
  `kotoba wasm emit` で WASM にコンパイルし、`kototama` の
  `actor:host` ABI（`kototama.contract`/`kototama.tender`, ADR-2607062330/
  2607062400）でホストする経路。**2026-07-06 版と異なり、これは今や実在し
  E2E で動作確認済み**（ADR-2607062330 addendum 5、2026-07-06〜07）:
  `kotoba-core-contracts` の閉じたホストインポート表に `.kotoba` から呼べる
  capability を登録し、`kotoba wasm emit` が実際に出力した（手書き WAT では
  ない）`.wasm` が `kototama.tender`（JVM/Chicory）にリンクして正しく実行
  することを確認済み（`kotoba-lang/kototama` の `test/kototama/fixtures/`
  に実バイナリとして checked in）。ホストは JVM/Chicory 経路
  （`kototama.tender`）と ブラウザネイティブ経路
  （`wasm-webcomponent` の `actor-host.js`、ADR-2607062400）の両方が実在。
- **`clojurewasm`** — `.kotoba` の極小サブセットではなく**フルの Clojure**
  を書きたい場合の次点。外部プロジェクト
  [`clojurewasm/ClojureWasm`](https://github.com/clojurewasm/ClojureWasm)
  （通称 `cljw`）: Zig で書かれた JVM フリーの Clojure ランタイムで、
  WebAssembly を FFI として呼べる（`(wasm/load "mod.wasm")` /
  `(:require ["comp.wasm" :as c])` で WASM component を名前空間のように
  require できる）。2026-02 発足、2026-07 時点で v1.0.0 安定版・実働デモ
  （cw-playground, cw-serverless-demo, cw-arcade）あり、157 stars、直近まで
  push されている活発なプロジェクト（確認日 2026-07-07）。**ただし
  Issues/PR は現在受け付けていない**（小規模チームのため。EPL-2.0）ので、
  このリポジトリへの直接貢献はできず「利用する」側の依存としてのみ扱う。
  **2026-07-10 時点でもこのモノレポ内に `clojurewasm`/`cljw` の利用例・
  ビルド・統合は無い**（既存 `.cljc` を勝手に `cljw` 前提に書き換えない —
  導入する場合は対象を決めてから着手する）。2026-07-10 に実適用を検討した
  実測: cljw v1.0.1 の FFI は `wasm/load`+`wasm/call`（import-free module
  専用）で、**Clojure 製 host import の提供は upstream 自身が「Phase-16 の
  fuller FFI surface」として将来に明示**（`docs/examples/wasm/README.md`）。
  host import を要する guest（例: kami-survivors の 12 imports）は現状
  ホストできないため ClojureScript に落ちる（ADR-2607100030 addendum 2）。
  Phase-16 が landed したら再評価する。
- **`ClojureScript`（cljs）** — ブラウザ/Node 向け。次点。
- **`nbb`** — ClojureScript-on-Node の高速スクリプティング。静的サイト生成
  など軽量タスク向け（実例: `kototama/web/generate.cljs`）。
- **JVM 単体と bb は最後の手段（app runtime として）。** 既存の JVM(`:clj`)
  専用ライブラリは、実装当時「唯一動く経路が JVM だった」という正しい判断の
  結果なので、上位の選択肢が実在するようになった今もリトロアクティブに
  書き直さない（移行する場合は対象を決めて ADR 化してから着手する）。
  ⚠ **ただしこの一覧を「今どれが JVM 専用か」の答えとして引かない。**
  ここは長く `kotoba-lang/ed25519`（現 `org-ietf-ed25519`）を例として挙げて
  いたが、**2026-08-27 の実測でそれは誤りだった** —— `edwards.cljc` と
  `scalar.cljc` は reader conditional が **0 個**、`sign.cljc` の 2 個は hex
  整形だけで、`test/nbb_smoke.cljs` は cljs の署名が JVM と**バイト一致**する
  ことを assert している。移行はとうに済んでいて、**それを書いた文だけが
  古かった**。この誤った記述を根拠に「この workspace に portable な署名は
  無い」と結論し、ADR に書き、次の作業の前提にしかけた（ADR-2608271200）。
  **JVM 専用かどうかは repo の `#?(:clj` を数えて決める。ここを引かない。****script host としての bb は ADR-2607173000 で退役** —
  app を bb 前提で新規に書かないのはもちろん、運用スクリプトも nbb に寄せる。
- `#?(:kototama ...)` / `#?(:clojurewasm ...)` という reader-conditional は
  **コードベース全体を検索してゼロ**——Clojure 標準は `:clj`/`:cljs`/
  `:cljr`/`:default` しか認識せず、これらを feature として認識させるカスタム
  reader/ビルドステップは存在しない。**存在しないものとしてこれらの
  reader-conditional を書かない**（無言でどちらの分岐も評価されない dead
  branch になる）。
- 新しい `.cljc`/`.kotoba` を書く／既存の `:clj`/`:cljs` 分岐を拡張する判断に
  迷ったら、上記の順序（kotoba wasm → clojurewasm → cljs → nbb →（降格:
  jvm / bb））で「今実際に動く経路はどれか」を確認してから選ぶ——ただし
  目の前のタスクを止めてまで存在しない統合（例: `clojurewasm` の新規導入）を
  今から作ることはしない（別スコープの ADR とプロジェクトとして切り出す）。

## `.kotoba` を書くときは `compile` 経路を使う — legacy emitter は使わない（repo-wide mandatory、2026-07-27）

> **方向の正本は ADR-2607279200（accepted）と
> `orgs/kotoba-lang/kotoba-lang/docs/kotoba-centered-migration-plan.md`。**
> 本節と ADR-2607270100 が記すのは *2026-07-27 時点で実測した現在地* であって到達目標ではない。
> 現在地の制約（再帰値・explicit capability 等）を恒久的な設計前提として引用しないこと —
> 計画側で解消予定のものが含まれる。両者が食い違ったら ADR-2607279200 が勝つ。
>
> **source-surface の唯一の authority は `orgs/kotoba-lang/kotoba-lang/lang/guest-grammar.edn`**
> （ADR-2607279200 Delivery #1）。`compiler/frontend.cljc` が受理することと authority が
> 認めることは**別物**で、両者の drift は機械検査で潰す対象。文法面を触るときは
> frontend ではなく authority を先に見る。

**kotoba には独立した2つのコンパイラ面があり、新規の `.kotoba` は必ず後者
（`amu compile` → `kotoba-lang/amu`）で書く。** legacy emitter
（`kotoba wasm emit` / `kotoba cljs emit`）は単一ファイル・貧弱な型・127 バイト文字列上限を
持つ旧経路であり、その制約を「Kotoba 言語の限界」と誤認しない（実際に 2026-07-27 の spike が
この取り違えをやった）。

⚠ **compiler repo は `kotoba-lang/compiler` から `kotoba-lang/amu`（編む）に改名済み。**
旧名は GitHub リダイレクトで生きているが、**west の `compiler` entry は撤去済み**
（実測 2026-09-06: `manifest/west.yml` に `name: compiler` は 0 件、`manifest/fleet-db.edn`
にも 0 件、`orgs/kotoba-lang/compiler` の checkout も無い）。2026-08 の改名直後は
2 entry が並存し古い pin の checkout を読む事故があったが、その状態はもう無い。
読むのも走らせるのも `orgs/kotoba-lang/amu` 側にする。CLI の front は `bin/amu`
（`bin/kotoba` / `bin/kotoba-compiler` は互換 shim）。native backend は
`kotoba-lang/kotoba-native`、KIR は `kotoba-lang/kotoba-kir`、restricted-ESM emitter は
`kotoba-lang/kotoba-script`、実行/runtime linking は `kotoba-lang/kototama` に分かれている
（ADR-2608139980 の 綾 分割）—— **amu に無いからといって「無い」と結論しない。**

| | legacy（`wasm emit` / `cljs emit`） | **`compile`（使うのはこちら）** |
|---|---|---|
| モジュール | 単一ファイルのみ | 複数ファイル閉グラフ `(:require [m :as a])` + `(:export [...])` |
| ターゲット | wasm32 / cljs テキスト | wasm32 + `:js-kotoba-v1` restricted ESM |
| 型 | i32/i64/f32 と生メモリ | `[:map K V]` `[:set T]` `[:record ...]` `[:variant ...]` `[:option T]` `[:result T E]` 異種 `[:vector ...]` `:document` `:string-index` |
| 数値 | cljs 側は 2^53 で throw | BigInt i64 + `assertI64` |
| 文字列 | **127 UTF-8 バイト上限** | EDN 1 MiB / string leaf 64 KiB（実測: 4,920 バイトの HTML 断片を構築可） |
| capability | host-import 表（id 201+） | capability-registry（id 1–12）+ 型付き kit |

- **型注釈はインライン構文で、いま必要なものだけ書く**（2026-09-01 改訂）:
  `(defn f [p :string n] body)`。legacy の `^:i64` メタデータ形式ではない。
  - **注釈は per-parameter**。1 つ書いたら全部書く規則は無くなった（kotoba-sema
    `14b5536`）。
  - **未注釈パラメータは body が要求する型を取る**（同 `0b0b31e`）。制約は型検査器
    自身の拒否から読むので、operand 型の第 2 の表は存在しない。
  - **結果型も省ける**（`infer-absent-results`）。
  - 書く必要が残るのは、**body が要求しない**型だけ。実例: `or` of two `=` は i64 を
    返すので、`(if (and has-x ...) ...)` の `has-x` は `:bool` と書かないと `if` の
    分岐型が食い違う。
  - ⚠ **書かれた注釈は決して推論で上書きされない。** 用途が食い違うパラメータは
    `:i64` に戻り、以前と同じ場所で同じメッセージで落ちる。
  - 実測 2026-09-01: `org-ietf-smtp` の 3 modules から 85 個中 **81 個**を外して、
    生成 wasm32 は**バイト単位で同一**。残った 3 個は上の `:bool` 3 つ。
- **`defdesugar` は使える**（2026-08-31、kotoba-sema `dae81ee`）。
  `(defdesugar clamp [x lo hi] (if (< x lo) lo (if (> x hi) hi x)))` を書いて
  `(clamp n 0 6)` と呼ぶ。**macro ではない** —— registered な head だけが展開され、
  body は**それより前に宣言された** template に対してだけ展開されるので再帰は
  構造的に不可能、引数は 1 度だけ synthesized name に束縛される（= 複数評価も
  capture も起きない）。個数・arity・body node 数・総展開数はすべて有界。
  ⚠ **`defmacro` の代わりに使えるのはこれだけ。** ADR-2608301500 が defmacro 恒久禁止の
  根拠に据えているのがこの機構であり、2026-08-31 まで**実装が存在しなかった**。
  なお同 ADR の fixture が使う `match` は今も未実装。
- capability は今のところ `(ns x (:capabilities #{:ui/commit}))` + `(cap-call :ui/commit v)`、
  policy は `{:allow #{[:cap/call 9]}}` と書ける（宣言したのに使わないとコンパイルエラー）。
  **ただしこれを「effect の書き方」として広めない。** ADR-2607279200 §2 は
  「通常の source は capability ID / WIT import / provider callback を記述しない」、
  Consequences は「`cap-call`・wire ID は compiler/host 内部へ押し下げられる」と定める。
  **effect は推論が既定**で、明示宣言が正当なのは公開 API・package 境界・security ceiling、
  および scope/quota/deadline を attenuate して委譲する場合だけ。数値 ID は wire ABI であって
  source 語彙ではない（正本は `lang/capability-semantics.edn` の
  `:cap/kind`/`:cap/resource`/`:cap/holder` という名前付き scoped モデル）。
- **`:js-kotoba-v1` の成果物は `kotoba-js-artifact/v1`**: `instantiateKotoba(grants)` を
  export し、grant が `requiredCapabilities` と厳密一致しなければ
  `capability-grant-mismatch` で instantiate 自体が落ちる（実行時も fail closed）。

### 再帰的な値型は landed（W4）— flat/handle 設計を恒久前提にしない

**この節は 2026-08-08 に書き換えた。** 旧文は「今日は hiccup のような任意深度の入れ子を
Kotoba の値として表現できない」と書いていたが、W4 は 2026-07-27 に 6 スライスまで landed
している（migration plan の W4 節が各スライスを記録）。第 5 スライス
（`recursive_tree_value_test`、compiler#343 + kotoba-kir#10 + kotoba-script#71）が
**sealed schema-checked tree としての recursive logical value** を、第 1〜4 スライスが
`:document` 値（構築・walk・digest・`document-sha256`・DOM reconcile）を入れている。
**backend は `#{:compiler :kotoba-wasm :kotoba-cljs}`。native には無い**（下記の新しい規則の
とおり、これは backend 未達であって言語の天井ではない）。

migration plan は **「Implementations may use arenas and handles, but *handles are not the
application programming model*」** と明記している。したがって:

- **flat node 集合 / parent ポインタ / handle を「Kotoba ではこう書くもの」として文書化しない。**
  それは実装戦略であって application の書き方ではない、と計画側が名指しで否定している。
- **native 向けに word 型へ閉じて書く場合も同じ** — その制限は「native がまだ持っていない
  から」であって様式ではない。モジュールのヘッダにそう書く。
- 形 A（component を `:string` を返す純関数にし `string-concat` で合成）は、capability 不要で
  native にも載る書き方として引き続き有効。ただし**string-only SSR を最終 API にしない**
  （ADR-2607279200 Delivery #6）。

### ブラウザ / Worker で動かす口は 3 つあり、既定は wasm32-browser（2026-08-30 改訂）

**amu は native compiler であって JVM に依存しない**（オーナー指摘 2026-08-30）。
`.kotoba` をブラウザや Worker で動かすときの既定は **`--target wasm32-browser`** で、
`bin/amu` はこれを **nbb で実行する —— JVM を起こさない**。

⚠ **この節は 2026-08-30 まで逆を書いていた。** 「既定は `--target js` の restricted ESM」
と指名した上で、同じ節の下の方で「コンパイルは js / cljs とも JVM 経路」と自分で書いて
いた —— **JVM を起こす経路を既定に指名していた**。JVM が現れるのは native/wasm 以外の
target に落ちたときだけで、**それは amu の経路ではない**。

実測 2026-08-30、`bin/amu` @ `kotoba-lang/main` `0df9d99` —— **nbb（JVM なし）で走るのは**
`check` / `extract-native` / `verify-output-set` / `sign-output-set` と、
`worker` | `compile` の `--target` ∈ {未指定, `wasm32`, `wasm32-browser`, `wasm32-wasi`,
`x86_64*`, `aarch64*`}。**それ以外は `spawn("clojure", …)` に落ちる。**
（読むのは `orgs/kotoba-lang/amu` の checkout ではなく `kotoba-lang/main` —— 2026-08-30
時点で checkout は pin のまま **139 commit 遅れ**ており、その古い tree を読んで
「amu も部分的に JVM」と誤読した。）

| target | 出力 | host | 実行 | 使いどころ |
|---|---|---|---|---|
| `wasm32-browser` | `.wasm` | `amu/runtime/browser-host.mjs`（`kotoba:typed/cap-call`） | **nbb（JVM なし）** | **既定。** ブラウザ / Worker |
| `js` / `js-browser` | restricted ESM `.mjs` | `amu/runtime/dom-driver.mjs` + `browser-host.mjs` | **nbb（JVM なし）**（2026-09-06、amu ADR 0340） | 既存資産の互換と、`kbb --backend js` の oracle。既定は上の wasm32-browser のまま |
| `cljs-browser-kotoba-v1` | `.cljs` **ソーステキスト** | 無い（自分で require して `main` を呼ぶ） | **clojure（JVM）** | cljs toolchain に載せる必要があるときだけ |

- **JVM を起こさないことは好みではなく容量の問題である。** 実測 2026-08-30、この 1 台
  （10 コア）で **load average 513**、java 14 本 / node 99 本 / Claude セッション 8 本。
  走っていた java を親プロセスで辿ると **`scripts/resource-guard.mjs` の下に居たのは 1 本だけ**で、
  残りは `clojure -M` / `-A:test` / `-Sdeps` / launchd 常駐だった。guard が壊れているのではなく、
  **guard の対象が「build コマンド名の列挙」（shadow-cljs / vite / next / cargo / wash）なので、
  実際に CPU を食っている JVM の test / gate / loop が全部その列挙の外にある**。
  列挙を足すより、**JVM を起こさない経路を既定にする方が効く。**

- **UI は `init` / `view` / `step` の 3 つの純関数 export**（`state + event -> next-state`）。
  参照実装は `amu/examples/todo-app.kotoba`、host は `amu/runtime/dom-driver.mjs`。
  **capability は要らない** —— guest は DOM 名も host object も callback も受け取らず、
  往復するのは `data-k` 由来の文字列だけ。`requiredCapabilities` は空で mount する。
- **cljs backend は「できている」が JS 面の主役ではない。** 出るのは `.cljs` ソースなので
  nbb / shadow-cljs が要り、**ブラウザ用の host runtime が無い**。capability kit ファイルに
  cljs の qualification 行は 1 件も無く（`:jit` は kotoba-script の `:js-kotoba-v1` のこと）、
  `surface-status.edn` の `:backend-parity` も「同じプログラムを `:kotoba-wasm` と
  `:kotoba-cljs` で走らせて突き合わせる harness はまだ無い」と自分で書いている。
  **「cljs があるから browser は済んでいる」と読まない。**
- **先に当たる天井は fuel ではなく値の大きさ。** `:document` は 256 ノードで、
  `todo-app.kotoba` 程度のレイアウトだと数行で `doc-node-limit` に届く（その天井は
  ファイル冒頭のコメントが自分で申告しているので、そこを読む）。fuel 512 は instance
  生涯で使い切りなので dom-driver は **1 インタラクション = 1 新規 instance** にしている
  —— 共有すると描画途中で `fuel-exhausted` になる。**整数→文字列の builtin が無い**
  （todo-app が ID を 26 文字のアルファベットから取っているのはそのため）。
- **`cljs` target を選ぶと `clojure` が起きる。** それが JVM の入口であって、
  amu 自体の性質ではない。1 ファイルで分単位かかるので、どうしても使う場合でも
  loop や hook に組み込む前に測る。**新規はこの target を選ばない。**
  ⚠ 2026-09-06 まで `js` もここに並んでいた。emitter（`kotoba-lang/kotoba-script`）が
  `.cljc` になり、`amu compile --target js --jvm-free` は nbb で走る（amu ADR 0340。
  parity は `test/nbb/js_parity.cljs` —— JVM 経路が書いた `runtime/http/route-decide.mjs`
  とバイト一致）。**JVM に残るのは `cljs-browser` だけ。**
- 実ブラウザでの確認は `amu/tests/browser/`（`app.html` + `browser.spec.mjs`、Playwright で
  trusted event を送る）。Node の mock DOM で足りるなら `createMockDom` が
  `browser-host.mjs` に在る。

### 今日の既知ブロッカー（回避策を知らずに時間を溶かさないこと）

1. ~~project linker が `:capabilities` を拒否~~ / ~~CLI が policy を `{}` 固定で渡す~~ —
   **どちらも 2026-07-27 に解消**（compiler#332 / kotoba#432 + pin 前進 #433）。
   多ファイル project で `:capabilities` を宣言でき、`--policy` に compiler の
   `{:allow #{[:cap/call <id>]}}` を渡せば CLI からそのままコンパイルできる。
   `--policy` 無しは空 policy（deny-by-default は不変）。`:schemas` は project mode では
   引き続き拒否（同名 schema の衝突規則が未決定）。
2. **`guest-grammar.edn` の `:admitted-builtins` を「呼べる操作の一覧」として引かない**
   （2026-09-06 追記）。実測: `document-sha256` はそこに**無い**のに guest source から呼べる。
   `document-*` は 36 個あり（`document-canonical-bytes` / `document-equal?` /
   `document-assoc` / `document-merge` / `document-print` ↔ `document-read` を含む）、
   正本は `:sugar :document` と amu の W4 スライス群のほう。**ある操作が無いことを、
   あの一覧に無いことから結論しない。** 今日それで一度誤診した。
3. **capability kit の qualification をここに書き写さない — kit ファイルが正本。**
   `orgs/kotoba-lang/amu/resources/kotoba/lang/capability-kits/*.edn` の
   `:qualification` を引く。key の意味は `:reference`（KIR インタプリタ）/
   `:wasm-aot`（`wasm32-browser-kotoba-v1` + `kotoba:typed/cap-call`）/
   `:wasm32-kotoba-v1`（clock の i64 `kotoba:cap/call` 面 —— **その target に
   コンパイルできることと、その host 面で動くことは別の主張**なので別 key）/
   `:native-aot` / `:jit`（kotoba-script `:js-kotoba-v1` を V8 で実行）。
   **値は kit ごとに違う**ので「N kit とも同じ」という形の要約を作らない。

   ```bash
   nbb --classpath ".:scripts/nbb_compat" -e '
   (ns x (:require [clojure.edn :as edn] ["fs" :as fs] ["path" :as p]))
   (def dir "orgs/kotoba-lang/amu/resources/kotoba/lang/capability-kits")
   (doseq [f (sort (fs/readdirSync dir))]
     (println (.padEnd (subs f 0 (- (count f) 4)) 20)
              (pr-str (:qualification (edn/read-string (fs/readFileSync (p/join dir f) "utf8"))))))'
   ```

   **grep で代替しない。** `grep -A6 … | cut` で試したところ、行の折り返しのせいで
   ちょうど `:jit` が 5 kit 分だけ末尾で切れ、**切れたことが出力から分からなかった**
   （「測れなかった検査が、測って問題が無かった検査と同じ顔をする」の小型版）。
   key の集合も kit ごとに違う（`stream-object-v1` だけ `:frontend` / `:wit-03` /
   `:restricted-esm` という別語彙）ので、reader で読んで map ごと出す。

   kit ファイルは pending の理由まで書いている（例: ui-v1 の `:native-aot` は
   「未着手」ではなく `[:set [:record …]]` が one-word 値でないという**測定された
   拒否**で、同じ native に dataspace は qualified 済み）。**pending を「誰も試して
   いない」と読まない。**

   ⚠ **この項目自身が 3 週間ずれていた。** 旧文は「全 8 kit が `:wasm-aot` /
   `:native-aot` / `:jit` とも pending」という **2026-07-27 の測定値**を定数として
   持ち、2026-08-18 に引用された時点で実態と食い違っていた（kit ファイル側は
   `Measured 2026-08-18` と日付を書いて更新し続けている）。**「今日の既知ブロッカー」
   という見出しの節に値を書けば、その値は明日も「今日」として読まれる。**
   ここに残してよいのは*引き方*であって*引いた結果*ではない。
4. **ingress capability は在る**（`capability-kits/http-ingress-v1.edn`、host-injects /
   guest-polls の accept-then-reply、queue 深さ 8、body 64 KiB）。**ただし ingress 系の
   qualification は他 kit と揃って進まない** —— Cloudflare Worker のエントリを Kotoba に
   移す前に、item 3 のコマンドで `http-ingress-v1` / `stream-ingress-v1` の行を実際に
   見る（ADR-2606290000 と整合）。2026-08-08 訂正: 旧文は「どちらの面にも無い」と
   書いていた。
5. **`kbb`（Kotoba script host）は在るが、まだ script host の正本ではない** — build
   スクリプトは nbb 据え置き（上の nbb-only 節）。⚠ **2026-09-06 訂正: 旧文は「kbb は無い」
   と書いていたが偽だった。** `kotoba-lang/kotoba` に `bin/kbb`（JVM bootstrap）、
   `--backend native`（ADR-2609051100）、`bin/kbb_js.cljs`（ADR-2609062200）が在り、
   同じ policy で同じ script が native と js で同じ値を返す（demo_kbb_fs_read_native = 84）。
   script が書く相手は `lib/kbb/*.kotoba`（`--source-path lib`）で、wire id を script に
   書かない。`kotoba-lang/kotoba-script` は restricted-ESM emitter であって script runner
   ではない（名前で誤解しないこと）。
   ⚠ **2026-09-06 訂正: 旧文はここに「fs/process/exec capability も無い」と書いていたが偽だった。**
   `amu/resources/kotoba/lang/capability-catalog.edn` の `:capabilities` は `:fs/transact`
   `:fs/browse` `:fs/app-data` `:process/spawn` `:env/read` `:git/run` `:secret/get`
   `:screen/act` `:code/eval` 等を、**wire id を持つ admitted な source 操作**として持っている
   （`:source-status :friendly-qualified`）。無いのは *kit ファイル* の方で、typed
   request/result schema と backend qualification 行がまだ書かれていない。
   **したがって「その capability は在るか」と「その backend で動くか」は別々に引く** ——
   前者は `capability-catalog.edn`、後者は `capability-kits/*.edn` の `:qualification`。
   どちらの件数もここに書かない（動くので）。

## `.kotoba` で「書けない」は 2 種類ある — 恒久と一時を混ぜない（repo-wide mandatory、2026-08-08、ADR-2608650000）

**`.kotoba` で何かが書けないと結論する前に、それが「恒久の安全設計」なのか
「backend がまだ追いついていない」だけなのかを、必ず分類してから書く。** 両者は
どちらも「使えない」として同じ形で現れるので、分類を書かなければ読み手は全部を恒久だと
読み、**backend が追いついた後もその自己制限を守り続ける**。

分類は推測しない。言語側が仕様として持っている:

- **`kotoba-lang/kotoba-lang` の `lang/surface-status.edn` の `:disposition`** —
  `:intentional-security-constraint`（安全不変条件。広げるには ADR と fail-closed 強制）/
  `:intentional-semantic-simplification`（決定性・可搬性のため意図的に狭い）/
  `:implemented-partial`（1 つ以上の backend で使える）/ `:not-yet-implemented`
  （**安全上の禁止ではない**）。
- **`kotoba-lang/amu` の `resources/kotoba/lang/application-language.edn` の
  `:backend-qualification :rule`** — *An unavailable backend is an implementation gap,
  not a reason to remove a specified safe language feature.*

**したがって「native に無い」は、それ自体では言語の設計判断の証拠にならない。**

### 恒久として引き受けるのは 2 つだけ（2026-08-30 精密化: 恒久は性質であって記法ではない）

| 制約 | 出典 |
|---|---|
| **untracked control effect の禁止** — 境界で返すのは `[:result T E]`。**恒久なのは「追跡されない制御効果」の禁止であって、`throw` という語の禁止ではない**（2026-09-06 訂正、下記） | `:invariants :explicit-errors`。改訂は ADR-2608650000 + adr-2608301500 |
| bool は数ではなく型 | `:invariants :bool-is-a-type-not-a-number` = `:intentional-semantic-simplification` |

`ex-info` → Result は後戻りしない設計変更なので、移行の副産物にせず正面からやる。

⚠ **`throw` / `try` は既に admitted である**（2026-09-06 実測）。旧文はこの表で「ambient
`throw` / `try` / `catch` を使わず」「wasm/cljs でも拒否」と書き、typed abort ability を
「前提条件 landed 後に widening 可」と将来形で述べていたが、**widening は部分的に landed
している**。`lang/guest-grammar.edn` の `:sugar` に `:throw` / `:try` が在り、契約は
`lang/abort-ability.edn`。

効くのは拒否の側で、そこは強い —— **abort する関数は export できない**、`throw` は
loop / doseq / dotimes の body・lazy thunk・fn literal の中で拒否、effect row に
`:dataspace/*` を持つ関数でも拒否。**書き方の既定は変わらない: 境界は `[:result T E]`。**

**現在地は `lang/surface-status.edn` の `:invariants :explicit-errors :widening` を引く** ——
slice 番号も、どの precondition が `:met` でどれが残っているかも、そこが持つ。ここに書き写さない。

**記法制限には shielding axis が付いた（adr-2608301500、2026-08-30 オーナー指示）。**
禁止が守る性質を 5 軸（`:code-identity` / `:dispatch-bypass` / `:authority` /
`:control-effect-tracking` / `:resource-bounds`）で名指しし、**definition CID
（Unison 的 identity）と grant 交差 dispatch（biscuit 的 authority）で防げる害には
記法禁止を恒久としない**。`:authority` 軸は `:state` ability への desugar という widening
path を持ち、**その一部は既に landed している**（2026-09-06 訂正。旧文はこの軸をまるごと
「fail-closed に拒否のまま」と書いていた）:

- **`atom` / `swap!` / `reset!` / `deref` は書ける** —— local-state slice 1、オーナー判断
  2026-09-02「build it, fail-closed, in slices」。ただし cell は**それを束縛した関数本体から
  逃げられない**: 引数として渡す・返す・コレクションやレコードに入れる・fn literal に捕捉
  する・loop / doseq / dotimes の中で読む、はいずれも名指しで拒否される。**逃げる cell、
  関数を跨ぐ cell、1 回の呼び出しより長生きする cell は今も host kit + grant。** escape 規則
  の全列挙と拒否メッセージは `lang/local-state.edn`（`:escape-rule` / `:refusals`）。
  これは「1 つの `let` の中の可変」であって、`app-db` や ratom がここに入るわけではない。
- **`volatile!` / `ref` / `dosync` / `binding` / `var` / `set!` は今も forbidden head**
  （`lang/guest-grammar.edn` の `:forbidden-heads`）。`:state` kit の desugar 自体も
  fail-closed のまま。

eval / interop / defmacro は CID と静的検査可能性そのものが要求するので恒久（機構が成熟しても
解禁されない）—— **ただしここで言う eval は生の `eval` であって、typed eval は別物**
（2026-09-06 追記）。`(eval request)` は DefCID を名指しする有界な document を取り、安全性に
`:no-source-text` を持ち、typed interface / effect row / allowed effects / fuel / max-depth で
受理される（`lang/surface-status.edn` の `:other-gaps :typed-eval`、`:disposition :implemented`）。
⚠ **この repo では実行できない**（同項の `:execution-in-this-repo :blocked` —— provider が
未実装）。拡張点を設計するときは「**ソースを渡す plugin は恒久に不可、CID を名指しする拡張は
仕様済み・ただし provider 待ち**」と読む。
正本は `kotoba-lang/kotoba-lang` `lang/surface-status.edn` の `:shielding-axis`。

### それ以外は native 追随を前提とした一時制約として書く

map / set / 永続コレクション・closure / HOF・異種ベクタ・再帰値はすべて
`:implemented-partial` で landed している。

⚠ **旧文はここに「native に無いだけ」と続けていた。2026-09-06 に実測して偽。**
`amu compile --jvm-free --target aarch64-macos` で `atom`/`swap!`/`deref`、
`defrecord`+アクセサ、`defprotocol`+`extend-type`、`fn` を値として渡す高階関数の
4 つとも通り、**kexe loader で実行して正しい値を返した**（12 / 74 / 15 / 7）。
コンパイルが通ることと動くことを分けて確かめている。

この一句は 1 日で実害を出した。**同じ段落の末尾が「これらを『無い』と仮定して
判断核だけを切り出す設計にしない」と警告しているのに、その直前の一句が
「native では無い」と言っていたため、native を target にした slice が
判断核だけになった**（cloud-itonami-isic-6820 の kumiai actor、ADR-2609062400）。
警告文は、その手前の断定に負ける。

**backend ごとの現在地はここで読まない。1 コマンドで測る**（`:implementation`
集合は surface-status 側でも更新が遅れうる —— 実測時、そこは HOF を
`#{:compiler :kotoba-wasm :kotoba-cljs}` とだけ書いていたが native で動いた）:

```bash
A=orgs/kotoba-lang/amu/bin/amu
printf '(ns t (:export [main]))\n(defn main [] :i64 (let [c (atom 0)] (swap! c + 5) (deref c)))\n' > /tmp/t.kotoba
$A compile /tmp/t.kotoba --jvm-free --target aarch64-macos --output /tmp/t.kexe
$A extract-native /tmp/t.kexe --symbol main --output /tmp/m.bin   # :offset を控える
cc -O2 -o /tmp/loader orgs/kotoba-lang/amu/tools/kexe_loader.c
/tmp/loader /tmp/m.bin <offset> 0 aarch64 -                        # 値が返る
```

⚠ **拒否メッセージを「その機能が無い」と読まない。** 実測 2026-09-06、
`defprotocol` の `requires unique bounded (method [this ...]) signatures` と
`fn value requires unique arities with zero to four unique parameters` は
どちらも**書式の指摘**で、シグネチャの型注釈と `fn` リテラルの戻り値注釈を
外したら両方 native まで通った。1 回目の拒否で止めると、実装状態どころか
自分のタイプミスを言語の天井として記録することになる。**`defrecord` / `defprotocol` / `extend-type` / `extend-protocol` も同様に
landed**（`:protocol-and-record-dispatch`。profile は `bounded-closed-world-static-dispatch`、
`:dynamic-fallback false`、未知または未実装の receiver はコンパイル時に拒否）。`defmulti` /
`defmethod` も `:closed-multimethod` として desugar される（hierarchy・preference・実行時拡張は
持たない）。first-class closure（`fn` / `invoke` / `apply` / `fn-ref`）も landed で、設計に
効くのは実装の有無ではなく **arity の上限**の方（`:first-class-closure-values :bounds`）。
**これらを「無い」と仮定して判断核だけを切り出す設計にしない。**
bare `:bool` パラメータは compiler ADR 0219 が自ら
*a real gap … in the INTERPRETER, not in either backend* と書いており解消途中。
**正規表現は `:forbidden-heads` に無い**（`value-codec.edn` の `:rejected-closed :regex` は
「正規表現を値として転送できない」という正準エンコーディングの話で、演算の禁止ではない）。

**一時制約に沿って書いたコードは、その旨と撤去条件をモジュールのヘッダに書く。**
書かなければ、後から読む者はそれを恒久の様式として模倣する。

### 移行の単位は kotoba/app の vertical slice である（ADR-2608261100）

Kotoba は safe application language である（ADR-2607201300）。source は
Clojure-shaped のまま、`kotoba/app` が第一候補。切り方は guest と host
（ambient authority）であって、判断と残りではない。narrow-slice-only は
2607201300 が削除済み。判断核を既定にすると、ADR-2607141900 と同じ誤りになる。

既定の移行は ADR-2607279200 決定 5 の 4 分類である。portable な product
semantics を普通の Kotoba 値（map / 文字列 / record / document / `cond`）として
移し、ソケット・credential・DOM 破壊は host に残す。vertical slice は
capability が conformance を通った一本の製品経路（state → effect → event →
governor → UI → checkpoint）。1 判断表ではない。1 commit を有界にするのは
正しい。有界はスカラーを意味しない。参照は amu の `examples/todo-app.kotoba`
（`init` / `view` / `step`）。kit の現状は
`amu/resources/kotoba/lang/application-language.edn` をその場で読め。

**2026-08-30 の Q9 whole-component 決定は、決定核 fallback を移行単位として
認めない。** backend が component 全体を admit できない場合、その移行は
`:blocked` である。predicate、decision core、caller が前計算した scalar shadow は
compiler research / historical fixture にはできるが、移行進捗、consumer cutover、
旧 source 削除の証拠にはならない。

文字列禁止ではない（ADR-2608261000）。`.cljc` oracle は slice の gate が揃うまで
残し、`.kotoba` を require しない。oracle は照合用の写しであり、意味の正本ではない。
コマンド文字列はゲストの product semantics である。『コマンド文字列は `.cljc`』は
不適切（ある日の SMTP fallback を言語にした読み）。mirror を作らない。正規表現走査は
移す前に宣言データへ直す。依存が `.cljc` のままの面は移行しない。

Q9 の移行単位は namespace / deployable component の全 public surface と transitive
source closure。機構だけを capability provider import に残す。各 target は verified
native `kotoba check` / `kotoba compile` / `kotoba rad build` と、
`amu check --jvm-free` / `amu compile --jvm-free` の両方を通す。acceptance では
`java` / `javac` / `clojure` / `clj` を deny/trace し、未対応 target、lock failure、
JVM-free project linker 未達は fallback せず block する。

oracle parity は nbb/CLJS、native、Wasm、または content-addressed golden vector で
全 public surface を照合する。JVM oracle は historical/non-gating。両 build の
payload CID、definition CID、exports/imports、effects、resource bounds が一致するまで
consumer cutover しない。機械正本は
`orgs/kotoba-lang/kotoba-lang/lang/q9-migration.edn`、Kototama 採用記録は
`orgs/kotoba-lang/kototama/qualification/q9-whole-component-build.edn`。既存の
JVM/Chicory tender と Clojure compiler path は compat/diagnostic であり、Q9 を
green にできない。

### native の現在地の読み方

**`amu/docs/native-aot-baseline.md` を引用しない** — ADR 0063 で更新が止まっており、
*there is still no native provider/capability mechanism at all* と書いていて native を
実際より低く見せる。現在地は次の 3 つから**その場で読む**:

1. **admission gate** `kotoba-lang/kotoba-kir` の `src/kotoba/kir.cljc` の
   `only-native-word-typed-features?` と `native-word-value-type?` — 名前のとおり
   1 ワードで表せる値しか通さない。**通る型の集合はその場で読む** — 後から足された
   型がある（`:document` は string と同じ pair(offset,length) として入った）。
   `typed-cap-call` は固定の型対に加えて `native-provider-contract?` が認めた
   provider 契約も通るので、**「N 組のみ」と要約しない**。
2. **kit の `:qualification` 行** — 読み方は上記「今日の既知ブロッカー」item 2 の
   reader スニペット。**値をここに書き写さない**（kit ごとに key の集合も値も違い、
   grep は行の折り返しで静かに切れる）。
3. **ADR 系列** `orgs/kotoba-lang/amu/docs/adr/` を `ls | tail` で末尾から読む。
   **番号の上限をここに書かない** — 書いた瞬間に天井として引用される。

⚠ **この 3 つは 2026-08-19 時点で 3 つとも実測値がずれていた**（kit 数・native-aot の
可否・admission gate の型集合・ADR 番号）。読む先が `compiler/` になっていたのも一因で、
正しくは `amu/`（改名済み。west に残る `compiler` entry は古い pin の別 checkout）。
**この節に測定値を書き足さないこと** —— 直近 3 回の陳腐化はすべて「日付付きで値を書いた」
ことが原因で、引用する側は日付を落とす。

可搬 stdlib は `kotoba-lang/lang/stdlib/core.kotoba`。`select-keys` `merge` `update`
`group-by` `every?` `some` `concat` `comp2` `partial1` 等は**在る**。無いのは `get-in`
`sort-by` `juxt` `mapv` `keep` `remove` `for` と `str/*` 全般、そして**バイト走査**
（`skip-spaces` / `digit?` / 大小無視比較のような、行指向プロトコルが必ず要るもの）。
**「stdlib に無い」と言う前にこのファイルを引く**（索引を引いてから「無い」と言う規則が、
repo だけでなく言語の stdlib にも当たる）。

⚠ **`compile --prelude` で取り込めると書いてあったのは誤り**（2026-08-30 に訂正）。
`--prelude` を読むのは **CLJS backend だけ**で、`kotoba.compiler.nbb.*` の entry point は
どれもパースしない。実測（amu `2cb7d3f`、JDK 無し）: stdlib 専用の名前（`comp2` /
`stdlib-binary-closure-anchor`）を単一ファイルで呼ぶと `:subset-reject`、**`--prelude` を
付けても一字一句同じ拒否**。`grep -c prelude` は wasm_cli / x86_64_cli / aarch64_cli とも 0。
つまり**フラグは黙って無視され、他の経路では緑を返していた**。amu#709 で exit 64 に
fail-closed 化した。

**したがって単一ファイルの guest は stdlib を引けない。** 共有する経路は project route
（`--source-path` / `--module-lock`）である。

⚠ **この節は 2026-08-30 に「project route は CLI では JVM 経由になる」と書いていた。
2026-09-01 に project mode は全部 Node へ移り、`bin/amu` の `jvmOnlyProjectMode` は
関数ごと消えた。** `--source-path` は 2026-08-31（amu#717、`kotoba.compiler.nbb.project-files`）、
`--module-lock` は 2026-09-01（amu#728、`kotoba.compiler.nbb.module-lock`、ADR 0289）。
どちらも同じ portable な `project/link-source` に渡す:

```bash
amu compile main.cljk --source-path <dir> --target wasm32 --jvm-free            # exit 0
amu module-lock main.cljk --source-path <dir> --blocks <dir> --jvm-free         # exit 0
amu compile --module-lock lock.edn --blocks <dir> --target wasm32 --jvm-free    # exit 0
```

実測（`clojure` と `java` を PATH から外し `JAVA_HOME=/nonexistent`）: 2 module の
project が通り、生成 wasm が `run(5) = 11` を返す（= もう一方の module のコードが
走っている）。

- **再現可能な build も、もう JVM を通らない。** lock を**作る**側（`amu module-lock`）も
  同じ日に移した —— 消費だけ移すと JDK が全 pinned build の 1 段上流に移るだけで、
  Q9 の反論は答えたことにならない。lock の全 refusal（未 pin の依存 / CID に hash
  しない block / block store の不在 …）は message ごと保存されており、path fallback は
  無い。実測: JVM 経路と突き合わせて **lock.edn・block CID・`.wasm` はバイト一致**。
  **provenance だけは 1 フィールド（`:build-metadata-sha256`）違う** —— これは
  `--source-path` でも同じに出る path-resolver 移植由来の既存差で、route を跨いで
  provenance を照合する consumer は 2 つを同一視できない。
- したがって「JVM-free を保つには単一ファイルにするしかない」はもう成り立たない。
  実測 2026-08-30 に見つかった重複 —— `org-ietf-smtp` / `org-ietf-pop3` /
  `org-ietf-imap` の 3 repo が同じバイト走査（空白送り・数字判定・大小無視比較）を
  **別々の名前で 3 回**書いている —— は、いま共有できる。
- **共有先の実例**: `kotoba-lang/kotoba-lang` の `lang/compat/clojure/string.kotoba`。
  `.cljc` の `(:require [clojure.string :as str])` がそのまま解決する
  （`--source-path <kotoba-lang>/lang/compat`）。**ただし置いてあるのは
  `clojure.string` と厳密同値な 3 つ（`starts-with?` `ends-with?` `includes?`）だけ**
  で、`index-of` `blank?` `trim` `lower-case` 等が**無い理由は 1 件ずつ
  `lang/compat.edn` に書いてある** —— Kotoba の文字列面は UTF-8 バイトで addressing
  されるので、それらは近似にしかならない。**近似を本名で置かない。**

## design system（css / html / shitsuke / liquid-glass-ui / kotoba-ui）は `.kotoba` 移行対象（オーナー判断 2026-07-27、ADR-2607270100 §10）

**この 5 リポジトリを「`.cljc` のまま維持する層」と扱わない。** `.kotoba` へ移行する方針が
決まっている。これらは本質的に「データ → 文字列」の純関数群（token map → CSS 変数、
hiccup → HTML、opts → component）で `->page` は文字列を返すため、**capability は一切不要で
`kotoba/pure` に収まる**。

**ただし string-only SSR を最終 API にしない（ADR-2607279200 Delivery #6 /
migration plan L201）**: *"Do not make string-only SSR the final abstraction. Start cutover
when the shared logical value and both required renderers for that tranche are qualified."*
つまり本格的な切り替えは **W4（recursive logical values）と両 renderer の qualification 後**に
始める。それ以前に書くものは oracle 付きの先行実験として扱い、最終 API として固定しない。

**進捗**: `css` は形 A で移植済み（2026-07-27、kotoba-lang/css#2）——ただし上記のとおり
**W4 に先行した oracle 付き実験**であって「5 段階の 1 段目完了」ではない。`kotoba/css_core.kotoba` +
byte 一致 parity gate（KIR インタプリタを同一 JVM で回す / compiler は test-only 依存）。
`css.core` 自体は無変更で、facade の裏に置く方針を踏襲。後続で効く実測知見:
**数値→文字列の組み込みが無い**（桁を literal から `string-substring` で引く）・**正規表現が無い**
（`string-contains?` で代替）・**`or` は bool でなく i64 を返す**（`if` の入れ子で畳む）・
例外の代わりに `[:result T E]` を返す。なお原典 `css.core/declarations` は**宣言 8 件超で
順序が未規定**（Clojure map が hash-map に切り替わるため。実測済み）で、移植版は
`typed-map-entry-at` のキー昇順で決定的。

### `document-bool` に i64 を渡すと、`amu check` は通り**実行時に**落ちる（2026-08-31 実測）

上の「`or` は bool でなく i64」は css 移植の知見だが、**`document-bool` 経由で表面化すると
症状が変わる**。実測（`org-ietf-ers` の chain slice、amu 88ae83e）:

- `and` / `or` に型注釈が無いと i64 になる。**keyword 同士の `=` も同じ**。
- それを `(document-bool …)` に渡しても `amu check` は **`:ok true` を返す**。
- 落ちるのは **export を実行した瞬間**で、`value is not a boolean`（`:phase :value`、
  `kotoba.kir.value/bounded-typed-value!`）。

**型の誤りが check を素通りして、値の構築時に初めて出る。** `cond` や `if` の*テスト位置*
では強制されるので、そこだけ見ていると気付かない。**document 構築に到達する bool は
全部 `if` に畳む**（`(if (= t :no) true false)` まで含めて）。

同日のもう 1 つの実測: **`new` は local 名にできない**。`:forbidden-heads`（interop）に
在るので `(let [new …] …)` は shadowing 警告ではなく `invalid local binding` になる。

**移行順序は依存順に厳守する**: `css` → `html` → `shitsuke` → `liquid-glass-ui` → `kotoba-ui`。
逆順・同時並行は依存を壊す。移行が完了するまでは skill `kotoba-uiux` の既存ルール
（app は `kotoba-ui.core` のみ require、raw hex 禁止、layout は shell から）がそのまま有効で、
**移行途中のリポジトリを app から直接 require しない**。

## kotoba の実行は最終的に JVM/Node/Rust を経由しない（ADR-2607198300、2026-07-19）

**kotoba-lang における「実行時に JVM/Node/Rust を迂回しない」とは、kotoba 自身の
コンパイラ（cljc）が AOT コンパイルを、独立して直接実行可能なネイティブ artifact
まで最後まで面倒を見ることを意味する。** 配布される実行成果物が JVM/Chicory ホスト・
JS エンジン（Node/browser）ホスト・新規 Rust 実行エンジンのいずれにも依存しては
ならない。さらに **Q9 source migration は build/acceptance も JVM-free** である。
verified native Kotoba CLI と Amu `--jvm-free` を使い、compiler、test、oracle の
どこにも JVM を必須化しない。ADR-2607198300 の「compiler tool の JVM は問わない」は
一般的な historical build の記述としてのみ残り、Q9 には適用しない。

- **kototama 自身の maturity ladder（`orgs/kotoba-lang/kototama/docs/maturity.md`）
  には JVM/JS 以外の層が無いことを直接確認済み**: R0 contract → **R1 JVM/Chicory
  (stable)** → **R2 browser-native (advanced-partial)** → R3 fleet-on-R1。
  R2 は「browser-*native*」であって machine-native ではない——JVM でも Node でも
  ないことを理由に R2（`wasm-webcomponent`/`kgraph.js`）で妥協しない。
- **Rust は書かない（新規の実行エンジンとして）。** `90-docs/adr/2607072000` が
  kotoba-lang 全体に「Rust が必要な実装は全て cljc」を明文化済み
  （kotoba-lang/kotoba 自身の旧 ~38万行 Rust crate 群を撤去した実績が根拠）。
  wasmtime 埋め込みホスト等を新規 Rust で書くのはこの accepted ADR に反する。
- **許容される非 cljc コードは「判断を含まない機構 (mechanism) 層」だけ**
  （ADR-2607241100 D6 / aiueos ADR-0015 で従来の「crt0 相当シムのみ」表現を
  実態に合わせて再定義、2026-07-24）。実測: `kotoba-lang/aiueos` の
  `os/aiueos/kernel` は C/asm 約5,000行超（pci.c 1178 / main.c 690 /
  scheduler.c 570 等）を持つが、**C はレジスタ/MMIO/GDT/IDT/ページング等の
  機構のみを所有し、判断（SHA-256/RSA-2048 検証・ELF/catalog/journal
  admission・capability 発行/委譲/世代付き失効・dispatch 計画）はすべて
  compiler-emit の `.kotoba` オブジェクト**。レビュー可能な性質は
  「decision-free C mechanism」であり、新しい admission/validation 経路は
  必ず Kotoba object として書く（C に判断ロジックを足さない）。汎用ランタイム
  や Rust 代替としての C 導入は引き続きこの例外に含まれない。
- **`kotoba-lang/kotoba-native` に、まさにこれを実現するネイティブ AOT バックエンドが
  既に実在する**: `src/kotoba/native/x86_64.cljc` / `src/kotoba/native/aarch64.cljc`
  ——生の機械語オペコードを直接 cljc で手書き emit（SysV/AAPCS64 ABI、fuel計測、
  末尾自己再帰最適化、`pair`ヒープアリーナ）。実ネイティブプロセス実行の証明
  （`result 42`・trap/signal検知・ヒープアリーナ動作）は amu 側の
  `test/kotoba/compiler/native_executor_test.clj`、ホスト側の非 cljc コードは
  amu の `tools/kexe_loader.c`（+ `_windows.c`、SHA256ピン留め・レビュー済み）。
  **新しいネイティブ実行経路を探す前に、まずこのバックエンドを確認する
  （ゼロから設計しない）。**
- ~~現状のギャップ: この native backend は `kgraph-assert!`/`kgraph-query` を
  まだサポートしない~~ **→ 解消済み（2026-07-24 実測、adr-ledger seq 41 で
  ADR-2607198300 に amend 済み）**: x86_64/aarch64 backend は
  `kgraph-assert!`/`kgraph-get`/`kgraph-count`/`kgraph-entity-at` を実装済みで、
  `native_executor_test.clj` の kgraph-native-customer-pilot が実 kexe loader
  実行で証明している。この capability gap を理由に native 経路を避けない。
  詳細・調査経緯は ADR-2607198300 / ADR-2607198200 / ADR-2607241100 を参照。
