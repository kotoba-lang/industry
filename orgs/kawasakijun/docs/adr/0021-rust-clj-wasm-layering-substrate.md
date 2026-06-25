# ADR-0021: Rust / Clojure / WASM の責任分担 — 言語ではなく役割で層を切る（substrate / authoring / target）

- **Status**: Accepted
- **Date**: 2026-06-23
- **Deciders**: 河崎純真 (jun784@gmail.com)
- **Context tags**: layering, rust, clojure, cljc, wasm, kotoba, kotoba-datomic, kotoba-clj, kototama-clj, kami-engine, substrate, authoring-surface, org-taxonomy
- **Related**: ADR-0013（portable Clojure agent stack）、ADR-0013（kotoba-datomic 埋め込みクエリ層）、ADR-0010（EDN 事実層）、ADR-0020（三組織タクソノミ）
- **Companion (機械可読)**: `orgs/kawasakijun/docs/adr/0021-rust-clj-wasm-layering.edn`
- **SSoT 連携**: `deps.edn`（ワークスペース配置）/ `orgs/kawasakijun/clj-stack.edn`（スタック意味論）

## 1. Context

`kotoba` は Rust 製（content-addressed Datalog DB `kotoba-datomic` + Clojure→WASM compiler
`kotoba-clj`）だが「主言語は Clojure」と位置づけている。agent スタック（langchain/langgraph-clj
等）と `kototama-clj`（organism runtime）は `.cljc` を「clj → wasm」で動かす。`kami-engine` も
Clojure 化したい要望がある。

ここで **Rust / Clojure / WASM を「横並びの3択」として捉える混乱**が生じていた。3 つは並列の
選択肢ではなく、役割の異なる層である。本 ADR はその責任分担を成文化し、配置・表記の正本を確定する。

## 2. Decision

### 2.1 3 つは「役割の違う層」であって言語の選択肢ではない（T 字構造）

一次基準は「**言語ではなく役割（role）**」。役割が決まれば言語は実装詳細として従う。

| 層 | 役割 | 言語 | 立ち位置 |
|---|---|---|---|
| **基盤 (substrate / engine)** | kotoba（`kotoba-datomic` / `kotoba-clj` compiler / store）。「機械」 | **Rust** | 速い・埋め込める・native も WASM も吐くネイティブ substrate。**普段は誰も書き換えない infra** |
| **記述 (authoring surface)** | 全ドメイン / agent / engine ロジック。langchain/langgraph-clj、kototama-clj、会計ドメイン、kami の挙動 | **Clojure (.cljc)** | **主言語**。ここに全てを書く |
| **実行ターゲット** | JVM / SCI / CLJS / **WASM** | （生成物） | **書く層ではない**。同じ `.cljc` が載るホストの一つが WASM |

- **Rust** は「kotoba がどう実装されているか」であって、ユーザーが書く言語ではない。
- **Clojure** が主言語。ドメインは全部 `.cljc`。
- **WASM** は層ではなくコンパイル先。「clj → wasm」をアーキテクチャと捉えない。正しくは
  「**clj を書く。ホストは {JVM, SCI, CLJS, WASM} の複数。WASM は kotoba-clj 経由のその一つ**」。

```
        ┌──────────────────────────────────────────────┐
  記述   │  Clojure (.cljc) — 主言語。全ドメイン/agent/engine │
        └───────────────┬──────────────────────────────┘
              2 つの細い契約だけが境界:
              (a) data API  {:q :transact! :db :pull :entid}   ← ADR-0013
              (b) kotoba-clj がサポートする Clojure subset
        ┌───────────────┴──────────────────────────────┐
  基盤   │  kotoba (Rust) — Datalog DB + clj→WASM compiler  │
        └──────────────────────────────────────────────┘
  実行   JVM(テスト) / SCI / CLJS(UI) / WASM(埋め込み) ← 同じ .cljc が載るだけ
```

### 2.2 境界は 2 つの細い契約だけ（最重要）

ドメイン `.cljc` が触ってよいのは次の 2 つだけ:

1. **data API** — `{:q :transact! :db :pull :entid}`（ADR-0013。Datomic 互換ミニ実装でも本物の
   Datomic Local / DataScript / kotoba-datomic でも差し替え可能）。
2. **host-capability 注入** — `:http-fn`、`:json-read/:write`、`IBrowser`、`IComputer`、
   `host-fn-node` 等（ADR-0013 §2）。

この 2 契約越しにしか触らない限り、Rust 内部は自由に変えられ、ホストも差し替え自由。
逆に **Rust を直接呼ぶ Clojure を書いた瞬間にポータビリティが壊れる**（禁止）。

### 2.3 責任分担（誰が何の不変条件を持つか）

- **kotoba（Rust）の責任** = エンジンの不変条件: datom `(E,A,V,T=CID,Added)`、`as-of`/`history`、
  Clojure subset のコンパイル正しさ、WASM ABI。**API 契約の安定性を守る**。
- **ドメイン（Clojure）の責任** = 全ビジネス / agent / engine ロジック。**Rust の存在を知らない
  コードを書く**（契約越しにしか触らない）。
- **WASM** = ビルド成果物。CI で「同じ `.cljc` が JVM で緑 → kotoba-clj で WASM 化して同値」を
  保証するだけ。**責任の所在ではない**。

### 2.4 kami-engine の Clojure 化 = kotoba と同じ T 字に合流させる

「kami を clj にしたい」＝**記述言語を clj にする**であって、**engine core を Clojure で書き直す
ことではない**。ゲーム / ロボティクスの hot loop（物理・描画・realtime 制御）は Clojure-on-WASM
では速度・決定性が厳しい。よって kotoba と同型に分割する:

- **kami engine core (Rust)** … 物理 / レンダ / realtime の内部ループ。kotoba と同じ「機械」。
- **kami authoring surface (.cljc)** … シーン・挙動・agent ロジック・DSL。**ここが clj 化の本体**。
  kotoba-clj で WASM 化、または埋め込み interp で駆動。
- 境界は kotoba と同型の細い契約（data API + host-capability 注入）。

→ substrate 全体（kotoba も kami も）が「**Rust core + Clojure surface**」の単一パターンに揃い、
`kototama-clj`（organism runtime）も同じ型に乗る。覚えるべきパターンが 1 個になる。

### 2.5 org 配置（ADR-0020 との整合）— Rust であることは配置を動かさない

ADR-0020 §2.3 のとおり、**「Rust であること」は配置を動かさない。役割 = 再利用 substrate なら
com-junkawasaki**。kotoba / kami-engine は Rust でも com-junkawasaki に置いたままで正しい。

## 3. 表記ずれの是正（drift reconciliation）

`kotoba` の **canonical home = `com-junkawasaki/kotoba`**（`.gitmodules`・on-disk submodule・
ADR-0020 companion `.edn` が一致。`etzhayyim/kotoba` は 2026-06 撤去済）。SSoT に残っていた古い
`etzhayyim` 表記を是正した:

| 箇所 | before | after |
|---|---|---|
| `deps.edn`（submodule エントリ） | `:name "etzhayyim-kotoba"` / `:path "orgs/etzhayyim/kotoba"` / `:remote https://github.com/etzhayyim/kotoba.git` / `:org "etzhayyim"`（しかも実体 `com-junkawasaki/kotoba` は未登録だった） | `:name "kotoba"` / `:path "orgs/com-junkawasaki/kotoba"` / `:remote git@github.com:com-junkawasaki/kotoba.git` / `:org "com-junkawasaki"` |
| `deps.edn`（`:query_engine`） | `kotoba-datomic (etzhayyim, Rust, …)` | `kotoba-datomic (com-junkawasaki, Rust, …)` |
| `orgs/kawasakijun/clj-stack.edn`（`:premises :wasm`） | `kotoba-clj (orgs/etzhayyim/kotoba)` | `kotoba-clj (orgs/com-junkawasaki/kotoba)` + WASM=ホストの一つ注記 |

**ADR-0013（clj-agent-stack / kotoba-datomic）本文に残る `orgs/etzhayyim/kotoba` 記述は
歴史的記録として保全し、書き換えない**。本 ADR-0021 が canonical home の正本であり、ADR-0013 の
当該パス参照は本 ADR により表記上 superseded される。

**`orgs/com-junkawasaki/kotoba/**` 配下の `com.etzhayyim.apps.kotoba.*`（ATProto NSID）、
`@etzhayyim/...`（npm package 名）、`brew tap etzhayyim/kotoba`、`etzhayyim-exclusive` 境界は
是正対象外** — これらは kotoba プロダクト自身のブランド / 運用境界（PDS publication・on-chain 等は
etzhayyim 運用主体に属する意図的境界）であり、org 配置のドリフトではない。かつ別 submodule の
内部ファイルなので superproject からは触らない。

## 4. Consequences

- 「Rust か Clojure か WASM か」という議論が消える: **Rust=engine 実装、Clojure=主言語の記述面、
  WASM=ホストの一つ**。新規 engine（kami 等）も同じ T 字に当てはめれば一意に決まる。
- kami-engine の clj 化は「surface を clj に統一・engine core は Rust のまま」と定義され、
  kotoba / kototama-clj と単一パターンに揃う。
- SSoT（`deps.edn` / `clj-stack.edn`）の kotoba 表記が `com-junkawasaki` で統一され、ツーリングが
  読む正本と実体（submodule）が一致する。
- 配置は不変（ADR-0020 のまま）。本 ADR は物理移動を伴わない（表記是正のみ）。

## 5. Non-goals / 保留

- kami-engine の Rust core / Clojure surface の実際の分割実装は本 ADR では未着手（設計方針の確定のみ）。
- ADR-0013 本文の歴史的パス記述は意図的に保全（書き換えない）。
- kotoba プロダクト内部の `etzhayyim` ブランド / NSID / 運用境界は対象外（プロダクト側の決定）。
