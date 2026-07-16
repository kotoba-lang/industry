---
id: adr-2606271700-kotoba-transport-planes
title: "ADR-2606271700: kotoba/murakumo の通信プロトコル — 2平面(Read=HTTP-by-CID / Live=libp2p multi-transport)と connect.edn 単一記述"
status: proposed
doc_type: adr
topic: transport-protocol
authoritative: true
last_verified: 2026-06-27
authoritative_for:
  - kotoba/murakumo の通信を「Read 面(CID-over-HTTP)」と「Live 面(libp2p マルチトランスポート)」の2平面に分ける決定
  - QUIC をネイティブ fleet の Live トランスポートとして維持し、browser/edge を raw-QUIC peer にしない方針
  - browser/edge の参加が大半 Read 面(HTTP-by-CID)で完結し p2p トランスポート不要であることの参照点
  - connect.edn を接続の単一宣言的記述(SSoT)とし、murakumo placement の :reach をそこから導く配線
related:
  - 90-docs/adr/2606271600-kotoba-stack-equivalences.md            # 上位 positioning(wasmCloud⊗Spin⊗clj⊗Datomic⊗Radicle)
  - orgs/kotoba-lang/kotoba/docs/ADR-browser-cid-query-vs-p2p.md  # Read=CID-over-HTTP / P2P=可用性層の一次決定
  - orgs/kotoba-lang/kotoba/crates/kotoba-net                  # libp2p QUIC/Noise/gossipsub/Kademlia + relay/dcutr/autonat
  - orgs/kotoba-lang/kotoba/crates/kotoba-turn                 # TURN(RFC 8656) — webrtc ICE relay に再利用
  - orgs/kotoba-lang/kotoba/crates/kotoba-store-web            # browser IndexedDB(read-only cache)
  - orgs/kotoba-lang/murakumo/connect.edn                      # 接続の単一記述(SSoT)
  - orgs/kotoba-lang/murakumo/src/murakumo/connect.clj         # connect.edn → reach 解決(純粋)
  - orgs/kotoba-lang/murakumo/src/murakumo/reconcile.clj       # :placement :reach を eligible に配線
supersedes: []
superseded_by: []
---

# ADR-2606271700: kotoba/murakumo の通信プロトコル — 2平面 + connect.edn 単一記述

**Status**: proposed
**Date**: 2026-06-27
**Deciders**: Jun Kawasaki

## Context

「通信プロトコルは QUIC のままでいいのか、libp2p か。browser / edge / local で
分散通信・オブジェクト配信・WASM 実行・serverless を想定したとき、適切なものは？」

現状(`kotoba-net`)は **libp2p QUIC-v1 + Noise + gossipsub + Kademlia +
relay/dcutr/autonat**。これはネイティブ Mac-mini fleet には最適だが、**browser /
edge(CF Worker 等)は raw QUIC/UDP ソケットを開けない**ため、QUIC 単独では
heterogeneous fleet を張れない。

決定の鍵は kotoba 一次 ADR(`ADR-browser-cid-query-vs-p2p.md`, Accepted)の洞察:

> クエリ/オブジェクトは **CID で content-addressed**。受信側が常に
> `CID == sha256(dag-cbor(bytes))` を検証するので、**「誰が bytes を配ったか」は
> 正しさに無関係**。content-addressing がトランスポートの信頼問題を消す ⇒ ランダムな
> HTTP gateway も verified peer も等価。

## Decision

### 1. 通信を2平面に分ける(プロトコルの問いは平面ごとに別)

| 平面 | 運ぶもの | 必要な性質 | プロトコル |
|---|---|---|---|
| **Read / Object** | オブジェクト配信・クエリ・serverless 呼び出し(`on-http`) | 到達性のみ(信頼は CID)。普遍・キャッシュ可 | **CID-over-HTTP/3**(wasi-http / XRPC / block.get / firehose-SSE) |
| **Live / Mesh** | 配置 gossip・heartbeat・realtime・持ち合い | 双方向・低遅延・NAT 越え | **libp2p マルチトランスポート**(quic/webrtc/webtransport/wss) |

Read 面は **CID 検証によりトランスポート無信頼**なので、最も普遍・安価・キャッシュ可能な
トランスポート(HTTP)が常に正解。browser/edge はここで**ゼロ追加で first-class**。
Live 面は**可用性/liveness の最適化層**であり、正しさの要件ではない(一次 ADR)。

### 2. QUIC は維持。browser/edge を raw-QUIC peer にしない

- **ネイティブ fleet の Live トランスポート = QUIC**(現状維持。最適)。
- browser/edge の参加は**大半 Read 面で完結**(HTTP-by-CID)。Live 面に入る必要が
  生じたときだけ、ブラウザ可能なトランスポートを足す。

### 3. 5プロトコル比較(Live 面トランスポート選定の根拠)

各次元 1–5 点(5=最良)、括弧内は実値の目安。

| # | プロトコル | 接続RTT | Browser | Edge(CF) | NAT越え | TLS証明書 | 多重化/datagram | スループット |
|---|---|---|---|---|---|---|---|---|
| 1 | **libp2p-QUIC**(現状) | 5 (1-RTT/0-RTT) | 1 (不可) | 1 (UDP不可) | 3 (dcutr+relay) | 5 (Noise, CA不要) | 5 (HOLなし) | 5 |
| 2 | **WebTransport**(H3/QUIC) | 5 (1-RTT) | 3 (Safari✗) | 2 (限定) | 2 (client→serverのみ) | 2 (有効証明書 or certHash≤14日) | 5 | 5 |
| 3 | **libp2p-WebRTC** | 2 (ICE+DTLS 2–4RTT) | 5 (Safari含む全) | 1 | 5 (STUN/TURN, kotoba-turn既存) | 4 (certHash) | 3 (SCTP) | 3 |
| 4 | **WebSocket Secure** | 3 (TCP+TLS+upgrade) | 5 (普遍) | 5 (CF DO で server可) | 2 (client→serverのみ) | 2 (CA証明書+ドメイン) | 2 (TCP HOL) | 3 |
| 5 | **CID-over-HTTP**(H3+bitswap/gateway = Read面) | 4 (H3 1-RTT) | 5 (普遍) | 5 (CF=CDN, 不変CID=∞キャッシュ) | — (pull型, 不要) | 5 (標準TLS) | 5 (CDN増幅) |

### 4. ノード種別ごとの最適解

| ノード | Read/Object + serverless | Live/Mesh |
|---|---|---|
| Local / Mac-mini fleet | bitswap **over QUIC** | **QUIC**(維持) |
| Browser | **CID-over-HTTP/3**(今日 shipping) | **WebRTC**(Safari+NAT, kotoba-turn 再利用)／高スループットは WebTransport |
| Edge (CF Worker/Deno) | **CID-over-HTTP**(CDN キャッシュ) | **WSS**(CF Durable Objects) |
| Serverless 呼び出し | **HTTP/3(wasi-http)** で `on-http` 駆動 | — |

### 5. 推奨スタック

> **QUIC をネイティブ fleet の libp2p トランスポートとして維持。browser/edge を
> raw-QUIC peer にしない。代わりに:**
> 1. **Read/Object 面 = CID-over-HTTP/3 を全ノード共通**(CID 検証でトランスポート
>    無信頼 ⇒ gateway/peer/CDN すべて等価)。
> 2. **Live/Mesh 面 = libp2p マルチトランスポートを1 swarm に**: `QUIC`(native) +
>    `WebRTC`(browser, kotoba-turn 再利用) + `WebTransport`(modern browser/高スループット)
>    + `WSS`(edge/CF・普遍 fallback)、`circuit-relay-v2 + dcutr` で NAT 統一。

これは ADR-2606271600 の wasmCloud 対比そのもの: wasmCloud は **NATS 単一トランスポート
(server-centric)**。kotoba は **libp2p マルチ + CID-over-HTTP** だから browser/edge/local が
全部 first-class になり、かつ CID 洞察により browser/edge 参加の大半は p2p トランスポート
不要。

### 6. connect.edn — 接続の単一宣言的記述("connect のように単一記述")

トランスポート行列を**1ファイル**で宣言し、kotoba-net の swarm 構成も murakumo の
placement reach も**そこから導く**(再宣言しない)。Connect スキーマが1記述で全
プロトコルを跨ぐのと同じ思想。

```edn
;; orgs/kotoba-lang/murakumo/connect.edn
{:connect/version 1
 :planes {:read {:protocol :http :trust :cid :alpn [:h3 :h2]}
          :live {:transports [:quic :webrtc :webtransport :wss]
                 :relay :circuit-relay-v2 :holepunch :dcutr
                 :turn "kotoba-turn (RFC 8656)"}}
 :classes {:native  {:read [:http] :live [:quic]                :dialable true}
           :edge    {:read [:http] :live [:wss]                 :dialable true}
           :browser {:read [:http] :live [:webrtc :webtransport] :dialable false}}
 :default-class :native}
```

murakumo の reconcile はこれを読み、app の `:placement {:reach […]}` を解決する:

- `reach :*/read` — ノードが `:http` を話せば満たす(CID-over-HTTP は普遍)。
- `reach :*/live` — ノードと対象クライアントクラスが **Live トランスポートを1つ以上
  共有**するとき満たす。

実装(`murakumo.connect` + `murakumo.reconcile/eligible-nodes`)はオフライン単体
テスト済み。例: `:reach [:browser/live]` のコンポーネントは、native が `:quic` のみの
現状では `eligible=∅` ⇒ `:blocked`(browser に届かないノードに黙って配置しない)。

### 7. 配線の単一ノブ(wiring knob)

native fleet を browser-live(webrtc)/edge(wss)に対応させるには、connect.edn の
`:classes :native :live` にそのトランスポートを足す**1編集**(+ kotoba-net swarm が
そのトランスポートを獲得)で、全 `:reach :browser/live` app の reconcile eligibility が
反転する。テスト `reach-after-wiring-webrtc-into-native` がこれを保証(:blocked→:place)。

## Consequences

**Positive**
- 「QUIC のままでいいか」に一意に答えられる: native fleet は QUIC 維持、browser/edge は
  Read=HTTP-by-CID + 必要時のみ Live(webrtc/wss)。
- 接続が connect.edn の単一記述に集約。murakumo placement が transport-reach を尊重し、
  到達不能配置を `:blocked` として正直に surface する。
- kotoba-turn(既存)が webrtc ICE relay として再利用でき、新規 NAT インフラ不要。

**Negative / 制約(honest)**
- kotoba-net の swarm はまだ **QUIC のみ**。webrtc/webtransport/wss トランスポートは
  未実装(本 ADR は方針 + 単一記述 + murakumo 側 reach 配線まで。kotoba-net への
  トランスポート追加は次 increment)。
- `:dialable false`(browser)を実 placement ターゲットにする話ではない — reach は
  「どの**サーバ/peer ノード**がそのクライアントクラスに届くか」の制約であり、browser
  自体への配置ではない。
- connect.edn が無い場合、reach は **degrade-open**(no-op)。SSoT 欠如で全配置を
  ブロックしないための選択(運用側は警告で気付く)。

## References
- `orgs/kotoba-lang/kotoba/docs/ADR-browser-cid-query-vs-p2p.md` — Read=CID-over-HTTP /
  P2P=availability layer の一次決定(本 ADR の Read 面はこれに従う)。
- `90-docs/adr/2606271600-kotoba-stack-equivalences.md` — wasmCloud(NATS 単一) vs
  kotoba(libp2p マルチ + HTTP-by-CID)の positioning。
- `orgs/kotoba-lang/murakumo/connect.edn` / `src/murakumo/connect.clj` /
  `src/murakumo/reconcile.clj` — 単一記述と reach 配線の実装 + テスト。
