# ADR-2607030400: murakumo.cloud の参加基盤 — web/wasm を旗印にした contribute/inference の最後の1マイル

**Status**: accepted（設計 + 一部実装）
**Date**: 2026-07-03
**Deciders**: Jun Kawasaki

## Context

murakumo は分散推論の技術・経済・UI が揃った（ADR-2607022000 / 030030 / 030200）。
残る公開への「最後の1マイル」は 3 つ: (1) `/join` インストーラの実体、
(2) `/v1` を実 fleet へ橋渡しする edge routing、(3) 外部 contributor の NAT 越え。
本 ADR はこれを設計し、さらに **「ブラウザ / wasm でも参加できる」を最大の差別化**
として据える。exo（native バイナリのみ）も petals（Python native）も持たない特徴。

## Decision

### 1. 参加は 3 tier、browser を最広リーチの第一級市民に（実装済み: murakumo.infer.join）

| tier | install | runtime | connect | 取れる仕事 | リーチ |
|---|---|---|---|---|---|
| **browser** | **不要**（URL を開く） | WebGPU+wasm | **WebRTC(アウトのみ)** | media-postproc / 並列 / prompt-eval / small-shard | **最広**（全ブラウザ端末） |
| **wasm** | 埋め込み | WebGPU+wasm | WebTransport | 同上 | 広 |
| **native** | curl\|sh | Metal/CUDA/CPU | QUIC 直 or relay | 大モデル host / 低遅延 pipeline / メディア生成 | 狭 |

`enrollment/1` は **client 側で計算**（タブが自分の WebGPU 上限・RAM・回線を知る）し、
**タブ内生成の did:key で署名**。その did がそのまま credits 口座。

### 2. NAT 越え = browser の構造的優位（設計）

**native は inbound 到達可能な rpc-server が要る → NAT/FW の痛み。**
**browser/wasm は relay へアウトバウンド接続だけ → NAT を無料で越える。**
これが「native がより有能」の通念を反転させる: リーチでは browser tier が
最大の contributor base（インストールゼロ・どの端末でも）。
`needs-relay?` は browser/wasm で常に true、native は un-reachable 時のみ true。
relay は murakumo.cloud overlay の WebRTC/WebTransport slot（既に予約済み、
ADR-2606… の cloud.edn）。

### 3. edge routing（設計）: /v1 → fleet

cloud-murakumo Worker（CF edge）が受けた `/v1/*`・`/infer/spend` を、
`/infer/nodes` レジストリ + murakumo.infer.schedule/join.partition-work で
振り分ける:
- **重い仕事**（大モデル host・低遅延）→ native tier
- **並列・media-postproc・小 shard** → browser/wasm swarm（NAT-free で最大数）
Worker は状態を持たず、ルーティングは純関数（plan/schedule/join は全 cljc）。
実 fleet への到達は relay 経由（browser worker が work を pull、結果を push）。

### 4. /join インストーラ（実装済み: エンドポイント / 設計: 中身）

- `GET /join` → tier メニュー（実装済み）
- `GET /join/browser` → **ゼロインストール worker ページ**（実装済み）:
  タブが WebGPU probe → did:key 生成 → `/infer/nodes` に browser tier で enroll。
  次段（relay への WebRTC 接続 + work pull）が最後の実装ポイント。
- `GET /join/native.sh` → curl\|sh installer（設計）: rpc-server/ComfyUI 常駐化。
- `GET /join/worker.wasm` → 埋め込み用 wasm worker（設計）: kotodama.inference
  （WebGPU 契約は既存、num/wgsl backend で計算）を wasm module 化。

## 実装済み / 残実装

**済**: 3 tier モデル（join.cljc、6 tests）、tier 別 enrollment（`/infer/nodes`）、
`/join` メニュー、`/join/browser` ゼロインストールページ（did:key 生成 + enroll
まで動く）、onboarding UI に browser/wasm 参加を旗印表示。全 SSR テスト green。

**残**（優先順、次の一手）:
1. **relay 結線**: browser worker ⇄ relay の WebRTC channel（overlay の
   WebTransport/WebRTC adapter を実バインド）。ここが work pull/push の実体。
2. **wasm worker 本体**: kotodama.inference を wasm ビルド、WebGPU で
   media-postproc / prompt-eval を実行。
3. **native.sh / worker.wasm** の配信物。
4. **edge /v1 実ルーティング**: schedule/join の純関数を Worker が呼び、
   relay 越しに実 fleet へ。

## Consequences

- **「あなたのブラウザタブが叢雲の一部になる」** が旗印になる。インストール障壁ゼロ、
  NAT 越え不要、did:key が口座 — 世界中のどの端末でも contribute できる。
- 重い推論は native、軽い並列は browser swarm、という自然な役割分担が
  join.partition-work で純データとして表現される（同じ台帳・同じ credits）。
- 残るは transport 実装であり、判定・登録・経済・UI は全て動いている。
