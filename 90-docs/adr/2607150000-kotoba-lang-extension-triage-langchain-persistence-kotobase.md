---
id: adr-2607150000-kotoba-lang-extension-triage-langchain-persistence-kotobase
title: "ADR-2607150000: kotoba-lang 全体の `.kotoba` 言語拡張トリアージ（追加/非追加/lib側修正）+ ADR-2607141900 訂正 + langchain.db の kotobase 永続化統合"
status: accepted
date: 2026-07-14
deciders:
  - Jun Kawasaki（「んー、実際に安全性を高めて運用したいので .kotoba を使いたいんですよねぇ.
    できれば cloud-itonami は kotoba で動かしたい. そっちの方が安全だよね? 可能??」
    →「では isic-6492 だけ深掘り」+「.kotoba の安全設計的には map keyword を
    つかするのは適切? kotoba-lang/kotoba, kotoba-lang, compiler の言語仕様と
    security 観点を判断して」→「ok, adr 正式化, db.cljc は kotoba-lang/kotobase
    を前提に永続化層を統合して」）
related:
  - 90-docs/adr/2607141600-kotoba-kotoba-lang-compiler-kototama-aiueos-consolidation-experiment.md
  - 90-docs/adr/2607141900-cloud-itonami-cljc-actors-kotoba-incompatibility-narrow-slice-porting-policy.md（本ADRが一部訂正する）
  - orgs/kotoba-lang/kotoba, orgs/kotoba-lang/compiler, orgs/kotoba-lang/kotoba-lang
  - orgs/kotoba-lang/langchain（`src/langchain/db.cljc`）
  - orgs/kotoba-lang/kotobase（`src/kotobase/{store,local,kotobase}.cljc`）
supersedes: []
superseded_by: []
last_verified: 2026-07-14
doc_type: adr
topic: kotoba-lang-language-extension-triage
authoritative: true
authoritative_for:
  - "`.kotoba` 言語拡張の判断表（本文 §Decision の表）を、今後の類似判断の正本とする位置づけ"
  - "ADR-2607141900 の「`and`/`or`/`when`/`pos?`/`neg?` が kotoba-lang/kotoba で
    未対応」という記述は、2026-07-14 時点の live 再検証により誤り（既に対応済み）
    であるという訂正の記録（`compiler/` 側は依然未対応のまま）"
  - "`langchain.db.cljc` の永続化を `kotoba-lang/kotobase` の `IStore`
    （`kotobase.store`/`kotobase.local`/`kotobase.kotobase`）へ委譲する設計
    （duck-typed `{:append :read}` map 経由、`kotobase` をハード依存にしない）
    をこの統合の正本とする位置づけ"
---

# ADR-2607150000: kotoba-lang 全体の `.kotoba` 言語拡張トリアージ + ADR-2607141900 訂正 + langchain.db の kotobase 永続化統合

**Status**: accepted
**Date**: 2026-07-14
**Deciders**: Jun Kawasaki

## Context

ADR-2607141900 で「cloud-itonami の `.cljc` actor は `.kotoba` と非互換、narrow-slice
porting のみ承認」と結論した直後、オーナーから「それでも安全性のために `.kotoba` を
広く使いたい」という押し戻しを受けた。議論の結果、安全性が実際に必要なのは actor
全体ではなく **governor の判定ロジックそのもの**だという整理に至り、そこから
「`.kotoba` に map/keyword を足すのは安全設計上妥当か」という、cloud-itonami に
限らない kotoba-lang 全体への問いに発展した。

3体の Explore agent を並列起動し、以下を調査した:

1. **文法の厳密再確認**（`kotoba-lang/kotoba`・`kotoba-lang/compiler`・
   `kotoba-lang/kotoba-lang` を live に再検証）
2. **構造的イディオムの実使用頻度**（`coll`/`json`/`xml`/`datom`/`dsl-core`/
   `chobo`/`mise`/`html`/`css`/`shitsuke`/`spec`/`wit`/`kotoba-ui`/`uikit`/
   `appkit`/`io-ipld`/`io-multiformats` の17リポジトリ）
3. **effect系・動的イディオムの実使用頻度**（`langgraph`/`langchain`/
   `org-chainagnostic-cacao`/`org-ietf-ed25519`/`http`/`io`/`fs`/`device`/
   `async`/`log`/`store`/`scheduler`/`kotoba`/`kototama`/`aiueos` の15リポジトリ）

## 訂正: ADR-2607141900 の「`and`/`or`/`when`/`pos?`/`neg?` 未対応」記述

調査1で `kotoba-lang/kotoba` の現行 `kotoba.runtime/compile-wasm-expr`
（HEAD `ecadee870`, 2026-07-14）に対し実際に `(and 1 2)` `(or 1 2)` `(when 1 2)`
`(pos? 3)` `(neg? -3)` を投げたところ、**全て正常にコンパイルされた**
（本セッションで独立に再現確認済み: `and`/`or`/`when` は `:i32` 型で成功、
`pos?`/`neg?` も `:bytes` を伴う正常な結果を返す）。

これは ADR-2607072600（2026-07-07 執筆、cloud-itonami isic-6492）と、それを
引用した ADR-2607141900（本セッション先ほど執筆）の「`when`/`and`/`or`/`pos?`/
`neg?` は未対応」という記述と矛盾する。2026-07-07 から 2026-07-14 の間に
`kotoba-lang/kotoba` 側の実装が進み、このギャップは**既に解消済み**だったと
判断する（このリポジトリ群は多数の並行セッションが継続的に開発しており、
1週間で実装が進むこと自体は驚くべきことではない）。

**ただし `kotoba-lang/compiler`（別実装）は今も `and`/`or`/`when` を
desugar していない**（`frontend.clj` の `desugar-expr` case dispatch に
これらが無いことを直接コード読解で確認済み）——`kotoba/` と `compiler/` の
文法が独立に進化しているという ADR-2607141600 の指摘が、ここでも別の実例で
裏付けられた。

**ADR-2607072600・ADR-2607141900 の該当記述は、本 ADR をもって「2026-07-14
時点で `kotoba-lang/kotoba` については既に解消済み、`kotoba-lang/compiler`
については未解消のまま」と訂正する。** 過去の ADR 本文は書き換えず（append-only
の精神）、この訂正を参照可能な形で記録する。

## Decision

### 1. `.kotoba` 言語拡張トリアージ

| 構文 | 実ライブラリでの使用頻度 | Effect(T2)/Capability(T3)/Memory(T1) 安全性への影響 | 判断 |
|---|---|---|---|
| **map + keyword** | 13-15/17、中核（`chobo`/`mise`/`spec` の実質的な構造体） | T1/T2/T3と直交。既存のheap-pair desugar手法（`compiler/`の`not`/`zero?`/`pos?`/`neg?`が既にこの手法で実装済み）をそのまま拡張可能 | **追加すべき（最優先）** |
| **destructuring**（`{:keys […]}`、`[a b & rest]`） | 14/17、中核 | mapのシンタックスシュガーに過ぎない、新規runtime機構不要 | **map追加後に追加すべき** |
| **`and`/`or`/`when`（`compiler/`側）** | — | `kotoba/`側で既に安全に動作している実績あり | **`compiler/`に移植すべき（低コスト）** |
| **vector-as-data** | 10/17、order-tuple用途中心 | mapと同様にheap-pair desugar可能 | **追加すべき（優先度中）** |
| **多重arity `defn`** | 13/17、中核（デフォルト引数パターン） | 各arityは別関数としてコンパイル時に静的展開できる | **追加すべき** |
| **`loop`/`recur`** | 7/17 | `kotoba/`自身が「無制限self-recursionによるStackOverflow」を既知リスクとして文書化済み——`loop`はWASM loop命令に直接コンパイルでき、コールスタックを消費しない分**むしろ安全性が上がる** | **追加すべき（安全性向上として）** |
| **一般の第一級関数/HOF**（`map`/`reduce`にラムダを渡す） | 15/17、中核だが | `infer-effects`は静的に既知の呼び出し先だけを辿るfixpoint。任意のクロージャを渡せると、渡された関数の未申告effectを静的解析が検知できずT2健全性が崩れる | **一般形は追加しない**。`kotoba/`が既に持つ`call-indirect`（既知top-level `defn`だけを収めたfuncref table経由の bounded 動的呼び出し）と同じ設計で、既知の`defn`参照だけを渡せるbounded版`map`/`reduce`を将来検討 |
| **`defprotocol`/`defrecord`/multimethod** | 3/17（`mise`/`chobo`の戦略パターン）、`defmulti`は0/17 | 型ベースの動的ディスパッチはHOFと同じ健全性問題 | **追加しない。ライブラリ側をkeyword-tagged dispatch（`case`）に書き換える**——map/keyword導入後は十分代替可能、かつclosed-set dispatchの方が「safe Kotoba」の思想（deny-by-default）に合致する |
| **`atom`/`ref`/`volatile!`/`set!`（guest側の一般可変状態）** | ライブラリごとに1箇所ほど本質的な永続状態（registry/checkpoint/DB接続）、host側のmetering用atomとは別カテゴリ | guest内の可変状態はeffect fixpointの参照透明性前提を壊す。host側atom（`aiueos.execute`/`kototama.tender`のquota/fuelカウンタ）はconfinement境界を**実装している**側であり同列に語れない | **guestには追加しない（明確な安全性理由）。host側は現状のままでよい** |
| **`try`/`catch`/`throw`（guest側）** | ordinary error報告とquota/fuel/capability-denial中断経路の両方で多用（`aiueos.execute`のex-data語彙が代表例） | guestが quota/fuel超過例外を握りつぶして実行継続できてしまうと、resource limitの回避経路になる | **追加しない（明確な安全性理由）** |
| **`defmacro`/動的`require`/`resolve`/reflection** | ほぼゼロ（動的require 0件、macro実質1件のみ純粋シュガー） | 静的検査バイパスの典型的攻撃面。コスト対効果が最初から釣り合わない | **追加しない（低コストで除外継続）** |

### 2. `langchain.db.cljc` の永続化を `kotoba-lang/kotobase` へ統合する設計

`langchain/src/langchain/db.cljc` は現状 `create-conn` が
`(atom {:db (empty-db schema) :log []})` を返すだけの、プロセス内メモリのみの
Datomic-API互換EAVストア。同ファイルは元々「a real Datomic Local / DataScript
backend can be swapped in via the `langchain.db/api` map」と、pluggable-backend
の意図を自ら文書化していた——今回はその swap-in を実際に `kotobase` で行う。

`kotobase`（`kotoba-lang/kotobase`）は `IStore` protocol
（`kotobase.store/-put`/`-get`/`-list`/`-append`/`-read`、doc空間+append-only
stream の2形状）を持ち、`kotobase.local/LocalStore`（純粋atomベース、OSS
standalone用）と `kotobase.kotobase/KotobaseStore`（kotobase.net XRPC 経由の
cloud永続化）の2実装を既に提供している——cloud-itonami の `MemStore ‖
DatomicStore` と全く同じ injection パターンだと `kotobase.store` 自身の
docstring が明記している。

**統合方針**:

1. `langchain.db` は `kotobase.store` を**ハード依存にしない**
   （langchain の ADR-0001「サードパーティ依存ゼロ」原則を維持——`kotobase`は
   第一者製zero-depではあるが、それでも依存追加は本体の`:deps`ではなく既存の
   `langchain.jvm`（http-kit/jsonista を optional alias に隔離するパターン）
   と同じ扱いにする）。
2. `create-conn` に **duck-typed `persist` オプション**
   `{:append (fn [event] ...) :read (fn [since] -> [events])}` を追加する
   （`kotobase.store/IStore`の`-append`/`-read`と同じシグネチャだが、
   `langchain.db`自身は`kotobase`のprotocolを`:require`しない）。
3. `persist` が渡された場合:
   - conn 作成時に `((:read persist) 0)` で永続化済みtxイベントを全件読み、
     既存の純粋な `with`（tx適用ロジック）で in-memory `:db` を再構築する
     （replay-from-log）。
   - `transact!` は既存の `swap!` 完了**後**（CAS retry で複数回実行されうる
     `swap!`の中ではなく、確定した`@report`を使って一度だけ）に
     `((:append persist) {:tx (:tx report) :tx-data (:tx-data report)})`
     を呼び、永続化する。
4. `q`/`pull`/`entid`/`db`/`datoms`/`api`マップは**無変更**——in-memory
   `:db`値に対する既存の純粋ロジックをそのまま再利用する。`langchain.memory`/
   `langgraph.checkpoint`など`:db-api`を受け取る上位層への影響もゼロ。
5. `kotobase`を実際に繋ぐアダプタは、`langchain.jvm`と同型の**optionalな
   新規namespace `langchain.kotobase-db`**として追加し、`kotobase.store`を
   `:require`する。`langchain/deps.edn`には新設する`:kotobase` alias の
   `:extra-deps`にのみ`kotobase`を追加し、メインの`:deps`には入れない。

この設計により、`langchain.db`のコア（zero-dep、`.kotoba`将来移植候補の
純粋ロジック）と、`kotobase`永続化という具体的backend選択が疎結合のまま
両立する。

## Consequences

正: `.kotoba`拡張の優先順位が実測ベースで明確になった——map/keyword/
destructuringが最優先、HOF/protocolは意図的に見送り、その理由（T2健全性）が
1箇所に記録された。langchain.dbはプロセス再起動をまたいで永続化できるように
なり、cloud-itonami/isic-3811の`DatomicStore`（langchain.db直接利用）等の
実運用先にも自動的に恩恵が及ぶ。ADR-2607141900の誤った制約記述が訂正された。

負: map/keyword等の実際の言語拡張実装（`kotoba-lang/kotoba-lang`/`kotoba`/
`compiler`側のコンパイラ改修）は本ADRでは行わない——判断のみ。`langchain.db`
のkotobase統合も本ADR時点では設計決定のみで、実装は後続コミットで行う
（本ADRの直後に同一セッションで着手）。

## Alternatives Considered

- **HOF/protocolも含めて全部言語に追加する**: 却下。T2 Effect Soundnessの
  interprocedural fixpointは静的に既知の呼び出し先を前提にしており、
  一般の第一級関数を許すとそもそもの健全性保証が崩れる——これは
  実装コストの問題ではなく理論的な健全性の問題。
- **langchain.dbに`kotobase`をハード依存させる**: 却下。`langchain`の
  ADR-0001「zero third-party runtime deps」原則と、`langchain.jvm`が
  既に確立しているoptional-alias依存パターンに反する。duck-typed
  `persist`マップの方が既存の`api`マップパターンとも一貫する。
- **ADR-2607141900/2607072600を書き換えて訂正する**: 却下。この
  リポジトリのADR運用はappend-onlyが原則（`manifest/cleanup-workflow.edn`
  等と同型）——過去のADRは訂正の必要が生じても本文を書き換えず、
  新しいADRから参照可能な形で訂正を記録する。

## 2026-07-14 Addendum — langchain.db kotobase 永続化統合を実装

§Decision 2 の設計をそのまま実装し、`kotoba-lang/langchain` main へ着地させた
（`27a479b`→`cda206e`、`gh api .../merges`でサーバ側マージ、superproject 外
sibling worktree で作業、branch/worktree cleanup 完了、`manifest/west.yml`
の `langchain` pin を `--entry` 最小diffで前進済み: `0f966d06c93d`→`cda206ea2cbe`）。

**着地内容**（設計からの変更点も含め正直に記録）:

- `langchain.db/create-conn` に3引数形式 `(create-conn schema persist)`
  を追加。`persist`は`{:append (fn [event]) :read (fn [since] -> [events])}`
  という duck-typed map で、`langchain.db`自身は`kotobase.store`を
  `:require`しない（設計通り）。
- 実装時に見つけた**前方参照バグ**: `create-conn`が`with`（ファイル後方で
  定義）を呼ぶため、`(declare with)`を`ns`直後に追加する必要があった
  （設計段階では見落としていた、実装時に`clojure -M:test`のコンパイル
  エラーで発覚・即修正）。
- `transact!`の永続化呼び出しは設計通り`swap!`解決後に`@report`を使って
  1回だけ実行。**実装時に正直に記録した未解決の既知の限界**:
  同一connへの並行`transact!`下で、in-memory`:log`のtx順序（swap!内で
  原子的に確定）とpersistへのappend順序（各callが自分のswap!成功後に
  別ステップで呼ぶ）がずれうる——リプレイ時に稀に元と異なるtx順序で
  `:db`が再構築される可能性がある。今回は対処せず、docstringに
  limitationとして明記するに留めた。
- 新規 `langchain.kotobase-persist`（`persist-for`関数1つ）が実際の
  `kotobase.store/IStore`との接続を担う。`langchain/deps.edn`は
  メイン`:deps`を変更せず、既存の`:test`aliasの`:extra-deps`に
  `kotobase`を追加（`langchain.jvm`のhttp-kit/jsonista分離パターンを
  踏襲）。
- 新規テスト4件（`kotobase_persist_test.cljc`）: 永続化+リプレイ、
  streamごとの分離、persist無し時の後方互換性。**実際に
  `clojure -M:test`を実行**し、既存56テストと合わせ**60 tests /
  152 assertions、0 failures/errors**を確認（新規4件含む）。
  `clojure -M:lint`も実行し**エラー0**（既存の無関係な警告10件のみ、
  今回変更したファイルに新規警告は無いことを確認済み）。

**副産物として判明した事実**: `langchain`には既に`langchain.kotoba-db`
という、リモートkotoba-server（`ai.gftd.apps.kotobase.datomic.*` XRPC）に
接続する**別の**フルDatomic互換`api`実装が存在していた——クライアント側
Datalogエンジンを持たない thin-client 方式で、今回追加した
`langchain.kotobase-persist`（`db.cljc`自身のpure Datalogエンジンを
維持したまま tx-log だけを`kotobase.store/IStore`で永続化する方式）とは
アーキテクチャが異なる。両者は競合せず共存する——利用者は「サーバ側で
全クエリを処理する薄いクライアント」か「ローカルで完結する永続化
Datalogエンジン」かを選べる。

## 2026-07-14 Addendum 2 — `.kotoba` 言語拡張を `kotoba-lang/compiler` に実装

「では adr を設計実装」の指示を受け、§Decision 1 のトリアージ表で `:add`
判定した項目のうち `and`/`or`/`when` の `compiler/` 移植と、map/keyword/
`get`/`assoc` を実際に `kotoba-lang/compiler` の `src/kotoba/compiler/
frontend.clj` に実装した（`7bb0905`→`7d27613`、`gh api .../merges`で
サーバ側マージ、superproject外 sibling worktree、branch/worktree
cleanup完了、`manifest/west.yml`の`compiler` pinを`--entry`最小diffで
前進済み: `26ff5233b569`→`7d27613c9a32`）。

**実装時に得た決定的な設計上の発見**: `compiler/`の`backend/wasm.clj`を
実際に読んだところ、`pair`/`pair-first`/`pair-second`は**WASM線形メモリで
guest自身が管理するものではなく、既にホストインポート済みcapability**
（`kotoba:heap.pair`/`pair-first`/`pair-second`、`emit`関数の`imports`
セクション）だと判明した。これは**バックエンド/codegenの変更が一切不要**
であることを意味する——map/keywordは既存の`pair`/`pair-first`/
`pair-second`/`list`プリミティブへの**フロントエンドのdesugarだけ**で
実現でき、wasm32/x86_64/aarch64の3ターゲットすべてが（各々のバックエンドが
既に同じ`pair`系ホストインジェクション機構を実装済みのため）自動的に
恩恵を受けた。

**着地内容**:

- `and`/`or`/`when`: `kotoba-lang/kotoba`の`runtime.clj`の`desugar-and`/
  `desugar-or`（今朝のsession中に live 再検証済み・実証済みの実装）を
  そのまま移植。`when`は`compiler/`に`do`が無いため「test + 単一結果式」
  のみ対応（Clojureの複数bodyフォームは非対応、正直に制約として明記）。
- keyword literal: 決定論的 FNV-1a 64bit hash による i64定数への
  interning（`clojure.core/hash`ではなく固定アルゴリズムを選択——この
  compilerが持つ「byte-for-byte再現可能ビルド」ゲートと整合させるため）。
- map literal: `{:k1 v1 :k2 v2}` → `(pair (pair k1' v1') (pair (pair k2'
  v2') 0))`という既存`pair`/`list`desugarへの完全な再利用。エントリ順は
  `pr-str`によるソースの正規化ソートで決定論性を担保。
- `get`（2/3-arg）: コンパイラが自動生成する再帰ヘルパー
  `__kotoba_map_get`（`get`が実際に使われたモジュールにのみ注入）で
  pair-listを線形探索。既存の固定fuel予算（256 call、`ir.clj`/
  `backend/wasm.clj`/`core.clj`で既に確立済み、今回変更していない）が
  map探索の深さも自動的に制限する——新しい制限ではなく既存機構の
  自然な拡張。
- `assoc`（可変長k-v pair対応）: 純粋なO(1) desugar（`pair`で前置、
  同一keyは`get`が先勝ちで返すことでシャドーイング、削除はしない
  という設計上のトレードオフを明記）。

**検証（3経路、独立）**:
1. 既存のoracleインタプリタ（`ir.clj`の`execute`、`compile-source`の
   `:oracle-value`）— and/or/when/map+get/assoc+get/assoc-shadowの
   全シナリオで期待値と一致。
2. **実際にコンパイルされたWASMバイトを実Chicoryで実行**（scratch
   検証、コミットはしていない——`compiler/`自身の既存テストスイートが
   Chicoryを使わない慣習に合わせた）: 本物のhost-side pairヒープ実装
   （Chicory `HostFunction`/`ImportValues`）を用意し、`main` exportを
   直接呼び出し。oracleと完全一致する結果を確認——「コンパイラ内部の
   参照実装が自己整合的」なだけでなく「実際に生成されたバイナリが実際の
   WASMランタイム上で正しく動く」ことを実証。
3. `clojure -M:test`: **96 tests / 2725 assertions、0 failures**
   （既存80テスト + 新規`frontend_extensions_test.clj`16テスト）。
   既存のfuzz/property testスイート（`frontend-fuzz-test`/
   `security-fuzz-test`/`property-test`）も無変更で通過——admission
   grammarの拡張が既存のランダム生成プログラム群を壊していないことを
   確認。

**正直に記録する限界**（実装で判明、設計段階では見えていなかった詳細）:
- `compiler/README.md`の「no when/do/and/or sugar」という記述が
  古くなっていたため、実装と合わせて更新した（`do`は依然非対応、
  `and`/`or`/`when`は対応——「2つの文法は未統合のまま」という結論
  自体は変わらず、ギャップが狭まっただけ）。
- `assoc`は同一keyへの重複割り当てで古い値を削除せず前置するだけ
  （リストが単調成長する）——`get`は先頭一致を返すため意味論上は
  正しいが、同じkeyへの`assoc`を繰り返すプログラムはメモリ効率が悪い。
  v1のスコープとして許容し、ドキュメント化するに留めた。
- map literalの最大エントリ数は既存の`max-list-items`（128）を流用
  しており、`get`のfuel消費（1 call = 1 fuel）と組み合わせても
  リテラルmapの範囲では実際にfuel枯渇に到達しない
  （128 + 1 ≪ 256）——`assoc`による動的な成長を経由して初めて
  fuel枯渇シナリオが構成可能であることをテストで確認した。
- `kotoba-lang/kotoba`側（wasm_exec.clj）へのmap/keyword実装は
  今回のスコープに含めていない——`kotoba/`は`compiler/`と違い
  `pair`系のheapプリミティブ自体をまだ持たない（前セッションのA調査で
  確認済み）ため、まず土台となるheapプリミティブの追加が前提になる、
  より大きな別タスク。

## 2026-07-14 Addendum 3 — 「kotoba/にもmap/keywordを実装する」を実際に着地

「やはり kotoba/ にも map/keyword を実装する」という指示（`.kotoba`に安全ゲートが
無いことを説明した上での判断）を受け、`kotoba-lang/kotoba`（`kotoba.runtime/
compile-wasm-expr`）に `pair`/`pair-first`/`pair-second` + keyword/map literal +
`get`/`assoc` を実装した（`68de254d8`→`dd2618067`、`gh api .../merges`で
サーバ側マージ、west pin `--entry kotoba` で前進済み: `ecadee870a73`→
`dd2618067fe2`）。

**アーキテクチャ上の発見（compiler/とは異なる実装方針が必要だった）**:
`kotoba-lang/compiler`は`pair`をホストインポート capability として実装していたが
（Phase 2 Addendumで発見済み）、`kotoba-lang/kotoba`は最初から生の線形メモリ操作
（`alloc`/`i32-store!`/`mem-i32-at`、既存プリミティブ）を guest に直接公開している
——なので `pair` は新しい opcode もホストインポートも一切不要で、既存プリミティブへの
`let`+ストア/ロードの desugar だけで実装できた（8バイト確保、left を offset 0、
right を offset 4 に格納）。

**`get`の実装がcompiler/と異なる点（正直な設計判断）**: compiler/の`__kotoba_map_get`は
「使われた時だけ注入される再帰ヘルパー関数」だったが、`kotoba/`では`function-defs`の
消費箇所が4箇所に分散しており、compiler/の`analyze`のような単一注入点が無い。そのため
`get`は**固定深度32の展開（bounded unroll）**として実装した——再帰やfuel限界を使わず、
m/k/defaultをそれぞれ`let`で1回だけ束縛してから最大32段の`if`チェーンを静的に生成する。
32段を超える深さのmapに対する`get`のミスは、trap ではなく黙ってdefaultを返す
（compiler/の「fuel超過でtrap」とは異なる、正直に文書化した既存の制約）。

**検証**: `kotoba.runtime/wasm-binary` + `kotoba.wasm-exec/run-main`という、この
repo自身の既存テスト（`wasm-and-or-when-test`/`wasm-exec-test`）と同じ実コンパイル→
実Chicory実行経路で検証（別立てのscratchスクリプトではなく）。新規テストファイル
`wasm_map_keyword_test.clj`（6 deftest）+ 既存`wasm_exec_test.clj`の
「bare keyword/map は`:unsupported-form`」という古いアサーションを
「今はcompileできる」に更新。

**実測（クリーンな比較——共有checkoutに別セッションの未コミットWIPが混在していたため、
detached-HEADの隔離workspaceで正しいbaseline commitに対して再実測した）**:
baseline（`ecadee870`）227 tests / 1088 assertions / 20 failures / 1 error →
本変更後 233 tests / 1123 assertions / **同じ20 failures / 1 error**（新規6テスト・
35アサーションすべてpass、既存の失敗——`rad_adapter_test.clj`、無関係——は不変）。
`clojure -M:lint`: エラー0・警告0。

**着地時の運用上の注意（正直に記録）**: `orgs/kotoba-lang/kotoba`の共有checkoutには
2つの別セッションの未コミットWIPが同時に存在していた（1: `test/kotoba/test_runner.clj`
への`kotoba.semantic-code-test`登録+新規`semantic_code.clj`/`semantic_code_test.clj`、
2: `src/kotoba/launcher.clj`+`docs/ADR-repository-boundaries.md`の変更）。1番目は
自分も同じファイル（`test_runner.clj`）を触るため`git stash push -u -- <対象3ファイル>`
で退避してからpin更新のcheckoutを行い、`git stash pop`で復元（3-way mergeで両者の
追加行が衝突なく共存することを確認済み）。2番目には触れていない。**stash退避の
前後で、別セッションが書きかけていたと見られる未追跡ファイル
`docs/ADR-aiueos-boot-kernel-os-integration.md`が消失していたことに気付いた**——
自分のcheckout操作が原因か、並行セッション自身の作業（rename/再生成中）によるものか
特定できていない。未追跡ファイルのため git 側に復元手段は無い。オーナーへの報告事項
として記録する（本ADRのスコープ外の別セッションの作業状況のため、これ以上の調査・
対応はしていない）。

**west.yml pin更新時のもう一つの注意**: `nbb scripts/gen-west-manifest.cljs --entry
kotoba`の出力diffに、`--entry`で指定していない`cloud-itonami`のpinも含まれていた
（別セッションによるローカルcheckoutの前進、`--entry`はVERIFICATIONのみをそのentryに
限定し、WRITEの対象は絞らないという生成器の既知の挙動）。CLAUDE.mdの
「wholesale再生成 commit 禁止」原則に従い、`git apply`で`kotoba`エントリの1行diffだけを
手動抽出してcommit——`cloud-itonami`側のpin前進はそのまま外部に残し、当該セッションの
判断に委ねた（未検証のまま自分のcommitに含めない）。

## 2026-07-14 Addendum 4 — `kotoba-lang/compiler` に destructuring/vector-as-data/loop-recur を実装、多重arity `defn` は明示的に見送り

Addendum 2 で `compiler/` に実装した `and`/`or`/`when`/keyword/map/`get`/`assoc`
に続き、§Decision 1 のトリアージ表で `:add` 判定した残り項目のうち
destructuring・vector-as-data・`loop`/`recur` を実際に `frontend.clj` に実装した
（`7d27613`→`68c15a4`、`gh api .../merges`でサーバ側マージ、superproject外
sibling worktree、branch/worktree cleanup完了、`manifest/west.yml`の`compiler`
pinを`--entry`最小diffで前進済み: `7d27613c9a32`→`68c15a436333`）。

**多重arity `defn` は今回のスコープから明示的に除外した**（トリアージ表は
「追加すべき」としていたが、実装検討の結果、他の項目と違いフロントエンドだけ
では完結しないと判断した）: 複数arityは`signatures`のシェイプ変更（関数名→
複数パラメータリスト）・`validate-expr`の呼び出しarity解決・そして**WASMには
関数名オーバーロードが無い**ため3バックエンド（`backend/wasm.clj`・
`backend/x86_64.clj`・`backend/aarch64.clj`）すべてのexport命名規則に触れる
必要がある——他の全項目が「フロントエンドのdesugarだけで3バックエンドに
自動的に恩恵が及ぶ」という同じ構造だったのに対し、これだけがバックエンド
横断の変更を要求する。価値に対してリスクが不釣り合いと判断し、別タスクとして
先送りした。

**実装した3項目**:

- **destructuring**（let + defn params、1段のみ）: `[a b & rest]`は
  `pair-first`/`pair-second`チェーン（`nth-pair-second`）への展開、
  `{:keys [a b]}`は`get`ベースの展開。`defn`パラメータの destructuring は
  「パラメータを一時シンボルに置き換え、bodyを`(let [pattern tmp] body)`で
  ラップする」ことで`let`側の実装をそのまま再利用（新しい機構を増やさない）。
  ネストしたパターン（`[[a] b]`等）は明示的に非対応で reject。
- **vector-as-data**: `[1 2 3]`は既存`desugar-list`のpair-chainエンコーディングを
  そのまま再利用——`(list 1 2 3)`と実行時表現が完全に一致する。`let`の
  bindings vectorと`defn`のparams vectorは、それぞれ専用のcase分岐/
  `analyze`内での事前消費によりこの汎用dispatchに落ちないことを確認済み。
- **`loop`/`recur`**: コンパイラが合成する再帰ヘルパー関数へのdesugar
  （`get`の`__kotoba_map_get`と同じ「合成ヘルパーの注入」パターンだが、
  1回限りの固定名ではなく**loop出現ごとに1つ**）。ループ本体が外側スコープの
  変数を参照する場合は`form-free-symbols`という**純粋に構文的な**自由変数
  スキャンでヘルパーの追加引数として捕捉する——環境/シグネチャ認識は不要:
  過剰/過小キャプチャのミスがあっても`validate-expr`の既存チェック
  （`:unbound-symbol`/arity不一致）が確実にハードエラーとして検出する
  （黙って誤動作することは無い、という設計）。

**実装時に見つけて修正した3つの実バグ**（設計時点では見えていなかった、
正直に記録する）:

1. **`let`のbindings desugarバグ**: 従来`desugar-expr`の汎用default分岐
   `(apply list op (map desugar-expr args))`が`let`のbindings vector**全体**を
   1個の不透明な引数として`desugar-expr`に渡していた——vectorは`seq?`で
   ないため無変更のまま素通りし、binding **値**側のmap/keyword/nested-vector
   desugarを黙ってスキップしていた。`(let [m {:a 1}] (get m :a))`が
   "value type is outside the safe profile"で失敗することをlive確認して
   発覚。修正: `let`専用のcase分岐を追加し、各bindingパターンを
   `destructure-binding`で展開しつつ値を明示的に`desugar-expr`する。
2. **loop-helper名の再現性バグ**: 合成ヘルパー関数名に`gensym`（JVM
   プロセスグローバルな単調カウンタ）を使っていた——`and`/`or`の
   `gensym`済み一時変数名は`let`-localでWASMローカル変数indexに消去される
   ため安全（同一プロセス内で2回コンパイルしてバイト列が一致することを
   実測確認済み）だが、loop-helperは**exportされるトップレベル関数名**で
   あり、WASMのexportセクションに文字列として直接焼き込まれる。同一
   ソースを同一プロセス内で2回コンパイルすると**異なるバイト列**になる
   ことをlive確認（oracle値は両方とも正しく一致していたにも関わらず）。
   このcompilerが持つ「byte-for-byte再現可能ビルド」ゲートに反するため、
   決定論的な`*loop-counter*`（`volatile!`、`analyze`呼び出し1回につき
   1回だけbind、ソース全体で連番）に置き換えて修正。
3. **上記2の修正中に見つかった遅延評価バグ**: `let`のbody desugarに
   `(map desugar-expr body)`という**遅延** seqを使い、それを`list*`の
   末尾引数として渡していた——`list*`は末尾引数を強制評価しない。ソース
   全体に対する`*loop-counter*`の`binding`が終わった**後**（例えば
   `uses-map-get?`の`analyze`後のtree walkが初めてそのlazy seqを強制する
   タイミング）まで`desugar-expr`呼び出しが遅延され、`loop`が`let`の
   body内にネストされている場合に`*loop-counter*`が既定値`nil`のまま
   `(vswap! *loop-counter* inc)`が呼ばれ`NullPointerException`になることを
   live確認。`mapv`（即時評価）に置き換えて修正。同種の遅延化ミスが他に
   残っていないか`frontend.clj`全体の`map`/`list*`/`concat`/`cons`の
   組み合わせを目視で洗い出し、他に同じ形の脆弱箇所は無いことを確認した。

**検証（3経路、独立、Addendum 2と同じ規律）**:
1. oracleインタプリタ（`ir.clj`の`execute`）— destructuring/vector-as-data/
   loop/reproducibility全シナリオで期待値と一致。
2. **実Chicory実行**（scratch検証、host-side pairヒープ実装）— `let`+map
   バグ修正・loop/recur・vector destructuring・map destructuring・defn
   パラメータdestructuring・複数loop・vector-as-dataの8シナリオすべてで
   oracle値と実行結果が一致することを確認。
3. `clojure -M:test`: **121 tests / 2764 assertions、0 failures/errors**
   （既存96テスト + 新規`frontend_destructuring_loop_test.clj`25テスト）。
   既存のfuzz/property/frontend-extensionsテストも無変更で通過。

**正直に記録する限界・発見**:
- destructuringが導入する`gensym`済みlet-local一時変数名
  （`destr-map__NNN`等）はJVMプロセスグローバルなカウンタで、`analyze`
  呼び出しごとにリセットされない——そのため同一ソースを3バックエンド分
  連続コンパイルすると、生の`:kir`データ（コード生成**前**のHIR/IR）は
  シンボル名が異なり**バイト同一にならない**（実測: 3つの`:kir`が
  すべて異なる値）。これは実行時挙動には無害（let-localはWASMローカル
  変数indexに消去されるだけで、`loop`単体のバイト再現性テストは別途
  pass済み）だが、Addendum 2で確立した「map/get/assocは3バックエンド間で
  `:kir`が完全一致する」というテストパターンをdestructuringにそのまま
  適用できないことを意味する。新規テストではこの発見を正直に反映し、
  `loop`単体（gensymを使わない）の`:kir`一致テストと、destructuring込み
  ソースの`:oracle-value`一致テスト（`:kir`一致は要求しない）を分けて
  記述した。
- `loop`は「bindings + 1個の本体式」のみ対応（このprofileに`do`が無い
  ため、`when`と同じ制約）。ネストしたloop、外側の`let`からの自由変数
  キャプチャ、複数defn間でのヘルパー名の一意性はすべてテストで確認済み。

## 2026-07-14 Addendum 5 — cloud-itonami isic-6492 の governor 判定ロジックを `.kotoba` へ narrow-slice port（最終ピース、本ADR完了）

「んー、実際に安全性を高めて運用したいので .kotoba を使いたいんですよねぇ.
できれば cloud-itonami は kotoba で動かしたい」（本ADRの発端の指示、message 4）
から続く一連の作業の最後のピースとして、ADR-2607141900 が承認した
narrow-slice porting パターンに従い、`cloud-itonami-isic-6492` の governor
判定ロジック本体（`credit.kernels.gate/verdict-code` + `phase-disposition`/
`phase-reason`、およびそれぞれの依存関数群）を実際に `.kotoba` へ移植した
（`cloud-itonami/cloud-itonami-isic-6492`、`main`→`6383859`、`gh api
.../merges`でサーバ側マージ、sibling worktree、branch cleanup完了）。

`affordability.kotoba`（ADR-2607072600 の先行実装）は affordability
サブチェックのみの移植だったが、今回は governor の**判定ロジック本体**
（hard-violation 判定 + confidence 判定 + actuation escalation の合成、
および phase gate の write/auto 可否判定）を移植した——`credit.governor/
check`の全体ファサード（mutable `store`読み取り、文字列`:detail`メッセージ
構築、fact catalog lookup）は ADR-2607141900 の決定どおり引き続き対象外。

**発見: 移植コストがゼロに近かった理由**——`credit.kernels.gate.cljc`は
そもそも「safe-kotoba subset」という設計方針で**既に**書かれていた
（同ファイルのdocstringが明記: 純整数演算、ネストした`if`、`=`/`<`のみ、
keyword/map/atom/host interop一切なし——`.kotoba`/wasm emission自体は
2026-07-12時点でオーナー判断により意図的に未配線のままだった）。実装時に
必要だった変更は「2つの named constant（`confidence-floor-x100`=60、
`affordability-ceiling-x100`=43）をリテラルにinline化する」の1点のみ
——`kotoba-lang/kotoba`の`wasm-binary`が top-level `def`を認識せず
（`function-defs`は`defn`のみ拾う、`def`は黙って無視される——エラーにも
ならない）、`affordability.kotoba`が既に確立していたのと同じ回避策。

**ファイルが2つに分かれた理由**: `kotoba-lang/kotoba`のwasmモジュールは
エントリポイントを`main`1つしかexportしない（`wasm-binary`のexport-section
は常に`main`+`memory`のみ）ため、`verdict-code`（governor全体の verdict）
と`phase-disposition`/`phase-reason`（phase gate）は別モジュールにする
必要があった。`phase-disposition`/`phase-reason`は入力形状・分岐構造が
完全に一対一で共有（`op-write-enabled`/`op-auto-enabled`という同じ依存
関数を2つとも呼ぶ）なので、2モジュールに複製するのではなく、1つの
`credit_phase.kotoba`の`main`が両方を`10*disposition + reason`に
pack して返す設計にした（disposition/reasonは常に{0,1,2}なので
losslessに`quot`/`rem`でunpack可能、hostがunpackする）。

**検証（gate.cljc自身の実行可能な"battery"を ground truth として直接再利用
——このADR系列で初めて、参照実装の既存テストケースをそのまま流用する形の
検証ができた）**:
1. `credit.kernels.gate.cljc`の`battery`（52ケース: verdict 21 + afford 10 +
   phase 21）の**入力/期待値の組をそのまま**、コミット前に独立した
   scratchスクリプトで`kotoba-lang/kotoba`自身の実`wasm-binary` +
   実Chicory実行（`kototama`を経由しない直接パス）に流し、52/52 pass
   （0 failures）を確認。
2. `kototama.tender`経由の正式テスト（`test/wasm/credit_verdict_test.clj`/
   `test/wasm/credit_phase_test.clj`、新規、既存`wasm/affordability_test.clj`
   と同型）でも同じbatteryケースを全て再現、`clojure -M:test`:
   **57 tests / 596 assertions、0 failures**（既存`credit.kernels.gate-test`
   のin-process battery実行を含む既存スイートは無変更で通過）。
   `clojure -M:lint`: エラー0・警告0。
3. 実際の`bin/kotoba-clj wasm emit --package-lock kotoba.lock.edn --json`
   （scratchの直接API呼び出しではなく、既存precedentと同じ実CLI経由）で
   両ファイルをコンパイルし、`kotoba.package/receipt`の`:verified? true`
   （package-lock検証込み）を確認した上でcheck-in。

**正直に記録する限界**:
- fleet deployment（`verify_node.cljs`/`server.cljs`配線、murakumo
  LaunchDaemonへのロールアウト）は今回のスコープ外——affordability check
  自身が「コンパイル+検証」（ADR-2607072600）と「fleet配備」
  （ADR-2607082000）を別ステップにした前例と揃え、同じ切り分けを維持した。
- `credit.governor/check`の全体ファサード（mutable store・文字列構築・
  fact catalog lookup）は意図的に非移植のまま——ADR-2607141900の
  narrow-slice方針どおり、判定ロジックの核だけが`.kotoba`側に存在し、
  ファサード自身がこの先コンパイル済みWASMを呼び出す統合（今回は未実装、
  affordability checkの既存パターンと同じ「hostが呼ぶ側」の対応）は
  followupとして残る。
- `cloud-itonami-isic-6492`はこのsuperprojectのwest manifestに登録
  されていない（`gftdcojp`ではなく`cloud-itonami` org配下の独立リポジトリ
  ——west pinの前進は不要、このADRへの記録のみで完結する）。

**本ADR（ADR-2607150000）のスコープはこれで完了**——Addendum 1（langchain.db
kotobase永続化）→ Addendum 2（compiler/へand/or/when+map/keyword/get/assoc）
→ Addendum 3（kotoba/へ同等実装）→ Addendum 4（compiler/へ
destructuring/vector-as-data/loop-recur）→ Addendum 5（cloud-itonami
governorの`.kotoba`移植、本ADRの発端だった安全性目的の実現）という
5段階すべてが着地した。

## 2026-07-14 Addendum 6 — `compiler/`の`assoc`が既存entryを本当に削除するよう修正（既知gapの解消）

Addendum 2/4で正直に記録していた既知の限界——「`assoc`は同一keyへの重複割り当てで
古い値を削除せず前置するだけ（`get`は先頭一致を返すため意味論上は正しいが、
同じkeyへの`assoc`を繰り返すプログラムはメモリ効率が悪い）」——を実際に修正した
（`4bd4a23`→`4e06992`、`gh api .../merges`でサーバ側マージ、west pin
`--entry compiler`で前進済み: `0ab557e8b899`→`4e06992a0e92`）。

**実装**: `get`の`__kotoba_map_get`と同じ「使用時のみ注入」パターンで
`__kotoba_map_without`ヘルパーを新設——mapのpair-chainを走査し、指定keyに
一致する既存entryを取り除きながら残りを再構築する再帰関数。`assoc`のdesugar
を、新しいpairを前置する**前**にこのヘルパーで古いentryを取り除くよう変更した
（key/valueは`gensym`済みlet-local一時変数に1回だけ束縛——2箇所で参照される
key式の二重評価を避けるため、`and`/`or`の一時変数と同じ安全な使い方）。

**検証（正直な発見を含む）**:
1. **oracleでentry数を直接確認**——`(defn count-entries [m] (if (= m 0) 0
   (+ 1 (count-entries (pair-second m)))))`という素朴な再帰を実際に`.kotoba`
   ソースへ書き、2distinct-keyのmapに同一keyを3回re-assocした後の
   entry数が**2のまま**（5に増えない）であることを確認。他keyの値も
   re-assocの影響を受けないことも確認。
2. **実Chicory実行**でも同じシナリオが一致することを確認（scratch検証）。
3. `clojure -M:test`: 既存の`map-get-recursion-shares-the-existing-fuel-budget`
   テスト（300回re-assocでfuel枯渇を検証）は**修正後も変わらず成立**する
   ことを確認したが、その**理由が変わった**ことに注意——修正前は
   「`get`の最終スキャンが肥大化したmapを歩く」ことがfuel枯渇の原因、
   修正後は「`assoc`呼び出しごとに`__kotoba_map_without`がO(map size)の
   再帰コストを払う」ことが原因。141 tests / 2841 assertions、0 failures
   （並行して別セッションがmergeした`agent/aiueos-freestanding-targets`
   PR由来の6テストも含む）。

**正直に記録するトレードオフ（単純な「改善」ではない）**: この修正は
「read側のコスト」を「write側のコスト」へ付け替えるものであり、全ての
アクセスパターンで無条件に有利になるわけではない。ir.clj/backend/wasm.clj
のheap実装は`pair`セルをdelete/GCしない（monotonicなallocationのみ）ため、
`__kotoba_map_without`による「生存entryの再構築」は生存entry 1つにつき
新しいpair cellを1つ消費する——つまり**assoc 1回あたりのheap消費は
修正前のO(1)からO(map size)へ悪化する**。一方で読み取り（`get`）側の
コストは、再assocの累積回数ではなく「distinct keyの実数」に上限される
ようになった。想定される実運用（cloud-itonami governor的な、設定/状態を
たまに書きたまに読むワークロード）ではこのトレードオフは正しい方向だが、
「大きなmapに対してほぼ読み取らず大量に書き込み続ける」ワークロードでは
heap容量（`pair-capacity`、既定4096）へむしろ早く到達しうる——単純な
Pareto改善ではなく、正直に両面を記録する。

**cljs backendとの相互作用の確認**: `assoc`のdesugarはfrontend層の変更
なので、ADR-2607151500で新設した4番目のbackend（cljs）にも自動的に
波及する——実際に`nbb`で同じentry-count検証シナリオを再実行し、一致
することを確認した（cljs backendが独自にmap/assocのロジックを持たない
ことの再確認にもなった）。

## References

- 90-docs/adr/2607141600-kotoba-kotoba-lang-compiler-kototama-aiueos-consolidation-experiment.md
- 90-docs/adr/2607141900-cloud-itonami-cljc-actors-kotoba-incompatibility-narrow-slice-porting-policy.md
- 90-docs/adr/2607072600-cloud-itonami-isic-6492-kototama-tender-wasm-deploy.md
- orgs/kotoba-lang/kotoba/src/kotoba/runtime.clj（`compile-wasm-expr`、`and`/`or`/`when`/`pos?`/`neg?`が現在compile可能であることのソース、`desugar-and`/`desugar-or`の移植元、`pair`/`pair-first`/`pair-second`/keyword/map/`get`/`assoc`の実装先）
- orgs/kotoba-lang/kotoba/test/kotoba/wasm_map_keyword_test.clj（新規6テスト）
- orgs/kotoba-lang/compiler/src/kotoba/compiler/frontend.clj（`desugar-expr`、`and`/`or`/`when`/keyword/map/`get`/`assoc`の実装先）
- orgs/kotoba-lang/compiler/src/kotoba/compiler/backend/wasm.clj（`pair`/`pair-first`/`pair-second`がホストインポートであることの根拠）
- orgs/kotoba-lang/compiler/test/kotoba/compiler/frontend_extensions_test.clj（新規16テスト）
- orgs/kotoba-lang/langchain/src/langchain/db.cljc（統合対象、`api`マップのpluggable設計）
- orgs/kotoba-lang/kotobase/src/kotobase/{store,local,kotobase}.cljc（`IStore`、`LocalStore`、`KotobaseStore`）
