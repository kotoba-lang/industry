# ADR-2607051510: kami-engine エコシステムの cljc/cljs 中心の重複整理

**Status**: accepted — webgpu の重複解消は完了、kami-engine/kami-engine-sdk 側は
調査・記録のみ（実行は owner 確認のうえ別セッション）
**Date**: 2026-07-05
**Deciders**: Jun Kawasaki
**Scope**: `orgs/kotoba-lang/{webgpu,kami-engine,kami-engine-sdk-clj,kami-genesis,expr}`、
`orgs/com-junkawasaki/kami-engine-sdk`

## Context

ADR-2607051400（kami-engine の WebGPU/SDK 系統合）の Phase 0/4 調査で、
kami-engine エコシステム全体に「移行(migration)で切り出したのに、切り出し元の
削除が実行されないまま残っている」というパターンが複数箇所で見つかった。
これは個別の事故ではなく、ADR-2607010930（clj-wgsl migration、~116 crate を
Rust から zero-dep `.cljc` へ復元した大規模wave）に共通する未完了ステップだと
判明した。オーナーの指示「全体として重複がないように cljc、cljs を中心に
整理して欲しい」を受け、3系統の並行調査を実施し、うち1系統（webgpu）は
実際に解消まで実行した。

### 調査1: `orgs/kotoba-lang/webgpu` の内部重複（解消済み）

`90-docs/migration/kami-webgpu-dsl-runtime-split.edn`（ローカル checkout の
sparse-checkout 対象外になっていたため復元して使用）と、付属の検証スクリプト
`scripts/kami-webgpu-dsl-runtime-split-audit.cljs` を実行した結果、**219 repo
中 `webgpu` だけが cleanup 未実行のまま `:status :done` になっていた**ことが
判明（他218 repoは全てaudit green）。台帳の `:removed-from-webgpu` に列挙された
`src/kami/`（丸ごと）・5本のscript・2つのfixtureが、実際には一切削除されずに
残っていた。

detail は Decision 節を参照。

### 調査2: `orgs/kotoba-lang/kami-engine` 本体（記録のみ、未実行）

kami-engineは「Rust workspace撤去済み、CLJ/EDN/WIT asset+contract repository」
と自称するが、実態は以下の**同型パターンが複数確認された**（診査したのは
サンプル3件、全数は未確認）:

- `kami-engine-sdk-clj/` サブツリー（38 files）— 標準 repo
  `orgs/kotoba-lang/kami-engine-sdk-clj`（35 files、2026-07-02に
  "migrate out of kami-engine monorepo" commitで正式分離）と重複。kami-engine
  側は分離後の2026-07-03に別件コミットで巻き込まれて触られており、
  削除前に両者のdiffを取って独自内容が無いか要確認。
- `kami-genesis/` サブツリー（4 files、stub）— 標準 repo
  `orgs/kotoba-lang/kami-genesis`（49 files）と重複。標準側が明確に本物、
  kami-engine側は安全に削除可能と見られる。
- `kami-webgpu-rs/` サブツリー（7 files）— 今回のwebgpu整理で既に解決した
  webgpu-rs統合と同じ内容の三重目のコピー。安全に削除可能と見られる。
- `kami-ui-sdk/`（ADR-2607051200で`kami-engine-app-sdk`へrename済みのはず）が
  kami-engine内でまだ**活発に更新されている**（直近commitがSlider/
  ColorSwatch/Carousel追加）。rename ADRが伝播していないのか、正当な新規
  作業なのか要owner判断。
- `kami-text/`（同様に`glyph`へrename済みのはず）も同様の要確認。
- `kami-render`/`kami-usd-native`/`kami-rtx-native`/`kami-web`/`kami-web-modelb`
  は同パターンの可能性が高いが未診査。
- 未追跡ディレクトリ `kami-app-sip-clj/` が1件存在（過去にこの名前の
  サブツリーを削除した履歴があるにも関わらず）。他セッションのWIPの可能性が
  あるため**触っていない**。

**推奨**: kami-engineの`kami-*`サブディレクトリ全数を対象に、対応する
kotoba-lang直下の標準repoとの重複有無を1件ずつ確認したうえで、確認済みの
重複を削除する専用フォローアップに切り出す。`kami-ui-sdk`/`kami-text`は
rename整合の確認をowner判断で先に行う。

### 調査3: `orgs/com-junkawasaki/kami-engine-sdk`（Svelte）の非genkoサブフォルダ
（記録のみ、未実行）

- **`data/*.ts`**（12ファイル、Svelte依存なしの純データ）: 概ね重複なし、
  cljc化の最も低リスクな候補。**ただし `joint-limits.ts` と `motion-key-map.ts`
  は実質的な重複が確認された** — `joint-limits.ts`の数値は
  `orgs/kotoba-lang/skeleton/src/skeleton.cljc`（Rust `kami-skeleton::
  defaultHumanoidConstraints()`のcljc移植）と完全一致。`kami-engine/
  ARCHITECTURE.md`が既に「`kami-engine-sdk`のTypeScript fallback motionは
  `kami-web::evaluate_motion`と乖離しうる（未監査）」と明記した既知リスク。
  恒久対応は `kami-web`（現状Rust）の `evaluate_motion`/`clamp_bone` が
  cljcへ移植されてから、TS側がそれを消費するよう配線し直すこと。
- **`builders/*.svelte.ts`**（8ファイル、Svelte 5 runes使用）: 純ロジックでは
  なくreactive controller。機械的移植は不可、cljc/cljsでの reactive-state
  方式（Reagent atom等）を先に決める必要がある。既存cljc repoとの重複なし。
- **`call/`/`gsplat/`/`types/`/`document/`/`manufacturing/`/`trackpad/`**:
  重複なし、対応不要。

### 調査4: `orgs/kotoba-lang/kotoba` の Rust 撤去状況（クリーン、対応不要）

オーナーの追加指示「`kotoba-lang/kotoba`、`kami-engine`等でRustからの切り出しが
ちゃんとできていない/cljc移行ができていない箇所」を受けて調査した結果、
**`kotoba-lang/kotoba` はこのパターンに該当しない**ことを確認した:

- `crates/`（36 crateのRust workspace）は **PR #259「Remove legacy Rust
  workspace」（2026-07-01）で完全かつクリーンに削除済み**。PR説明を確認したところ、
  `clojure -M:test`・CLI contract検証・`npm pack --dry-run`・パックした
  tarballのグローバルインストールsmoke testまで検証したうえでの、意図的な
  アーキテクチャ決定だった（「デフォルトのCLI/packageパスは既にCLJC/EDN
  backed」という判断）。
- `90-docs/migration/clj-wgsl-ledger.edn`の`:kotoba`節（36 crate、大半が
  `:stay-Rust-substrate`=「意図的に恒久Rustのまま」指定）は、**この削除以降
  完全に陳腐化している**（記載されている crate は1つも実在しない）。これは
  webgpuのような「cleanup未実行」問題ではなく、**移行がledgerの想定より
  先に進んでしまった**ケース。ledger自体をarchive/superseded扱いにする
  ドキュメント更新が今後必要（本ADRでは未実施）。
- `kami-kotoba-repo-split.edn`（同ディレクトリ内の別ledger）も自ら
  「Compatibility stub... 実際の移行はこの形を超えて進んでいる」と明記済み。

### 調査5: `orgs/kotoba-lang/kami-engine` の追加4crate + Phase1候補群（概ねクリーン）

同ledgerの`:kami-engine`節から、調査2で未確認だった項目を追加調査した:

- **`kami-eng-core`/`kami-eng-render`/`kami-eng-io`/`kami-dft`**（いずれも
  `:class :migrate-out`、"Restored to kotoba-lang/X"と記載）: 4つとも
  **クリーン**と確認 — 標準repo（`engineer`/`engineer-render`/
  `engineer-io`/`dft`）は実装済みでテストgreen（9/19、4/8、6/19、
  17/40 assertions）、`kami-engine`側に同名のleftoverサブツリーは
  **存在しない**（webgpu-rs/kami-genesis/kami-engine-sdk-cljとは違い、
  抽出元の削除も正しく実行されていた）。ledger自身の`:phase-4-supplemental-
  2026-07-01`節（本ADR起票時に読み落としていた既存の監査記録）が既にこれを
  裏付けていた。
- **`:port-to-CLJC-domain-interpreter`のPhase1候補**（kami-game/kami-cam/
  kami-input/kami-skeleton/kami-tilemap）: 標準repoはいずれも実在し実装内容も
  ledgerの説明と一致（kami-gameは60ファイルと特に大きく「25 game-systems
  modules」の記述と整合）。**ただし、ledgerが検証根拠として挙げている
  `keystone_domains.rs`（"already green"の gate）は、kami-engineのRust
  workspace撤去（PR #82）で完全に削除済み — 今日この時点で再検証する手段が
  無い**。つまりledgerの「green」という記述は「削除前のスナップショットで
  一度greenだった」以上の意味を持たない。コード重複ではなくドキュメント/
  検証手段の陳腐化として記録する。
- `kami-skeleton`の実体は`orgs/kotoba-lang/skeleton`と確認済み — 調査3で
  見つけた`kami-engine-sdk`の`joint-limits.ts`重複の対象と同一repo。

## Decision

### webgpuの重複解消（本ADRで実行済み）

台帳の`:removed-from-webgpu`が指す38個の無関係フォーマットDSL名前空間
（materialx, dxf, verilog, scad, graphql, dance, atom, capnp, css, cue, dot,
exr, geojson, gltf, graphml, html, ical, json, kicad, mathml, mermaid,
musicxml, ocio, otio, proto, re, scene2d, spice, spirv, sql, srt, step, text,
toml, usd, xml, yaml, expr）を、実際の依存関係を検証したうえで削除する。

**検証手順**（台帳を鵜呑みにせず実施）:
1. 残すべきレンダリング系ファイル（`webgpu.cljs`/`webgpu/ir.cljc`/
   `shaders.cljc`/`wgsl.cljc`/`webgl.cljs`等）から、削除予定の38名前空間への
   requireが無いか全数grep — 唯一の実依存が `kami.wgsl` → `kami.expr` だった。
2. `kami.expr`は標準repo`expr`（`kotoba.expr`facade）と実装が
   namespace名以外ほぼ同一と確認 → 削除せず`:local/root`依存へ置換。
3. 削除対象専用のtest 34本、およびそれらだけを実バイナリ検証していた
   `scripts/format_gate.clj`（25 requires中23個が孤児DSL）+ 専用validator
   （`gltf_validate.js`/`graphql_validate.js`）も削除。
4. 台帳が「削除対象」と指定していた`scripts/gen_glsl.clj`/`fixtures/glsl`等は
   **実際には`kami.wgsl`/`kami.shaders`（このrepo自身のドメイン）と、生きている
   playwright render/webgl testに使われている**と判明 → 台帳の指定を無視し
   維持（台帳の日付2026-07-01は、この後 2026-07-02 に統合されたwebgpu-rs や、
   その後追加されたWebGL2フォールバック描画系テストを知らない）。

**検証方法**: `cljs.build.api`の`:optimizations :none`コンパイル
（src + ../org-w3-webgpu/src + ../expr/src、kept namespaceに関するwarningゼロ）
+ `nbb verify`（全JVM `.cljc`テストgreen）。

### kami-engine / kami-engine-sdk（本ADRでは調査・記録のみ）

上記調査2・3の内容を記録として残す。実行（kami-engineのサブツリー削除、
joint-limits.tsのcljc配線）はスコープが大きいため、owner確認のうえ別
フォローアップで着手する。

## Consequences

- (+) `webgpu`は80ファイル・4,152行の重複を削除し、台帳が本来意図していた
  「1 repo = 1 責務」に実質的に復帰した。
- (+) 「移行台帳を鵜呑みにせず、実依存グラフとテストで検証してから削除する」
  という手順が、今後同種のcleanupにも適用できる再現可能な型になった。
- (+) `kotoba-lang/kotoba`のRust撤去（PR #259）と`kami-engine`の4crate
  追加移行（kami-eng-core/render/io、kami-dft）は、いずれも**クリーンに
  実行済み**と確認できた — このエコシステムの問題は「全部が中途半端」では
  なく「一部（webgpu、kami-genesis等）だけが未完了」という限定的なもの
  だと判明した。
- (−) kami-engine本体のサブツリー重複（少なくとも3件確認、全数未確認）は
  未解消のまま残っている。
- (−) `kami-engine-sdk`のjoint-limits.ts/motion-key-map.ts重複は、根本解決に
  `kami-web`（Rust）のcljc移植という前提条件があり、本ADRの範囲では解消
  できない。
- (−) `kami-ui-sdk`/`kami-text`のrename整合状況はowner判断待ち。

## Alternatives Considered

- **移行台帳の`:removed-from-webgpu`指定を全部そのまま実行する**: 却下。
  `gen_glsl.clj`/`fixtures/glsl`等は実際には生きた依存があり、台帳の日付が
  その後の変更（webgpu-rs統合、playwright test追加）より古いため盲目的に
  従うと現行テストを壊す。実依存グラフでの個別検証を優先した。
- **kami-engineのサブツリー重複も本ADRで即実行する**: 却下（owner判断）。
  スコープが大きく（少なくとも5件、全数未確認）、`kami-ui-sdk`のように
  「本当に重複か、正当な新規作業か」の判断が必要なケースがあるため、
  即断で削除するのはリスクが高い。

## Related

- ADR-2607051400: kami-engine の WebGPU/SDK 系統合（本ADRの発端）
- ADR-2607010930: clj-wgsl migration（本ADRが検出した「未完了cleanup」の
  wave自体を作った移行）
- `90-docs/migration/kami-webgpu-dsl-runtime-split.edn`:
  webgpu整理の一次資料（sparse-checkout対象に追加済み）
