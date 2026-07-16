---
id: adr-2607141600-kotoba-kotoba-lang-compiler-kototama-aiueos-consolidation-experiment
title: "ADR-2607141600: kotoba / kotoba-lang / compiler / kototama / aiueos の5リポジトリ責務境界を確定し、言語プロファイルと Chicory 実行系の重複を解消する実験的統合を行う"
status: accepted — closed（Phase 0/1 done, Phase 2 実験実施の上 negative-close, Phase 3 対象外）
date: 2026-07-14
deciders:
  - Jun Kawasaki（「今の kotoba-lang/kotoba, kototama, compiler の成熟度は? 今の cljc を
    .kotoba に置き換えていけるかな? また .kotoba は cljs に互換性はある?...
    kotoba-lang/kotobascript などを作った方がいいかな? 分析してみて」→（分析結果を受けて）
    「ok, では update adr. kotoba, kotoba-lang, compiler, kototama, aiueos の実験統合 adr
    をまとめて」）
related:
  - 90-docs/adr/2607022800-kotoba-lang-org-dependency-map.md（org全体の依存関係マップ、
    この ADR が定める5リポジトリ境界の上位コンテキスト）
  - 90-docs/adr/2607022400-kototama-unikernel-tender-runtime-vocabulary.md
  - 90-docs/adr/2607022700-aiueos-native-adapter-tender-design.md
  - 90-docs/adr/2607022900-aiueos-chicory-jvm-native-adapter.md
  - 90-docs/adr/2607062330-kototama-tender-chicory-execution-runtime.md
  - 90-docs/adr/2607062400-kototama-actor-host-browser-native-and-tender-module-fix.md
  - 90-docs/adr/2607100030-kotoba-kami-engine-host-imports.md
  - 90-docs/adr/2607022200-aiueos-cljc-wasm-component-kernel-architecture.md
  - orgs/kotoba-lang/kotoba-lang（`lang/profile.edn` / `docs/lang/versioning.md` —
    `.kotoba` 言語プロファイルの正典）
  - orgs/kotoba-lang/kotoba（`kotoba wasm emit` 実装ランチャー、CLJ ホストアダプタ）
  - orgs/kotoba-lang/compiler（multi-target: wasm32-browser/wasi + native x86/arm）
  - orgs/kotoba-lang/kototama（actor:host ABI ホスティング runtime、fleet tender）
  - orgs/kotoba-lang/aiueos（capability OS、policy 決定 + quota/fuel 施行 + 独自 execute）
supersedes: []
superseded_by: []
last_verified: 2026-07-14（Phase 1 実施日）
doc_type: adr
topic: kotoba-lang-wasm-runtime
authoritative: true
authoritative_for:
  - "kotoba-lang / kotoba / compiler / kototama / aiueos の5リポジトリの責務境界表（本文
    §Decision の表）を、この関係性についての正本とする位置づけ"
  - "`.kotoba` 言語仕様（reader-forms / reserved-forms）の唯一の正典は
    `kotoba-lang/kotoba-lang` の `lang/profile.edn` であり、`kotoba-lang/compiler` が
    独自に保持している reserved-forms 集合（`src/kotoba/compiler/frontend.clj`）はそれと
    未整合の重複実装であるという診断結果の記録"
  - "`kotoba.wasm-exec`（kotoba/）・`kototama.tender`（kototama/）・`aiueos.execute`
    （aiueos/）の3箇所が、いずれも ADR-2607022900 を根拠に独立して Chicory の
    Instance-building / host-import closure 配線を実装しているという重複の記録、および
    それを解消する実験（Phase 2/3、未着手）の位置づけ"
  - "この ADR は5リポジトリの git 統合（レポジトリ merge）を提案しない、という否定の記録
    （west/manifest 上は引き続き独立5エントリ）"
---

# ADR-2607141600: kotoba / kotoba-lang / compiler / kototama / aiueos の5リポジトリ責務境界を確定し、言語プロファイルと Chicory 実行系の重複を解消する実験的統合を行う

**Status**: accepted — closed。Phase 0/1 完了、Phase 2 は実際に3ファイルを精読比較する
形で実施し negative-close（末尾 Addendum 参照）、Phase 3 は Phase 2 の gate 条件
（「net-positive なら migrate」）が成立しなかったため対象外。
**Date**: 2026-07-14
**Deciders**: Jun Kawasaki

## Context

前セッションのターンで「kotoba-lang/kotoba, kototama, compiler の成熟度」「`.cljc` を
`.kotoba` に置き換えられるか」「`.kotoba` は cljs 互換か」「`kotoba-lang/kotobascript`
を作るべきか」という分析依頼を受け、Explore agent による実地調査（`git log` / README /
テストスイート / ADR 横断 grep）で以下が判明した:

1. **`kotoba-lang/kotoba-lang`** が `.kotoba` 言語プロファイルの唯一の意味論的権威
   （`lang/profile.edn`、`docs/lang/versioning.md`、M0〜M6 maturity gate、conformance
   fixtures）。「Kotoba is not 'any JVM Clojure or ClojureScript program runs'」と明記され、
   `defmacro`/`eval`/`require`/`import`/Java-JS interop/サードパーティ依存を一切禁止した
   極小サブセット。
2. **`kotoba-lang/kotoba`** はその実装ランチャー（`kotoba wasm emit` 等の CLI、192 tests /
   945 assertions green、`.kotoba` サンプル67本）。
3. **`kotoba-lang/kototama`** は `actor:host` ABI ホスティング runtime。JVM/Chicory tender
   （R1 stable）とブラウザネイティブ host（R2 advanced-partial）、fleet 多重テナント
   （R3 stable）の2〜3経路を持つ。
4. **`kotoba-lang/compiler`**（今回発見: これは**別の第三実装**）は multi-target
   （`wasm32-browser`/`wasm32-wasi` + x86_64/aarch64 native、linux/mac/win/android/ios）の
   frontend→HIR→KIR→backend パイプラインを持ち、commit数130で3リポ中もっとも活発。
   **独自のリーダー・独自の reserved-forms 集合**（`src/kotoba/compiler/frontend.clj`）を
   持ち、`kotoba-lang/kotoba-lang` の profile とは**明示的な相互参照が一切ない**
   （grep 0件）。両者の reserved-forms 集合は字面上も一致しない（`compiler/` は
   `when`/`do`/`and`/`or` を明示的な reserved 集合に含めていない）。
5. **`kotobascript` という名前・提案・ADR はどこにも存在しない**。唯一のヒットは廃止済み
   Rust 時代のアーカイブ（`kotoba-v2025/_archive`）内の無関係な `.kotobas` 拡張子・
   `kotoba-kotobas` crate で、現行設計とは無関係。
6. `.kotoba` は WASM のみをコンパイルターゲットとし、cljs/JS へのコンパイルパスはどこにも
   存在しない。Profile v3 が `.cljs` を**受理する入力拡張子**として復活させた事実はあるが
   （`docs/lang/versioning.md`）、これは「フルの ClojureScript が動く」という意味ではなく、
   `.clj` と同型の単一ターゲット互換拡張子（`["cljs" "default"]` reader-branch のみ）で
   あり、中身は同じ極小サブセットに制約される。
7. Node.js での cljs 実行は既に `kototama/clj/src/kototama/node.cljs` で実証済み
   （通常の `.cljc`→cljs→Node コンパイル、`.kotoba`→WASM パイプラインとは完全に独立）。

このターンでの追加調査（本 ADR 起票のための followup）で、**5番目の関連リポジトリ
`kotoba-lang/aiueos`** の存在と、上記診断を補強する具体的な重複を発見した:

- `aiueos` は「AI-agent-native capability OS」— manifest/policy/grant/audit/run-plan の
  CLJC 意味論的権威 + `aiueos/component` Wasm Component Model 境界。
  `aiueos.execute`（ADR-2607022900）が **Chicory（`com.dylibso.chicory`、pure-JVM Wasm
  runtime）で実際に `.kotoba` コンパイル済み Wasm component を実行する**——
  `kototama.tender` と同じ根拠 ADR（ADR-2607022900）を引用する、**独立した第2の
  Chicory 実行実装**。
- `kototama.aiueos_adapter`（`kototama/src/kototama/aiueos_adapter.clj`）が既に
  「aiueos decides, kototama enforces」という境界を明示的に文書化・実装済み
  （ADR-2607022700・ADR-2607062330 addendum 6）：`kototama.tender` は決定アルゴリズムを
  持たず、`aiueos.cli/command-result` の判断を**翻訳するだけ**——「not a code-level merge
  of the two execution namespaces」と docstring 自身が明言している。**この決定層の分離は
  既に正しく設計されており、本 ADR はこれを変更しない。**
- 一方で、**低レベルの Chicory 実行プラミング**（`Instance.Builder` 配線、host-import
  closure 群、capability チェックのラップ、`withMemoryLimits`/
  `withUnsafeExecutionListener` によるメモリ上限・fuel 計測）は
  `kotoba.wasm-exec`（`kotoba/`）・`kototama.tender`（`kototama/`）・`aiueos.execute`
  （`aiueos/`）の**3箇所で独立に手書きされている**。各 namespace の docstring は互いの
  存在を認識し「決定は一本化されている」ことは説明しているが、**この配線コード自体を
  共有する仕組みは存在しない**。`aiueos.execute` 自身の docstring が「Chicory has no
  first-class gas-metering API, and this hook is explicitly documented
  unsafe/experimental」と認めている通り、Chicory の非公式APIに3箇所が個別に依存している
  状態でもある。

まとめると: `.cljc`（5,008ファイル、org全体）を `.kotoba`（961ファイル）へ「置き換えていく」
という当初の問いは、`.kotoba` の言語仕様上そもそも成立しない（サンドボックス化が必要な
狭いスライス専用の言語であり、一般アプリロジックの受け皿ではない）。しかし調査の副産物として、
**「`.kotoba`/Wasm 実行系」という狭いスコープの中でこそ、実際の重複が5リポジトリにまたがって
存在する**ことが判明した——これが本 ADR の主題。

## Decision

**5リポジトリの責務境界を以下の表で確定する**（重複ではなく分業として認め、各リポジトリの
README がこの境界を相互参照するようにする）:

| リポジトリ | 役割 | 正本範囲 |
|---|---|---|
| `kotoba-lang/kotoba-lang` | 言語プロファイルの意味論的権威 | `.kotoba` の reader-forms / reserved-forms / source-extension 契約（`.kotoba`/`.cljc`/`.clj`/`.cljs`）、conformance fixtures |
| `kotoba-lang/kotoba` | リファレンス実装ランチャー | `kotoba wasm emit` CLI、JVM/Chicory 経由の一次実行アダプタ、ホスト provider 群 |
| `kotoba-lang/compiler` | マルチターゲット AOT バックエンド | frontend→HIR→KIR→(wasm32-browser/wasi \| native x86/aarch64) の独立コンパイルパイプライン |
| `kotoba-lang/kototama` | actor:host ABI ホスティング runtime | 多重テナント fleet dispatch（JVM/Chicory tender + browser-native host）、`aiueos` の決定を**翻訳するだけ**（自ら決定しない） |
| `kotoba-lang/aiueos` | capability OS の決定層 | manifest/policy/grant/audit の権威、quota/fuel/memory 施行、他アダプタ（kototama 含む）への決定サービス提供 |

1. **言語プロファイルの一本化（`compiler/` → `kotoba-lang/kotoba-lang`）**: `compiler/`
   は独自に導出した reserved-forms/`forbidden-heads` 集合を正本として維持することを
   やめ、`kotoba-lang/kotoba-lang` の `lang/profile.edn`（またはその conformance
   fixtures）を単一の真実源として消費する。現状の字面不一致（`when`/`do`/`and`/`or` の
   扱い等）は「意図的な設計差異」ではなく**バグとして扱い**、両リポジトリの README に
   相互参照を追加する（Phase 1、§Plan）。
2. **Chicory 実行プラミングの共有化実験（`kotoba` / `kototama` / `aiueos`）**:
   ADR-2607022700 が既に確立した「aiueos が決定し、kototama が実行を委譲される（決定層の
   コードマージはしない）」という原則は**変更しない**。本 ADR が対象にするのはその**下**の
   レイヤー——Chicory `Instance.Builder` 配線・host-import closure dispatch・
   `withMemoryLimits`/`withUnsafeExecutionListener` の設定——が3リポジトリで独立に
   手書きされている実態を、共有可能かどうか**実験する**（Phase 2/3、§Plan）。これは
   「統合すべき」という確定decisionではなく「試して、有効なら採用・無効なら
   'tried, not worth it' として明示的に記録して終える」実験である——fuel-metering hook が
   Chicory の非公式APIである以上、3箇所の実際の instantiate 要件が共有に値するほど
   似ているとは限らないため。
3. **リポジトリの git 統合はしない**: west/manifest 上、5リポジトリは引き続き独立
   エントリのまま。「統合」は責務境界の明確化と重複排除であって、git history の
   merge やリリースサイクルの lock-step 化ではない。
4. **`.kotoba` は引き続き狭いスコープに留める**: 前ターンの分析（サンドボックス化・
   capability-checked な自動化セルなど）を再確認し、`.cljc` 5,008ファイルの一般移植は
   本 ADR のスコープ外のまま。`kotoba-lang/kotobascript` の新設も見送る（前ターンの
   結論を維持——`.kotoba` を JS ターゲットにすることは capability-safe な設計意図と
   衝突し、Node.js での cljs 実行は既に `kototama.node.cljs` 等の標準 cljs/nbb 経路で
   足りている）。

## Plan（実験なので段階を明示する）

- **Phase 0（本 ADR）**: 責務境界表と重複箇所の記録。完了。
- **Phase 1**: `compiler/` の reserved-forms 集合と `kotoba-lang/kotoba-lang` の
  `profile.edn` を diff し、各不一致が「意図的な機能追加」か「単なる drift」かを判定、
  `compiler/` 側を `profile.edn` 準拠へ寄せる修正 + 両リポジトリ README への相互参照
  追加。テストスイート green を確認してから着地。
- **Phase 2**: 3箇所の Chicory 実行プラミングのうち1箇所（`aiueos.execute` を想定——
  他2箇所と同じく JVM-only かつもっとも新しく書かれている）を対象に、共有ライブラリ
  抽出のプロトタイプを試作し、既存テストスイート green を確認する。
- **Phase 3**: Phase 2 が「コード量削減・カバレッジ維持・性能劣化なし」を示せたら
  `kotoba.wasm-exec` と `kototama.tender` も移行する。示せなければ、実験結果を
  Addendum として記録し「試したが見合わなかった」で close する——無理に統合しない。

## Consequences

**正**: `.kotoba` の言語仕様が二重に定義されるリスク（`compiler/` が受理する
プログラムと `kotoba` が受理するプログラムが静かに乖離する）を Phase 1 で解消できる。
Phase 2/3 が成功すれば、Chicory API の将来変更（特に非公式な fuel-metering hook が
upstream で削除された場合）への追従コストが1箇所に集約される。

**負/リスク**: Phase 2/3 は「3つの実行コンテキスト（生サンドボックス／actor-fleet ABI／
capability-OS の quota+fuel）が実際には別々の Instance-building コードを要する」という
結果に終わる可能性があり、その場合は複雑性削減にならない——これは許容された実験結果
であり、失敗ではなく「試して記録した」ことそのものが成果とみなす。

**中立**: 本 ADR 単体でユーザ向けの挙動変化は無い（構造・プロセス上の決定 + Phase 1 の
言語プロファイル修正のみ）。

## Alternatives Considered

- **5リポジトリを1つのモノレポに統合する**: 却下。west/manifest は既にこれらを
  独立バージョニング単位として扱っており（`compiler/` は `kotoba-lang/kotoba-lang` が
  必要としないマルチプラットフォームネイティブビルドを持つ）、境界自体は（今回発見した
  2箇所を除き）概ね健全——merge は意味のない lock-step リリースを強制するだけ。
- **`compiler/` の独自 reserved-forms 集合をそのまま許容する（「別コンパイラなので
  受理範囲が違ってよい」）**: 却下。それを意図的な設計とドキュメント化している箇所は
  どこにも無く、`kotoba-lang/kotoba-lang` 自身の `versioning.md` が
  `:kotoba.lang/profile-version` gate で防ごうとしている「外部実装は準拠レベルを宣言せず
  静かに乖離してはならない」というまさにその状況に該当する。
- **`kototama`/`aiueos` の決定アルゴリズムそのものを統合する**: 却下。
  ADR-2607022700 が既に明示的に禁止している（「aiueos decides, kototama enforces...not
  a code-level merge of the two execution namespaces」）。本 ADR はその原則の**下の**
  低レベル配線のみを対象とする。
- **`kotoba-lang/kotobascript` を新設し `.kotoba`→JS backend を `compiler/` に追加する**:
  前ターンの分析で却下済み、本 ADR でも再確認して見送る（§Decision 4）。

## References

- 90-docs/adr/2607022800-kotoba-lang-org-dependency-map.md
- 90-docs/adr/2607022400-kototama-unikernel-tender-runtime-vocabulary.md
- 90-docs/adr/2607022700-aiueos-native-adapter-tender-design.md
- 90-docs/adr/2607022900-aiueos-chicory-jvm-native-adapter.md
- 90-docs/adr/2607062330-kototama-tender-chicory-execution-runtime.md
- 90-docs/adr/2607062400-kototama-actor-host-browser-native-and-tender-module-fix.md
- 90-docs/adr/2607100030-kotoba-kami-engine-host-imports.md
- orgs/kotoba-lang/kotoba-lang/README.md, docs/lang/versioning.md, lang/profile.edn
- orgs/kotoba-lang/kotoba/README.md, CLAUDE.md, src/kotoba/wasm_exec.clj
- orgs/kotoba-lang/compiler/README.md, src/kotoba/compiler/frontend.clj
- orgs/kotoba-lang/kototama/docs/maturity.md, src/kototama/tender.clj,
  src/kototama/aiueos_adapter.clj
- orgs/kotoba-lang/aiueos/README.md, src/aiueos/execute.cljc

## 2026-07-14 Addendum — Phase 1 実施（compiler/ 独自 reserved-forms → 診断の訂正 + 相互参照追加）

「p1」の指示を受け Phase 1（当初計画:「`compiler/` の reserved-forms 集合と
`kotoba-lang/kotoba-lang` の `profile.edn` を diff し、乖離を修正」）に着手したところ、
**着手前提そのものに誤りがあった**ことが判明した。実物を読んで訂正する:

**訂正1: `kotoba-lang/kotoba-lang` の `lang/profile.edn` は「特殊フォーム/reserved-forms
の文法」を一切定義していない。** 中身を実際に読むと、`profile.edn` が持つのは
`:kotoba.lang/source-extensions`・`:reader-targets`・`:namespace-extension-priority`
のみ——**ファイル拡張子とリーダーターゲット解決のcontract**であって、`def`/`defn`/`if`/
`when`/…のような受理される式の文法ではない。`kotoba-lang/kotoba-lang` の README 自身も
「Repository Scope」で source-extension/CLI/package contract のみを権威範囲として明記して
おり、特殊フォームの文法には一言も触れていない。したがって Decision §1 が想定した
「`compiler/` は `profile.edn` を文法の正典として consume すべき」は**そもそも consume
できるものがそこに存在しない**——前提が誤っていた。

**訂正2（本当の重複箇所）: `compiler/` の reserved-forms 集合は、実は `kotoba-lang/kotoba`
README の "safe Kotoba" 三ゲート設計（T1 Memory Safety / T2 Effect Soundness / T3
Capability Confinement、`policy.rs`/`subset.rs`/`effects.rs` として Rust 時代に実装され
2026-07-01 に除去済み）の CLJC-native 後継そのものだった。** `compiler/frontend.clj` の
`forbidden-heads`＝subset ゲート（`eval`/`require`/`import`/`set!`/`defmacro`/reflection
禁止、`kotoba/` の `subset.rs` と字面レベルで一致）、`cap-call`＝capability ゲート、
`direct-facts`+`infer-effects`（関数間 fixpoint、相互再帰で収束）＝effect ゲート
（`kotoba/` README の「checked interprocedurally...mutual recursion converges」という
記述と概念的に完全一致）。しかし **`kotoba-lang/kotoba` の README（`604896171b` 時点の
記述）は、この CLJC-native 後継が「`kotoba-lang/kotoba-lang` に丸ごと存在する」と誤記して
いた**——実際には `kotoba-lang/kotoba-lang` にこの三ゲートの実装は影も形もない
（grep 0件）。`compiler/` 自身の README も「the...compiler for the safe Kotoba language」
と名乗ってはいたが、`kotoba-lang/kotoba` 側からの相互参照が一切なく、この帰属誤りに
誰も気付いていなかった。

**Decision §1 は上記の通り誤りだったため撤回し**、代わりに実際に見つかった帰属誤りを
3リポジトリの README 相互参照で修正した（doc-only、コード変更なし、テスト影響なし）:

- `kotoba-lang/compiler` `README.md`: 「Relationship to `kotoba-lang/kotoba` and
  `kotoba-lang/kotoba-lang`」節を新設。safe-Kotoba 三ゲート↔このrepoの実装対応表
  （subset↔forbidden-heads、capability↔cap-call、effect↔direct-facts/infer-effects）を
  明記、この repo が CLJC-native 後継であること・`kotoba/` の文法と未統合のまま
  独立進化した2つの異なる grammar である事実を明記（`96f72fb`→`26ff523`）。
- `kotoba-lang/kotoba` `README.md`: 「lives entirely in `kotoba-lang/kotoba-lang`
  itself」という誤記述を「lives in `kotoba-lang/compiler`」に訂正
  （`53d51110c`→`ecadee870`）。
- `kotoba-lang/kotoba-lang` `README.md`: 「Out of scope, by design」として、この repo が
  safe-Kotoba 三ゲートを実装しないこと、実装は `kotoba-lang/compiler` にあることを明記
  （`ca6aba5d4`→`a777ecd1`）。

3リポジトリとも `docs/p1-repo-boundary-cross-references` ブランチで作業
（superproject 外の sibling worktree、`git worktree add -B ... origin/main` パターン）、
`gh api .../merges` でサーバ側マージ、branch/worktree cleanup 完了。`manifest/west.yml`
の `kotoba` / `kotoba-lang` 2 entry を `--entry` 最小 diff で pin 前進済み
（`nbb scripts/gen-west-manifest.cljs --check` は他リポジトリ由来の既存 STALE 分を含み
全体は通らないが、今回触った2 entry はいずれも fast-forward 検証 OK）。

**`kotoba-lang/compiler` は west manifest に未登録**（`--entry compiler` が
"生成結果に entry がありません" で失敗——`kami-studio`/`kami-creative-studio`
と同型の「手動 clone 済みだが manifest 未登録」状態）。README への doc push/merge 自体は
manifest 登録の有無と無関係に完了させたが、**正式登録は本 Phase のスコープ外**として
意図的に据え置く（ADR-2607132300 Addendum 2 の precedent と同じ判断）。

**Phase 2/3（Chicory 実行プラミングの共有化実験）は未着手のまま。** 次回セッションへの
引き継ぎ:
1. `kotoba-lang/compiler` の west manifest 正式登録（別判断が必要、本ADRのスコープ外）
2. Phase 2: `aiueos.execute` を対象にした Chicory プラミング共有化のプロトタイプ
3. `kotoba/` の友好的surface文法と `compiler/` のゲート付きKIR文法——今回判明した通り
   2つは**意図的にではなく、そうと知らずに**別々に進化した——を実際に統合するか、
   明示的に「2つの異なる profile」として設計文書化するかの決定（今回は relationship を
   記録しただけで、統合の是非そのものはまだ決めていない）

## 2026-07-14 Addendum 2 — Phase 2 実施（Chicory プラミング共有化: 精読比較の上 negative-close）

「next」の指示を受け Phase 2（`aiueos.execute` を対象にした Chicory プラミング共有化の
プロトタイプ、テストスイート green を確認）に着手した。3ファイル（`aiueos/src/aiueos/
execute.cljc` 473行、`kototama/src/kototama/tender.clj` 609行、`kotoba/src/kotoba/
wasm_exec.clj` 683行、合計1765行）を実際に全文精読し、Instance-building/host-import
配線の実態を比較した。

**発見1（決定的）: `kototama.tender` 自身の namespace docstring（18-28行目）が、
まさにこの実験を過去に一度検討し、明示的に却下していた。** 「The generic Chicory
wiring below (host-fn/instantiate/call-main/run-main/fuel-listener/memory
ptr-len helpers) is written fresh here, not vendored from kotoba-lang/kotoba's
wasm_exec.clj — that namespace is tightly coupled to kotoba's OWN kgraph/
capability vocabulary...and pulling it in as a dependency would run a second,
incompatible capability model next to kototama's own actor:host ABI (the
'semantic authority duplication' ADR-2607022700 rules out). The Chicory API
calls themselves are the same shape because there is only one sane way to
wire a HostFunction — proof-of-pattern, not shared code.」

**発見2（実測による定量評価）: 3ファイルとも `host-fn`/`valtype`/`write-bytes!`/
`read-bytes!`（or `read-str`）/`fuel-listener`/`memory-limits-for` の6ヘルパーは
関数名・実装とも酷似しているが、字面が同じ部分は各ファイル60〜90行程度
（全体1765行の12〜15%）に留まり、しかも完全同一ではない**:

- `host-fn` の引数規約が違う——`aiueos`は`params`をキーワードで受けて内部で
  `valtype`マップ経由変換、`kototama`/`kotoba`は事前に解決済みの`ValType`
  オブジェクトを直接受け取る。
- エラー処理の哲学が3者3様——`aiueos`は例外(`ex-info`)を投げて`run-if-granted`が
  中央で`catch`・翻訳、`kototama`は帯域内`-1`センチネル(quota/http系)と
  `denied!`例外(構造違反系)を使い分け、`kotoba`はまた別の`capability-granted?`
  ベースの拒否パターンを持つ。
- `fuel-listener`のex-data語彙が3者バラバラ——`:aiueos.execute/fuel-exceeded`
  vs `:kototama.tender/problem :fuel-exhausted` vs `:kotoba.wasm/problem
  :fuel-exhausted`（それぞれ独自のドメイン語彙、ADR-2607022700が権威分離を
  求める通りの結果）。
- `memory-limits-for`の前提が違う——`aiueos`は「`.kotoba`コンパイル済みモジュールは
  必ずmemory sectionを持つ」というドキュメント化済みの不変条件を前提に
  `.get()`を無条件呼び出し、`kototama`は任意のChicoryゲストを想定して
  `.isPresent()`を防御的にチェックする（**バグではなく、入力ドメインの
  スコープが違う——`aiueos`は`.kotoba`専用、`kototama`は汎用**、と確認した）。

**結論（Phase 2 の実験結果として記録）: 共有ライブラリの抽出は見送る
（negative-close）。**

1. `kototama.tender`の設計者は既に一度この判断を下しており（発見1）、その理由
   （ADR-2607022700の「semantic authority duplication」原則）は今回の3ファイル
   比較でも裏付けられた——3者のエラー語彙・引数規約は意図的にドメインごとに
   独立しており、これは事故ではなく設計。
2. 定量的に見ても割に合わない——3リポジトリ合計1765行のうち共有可能な部分は
   多くて200行強、しかもそれすら完全同一ではなく共通APIに正規化する追加の
   抽象化コストが発生する。新規共有repoを1つ作る場合のコスト（新規ADR・
   新規GitHub repo・west登録・3リポジトリでの依存追加・3つのJVMテストスイート
   [`clojure -M:test`]の再検証)は、削減できる重複行数に見合わない。
3. ADR本文 §Plan が明記した Phase 3 のゲート条件（「if phase 2 proves
   net-positive...if not, record negative result and close as 'tried, not
   worth it'」）に従い、**Phase 3（実際の移行）は実施しない**。

**この判断はコード変更を一切伴わない**（読解・比較のみ、3リポジトリとも
無変更）——「試して、記録した」こと自体が本 Phase の成果である
（ADR本文 §Consequences 参照）。

以上でこの ADR が計画した実験（Phase 0〜3）は完了したため、ステータスを
`accepted — closed` に更新する。今回の副産物として見つかった、まだ本 ADR の
スコープに含めていない項目（`kotoba-lang/compiler` の west 正式登録、`kotoba/`
と`compiler/`の2つの異なる文法をいつか統合するか否かの決定）は Phase 1
Addendum の「引き継ぎ」節にある通り、別ADR/別セッションに委ねる。

## 2026-07-14 Addendum 3 — `kotoba-lang/compiler` の west 正式登録（引き継ぎ項目1の解消）

「次、どちらをやるか」を尋ねたところ「Register kotoba-lang/compiler in west」の指示を
受け、Phase 1 Addendum が引き継ぎ項目としていた `kotoba-lang/compiler` の west manifest
未登録状態を解消した。

着手時点で判明した事実: `manifest/repos.edn` の `:manifest.repos/extra-projects` には
既に `"orgs/kotoba-lang/compiler"` が含まれていた——`git log -S'"orgs/kotoba-lang/
compiler"' -- manifest/repos.edn` で追跡すると、直前に着地していた**無関係な**
`kyoninka`（ITAD許認可手続き, ADR-2607141620）commit（`ed0e4a844473`）が同時に追加して
いた。並行セッションが同じギャップに気付き先に着手していたと見られる。一方
`manifest/west.yml` 側にはまだ `name: compiler` エントリが生成されておらず（
`repos.edn` は手書きポリシー、`west.yml` は生成物——前者の追加だけでは後者は自動で
追従しない）、`nbb scripts/gen-west-manifest.cljs --entry compiler` は "新規 entry"
として実行できる状態だった。

実施: `--entry compiler` で最小 diff 生成（`verify-west-pins.cljs` が pin
`26ff5233b569`（Phase 1 で押した README doc-fix merge commit と同一）を「main から
到達可能」と検証済み）。superproject 外の sibling worktree
（`chore/west-register-kotoba-lang-compiler`）で commit・push・`gh api .../merges`
サーバ側マージ・branch/worktree cleanup、という Phase 1/2 と同じ手順。`repos.edn` 側は
既に main にあったため無変更（重複追記していない）。

**着地**: `manifest/west.yml` に `compiler`（`kotoba-lang/compiler`,
`orgs/kotoba-lang/compiler`, `26ff5233b56950f8ae95390e35cb7e2d71aee87c`）が
登録された（`8e775c6d4f0d`）。`west update` で他 project 群と同様に同期対象になる。

**残る引き継ぎ項目**（Phase 1 Addendum 記載のまま、未着手）: `kotoba/` の友好的
surface文法と `compiler/` のゲート付きKIR文法をいつか統合するか、明示的に「2つの
異なる profile」として設計文書化するかの決定——これは west 登録のような機械的作業
ではなく、言語設計判断そのものなので、引き続き別セッション・（必要なら）別 ADR に
委ねる。
