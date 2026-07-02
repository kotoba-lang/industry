# ADR-2607022600: 削除された kotoba Rust crates（DB本体 — CID/Prolly Tree/5-index Datalog/CommitDag/BlockStore/SPARQL/DHT等）を CLJC へ移行するロードマップ

**Status**: proposed
**Date**: 2026-07-02
**Deciders**: Jun Kawasaki

> **2026-07-02 追記（本 ADR 自身への訂正）**: 初稿は `orgs/kotoba-lang/*` の
> **ローカルにチェックアウト済みのディレクトリだけ**を調べて Wave 1–3 を
> 「未着手」と書いたが、誤りだった。同日 05:16–05:21 UTC、**別の ADR
> （ADR-2607010930 clj-wgsl-migration Phase 6 ——名前からは DB 移行と分からない）
> の作業として** `kotoba-lang/{prolly-tree, quad-store, kqe, kotoba-client}` が
> 既に GitHub に push 済みだった。ローカル未 clone のため `find`/`grep` に
> 引っかからず見落とした。GitHub 側を `gh repo list` で確認して発覚。
> Decision §1 の該当行・§3 のロードマップ・References を実態に合わせて訂正
> 済み（取り消し線ではなく直接修正——本 ADR はまだ他コミットに参照されておらず、
> 別 ADR を積むより本体を正すほうが読者に親切と判断）。教訓: 今後この種の
> 棚卸しは **ローカル checkout の grep だけでなく `gh repo list <org>` で
> GitHub 側の全 repo を見る**こと。

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
   である（下記 Decision §1 の一覧）。ゼロからの移行ではない。**特に
   ADR-2607010930（clj-wgsl-migration Phase 6）が同日、`kotoba-lang/prolly-tree`
   （Prolly Tree）→ `kotoba-lang/quad-store`（4-index Arrangement + commit
   snapshot）→ `kotoba-lang/kqe`（hot Datalog 相当のパターンクエリ）→
   `kotoba-lang/kotoba-client`（CID 検証付きブロック取得/hydrate）という
   依存チェーンを実際に landing 済み**（各リポジトリ README が自身の
   "Not in this landing" を明記——後述）。Wave 1–3 は「ゼロから作る」ではなく
   「既存実装のギャップを閉じる」に主として変わる。
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
| CID / multihash | `kotoba-core::cid`, `kotoba-ipfs::cid` | `kotoba-lang/multiformats`（203行）— `ipfs add --cid-version=1 --raw-leaves` とバイト同一の CIDv1（sha2-256, base32, base58btc）。**raw leaf（0x55）と dag-cbor（0x71）の両コーデックとも実装済み**（`cidv1-raw`/`cidv1-dag-cbor`。本 ADR 初稿は後者の存在を読み落として「未対応」と誤記していた） | 完了 |
| DAG-CBOR | `kotoba-core`（prolly.rs 内部で使用） | `kotoba-lang/dag-cbor`（127行）— 正規順ソートの definite-length CBOR encode/decode（stdlib only） | 完了（プリミティブとして） |
| Prolly Tree | `kotoba-core::prolly`（blake3 チャンキング + DAG-CBOR/IPLD ノード、path-copy） | **`kotoba-lang/prolly-tree`（ADR-2607010930 Phase 6）— 実装済み。** SHA-256 の下位ビット境界（~1/256、旧 Rust の `BOUNDARY_MASK=0xFF` と同密度）、DAG-CBOR ノード、`multiformats.core/cidv1-dag-cbor` アドレッシング。内部レベルの境界判定を「子の CID」に取る設計（「子の max-key に取ると全レベルで同じ境界判定が再発火して無限再帰する」という旧 Rust 実装が踏んだバグを踏まえた回避）。`build-tree`/`lookup`/`scan-prefix`（prefix scan は index pruning 無しの全走査、後続課題として明記）。近縁の `kotoba-lang/mst`（AT-Proto MST）とは設計が別物（用途が違う、共存でよい） | **ほぼ完了**（range-pruned scan・diff/merge・GC が未着手として明記されている） |
| Datom ログ / EAVT | `kotoba-datomic`, `kotoba-graph::quad_store` | `kotoba-lang/datom`（39行、entity→`[e a v]` フラット化のみ）、`kotoba-lang/kotoba` の `kgraph.clj`（63行、インメモリ EAVT + 単純マッチ）— この2つは未着手のまま。ただし下記 quad-store がインメモリ4-index の実質的な代替になっている | 部分完了（quad-store が肩代わり） |
| 5-index 相当 Arrangement | `kotoba-query::arrangement`（EAVT/AEVT/AVET/VAET/TEA） | **`kotoba-lang/quad-store`（ADR-2607010930 Phase 6）— 実装済み。** `spo`/`pso`/`pos`/`ocp` の4-index（旧 Rust の命名と1:1対応ではなく Quad ベース）。`assert-quad`/`retract-quad`/`entity-attrs`/`by-predicate`/`by-predicate-value`/`refs-to`。値は文字列限定（dag-cbor round-trip の安全域、typed values は明記された follow-up）。cold（prolly-tree 経由の再構築）query は未着手と自己申告 | **ほぼ完了**（hot/インメモリのみ。cold 再構築は未） |
| CommitDag（append-only, parent-linked, head-ref atomic） | `kotoba-graph::commit` | **`kotoba-lang/quad-store/commit!`が単発コミット（`{index-roots prev}`、db+prev から決定的に同一 CID）を実装済み。** ただし `seq` フィールドが無く、複数コミットの chain 走査（`chain`）や改竄検知（`verify-chain`）のユーティリティも無い。本セッションでこのギャップを埋める **`kotoba-lang/commit-dag`** を新規実装（下記参照）— `commit!`/`chain`/`head`/`verify-chain`、`state` は opaque（quad-store の `index-roots` map もそのまま渡せる）。ADR-2606041151（WAL 廃止、CommitDag が唯一の WAL）の設計にはまだ「checkpoint からの再開」が無く、それは commit-dag 側も明記の上で未着手 | quad-store が単発コミット済み・commit-dag が chain/検証を追加（本セッション） |
| BlockStore（Memory/Kubo/Budgeted LRU/Tiered/Distributed/B2 cold） | `kotoba-store` | 無し（prolly-tree/quad-store/commit-dag は全て `put!`/`get-fn` 注入で、呼び出し側が store 実体を持つ設計——Memory 実装はテストにのみ存在）。`kotobase-clj/store.cljc`（26行、IStore = doc/stream の汎用ストア。ブロックストアではない）と、B2/DataLad 運用（CLAUDE.md 「大容量バイナリの扱い」）は既に別レイヤで運用中 | 未着手（block-store 抽象なし、注入ポートはある） |
| Datalog / パターンクエリ | `kotoba-query`（Datalog エンジン） | **`kotoba-lang/kqe`（ADR-2607010930 Phase 6）— 実装済み。** `[s p o]` パターン（wildcard 可）を quad-store の 4-index にルーティング（旧 Rust `route_bgp_triples` 相当）。hot（インメモリ）のみ。全 Datalog semi-naive fixpoint・SPARQL-style BGP（multi-triple join, FILTER/OPTIONAL/UNION）は未着手と自己申告 | **部分完了**（triple-pattern のみ。join/fixpoint/SPARQL は未） |
| ブラウザ側クライアント（CID 検証 hydrate） | `KotobaNode.ingestBlock`/`hydrateViaBlocks`（Rust→wasm） | **`kotoba-lang/kotoba-client`（ADR-2607010930 Phase 6）— 実装済み。** 取得した block を再ハッシュして CID 不一致なら reject（信頼できない fetch-block からの汚染を防止）。IPNS レコード署名検証（trustless head 解決）は CACAO 依存の follow-up として未着手と自己申告 | **ほぼ完了**（IPNS 検証 待ち） |
| SPARQL 1.1 | `kotoba-graph::sparql` | 無し（kqe は triple-pattern のみ） | 未着手（README 自身が「auxiliary」と明言 — 優先度低） |
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
    一致を要求する契約が現存しない（旧 Rust サーバも削除済み）ため新規設計で
    よい、という方針のとおり `kotoba-lang/prolly-tree` は独自設計で実装済み
    （SHA-256 下位ビット境界、DAG-CBOR ノード、CID から辿れる）。ただし
    child/commit への参照は CBOR tag-42 の真の IPLD リンクではなく**プレーンな
    CID 文字列**（`cbor.core` が CBOR tag 自体を未実装のため）——honesty note
    として prolly-tree/commit-dag 双方が明記済み。呼び出し側は CID 文字列しか
    見えないため、将来 tag-42 化しても破壊的変更にはならない。
  - CommitDag / Arrangement は ADR-2606041151 の設計（WAL 廃止、CommitDag が
    唯一の WAL）をそのまま仕様として使う。

### 3. フェーズロードマップ

| Wave | 内容 | 前提 | 状態 |
|---|---|---|---|
| **Wave 0（完了済み）** | CID・DAG-CBOR・CACAO 基礎・Signal・ed25519・汎用暗号・datom 形状ヘルパー・gossip/bitswap 断片 | — | 完了 |
| **Wave 1 — 基盤（Prolly Tree + BlockStore）** | ~~`kotoba-lang/prolly`: 決定的チャンキング + path-copy + DAG-CBOR ノード~~ → **`kotoba-lang/prolly-tree` として ADR-2607010930 が既に実装済み**（上表参照）。残るのは `kotoba-lang/block-store`: Memory backend（テスト内にのみ存在するものを独立ライブラリに）→ Kubo HTTP backend → 既存 B2/DataLad 運用との接続 | Wave 0 | **Prolly Tree 完了、BlockStore 抽象化のみ残**（優先度: 中） |
| **Wave 2 — Arrangement + CommitDag** | ~~`kotoba-lang/arrangement`: EAVT/AEVT/AVET/VAET/TEA~~ → **`kotoba-lang/quad-store` として実装済み**（4-index、hot のみ）。~~`kotoba-lang/commit-dag`: parent-linked チェーン~~ → **本セッションで実装・push 済み**（`commit!`/`chain`/`head`/`verify-chain`）。残るのは cold（prolly-tree 経由）query の再構築と、quad-store の単発 `commit!` と commit-dag の chain/検証層を実際に繋ぐ配線（両者は今のところ独立ライブラリのまま） | Wave 1 | **hot Arrangement + commit-dag 完了、cold 再構築・配線が残**（優先度: 高） |
| **Wave 3 — クエリ面** | ~~Arrangement 上の Datalog~~ → **`kotoba-lang/kqe` として triple-pattern query は実装済み**（hot のみ）。残るのは全 Datalog semi-naive fixpoint（再帰ルール・推移閉包）と SPARQL-style BGP（multi-triple join / FILTER / OPTIONAL / UNION）。SPARQL 1.1 自体は README が auxiliary と明言しており優先度を下げる | Wave 2 | **triple-pattern 完了、join/fixpoint/SPARQL が残**（優先度: 中） |
| **Wave 3.5 — ブラウザクライアント** | ~~本 ADR 初稿では未計画~~ → **`kotoba-lang/kotoba-client` として実装済み**（CID 検証付き block ingest/hydrate）。残るのは IPNS レコード署名検証（trustless head 解決、CACAO 依存） | Wave 1 | **ほぼ完了、IPNS 検証が残**（優先度: 中） |
| **Wave 4 — 認可/暗号の配線強化** | `kotoba-lang/cacao` と `kekkai/cacao.cljc` の重複を解消し depth-2 delegation + multi-graph grants に強化。Signal Protocol をグラフの E2E envelope 経路に配線 | Wave 1（並行可） | 未着手（優先度: 中） |
| **Wave 5 — DHT/Pregel（投機的、着手保留）** | Source Chain/Warrant/Neighborhood、Pregel BSP。`net/gossip`+`net/bitswap` の先。**Wave 1–3 が着地し、実際の分散クエリ需要が具体化してから再検討**（先回りして作らない） | Wave 2–3 | 未着手（優先度: 低・保留） |

## Consequences

- (+) `docs/rust-crate-migration.md` の抽象的な「新規挙動は CLJC/EDN を先に」
  という方針を、DB 本体について**具体的な順序付きロードマップ**に落とし込む。
- (+) Wave 0 として既に使える資産（`multiformats`/`dag-cbor`/`mst`/`cacao`/
  `signal`/`crypto`/`ed25519`/`datom`/`store`/`crdt`/`net` の断片）を棚卸しした
  ことで、Wave 1 はゼロからではなく大部分を再利用して始められる。
- (+) Wave 1 着手前に解くべき具体的な設計課題を可視化した——うち (a)(b)(d) は
  `kotoba-lang/prolly-tree`/`kqe`（ADR-2607010930）により既に実質決着済み
  （本 ADR 初稿作成時点でこれらの存在を見落としていたため「未解決」と誤記
  していた。冒頭の追記参照）:
  (a) ~~Prolly チャンキングのハッシュ関数~~ → SHA-256 に決定・実装済み
  （`prolly-tree` が採用）、
  (b) ~~`multiformats` に dag-cbor コーデック（0x71）が無い~~ →
  **これも誤り**: `multiformats.core/cidv1-dag-cbor` は初稿作成時点で
  既に存在していた（読み落とし）、
  (c) `kotoba-lang/cacao` と `kekkai/cacao.cljc` の重複——**未解決のまま**、
  (d) ~~Datalog エンジンの build-vs-adopt~~ → `kqe` が DataScript 等を
  採用せず独自の triple-pattern ルーティングを実装（hot のみ、fixpoint/
  SPARQL は未）。
  加えて新たに判明した課題: (e) `quad-store/commit!` と本セッションの
  `commit-dag` の概念重複（上記 Consequences 参照）。
- (−) 本 ADR は**計画のみ**であり、このコミット自体はコードを一切変更しない。
  複数リポジトリ・複数ヶ月規模の作業であり、各 Wave 着手時に個別 ADR
  （実装内容・テスト結果を記録するタイプ、例: ADR-2607022500 の形式）を積む
  ことを前提とする。
- (+) **本番 `kotobase.net` の実態を確認した(2026-07-02、本セッション)**:
  2026-06-24 に旧 Vultr VKE 上の Rust `kotoba` pod は退役・DNS 削除済み。
  現在のバックエンドは `kotobase.aozora.app`——`{"what":"kotobase pod-less wasm
  worker"}` を返す、**旧 Rust `kotoba` を wasm32 にビルドした
  バイナリ(`kotoba_wasm.{js,wasm}`, 2.2MB)を Cloudflare Worker 内でそのまま
  動かす構成**(ADR-2606231200 pod-less kotobase の「主軸」案が実際に本番稼働)。
  同じ ADR に代替として「純 cljs DataScript」案(`kotobase.engine`、
  `app-aozora/40-engine/cljs/kotobase-engine`、51行の DataScript 薄ラッパー、
  CID/Prolly/永続化は無し)がテスト green の PoC として存在するが、本番には
  採用されていない(parity リスク・工数を理由に見送り)。**つまり本番の
  「データベースエンジン」は今も Rust 由来の wasm バイナリであり、ビルド元
  ソースはもう存在しない。** ここに `kotoba-lang/prolly-tree` +
  `quad-store` + `kqe` + `commit-dag` を接続してカットオーバーするのが
  Wave 1–3 の最終ゴールになる——ただし本番の課金対象サービスであるため、
  カットオーバー自体は本 ADR の範囲外(別途、staging 並走 → parity diff →
  切替という手順を踏む。ADR-2606231200 の Migration 節に既述)。
- (−) SPARQL・DHT・Pregel は明示的に後回し（Wave 3 の一部・Wave 5）とした。
  近い将来に具体的需要が生じた場合はロードマップの優先度を見直すこと。
- (−) `kotoba-git`/`kotoba-rad`、ドメインセル、WASM ランタイム、LLM、Ingest、
  Custody は意図的に本 ADR の対象外とした（既存 ADR か別途 ADR が必要）。
  「kotoba crates を全て CLJC に」という要求に対する**部分的な回答**である
  ことを明記する。
- (−) **本 ADR 初稿の棚卸しは不完全だった**（冒頭の追記参照）。`gh repo list`
  で GitHub 側を確認する前に「ローカル checkout に無い = 存在しない」と
  誤って結論した。訂正後も、この種の並行作業（同じ日に複数セッションが
  重複領域を触る）を検知する仕組みは無い——`kotoba-lang/quad-store/commit!`
  と本セッションの `kotoba-lang/commit-dag` は概念が重なる(`{index-roots
  prev}` vs `{state prev seq}`)。両者の統合(quad-store 側が commit-dag の
  chain/verify を使うようリファクタするか、commit-dag が quad-store の
  フィールド名に合わせるか)は明示的な follow-up として残し、無理に本 ADR
  内で解決しない。

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
- **ADR-2607010930（clj-wgsl-migration）Phase 6** — `kotoba-lang/{prolly-tree,
  quad-store, kqe, kotoba-client}` を実際に landing させた ADR。本 ADR が
  当初見落としていた実装（冒頭の追記参照）。Wave 1–3 の実体はほぼこちら
- `kotoba-lang/prolly-tree` — Prolly Tree 実装（Wave 1、上記棚卸し参照）
- `kotoba-lang/quad-store` — 4-index Arrangement + 単発 commit（Wave 2）
- `kotoba-lang/kqe` — triple-pattern クエリ（Wave 3）
- `kotoba-lang/kotoba-client` — CID 検証付き block ingest/hydrate（Wave 3.5）
- `kotoba-lang/commit-dag`（本セッションで新規実装・push）— chain 走査 +
  改竄/seq-gap 検知（`chain`/`head`/`verify-chain`）。quad-store の単発
  `commit!` との統合は follow-up
- ADR-2606231200（`net-kotobase/docs/adr/`, pod-less kotobase CF Worker）—
  本番 `kotobase.net` の実際のバックエンド構成（Rust 由来 wasm、本セッションで
  確認）とカットオーバー手順の一次設計
- `orgs/kotoba-lang/{multiformats,dag-cbor,mst,cacao,signal,crypto,ed25519,
  datom,store,crdt,net}` — Wave 0 で既に存在する再利用可能な CLJC 資産
- `orgs/kotoba-lang/kekkai/src/kekkai/{cacao,kotoba,store}.cljc` — kotobase.net
  への既存クライアント配線（重複解消対象）
- `net-kotobase`（`gftdcojp/net-kotobase`）— kotobase.net のエッジ Worker
  （`worker/src/edge-app.cljc`、既に CLJS）+ 運用ドキュメント一式
- `app-aozora/40-engine/cljs/kotobase-engine`（`gftdcojp/app-aozora`）—
  DataScript 薄ラッパーの PoC（本番不採用、CID/Prolly/永続化なし）
