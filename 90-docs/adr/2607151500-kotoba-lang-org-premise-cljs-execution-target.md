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

## References

- 90-docs/adr/2607150000-kotoba-lang-extension-triage-langchain-persistence-kotobase.md
- 90-docs/adr/2607141900-cloud-itonami-cljc-actors-kotoba-incompatibility-narrow-slice-porting-policy.md
- 90-docs/adr/2607141600-kotoba-kotoba-lang-compiler-kototama-aiueos-consolidation-experiment.md
- orgs/kotoba-lang/compiler/src/kotoba/compiler/backend/cljs.clj（新backend本体、設計の正本）
- orgs/kotoba-lang/compiler/src/kotoba/compiler/target.clj（`:cljs-kotoba-v1`等のprofile定義）
- orgs/kotoba-lang/compiler/test/kotoba/compiler/backend_cljs_test.clj（新規10 deftest）
- orgs/kotoba-lang/kotoba/src/kotoba/launcher.clj（`--reader-target cljs`が実行エンジンを変えないことの根拠）
- orgs/kotoba-lang/kototama/clj/src/kototama/unspsc/capability.cljc（次の narrow-slice port 候補）
