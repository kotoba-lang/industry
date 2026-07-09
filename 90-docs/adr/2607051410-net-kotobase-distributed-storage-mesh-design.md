# ADR-2607051410: net-kotobase を分散ストレージ mesh として設計する — Holochain/IPFS/Filecoin 参照設計（accepted・実装は別PR）

**Status**: accepted（2026-07-05、オーナー確認）
**Date**: 2026-07-05
**Deciders**: Jun Kawasaki
**Data SSoT**: `2607051410-net-kotobase-distributed-storage-mesh-design.edn`（本文の構造化コンパニオン）

**Acceptance note**: この ADR が「accepted」を意味するのは**設計方針の確定**
であり、実装の完了ではない。L0/L1（既存流用）以外の全レイヤ・全follow-up
（`kotoba.ledger.memory-time` 抽出含む）は引き続き実装ゼロ。唯一の例外は
`kotobase.peer.availability`（L5寄りの監査プリミティブ）— これは実装され
`kotoba-lang/kotobase-peer` に着地済み（下記「Landed」節）。次の一歩は、
L4の土台である ADR-2607023100（これも未実装）に着手するか、あるいは
`kotobase.peer.availability` を実際に credits/kekkai へ配線する、のいずれ
か — 別PR・オーナー確認の上で進める。

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

## Decision（accepted・実装は別 PR）

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

## Follow-up（2026-07-05）— L6 は新規 bid 機構ではなく既存 credits 台帳への相乗りで足りる

オーナーから「実際に kotobase.net としてこの経済（murakumo 推論経済、
ADR-2607030030・accepted）に参加するには」と問われ調査した結果、
L6(Incentive/market) の当初案（「`cloud-murakumo.scheduler` の
leaderless auction を storage bid へ一般化する」）は過剰設計だったと判明
した。

`kotoba-lang/murakumo` の `src/murakumo/infer/credits.cljc`
（ADR-2607022000 系譜）は、稀少性を **MEMORY×TIME**（shard-bytes ×
run-duration）として定式化した純関数の credits 台帳で、すでに
**Civitai-Buzz 方式の拡張可能な単価マップ** `unit-prices`
（`:tokens`/`:images`/`:video-seconds`/`:audio-seconds`/
`:training-steps`）を持つ。この式は「block-bytes × hold-duration」という
ストレージの稀少性にそのまま一致する — **新しいオークション機構を作らず**、
`unit-prices` に `:gb-months`（または `:byte-seconds`）エントリを1つ足す
だけで、`job-cost`/`settle`/`charge`/`spend`/`receipt`/`balances` は
無改造のまま storage 課金に使える。

**L6 の実装コストは当初想定より大幅に小さい:**

- **需要側**（テナントが払う）: net-kotobase の pin/write path から
  `charge`/`spend` を呼ぶだけ（残高不足は既存 CACAO ゲートと同じ場所で
  402）。
- **供給側**（fleet ノードが稼ぐ）: storage peer が「保持バイト×保持時間」
  を `plan {:assignments [...]}` と同じ shape で報告すれば
  `memory-time`/`settle` がそのまま `:run/shares` を配分。
- **監査/証跡**: `receipt`（hash chain + CACAO 署名）が Filecoin の
  proof-of-storage 相当を提供 — L6 が想定していた「定期サンプル再検証+
  監査 datom」の実体はこれで代替できる（`verify-chain`(L5) との併用は
  引き続き必要、別レイヤのまま）。
- **fiat 導線**: ADR-2607030030「非貢献者は fiat→credits(Stripe)」が
  そのまま net-kotobase の `Stripe active subs 0` ギャップの解消経路になる
  （`90-docs/business/net-kotobase-business-model.md`）。
- **フェーズ整合**: GPU 経済の Phase 1（単一テナント gftd fleet、稼働済み）
  に相乗りする形なら、storage 課金は外部フェーズ(2/3)を待たず内部
  chargeback として即着手可能。外部テナントが自分のノードで貢献できるのは
  Phase 2（fleet 連邦・murakumo.cloud 公開）待ち。

この follow-up は**設計の訂正のみ**（オーナー確認済み、2026-07-05）。
実装（`unit-prices` 拡張、net-kotobase Worker 配線、料率決定）は引き続き
別 PR・別オーナー確認。

## Follow-up（2026-07-05・その2）— 前回 follow-up の過大主張を訂正 + cljc での軽量 retrieval-proof 設計 + 経済圏の分離

前回の follow-up で「`receipt`（hash chain + CACAO 署名）が Filecoin の
proof-of-storage 相当を提供する」と書いたが、これは**不正確**だった。
`receipt` は決済計算とその署名が改ざんされていないことの証明であり、
**申告されたバイト列が今も物理的に保持されているかどうかは何も検証しない**。
ノードは初回 pin 時に本物のブロックを受け取った後、静かに削除しても
`settle`/`balances`/`receipt` はそれを検知できない。加えて、複製の水増し
（1つの物理コピーを複数 replica として申告する — Filecoin の PoRep が
防ぐ攻撃）への対策も現設計には無い。

### 発見: この解決策は一度このプロジェクトで設計されていた（Rust で、失われた）

`gftdcojp/ai-gftd-apps-gftdcojp`（net-kotobase の前身 vendor monorepo）の
ADR-2605252200「Kotoba Token Economy」§5.2 に、まさにこの解が書かれてい
た: 毎 epoch nonce をブロードキャストし、ストレージノードが
`blake3(block_bytes || nonce)` を返し、失敗/無応答なら stake の5%を
slash する「軽量 PoSt」。実装予定だった
`kotoba-dht/src/availability_proof.rs` は、kotoba の Rust ワークスペース
削除に伴い実装されないまま失われた。この旧 ADR は KOTO トークン・staking
という、現行の ADR-2607030030（consensus/L1 を持ち込まない方針）とは
異なる、より重い経済圏を前提にしており、2つの ADR は supersede 関係もな
く静かに分岐したまま残っている。

### cljc での再設計（イラストレーティブ・未着地。実装は別 PR）

blake3 の代わりに、このコードベースで JVM/cljs 両対応が実証済みの
`multiformats.core/sha256` を使う。**正直な限界**: この方式は Filecoin
の PoRep/PoSt（検証者がデータを一切持たずに検証できる、SNARK による）
ではない。検証者は**自分も同じバイト列を持っている**（別の replica 保持
ピア・テナント自身の保持コピー・L3 の provider-list から選ばれる巡回監
査役）ことが前提で、その上で「今も持っているか（鮮度）」を、フルデータ
の再転送なしにハッシュ1つで確認する、というより軽い trade-off。kekkai
によって参加者が既知の身元に限定されていることと、耐久性のためにすでに
replication-factor≥2 が要求されることを理由に、この軽量化は正当化でき
る。

```clojure
(ns kotobase.peer.availability
  "Lightweight, salted-hash proof-of-retrievability for pinned blocks —
   a periodic challenge/response BETWEEN REPLICA-HOLDING PEERS (not a
   trustless prover-only scheme). Redesigned in cljc from the
   pre-Rust-deletion kotoba-dht/availability_proof.rs sketch (legacy
   ADR-2605252200 §5.2, 'lightweight PoSt'); reuses this codebase's own
   portable SHA-256 (multiformats.core/sha256, real JVM+cljs parity)
   instead of introducing blake3.

   HONEST SCOPE: NOT Filecoin's PoRep/PoSt. The verifier must already
   hold the same bytes (another replica peer, the tenant's own kept
   copy, or a rotating auditor from L3's provider-list) to recompute
   the expected hash — this proves ongoing possession/freshness
   between two parties who both once had the data, at the cost of one
   hash instead of a full re-transfer. Justified because kekkai gates
   membership to known identities and durable pins already require
   replication >= 2 for other reasons (see redundancy-tiers)."
  (:require [multiformats.core :as mf]))

;; mirrors legacy ADR-2605252200 §5.1 pricing — only tiers with >=1
;; OTHER replica holder can run this audit at all.
(def redundancy-tiers
  {:volatile {:replicas 1 :availability-proof? false}   ; base price, unauditable
   :standard {:replicas 3 :availability-proof? false}   ; replicated, unaudited
   :sla      {:replicas 5 :availability-proof? true}})  ; replicated + audited, premium

(defn challenge
  "Verifier -> one challenge for one (node, cid) pair. `nonce` is
   caller-supplied randomness (host chooses the source; this stays a
   pure fn of its inputs, matching this codebase's Date.now/random
   discipline elsewhere)."
  [cid nonce epoch]
  {:kotobase.availability/cid cid
   :kotobase.availability/nonce nonce
   :kotobase.availability/epoch epoch})

(defn- salted-hash [block-bytes nonce]
  (mf/hexify (mf/sha256 (byte-array (concat block-bytes nonce)))))

(defn prove
  "Storage node's response, via the SAME get-fn seam kotobase-peer.core
   /commit! and hot-datoms already use. Returns nil (not a fabricated
   proof) if the node lacks the block — fails closed, same discipline
   as verify-chain/quota-exceeded?."
  [get-fn {:kotobase.availability/keys [cid nonce]}]
  (when-let [block-bytes (get-fn cid)]
    {:kotobase.availability/cid cid
     :kotobase.availability/proof (salted-hash block-bytes nonce)}))

(defn verify
  "Verifier resolves `cid` through ITS OWN get-fn (its own replica) and
   recomputes the salted hash — only the compact `proof` crosses the
   wire from the node under audit, not the block itself. Never throws;
   fails closed on a missing local replica or malformed response."
  [verifier-get-fn {:kotobase.availability/keys [cid nonce]} node-response]
  (let [local-bytes (verifier-get-fn cid)]
    (cond
      (nil? local-bytes) :verifier-lacks-replica
      (nil? node-response) :missed
      (not= (:kotobase.availability/cid node-response) cid) :malformed
      (= (:kotobase.availability/proof node-response) (salted-hash local-bytes nonce)) :ok
      :else :failed)))

(defn audit-outcome
  "One epoch's result for one (node, cid) pair -> pure decision record.
   Caller wires :ok into the storage-economy settle/credit path and
   :failed/:missed into kekkai membership standing — this ns holds no
   opinion on credits or admission, same stance kotobase-peer already
   takes on CACAO (its own README: 'no auth opinion of its own')."
  [node cid epoch verdict]
  {:audit/node node :audit/cid cid :audit/epoch epoch :audit/verdict verdict})
```

この ns の置き場所は未決（open question に追加、下記）— `kotobase-peer`
自身に足すか、`kotobase-peer` の「CACAO/capability auth に opinion を持
たない」という既存の狭いスコープ方針に倣い、別の小さな sibling repo
（kotoba-lang の他の単機能 repo と同型）にするか。

### 経済圏: 分離を推奨（`murakumo.infer.credits` の核だけを共有）

kotobase.net（graphdb storage）と murakumo.cloud（computing cloud）は、
資源の物理的性質（有界イベント vs 継続保有）・事業境界（GPU 経済の
treasury/価格変動と storage の regulated テナント会計を混ぜたくない）・
監査プロセス（compute は無しで足りる、storage には上記の定期監査が要る）
が異なるため、**台帳/feed/treasury は分離**し、**決済の数式
（memory-time 按分）と身元/transport（CACAO・kekkai・kotoba-lang/net・
overlay）だけ共有**するのが適切と判断する。

```clojure
;; kotoba-lang/ledger（新規・最小）— murakumo.infer.credits の核(memory-time
;; 按分)を "推論" から切り離しただけ。cloud-murakumo は自分の feed/treasury
;; でこのまま使い続け、net-kotobase は自分専用の feed/treasury で使う。
(ns kotoba.ledger.memory-time)

(defn settle
  "汎用 memory-time 決済(GPU shard-seconds でも storage byte-seconds でも
   同じ式)。assignments = [{:node :est-bytes :span} ...]。呼び出し側が
   head-frac/protocol-frac/価格レジストリを自分の経済圏の値で渡す。"
  [{:keys [job-cost duration-ms assignments head-frac protocol-frac]}]
  ,,,) ; murakumo.infer.credits/settle と同じ本体、:model への依存だけ外す
```

`net-kotobase` はこの `settle` を呼びつつ、独自の `unit-prices`
（`:gb-months`）・独自 treasury 比率・独自の kekkai storage-peer 入会条
件・上記 `kotobase.peer.availability` による監査を持つ。GPU 経済側
（`murakumo.infer.credits`、`cloud-murakumo`）は無改造のまま。

この follow-up も設計の訂正・追加提案のみ（オーナー確認済み、
2026-07-05）。実装（`kotoba.ledger.memory-time`/`kotobase.peer.
availability` の新規抽出・net-kotobase 配線・料率決定）は引き続き別 PR・
別オーナー確認。

## Landed（2026-07-05）— `kotobase.peer.availability` merged

`kotoba-lang/kotobase-peer#3`（`feat/availability-proof` →
`main`、merge commit `042cf7fdb7d2be6c6d835507ad0cd6d058ee7c43`）が
merge 済み。`src/kotobase_peer/availability.cljc` +
`test/kotobase_peer/availability_test.cljc` — 上記 cljc 再設計をそのまま
実装したもの。テストは JVM(`clojure -M:test`) と実 Node.js
(`shadow-cljs compile test`) の両方で 35 tests / 88 assertions, 0
failures を確認済み。**まだ standalone** — `commit!`/`hot-datoms` や
どの Worker にも配線されていない。superproject の
`manifest/west.yml` の kotobase-peer pin もこの commit に前進済み。

8観点(correctness×3, reuse, simplification, efficiency, altitude,
CLAUDE.md規約)のadversarial reviewを実施、verify(1票)を経て以下が
survived — いずれもマージを止めるほどではないが、次にこの ns を実配線
する前に潰しておくべき follow-up:

- **[correctness, confirmed]** `verify` の docstring は「Never
  throws」と主張するが、`verifier-get-fn` が非バイト型データを返すと
  `bytes-concat` 内で `ClassCastException`/`TypeError` が実際に飛ぶ
  （`get-fn` の「bytesを返す」契約はこのコードベース全体でprose-onlyで
  runtime強制が無いため到達可能）。fail-closedにするか、docstringを
  正直に書き直すか。
- **[correctness, plausible]** `nonce`/`block-bytes` が `nil` でも
  検証エラーにならず、静かに freshness 保証のない hash に劣化する。
  現状呼び出し元ゼロなので未発火だが、実配線前に防御チェックを足すべき。
- **[efficiency, confirmed]** `bytes-concat` の `:clj` 分岐が
  `(byte-array a)`/`(byte-array b)` で既に正しい型の入力を毎回コピーし
  直しており、実測で素の `aclone` の約40倍遅い。無条件coercionをやめ、
  型ガード付きにすべき。
- **[reuse, confirmed]** `bytes-concat` は `kotoba-lang/pqh`(`util/
  concat-bytes`)・`kotoba-lang/knp`(`packet.cljc`)等に既に存在するのと
  同じロジックの再実装。ただし `pqh` は現状 `kotobase-peer` の依存では
  ないため、「再利用」は新規cross-repo依存を足すコストとのトレードオフ。
- **[simplification, confirmed]** `redundancy-tiers` はこのPR内で一切
  参照されない dead data。実際に tier gating を強制するか、次PRまで
  削るか。
- **[cosmetic, confirmed]** `challenge`/`prove`/`verify` は
  `:kotobase.availability/*`、`audit-outcome` は `:audit/*` と、同一
  ファイル内でキーワード名前空間の流儀が割れている。

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
