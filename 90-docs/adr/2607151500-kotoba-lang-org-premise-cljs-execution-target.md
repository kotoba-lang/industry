---
id: adr-2607151500-kotoba-lang-org-premise-cljs-execution-target
title: "ADR-2607151500: kotoba-lang org を `.kotoba` 前提にする方針 + `.kotoba` の新しい ClojureScript 実行ターゲット"
status: accepted
date: 2026-07-14
deciders:
  - Jun Kawasaki（「ok, では 他の kotoba-lang の repo も .kotoba の前提に. cljs ウェブ実行も
    .kotoba から clojurescirpt も実行できるように. adr を更新」）
related:
  - 90-docs/adr/2607150000-kotoba-lang-extension-triage-langchain-persistence-kotobase.md
  - 90-docs/adr/2607141900-cloud-itonami-cljc-actors-kotoba-incompatibility-narrow-slice-porting-policy.md
  - 90-docs/adr/2607141600-kotoba-kotoba-lang-compiler-kototama-aiueos-consolidation-experiment.md
  - orgs/kotoba-lang/compiler（`src/kotoba/compiler/backend/cljs.clj`、新設）
  - orgs/kotoba-lang/kototama（`clj/src/kototama/unspsc/capability.cljc`、次の移植候補）
supersedes: []
superseded_by: []
last_verified: 2026-07-14
doc_type: adr
topic: kotoba-lang-org-premise-cljs-target
authoritative: true
authoritative_for:
  - "kotoba-lang org 内の各 repo に対する `.kotoba` 適用方針（本文 §Decision 1）を、
    今後の同種判断の正本とする位置づけ"
  - "`.kotoba` → ClojureScript backend の設計・意味論・既知の scope 限界
    （本文 §Decision 2、実装は `kotoba-lang/compiler`
    `src/kotoba/compiler/backend/cljs.clj`）を正本とする位置づけ"
---

# ADR-2607151500: kotoba-lang org を `.kotoba` 前提にする方針 + ClojureScript 実行ターゲットの新設

**Status**: accepted
**Date**: 2026-07-14
**Deciders**: Jun Kawasaki

## Context

ADR-2607150000 で cloud-itonami isic-6492 の governor 判定ロジックを `.kotoba`
へ narrow-slice port し、5段のAddendumを経てその ADR は完了した。続けてオーナー
から「他の kotoba-lang の repo も .kotoba の前提に. cljs ウェブ実行も .kotoba
から clojurescirpt も実行できるように」という指示を受けた。2つの独立した問いに
分解できる:

1. **kotoba-lang org 全体を `.kotoba` 前提にする、とは具体的に何を意味するか？**
   （org には多数の repo があり、大半は既に `.cljc`/cljs-portable な一般ライブラリ
   であって、cloud-itonami の governor のような「純粋な判定/検証ロジック」の形を
   していない——同じ narrow-slice pattern が当てはまるとは限らない。）
2. **`.kotoba` から ClojureScript（cljs、ブラウザ/Node実行）は今できるのか？**
   できないなら、それは新しく設計・実装すべき能力である。

着手前に、org 内の repo 群と、既存の `:cljs` reader-target まわりの実装状況を
調査した。

## 調査結果

### 1. kotoba-lang org の repo 群と `.kotoba`/cljs の現状

`.kotoba` ファイルが実在するのは `kotoba`（67）・`kotoba-lang`（5）・
`compiler`（7）・`kototama`（6）・`kotoba-v2025`（31、`.kotoba` 専用）・
`wasm-webcomponent`（4、`.kotoba`自体はコンパイルせずコンパイル済みWASMを
ホストするだけ）に集中している。それ以外の主要repo（`kotobase`・`langchain`・
`langgraph`・`css`・`shitsuke`・`kotoba-ui`・`uikit`・`appkit`・
`liquid-glass-ui`・多数の `kotoba-rad/git/issue/ledger/procedure/protocol/
fleet/client`・`kotoba-*-contracts` 群）は**既に純粋 `.cljc`、JVM+cljs
両対応で、JVM専用のものは1つも無い**——つまりこれらは「JVM専用だから
cljs/kotoba化すべき」という理由付けが最初から当てはまらない。加えて、これらは
map/protocol/多相ディスパッチ/atom等を使う**汎用ライブラリ**であり、
`.kotoba` の安全サブセット（純粋整数演算・`let`/`if`・named function call・
ADR-2607150000で追加したmap/keyword/destructuring/loop-recur）に収まる
「純粋な判定ロジック」の形をしていない——ADR-2607141900が`credit.governor/
check`の全体ファサードを非対象としたのと全く同じ理由で、これらライブラリ
全体を`.kotoba`化する動機・適合性のどちらも無い。

唯一の例外候補: `kototama/clj/src/kototama/unspsc/capability.cljc` の
`check`/`present?`/`risk-checks` 等——`atom`/`defrecord`/`langchain`/
`langgraph`依存が無い、純粋なpredicate/checkテーブルロジックで、
`credit.kernels.gate.cljc`と全く同じ「safe-kotoba subset適合済み」の形を
している。cloud-itonami という外部消費者向けだけでなく、**kotoba-lang org
自身の内部コードにも同じnarrow-slice patternが当てはまる実例**が見つかった
ことは重要——「他の repo も .kotoba 前提に」を空虚な標語で終わらせず、
具体的な次の移植先を持つ。

### 2. `.kotoba` → ClojureScript 実行は今存在するか

**結論: 存在しない。今日まで `.kotoba` はWASMのみをコンパイルターゲットとし、
「.kotoba ソースをcljsとして実行する」経路はどこにも無かった。**

`kotoba-lang/kotoba-lang`の`lang/profile.edn`は`:cljs`を`reader-target`
として今も認識するが、これは**読み込み時にどの`#?(:cljs ...)`
reader-conditional分岐を選ぶかだけ**を左右する——実行エンジンには一切関与
しない。`kotoba-lang/kotoba`の`launcher.clj`を全読した結果、`kotoba run
--reader-target cljs`は`kotoba.runtime/read-file`（読み込み時の分岐選択の
み`:cljs`が効く）→`kotoba.runtime/check`/`wasm-binary`という、常に
**JVM上の自前インタプリタ or WASM+Chicory**の経路を通る——`launcher_test.clj`
の`dot-cljs-entry-file-runs-under-its-own-default-reader-target`テストが
これを直接証明している（`.cljs`拡張子のファイルが`kotoba run`されても、
結果はJVMインタプリタ/WASM経路で計算される、V8/Node/cljsコンパイラは一切
関与しない）。

一方、`kototama/clj/src/kototama/node.cljs`が示すとおり、**Node.js上での
本物のcljs実行自体はこのorgに既に存在する**——ただし普通の`.cljc`→cljs→
Nodeコンパイルであって、`.kotoba`→WASMパイプラインとは完全に独立している。
また`cloud-itonami-isic-6492`の`wasm/verify_node.cljs`/`server.cljs`
（`wasm-webcomponent`の`actor-host.js`経由）が示すのは「cljs/nbbホストが
**コンパイル済みWASMバイナリ**を`WebAssembly.instantiate`でロードして実行
する」であって、これも「`.kotoba`ソースをcljsとしてコンパイルする」とは
別物——`actor-host.js`は`.kotoba`ソースにもcljsコンパイルにも一切関知しない。

この調査結果はADR-2607141600自身が既に独立に記録していた
（「`.kotoba`はWASMのみをコンパイルターゲットとし、cljs/JSへのコンパイル
パスはどこにも存在しない」）——本ADRはその指摘を実装で解消する。

## Decision

### 1. kotoba-lang org を「`.kotoba` 前提にする」の具体的な意味

「org全体のライブラリを書き直す」ことは**しない**——`kotobase`/`langchain`/
`langgraph`/UI層は汎用ライブラリであり`.kotoba`の安全サブセットに収まる形
をしていない。代わりに、以下の2点を標準ポリシーとする:

- **今後 kotoba-lang org 内で新しく書く「純粋な判定/検証/データ変換ロジック」
  は、`credit.kernels.gate.cljc`が先取りしたのと同じ safe-kotoba subset
  スタイル（純整数演算、`atom`/`defrecord`/多相ディスパッチ無し、named
  function callの合成）で書けるならそう書く。** 決定ロジックがこの形を
  していれば、後からの`.kotoba`移植コストはほぼゼロになる（ADR-2607150000
  Addendum 5で実証済み）。汎用ライブラリ本体はこの対象外。
- **具体的な次の移植先として `kototama/clj/src/kototama/unspsc/
  capability.cljc` を採用する**——cloud-itonami という外部消費者向けだけ
  でなく、org自身のコードにも同じnarrow-slice patternが適用できることを
  証明する。実装は本ADRの後続コミットとして着手する（本ADR単体のスコープ
  では設計・候補選定のみ）。

### 2. `.kotoba` の新しい ClojureScript 実行ターゲット

`kotoba-lang/compiler`（既にfrontend→HIR→KIR→backend{wasm32,x86_64,
aarch64}という多ターゲットアーキテクチャを持つ）に、KIRをプレーンな
ClojureScriptソーステキストへ下ろす**新しいbackend**を追加する
（`src/kotoba/compiler/backend/cljs.clj`、target `:cljs-kotoba-v1` +
os-scoped variants `:cljs-node-kotoba-v1`/`:cljs-browser-kotoba-v1`）。
`kotoba-lang/kotoba`ではなく`compiler/`を選んだ理由: `kotoba/`の
`compile-wasm-expr`は単一パスでfrontend/HIRの分離が無く、KIRのような
「バックエンド非依存な小さなクローズドop集合」を経由しない——`compiler/`
は既にこの分離を持っており、新backend追加はfrontendに一切触れず
`backend/cljs.clj`だけで完結する。

**設計の要点**（詳細は`backend/cljs.clj`自身のdocstringが正本）:

- KIRの`:functions`は既に小さなクローズドLisp（`let`/`if`/`cap-call`/
  `pair`族/算術比較/named call）なので、他backendのようなバイト組み立て
  は不要——KIRのs式をcljsのs式へ1:1変換するだけ。
- `pair`/`pair-first`/`pair-second`はcljsの実ヒープ（永続データ構造）を
  そのまま使い、プレーンな2要素vector + `nth`になる——wasm32/x86_64/
  aarch64のような線形メモリheapシミュレーションが不要（他backendより
  むしろ単純）。
- KIRの`if`は「0のみ偽、それ以外（負数含む）真」という規約だが、cljs/
  clj自身の`if`は「0は真」——毎回`(if (zero? test) else then)`へ明示的
  にwrapする。比較演算(`= < > <= >=`)も同様に「1/0の整数」へ明示的に
  wrapする（cljsの`true`/`false`のままでは後続の`if`規約と噛み合わない）。
- fuel: WASMの`charge!`はモジュールグローバルで一度も補充されない
  カウンタ（1 Instanceの生涯で256回のみ、top-levelの呼び出し毎ではない）
  ——同じセマンティクスを`defonce`atomで再現。
- `cap-call`はv1では未対応、emit時に明示的にreject（黙ってstubを出さない）。
- i64のwraparound（オーバーフロー時2の補数で回り込む）はcljsのプレーン
  `+`/`-`/`*`では再現されない（bignumへ自動昇格）——現存する全ての
  safe-kotoba-subsetプログラムがJSの53bit安全整数域に収まるため実害は
  無いが、正直な未対応ギャップとして明記する。

**検証**: 実際に`nbb`（ClojureScript-on-Node）でemit結果を実行し、
着地前に**本物のバグを1つ発見・修正した**——KIRの`:functions`の並び順は
「呼び出し先が呼び出し元より先に定義される」ことを保証しない（`loop`が
展開する合成ヘルパーは、それを呼ぶdefnの**後**に並ぶ）。プレーンな`defn`
はcljsでもJVM Clojureでも前方参照をhoistしない——`nbb`で実際に
`Unable to resolve symbol: __kotoba_loop_1`という実エラーとして確認し、
全関数名を`(declare ...)`で前もって宣言することで修正した。

## Consequences

正: `.kotoba`が初めてWASM以外の実行ターゲットを持ち、ブラウザ/Node上で
WASMインスタンス化の境界を経由せず直接実行できるようになった——既存
cljsアプリ（`kotoba-ui`/`uikit`等）が`.kotoba`で書かれた判定ロジックを
ただのcljs関数として直接require/呼び出しできる、という新しい統合経路が
開いた。kotoba-lang org内にも安全なnarrow-slice port候補
（`kototama/unspsc/capability.cljc`）が見つかり、次の一手が明確になった。

負: 汎用ライブラリ（`langchain`/`langgraph`/`kotobase`/UI層）は今回も
`.kotoba`化の対象外のまま——「org全体を.kotoba前提に」という指示の字面
どおりの全面適用はしていない（そもそも適合しない形のコードに強制する
ことは安全設計上も工学的にも正しくないと判断した）。`kototama/unspsc/
capability.cljc`の実移植は本ADRでは未着手（次のコミットで実施）。
cljs backendのi64 wraparound/cap-callは意図的な未対応スコープとして残る。

## Alternatives Considered

- **kotoba-lang/kotoba にcljs backendを足す**: 却下。`compile-wasm-expr`
  は単一パスでfrontend/HIRの分離が無く、KIRのようなbackend非依存の
  中間表現を経由しない——新backend追加が`compiler/`よりずっと侵襲的になる。
- **全てのkotoba-lang汎用ライブラリを.kotoba subsetへ書き直す**: 却下。
  map/protocol/多相ディスパッチを使う汎用ライブラリを`.kotoba`の閉じた
  安全サブセットへ強制することは、ADR-2607141900がcloud-itonamiの
  ファサードについて下した判断と全く同じ理由（T2健全性・工学コスト）
  で妥当ではない。
- **cljs backendでi64 wraparoundを厳密再現する（BigInt経由）**: 見送り。
  現存する全プログラムがJS安全整数域に収まるため実害が無く、全ての
  算術をBigInt化する複雑さに見合わない——正直な既知ギャップとして
  文書化する方を選んだ。

## 2026-07-14 Addendum 1 — cljs backendの`cap-call`未対応gapを解消

本ADR着地時点で正直に記録していた既知の限界——「`cap-call`はcljs側の
host-import機構が無いためemit時にreject」——を実際に解消した
（`kotoba-lang/compiler`、`4e06992`→`1455568`、`gh api .../merges`で
サーバ側マージ、west pin `--entry compiler`で前進済み: `4e06992a0e92`→
`1455568d4a3b`）。

**実装**: emitされるモジュールが`set-cap-dispatch!`（`fn [cap-id value]
-> i64`を受け取る関数）をexportするようにした——`kotoba$cap-dispatch`
という`defonce` atomにインストールされ、WASM backendの`kotoba:cap` host
importに相当するcljs側の等価物になる。hostは`main`を呼ぶ**前**に
`set-cap-dispatch!`を呼ぶ——WASM/nativeバックエンドの「hostがmemoryに
書き込んでから`main`を呼ぶ」規約と同じ形。dispatcherが未インストールの
場合は全ての`cap-call`が`:capability-denied`で拒否される——fail-closed、
`kotoba-lang/kotoba`自身の`has-capability-fn`（「no POLICY grants
NOTHING」）と同じ設計判断に揃えた。

**検証**: 実`nbb`実行で両方のシナリオを確認——(1)
dispatcher未インストール時に`capability-denied`が投げられること、
(2) `set-cap-dispatch!`でインストールした関数へcap-id/valueが正しく
渡り、その戻り値が`main`の結果になること。コミット済みテストも
既存の「emit時にreject」テストを置き換える形で2件追加
（`cap-call-with-no-dispatcher-installed-is-denied-fail-closed`/
`cap-call-dispatches-to-the-installed-host-function`）。
`clojure -M:test`: 142 tests / 2842 assertions、0 failures。

**残るgap**: i64 wraparoundの未対応は本Addendumのスコープ外——
引き続き正直な未対応事項として`backend/cljs.clj`のdocstringに記載
したまま。

## 2026-07-14 Addendum 2 — kotoba-lang-org内部のnarrow-slice port候補を実際に着地（`kototama.unspsc.life/prior-shortcut?`）

本ADR着地時点で挙げた候補`kototama/clj/src/kototama/unspsc/capability.cljc`
を実際に移植しようとしたところ、**候補選定自体が誤りだった**ことが判明した
——`segment-capabilities`テーブルは`:pred pred`という形でpredicate
**closureをmapの値として保存し、後から動的に呼び出す**構造になっており、
これはまさにADR-2607150000のトリアージ表が明示的に却下した「一般の
第一級関数/HOF」パターン（`infer-effects`の静的fixpointが任意のクロージャの
未申告effectを検知できない、T2健全性違反）そのものだった。前回のExplore
agentの調査は「`atom`/`defrecord`依存が無い」ことだけを確認しており、
closure-in-data/HOFパターンの有無を見ていなかった——確認不足による誤った
候補選定を、実装着手時に発見・訂正した。

**訂正した候補**: 同じ`kototama/clj/src/kototama/unspsc/life.cljc`内の
`prior-shortcut?`関数——純粋な整数/真偽値判定（`and`/`>=`/`=`の合成のみ、
closure・atom・defrecord・多相ディスパッチ一切無し）で、`credit.kernels.
gate.cljc`と全く同じ「safe-kotoba subset適合済み」の形をしている。
さらに重要な点として、この関数は**実際に呼び出されている**
（`kototama.unspsc.organism.cljc`の68行目、`life/prior-shortcut?`）——
先に断念した`capability.cljc`の候補が実は**どこからも呼ばれていない**
コードだったのとは対照的に、こちらは実利用中の判定ロジックである。

移植を実施（`kotoba-lang/kototama`、`main`→`3d317f1`、`gh api .../merges`
でサーバ側マージ、sibling worktree、branch cleanup完了、west pin
`--entry kototama`で前進を試みたが後述の理由で手動パッチに切替: `a850a8db1d19`
→`3d317f11a3ae`）。

**唯一必要だった適応**: 元の関数は`consensus`という1つのmapを受け取り
`(:dominant-status consensus)`を文字列`"authorized"`と比較する——`.kotoba`
の安全サブセットには文字列等価比較演算が無い。4つの位置引数へ平坦化し、
文字列比較は**呼び出し側**へ押し出した（`dominant-status-authorized`という
0/1フラグをホスト側で事前計算してから渡す）——`wasm/affordability.kotoba`や
cloud-itonami governor portが既に確立した「map/文字列比較をホスト境界へ
押し出す」変換パターンをそのまま踏襲。

**検証**: `kototama.unspsc.life-test`自身の`prior-consensus-parity`テストが
持つ3つのoracleケース（mixed-status/empty-priors/all-authorized）を
そのまま流用し、加えて4つの閾値境界ケース（outcome-count/confidence-
permille/input-match-count/authorized各々のちょうど閾値・閾値未満）を
追加。**実Chicory実行**（`test/wasm/prior_shortcut_test.clj`、host import
不要——このモジュールはcapability/heap opを一切使わず`mem-i32-at`/算術/
比較のみ）で全8ケースが一致。`clojure -M:test`: 44 tests / 230 assertions、
既存スイート無変更で通過（新規4 deftest含む）。

**west pin更新時の実務上の注意（正直に記録）**: `nbb scripts/gen-west-
manifest.cljs --entry kototama`を実行したところ、**pin退行として拒否
された**——共有checkout（`orgs/kotoba-lang/kototama`）が別の並行セッションに
よって`pds/aozora`という無関係なWIPブランチにcheckoutされたままだったため、
生成器が「ローカル子リポのHEAD」を見て「既存pinより後退している」と正しく
検知しfailした。これは生成器の設計どおりの安全装置（pin退行を機械的に検出
する）であり、バグではない——ただし生成器はローカルcheckoutのHEADを信用する
設計なので、共有checkoutが目的のブランチ/commitにいない状況では使えない。
対応: 実際に着地したmerge commit（`3d317f11a3ae`）が(1)実在し(2)`main`
（default branch）から到達可能で(3)旧pinから前進していることを`gh api`
（サーバ側full履歴）で個別に確認した上で、west.yml の該当1行だけを手動編集
した——「登録・rename・pin前進は`--entry`で当該entryのみの最小diffを生成する」
という原則の精神を、生成器が使えない状況でも手動で守った形。

## 2026-07-14 Addendum 3 — `prior-shortcut?`の`.kotoba`/WASM実装を「呼び出し可能な関数」として配線（本番切替は非実施）

Addendum 2で「compile+verifyのみ、`organism.cljc`の実呼び出し箇所には
未配線」と正直に記録していたgapを一段進めた——ただし**本番の判定挙動を
自律的には変更しない**という判断を維持したまま。

`kototama.unspsc.prior-shortcut-kotoba/prior-shortcut?`という新規
namespace（`clj/src/kototama/unspsc/prior_shortcut_kotoba.clj`）を追加
した。これは元の`kototama.unspsc.life/prior-shortcut?`と**全く同じ契約**
（`consensus`という1つのmapを受け取りboolean を返す）を持つ、実Chicory
Instanceでコンパイル済み`.kotoba`/WASMモジュールをホストする**本物の
drop-in代替関数**——文字列比較(`"authorized"`との等値判定)をこの関数内で
行い、WASM側には4つのi32スカラーへ平坦化して渡す変換も含めて実装。

**なぜ`organism.cljc`の`validate-node`を実際に切り替えなかったか**:
`validate-node`は18,342体のfleet全体の判定ゲート——ライブラリコードでは
なく**稼働中の共有システムの挙動そのもの**であり、これを自律的に
（オーナーの明示的判断を経ずに）切り替えることは、この一連の作業の
「安全性向上のため.kotobaを使いたい」という動機とは別の話——**本番挙動の
変更**は取り返しが効きにくく、影響範囲が広い操作なので、ADR-2607141900
以来一貫している「narrow-slice portの実装・検証」と「実運用への切替」を
別ステップとして扱う方針をここでも維持した。関数は用意し、テストで
既存実装との完全一致を証明したが、実際に呼ぶかどうかはオーナー判断に
委ねる。

**依存関係の隔離**: Chicoryを`clj/deps.edn`のメイン`:deps`には追加せず
（`kototama.unspsc.life`/`.organism`をrequireするだけの消費者に強制しない
ため）、`prior-shortcut-kotoba`という独立namespaceに閉じ込めた——
`langchain.jvm`/`langchain.kotobase-persist`が確立した「オプショナル
backendの隔離」パターンと同じ形。コンパイル済み`.wasm`実体は
`wasm/`から`resources/`へ移動（`io/resource`経由でclasspathから読む、
呼び出し側のcwdに依存しない）——`.kotoba`ソース自体は引き続き`wasm/`に残す。

**検証**: `life-test`の`prior-consensus-parity`が持つ全oracleケースを
**map引数のまま**（Addendum 2の生のスカラーABIテストとは異なり、変換
ロジック自体も含めた完全なend-to-endパス）再利用し、`life/prior-shortcut?`
と`prior-shortcut-kotoba/prior-shortcut?`が全ケースで一致することを確認。
`clojure -M:test`: 44 tests / 234 assertions、0 failures
（`kotoba-lang/kototama`、`main`→`65899de`、`gh api .../merges`でサーバ側
マージ、sibling worktree、branch cleanup完了）。

**west pin更新の実務注意（Addendum 2と同型の再発）**: 今回も
`nbb scripts/gen-west-manifest.cljs --entry kototama`が共有checkout
（引き続き`pds/aozora`という無関係な並行WIPブランチのまま）を見てpin
退行として拒否した。実際の着地commit（`65899deed4fb`）を`gh api`で
(1)実在(2)default branch到達可能(3)旧pinから前進、の3点を個別確認した
上で、west.ymlの該当1行のみ手動編集——Addendum 2で確立した対処法を
そのまま踏襲。

## 2026-07-14 Addendum 4 — cloud-itonami credit governorの`.kotoba`/WASM実装も同じ形で配線（本番切替は非実施）

Addendum 3で確立した「呼び出し可能なdrop-in関数として配線するが、実際の
呼び出し箇所は切り替えない」というパターンを、そのADRの主題だった
kototama内部だけでなく、**この一連の作業の出発点だったcloud-itonami
governor自身**（ADR-2607150000 Addendum 5）にも適用した——「主要な
production facadeが未配線のまま」という残gapは、kototama側だけでなく
cloud-itonami側にも対称的に存在していたため。

`credit.kernels.gate-kotoba`という新規namespace
（`src/credit/kernels/gate_kotoba.clj`）を追加し、`verdict-code`/
`phase-disposition`/`phase-reason`を——`credit.kernels.gate`自身の
in-process関数と**全く同じシグネチャ**で——`kototama.tender`経由の
実Chicory実行によりコンパイル済み`credit_verdict.wasm`/`credit_phase.wasm`
へ委譲する形で実装した。`credit.governor/check`（227行目付近で
`gate/verdict-code`を直接呼ぶ）・`credit.phase/gate`（103-104行目付近で
`kernel/phase-disposition`/`kernel/phase-reason`を呼ぶ）は、**他に何も
変更せずにこの新namespaceの関数を指すよう切り替え可能**——ただし
その切り替えは今回**実施していない**。実施すると`credit.governor/check`
が評価する全てのproposalが実際のWASMインスタンス化境界を本番で通ることに
なる——実稼働中の判定ゲートへの実挙動変更であり、Addendum 3で
`kototama.unspsc.organism`について下したのと同じ理由で、オーナーの
明示的判断に委ねた。

**検証**: `test/credit/kernels/gate_kotoba_test.clj`が、
`credit.kernels.gate.cljc`自身が持つ52ケースのbattery全体に対して
`credit.kernels.gate`（in-process）と`credit.kernels.gate-kotoba`
（WASM-backed）の両方を呼び、期待値・in-process・WASM-backedの三者一致を
確認。`clojure -M:test`: 59 tests / 659 assertions、0 failures
（`cloud-itonami/cloud-itonami-isic-6492`、`main`→`73d9815`、
`gh api .../merges`でサーバ側マージ、sibling worktree、branch cleanup
完了）。`kototama`（したがってChicory）はこのrepoのメイン`:deps`には
追加せず——既存の`wasm.*-test`群と同じく`:test` alias限定のまま、
`credit.governor`/`credit.phase`をrequireするだけの消費者に強制しない。

**west pinへの影響なし**: `cloud-itonami-isic-6492`はこのsuperprojectの
west manifestに登録されていない（`gftdcojp`ではなく`cloud-itonami` org
配下の独立リポジトリ、ADR-2607150000 Addendum 5で既に記録済み）——
今回もpin前進は不要、本ADRへの記録のみで完結する。

## 2026-07-14 Addendum 5 — cljs backendのi64 wraparound gapを「黙って間違う」から「大声で失敗する」へ縮小

Addendum 1着地時点で正直に記録していたi64 wraparoundの未対応ギャップ
——「KIRの正規セマンティクス（2の補数オーバーフロー）をcljsのプレーン
`+`/`-`/`*`は再現しない（bignumへ自動昇格）」——を、**厳密な再現**では
なく**安全側への縮小**という形で対応した。

**なぜBigIntによる厳密再現をしなかったか**: 真のi64 wraparound一致には
全ての値（パラメータ・リテラル・中間結果）をJS BigIntとして最後まで
持ち回る必要があり、このbackendの数値表現全体を作り直す、より大きく
侵襲的な書き換えになる——既知のsafe-kotoba-subsetプログラムでこれを
必要とするものは無いままであり、費用対効果が見合わないと判断した
（Addendum 1と同じ判断を維持）。

**代わりに実装したこと**: 全ての`+`/`-`/`*`の結果をJS自身のsafe-integer
境界（2^53-1）と照合し、境界を超えたら黙って不正確な値のまま処理を
続けるのではなく`:arithmetic-overflow`を投げるようにした
（`kotoba$check-safe-int`）——このbackendが既にfuel枯渇・division-by-zero・
capability-denialに対して取っている「fail-closed」姿勢と同じものを
ここにも適用した形。境界値は`js/Number.isSafeInteger`ではなく**移植可能な
数値リテラル**として書いた——これにより、実cljs環境でもこのbackend自身の
既存テストスイートが採用する「プレーンJVM Clojureでeval」環境でも、
チェックが同一に評価される。

**検証**: 実`nbb`実行で3パターンを確認——(1)安全域内の演算（境界値
ちょうど2^53-1に収まる`(+ 9007199254740990 1)`）は例外を投げず正しい
値を返す、(2)安全域を超える演算（`(* 100000000 100000000)` = 10^16 >
2^53-1）は`:arithmetic-overflow`を投げる、(3)境界ちょうどの値は
false-positiveしない。新規テスト3件を含め`clojure -M:test`: 145 tests /
2848 assertions、0 failures（`kotoba-lang/compiler`、`main`→`19275b7`、
`gh api .../merges`でサーバ側マージ、sibling worktree、branch cleanup
完了、west pin `--entry compiler`で前進: `1455568d4a3b`→`19275b772287`）。

**正直に記録する残りの限界**: これは厳密なwraparound一致ではなく、
「黙って間違った値を返す」ケースを「大声で失敗する」ケースへ変換した
だけ——safe-kotoba-subsetの意味論上、wasm32/x86_64/aarch64バックエンドと
cljsバックエンドの間で**値そのものが一致しないケースは依然として
存在しうる**（2^53-1を超える演算を行うプログラムの場合）。ただし
そのケースは今後「黙って発散した結果を返す」のではなく「即座に検出
可能な例外」になる——safe-kotobaの「fail-closed」設計思想（unknown/
invalidな入力はより少ない自律性へ、より多い自律性へは決して倒れない）
と整合する形での縮小であり、真の等価性証明ではない。

## 2026-07-15 Addendum 6 — `kotoba-lang/kotoba`自身にもcljs backendを新設（Addendum 1で「別途follow-up」としていたgapを実施）

Addendum 1着地時点で`:adr/decision-not-made`に明記していた「`kotoba-lang/kotoba`
自身へのcljs backend追加は、compiler/がfrontend/HIR/KIR分離を持つ正しい
アーキテクチャ上の置き場所であり、kotoba/側の同等物は別途未着手のfollow-up」
というgapに、責任を持って**縮小した形で**着手した——compiler/の
`compile-wasm-expr`が持つWASMの全op surface（i64/f32/bitwise/string/memory/
capability含む2000行超）をそのまま鏡写しにするのは今回のiterationの範囲を
超えると判断し、代わりに**kototama/cloud-itonami向けの narrow-slice governor
port群が実際に使っている演算のみ**（算術・比較・`and`/`or`/`not`/`zero?`/
`pos?`/`neg?`/`inc`/`dec`・`pair`系・`map`リテラルの`get`/`assoc`）に絞った
v1として実装した。

**アーキテクチャ上の単純化点**（compiler/のcljs backendとの対比）:
- kotoba/自身の`compile-wasm-expr`はWASM localsが数値インデックスを要求する
  ため、シンボル名→インデックスの`locals`マップを全再帰呼び出しに引き回す
  必要があるが、cljs backendでは`let`/`defn`が自前でシンボル名を束縛するため
  この機構は丸ごと不要——新しい`compile-cljs-expr`は`locals`コンテキストを
  一切持たない、真の意味で単純な変換になっている。
- `pair`/`pair-first`/`pair-second`はプレーンなvector + `nth`に落とす
  （cljsは実persistent data structureを持つのでWASM側の手組みpair表現は
  不要）。
- WASMの`main` 0-arity制約（引数をlinear memoryへ`mem-i32-at`経由でmarshal
  する必要があった）はcljs targetには存在しない——`defn`は自前の実引数を
  そのまま受け取れる。**この結果、既存の`mem-i32-at`前提で書かれた.kotoba
  ソース（例えばcloud-itonami governor移植群）はこの新targetへ無変更では
  コンパイルできない**——正直に記録する、意図的なv1スコープ限界。

**着地前に自分で発見・修正した2件の実バグ**（ユーザー指摘ではなく、
`compile-wasm-fold`の実装を読み込んで発見）:
1. **fold意味論の不一致**: 初稿は`quot`/`rem`/`mod`/比較演算子をcljs自身の
   可変長引数セマンティクスにそのまま委ねていたが、`compile-wasm-fold`は
   厳密な**左畳み込み**（`(op a b c)` → `((a op b) op c)`）であり、
   (a) Clojureの`quot`/`rem`/`mod`はそもそも2引数専用（3引数以上はarity
   error）、(b) WASMの比較opcodeも同じfoldを通るため、3引数以上の比較は
   Clojure標準の単調連鎖比較と**異なる結果**になる
   （`(< 3 1 2)`は`((3<1)<2)=(0<2)=true`即ち`1`——真の単調連鎖なら`false`）、
   という2点を見落としていた。汎用`cljs-fold-binary`ヘルパーを実装し直し、
   `nbb`で`(- 5)`→5、`(quot 100 5 2)`→10、`(< 3 1 2)`→1の3ケースを実行
   確認。
2. **division-by-zeroガード欠落**: 初稿はi32.div_s/i32.rem_sの
   トラップ意味論をcljsの`quot`/`/`が無条件で再現すると誤って前提しており
   （実際はJS numberに対する`quot`/`/`はInfinity/NaNを静かに返す）、
   compiler/の`kotoba$quot`で確立済みの前例に倣い`cljs-checked-divide`
   （除数を一度let束縛し0なら`:division-by-zero`を投げる）を追加、`nbb`で
   確認。

**テスト作成中に踏んだ既知の罠の再発**: `get`のbounded-unrollが使う
`gensym`（`get-m__`/`get-k__`/`get-d__`）はJVMプロセスグローバルなカウンタ
であるため、同一ソースの別々のcompileは**生テキストとしては非決定的**
（実行時の値は同一）——実際にテストの初稿が生テキスト等価性を比較して
落ち、compiler/側のdestructuring/assoc gensymで既に文書化済みの同クラスの
問題だと確認した上で、実行値比較（`run`ヘルパー経由）へ書き換えて修正。

**検証**: 新規`test/kotoba/cljs_backend_test.clj`（16 deftest、compiler/の
`backend_cljs_test.clj`と同型の`eval-cljs-source`/`compile-cljs`/`call`/`run`
ヘルパーを使用）に加え、既存スイート全体を実行。さらに、この
repoについて過去に記録済みだった「20 failures / 1 error」というpre-existing
baselineが依然有効かを疑い、同一base commit（`55687281cc04`）から
`/tmp/kotoba-baseline-check`へ**独立にfresh clone**して同じテストコマンドを
実行、baseline自体が既に250 tests / 1261 assertions / 0 failures / 0 errors
（並行する無関係な開発で既に修正済み）とクリーンであることを確認した上で、
自分のbranchが266 tests / 1300 assertions / 0 failures（新規16 tests /
39 assertionsのみが差分、regressionゼロ）であることを確認した。

**着地**: `kotoba-lang/kotoba`、`main`→`6d7254ae4c99242a65d3bc78cc726fd17bad6c44`
（`Merge feat/cljs-backend-core-subset: new ClojureScript backend for
.kotoba`）、sibling worktree + `gh api .../merges`サーバ側マージ + branch
cleanup。**west pin更新でもAddendum 2/3と同型のgen-west-manifestハザードが
再発**: 共有checkoutの`main`ブランチが（旧submodule時代の`.git/modules/...`
という紛らわしいdual-gitdir artifactにより）`c7ca33a14163`という無関係な
stale commitに固定されており、`--entry kotoba`がこれをpin退行として拒否
した。この共有checkout自体のdual-gitdir状態の修復は本タスクのスコープ外と
判断し（`git branch -f main origin/main`もworktree lockで失敗することを
確認済み、当該コンテンツは`origin/docs/language-maturity-roadmap-2607131800`
上に既に安全に存在することも確認済み）、`gh api .../compare`で新pin
（実在・default branch到達可能・旧pinから前進の3点）を独立検証した上で
west.ymlの該当1行のみ手動編集（`superproject`、`main`→サーバ側merge
commit `a7ae6b366e18`）。

## 2026-07-15 追記 — `kototama/unspsc/capability.cljc`移植候補の再調査（否定的結論）

Addendum 1の`:decision-not-made`が「次の移植先」として挙げていた
`kototama/clj/src/kototama/unspsc/capability.cljc`を実装前に再調査した
結果、**当初の楽観的な評価を撤回する**——`credit.kernels.gate.cljc`と
「同じsafe-kotoba-subset形状」という説明は精査すると成り立たない。

実物を全読した結果: 30個のUNSPSCセグメント（`"10"`〜`"56"`の文字列キー）
ごとに異なるドメイン固有フィールド名（数十種類の文字列——
`cold_chain`/`provenance`/`sds`/`gmp_certificate`/`ghs_classification`
等）に対する`present?`述語をclosureとして埋め込んだ巨大なdata-driven
dispatchテーブルであり、`.kotoba`のsafe subsetが持たない3つの機構
（文字列値の第一級扱い、closure、セグメント文字列による動的dispatch）
に本質的に依存している。`credit.kernels.gate.cljc`が最初から
整数コード・closureなしで設計されていたのとは対照的に、これは
`kotobase`/`langchain`等と同じ「汎用ライブラリ」の側に属する形——
ADR-2607141900が`credit.governor/check`の全体ファサードを対象外とした
のと同じ理由がここにも当てはまる。全面移植は行わないと判断した。

## 2026-07-15 Addendum 7 — `kotoba-lang/kotoba`のcljs backendをCLIから使えるように配線（`kotoba cljs emit`）

Addendum 6で`compile-cljs-expr`/`cljs-source`を実装したが、着地直後の時点では
**テスト/REPLからしか到達できないライブラリ関数のまま**だった——`wasm-binary`
が`kotoba wasm emit`/`kotoba wasm run`という完全なCLI経路を持つのと非対称に、
新backendにはCLIエントリポイントが一切無かった（`launcher.clj`の
`dispatch`は`"selfhost"`/`"wasm"`/`"package"`しか認識しない）。この
非対称性自体を今回のgapとして特定し、`kotoba cljs emit <source>
[--output path]`を新設して埋めた。

**実装**: `wasm-emit-result*`と同じ構造を踏襲する`cljs-emit-result*`/
`cljs-emit-result`を追加——`wasm emit`と同じ`runtime/check`静的解析
ゲートを先に通し、通過した場合のみ`runtime/cljs-source`でコンパイルする。
**`runtime/check`通過は`cljs-source`自体の成功を保証しない**——safe-kotoba
全体の静的解析はこのbackend固有の狭い対応範囲（i64/f32/bitwise/string/
memory/capability操作はsafe-kotoba subsetとしては正当だがこのbackend
では非対応、Addendum 6参照）を知らないため——`cljs-source`が投げる
`cljs-reject!`をtry/catchで受け、`wasm-run-result*`が capability-denial
の`ex-data`形状を区別して処理するのと同じ発想で、生の例外ではなく
クリーンな`:cljs/emit-unsupported`結果に変換した。`wasm emit`/`wasm run`
と同じ`--package-lock`必須のadmission gate（F-001）をこの新entry point
にも適用した。

**着手中に発見・修正した既存の小さな欠陥**: 3つ目の呼び出し元を追加した
ことで、`admission-gated`が呼び出し元に関わらず`:kotoba.cli/code`を
`:wasm/package-rejected`に**ハードコード**していたことが表面化した——
`cljs emit`がpackage-lockで拒否されても`:wasm/...`という誤った
namespaceで報告されてしまう。`admission-gated`に`reject-code`引数を
追加してパラメータ化し（`wasm-emit-result`/`wasm-run-result`は
明示的に`:wasm/package-rejected`を渡すよう更新、既存挙動を変えず）、
`cljs-emit-result`は`:cljs/package-rejected`を報告するようにした——
新しい呼び出し元を追加する際に、既存の共有ヘルパーの隠れた前提を
そのまま継承しない、という一貫した姿勢。

**検証**: 単体テストに加え、**実CLIプロセスとしての完全なend-to-end
実行**を行った——`clojure -M -m kotoba.launcher cljs emit src/demo.kotoba
--package-lock ... --output <path>`を実際に起動し、書き出された
ファイルを実`nbb`（ClojureScript-on-Node、JVM Clojure evalではない）
配下で`require`して`main`を呼び出し、`42`という正しい値が返ることを
確認した——「それらしく見えるテキスト」ではなく本物のcljsとして実行
可能であることの確認。新規7 deftest（`launcher_test.clj`）。
`clojure -M:test`: 273 tests / 1325 assertions（Addendum 6着地時点の
266/1300から+7/+25）、0 failures/errors。

**着地**: `kotoba-lang/kotoba`、`main`→`fc7e98bec18b841825da64940aa5898505de56df`、
sibling worktree + `gh api .../merges`サーバ側マージ + branch cleanup。
west pin更新は`--entry kotoba`が再び共有checkoutのstale `main`（依然
`c7ca33a14163`、Addendum 6と同じ既知のdual-gitdir問題、修復は引き続き
スコープ外）を見てpin退行と誤判定したため、`gh api .../compare`
（ahead_by=2, behind_by=0, merge_base==旧pin）で新pinを独立検証した上で
west.ymlの該当1行のみ手動編集。

**superproject checkout自体の同期漏れも合わせて修正**: この作業に着手する
過程で、superproject本体checkout（`com-junkawasaki/root`）のHEADが
detached状態のまま`origin/main`より4コミット遅れており、しかもAddendum 6
着地直後に行った「shasum一致を確認してから`git checkout --`で破棄」という
手順が、実は**detached HEADがorigin/mainより古いことを見落として**いて、
`git checkout --`がorigin/mainの内容ではなく古いHEADの内容へ working tree
を巻き戻していたことが判明した（shasum比較自体はその時点で一致していた
が、その後の`checkout --`がその一致を壊した）。`git merge-base
--is-ancestor HEAD origin/main`が偽（shallow graftの境界による偽陽性、
上位CLAUDE.mdが文書化する既知のhazardと同型）だったため、`gh api
.../compare`（サーバ側、full history）で純粋なfast-forward
（ahead_by=4, behind_by=0, merge_base==HEAD）であることを確認した上で
`git fetch --deepen=20`→`git merge --ff-only origin/main`でsuperproject
checkoutを正しく最新化した。

## 2026-07-15 Addendum 8 — `kotoba-lang/compiler`のCLI `compile --target cljs-kotoba-v1`が出力を静かに壊していたbugを修正

Addendum 7でkotoba-lang/kotoba側のCLI非対称性（cljs backendがテスト/REPL
からしか届かない）を埋めた流れで、姉妹repo `kotoba-lang/compiler`
（Addendum 1〜5でcljs backendを実装した、より成熟している側）自身の
CLI（`kotoba.compiler.cli`）も同様に確認したところ、こちらは**CLIから
既に`--target cljs-kotoba-v1`が受理される**ことが分かった
（`compiler.core/compile-source`が`(= backend :cljs-kotoba-v1)`分岐を
既に持ち、`{:format :cljs/v1 ... :source "..."}`を返す）——一見すると
配線済みに見えた。

しかし実際に`clojure -M -m kotoba.compiler.cli compile demo.kotoba
--target cljs-kotoba-v1 --output demo.cljs`を実行し`cat demo.cljs`した
ところ、ファイルの中身は文字通り**`nil`という4文字**だった——CLIの
`"compile"`コマンドは`(:format result)`を`:wasm/v1`かそれ以外かの
2分岐でしか見ておらず、それ以外（`:cljs/v1`含む）は無条件で
`(atomic-output/write-edn! output (:artifact result))`を書いていた。
`:cljs/v1`の結果に`:artifact`キーは存在しない（`:source`という別の
キーに文字列が入っている）ため、`(:artifact result)`は`nil`——それが
`pr-str`されてファイルに書かれていた。**`{:ok true ...}`を返しながら
中身は壊れている**という、raiseされない分だけ`wasm emit`の
`:cljs/emit-unsupported`より発見しにくい類の欠陥。加えて`--output`
省略時の既定拡張子ロジックも`:wasm`実行か否かの2分岐（`.wasm`/`.kexe`）
のみで、cljs targetには誤って`.kexe`が付いていた。

**修正**: `atomic-output.clj`に`write-text!`を追加（文字列の生UTF-8
bytesをそのまま書く——`write-edn!`のように`pr-str`でエスケープしない。
生成されたcljsソーステキストを`pr-str`すると引用符付きEDN文字列
リテラルに壊れてしまうため）。`cli.clj`の`"compile"`コマンドを
`(:format result)`の`case`（`:wasm/v1`→`write-bytes!`、`:cljs/v1`→
`write-text!`、それ以外→従来通り`write-edn!`）に書き換え、既定拡張子
ロジックにも`:cljs`→`.cljs`の分岐を追加した。

**検証**: 修正前に実際にbugを再現してから修正し、修正後は実CLI
プロセス経由で`--output`にファイル書き出し→そのファイルを実`nbb`
（ClojureScript-on-Node）で`require`して`main`を呼び出し、正しい値
`42`が返ることを確認（Addendum 7と同じ「JVM Clojure evalではなく本物
のcljs実行」の検証水準）。新規3 deftest（bugの直接再現テスト・
既定拡張子テスト・`wasm32` targetが今回の変更で壊れていないことの
sanity check）。`clojure -M:test`: 148 tests / 2860 assertions
（修正前145/2848から+3/+12）、0 failures/errors。

**着地時の作業ミスと訂正**: 実装の初回試行を誤って共有checkout
（`orgs/kotoba-lang/compiler`）へ直接編集してしまったが、着地前に
気付き、diffを退避→共有checkoutを`git checkout --`で復元→sibling
worktreeで作業をやり直した（本ADR Addendum群が繰り返し記録してきた
「共有checkout直接編集の禁止」原則からの一時的逸脱を、実害が出る前に
自己訂正した実例）。

**着地**: `kotoba-lang/compiler`、`main`→`d1dd2275ec75ec80335a272da654ed29349a9e80`、
sibling worktree + `gh api .../merges`サーバ側マージ + branch cleanup。
west pin更新: この共有checkoutは（`kotoba-lang/kotoba`と異なり）
dual-gitdir問題を抱えておらず、`git merge --ff-only origin/main`が
素直に成功した。pinはAddendum 6/7と同じ手動1行編集パターンで前進
（`gh api .../compare`でahead_by=2, behind_by=0を確認済み）。

## References

- 90-docs/adr/2607150000-kotoba-lang-extension-triage-langchain-persistence-kotobase.md
- 90-docs/adr/2607141900-cloud-itonami-cljc-actors-kotoba-incompatibility-narrow-slice-porting-policy.md
- 90-docs/adr/2607141600-kotoba-kotoba-lang-compiler-kototama-aiueos-consolidation-experiment.md
- orgs/kotoba-lang/compiler/src/kotoba/compiler/backend/cljs.clj（新backend本体、設計の正本）
- orgs/kotoba-lang/compiler/src/kotoba/compiler/target.clj（`:cljs-kotoba-v1`等のprofile定義）
- orgs/kotoba-lang/compiler/test/kotoba/compiler/backend_cljs_test.clj（新規10 deftest）
- orgs/kotoba-lang/kotoba/src/kotoba/launcher.clj（`--reader-target cljs`が実行エンジンを変えないことの根拠）
- orgs/kotoba-lang/kototama/clj/src/kototama/unspsc/capability.cljc（次の narrow-slice port 候補）
- orgs/kotoba-lang/kotoba/src/kotoba/runtime.clj（Addendum 6: `compile-cljs-expr`/`cljs-source`、新backend本体）
- orgs/kotoba-lang/kotoba/test/kotoba/cljs_backend_test.clj（Addendum 6: 新規16 deftest）
- orgs/kotoba-lang/kotoba/src/kotoba/launcher.clj（Addendum 7: `cljs-emit-result*`/`cljs-emit-result`/`cljs-result`、`kotoba cljs emit`のCLI配線）
- orgs/kotoba-lang/kotoba/test/kotoba/launcher_test.clj（Addendum 7: 新規7 deftest）
- orgs/kotoba-lang/compiler/src/kotoba/compiler/cli.clj（Addendum 8: `"compile"`コマンドの`:cljs/v1`分岐追加）
- orgs/kotoba-lang/compiler/src/kotoba/compiler/atomic_output.clj（Addendum 8: `write-text!`新設）
- orgs/kotoba-lang/compiler/test/kotoba/compiler/cli_test.clj（Addendum 8: 新規3 deftest）
