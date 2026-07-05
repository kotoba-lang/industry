# ADR-2607051410: net-kotobase を分散ストレージ mesh として設計する — Holochain/IPFS/Filecoin 参照設計（proposed・未実装）

**Status**: proposed
**Date**: 2026-07-05
**Deciders**: Jun Kawasaki
**Data SSoT**: `2607051410-net-kotobase-distributed-storage-mesh-design.edn`（本文の構造化コンパニオン）

## Context

`net-kotobase`（`kotobase.net`）は現在 **単一障害点構成** で動いている:
外部書き込みは Cloudflare Worker(`worker/src/app.cljc`) 1枚のみが受け付け、
`kotoba-backend.gftd.ai` トンネル越しに**単一の kotoba pod**（S1-locked、
Kubernetes 上）へ転送し、pin は CAR 化して**単一の B2 バケット**へ archive
する（`net-kotobase/README.md` Architecture 節）。ノード間の複製・ゴシップ・
DHT は一切ない — 「分散」なのは CID による内容アドレスとテナント分離だけで、
可用性・耐久性の実体は「1 Worker + 1 pod + 1 B2」に閉じている。

一方 `cloud-murakumo` ファミリー（GPU 計算側）は、同じ悩み（単一障害点な
制御面）に対してすでに実装/提案済みのレイヤ群を持つ:

- **`murakumo.kekkai`**（zero-trust admission 台帳。Tailscale 相当）—
  「このノードを mesh の一員として扱ってよいか」をゲートする membership 層。
- **`murakumo.overlay`**（JVM Kwik QUIC + relay。Tailscale/WireGuard 置換）—
  実際のノード間トランスポート。
- **`kotoba-lang/net`**（旧 Rust `kotoba-net`/libp2p の意味論だけを cljc 純
  関数として再実装したもの）— `kotoba.net.gossip`(peer/topic dedup・決定的
  fanout・route-message) と `kotoba.net.bitswap`(want/have・`WantSince`
  delta-sync)。**トランスポート非依存**で、現状 consumer ゼロ
  （ADR-2607023100 時点）。
- **`cloud-murakumo.scheduler`** の **leaderless auction**（bid → placement、
  `:murakumo.run`/`:murakumo.audit` 監査 datom）— GPU 在庫を bid 対象にした
  もの（`gftdcojp/cloud-murakumo` README）。

そして ADR-2607023100 は「`kotoba-lang/net` の gossip/bitswap を
`murakumo.overlay` の QUIC トランスポート上に native adapter として接続す
る」設計を**すでに proposed 済み**（未実装）。本 ADR は**この同じレイヤ積み
を net-kotobase のストレージ側に転用する**ことを提案する — 新しいプロトコ
ルは発明しない。

さらに net-kotobase 自身、実はすでに Holochain/IPFS 的な部品を持っている
ことに気づいた（今回の調査で判明）:

- **`kotobase-peer`** — テナントごとのコミットチェーン
  (`kotobase/db/<did>/<db-name>`) は、prolly-tree + arrangement + chain +
  IPLD で構成された**content-addressed かつ検証可能な commit chain**。
  JVM/cljs で byte-identical CID を出す。これは Holochain の
  "agent-centric source chain" にすでに一致する形をしている。
- **`kotobase-browser-worker`** — `kotobase-cljc-worker`(Cloudflare/R2) と
  **同じ純粋ハンドラ**を IndexedDB に配線し、ブラウザタブ単独で
  `transact`/`datoms`/`q`/`pull`/`fold` が完結することを実証済み
  （README: 「ネットワーク往復が要るのは federation の同期のためであって
  正しさのためではない」）。
- **`kotobase-client`** — did:key + CACAO 自己発行、DAG-CBOR、
  client/server 共通のバイト完全一致実装。

つまり **identity・content-addressing・per-agent source chain・ローカル
ファースト実行** の4つはすでに実在する。欠けているのは「ノード間の発見・
複製・検証・インセンティブ」という**mesh 化そのもの**。

## Decision（設計提案のみ・実装は別 PR）

net-kotobase を単一 origin 構成から、`cloud-murakumo` 陣営と同じレイヤ積み
を再利用した **kotobase mesh** に段階移行する。レイヤと、それぞれ「何を
流用するか／何が正味の新規か」を明記する（詳細は `.edn` の
`:layer-design`）:

| Layer | 役割 | 流用元（既存） | 正味の新規 |
|---|---|---|---|
| L0 Identity/Membrane | 鍵・参加資格 | `kotobase-client/cacao`(did:key+CACAO)、`murakumo.kekkai` パターン | kekkai 台帳を storage-peer membership にも使う配線 |
| L1 Data model | content-addressed per-tenant chain | `kotobase-peer`(prolly-tree/arrangement/chain/IPLD) | なし（既存そのまま） |
| L2 Local storage | 各ノードのブロック実体 | `kotobase-cljc-worker`(R2)、`kotobase-browser-worker`(IndexedDB) | fleet ノード用ローカルディスク shell（新規、同じ純粋ハンドラを再配線するだけ） |
| L3 Discovery | 「CID X を誰が持つか」 | kekkai membership リストを暫定 provider-list に流用 | 本格 DHT は不要(現規模)。将来ノード数が増えたら Kademlia 化 |
| L4 Replication | ノード間複製プロトコル | `kotoba-lang/net`(gossip dedup/fanout + bitswap want/have/delta-sync)、`murakumo.overlay`(QUIC) | ADR-2607023100 の配線パターンをストレージ CID にも適用する薄いアダプタ |
| L5 Validation | 受信ブロックの検証 | `kotobase-peer/verify-chain` | 新規: bitswap 受信時に verify-chain + tenant quota/schema を通してから replica set に取り込む（Holochain の validating-DHT 相当） |
| L6 Incentive/market | 複製の経済層 | `cloud-murakumo.scheduler` の leaderless auction、`:murakumo.audit/*` 監査 datom パターン | 新規: storage bid（$/GB-month）+ 定期サンプル再検証を `:kotobase.storage.audit/*` に記録（Filecoin の deal + PoSt 相当） |
| L7 Edge/API | 外部公開面 | 現行 `kotobase.net` Worker | Worker を「唯一の origin」から「mesh 前段のゲートウェイ/キャッシュ」へ役割変更 |

### Holochain / IPFS / Filecoin の対応（詳細は `.edn` `:reference-mapping`）

- **IPFS**: content addressing・Merkle DAG(`kotobase-peer`)・bitswap
  意味論(`kotoba-lang/net`)は既にある。欠けているのは provider record
  discovery(L3) のみ。
- **Holochain**: agent-centric source chain は `kotobase/db/<did>/<db-name>`
  がすでにそれ。欠けているのは受信ブロックを盲目的にミラーせず検証してから
  hold する validating-DHT 的な層(L5)。capability token は CACAO を転用。
- **Filecoin**: content addressing 側は揃っているが、**deal(誰が何を
  いくらでどれだけ保持するか)と proof-of-storage(本当にまだ持っているか
  の定期証明)が丸ごと欠けている**(L6)。ここは `cloud-murakumo.scheduler`
  の bid auction を GPU からストレージへ一般化するだけで新しい機構を作らず
  に済む。

### 移行フェーズ（非破壊・段階的、詳細は `.edn` `:migration-phases`）

0. 現行 Worker+pod+B2 はそのまま **peer #1（bootstrap peer）** として残す。
1. `kotoba-lang/net` の gossip/bitswap を `murakumo.overlay` へ配線
   （ADR-2607023100 をそのまま実行 — 本 ADR は新規に何も足さない、既存
   proposal の実装待ちに乗るだけ）。
2. Mac-mini fleet ノードに L2 shell（新規、`kotobase-cljc-worker` の
   R2 部分をローカルディスクに差し替えるだけ）を立て、fleet ノードを
   compute peer だけでなく **storage peer** にもする。
3. L3(discovery) は当面 kekkai membership リストで代用。ノード数が
   静的リストで足りなくなった時点でのみ本格 DHT を検討する。
4. L5(validation) と L6(incentive/market・監査) を追加し、
   「B2 を信じるだけ」だった `pinned CID 数`/`storage GB` メトリクス
   （`docs/BUSINESS-MODEL.md`）を実測の複製証跡に置き換える。
5. （長期・任意）`kotobase-browser-worker` をオフラインファーストな
   軽量/edge replica として mesh に参加させる。ネットワークの glue
   (L3/L4)さえ足せば、エンジン自体はすでに証明済み。

## Open Questions（実装 PR で解く）

1. L3 の DHT 化タイミング — 静的 kekkai リストで実運用上十分な規模はどこまでか。
2. L5(validation) の置き場所 — `kotobase-peer` 自身に足すか、
   `kotoba-lang/net` 側の bitswap 受信フックとして足すか。
3. L6(storage bid) のデータ形状 — `:murakumo.fn/*`(GPU) と同じ shape を
   共用するか、`:kotobase.storage.bid/*` として独立させるか。
4. Org 境界 — `net-kotobase` README の「kotobase/IPFS pinning は gftd
   vendor 機能、kotoba 自体は etzhayyim 製品」という区分
   （ADR-2606011400）に対し、L3/L5/L6 の新規コンポーネントはどちらに
   属するか（`murakumo`/`cloud-murakumo` の役割分離と同型の整理が要る）。
5. 命名 — ADR-2607041302（`cloud-murakumo-fleet`→`local-murakumo`）と同じ
   教訓: 「kotobase.net という製品」と「その下で動く分散ストレージ
   プロトコル/mesh」を同一視しない方がよい。mesh 自体に独立した名前が
   要るか（例: `kotoba-lang/net` 同様、プロトコル層は kotoba-lang 側に
   置き、`net-kotobase` は依然として gftd 側の製品名のままにする、等）。

## Consequences

**Positive**

- 新しい P2P プロトコルを何一つ発明しない — identity(CACAO)・
  data-model(kotobase-peer)・gossip/bitswap(kotoba-lang/net)・
  transport(murakumo.overlay)・auction(cloud-murakumo.scheduler) を
  すべて他ドメインで検証済みの実装からそのまま借用する。
- ADR-2607023100 にとって2つ目の実 consumer になり、「kotoba-lang/net は
  transport 非依存の汎用意味論である」という設計意図がストレージ/計算
  両ドメインで検証される。
- net-kotobase の business model が抱える「pin/storage サービスに
  provenance がない」という Problem（`docs/business/net-kotobase-
  business-model.md`）に対し、L6 の監査証跡が直接的な答えになる。

**Negative / 制約（honest）**

- 本 ADR は設計のみ。L2(fleet storage shell)・L3(discovery)・
  L5(validation)・L6(bid+audit) はいずれも実装ゼロ、別 PR で行う。
- L4 の土台である ADR-2607023100 自体もまだ未実装 — 本 ADR はその上に
  積む提案であり、両方が実装されるまで実効的な分散ストレージにはならない。
- fleet ノードがストレージ役も担うことで、ディスク容量・帯域の運用負荷が
  増える（「大容量バイナリの扱い」節の DataLad/B2 運用と同様、shallow/
  容量管理の検討が要る）。
- L6(storage bid) が実装されるまで、`docs/BUSINESS-MODEL.md` の
  storage 系メトリクスは現状の「B2 を信じる」ままであり続ける。

## Related

- ADR-2607023100（murakumo × kotoba-lang/net gossip/bitswap 統合設計。
  本 ADR の L4 が直接前提にする）
- ADR-2607041302（murakumo family 命名整理。本 ADR の open question 5 が
  同じ教訓を参照）
- `90-docs/business/net-kotobase-business-model.md`（pin/storage の
  provenance 欠如という Problem。本 ADR の L6 が対応）
- `orgs/gftdcojp/net-kotobase/README.md`（現行の中央集権アーキテクチャ）
- `orgs/kotoba-lang/kotobase-peer/README.md`（L1 data model の実体）
- `orgs/kotoba-lang/kotobase-browser-worker/README.md`（L2 のローカル
  ファースト実行が既に証明済みという根拠）
