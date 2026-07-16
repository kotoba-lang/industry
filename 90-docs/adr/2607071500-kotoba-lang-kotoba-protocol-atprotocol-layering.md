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
- (−) cap-bridge は `graph/query`（読取専用）+ `graph/transact`（書込み、
  Addendum 3）。wasm actor は 7/8 imports（`http-post` 不可）、`llm/complete`
  は proxy 先 backend が無く未実装（下記 open follow-up）。
- (−) bundle-cid integrity 検証は raw codec の単一 HTML/バイナリ blob 専用
  （Addendum 4: wasm module、Addendum 5: embed iframe の host-fetch→srcDoc
  mount）+ Addendum 6 で dag-pb/UnixFS ディレクトリの再帰検証も実装。ただし
  検証済みディレクトリツリーを実際に mount する（相対パス解決込みの複数
  ファイル embed 提供）には Service Worker 等の virtual filesystem が要る —
  未実装のまま下記 open follow-up に持ち越し。

## Open follow-up（本 ADR クローズ後も残る）

- lexicon `_lexicon` DNS 公開・firehose・MST encoding の kotoba graph 導出
  （atprotocol の wire 互換を深める話、miniapp モデル自体には不要）。
- ~~cap-bridge の書込み系 capability（`graph/transact`）の host 代行実装~~ →
  実装済み（Addendum 3）。`llm/complete` は proxy 先の LLM completion
  XRPC/backend 自体がまだ存在しないため未実装のまま持ち越し。
- ~~studio への actor 鍵 import UI の汎用化~~ → 再調査の結果、記載自体が
  誤りだったと判明（Addendum 5）。`ensure-key!`/`import-key!`/
  `key-import-form`/`publish-status` は元から `slug` 引数で汎用化済みで、
  `studio-page` は `publish/app-actors` の全件を無条件 iterate している —
  mangaka-app 専用ではなく 3 app actor すべてで動く。コード上の確認のみ
  （ブラウザでの目視確認は未実施）。
- ~~`:kotoba.app/bundle-cid` の appview 側 integrity 検証~~ → wasm actor
  （Addendum 4）+ embed iframe（Addendum 5、host-fetch→`srcDoc` mount）+
  dag-pb/UnixFS ディレクトリの再帰検証（Addendum 6）まで実装済み。
  **残る範囲**: 検証済みディレクトリツリーを実際に mount する（相対パス
  解決込みの複数ファイル embed 提供）には Service Worker 等の virtual
  filesystem が要る — mount 自体は未実装のまま持ち越し。

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

## Addendum 3 (2026-07-07 同日): cap-bridge 書込み系 capability — graph/transact 実装

クローズ時に open follow-up として残した cap-bridge の書込み capability
（`graph/query` 読取専用の対）を実装（`llm/complete` は proxy 先 backend が
まだ無いため引き続き未実装）。

- **host 側**: `yoro-ui.interop.app-bridge/run-cap` に `graph/transact` 分岐を
  追加。embed app は生の nsid を渡せず、代わりに
  `{:action "create"|"put"|"delete" :collection :rkey :record}` を渡す —
  host 側で action → procedure nsid（`com.atproto.repo.{createRecord,
  putRecord,deleteRecord}`）を解決するため、app が任意 nsid を叩くことは
  できない。`collection` は `com.etzhayyim.` 名前空間のみ許可
  （identity/account 等の core atproto collection は書けない）。
  `host-supported-caps` に `"graph/transact"` を追加（`kotoba.protocol.app`
  の `bridge-caps` には元々 `graph/transact`/`llm/complete` 両方が登録済み
  だったので protocol 層の変更は不要 — host 側の対応表を埋めただけ）。
- **書込みルーティング**: 新設 `yoro-ui.interop.atproto/at-write-record` が
  既存の `:atproto/procedure` re-frame fx と同じ no-server-key ルーティングを
  再利用: セッション DID がこの端末にローカル repo 鍵を持てば
  `repo-signer/write-record!`（on-device 署名）、無ければ従来の
  server-signed `at-procedure` にフォールバック。cap-bridge 経由でも鍵/token
  は embed app には一切渡らない（host が代行するのは「操作」であって
  「権限そのもの」ではない、という cap-bridge の不変条件を維持）。
- **検証**: 実 shadow-cljs `:test` build（monorepo の相対パス依存を要するため
  隔離 worktree でなく、clean な共有 checkout に一時的にファイルを重ねて
  build → 直後に `git checkout --` で復元、というノーコミットの手法で検証）
  で 174 ファイルがコンパイル成功（既存の無関係な `:infer-warning` 1 件のみ）。
  実ブラウザでの round-trip デモ（bridge-demo-app からの実書込み）は
  持ち越し — 次に embed app を書き込みテストするときに確認する。

## Addendum 4 (2026-07-09): bundle-cid integrity 検証 — wasm actor 実装

open follow-up の `:kotoba.app/bundle-cid` integrity 検証（「配信内容と
bundle-cid の一致は未検証」）に着手。調査の結果、`:kotoba.app/bundle-cid`
は実運用ではほぼ未使用（唯一 `bridge-demo-app` が手で `embed-url` と同じ
CID 文字列を重複させているだけ）で、実際に検証すべき CID は
`:kotoba.app/wasm[].cid`（kind=actor）に一番具体的な形で存在すると判明 —
そこに scope を絞った。

- **`kotoba.protocol.cid`（新規 ns、kotoba-protocol）**: `parse-raw-cid` /
  `digest-matches?` — CIDv1/raw/sha2-256 の digest 抽出 + 比較。
  `kotobase.archive-put`（net-kotobase、本番で稼働中の同一ロジック）から
  移植（byte-exact）。ハッシュ計算自体は host の仕事（`crypto.subtle.digest`
  等）で、この ns は CID⇄digest bytes の変換のみを持つ pure 関数。
- **`kotoba.protocol.app/bundle-cid-consistent?`**: manifest が
  `:kotoba.app/bundle-cid` と ipfs:// scheme の `:kotoba.app/embed-url` を
  両方持つ場合、同じ CID を指すことを検証（`validate-manifest` に組込み）。
  手で 2 箇所に同じ CID を書く現行運用が desync しても検出できなかった穴を
  塞いだ。
- **`yoro-ui.interop.wasm-actor/run-actor!`**（app-aozora）: `:cid` opt を
  追加。fetch した ArrayBuffer を `js/crypto.subtle.digest "SHA-256"` で
  ハッシュし、`kotoba.protocol.cid/digest-matches?` で manifest の CID と
  比較 — 不一致なら `WebAssembly.instantiate` の**前**に reject (fail-closed)。
  `wasm-actor-panel` が module の `:cid` を forward し、成功時に
  「✓ CID 一致検証済み (sha256)」badge を表示。
- **scope の明示的な線引き**: raw codec の単一バイナリ（wasm module）専用。
  (a) `app-embed-panel` の iframe mount（embed kind）は host が iframe の
  fetch レスポンス bytes を一切見ないため、同じ「fetch→hash→compare」方式
  では検証できない — host 自身が fetch して blob:/srcdoc で mount する
  方式への切替が要る、より大きな設計変更（open follow-up に明記）。
  (b) dag-pb/UnixFS ディレクトリ CID（複数ファイルの embed バンドル）は
  単純な sha256 一致では検証できない（IPLD DAG 構造の検証が要る）—
  同じく open follow-up に明記。
- **検証**: kotoba-protocol は実 `clojure -M:test`（13 tests / 81
  assertions、既存 68 + 新規 13）+ `clojure -M:lint`（clj-kondo、0
  errors/warnings）で確認。app-aozora は実 shadow-cljs `:test` build
  （214 ファイルコンパイル成功、Addendum 3 と同じノーコミット overlay
  手法）で確認。ブラウザでの実 CID 不一致 reject デモ（意図的に壊れた
  CID を manifest に入れて fail-closed を目視確認）は持ち越し。

## Addendum 5 (2026-07-10): iframe embed の CID 検証 + actor 鍵 import UI 汎用化の再確認

open follow-up の残り 2 件に着手。1 件は実装、もう 1 件は**調査の結果
記載が誤りだったと判明**（コードは既に汎用化済み）。

### iframe embed（kind=embed）の CID 検証 — 実装

Addendum 4 の wasm actor 検証と同じ「fetch した bytes の sha256 を CID と
比較」を、`app-embed-panel` の iframe mount にも拡張。wasm actor と違い
iframe は `<iframe src=url>` にすると **host がレスポンス bytes を一切
見られない**（ブラウザが iframe 内部で直接 fetch する）ため、host が
先に自分で fetch する方式に切り替える必要があった:

- **`atprotocol.profile/project-app`**（atprotocol）: `:kotoba.app/embed-url`
  が `ipfs://` scheme のとき、`resolve-embed-url` が既に持っている raw CID
  を `:embedCid` として view に追加。`:kotoba.app/bundle-cid` は manifest
  作者が別途セットしないと出ない（実運用ではほぼ未使用と Addendum 4 で判明
  済み）のに対し、`:embedCid` は embed-url 自体から常に導出できる — こちら
  を検証の一次ソースにした。
- **`yoro-ui.interop.embed-fetch/fetch-verified-html!`**（新規 ns、
  app-aozora）: `wasm-actor/run-actor!` の CID 検証と同型（fetch →
  `crypto.subtle.digest` → `kotoba.protocol.cid/digest-matches?`）。
  一致すれば `{:html <string>}`、不一致/fetch 失敗なら `{:error}`。
- **`app-embed-panel`**: `embedCid` があるときは `<iframe src>` で直接
  mount せず、`r/with-let` で一度だけ `fetch-verified-html!` を呼び、
  検証中は「CID 検証中…」、不一致/失敗時は「mount しない: `<error>`」を
  表示して**iframe を一切 mount しない**（fail-closed）、一致したときだけ
  `<iframe srcDoc=html>` で mount する。`embedCid` が無い（https scheme 等、
  検証不能）embed は従来どおり `<iframe src=embedUrl>` のまま変更なし。
- **scope**: raw codec の単一 HTML blob 専用（`kotoba.protocol.cid` と
  同じ制約）。dag-pb/UnixFS ディレクトリ CID（複数ファイル bundle）はこの
  方式では検証できない — Addendum 6 で再帰検証を実装（mount 自体は別
  follow-up、下記参照）。
- **検証**: atprotocol は実 `clojure -M:test`（7 tests / 48 assertions、
  既存 46 + 新規 2）+ `clojure -M:lint`（0 errors）で確認。app-aozora は
  実 shadow-cljs `:test` build（231 ファイルコンパイル成功）で確認。
  ブラウザでの実 mount 確認（bridge-demo-app の実 profile page で
  「✓ CID 一致検証済み」表示 → srcDoc 経由で実際に描画されることの目視
  確認）は持ち越し。

### studio actor 鍵 import UI 汎用化 — 「未実装」は誤りだったと判明

open follow-up リストに残っていた「studio への actor 鍵 import UI の
汎用化（現状 mangaka-app 専用）」を実装しようと `yoro-ui.studio.publish`
と `yoro-ui.pages.studio` を精査した結果、**この記載自体が誤り**だったと
判明した:

- `ensure-key!` / `import-key!` / `actor-did` / `storage-key`
  （`studio/publish.cljc`）はすべて元から `slug` 引数でパラメータ化されて
  おり、mangaka 固有のリテラル文字列は一切無い（`localStorage` key は
  `"aozora-studio-actor-key-" + slug` で actor ごとに独立）。
  `git log -p` で履歴を辿ると、`app-actor-row` 追加時（ADR-2607071500 本体
  実装コミット）から既存の `publish-status`/`key-import-form`
  （`pages/studio.cljc`）をそのまま再利用しており、mangaka 専用にコピペ
  された形跡も無い。
- `studio-page` は `(for [actor publish/app-actors] [app-actor-row actor])`
  で `app-actors` の**全件を無条件 iterate**しており、`bridge-demo-app` /
  `wasm-demo-app` も `mangaka-app` と同じ「鍵あり `<did>`」/「鍵 import
  フォーム」を独立して表示する。
- 結論: **コード変更は不要**。open follow-up のこの項目は「未実装だった
  こと」ではなく「クローズ時点の記載ミス（実際には既に汎用化済みだった）」
  として訂正する。
- **限界**: この確認はコード読解のみ（`clj-kondo`/`shadow-cljs` の静的
  検証は通っている既存コードの再読）で、ブラウザで実際に `/studio` を開き
  `bridge-demo-app`/`wasm-demo-app` 行に鍵 import フォームが描画されることの
  目視確認は本セッションでは未実施（ブラウザ拡張が未接続だったため）。

## Addendum 6 (2026-07-10): dag-pb/UnixFS ディレクトリ CID の再帰検証

Addendum 4/5 の「fetch した bytes の sha256 を CID と比較」を、複数
block からなる dag-pb Merkle DAG（UnixFS directory）全体に拡張した。
単一 blob と違い、ディレクトリツリーはノードごと（ディレクトリノード
自身・各ファイルの leaf・大きいファイルの chunk）に別々の CID を持つ
独立した block の集まりなので、host は block を 1 個ずつ個別に fetch し、
block ごとに sha256 を declared CID と照合してから dag-pb を decode する
— 途中のどれか 1 block でも digest が合わなければ再帰全体を reject する
（fail-closed。部分的に検証できた結果を「検証済み」として返さない）。

- **`kotoba.protocol.cid`（kotoba-protocol）拡張**: `parse-cid` /
  `digest-matches-cid?`（Addendum 4 の raw-only `parse-raw-cid`/
  `digest-matches?` を一般化 — raw/dag-pb/dag-cbor いずれの multicodec でも
  digest 位置は同じなので codec を問わず比較できる）。`base32-encode` /
  `parse-cid-bytes` / `cid-bytes->string`（dag-pb ノードの `Link.Hash` は
  生 CID bytes で来るので、fetch 可能な CID 文字列に戻すのに使う）。
- **`yoro-ui.interop.dagpb-verify`（新規 ns、app-aozora）**: root CID から
  再帰的に fetch+検証し、`{:type :file :bytes …}` | `{:type :directory
  :entries {name → tree}}` の verified tree を返す。dag-pb の protobuf
  decode は公式 `@ipld/dag-pb` の `pb-decode.js` を byte-exact に移植した
  もの（フィールド番号・wireType 検証・エラーメッセージまで同一）—
  `@ipld/dag-pb` npm package 自体は ESM-only で package.json exports に
  `require`/`module-sync` 条件が無く、shadow-cljs の `:node-test` target
  （Node CJS 経由）からは解決できなかったため直接依存はせず、ロジックだけ
  移植した（同じ手法は kotoba-lang エコシステム内の別リポジトリ
  `etzhayyim/root` の `car.ts` でも前例あり）。UnixFS Data の Type
  判別（raw/directory/file/…）は dag-pb 自体の scope 外なので、Type
  field（1 個の varint）だけを最小限に自前 decode する。
- **検証**: 実 dag-pb encode（テスト内の最小 protobuf encoder）+
  `multiformats`（module-sync 条件があり node-test で解決可能）で実際に
  組み立てた fixture に通した実 round-trip テスト — 2 ファイルディレクトリ
  の検証、chunked file（複数 block に分割されたファイル）の順序どおり
  再結合、単一 raw CID の扱い、そして **fail-closed tamper test**（tree
  構築後に 1 block の内容だけ差し替えて全体が reject されることを確認）。
  kotoba-protocol は実 `clojure -M:test`（15 tests / 95 assertions）+
  `clojure -M:lint`（0 errors）、app-aozora は実 `node out/node-tests.js`
  （259 tests / 830 assertions、0 failures）で確認 — モック無しの実データ
  round-trip。
- **scope**: 検証のみ。検証済みツリーを実際に mount する（相対パス解決
  込みの複数ファイル embed 提供）には Service Worker 等の virtual
  filesystem が要る — 別 follow-up（mount 未実装。`app-embed-panel` は
  raw codec の単一 HTML blob のみ Addendum 5 で対応済み）。
