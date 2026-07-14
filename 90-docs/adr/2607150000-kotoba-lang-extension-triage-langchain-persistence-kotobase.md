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

## References

- 90-docs/adr/2607141600-kotoba-kotoba-lang-compiler-kototama-aiueos-consolidation-experiment.md
- 90-docs/adr/2607141900-cloud-itonami-cljc-actors-kotoba-incompatibility-narrow-slice-porting-policy.md
- 90-docs/adr/2607072600-cloud-itonami-isic-6492-kototama-tender-wasm-deploy.md
- orgs/kotoba-lang/kotoba/src/kotoba/runtime.clj（`compile-wasm-expr`、`and`/`or`/`when`/`pos?`/`neg?`が現在compile可能であることのソース）
- orgs/kotoba-lang/compiler/src/kotoba/compiler/frontend.clj（`desugar-expr`、`and`/`or`/`when`が未対応のままであることのソース）
- orgs/kotoba-lang/langchain/src/langchain/db.cljc（統合対象、`api`マップのpluggable設計）
- orgs/kotoba-lang/kotobase/src/kotobase/{store,local,kotobase}.cljc（`IStore`、`LocalStore`、`KotobaseStore`）
