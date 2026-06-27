---
id: adr-2606271800-kotoba-net-webrtc-turn-transport
title: "ADR-2606271800: kotoba-net に WebRTC(+WebTransport/WSS) トランスポート追加 — kotoba-turn を ICE relay 再利用し browser/edge を Live 面の first-class peer に"
status: proposed
doc_type: adr
topic: transport-protocol
authoritative: true
last_verified: 2026-06-27
authoritative_for:
  - kotoba-net の libp2p swarm に WebRTC(webrtc-direct)トランスポートを QUIC と並置する設計
  - kotoba-turn(RFC 8656)を WebRTC ICE の TURN relay として再利用する配線
  - browser=webrtc/webtransport, edge=wss を Live 面に載せ connect.edn の :reach を実体化する段階計画
related:
  - 90-docs/adr/2606271700-kotoba-transport-planes.md         # 2平面 + connect.edn 単一記述(本 ADR の上位)
  - orgs/com-junkawasaki/kotoba/crates/kotoba-net             # libp2p swarm(現状 QUIC のみ) ← 実装先
  - orgs/com-junkawasaki/kotoba/crates/kotoba-turn            # TURN(RFC 8656) ephemeral cred mint/verify ← 再利用
  - orgs/com-junkawasaki/kotoba/crates/kotoba-rt             # realtime per-room bus(WebRTC media と同居)
  - orgs/com-junkawasaki/kotoba/crates/kotoba-store-web       # browser IndexedDB(read-only cache, 現状)
  - orgs/com-junkawasaki/murakumo/connect.edn                # native :live に :webrtc を足す単一ノブ
supersedes: []
superseded_by: []
---

# ADR-2606271800: kotoba-net WebRTC(+WebTransport/WSS) トランスポート + kotoba-turn 再利用

**Status**: proposed（design — 実装は kotoba-net 側 PR に分離）
**Date**: 2026-06-27
**Deciders**: Jun Kawasaki

## Context

ADR-2606271700 で「Live 面 = libp2p マルチトランスポート」「browser=webrtc/webtransport,
edge=wss」を決め、murakumo 側は `connect.edn` の `:classes` と `reconcile` の `:reach`
配線まで実装した。だが **`kotoba-net` の swarm は現状 QUIC-v1 のみ**（`with_quic` +
Noise + gossipsub + Kademlia + relay/dcutr/autonat）。よって `:reach [:browser/live]` の
コンポーネントは reconcile で正直に `:blocked` になる（native が `:quic` のみで browser の
`:webrtc` と共有トランスポートが無い）。

本 ADR は、その「最後の1マイル」= **kotoba-net に WebRTC トランスポートを足し、ICE の
TURN を既存 `kotoba-turn` に配線する**設計を確定する。これで browser が Live 面の
first-class peer になり、connect.edn の `:native :live` に `:webrtc` を足す1編集が
reconcile eligibility を反転させる（テスト `reach-after-wiring-webrtc-into-native` が
既に保証している到達点）。

> 前提（不変）: Read 面はこれに依存しない。オブジェクト配信・クエリ・serverless は
> CID-over-HTTP のままで、browser/edge は今日 first-class（ADR-2606271700 §1）。本 ADR は
> **Live 面（可用性/gossip/realtime/持ち合い）専用**の拡張。

## Decision

### 1. swarm に webrtc-direct を QUIC と並置する

`libp2p-webrtc`（`webrtc-direct`, server プロファイル）を QUIC と `OrTransport` で合成。
QUIC native↔native は不変、追加だけ。

```rust
// crates/kotoba-net (sketch)
let webrtc = libp2p_webrtc::tokio::Transport::new(
    keypair.clone(),
    libp2p_webrtc::tokio::Certificate::generate(&mut rand::thread_rng())?, // self-signed
);
let transport = quic_transport
    .or_transport(webrtc)
    .map(|either, _| either.into_inner())
    .boxed();
```

- **証明書は self-signed**。その hash を multiaddr の `/certhash` に載せる（CA 不要）=
  connect.edn `:live {:security :noise}` / certhash 方針と一致。
- 公開 multiaddr 形:
  `/ip4/<tailscale-ip>/udp/<port>/webrtc-direct/certhash/<mb>/p2p/12D3Koo…`
- browser 側は **js-libp2p の `@libp2p/webrtc`** で dial（既存 kami-engine-sdk の WebRTC
  スタックと同居可能）。

### 2. ICE の TURN を kotoba-turn に再利用する（新規 NAT インフラ不要）

WebRTC の NAT 越えは STUN(hole-punch) + TURN(relay fallback)。kotoba は既に
**`kotoba-turn`（RFC 8656, coturn use-auth-secret 互換の ephemeral credential
mint/verify、`mintTurnCredential` を kami-engine-sdk JS と byte 単位共有）**を持つ。

- browser は `kotoba-server` の XRPC（例 `com.etzhayyim.apps.kotoba.turn.credential`）から
  **短命 TURN credential**（HMAC-SHA1, expiry 付き）を受け取り、ICE の `iceServers` に
  載せる。サーバ鍵は出さない（no-server-key を維持）。
- `kotoba-turn` の **未完分=async UDP/TCP listener shell（recv loop + relay-port pool +
  packet forwarding）**が本配線の前提依存（socket-free core は 28 tests green 済み）。
  ここを閉じるのが P1 の主作業。
- 対称 NAT 配下でも relay 経由で browser↔native が張れる。

### 3. edge/WSS と WebTransport は段階追加（Live 面の補完）

- **WSS**（`libp2p-websocket` over TLS）: CF Worker / Deno（Durable Objects で WS server
  可）= `edge` クラスの Live トランスポート。CA 証明書 + ドメイン必須。
- **WebTransport**（`libp2p-webtransport`, H3/QUIC）: modern browser の高スループット経路
  （Safari 非対応なので WebRTC を一次・WebTransport を最適化として併設）。

### 4. connect.edn / kotoba-server env への配線（単一記述から導出）

| 層 | 変更 |
|---|---|
| `connect.edn` | `:classes :native :live` に `:webrtc`（将来 `:wss`）を追記 = **単一ノブ**。murakumo reconcile が自動で `:reach :browser/live` を eligible 化 |
| `kotoba-server` env | `KOTOBA_WEBRTC=on` / `KOTOBA_WEBRTC_PORT` / `KOTOBA_TURN_URL` / `KOTOBA_TURN_SECRET`（cred mint 用）/ 公開 `…/webrtc-direct/certhash/…` を external addr に追加 |
| murakumo plist tmpl | 上記 env の `{{WEBRTC*}}` placeholder を `provision` でレンダ。`mesh` の bootstrap に webrtc-direct addr を含める |
| browser SDK | js-libp2p `webrtc` transport + `turn.credential` XRPC から iceServers を取得 |

### 5. browser は leaf（:dialable false）— inbound は relay 経由

browser は dial-out のみ（外から叩けない）。browser↔browser や inbound は
**circuit-relay-v2**（native relay ロール — `fleet.edn` の asher が既に `relay`）+ dcutr
hole-punch で確立。connect.edn の `:dialable` がこの非対称を表現済み。

## 段階計画（implementation phases — 別 PR, kotoba checkout が clean な時）

1. **P1**: `kotoba-turn` の async UDP listener shell を実装（relay-port pool + forwarding）。
   `kotoba-net` に webrtc-direct server transport を `OrTransport` で追加（feature
   `webrtc` + `KOTOBA_WEBRTC` gate）。browser→native の生 data channel 疎通 smoke test。
2. **P2**: `turn.credential` XRPC（kotoba-turn の mint を公開、operator-gated）。connect.edn
   `:native :live += :webrtc`。murakumo `provision`/`mesh` が env + multiaddr をレンダ。
   `:reach [:browser/live]` app が reconcile で `:place` に反転することを fleet で確認。
3. **P3**: `libp2p-webtransport`（modern browser 高スループット）+ `libp2p-websocket`/WSS
   （edge/CF, connect.edn `:edge :live :wss`）。
4. **P4**: circuit-relay-v2 + dcutr で browser↔browser / inbound を確立。

## Consequences

**Positive**
- browser が Live 面の first-class peer に。新規 NAT インフラ不要（kotoba-turn 再利用）。
- connect.edn 単一記述から env/multiaddr/placement が導出され、二重管理が無い。
- QUIC native 経路は不変（追加合成のみ、回帰なし）。

**Negative / 制約（honest）**
- `kotoba-turn` の async listener が未完 = P1 のクリティカルパス。
- WebRTC は SCTP/DTLS でスループットが QUIC より低い → Live 面（制御/gossip/realtime）
  専用。**バルク配信は Read 面の CID-over-HTTP のまま**（混同しない）。
- WSS は CA 証明書 + ドメイン、WebTransport は Safari 非対応という外部制約は消せない
  （だから WebRTC が browser 一次）。
- 実装は dirty な kotoba checkout を避け別 PR に分離（本 ADR は設計確定のみ）。

## Implementation status (2026-06-27)

- **P1 done + verified** — `kotoba-turn` async UDP relay listener
  (com-junkawasaki/kotoba#226). `cargo test -p kotoba-turn --features listener` = 31
  passed incl. a full loopback relay roundtrip. TCP/TLS listeners remain.
- **P2 core done + verified** — `kotoba-turn::ice` mints the browser `iceServers`
  config from the relay-verifiable ephemeral credential
  (com-junkawasaki/kotoba#227). `cargo test -p kotoba-turn` = 32 passed.
- **P2 remaining (concrete patches below)** — the `kotoba-server` XRPC and the
  `kotoba-net` `libp2p-webrtc` transport. The latter adds a new alpha dependency
  (`libp2p-webrtc`, a registry fetch on libp2p 0.53) and is best landed in a build
  environment with crates.io access; it is **not** compile-verified here, so this ADR
  carries the exact patch rather than half-built code.
- **connect.edn stays unflipped** — `:native :live` keeps `[:quic]` until the
  `kotoba-net` transport actually speaks WebRTC on provisioned nodes; flipping it
  early would make murakumo place `:reach :browser/live` apps where browsers can't yet
  reach them. The flip is the LAST step of P2.

### Patch A — `kotoba-server` `turn.credential` XRPC (no new external dep)

`kotoba-server` already routes `/xrpc/:nsid → xrpc::generic_invoke`; add `kotoba-turn`
as a workspace dep and a dispatch arm (operator-gated, like `audit.listReceipts`):

```rust
// crates/kotoba-server/Cargo.toml
// kotoba-turn = { workspace = true }

// in xrpc::generic_invoke's nsid match:
"com.etzhayyim.apps.kotoba.turn.credential" => {
    // operator-gated: same Bearer/CACAO operator check the audit XRPCs use
    require_operator(&state, &headers)?;
    let room   = query.get("room").map(String::as_str).unwrap_or("default");
    let player: u32 = query.get("player").and_then(|s| s.parse().ok()).unwrap_or(0);
    let ttl    = query.get("ttl").and_then(|s| s.parse().ok()).unwrap_or(300);
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH).unwrap().as_secs();
    let secret = std::env::var("KOTOBA_TURN_SECRET")
        .map_err(|_| xrpc_err(500, "turn secret not configured"))?;
    let stun: Vec<&str> = state.turn_stun_urls.iter().map(String::as_str).collect();
    let turn: Vec<&str> = state.turn_urls.iter().map(String::as_str).collect();
    let cfg = kotoba_turn::ice::ice_config(&secret, &stun, &turn, room, player, ttl, now);
    return Ok(json_response(kotoba_turn::ice::to_json(&cfg, now)));
}
```

`KOTOBA_TURN_SECRET` is the shared secret the relay (`kotoba-turn` listener, P1) also
loads; `state.turn_urls`/`turn_stun_urls` come from `KOTOBA_TURN_URLS`/`KOTOBA_STUN_URLS`.

### Patch B — `kotoba-net` `libp2p-webrtc` transport (adds an alpha dep)

```toml
# Cargo.toml (workspace)  — new optional dep
libp2p-webrtc = { version = "0.7.0-alpha", features = ["tokio"], optional = true }

# crates/kotoba-net/Cargo.toml
# libp2p-webrtc = { workspace = true, optional = true }
# [features]
# webrtc = ["dep:libp2p-webrtc"]
```

```rust
// crates/kotoba-net/src/swarm.rs — compose webrtc-direct alongside QUIC.
// The SwarmBuilder already does .with_tokio().with_quic(); add:
let mut b = libp2p::SwarmBuilder::with_existing_identity(keypair)
    .with_tokio()
    .with_quic();
#[cfg(feature = "webrtc")]
let b = b.with_other_transport(|key| {
    let cert = libp2p_webrtc::tokio::Certificate::generate(&mut rand::thread_rng())?;
    Ok(libp2p_webrtc::tokio::Transport::new(key.clone(), cert))
})?;
let mut swarm = b.with_behaviour(|_| behaviour)?.build();
// listen addr: /ip4/<tailscale-ip>/udp/<port>/webrtc-direct
// (cert hash is advertised in the emitted multiaddr; browser dials via js-libp2p)
```

Then, on a node provisioned with `KOTOBA_WEBRTC=on`, flip
`connect.edn :classes :native :live` to `[:quic :webrtc]` — murakumo `reconcile`
immediately makes `:reach :browser/live` apps eligible on that node (proven offline by
`reach-after-wiring-webrtc-into-native`).

## References
- ADR-2606271700 — 2平面 + 5プロトコル比較 + connect.edn 単一記述（本 ADR の上位）。
- com-junkawasaki/kotoba#226 (P1 listener) / #227 (P2 ice core)。
- `kotoba/CLAUDE.md` の `kotoba-turn` 項 — socket-free core done / async listener shell remaining。
- libp2p: `webrtc-direct` certhash multiaddr, circuit-relay-v2, dcutr。
