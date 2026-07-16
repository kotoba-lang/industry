---
id: adr-2606271600-kotoba-stack-equivalences
title: "ADR-2606271600: kotoba ≅ wasmCloud⊗Spin⊗clj⊗Datomic⊗Radicle / murakumo ≅ wadm⊗wash"
status: proposed
doc_type: adr
topic: architecture-positioning
authoritative: true
last_verified: 2026-06-27
authoritative_for:
  - kotoba を「5技術の統合」ではなく「CID+Datom+WASM の1基板の5つの顔」として位置づける語彙
  - kotoba の分散モデルが wasmCloud(=Holochain)流の leaderless lattice を採用し Spin/SpinKube の中央(k8s)分散を不採用とする決定の参照点
  - murakumo(com-junkawasaki) を kotoba mesh の制御面 = wadm/wash 相当として定義
  - 2つの murakumo(kotoba WASM mesh vs etzhayyim k3s LangGraph cell)の曖昧性解消
related:
  - orgs/kotoba-lang/kotoba                                  # 基板本体(crates 群)
  - orgs/kotoba-lang/kotoba/docs/ADR-kotoba-mesh-wasm-hosting.md  # 出自(wasmCloud/Spin/Holochain)を明示した一次設計
  - orgs/kotoba-lang/kotoba/crates/kotoba-lattice            # lattice 制御面(protocol/reconcile/auction)
  - orgs/kotoba-lang/kotoba/crates/kotoba-net               # libp2p QUIC/Noise/gossipsub/Kademlia
  - orgs/kotoba-lang/kotoba/crates/kotoba-auth              # CACAO link = 署名済み grant datom
  - orgs/kotoba-lang/kotoba/crates/kotoba-runtime           # WASM Component host + trigger(on-http/on-tick/on-kse)
  - orgs/kotoba-lang/kotoba/crates/kotoba-clj               # Clojure/EDN サブセット → WASM
  - orgs/kotoba-lang/kotoba/crates/kotoba-datomic           # Datom log / CommitDag(DistributedDatomCommit)
  - orgs/kotoba-lang/kotoba/crates/kotoba-query             # KQE Datalog / 4-index Arrangement
  - orgs/kotoba-lang/kotoba/crates/kotoba-git               # git DAG → content-addressed blocks(SHA↔CID)
  - orgs/kotoba-lang/kotoba/crates/kotoba-dht               # Source Chain / Warrant / Neighborhood(Holochain)
  - orgs/etzhayyim/root/80-data/kotoba-rad                      # *.identity.journal.edn(ノード主権 identity journal)
  - orgs/kotoba-lang/murakumo                               # kotoba mesh 制御面(= wadm/wash 相当)
supersedes: []
superseded_by: []
---

# ADR-2606271600: kotoba ≅ wasmCloud⊗Spin⊗clj⊗Datomic⊗Radicle / murakumo ≅ wadm⊗wash

**Status**: proposed
**Date**: 2026-06-27
**Deciders**: Jun Kawasaki

## Context

kotoba を外から見ると「**wasmCloud / Spin / Clojure / Datomic / Radicle を統合した
もの**」に見える。実際この対比は的を射ているが、放っておくと 2 つの混乱を生む:

1. **「言語なのか DB なのか」問題** — kotoba は `kotoba-clj`(Clojure→WASM の言語
   フロントエンド)であり、同時に `kotoba-datomic`(不変 Datom DB)でもある。どちらが
   本体かが曖昧になる。
2. **「分散がどう整理されているか」問題** — kotoba 自身が分散(libp2p / lattice /
   CommitDag)を持ち、その上に `murakumo` という別の分散制御がいる。2 つの「分散」と
   2 つの「murakumo」(kotoba WASM mesh / etzhayyim k3s)が同じハードに同居し、関係が
   見えにくい。

本 ADR は、これを **5 技術への対応表**で固定し、混乱を語彙レベルで解消する。一次
設計は `kotoba/docs/ADR-kotoba-mesh-wasm-hosting.md`(出自を wasmCloud / Spin /
Holochain の 3 系統と明示)であり、本 ADR はその positioning を superproject の SSoT
として要約・拡張する。

## Decision

### 1. kotoba は「統合」ではなく「1 基板の 5 つの顔」

kotoba は 5 プロダクトを束ねたのではない。**CID(content-addressed) + Datom +
WASM Component という 1 枚の基板**の上に、各技術の役割が汎用プリミティブとして
再現されている。一次 ADR の言葉では「もう半分できている」— 足りないのは制御面
(wadm 相当)と trigger 層だけ。

| 技術 | 担う役割 | kotoba 側の実体 | 基板による置き換え |
|---|---|---|---|
| **wasmCloud** | 分散制御面 — lattice / auction / capability link | `kotoba-lattice` + `kotoba-net` + `kotoba-auth` | NATS → **gossipsub** / OCI registry → **CID・bitswap** |
| **Spin (SpinKube)** | component + trigger + DX | `kotoba-runtime` + trigger `on-http`/`on-tick`/`on-kse`/`datom-Δ` | spin.toml → **EDN→datom** / SDK → `kotoba-guest`(WIT `kotoba:kais`) |
| **Clojure (clj)** | 言語フロントエンド | `kotoba-clj`(Clojure/EDN サブセット → WASM core/component) | ソースコード = そのまま content-addressed component |
| **Datomic** | 不変 DB / as-of / Datalog | `kotoba-datomic` + `kotoba-query`(KQE, 4-index EAVT/AEVT/AVET/VAET) + CommitDag | SQL peer → **Datom on CID/ProllyTree** |
| **Radicle** | P2P 主権 git / ノード identity / source chain | `kotoba-git`(git DAG→CID) + `kotoba-dht`(Source Chain/Warrant/Neighborhood) + `kotoba-rad`(`*.identity.journal.edn`) | 中央 forge 無し / CID gossip / DID 由来 identity |

帰結として「**言語 vs DB**」の緊張は消える: `kotoba-clj`(言語=入力面)と
`kotoba-datomic`(DB=永続面)は 5 つの顔のうちの 2 つにすぎず、**両方とも CID+Datom に
落ちる**ため対立しない。

### 2. 分散モデル — wasmCloud 流を採用、Spin の中央(k8s)分散は不採用

一次 ADR の不変条件(no-central-master)に従い、分散の出自を使い分ける:

- **データ平面の分散** = wasmCloud lattice ⊗ Holochain。leaderless な gossipsub
  **auction**(各ノードが入札、中央キュー無し)、CID/bitswap でのアーティファクト配布、
  `kotoba-dht` の source chain。
- **DB の分散** = Datomic 流の **CommitDag**(`DistributedDatomCommit` が
  canonical/accountability chain。`import_commit` で peer 間 sync)。
- **Spin / SpinKube から採るのは trigger と単一アプリ DX のみ**。その分散像は
  k8s 制御面に依存する＝中央管理前提で no-central-master と衝突するため、
  **ネットワーク制御面の参照には採らない**(DX 表層だけ借用する)。

> 一言で: **kotoba は wasmCloud の*分散*と Spin の*DX*を採り、Spin の*分散*を捨てた。**

### 3. Security posture — Radicle 型 selective replication だけに秘密を置かない

Radicle の private repository は、Git/RID/identity による content-addressing と
allow-list による selective replication が中核であり、**暗号化 at rest の境界ではない**。
これは「信頼 peer にだけ複製する」アクセス制御であって、seed node / 許可 peer が侵害されると
平文 repository は読まれる。kotoba は Radicle protocol 互換を目指すのではなく同じ役割を
CID+Datom+DHT で再現するため、private data の境界は最初から **object encryption** に置く。

決定:

- **public / shareable metadata**: CID、Datom、CommitDag、capability policy、identity journal。
- **private content**: 常に envelope encryption。DHT / untrusted peer / B2 / local clone へは
  暗号文を複製できる設計にする。
- **CID の分離**: `ciphertext-cid = hash(ciphertext)` を複製・取得の主キーにし、
  `plaintext-cid = hash(plaintext)` は検証用 metadata として権限内に閉じる。
- **access control**: capability datom + recipient set + epoch key。revocation は既存暗号文を
  消せない前提で、新 epoch への key rotation と future writes の遮断で扱う。
- **crypto agility**: `alg` / `hash` / `sig` / `kem` / `key-id` / `recipient-set` /
  `epoch` / `created-at` を identity journal または storage manifest の datom に明示し、
  hash・署名・KEM の移行をデータモデルで表現できるようにする。
- **post-quantum stance**: harvest-now-decrypt-later を前提に、長期秘匿データは将来
  `X25519 + ML-KEM`、`Ed25519 + ML-DSA/SLH-DSA` のような hybrid KEM / hybrid signature へ
  移行できる形で置く。現行 GPG/git-annex は実運用の at-rest 暗号、kotoba は次世代の
  algorithm-agile envelope を設計面で受ける。

`age` / `sops` / `gpg` の位置づけ:

| ツール | kotoba での位置づけ | 採用境界 |
|---|---|---|
| `gpg` / OpenPGP | 現行 DataLad/git-annex の at-rest 暗号 | 大容量・証跡・warehouse の実運用 |
| `age` | シンプルな file/blob envelope の候補 | 小〜中規模 secret blob。標準 age 単体は PQ ではないため長期秘匿は hybrid 化が必要 |
| `sops` | 構造化 secret config の管理 | YAML/JSON/EDN 相当の設定値。大容量 blob には使わない |
| kotoba envelope | CID/Datom/DHT と一体化した object encryption | untrusted mesh へ暗号文を撒くための将来 SSoT |

したがって、kotoba の security target は Radicle private repo より強く置く:
**trusted peer だけに平文を配る**のではなく、**暗号文なら untrusted mesh にも配れる**ことを
第一原則にする。

### 4. murakumo(com-junkawasaki) ≅ wadm + wash

一次 ADR が「唯一の gap」と呼ぶ L4(lattice 制御面)/ L5(宣言的アプリ reconciler)を
ターミナルから駆動する operator が `com-junkawasaki/murakumo`。対応は明確:

```
murakumo : kotoba  ≅  wash + wadm : wasmCloud
```

- `kotoba.app.edn`(EDN) → desired datom → gossipsub auction(= wadm reconciler)
- `provision / up·down / status / deploy`(= wash CLI 体験。CID push + EDN deploy)
- ノードに常駐エージェントは入れない。各ノードは `kotoba-server` を macOS
  LaunchAgent として常駐させ、murakumo は Tailscale SSH 越しに束ねる。

### 5. 2 つの murakumo の曖昧性解消

| | **murakumo (com-junkawasaki)** | **murakumo (etzhayyim-project-murakumo)** |
|---|---|---|
| 束ねる対象 | kotoba **WASM mesh**(libp2p lattice + WASM component) | **LangGraph/Pregel cells**(religious-corp) |
| 制御基板 | babashka/clj + Tailscale SSH + LaunchAgent | **k3s-on-Lima + Ansible** |
| 分散モデル | **wasmCloud/wadm 型**(leaderless, gossipsub) | **Spin/SpinKube 型**(中央 k8s 制御面) |
| 本 ADR との関係 | kotoba mesh が *採った* 分散の制御面 | kotoba mesh が *捨てた* 中央分散モデルの実例 |

両者は同じ Mac mini fleet(12 部族ノード名)上に **別ポート・別 substrate** で
同居する。名前とノード名が同じため混同しやすいが、片方は WASM lattice 層、
もう片方は k3s/LangGraph 層。

## Consequences

**Positive**

- 「kotoba は言語か DB か」「分散が二重では」という頻出の混乱に、5 技術の語彙で
  一意に答えられる(= 本 ADR を参照すれば済む)。
- `murakumo : kotoba = wadm/wash : wasmCloud` という対応で、murakumo の設計判断
  (宣言的 desired state は datom、CLI 体験は wash 準拠)が一次設計から導ける。
- 2 つの murakumo を「採った分散 / 捨てた分散」という対比で恒久的に区別できる。
- security boundary が明確になる。Radicle 的な selective replication は発見・同期の
  制御面として使い、長期秘匿は object encryption + recipient set + epoch key に寄せる。

**Negative / 制約(honest)**

- fleet 横断の単一 lattice auction(cross-node peering)は **未配線**。現状は各ノードが
  独立した単一ノード lattice。`KOTOBA_BOOTSTRAP_PEERS` の wiring + murakumo `--peers`
  render が次の作業(一次 ADR の "Next" / murakumo README の "Status")。
- 「Radicle」対応は概念的なもの。kotoba は Radicle の rad プロトコル実体を実装して
  いるわけではなく、git DAG の content-addressing(`kotoba-git`)+ source chain
  (`kotoba-dht`)+ identity journal(`kotoba-rad`)で *同じ役割* を満たしているという
  対応関係。
- post-quantum 対応は現時点では設計要件であり、実装済み crypto suite ではない。現行の
  DataLad/git-annex は GPG hybrid encryption に依存するため、長期秘匿データは将来
  PQ/hybrid envelope への再暗号化 migration を前提に保全する。
- この対応表は positioning(理解のための地図)であって crate 境界の規範ではない。
  実装上の SSoT は各 crate と `kotoba/docs/ADR-kotoba-mesh-wasm-hosting.md`。

## References

- `orgs/kotoba-lang/kotoba/docs/ADR-kotoba-mesh-wasm-hosting.md` — 出自を
  wasmCloud(制御面) / Spin(component・trigger・DX) / Holochain(agent 主権)の 3 系統と
  明示した一次設計。L0–L3 完成 / L4–L6 が gap、no-central-master 不変条件。
- `orgs/kotoba-lang/kotoba/CLAUDE.md` — crate 一覧と一行定義
  `KOTOBA ≝ Datom[CID/T] × EAVT[KSE Topic] × Pregel[BSP] × Datalog[Δ] × CACAO ×
  AT Protocol × LLM/Weight × WASM/WIT`。
- `orgs/kotoba-lang/murakumo/README.md` + `fleet.edn` — kotoba WASM mesh の
  制御面。2 つの murakumo の区別を明記。
- `orgs/kawasakijun/docs/adr/0007-repo-reorg-orgs-layout-and-b2-persistence.md` —
  現行の DataLad/git-annex + B2 + GPG hybrid encryption 運用。
- NIST FIPS 203/204/205 — ML-KEM / ML-DSA / SLH-DSA。post-quantum 移行時の
  hybrid KEM / signature 候補。
