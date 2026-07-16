# ADR-2607032500: kotoba : kotobase = Clojure : Datomic — 言語とデータベースの分離

**Status**: accepted
**Date**: 2026-07-03
**Deciders**: Jun Kawasaki

## Context

`kotoba-lang/kotoba` と `kotoba-lang/kotobase` の関係を **Clojure と Datomic の関係**
として整理する、というオーナー指示。現状はその対応が崩れている:

- **kotoba** は README で自らを *「A capability-safe language _and_ a
  content-addressed distributed Datalog database」*、*「Kotoba is two things in
  one system: A database … A language …」* と宣言し、**言語とデータベースの両方**を
  兼任している（"combining Datomic-style immutable datoms …"）。
- **kotobase**（`kotobase-clj`）は *「external-storage port — one `IStore` seam」* と
  位置づけられ、**ストレージ抽象の port 止まり**で「データベース」を名乗っていない。
- 実際の永続 datom データベースは **別の複数 repo に分散**している
  （`prolly-tree` / `ipld` / `commit-dag` / `quad-store` / `kqe` / `kotobase-engine`
  / `kotobase-client` / `kotobase-cljc-worker`）。
- **datom model が重複**: kotoba に `kgraph`（in-mem EAVT `[e a v]` datom store、旧
  名 `kqe`）があり、foundation 側 `quad-store` にも datom がある。両者は同型なのに
  別定義。
- 依存方向が未確立: foundation repo 群は kotoba（言語）に依存していない（standalone）。

Clojure と Datomic の関係の本質:

- **Clojure** は言語であり、不変の永続データ構造（map/vector）と reader/eval を持つ。
- **Datomic** はデータベースであり、**Clojure の上に構築**され、**Clojure に依存**する。
  datom（`[E A V Tx Op]`）・4 covering index・Datalog query・不変時間(T)・
  content-addressed storage を持ち、**query も transaction も Clojure のデータ**である。
- Clojure は standalone。Datomic は Clojure を必要とする（逆はない）。

## Decision

**kotoba = 言語（Clojure）、kotobase = データベース（Datomic）。kotobase は kotoba の
datom model の上に構築され、kotoba に依存する（逆はない）。**

### 対応表

| Clojure | Datomic | **kotoba**（言語） | **kotobase**（データベース） |
|---|---|---|---|
| 言語（reader / eval / special forms） | — | capability-safe kotoba 言語（`runtime` / `cap_table` / `wasm_exec` / `launcher` / `host_providers`） | — |
| 不変の永続データ構造（map/vector） | in-memory db value（不変） | **`kgraph`**（in-mem EAVT `[e a v]` datom store、言語のデータモデル） | — |
| — | datom `[E A V Tx Op]`、accretion-only log | datom `[e a v]` 原始形 | 永続 datom log（`commit-dag`） |
| — | 4 covering index EAVT/AEVT/AVET/VAET | — | `quad-store`（spo/pso/pos/ocp） |
| — | Datalog query（query は Clojure データ） | — | `kqe`（triple-pattern query。query も kotoba データ） |
| — | 不変時間 T / as-of / history | — | `commit-dag`（content-addressed commit chain = 時間） |
| — | storage services（dev/sql/dynamo/…） | — | `kotobase` IStore port（Local ‖ kotobase.net）+ `prolly-tree`/`ipld`（content-addressed blocks） |
| — | peer/client API（transact, q, pull, datoms） | — | `kotobase-engine`（transact/datoms/q/pull）+ `kotobase-client`（CACAO） |
| Clojure は JVM/JS で走る | Datomic peer は Clojure を埋め込む | kotoba は JVM/cljs/WASM で走る | `kotobase-cljc-worker` が edge(workerd) で DB を走らせる |

### kotobase = データベースの傘（umbrella）

「kotobase」は単一 repo でなく **Datomic に相当するデータベース製品の総称**であり、
以下が層をなす（下から）:

```
kotoba（言語 + datom model kgraph）            ← Clojure
   ▲ depends on
ipld / multiformats / dag-cbor（content addressing）
prolly-tree（content-addressed B-tree = storage）
commit-dag（immutable commit chain = 時間/log）
quad-store（4 covering index）
kqe（Datalog query）
kotobase-engine（transact/datoms/q/pull API）
kotobase-client（CACAO client）/ kotobase IStore port（storage seam）
kotobase-cljc-worker（edge runtime = kotobase.net PDS）
   = kotobase（Datomic）
```

### 不変条件（invariants）

1. **依存方向は kotobase → kotoba のみ**（Datomic → Clojure）。**逆・循環は禁止**。
   kotoba は言語として standalone を保つ。
2. **datom model は kotoba に一度だけ定義**する（言語のデータモデル）。kotobase の
   datom-plane（quad-store/engine）は **その同一モデルを永続化・索引・query** する
   （`kgraph` の `[e a v]` と quad-store の datom は**同型 = 統一すべきで、重複させない**）。
   Datomic の db value が Clojure データであるのと同じ。
3. **query/transaction は kotoba のデータ**として表現する（Datalog-in-kotoba）。
   Datomic の query が Clojure データであるのと同じ。
4. **kotoba は「データベース」を名乗らない**（in-mem `kgraph` 原始形は保持するが、
   それは言語のデータモデルであって、永続 DB は kotobase）。**kotobase が
   「データベース」のアイデンティティを持つ**。

### repositioning（この ADR に伴う README 変更）

- **kotoba README**: 「language _and_ database」→ **「the language」**。in-mem `kgraph`
  は言語のデータモデルとして残し、永続・分散データベースの物語は kotobase を指す。
- **kotobase README**: 「external-storage port」→ **「the database（Datomic 型、kotoba の上）」**。
  IStore port は その **client seam の一層**として位置づけ直す。

## Consequences

- **アイデンティティが明確**: 「言語 → kotoba、データベース → kotobase」。新規 consumer が
  迷わない。
- **DB は言語を触らず進化できる**: kotobase の永続/索引/query 実装（prolly-tree の
  pruning、quad-store の index、kqe の Datalog）は kotoba を変えずに深化できる。
- **言語は小さく保たれる**: kotoba は cap-safe な言語 + データモデルに集中し、
  再利用性が上がる。
- **Datomic 型の分離利益**: db value（永続 datom）は不変・content-addressed で、
  時間(commit-dag)を跨いだ as-of/history が構造的に可能。edge(worker) で走る PDS も
  「Datomic peer が Clojure を埋め込む」構図の写像。

## Follow-up

- **datom model の統一**（invariant 2 の具体化）: `kotobase-engine`/`quad-store` が
  自前の datom 定義でなく **kotoba の datom model（`kgraph` 由来）に依存**するようにし、
  「Datomic が Clojure データを使う」構図をコードでも実現する。これにより foundation
  repo 群に `kotoba`（言語）への依存が入る（現状 standalone）。段階移行。
- kotobase 傘下 repo（engine/quad-store/kqe/…）の deps は ADR-2607032300 の
  `check-foundation-deps.cljs` で consistency を維持（この ADR の層構造がその DAG）。
- query/transaction の kotoba 表現（Datalog-in-kotoba）を kqe の公開面に固める。

## 一行まとめ

**kotoba は言語（Clojure）。kotobase はその datom を永続化・索引・query する
データベース（Datomic）で、kotoba の上に建ち kotoba に依存する。言語は「DB」を
名乗らず、DB は言語を書き換えない。**

## 実装状況更新（2607032530）— datom model 統一（engine 側）着地

follow-up「datom model の統一」の**具体化を開始・engine 側を着地**（kotobase-engine `963ed6d`）:

- **canonical datom model = `io.github.kotoba-lang/datom`（datom-clj）**。zero-dep 可搬
  `.cljc`、"EAVT, Datomic-isomorphic" な entity↔`[e a v]` 表現（`datom.core/{entity,eavt,log}`）。
  従来は孤児（誰も依存せず、kgraph も quad-store も独自定義）だった。
- **kotobase-engine が datom-clj を依存に採用**し、entity tx-map の datafication を
  `datom.core/eavt` 経由に:
  - `entities->datoms` / `tx-map->datoms` / `transact-tx`（entity tx-maps → `[e a v]` →
    `transact`）。engine の `transact`/`->quad` は元々 `[e a v]` を受理するので自然な接続。
  - test: engine の entity datafication == canonical `[e a v]` モデル（18 tests/66 assertions green）。
- これで **DB（kotobase-engine）が「private な entity→quad 再実装」でなく共有 datom model を
  consume** する（Datomic が Clojure データを使う構図のコード化）。依存 edge は
  `check-foundation-deps.cljs`（CI）が drift なく維持（engine → datom を追加）。

**残り（paired step）**:
- **`kotoba.kgraph`（言語の in-mem view）も datom-clj を consume** させ、両側（言語の in-mem
  datom store と DB の永続 datom store）が **同一 datom model を共有**する状態にする。kgraph は既に
  `[e a v]` を話すので低リスク。これで invariant 2（datom model は一度だけ定義）が両側で実体化。
- worker（`kotobase-cljc-worker/handler.tx-edn->quads`）の datafication も engine の
  `entities->datoms` に委譲（現在は並行セッションが handler.cljc を D1 novelty-log で編集中のため
  その決着後）。

## 実装状況更新（2607032600）— datom model 統一 **両側完了**（invariant 2 実体化）

前回の engine 側に続き、**言語側（kgraph）も datom-clj を consume**（kotoba `338c233`）:

- **`kotoba.kgraph`（言語の in-mem EAVT datom store）が datom-clj を依存採用**し、entity
  tx-map の datafication を `datom.core/eavt` 経由に: `assert-entity` / `assert-entities`。
  engine の `entities->datoms` / `transact-tx` と**同一の datafication 経路**。
- test で等価性をロック: `(kgraph/assert-entity [] ent) == (datom.core/eavt ent)`
  （kgraph-test 4 tests/11 assertions green）。
- kotoba `deps.edn` に datom-clj（:deps git-pin + :dev :local/root）。

**→ invariant 2 が両側で実体化**:

```
                    datom.core (datom-clj)   ← 唯一の datom model [e a v] / entity↔eavt
                    ↙                      ↘
kotoba.kgraph（言語の in-mem view）      kotobase-engine（DB の永続 view）
   assert-entity/assert-entities            entities->datoms/transact-tx
        = 同一 datafication ============================ 同一 datafication
```

言語（Clojure=kotoba）の in-mem datom store と データベース（Datomic=kotobase）の永続
datom store が、**同一の datom model を共有**する（Datomic の db value が Clojure データで
あるのと同型）。この shared 依存は `check-foundation-deps.cljs`（CI）が drift なく維持する。

**残り（最後の一歩）**: worker（`kotobase-cljc-worker/handler.tx-edn->quads`）の datafication
を engine の `entities->datoms` に委譲。現在は並行セッションが handler.cljc を D1 novelty-log で
編集中のため、その決着後に。これで transport 層も canonical model に揃う。

## 実装状況更新（2607032630）— datom model 統一 **全3層完了**

最後の transport 層も統一（kotobase-cljc-worker `cf7f0b7`）:

- **worker の `handler/tx-edn->quads` が engine の `entities->datoms`（= `datom.core/eavt`）
  経由に**。private な entity→quad ループを撤去し、DB・言語と同一の `[e a v]` datafication に。
  behavior-preserving（worker build + node-test 12/54 green）。
- **副次修正（必須）**: engine が `datom.core` を require し始めたため、worker の shadow-cljs
  source-paths に `../datom/src` を追加（無いと `namespace datom.core not available` で build 破綻）。

**→ 全3層が唯一の datom model（datom-clj）に収束**:

```
                       datom.core (datom-clj)
            ┌───────────────┼───────────────┐
   language(kotoba)      DB(kotobase-engine)   transport(worker)
   kgraph                 entities->datoms      tx-edn->quads
   assert-entity          transact-tx           = entities->datoms
        └───── すべて datom.core/eavt = 同一 [e a v] datafication ─────┘
```

**kotoba : kotobase = Clojure : Datomic** が、positioning（ADR/README）だけでなく **言語・
データベース・transport の全層のコードで、唯一の共有 datom model として実体化**した。
Datomic が Clojure データを db value・query・tx に使うのと同型。共有依存は
`check-foundation-deps.cljs`（CI）が drift なく維持する。

**この ADR の follow-up は全て着地**（残作業なし）。
