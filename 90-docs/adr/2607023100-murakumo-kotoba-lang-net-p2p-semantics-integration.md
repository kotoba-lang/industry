# ADR-2607023100: murakumo を kotoba-lang/net の gossip/bitswap 意味論の上に — murakumo.overlay を「native adapter」として接続する設計（proposed・未実装）

**Status**: proposed
**Date**: 2026-07-02
**Deciders**: Jun Kawasaki

## Context

`kotoba-lang/net`（旧 Rust クレート `kotoba-net`＝libp2p QUIC+Noise+GossipSub+
bitswap の削除後、その**意味論だけ**を cljc で再実装したもの。
`docs/ADR-kotoba-net-p2p-semantics.md`、2026-07-01 accepted）は:

- `kotoba.net.gossip` — peer/topic bookkeeping、content-hash dedup（bounded
  seen-cache）、**決定的**fanout 選択、`route-message`
- `kotoba.net.bitswap` — want-list/have-list、`respond-to-want`
  （want∩have）、`WantSince` delta-sync

を純関数として提供する。**トランスポート（QUIC/WebRTC/Noise/NAT越え/wire
framing）は明示的にスコープ外**で、ADR 本文は「将来の native adapter（JVM NIO/
QUIC library、または Rust/Go サイドカー）」がこの契約の上に乗ることを想定して
いる。**現状の consumer はゼロ**（`deps.edn` は空、org 内どこからも参照なし）。

一方、`kotoba-lang/murakumo` は次の2つが並行して育っている:

1. **`murakumo.kekkai`**（本 ADR 直前に着地、murakumo PR #5 / kekkai PR #1）—
   `fleet.edn` 記載だけでは fleet **membership** の根拠にならないとして、
   `kotoba-lang/kekkai`（zero-trust Tailscale-equivalent 制御面）の admission
   ledger に対して `murakumo.fleet/select` をゲートする。これは「このノードを
   操作対象にしてよいか」のレイヤ。
2. **`murakumo.overlay` / `murakumo.cloud`**（README「murakumo.cloud —
   replacing Tailscale/WireGuard」節）— `murakumo.overlay.quic-driver`
   （JVM Clojure、pure-Java **Kwik** QUIC スタック）、relay（認証/AES-GCM
   フレーム）、`murakumo.overlay.peer`（"deterministic peer discovery/
   route-selection state from `cloud.murakumo.route` records"）、`cloud.edn`
   の default-deny capability policy まで、**Tailscale/WireGuard を置き換える
   自前オーバーレイの実装がすでに広範に進んでいる**。ただし今のところ
   `murakumo.overlay.peer` の route-selection は自前の状態機械であり、
   `kotoba-lang/net` の gossip 意味論（dedup・決定的 fanout・route-message）
   とは接続されていない。

また、旧 murakumo README が謳っていた「libp2p gossipsub lattice」の実体
（`kotoba-server` 側 Rust トランスポート）は、`kotoba-lang/kotoba` の Rust
ワークスペース削除（`docs/rust-crate-migration.md`）後、置き換えが着地して
いない（PR #273 で README の crates 表も「現状でなく歴史的記録」へ格下げ）。
つまり **`kotoba-server` 側の gossip は事実上不在**で、`murakumo.overlay` が
その空白を JVM 側から埋めつつある、というのが 2026-07-02 時点の実態。

## Decision（設計提案のみ・実装は別 PR）

**`kotoba-lang/net` の gossip/bitswap 純関数を、`murakumo.overlay` が担う
JVM QUIC/relay トランスポートの上に「native adapter」として接続する。**
`kotoba-lang/net` の ADR がまさに募集している「JVM NIO/QUIC library ベースの
native adapter」の役、そのものを murakumo が担う — 新しいネットワーク機能を
足すのではなく、`murakumo.overlay` がすでに持っているトランスポートに、
再発明せず `kotoba-lang/net` の検証済み意味論を配線するリファクタリング。

1. **依存追加**: murakumo `deps.edn` に
   `io.github.kotoba-lang/net {:local/root "../net"}`（kekkai と同じ
   sibling-checkout パターン。`kotoba-lang/net` は zero-dep cljc なので、
   kekkai と違って langgraph/JVM 境界越えのプロセス分離は不要 — **in-process
   require で問題ない**）。
2. **peer/topic 状態の置き換え**: `murakumo.overlay.peer` の自前
   route-selection state を `kotoba.net.gossip` の `empty-peer-state` /
   `add-peer` / `route-message` に載せ替える（あるいは既存 state を
   gossip 側の shape に正規化してラップする — 実装 PR で決める）。
   `add-peer` 対象は `murakumo.kekkai/apply-gate` を通過したノード集合のみ
   ＝ **kekkai で denied のノードは、そもそも gossip fanout 先の候補にすら
   入らない**（2 つの ADR は直交ではなく積み重なる: kekkai = membership 層、
   net = その上の gossip 層）。
3. **配置 fanout の murakumo 内製化**: 現状 `distribute-artifact` は全
   reachable ノードへの無差別 push（bitswap なし）。`kotoba.net.bitswap` の
   want/have + `WantSince` delta-sync に置き換え、ノードが実際に欲しい CID
   だけを配る。`commits-since` はローカル commit-log に対して適用する純関数
   なので、murakumo 側の「ノードが何を持っているか」ログ（現状 SSH grep で
   代用している `~/.murakumo/mesh.log` 相当）を commit-log 形に正規化する
   薄いアダプタが必要になる。
4. **アーキテクチャ**: 既存の cljc pure-core / `.clj` host-shell 分離
   （`murakumo.fleet.inventory` + `murakumo.fleet`、`murakumo.kekkai.gate` +
   `murakumo.kekkai` と同型）を踏襲する。`kotoba.net.gossip`/`bitswap` の
   呼び出し自体は純関数なのでテストしやすいが、それを実際の
   `murakumo.overlay` ストリーム送受信に結線する副作用シェルは新規に書く
   必要がある（`kotoba-lang/net` 自身は wire framing を持たないため）。

## Open Questions（実装 PR で解く）

- `murakumo.overlay.peer` の既存 state と `kotoba.net.gossip` の
  `PeerState`/`seen-cache` をどう統合するか（置換 or 変換アダプタ）。
- bitswap 導入で `distribute-artifact` の挙動が変わることの既存テスト
  （`murakumo.overlay-*` 系）への影響。
- `kotoba-server`（Rust トランスポート不在）と `murakumo.overlay`
  （JVM 自前実装）が並走している現状の整理 — `murakumo.overlay` が「正」に
  なっていくなら、README 冒頭の「kotoba ships a single-node mesh runtime
  (`kotoba-server` with the `p2p,realtime-wasm` features)」という前提記述も
  更新が要る（本 ADR のスコープ外、別途）。

## Consequences

**Positive**
- gossip/bitswap の意味論（dedup・決定的 fanout・want-have・delta-sync）を
  murakumo が再発明せず、`kotoba-lang/net` の unit test で担保された実装を
  そのまま使える。
- `kotoba-lang/net` にとって最初の実 consumer になり、「native adapter は
  この契約に従う」という ADR の設計意図が初めて検証される。
- kekkai（membership 層）→ net（gossip 層）→ overlay（トランスポート層）
  という素直なレイヤ積みになる。

**Negative / 制約（honest）**
- `kotoba-lang/net` は本当にトランスポート非依存の純ロジックのみなので、
  wire framing・NAT 越え・実ソケット I/O は引き続き `murakumo.overlay` 側の
  責務のまま — この統合単体では「新しい到達性」が増えるわけではない。
- 本 ADR は設計のみ。実装（deps.edn 追加・peer state 置き換え・bitswap 配線・
  テスト）は別 PR/ブランチで行う。
