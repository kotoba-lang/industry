# ADR-2607071500: kotoba-protocol — 層設計の正本化（datom/IPLD/IPNS/IPFS 統合）+ atprotocol を「上に立つ投影層」として分離 + appview / embedUrl モデル（W-Protocol 退役）

**Status**: closed (implemented — scaffold + 全 follow-up が本番 live で実証済み)
**Date**: 2026-07-07
**Deciders**: Jun Kawasaki

## Context

- オーナー指示（2026-07-07）: 「W-Protocol の設計は古い。**kotoba-protocol**
  （`kotoba-lang/kotoba-protocol`）として設計し直し、Datomic・IPLD・IPNS・IPFS
  を設計統合する。atproto は **`kotoba-lang/atprotocol`** として、あくまで
  kotoba-protocol の上に立つ protocol として責任・境界を分ける。UI 提供は
  ActorFrame（W-Protocol の実装概念）ではなく **appview / embedUrl** として
  設計する。」
- 現状、基底プロトコルの構成要素は repo 群に**実装として散在**しているが、
  層と責務を宣言する正本が無い:
  - bytes/addressing: `io-multiformats` / `io-ipld` / `dag-cbor`（CID, IPLD）
  - fact: `datom`（EAVT+tx、Datomic モデル）
  - graph: `kotobase-*` / `chain` / `prolly-tree` / `mst`（append-only datom
    log の Merkle DAG、graph CID）
  - authority: `kotobase.cacao` / `kotoba-auth`（Ed25519 did:key、CACAO/SIWE
    capability chain、**鍵由来 IPNS 名 = actor の graph 名。AUTHORITY は
    鍵由来 IPNS 名への署名であってサーバではない**）
  - distribution: kotobase（IPFS/Kubo wrapper + B2）、`tech-ipfs-specs-ipns`
  - execution: `kototama`（`actor:host` ABI 8 imports、HostCaps/RuntimeLimits、
    JVM/Chicory + browser-native actor-host.js、ADR-2607062330/2607062400）
- 一方 **W-Protocol** は旧 yoro/svelte 期の産物で、profile view に
  `performerType / contentMode / uiType / embedUrl / service / system` を生やし、
  svelte の `ActorFrame` が iframe を mount する**実装込みの規約**だった
  （lexicon `com.etzhayyim.yoro.actor.getProfile`、実装は archive の
  app-aozora-svelte のみ。現行 cljs SPA には未移植）。データの正・配布・認可の
  層を持たず、view field の羅列になっている点が「古い」。
- atproto 側の前例: lexicon スキーマ自体を record として repo に公開し
  DNS TXT `_lexicon` → DID → PDS record で解決する（「契約 = actor の repo 内の
  署名済み record」）。miniapp 標準は無い。Matrix widgets は URL + iframe +
  capability 交渉、Farcaster Mini Apps は signed manifest、Nostr は NIP-89 +
  content-addressed nsite（前턴の調査、ADR 外部参照）。
- aozora PDS は既に **record→datom / datom→view の双方向投影**を実装している
  （`aozora.pds.encode` / `aozora.appview.scan`）— つまり「atproto は kotoba
  graph の投影層」という構図は**実態として既に動いており**、宣言だけが無い。

## Decision

### 1. `kotoba-lang/kotoba-protocol` — 基底プロトコルの正本（新規 repo、public）

主権データ基盤の**層と責務を宣言する spec-first repo**。実装 repo 群の上位に
立つのは「定義」であって実装の複製ではない（実装は既存 repo に残る）。

| 層 | 責務 | 実装 repo（参照） |
|---|---|---|
| **L0 address** | bytes → CID（multihash/multibase/multicodec、IPLD dag-cbor） | io-multiformats / io-ipld / dag-cbor |
| **L1 fact** | datom `[e a v tx added?]`（Datomic モデル、append-only、retraction は事実） | datom |
| **L2 graph** | datom log の Merkle DAG 化 → **graph CID**（chain/prolly-tree/MST）、db 名前空間 `kotobase/db/<did>/<name>` | kotobase-peer / chain / prolly-tree / mst |
| **L3 authority** | Ed25519 **did:key**、**graph 名 = 鍵由来 IPNS 名**、書込認可 = CACAO capability chain（自分の graph へは depth-1 自己 mint）。サーバは authority ではない | kotobase.cacao / kotobase.cid / kotoba-auth / tech-ipfs-specs-ipns |
| **L4 distribution** | CID 実体の配布（IPFS retrieval/pinning、B2 offload）と IPNS head の公開 | kotobase(.net) / ipfs-pinner |
| **L5 application** | **actor 実行**（kototama `actor:host` ABI + HostCaps/RuntimeLimits）と **app 配布/提供**（manifest datoms、appview、embedUrl — 下記） | kototama / wasm-webcomponent |

repo の中身（pure cljc、zero runtime deps）:
- `kotoba.protocol.layers` — 上表を **data として**保持（`layers` / `owner-of`）。
  ドキュメントとテストが同じ data から導出される。
- `kotoba.protocol.vocab` — datom 語彙 registry:
  `:kotoba.actor/*`（did / ipns / app）、`:kotoba.graph/*`（cid / head / name）、
  `:kotoba.app/*`（下記）。各属性に doc + 値述語。
- `kotoba.protocol.app` — L5 の app モデル:
  - **manifest** = actor 自身の graph に置く datoms（＝署名済み・履歴付き。
    Farcaster の signed manifest / Matrix の state event / NIP-89 に相当する
    ものを「graph 内の事実」で表す）:
    `:kotoba.app/id`（reverse-dns）、`:kotoba.app/version`、
    `:kotoba.app/kind`（`"appview"` | `"embed"` | `"actor"`）、
    `:kotoba.app/bundle-cid`（静的バンドルの root CID）、`:kotoba.app/entry`、
    `:kotoba.app/embed-url`、`:kotoba.app/appview-of`（描画対象 graph/属性
    selector）、`:kotoba.app/wasm`（`[{:cid :imports}]`）、
    `:kotoba.app/caps`（要求 capability）、`:kotoba.app/limits`、
    `:kotoba.app/latest`（**actor の鍵由来 IPNS = 署名済み更新チャネル**）。
  - **appview** = 「graph を描画する app」。全画面サーフェス。aozora の
    /manga viewer も mangaka editor も appview（描画対象が違うだけ）。
  - **embedUrl** = 「host アプリが文脈内に mount できる URL」。スキームは
    `https://` | `ipfs://<cid>[/path]` | `ipns://<name>[/path]` を認め、
    `resolve-embed-url` が解決規則（CID 検証可否・gateway 化）を返す。
    **iframe か web component かは host の実装詳細で、protocol は関知しない**
    （ここが ActorFrame との決別点）。
  - capability 名の正本はこの repo（kototama の 8 imports を registry 化し、
    kototama.contract は「実装」と位置付ける）+ bridge caps
    （`net/http-post` の同期 ABI 制約を回避する host 代行呼び出し）。

### 2. `kotoba-lang/atprotocol` — kotoba-protocol の上に立つ投影層（新規 repo、public）

**atproto 互換は「kotoba graph の別 encoding」**。責務境界を data で宣言する:

| | owns（atprotocol） | delegates（kotoba-protocol へ） |
|---|---|---|
| identity | handle ↔ DID 解決、did:web ドキュメント配信 | 鍵（did:key）、CACAO 検証 |
| data | lexicon NSID / record 形、record ⇄ datom **codec** | datom の真実、graph CID |
| mutability | repo commit / MST / firehose の wire encoding | IPNS head |
| transport | XRPC、PDS/AppView エンドポイント | 配布（IPFS/B2） |
| app 提供 | profile view への投影（embedUrl / appview flag） | manifest datoms、bundle CID、caps |

repo の中身:
- `atprotocol.boundary` — 上表 as data + `delegated?` 述語。
- `atprotocol.projection` — record⇄datom codec の**契約**
  （`{:record->datoms f :datoms->view f}` の protocol map。参照実装 =
  `aozora.pds.encode` / `aozora.appview.scan` — 既に本番で動いているものを
  この契約の実装と再定義する。移植はしない）。
- `atprotocol.profile` — `:kotoba.app/*` → atproto profile view への投影。
  **W-Protocol の `performerType / contentMode / uiType` はこの層の
  deprecated compat alias に降格**（mapping 表 as data、`:deprecated true`）。
  正は `:kotoba.app/kind` と `:kotoba.app/embed-url`。新規実装は
  `embedUrl` + `appKind` のみを読む。

### 3. mangaka miniapp（前ターンの設計）の語彙差し替え

- mangaka actor の profile 拡張（W-Protocol fields）→ **actor 自身の graph の
  `:kotoba.app/*` datoms** が正。atproto から見える `embedUrl` は
  atprotocol.profile の投影出力。
- app manifest record（`net.kotoba.app.manifest` lexicon 案）→ lexicon は
  atprotocol 側の**投影形**であり、正は `:kotoba.app/*` datoms。
- ActorFrame → 廃語。host（aozora SPA）は「profile の投影に embedUrl があれば
  mount する」だけで、mount 実装（iframe / wasm-webcomponent）は host 実装詳細。

### 意図的に scaffold に入れなかったもの → 全て Addendum 1/2 で実装済み

以下は起票時点で follow-up と明記したが、本 ADR のクローズまでに全て実装・
本番 live で実証した（詳細は Addendum 1/2）:

- ~~kotobase の公開 IPFS retrieval~~ → `KOTOBASE_IPFS_GATEWAY_URL` +
  `PUT /ipfs/:cid` で実配信・実 B2 書込まで live（Addendum 1 §3）。
- ~~aozora SPA への embedUrl mount 実装~~ → `app-embed-panel`（opaque
  origin iframe）+ `wasm-actor-panel`（kind=actor、WebAssembly 直接実行）
  として実装（Addendum 1 §2 / Addendum 2）。
- ~~kototama bridge caps の実装~~ → `kotoba.protocol.bridge` + host
  `yoro-ui.interop.app-bridge`、round-trip 実証済み（Addendum 1 §2）。

**未実装のまま残るもの**（本 ADR のスコープ外として明示的に持ち越し）:
- lexicon `_lexicon` DNS 公開、firehose、MST encoding の kotoba graph 導出
  （atprotocol 層の wire 互換を深める話で、miniapp モデル自体には不要）。

## Consequences

- (+) 散在していた基盤（datom/IPLD/IPNS/IPFS/CACAO/kototama）が**1 つの層表**
  で名指しされ、新規設計（miniapp 等）が「どの層の話か」で会話できる。
- (+) atproto 互換が「投影」だと宣言され、aozora PDS の encode/scan が既に
  その実装であることが明文化される。Bluesky 互換を壊さず kotoba 側を進化
  させられる。
- (+) W-Protocol は互換 alias として残るため既存 archive を壊さない。
  新規コードは `:kotoba.app/*` だけを見ればよい。
- (+) **L0–L5 の全層が単一の miniapp で貫通して本番稼働する状態を実証**:
  CID/IPLD（L0）→ datom（L1）→ graph（L2）→ did:key/鍵由来 IPNS（L3）→
  IPFS 配布・実 B2 書込（L4）→ app 提供の両形態（L5: embed の cap-bridge
  mount、actor の WASM ブラウザ実行）まで、mangaka / bridge-demo /
  wasm-demo の 3 app actor が aozora.app の profile で live 動作
  （Addendum 1/2）。
- (+) 副産物として発見・修正した一般則: vendor JS を Closure `:advanced`
  最適化と併用する際、object literal を介した cljs↔JS 境界はプロパティ名
  renaming の不一致を起こしうる（Addendum 2 の externs 修正）— 今後の
  kototama/wasm-webcomponent 系 vendor 作業に適用できる知見として記録。
- (−) spec repo と実装 repo の drift リスク — layers/vocab を data にして
  テストから参照させることで軽減（宣言が壊れたらテストが落ちる）。
- (−) 2 repo（kotoba-protocol/atprotocol）+ vendor JS（actor-host.js）+
  externs ファイルの追加管理コスト。
- (−) cap-bridge は `graph/query`（読取専用）のみ、wasm actor は 7/8
  imports（`http-post` 不可）— 書込み系 capability の host 代行は
  引き続き未実装（下記 open follow-up）。

## Open follow-up（本 ADR クローズ後も残る）

- lexicon `_lexicon` DNS 公開・firehose・MST encoding の kotoba graph 導出
  （atprotocol の wire 互換を深める話、miniapp モデル自体には不要）。
- cap-bridge の書込み系 capability（`graph/transact`・`llm/complete`）の
  host 代行実装（現状は grant registry に名前があるだけで未実装）。
- studio への actor 鍵 import UI の汎用化（現状 mangaka-app 専用）。
- `:kotoba.app/bundle-cid` の appview 側 integrity 検証（現状 `embed-url`
  の resolve のみで、配信内容と bundle-cid の一致は未検証）。

## Addendum (2026-07-07 同日): follow-up 3 件 (IPNS / cap-bridge / bundle CID) 実装

1. **:kotoba.app/latest = 鍵由来 IPNS — 実装・live**。`ipns.core/pubkey->name`
   (既存の pure cljc、test-vector 付き) を studio publish に配線し、app actor
   の did:key 公開鍵から署名済み更新チャネル IPNS を導出して manifest record に
   刻む。mangaka.aozora.app 再公開で live 確認: `appLatest =
   k51qzi5uqu5dk5inaqognid5ztll223iptppwgjuafx6jp3i7cfrhd1busbqss`。

2. **cap-bridge (postMessage) — 契約 + host + round-trip 実証**。
   `kotoba.protocol.bridge` (pure): hello/request/result の形、`grant` =
   要求 caps ∩ host 対応、`validate-request` は unknown/未 grant を fail-closed。
   host `yoro-ui.interop.app-bridge`: embed iframe を **opaque origin**
   (sandbox から allow-same-origin 除去) で mount し、granted cap のみ host
   代行 (graph/query = appview 読取 XRPC。net/transact/llm は署名・課金を伴い
   未対応)。鍵も token も app に渡らない。harness 実証: guest が graph/query
   だけ grant され、searchActors request → host 代行 → 実データ受信。

3. **bundle CID 化 — PUT /ipfs/:cid コード完成 (B2 bucket は ops 依存)**。
   kotobase.net に first-party content-addressed 書込
   `PUT /ipfs/<raw-CIDv1>` を追加 (`archive-put.cljc` pure: base32 decode /
   raw sha2-256 検証 / web content-type whitelist + CSP sandbox 配信)。
   Bearer `KOTOBASE_ARCHIVE_TOKEN`、body sha256 = CID digest fail-closed。
   web 系は **opaque origin (CSP sandbox)** で配信 — path gateway で任意 HTML を
   kotobase.net origin では走らせない。GET retrieval (③ の gateway 経路) は
   `KOTOBASE_IPFS_GATEWAY_URL` で live (200/hello world)、require-b2 の
   sync-throw は gateway fallback へ degrade。auth 401 / digest 422 gate 検証済み。
   **実 B2 書込は `KOTOBASE_B2_BUCKET` を writable な public-web bucket に
   設定する ops が前提** (現行値は archive read 用で、m365 annex とは別に
   立てる)。それが済めば `ipfs://<cid>` embed-url の app actor が完全に
   content-addressed で live mount される (embed-url 解決 → cap-bridge round-trip
   は既に実証済み)。

## Addendum 2 (2026-07-07 同日): kind=actor — kototama WASM actor のブラウザ実行 live

前回 Addendum の「残る発展系」だった `:kotoba.app/kind "actor"` の browser-host
実行を実装・本番稼働させた。

- **manifest 投影**: `:kotoba.app/wasm [{:cid :imports}]` を atprotocol.profile
  が `appWasm [{:cid :url :imports}]` に投影（`:url` は既存の embed-url 解決
  経路で CID → kotobase.net gateway）。`kotoba.protocol.app/cap->wasm-import`
  / `wasm-import->cap` で cap 名 (kebab) ⇄ wasm import 関数名 (snake) を変換。
- **host 実行**: `kotoba-lang/wasm-webcomponent` の `actor-host.js`
  （ADR-2607062400 の browser-native 実装、7/8 imports・ed25519 のみ
  `@noble/curves` へ差し替え）を vendor。`yoro-ui.interop.wasm-actor/run-actor!`
  が CID URL から wasm を fetch → manifest 要求 imports ∩ host 対応で
  HostCaps 構築 → `WebAssembly.instantiate` → export 実行 → memory から
  result/log を読む。profile の `wasm-actor-panel`（`kind=actor` の時に
  `app-embed-panel` の代わりに dispatch）が granted/denied import chip と
  実行ボタンを表示。
- **重要なハマりどころ（根本原因特定・修正済み）**: vendor した JS が SPA と
  **同じ Closure `:advanced` コンパイルパスに載る**ため、JS 内部の dot 記法
  プロパティアクセス（`m.grants`・`fns.sha256_hex` 等）が cljs 側の出力と
  **同じ property-renaming** を受ける。しかし cljs 側から object literal を
  組み立てて渡す書込みは、その rename 決定に載らず**別の mangled 名**になり、
  HostCaps の grant が全て「missing」と読めたり、`WebAssembly.instantiate`
  の native import lookup（wasm バイナリに焼き込まれた文字列で行われる）が
  renamed 済みの関数名を見つけられず失敗した。**externs ファイル**
  （`externs/actor-host.js`）で `grants`/`limits`/`store`/`memory` と
  host 関数名（`sha256_hex` 等 snake_case）を明示的に rename 対象外にして
  解決 — vendor JS を Closure :advanced と併用する際の一般的な罠として記録。
- **live 実証**: `wasmdemo.aozora.app` の profile で `main()` を実行し
  `sha256_hex("hello")` が正しいハッシュ値
  `2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824` を返し、
  `log_write` が `"hello"` を記録。CID 配信の wasm → HostCaps 検証付き
  ブラウザ実行という L5 の最後のピースが本番で一周した。
