---
id: adr-2607083000-kotoba-lang-cljc-cljs-portability-lint-ci-sweep
title: "ADR-2607083000: kotoba-lang org 横断 — .cljc の cljs-portability バグ修正 + clj-kondo lint/CI ゲート追加（成熟度向上ループ）"
status: accepted
doc_type: adr
topic: kotoba-lang-cljc-cljs-portability-lint-ci-sweep
authoritative: true
last_verified: 2026-07-08
authoritative_for:
  - "kotoba-lang org の .cljc ファイルには、拡張子が dual-platform (JVM/cljs) を謳っているにもかかわらず、実際は JVM-only なシンボル（the-ns、Character/*、Double/parseDouble、clojure.core/format、StringBuilder、byte-array、BigInteger、clojure.lang.ExceptionInfo、未 require の clojure.string 完全修飾呼び出し）を無guard で使っているファイルが多数存在し、`:lint` alias（clj-kondo、--fail-level error）が無い repo ではこれが検出されずに埋め込まれ続けていた。多くの repo に `:lint` alias 自体が存在しなかった。"
  - "確立した修正パターンは3種類: (1) the-ns → find-ns のような drop-in 置換、(2) 実際に動く cljs 実装が書ける場合（TextEncoder による UTF-8 percent-encoding、NaN 時に throw する js/parseFloat ラッパ、byte-array の代わりに vec-of-ints）は #?(:clj ... :cljs ...) で本当に portable にする、(3) 現実的な cljs 実装が無い場合（BigInteger 任意精度演算、javax.crypto、MessageDigest 等の重い暗号）は namespace 全体を #?(:clj (do ...)) で包み同名の throwing :cljs stub を用意する — eth-crypto.core / kotoba-lang/io-multiformats.core が確立した先例に倣う。test ファイルが clojure.test のみを require し（cljs.test を一度も require しない）JVM-only なテストヘルパー（byte-array/format/Integer.parseInt/slurp）を使っているだけの場合は、reader-conditional で無理に portable にせず `.cljc` → `.clj` にリネームする方が正直で保守しやすい。"
  - "本セッションで検証・着地させた具体例（コミット SHA は各 repo の main 上）: kotoba-lang/game(6c092648, +'/*' の auto-promoting/no-cljs-equiv な演算を unchecked-add/unchecked-multiply へ、docstring が謳う「Rust の wrapping を模す」意図によりむしろ忠実), kotoba-lang/spice→edu-berkeley-spice へのリネームも並行発覚(65ba8f8f, Character/isLetter+Double/parseDouble), kotoba-lang/input(17da4c18, 古い未 push ローカル main を「本当に obsolete か」diff で検証した上で reset), kotoba-lang/kami-cad-import(f253cbb9, 最大規模の修正 — urlencode の Character/isLetterOrDigit+.getBytes/format による UTF-8 percent-encoding を TextEncoder ベースの helper へ、副産物として Character/isLetterOrDigit が Unicode 文字を非標準的に \"safe\" 扱いしていた潜在的正しさの問題も同時に是正; manifest 登録漏れ(repos.edn にはあるが west.yml に無い)も発見), kotoba-lang/btc-crypto(a66155ca, 24 エラー、6 namespace 全てが正真正銘 JVM-only な Bitcoin 暗号なので eth-crypto.core 方式の throwing stub でラップ、実ロジックは一切変更せず、test 7ファイルは .clj へリネーム), kotoba-lang/btc-mining(d7cd59d0, btc-crypto に推移的依存するため同じラップ方式), kotoba-lang/crypto(d40b315e, 唯一 \"本当に portable にした\" 例 — 自身の docstring が dual-platform を明言しロジックも純粋なビット演算だったため throwing stub ではなく ->bytes helper で本当の portability を実現、io-multiformats.core の byte-array/vec 先例に倣う), kotoba-lang/nagare(c51fc7d1, demo.cljc の -main の Integer/parseInt+format を portable helper 化、副産物として clj-kondo の format 文字列引数カウントの false positive も解消(REPL で実行時出力が常に正しいことを検証済み)), kotoba-lang/chobo(85078e77, invoice.cljc の Integer/parseInt+裸の Exception catch を修正)。全て: 修正前後でテストのアサーション数を確認 → clj-kondo 0 errors 確認 → branch+PR+サーバサイド merge で着地 → 実 GitHub Actions CI が green であることを再確認 → `nbb scripts/gen-west-manifest.cljs --entry <name>` で manifest pin のみ前進、という手順を毎回踏んだ。"
  - "本 ADR 起票以前（同一セッション、コンテキスト圧縮前）に、the-ns→find-ns の1次修正（~42 repo規模）と、JVM-only な :test alias（cognitect.test-runner ではなく実際には決して JVM でしか動かない誤った main-opts を持つ alias で、テストが静かに一度も実行されていなかったバグ）の修正（~20 repo規模、kami-engine クラスタ復元 repo 群）も同一ループ内で実施済み。これらは並行していた別セッション（kami-engine 復元効果）の実装と衝突・reconciliation が発生し、重複コミットの破棄や再適用で対応した。"
related:
  - 90-docs/adr/2607010930-clj-wgsl-migration.edn
  - 90-docs/adr/2607012230-kotoba-lang-btc-mining-wallet-substrate.md
  - 90-docs/adr/2607012300-sha256d-clj-portable-cosci-tournament.md
  - 90-docs/adr/2607012200-kotoba-lang-cljc-refactor.md
supersedes: []
superseded_by: []
---

# ADR-2607083000: kotoba-lang org 横断 — .cljc の cljs-portability バグ修正 + clj-kondo lint/CI ゲート追加（成熟度向上ループ）

**Status**: accepted
**Date**: 2026-07-08
**Deciders**: Jun Kawasaki（`成熟度を向上` 定期ループ、30分間隔の自律イテレーションとして毎回オーナーの明示的トリガーで実行。個々の repo 選定・修正方針は各イテレーション内で判断）

## Context

`成熟度を向上` という定期ループ（オーナーが30分おきに送る同一プロンプトで駆動）の中で、
kotoba-lang org 配下の `.cljc` ファイル（`clj`/`cljs` 両対応を意図した拡張子）を対象に
`clj-kondo` の `:cljs` 解析パスを走らせたところ、JVM 専用シンボルを無 guard で使っている
ファイルが多数見つかった。多くの repo にはそもそも `:lint` alias が存在せず、
`clojure -M:test`（JVM 実行）は green でも `.cljc` を名乗る以上本来満たすべき cljs 互換性が
実は満たされていない、という状態が放置されていた。

見つかった JVM-only シンボルのパターン:

- `the-ns`（cljs に相当物なし。`find-ns` が portable な代替）
- `Character/isLetter` / `Character/isLetterOrDigit` / `Character/digit`
- `Double/parseDouble` / `Integer/parseInt` / `Long/parseLong`
- `clojure.core/format`（`java.util.Formatter` をラップ、JVM-only）
- `StringBuilder`（可変文字列、cljs に相当物なし）
- `byte-array`（cljs.core に定義が無い — JS にはネイティブ byte array 型が無いため）
- `java.math.BigInteger` / `javax.crypto.*` / `java.security.MessageDigest`
- `clojure.lang.ExceptionInfo`（catch節。cljs 側は `ExceptionInfo`）
- 完全修飾 `clojure.string/*` 呼び出し（`:require` に alias が無い）

## Decision

修正は3パターンに分類し、それぞれ機械的に適用した:

### (1) drop-in 置換
`the-ns` → `find-ns`。意味的に完全に等価で reader-conditional 不要。

### (2) 本当に portable にする（実装が現実的な場合）
既存の cljs 標準機能・ブラウザ API で同等の実装が書ける場合は `#?(:clj ... :cljs ...)` で
本当にデュアルプラットフォーム対応にする。具体例:

- UTF-8 バイト単位の percent-encoding: `.getBytes`+`format` (JVM) ↔ `js/TextEncoder` (cljs)
- 厳密な数値パース: `Double/parseDouble`（失敗時 throw）↔ `js/parseFloat` をラップして
  `NaN` の場合に throw させる（既存の try/catch フォールバックを両プラットフォームで
  同一に保つ）
- バイト列表現: `byte-array`（JVM）↔ `vec`-of-ints（cljs） — `kotoba-lang/io-multiformats.core`
  が確立した先例
- 文字判定: `Character/isLetterOrDigit`/`Character/digit` → 正規表現ベースの判定
  （`(re-matches #"[A-Za-z0-9]" (str c))` 等、両プラットフォームで同一実装、
  reader-conditional 不要になるケースも多い）

`kotoba-lang/crypto`（`kotoba.lang.crypto`）はこのパターンの代表例: 自身の docstring が
「Portable — ... .cljc (JVM/SCI/CLJS/GraalVM/kotoba-WASM)」と dual-platform intent を
明言しており、実ロジック（HMAC 構成・HKDF 展開・AEAD XOR mock）も純粋なビット/バイト演算で
JVM 専用の数学は無かったため、`byte-array` の構築部分だけを `->bytes` helper
（`:clj` は `byte-array`、`:cljs` は `vec`）に切り出して本当に portable にした。

### (3) `:clj-only` ラップ + throwing `:cljs` stub（現実的な cljs 実装が無い場合）
`java.math.BigInteger` の任意精度演算や `javax.crypto`/`java.security.MessageDigest` の
ような、ブラウザ側に相当する標準ライブラリが無い（または再実装が正当化できないほど大きい）
処理は、namespace の実装全体を `#?(:clj (do ...))` で包み、公開関数と同名の
throwing `:cljs` stub を用意する。これは `eth-crypto.core`
（Keccak-256/secp256k1 ecrecover を `BigInteger`/64bit レーン演算のため `:clj-only` にした
先例）と `kotoba-lang/io-multiformats.core` が既に確立していたパターンで、今回はこれを
Bitcoin 系の暗号ライブラリ群（`btc-crypto`, `btc-mining`）に一貫して適用した。
**実際の `:clj` ロジックは一切変更しない** — 追加されるのは throw するだけの `:cljs` 分岐のみ。

test ファイルが `clojure.test` のみを require し（`cljs.test` を一度も require しない）
JVM-only なテストヘルパー（`byte-array`/`format`/`Integer/parseInt`/`slurp`）を使っている
だけの場合は、reader-conditional で無理に portable にせず `.cljc` → `.clj` へリネームする方を
選んだ（`btc-crypto` 7ファイル、`btc-mining` 1ファイル、`nagare` の
`portability_test.cljc` — 後者は自身の docstring が既に「このテスト自身は slurp を使うので
JVM 側でのみ動く」と明記していた）。

### 着地手順（毎回同一）
1. ローカルで `clojure -M:test` のアサーション数を修正前後で比較 → 回帰なしを確認
2. `clojure -M:lint`（新規追加した alias）で 0 errors を確認
3. child repo 単体で branch を切り push → `gh api .../merges` でサーバサイド merge
   （ローカル shallow merge を戦わない）
4. 実 GitHub Actions CI が green であることを `gh api .../actions/runs` で再確認
5. superproject の `manifest/west.yml` を `nbb scripts/gen-west-manifest.cljs --entry <name>`
   （wholesale 再生成ではなく単一 entry のみ）で前進 → `verify-west-pins` の
   pin 検証（fast-forward 確認）を通す

## 検証済みの具体的な着地（本 ADR 起票時点、コミット SHA は各 repo main）

| repo | 主な修正 | 着地コミット |
|---|---|---|
| kotoba-lang/game | `+'`/`*'`（auto-promoting、cljs 相当物なし）→ `unchecked-add`/`unchecked-multiply`（docstring が謳う「Rust の wrapping_mul/wrapping_add を模す」により本来の意図に近い）; ネストした test ファイル2件の `the-ns` | `6c092648` |
| kotoba-lang/spice → `edu-berkeley-spice` | `Character/isLetter`+`Double/parseDouble` → portable helper; push 直前に repo rename が判明し local checkout も追従 | `65ba8f8f` |
| kotoba-lang/input | 古い未 push ローカル `main`（pre-restoration 期の obsolete な履歴と diff で確認）を reset した上で `the-ns` 修正 | `17da4c18` |
| kotoba-lang/kami-cad-import | urlencode の `Character/isLetterOrDigit`+`.getBytes`/`format` UTF-8 percent-encoding → `TextEncoder` ベースの helper（副産物として Unicode 文字の非標準的な \"safe\" 扱いも是正）; `StringBuilder` → 関数型文字列構築; `format "%.4f"` → helper; 8ファイルの未 require `clojure.string`; manifest 登録漏れ（`repos.edn` にはあるが `west.yml` に無かった）も発見・是正 | `f253cbb9` |
| kotoba-lang/btc-crypto | 24エラー。6 namespace 全てが正真正銘 JVM-only な Bitcoin 暗号（`BigInteger`/`javax.crypto`/`MessageDigest`/`byte-array`）→ `eth-crypto.core` 方式の throwing `:cljs` stub でラップ（実ロジック無変更）; test 7ファイルを `.clj` へリネーム | `a66155ca` |
| kotoba-lang/btc-mining | `btc-crypto` に推移的依存するため同じラップ方式; test 1ファイルを `.clj` へリネーム | `d7cd59d0` |
| kotoba-lang/crypto | 唯一「本当に portable にした」例 — 自身の docstring が dual-platform を明言しロジックも純粋ビット演算だったため `->bytes` helper（`io-multiformats.core` 先例）で真の portability を実現; `clojure.lang.ExceptionInfo`（8箇所） | `d40b315e` |
| kotoba-lang/nagare | `demo.cljc` の `-main` の `Integer/parseInt`+`format` → portable helper（CLI 出力が byte-for-byte 同一であることを実行して確認）; 副産物として clj-kondo の format 文字列引数カウントの false positive も解消（REPL で実行時出力が常に正しいことを検証済み）; `portability_test.cljc` を `.clj` へ | `c51fc7d1` |
| kotoba-lang/chobo | `invoice.cljc` の `Integer/parseInt`+裸の `Exception` catch → portable helper; test の未 require `clojure.string` | `85078e77` |

各 repo とも: `:lint` alias（clj-kondo `2024.11.14`、`--fail-level error`）を新規追加し、
既存 CI ワークフローに lint ステップを追加、実 CI green を確認済み。

同一セッション・本 ADR 起票以前（コンテキスト圧縮前）には、`the-ns`→`find-ns` の
1次修正（~42 repo規模）と、JVM-only な `:test` alias（`cognitect.test-runner` ではなく
実際には JVM でしか動かない誤った `main-opts` を持つ alias により、テストが静かに
一度も実行されていなかったバグ）の修正（~20 repo規模、kami-engine クラスタ復元 repo 群）も
実施済み。これらは並行していた別セッション（kami-engine 復元効果）の実装と衝突・
reconciliation が発生し、重複コミットの破棄や再適用で対応した。

## Consequences

- 上記9 repo（+ 起票以前の ~42/~20 repo規模のバッチ）で `.cljc` の実態が拡張子の約束通り
  cljs 互換になった、または JVM-only である実態が `.clj` 拡張子/throwing stub として
  正直に文書化された。
- 各 repo に `:lint` alias + CI lint ステップが追加され、今後の劣化を CI が検出する。
- `eth-crypto.core`/`io-multiformats.core` が確立していた「`:clj-only` ラップ + throwing
  `:cljs` stub」パターンが、Bitcoin 系暗号ライブラリ群（`btc-crypto`/`btc-mining`）にも
  一貫して適用され、org 全体でのスタイルが揃った。
- `kami-cad-import` の manifest 登録漏れ（`repos.edn` にはあるが `west.yml` に無い）を
  発見・是正した副産物として、`gen-west-manifest.cljs --entry` が「既存 pin の前進」だけでなく
  「未登録 entry の新規追加」もサポートしていることを確認した。
- `kami-cad-import` の urlencode 修正は、cljs-portability のついでに
  `Character/isLetterOrDigit` が非標準的に Unicode 文字を \"URL-safe\" 扱いしていた
  潜在的な正しさの問題も是正する副産物があった。

## Not Decided / 未完了

- kotoba-lang org は600+ repo規模であり、本 ADR の対象は既知パターンを grep して
  opportunistic に見つけた repo群に限られる。網羅的な監査は行っていない — 同様の
  JVM-only シンボル未 guard 問題が未発見のまま残っている repo は他にも存在しうる。
- 起票以前に実施した ~42/~20 repo規模の1次バッチについて、全 repo が `:lint` alias + CI
  まで完了しているかは本 ADR 単独では保証しない（一部は完了確認済み、一部は
  the-ns 修正のみで `:lint`/CI 追加が未完了の可能性がある — 後続イテレーションで
  棚卸しが必要）。
- `:lint` alias を持つ repo は依然 kotoba-lang org 全体のごく一部。org 全体への
  `:lint`+CI 展開自体は本 ADR のスコープ外。
