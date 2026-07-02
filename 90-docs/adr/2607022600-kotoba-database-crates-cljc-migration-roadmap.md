# ADR-2607022600: 削除された kotoba Rust crates（DB本体 — CID/Prolly Tree/5-index Datalog/CommitDag/BlockStore/SPARQL/DHT等）を CLJC へ移行するロードマップ

**Status**: proposed
**Date**: 2026-07-02
**Deciders**: Jun Kawasaki

## Context

`kotoba-lang/kotoba` の Rust ワークスペース（crates/* 全体、約38万行）は
PR #259「Remove legacy Rust workspace」（2026-07-01, commit `604896171`）で
一括削除された。README も同時に最小限の CLJC ランチャー説明に差し替えられて
いたが、本セッションで PR #270（`docs/restore-database-cid-prolly-readme`）
により削除直前の全文（CID/Prolly Tree/5-index Arrangement/CommitDag/SPARQL/
CACAO/DHT/Pregel/Signal を備えた分散 content-addressed Datalog データベース、
という説明）を復元した。しかしこの復元は**歴史的記述の復元であって現状の主張
ではない**——実際に調査した結果、次が判明している。

1. `docs/rust-crate-migration.md`（kotoba-lang/kotoba 内）が定める既存の移行方針
   は **CLI/ランチャー/selfhost リソースの範囲に限定**されており（"New protocol,
   CLI, database, deploy, git/rad, or language behavior must land first as a
   CLJC/EDN contract" と書かれてはいるが）、DB 本体（Prolly Tree・5-index
   Arrangement・CommitDag・階層化 BlockStore・SPARQL・DHT・Pregel）の具体的な
   移行計画は存在しない。
2. `kotoba-lang/kotoba` の CLJC 側実装は `src/kotoba/kgraph.clj`（63行、インメモリ
   EAVT ベクタ + 単純パターンマッチのみ）と `datom/core.cljc` 系（39行、エンティ
   ティマップを `[e a v]` にフラット化するだけ）に留まり、永続化も
   content-addressing も無い。
3. ADR-2606271600（kotoba-stack-equivalences、2026-06-27）は Rust crate 構成
   （`kotoba-lattice`/`kotoba-net`/`kotoba-auth`/`kotoba-runtime`/`kotoba-clj`/
   `kotoba-datomic`/`kotoba-query`/`kotoba-git`/`kotoba-dht`）を wasmCloud/Spin/
   Datomic/Radicle と対応づける **positioning 地図**として書かれたが、4日後の
   crate 削除を反映しておらず、**実装マップとしては陳腐化**している（登場する
   crate は現在ソースが存在しない）。本 ADR はその実装面のギャップを引き継ぐ。
4. 一方で `orgs/kotoba-lang/*` には、Rust crate とは独立に育ってきた小さな
   CLJC/EDN ライブラリ群が既に存在し、DB 本体の一部プリミティブは**既に実装済み**
   である（下記 Decision §1 の一覧）。ゼロからの移行ではない。
5. `kotoba-git`/`kotoba-rad`（主権 git/repository 層）は ADR-2606280300 に
   R0–R4 の独立した成熟度ロードマップが既にあり、`kotoba-kotodama` のドメイン
   セル群は CLAUDE.md の Actors パターンで別リポジトリへ個別移行する既定路線が
   ある。WASM コンパイラ/ランタイム層は ADR-2607022200（aiueos CLJC WASM
   component kernel architecture）が担う。これらは本 ADR の対象外とし、参照のみ
   行う（重複させない）。

## Decision

### 1. 現状棚卸し（削除された Rust crate → CLJC 側の現状）

| 領域 | 旧 Rust crate | CLJC 側の現状 | 状態 |
|---|---|---|---|
| CID / multihash | `kotoba-core::cid`, `kotoba-ipfs::cid` | `kotoba-lang/multiformats`（203行）— `ipfs add --cid-version=1 --raw-leaves` とバイト同一の CIDv1（sha2-256, base32, base58btc）。**ただし raw leaf（multicodec 0x55）のみで dag-cbor（0x71）コーデック未対応** | 部分完了 |
| DAG-CBOR | `kotoba-core`（prolly.rs 内部で使用） | `kotoba-lang/dag-cbor`（127行）— 正規順ソートの definite-length CBOR encode/decode（stdlib only） | 完了（プリミティブとして） |
| Prolly Tree | `kotoba-core::prolly`（blake3 チャンキング + DAG-CBOR/IPLD ノード、path-copy） | 無し。近縁として `kotoba-lang/mst`（AT Protocol Merkle Search Tree、SHA-256 leading-zero fanout、83行）があるが**設計が別物**（確率的チャンキングでなく AT-Proto 固有の fanout 規則） | 未着手 |
| Datom ログ / EAVT | `kotoba-datomic`, `kotoba-graph::quad_store` | `kotoba-lang/datom`（39行、entity→`[e a v]` フラット化のみ）、`kotoba-lang/kotoba` の `kgraph.clj`（63行、インメモリ EAVT + 単純マッチ） | 未着手（形状ヘルパーのみ） |
| 5-index Arrangement（EAVT/AEVT/AVET/VAET/TEA） | `kotoba-query::arrangement` | 無し | 未着手 |
| CommitDag（append-only, parent-linked, head-ref atomic） | `kotoba-graph::commit` | 無し（設計自体は ADR-2606041151 kotoba-commitdag-as-wal-and-incremental-query-tier に既存） | 未着手（設計は既存、実装無し） |
| BlockStore（Memory/Kubo/Budgeted LRU/Tiered/Distributed/B2 cold） | `kotoba-store` | 無し。`kotobase-clj/store.cljc`（26行、IStore = doc/stream の汎用ストア。ブロックストアではない）と、B2/DataLad 運用（CLAUDE.md 「大容量バイナリの扱い」）は既に別レイヤで運用中 | 未着手（block-store 抽象なし） |
| SPARQL 1.1 | `kotoba-graph::sparql` | 無し | 未着手（README 自身が「auxiliary」と明言 — 優先度低） |
| CACAO（depth-2 delegation, multi-graph grants） | `kotoba-auth::cacao` | `kotoba-lang/cacao`（191行）と **別に** `kekkai/cacao.cljc` が存在（2実装が並存、要統合） | 部分実装・重複あり |
| Signal Protocol（X3DH + Double Ratchet + MLS） | `kotoba-signal` | `kotoba-lang/signal`（ratchet/hkdf/x3dh/group/x25519、計526行）— かなり充実 | ほぼ完了（グラフ層への配線は未） |
| 汎用暗号（AEAD/HKDF/key wrap） | `kotoba-crypto` | `kotoba-lang/crypto`（162行）、`kotoba-lang/ed25519`（233行） | 完了（プリミティブとして） |
| DHT（Source Chain/Warrant/Neighborhood） | `kotoba-dht` | `kotoba-lang/net`（gossip.cljc + bitswap.cljc、計201行）— gossip/bitswap の断片のみ | 未着手（断片のみ） |
| Pregel BSP | （`kotoba-graph` 内） | 無し | 未着手 |
| kotoba-git（git DAG→CID） / kotoba-rad | `kotoba-git`, `kotoba-rad` | ADR-2606280300 に独立ロードマップあり | **対象外**（参照のみ） |
| ドメインセル（`kotoba-kotodama/cells/*`） | `kotoba-kotodama` | CLAUDE.md Actors パターンで個別リポジトリへ移行中 | **対象外**（参照のみ） |
| WASM コンパイラ/ランタイム | `kotoba-clj`(Rust), `kotoba-runtime` | ADR-2607022200（aiueos CLJC WASM component kernel architecture） | **対象外**（参照のみ、本 ADR の成果を `:db-api` 経由で消費する側） |
| LLM（weight blob/LoRA/WebGPU train・infer） | `kotoba-llm` | `webgpu`/`inference`/`torch` 系ディレクトリが将来の受け皿候補。未計画 | **対象外**（別 ADR が必要、本 ADR には含めない） |
| Ingest（Gmail/CC/BM25/pagerank/media embed） | `kotoba-ingest` | 無し | **対象外**（別 ADR が必要、本 ADR には含めない） |
| Custody（X-Road t-of-N shares/audit） | `kotoba-custody` | 無し。ただし目標設計は ADR-2606271600 §3（object encryption, ciphertext-CID, envelope, epoch key）に既述 | **対象外**（別 ADR が必要、設計方針のみ参照） |

### 2. 移行方針（アーキテクチャ規約）

- **新規実装は `orgs/kotoba-lang/<name>` 配下の小さな独立 `.cljc` ライブラリとして
  切り出す**（`multiformats`/`dag-cbor`/`mst`/`cacao`/`signal` と同じ粒度・同じ
  パターン）。`kotoba-lang/kotoba` 本体に巨大モノリスとして書き戻さない。
- **`kotoba-lang/kotoba` 側は薄いホストアダプタに留める**。CLAUDE.md の Actors
  パターンにある Store 注入境界（`MemStore ‖ DatomicStore`、`:db-api` 経由でのみ
  backend と会話）をそのまま踏襲し、Prolly/Arrangement/CommitDag/BlockStore の
  実装差し替えを注入可能にする。
- **正しさの検証方法を明記する**（Rust 側のバイト一致オラクルはもう存在しない
  ため）:
  - IPFS 相互運用が必要な部分（CID）は引き続き `ipfs add` CLI をオラクルにする
    （`multiformats` が既に採用している方式）。
  - Prolly Tree の内部チャンキングパラメータ自体は、外部コンシューマがバイト
    一致を要求する契約が現存しない（旧 Rust サーバも削除済み）ため、**新規に
    設計してよい**——ただし IPLD ノードとして CID から辿れる形（DAG-CBOR +
    tag-42 リンク）は維持する。
  - CommitDag / Arrangement は ADR-2606041151 の設計（WAL 廃止、CommitDag が
    唯一の WAL）をそのまま仕様として使う。

### 3. フェーズロードマップ

| Wave | 内容 | 前提 | 優先度 |
|---|---|---|---|
| **Wave 0（完了済み）** | CID・DAG-CBOR・CACAO 基礎・Signal・ed25519・汎用暗号・datom 形状ヘルパー・gossip/bitswap 断片 | — | — |
| **Wave 1 — 基盤（Prolly Tree + BlockStore）** | `kotoba-lang/prolly`: 決定的チャンキング（ハッシュ関数選定要検討、下記 Consequences 参照）+ path-copy + DAG-CBOR ノード。`multiformats` に dag-cbor コーデック（0x71）を追加。`kotoba-lang/block-store`: Memory backend から開始、Kubo HTTP backend、既存 B2/DataLad 運用との接続 | Wave 0 | 高 |
| **Wave 2 — Datom ログ + 5-index Arrangement + CommitDag** | `datom/core.cljc` を append-only ログ（t, added/retracted 付き）に拡張。`kotoba-lang/arrangement`: EAVT/AEVT/AVET/VAET/TEA を Prolly Tree 上に構築。`kotoba-lang/commit-dag`: ADR-2606041151 準拠の parent-linked チェーン + head-ref atomic update | Wave 1 | 高 |
| **Wave 3 — クエリ面** | Arrangement 上の Datalog（join/scan）。**DataScript 等の既存 Clojure Datalog 実装を採用するか、独自実装するかを Wave 3 着手時に決定**（build-vs-adopt、本 ADR では未決）。SPARQL 1.1 は README 自身が auxiliary と明言しており優先度を下げる | Wave 2 | 中 |
| **Wave 4 — 認可/暗号の配線強化** | `kotoba-lang/cacao` と `kekkai/cacao.cljc` の重複を解消し depth-2 delegation + multi-graph grants に強化。Signal Protocol をグラフの E2E envelope 経路に配線 | Wave 1（並行可） | 中 |
| **Wave 5 — DHT/Pregel（投機的、着手保留）** | Source Chain/Warrant/Neighborhood、Pregel BSP。`net/gossip`+`net/bitswap` の先。**Wave 1–3 が着地し、実際の分散クエリ需要が具体化してから再検討**（先回りして作らない） | Wave 2–3 | 低（保留） |

## Consequences

- (+) `docs/rust-crate-migration.md` の抽象的な「新規挙動は CLJC/EDN を先に」
  という方針を、DB 本体について**具体的な順序付きロードマップ**に落とし込む。
- (+) Wave 0 として既に使える資産（`multiformats`/`dag-cbor`/`mst`/`cacao`/
  `signal`/`crypto`/`ed25519`/`datom`/`store`/`crdt`/`net` の断片）を棚卸しした
  ことで、Wave 1 はゼロからではなく大部分を再利用して始められる。
- (+) Wave 1 着手前に解くべき具体的な設計課題を可視化した:
  (a) Prolly チャンキングのハッシュ関数（旧 Rust は blake3 だが CLJC 側に
  ネイティブ blake3 実装が無い。`mst`/`multiformats` は JVM
  `MessageDigest`/`@noble/hashes` 経由の SHA-256 に統一している——同じ路線を
  踏襲するか blake3 依存を追加するかは Wave 1 着手時に決定する）、
  (b) `multiformats` に dag-cbor コーデック（0x71）が無い、
  (c) `kotoba-lang/cacao` と `kekkai/cacao.cljc` の重複、
  (d) Datalog エンジンの build-vs-adopt（DataScript 等）。
- (−) 本 ADR は**計画のみ**であり、このコミット自体はコードを一切変更しない。
  複数リポジトリ・複数ヶ月規模の作業であり、各 Wave 着手時に個別 ADR
  （実装内容・テスト結果を記録するタイプ、例: ADR-2607022500 の形式）を積む
  ことを前提とする。
- (−) **本番 `kotobase.net` が現時点で削除済み Rust バイナリのまま稼働している
  かどうかは本 ADR 作成時点で未確認**（`kekkai/kotoba.cljc` は kotobase.net の
  XRPC 経由 DatomicStore に接続するクライアントを既に持つ——サーバ側の実体は
  未調査）。もし稼働中なら、Wave 2 完了後に「ソース置換」だけでなく実際の
  カットオーバー/並行稼働手順が別途必要になる。Wave 1 着手前に要調査。
- (−) SPARQL・DHT・Pregel は明示的に後回し（Wave 3 の一部・Wave 5）とした。
  近い将来に具体的需要が生じた場合はロードマップの優先度を見直すこと。
- (−) `kotoba-git`/`kotoba-rad`、ドメインセル、WASM ランタイム、LLM、Ingest、
  Custody は意図的に本 ADR の対象外とした（既存 ADR か別途 ADR が必要）。
  「kotoba crates を全て CLJC に」という要求に対する**部分的な回答**である
  ことを明記する。

## References

- PR #259（`kotoba-lang/kotoba`, commit `604896171`）— Rust ワークスペース一括削除
- PR #270（`kotoba-lang/kotoba`, `docs/restore-database-cid-prolly-readme`）—
  削除前 README の復元（本 ADR の直接の発端）
- `kotoba-lang/kotoba/docs/rust-crate-migration.md` — 既存の CLI/selfhost 限定
  移行方針（本 ADR が DB 本体まで拡張する）
- ADR-2606271600（kotoba-stack-equivalences）— crate 構成の positioning 地図
  （実装面では本 ADR が引き継ぐ）
- ADR-2606041151（kotoba-commitdag-as-wal-and-incremental-query-tier）—
  CommitDag の設計仕様（Wave 2 で実装対象にする既存設計）
- ADR-2606041130（kotoba-b2-blockstore-cold-pin）— B2 cold tier の既存運用
  （Wave 1 の BlockStore が接続する対象）
- ADR-2605312345（kotoba-datom-first-class-canonical-state）— Datom ログが
  正本であるという既存決定
- ADR-2606280300（kotoba-rad-git-sovereign-repo）— kotoba-git/kotoba-rad の
  独立ロードマップ（本 ADR の対象外、参照のみ）
- ADR-2607022200（aiueos-cljc-wasm-component-kernel-architecture）— WASM
  ランタイム層（本 ADR の DB を `:db-api` 経由で消費する側）
- ADR-2606290900（kotoba-aiueos-key-lifecycle-revocation）— signer/capability
  revocation（Wave 4 の CACAO 強化と隣接するが別スコープ）
- `orgs/kotoba-lang/{multiformats,dag-cbor,mst,cacao,signal,crypto,ed25519,
  datom,store,crdt,net}` — Wave 0 で既に存在する再利用可能な CLJC 資産
- `orgs/kotoba-lang/kekkai/src/kekkai/{cacao,kotoba,store}.cljc` — kotobase.net
  への既存クライアント配線（重複解消対象、および本番稼働状況の調査起点）
