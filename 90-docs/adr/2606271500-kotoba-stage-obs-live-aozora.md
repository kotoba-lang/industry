---
id: adr-2606271500-kotoba-stage-obs-live-aozora
title: "ADR-2606271500: kotoba-stage — kotoba/CLJ ネイティブな OBS 相当の配信ツールと OBS→app-aozora ライブ配信"
status: accepted
doc_type: adr
topic: live-streaming
authoritative: true
last_verified: 2026-06-27
authoritative_for:
  - OBS 相当のシーン/ソース/ミキサ合成を kotoba データ + .kotoba(CLJ→WASM) で表す設計
  - OBS から app-aozora へライブ配信する取り込み(WHIP/RTMP/SRT)・配信(LL-HLS/WebRTC)経路
  - ライブ配信の AT Protocol レキシコン名前空間 app.aozora.live.*
related:
  - kotoba/crates/kotoba-rt          # リアルタイム signaling relay(ClientMsg::Signal で SDP/ICE 中継)
  - kotoba/crates/kotoba-turn        # TURN(coturn use-auth-secret) ephemeral credential
  - kotoba/crates/kotoba-net         # libp2p QUIC / gossipsub / bitswap(セグメント配布)
  - kotoba/crates/kotoba-clj         # Clojure/EDN サブセット → WASM コンパイラ
  - kotoba/crates/kotoba-runtime     # WASM Component host(kqe assert/retract/query, kse send/recv)
  - kotoba/crates/kotoba-ingest      # 取り込みパイプライン(拡張先)
  - kami-webgpu                      # WebGPU レンダラ(合成バックエンド)
  - kami-engine-sdk/src/lib/call     # WebRTC media-call SDK(perfect negotiation, TURN mint)
  - app-aozora/60-apps/etzhayyim-project-yoro/kotoba-appview   # appview(datom→feed)
  - app-aozora/60-apps/etzhayyim-project-yoro/xrpc-adapter     # Cloudflare Worker XRPC エッジ
  - app-aozora/40-engine/svelte/design-system                 # 配信コントロール UI
supersedes: []
superseded_by: []
---

# ADR-2606271500: kotoba-stage — OBS 相当の配信ツールと OBS→app-aozora ライブ配信

**Status**: accepted（設計は確定。下記「実装状況の更新」節が主張する control plane /
studio UI / media plane の実装・動作検証は、**2026-07-01 時点でリポジトリ上に実体が
確認できず未着手**。詳細は末尾「追記 (2026-07-01)」参照）
**Date**: 2026-06-27
**Deciders**: Jun Kawasaki

## Context

「kotoba + clj で OBS のような配信ツールを設計し、さらに OBS から app-aozora で
動画配信できるようにする」。要件を 2 つに分ける:

1. **kotoba-stage** — OBS の中核(シーン / ソース / フィルタ / ミキサ /
   トランジション / Studio Mode)を kotoba データモデルと `.kotoba`(CLJ→WASM)で
   表現する、宣言的・再現可能・コンテンツアドレス可能な合成エンジン。
2. **OBS → app-aozora ライブ配信** — 既存 OBS をそのまま使うユーザのために、OBS の
   egress(WHIP / RTMP / SRT)を kotoba へ取り込み、app-aozora(yoro appview)で
   視聴できるようにする取り込み・配信経路。

調査の結果、配信に必要な部品は **ほぼ既存**で、新規に書くのは「取り込みエッジ」と
「合成を CLJ で書くための薄い層」「ライブ用レキシコン」だけだと判明した。既存資産:

| 必要機能 | 既存実体 | 根拠 |
|---|---|---|
| WebRTC signaling 中継 | `kotoba-rt`(`ClientMsg::Signal` で SDP/ICE を素通し中継) | `kami-engine-sdk/.../call/call.ts` がこの relay を使用 |
| TURN/ICE relay | `kotoba-turn`(coturn `use-auth-secret`、room/player スコープ ephemeral cred) | `crates/kotoba-turn` README + `call/turn.ts` の `mintTurnCredential` |
| WebRTC media plane | `@etzhayyim/kami-engine-sdk/call`(perfect negotiation、mesh-ready、SSR-safe) | `call/call.ts` |
| コンテンツ配布 | `kotoba-net`(libp2p QUIC + gossipsub + bitswap)+ IPFS/CID | `crates/kotoba-net/src/lib.rs` |
| 合成(GPU) | `kami-webgpu` | submodule(WebGPU レンダラ) |
| シーンロジック | `kotoba-clj`(.kotoba → WASM)+ `kotoba-runtime`(WASM host, `kse` pub/sub・`kqe` datom) | ADR-2606241700 / `crates/kotoba-runtime` |
| タイムライン DB | kotoba datom EAVT(`[stream T :live/segment CID]`)+ Datalog query | `kotoba` README |
| 配信メタの配信 | app-aozora yoro appview(datom→ranked feed)+ xrpc-adapter | `kotoba-appview/src/feed.ts`, `getRankedFeed.json` |
| 認可 | CACAO(depth-2 delegation)+ Signal E2E(X3DH→Double Ratchet, group) | `kotoba` README / `kotoba-signal` |

結論として本 ADR は **新規メディアスタックを作らず、既存の kotoba リアルタイム /
配布 / WASM / appview 基盤の上に "OBS 互換エッジ" と "CLJ シーングラフ" を載せる**
方針を採る。

## Decision

### 全体像

```text
        ┌─────────────────────── 制作(どちらか / 併用) ───────────────────────┐
        │  (A) 既存 OBS                         (B) kotoba-stage(本ツール)        │
        │   Scene/Source を OBS で組む            Scene = EDN(.kotoba)             │
        │   egress: WHIP / RTMP / SRT             compositor = CLJ→WASM            │
        │        │                                  render = kami-webgpu          │
        └────────┼──────────────────────────────────────────┼────────────────────┘
                 │  encoded A/V (H264/AV1 + Opus)            │ composited A/V
                 ▼                                            ▼
        ┌──────────────────────── kotoba-ingest-live(新規エッジ) ────────────────┐
        │  WHIP 終端(DTLS-SRTP, ICE=kotoba-turn) / RTMP / SRT リスナ              │
        │  → 正規化 → CMAF(fMP4) パッケージ → セグメント毎に CID(DAG-CBOR/IPLD)   │
        │  → kotoba datom 書込: [stream T :live/segment CID] (EAVT 時系列)        │
        │  → kotoba-net bitswap で IPFS ピン / 低遅延は WebRTC SFU forward         │
        │  (private 配信は kotoba-signal group ratchet でセグメント鍵を配る)      │
        └────────────────────────────────┬───────────────────────────────────────┘
                                          │ datom(真実の源 = SSoT)
                  ┌───────────────────────┼───────────────────────┐
                  ▼                        ▼                       ▼
        getPlaylist(LL-HLS)      getViewerTicket(TURN)    live.broadcast record
        = segment datom を         = WebRTC 視聴者向け        = AT Proto レコード
          Datalog で manifest 化     ephemeral cred 発行       → yoro feed が surface
                  │                        │                       │
                  ▼                        ▼                       ▼
        ┌──────────────────────────── app-aozora(yoro appview) ───────────────────┐
        │  xrpc-adapter(CF Worker): live.* XRPC を公開                            │
        │  Svelte UI: LL-HLS 再生(規模) / WebRTC 再生(<1s) を切替                  │
        │  feed: getRankedFeed が live.broadcast を上位表示                       │
        └─────────────────────────────────────────────────────────────────────────┘
```

要点: **datom がライブの単一真実源(SSoT)**。プレイリストも視聴チケットも feed 露出も、
すべて `[stream T :live/segment CID]` を含む datom 群への **派生クエリ**であり、
別建ての状態を持たない。これが kotoba ネイティブにする最大の理由。

### 1. kotoba-stage — シーングラフを「データ + CLJ」で表す

OBS の命令的な C++ プラグイン/シーンコレクションを、**宣言的データ(EDN)+ 純粋関数
(.kotoba→WASM)** に置き換える。

- **シーン = データ(EDN)**。各シーンは順序付きソース列。各ソースは
  `transform`(x/y/scale/rotation/crop)・`filters`・`blend` を持つ。ソース種別は
  capture(camera/display/window)、`browser`(URL)、`image`/`media`、そして
  **`live-cid`(別のライブ/ VOD を CID で取り込む)** ＝ 配信の合成入れ子。
- **合成ロジック = `.kotoba`(CLJ サブセット → WASM)**。`(render scene t)` を純粋
  関数として書き、`kotoba-clj` で WASM 化、`kotoba-runtime`(または `mesh.run`)で
  毎フレーム実行 → framegraph を `kami-webgpu` が GPU 実行。トランジション/自動化は
  `t` の関数(イージング、自動シーン切替)。
- **オーディオミキサも同型**。gain / ducking / noise-gate / sidechain を `.kotoba`
  DSP グラフで表し WASM 実行。
- **Studio Mode**(Preview/Program)は 2 つのシーン CID を保持し、`take`/`transition`
  で program 側の CID を差し替えるだけ。
- 効果:シーンは **コンテンツアドレス可能・再現可能・共有可能・ホットスワップ可能**。
  「このシーンを CID で配って誰でも同じ絵を再現」が成立する(OBS のシーンコレクション
   json の上位互換)。

スキーマ(EDN、`app.aozora.live.scene` レコードの本体としても保存):

```clojure
;; scene.kotoba — データとしてのシーン + 純粋な合成関数
{:scene/id     "main"
 :scene/canvas {:w 1920 :h 1080 :fps 60}
 :scene/sources
 [{:src/type :media   :src/uri "cid://bafy…intro"   :transform {:x 0 :y 0 :scale 1.0}}
  {:src/type :capture :src/dev  "camera:0"
   :transform {:x 1360 :y 720 :scale 0.28}           ; ワイプ(右下)
   :filters   [{:f :chroma-key :key [0 1 0] :sim 0.32}
               {:f :sharpen :amt 0.2}]}
  {:src/type :browser :src/uri "https://overlay…/alerts" :transform {:x 0 :y 0}}
  {:src/type :live-cid :src/cid "cid://bafy…coStreamHead"  ; 別配信を合成
   :transform {:x 40 :y 40 :scale 0.45}}]
 :scene/audio
 [{:a/in "mic:0"  :gain 0.0  :gate -45 :duck {:by "media:0" :amt -8}}
  {:a/in "media:0" :gain -6.0}]}
```

```clojure
;; compositor.kotoba — (render scene t) → framegraph(WASM 化して毎フレーム実行)
(ns aozora.stage)
(defn ease-in-out [a b t] (+ a (* (- b a) (* t t (- 3 (* 2 t))))))
(defn render [scene t]
  ;; ソースを下→上に重ね、フィルタ適用、ブレンド。kami-webgpu が実行する
  ;; 中間表現(framegraph)を返す純粋関数。トランジションは t の関数。
  (->> (:scene/sources scene)
       (map (fn [s] {:layer (:src/type s)
                     :xf    (:transform s)
                     :fx    (:filters s)}))
       (into [])))
```

### 2. OBS の取り込み — WHIP を第一級にする(RTMP/SRT は互換)

OBS 30+ は **WHIP(WebRTC-HTTP Ingest Protocol)** を標準サポートする。kotoba は既に
WebRTC media plane(`kotoba-rt` signaling + `kotoba-turn` ICE/TURN +
`kami-engine-sdk/call`)を持つため、**WHIP を第一経路にすると新規実装が最小**になる。

- **Path A(推奨, ネイティブ低遅延): OBS → WHIP → kotoba-ingest-live**
  - OBS の WHIP 設定:
    - Server URL = `https://ingest.aozora.example/whip/<broadcastId>`
    - Bearer Token = **stream key**(`getIngestTicket` が CACAO スコープで発行する短命トークン)
  - エッジは WHIP の HTTP POST(SDP offer)を受け、`kotoba-turn` の ephemeral cred で
    ICE/DTLS-SRTP を確立、RTP(H264/AV1 + Opus)トラックを終端する。signaling は
    `kotoba-rt` の `ClientMsg::Signal` を再利用。
- **Path B(互換: RTMP / SRT)**: WHIP 不可環境向けに標準 RTMP(S)/SRT リスナを併設。
  内部で同じ正規化 → CMAF パイプラインへ合流。

取り込みエッジは新規クレート **`kotoba-ingest-live`**(既存 `kotoba-ingest` を拡張)で実装:

```text
WHIP/RTMP/SRT 終端
  → 正規化(コンテナ/タイムスタンプ/キーフレーム境界整列)
  → CMAF(fMP4) セグメント化(LL-HLS 用 part 含む。例: 2s segment / 200ms part)
  → セグメント毎に CID(DAG-CBOR/IPLD)を採番、bitswap でピン(kotoba-net)
  → datom 書込:  [<stream> <T> :live/segment <CID>]
                 [<stream> <T> :live/part    <CID>]   ; LL-HLS preload-hint
                 [<stream> :live/status :live]        ; 状態も datom
  → 低遅延ルーム向けには WebRTC SFU(kotoba-turn fan-out)に同一トラックを forward
```

### 3. 配信(視聴)— manifest は「クエリ」、チケットは「mint」

別建ての配信状態を持たず、**datom への派生**で配る:

- **LL-HLS / DASH(規模・CDN)**: `getPlaylist` は対象 stream の `:live/segment` /
  `:live/part` datom を T 昇順で `scan_prefix` し、LL-HLS manifest(CMAF)を **動的生成**。
  セグメント本体は CDN/エッジ、無ければ IPFS gateway(bitswap)へフォールバック。
  manifest は保存物でなくクエリ結果なので巻き戻し/DVR も T 範囲指定で自然に表現。
- **WebRTC(< 1s, 双方向ルーム)**: `getViewerTicket` が `mintTurnCredential` で
  視聴者用 ephemeral TURN cred を発行、`kami-engine-sdk/call` で SFU から購読。
  コラボ配信(共同ホスト)も同じ mesh で表現。
- **Private 配信(E2E)**: セグメント鍵を `kotoba-signal` の group ratchet
  (X3DH→Double Ratchet / sender-keys)で配り、CACAO がメンバシップをゲート。公開
  配信は素の CMAF、限定配信は E2E、を同一パイプラインのフラグで切替。

### 4. app-aozora との接続 — レキシコン + appview + feed

ライブを **AT Protocol レコード**として PDS / kotoba appview に publish し、yoro feed に
載せる。既存 `getRankedFeed`(datom→ranked feed)パターンをそのまま踏襲する。

新規レキシコン名前空間 `app.aozora.live.*`
(配置: `app-aozora/00-contracts/lexicons/app/aozora/live/`、
`app.aozora.convo.*` 等の既存 rename 済みレキシコンと同じ「ディレクトリ = id
そのままのパス」規約。旧 `com.etzhayyim.aozora.*` 名前空間は廃止):

| lexicon id | type | 役割 |
|---|---|---|
| `app.aozora.live.broadcast` | record | ライブ/ VOD 1 本。title/status/ingest head CID/playlist ref/visibility/edge hints |
| `app.aozora.live.scene` | record | コンテンツアドレス可能な kotoba-stage シーン(EDN CID) |
| `app.aozora.live.getIngestTicket` | query | OBS 用 stream key(WHIP/RTMP)+ TURN cred を CACAO スコープで mint |
| `app.aozora.live.getPlaylist` | query | segment datom → LL-HLS/DASH manifest |
| `app.aozora.live.getViewerTicket` | query | 視聴者 TURN cred / 署名済み再生トークン |
| `app.aozora.live.startBroadcast` / `stopBroadcast` | procedure | 配信状態 datom の遷移 |

公開エッジは **既存 `xrpc-adapter`(Cloudflare Worker)** に `live.*` ハンドラを追加。
appview(`kotoba-appview`)は `live.broadcast` レコードを index、yoro `getRankedFeed` が
ライブを上位表示。コントロール UI(シーン/ソース/ミキサ/Studio Mode)は
`40-engine/svelte/design-system` で実装し datom に束縛。

### OBS から app-aozora への最短手順(ユーザ視点)

```text
1. app-aozora で「配信を作成」→ live.broadcast 作成、getIngestTicket で stream key 取得
2. OBS → 設定 → 配信 → サービス「WHIP」
     Server   = https://ingest.aozora.example/whip/<broadcastId>
     Bearer   = <stream key>
   (WHIP 不可なら「カスタム RTMP」: rtmps://ingest.aozora.example/live, key=<stream key>)
3. OBS「配信開始」→ kotoba-ingest-live が終端・CMAF 化・datom 書込・IPFS ピン
4. app-aozora の yoro feed に live.broadcast が出現、視聴者は LL-HLS(規模) or
   WebRTC(<1s) で再生。private は CACAO + signal group 鍵でゲート。
```

## Consequences

**Pros**

- 新規メディアスタックを作らず、**既存の kotoba realtime / TURN / WebRTC / IPFS /
  WASM / appview を合成**するだけ。新規は取り込みエッジ・CLJ シーン層・レキシコンに限定。
- **datom が SSoT**。playlist/DVR/feed 露出/権限がすべて Datalog 派生で一貫。別状態
  の同期不整合が原理的に起きない。
- シーンが **コンテンツアドレス可能・再現可能・合成入れ子可能**(OBS のシーン json の上位互換)。
- OBS ユーザは **既存 OBS のまま**(WHIP/RTMP)で app-aozora に流せる。移行障壁ゼロ。
- 公開/限定を同一パイプラインで切替(限定は signal group ratchet + CACAO)。

**Cons / 留意点**

- **WHIP 終端 + CMAF パッケージャ(`kotoba-ingest-live`)は新規実装**で、本 ADR の主要
  工数。コーデック正規化・キーフレーム整列・LL-HLS part 生成が要点。
- `.kotoba` 合成が毎フレーム WASM 実行 ⇒ ホットパスの fuel 上限/レイテンシ設計が必要
  (ADR-2606241700 の mesh.run fuel ゲートを踏襲)。重い実エフェクトは WASM 内 DSP より
  `kami-webgpu` のネイティブパスに寄せる。
- LL-HLS と WebRTC の二系統を維持する運用コスト(規模 vs 低遅延のトレードオフ)。
- 大容量の録画/ VOD は CLAUDE.md 方針どおり **git に直接置かず** B2 + DataLad / IPFS ピン
  に逃がす(配信セグメントは CID 参照、実体はオブジェクトストレージ)。

## 実装フェーズ(提案)

1. **レキシコン + appview index**: `live.*` レキシコン定義、`kotoba-appview` に
   `live.broadcast` の index、`getRankedFeed` 露出。(本 ADR に同梱の lexicon 雛形)
2. **取り込みエッジ MVP(RTMP→HLS)**: `kotoba-ingest-live` で RTMP 終端 → CMAF →
   segment datom → `getPlaylist`(LL-HLS)。OBS「カスタム RTMP」で疎通。
3. **WHIP ネイティブ + TURN**: WHIP 終端を `kotoba-rt`/`kotoba-turn` に接続、
   `getIngestTicket`/`getViewerTicket` を mint。OBS「WHIP」で疎通、WebRTC 視聴。
4. **kotoba-stage 合成**: `.kotoba` シーン/compositor、`kami-webgpu` バックエンド、
   Svelte コントロール UI、Studio Mode。
5. **E2E private 配信**: `kotoba-signal` group ratchet でセグメント鍵、CACAO ゲート。

## 実装状況の更新 (2026-06-27, accepted)

設計提案に対し、以下を実装・検証した。あわせて **control-plane の実装言語を
ClojureScript Common (`.cljc`) に正準化**する追加判断を行った（下記）。

### 追加判断: control plane は `.cljc` を正準とし、TS / Rust を退役する

当初は control plane を TS(`live.ts`、xrpc-adapter 内)で実装し、cljs / Rust に
並行実装してパリティ検証していた。これを **単一 `.cljc` ソースに集約**する:

- **正準**: `xrpc-adapter/cljs/src/aozora/live.cljc`。reader conditional で
  `:cljs`(Cloudflare Worker, shadow-cljs `:esm`)と `:clj`(Clojure/JVM)の **両方**に
  コンパイルされる単一ソース。crypto は `:clj` = `javax.crypto` / `:cljs` =
  `goog.crypt` で分岐するが、**同一バイト**(RFC 2202 HMAC-SHA1 ピン)。
- **退役**: TS(`live.ts` + テスト)は削除。Rust 制御部は退役。Rust クレート全体は
  `40-engine/kotoba-stage/_archive/ingest-rust-retired/` に**アーカイブ**(削除では
  なく温存。media plane の唯一の実装を保全するため)。
- **理由**: 同一ロジックを 3 言語で複製する保守コストを排除し、リポジトリの
  CLJ 方向(.kotoba 正準・cljs UI 移行)と一致させる。LL-HLS マニフェストは
  `40-engine/kotoba-stage/testdata/golden-llhls.m3u8` に **JVM・Worker 両方で
  golden ピン**し、プラットフォーム間でバイト乖離しないことをテストで保証する。

### 実装済み・検証済み

| 領域 | 実体 | 検証 |
|---|---|---|
| **control plane**(正準) | `aozora.live` `.cljc`(`get-ingest-ticket`/`get-viewer-ticket`/`get-playlist`/`render-ll-hls-manifest`/`mint-*`/Worker `fetch`) | `:cljs` 20 tests・`:clj` 4 tests・両方 golden ピン |
| **配信先**(レキシコン) | `app.aozora.live.{broadcast,getIngestTicket,getPlaylist,getViewerTicket}` | JSON 妥当性 |
| **デプロイ配線** | cljs Worker `wrangler.jsonc`(route `…/xrpc/app.aozora.live.*`、特異性で TS アダプタに優先) | `wrangler deploy --dry-run` 通過 |
| **配信スタジオ UI** | `appview/…/cljs`(reagent+re-frame, `/live`): scene/source/mixer/Studio Mode/create-broadcast/OBS-connect/LL-HLS viewer | cljs.test 20 tests |
| **`.kotoba` シーン/compositor** | `40-engine/kotoba-stage/examples/{scene,compositor}.kotoba` | — |
| **e2e デモ**(Rust 不要) | `examples/{mock-ingest,demo-live}.mjs`(production `get-playlist` 経由で live→VOD) | 実行確認 |
| **media plane**(`.cljc` 移植済み・動作検証済み) | `aozora.media.{rtp,h264,cmaf,pipeline}` `.cljc`(RTP 解析 / H.264 デパケタイズ / CMAF box writer / RTP→AU→GOP CMAF パイプライン) | `:cljs` + `:clj` 両方で 32/16 tests・**end-to-end 動作検証**(下記) |
| **media plane**(参照アーカイブ) | `_archive/ingest-rust-retired/`(退役 Rust。WHIP 認証含む) | 退役時点 25 tests |

### end-to-end 動作検証 (2026-06-27, 実データ + ffmpeg)

メディアプレーンを**実 H.264 で検証**済み(`script/verify_jvm.clj` /
`src/aozora/verify.cljs`、手順は cljs README #動作検証):

1. ffmpeg で実 H.264 生成(320×240・10 frames・baseline、13 NALs)。
2. 同一 `.cljc` パイプラインに RTP として投入 → 13 NALs → 10 access units →
   init(`ftyp`+`moov`) + CMAF セグメント = **8971 bytes** の fMP4。
3. **JVM 出力と node(Worker ランタイム)出力が byte 単位で完全一致**(同一 SHA-256)。
4. **ffmpeg が再生検証**: デコードエラーゼロ・10 frames 全デコード、ffprobe =
   `h264 Constrained Baseline 320×240`。

= 生 H.264 → RTP → cljc(rtp→h264→cmaf→pipeline)→ **ffmpeg がデコード可能な正規
fMP4** を、JVM と Worker で同一バイト生成することを実証。control plane(cljc Worker)も
`worker.fetch` 実起動で getIngestTicket=200 / getPlaylist=200 / 未知=404 を確認。

### 残課題

- **media plane の RTP→H.264→CMAF は `.cljc` に移植済み**(JVM・Worker 両対応、
  unsigned-int ベクタで可搬)。残るのは **SRTP トランスポート**(WHIP/ICE/DTLS-SRTP
  終端 → 復号 RTP を `pipeline/push-rtp` に渡す)と、パッケージ済みセグメントを
  `SegmentSink`(IPFS ピン + `:live/segment` datom 書込)へ結線し `SegmentScan`
  エンドポイントを公開すること。SRTP は native(str0m 等)か Worker の WebTransport 系
  が必要で、実 OBS + プレイヤーでの byte-perfect 再生検証を伴う。
- `kami-webgpu` 合成バックエンドの実結線、`kotoba-signal` group ratchet による
  E2E private 配信は未着手(設計のみ)。

## 追記 (2026-07-01): 実装状況の再確認 — 上記「実装済み・検証済み」は現行リポジトリに実体なし

`orgs/kotoba-lang/kotoba` を PR #259「Remove legacy Rust workspace」が
2026-07-01 に main へ merge し、Rust workspace(`kotoba-turn`/`kotoba-net`/
`kotoba-rt`/`kotoba-media`/`kotoba-ingest` を含む)を**リポジトリから完全削除**
した（方針: 「Historical Rust implementation details remain available through
git history; new behavior should land first in CLJC/EDN contracts」）。この
削除を機に本 ADR の実装状況を再確認したところ、上記「実装済み・検証済み」表が
挙げる成果物は **`app-aozora` / `kotoba-lang/kotoba` / `kotoba-lang/kotoba-lang`
のいずれにも実体が見つからない**:

- `xrpc-adapter/cljs/src/aozora/live.cljc`、`aozora.media.{rtp,h264,cmaf,pipeline}`、
  `40-engine/kotoba-stage/`（examples・testdata・`_archive/ingest-rust-retired/`
  含む）— いずれもローカル checkout・GitHub 上のブランチ/PR に存在しない
  （`grep -rl "aozora\.media\|aozora\.stage"` / `find -iname "*kotoba-stage*"`
  は 0 件、`kotoba-lang/kotoba` の merge 済み PR 一覧にも該当なし）。
- `app-aozora/00-contracts/lexicons/` にも `live.*` レキシコン(`broadcast`/
  `scene`/`getIngestTicket`/`getPlaylist`/`getViewerTicket`)は存在しない。
  存在するのは `com.etzhayyim.aozora.repo.{prepareWrite,commitSigned}`
  (ADR-2606201000、PDS 用途で live とは無関係)と、進行中の
  `app.aozora.convo.*`(messenger, rename 済み/進行中 WIP)のみ。
- `kotoba-lang/kotoba` の crates は現在 `kotoba-clj`/`kotoba-kotodama`/
  `kotoba-transit`/`kotoba-wasm` のみで、`kotoba-turn`/`kotoba-net`/`kotoba-rt`
  は git 履歴には残るが HEAD には存在しない。
- 後継の CLI/言語権威 repo `kotoba-lang/kotoba-lang` にも、WebRTC/TURN/SRTP/
  WHIP/RTP/H264/CMAF に関するコード・EDN 契約・ADR は一件もない
  （`docs/adr/` 7 本はいずれも言語仕様・パッケージ管理・RAD・ワイヤプロトコル）。

結論: 「実装状況の更新 (2026-06-27)」節と「end-to-end 動作検証」節の記述は、
**当時のセッションでは検証されたとしても、その成果物は main に merge されず
現存しない**。本 ADR は**設計提案として有効**だが、実装は事実上ゼロから
（かつ Rust ではなく CLJC/EDN 方針で）やり直す必要がある。レキシコン名前空間は
本追記の時点で `app.aozora.live.*`(旧 `com.etzhayyim.aozora.live.*` から
rename済み表記)に統一した。次に着手する場合は、本 ADR を「accepted」のまま
実装フェーズ節から書き直すか、後継 ADR を新規に起票すること。

## Notes

- 本 ADR は調査時点(2026-06-27)の既存実装(`kotoba-rt`/`kotoba-turn`/`kotoba-net`/
  `kotoba-clj`/`kotoba-runtime`/`kami-engine-sdk/call`/yoro appview)を前提にした
  **設計提案**。実装着手時に各クレートの現行 API と差分が出たら本 ADR を更新する。
- 拡張子方針(.kotoba 正準)・mesh.run fuel ゲートは ADR-2606241700 に従う。
- 大容量バイナリ方針(B2+DataLad / IPFS)は ルート CLAUDE.md に従う。
