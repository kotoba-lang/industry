---
id: adr-2607072400-kaisha-pod-murakumo-fleet-deployment
title: "ADR-2607072400: kaisha realtime pod は murakumo fleet の kotoba-server に deploy する（到達は tailnet → murakumo.cloud overlay、公開 HTTP は kotobase.net）"
status: accepted
doc_type: adr
topic: kaisha-pod-murakumo-fleet-deployment
authoritative: true
last_verified: 2026-07-07
authoritative_for:
  - kaisha communication space の realtime pod（space graph + KSE fan-out）のホスト先が murakumo fleet 上の kotoba-server であること
  - pod への到達経路の段階方針（当面 tailnet、murakumo.cloud overlay 完成後に移行）
  - 公開 HTTP / 静的面 / auth-gated datom endpoint が kotobase.net（gftdcojp/net-kotobase）に残るハイブリッド分担
  - denrei/koyomi 等の kotoba-store（:url 注入 + CACAO 自己発行）が deploy 先に依存しない契約であること
related:
  - 90-docs/adr/2607072310-kotoba-lang-kaisha-communication-space.md
  - 90-docs/adr/2607072330-kotoba-lang-denrei-posting-actor.md
  - 90-docs/adr/2607041302-murakumo-family-naming.md
  - 90-docs/adr/2607072000-gftdcojp-net-kotobase-ipfs-gateway-split.md
supersedes: []
superseded_by: []
---

# ADR-2607072400: kaisha realtime pod は murakumo fleet の kotoba-server に deploy する

**Status**: accepted
**Date**: 2026-07-07
**Deciders**: Jun Kawasaki

## Context

kaisha（ADR-2607072310）の live 化には「space graph を持ち KSE realtime
fan-out を配る kotoba-server pod」が要る。deploy 先候補は2系統ある（実測
2026-07-07）: ① **kotobase.net**（gftdcojp/net-kotobase、Cloudflare Worker 系。
denrei/koyomi/tayori/kekkai の `kotoba.clj` が既定例として指す auth-gated
datom endpoint。realtime の口は無い）、② **murakumo fleet**（kotoba-lang/
murakumo が管理する Mac-mini 群。各ノードは既に `kotoba-server` を
LaunchAgent 常駐（`p2p,realtime-wasm`）させ、lattice で `on-http`/`on-tick`/
`on-kse` trigger の WASM component をホストし、`kqe-assert!` を kotoba Datom
log に永続する — つまりノード自体が kotoba-server pod）。到達は今日は
Tailscale、`murakumo.cloud` はそれを DID/CID identity addressing + QUIC/
WebRTC/WebTransport + relay fallback で置き換える構築中の overlay。
この deploy 方針はどの ADR にも明文化されていなかった（オーナー確認
2026-07-07）。

## Decision

**ハイブリッド分担を正式方針とする。**

1. **kaisha の realtime pod（space graph + KSE fan-out）= murakumo fleet 上の
   kotoba-server。** 理由: (a) live ChannelTarget の realtime fan-out は
   murakumo lattice の `on-kse` trigger とそのまま噛み合う（kotobase.net に
   realtime の口は無い）、(b) actor 鍵由来 IPNS 名 = graph の kaisha/denrei
   モデルは murakumo.cloud の DID/CID identity addressing と同一思想、
   (c) fleet ノードは既に kotoba-server 常駐でありデプロイ増分が最小。
2. **到達経路は段階方針**: 当面は tailnet（Tailscale）経由。murakumo.cloud
   overlay が実用化したら overlay 経由に移行する（pod 側は変更なし —
   到達層の差し替えのみ）。
3. **公開 HTTP / 静的面 / 既存の auth-gated datom endpoint は kotobase.net
   （gftdcojp/net-kotobase）に残す。** kaisha の社外公開面（もし作るなら）も
   こちら。
4. **actor 側の契約は不変**: denrei/koyomi 等の `kotoba-store` は `:url` 注入 +
   CACAO 自己発行（`:aud` = 与えられた url）なので、pod がどちらの substrate に
   いてもコード変更ゼロ。deploy 先の選択は設定（url）だけの問題に保つ。

## Consequences

live ChannelTarget（ADR-2607072330 の follow-up）は murakumo fleet 上の
kaisha pod を対象に実装する。murakumo.cloud overlay の完成を kaisha の
live 化のブロッカーにしない（tailnet で先行できる）。kotobase.net は
realtime を持たない現状のまま公開面に専念できる。

Addendum（2026-07-07 同日、実装完了）: `denrei.pod`（denrei `dc59f13`）として
live ChannelTarget を実装し、**実 fleet node（asher, tailnet :8077）で
end-to-end 実測成功**（post!=transact 200 / fetch-message=pull round-trip /
channel-messages=q index read）。kaisha graph の「配置」は deploy 作業では
なく **actor の account-owned private graph の初回 write 自動登録**
（kotoba-server の data-sovereignty 経路: graph CID =
CID("kotoba://graph/private/<did>") なら Private{owner=issuer} で自動登録）
で実現される — つまり fleet ノードが生きていれば kaisha pod は
provision 不要で成立する。実測で確定した fleet 方言（`fleet-channelport`
が pre-wire）: CACAO aud = node did:key（URL 不可）、multi-cap grant
（datom:read + datom:transact + tx:create）、graph scope は write=CID /
read=private/<did> 名前形の両方、秒精度 timestamp、tx map に :db/id 必須、
graph param は dag-cbor CID のみ（IPNS 名 / libp2p-key CID は不可）。
Addendum 2（2026-07-07 同日、KSE 訂正 — オーナー指摘による）: 本 ADR が
当初 follow-up とした「lattice `on-kse` WASM component による push 型
realtime fan-out」は**撤回し、kaisha の設計から KSE 依存を除去した**。
実装照合（kotoba-server Rust ソース）の結果: KSE（Kotoba Stream Engine、
LiveBus topic pub/sub）は kotoba-server / lattice **runtime 固有**の設備で、
kotoba-protocol の層スタックに含まれず、**workerd / browser の kotobase
engine（kotobase-cljc-worker / kotobase-peer / kotobase-client）には存在
しない**。datomic.transact は各 datom を gossip topic `quad/assert` に
ephemeral broadcast し on-kse component も受信可能だが（橋は実在）、
LiveBus は datomic topic を永続せず catch-up 不能（永続 replay は datom 面の
`sync.eventsFromCommits` のみ）。よって realtime 追従の正本は **kotobase /
kotoba-peer と共通の datom 面（`:db-api` 契約）**とし、denrei `69a3f5d` の
`messages-since` / `follow!`（cursor tail、loss-free・replayable、全
backend 同一コード）で実装済み。KSE は kaisha にとって「使わない」が結論
（kotoba-server 本体の KSE subsystem は mesh の signal/realtime/ENGI 用途で
現役のため、本 ADR は kotoba-server からの KSE 除去までは主張しない）。

## References

`orgs/kotoba-lang/murakumo`（README: fleet 各ノードの kotoba-server 常駐と
`on-kse`）、`orgs/gftdcojp/net-kotobase`、`orgs/kotoba-lang/denrei`
`src/denrei/kotoba.clj`（:url 注入）、ADR-2607072310(kaisha)、
ADR-2607072330(denrei)、ADR-2607041302(murakumo family naming)、
ADR-2607072000(net-kotobase IPFS split)。
