---
id: adr-2606282000-cloud-workers-kotobase-external-storage
title: "ADR-2606282000: OSS アプリ(murakumo/manimani)を standalone のまま、cloud 接続時は kotobase.net(kotoba PDS)を外部ストレージにする IStore ポート(kotobase-clj)+ cljs Cloudflare Worker API(cloud-murakumo/cloud-manimani)。kagi/murakumo/manimani を OSS 公開"
status: proposed
doc_type: adr
topic: actor-design
authoritative: true
last_verified: 2026-06-28
authoritative_for:
  - アプリの永続化を IStore ポートで抽象化し、OSS standalone(:local)↔ cloud(:kotobase) を無改造で切替える方針
  - kotobase.net(kotoba PDS)を外部ストレージ backend とし、kotoba 由来 IPNS graph を外部 object storage(B2/S3)に背負わせる設計
  - cloud-murakumo / cloud-manimani を cljs Cloudflare Worker の外部公開 API とする決定
  - 再利用 clj ライブラリ + kagi-clj/murakumo/manimani を public 公開する判断
related:
  - orgs/com-junkawasaki/kotobase-clj      # 新設: IStore ポート(:local 参照実装 + :kotobase XRPC 注入)
  - orgs/com-junkawasaki/cloud-murakumo    # 新設: cljs CF Worker — fleet API
  - orgs/com-junkawasaki/cloud-manimani    # 新設: cljs CF Worker — triage Decision Ledger API
  - orgs/com-junkawasaki/murakumo          # OSS: kotoba WASM lattice 操作 CLI(standalone)
  - orgs/com-junkawasaki/manimani          # OSS: triage デスクトップ/CLI(standalone)
  - orgs/com-junkawasaki/kagi-clj          # OSS 公開(vault コードは公開可・鍵は gitignore)
  - orgs/com-junkawasaki/langchain-clj     # 既存: langchain.kotoba-db(kotoba XRPC db-api)
---

# ADR-2606282000: kotobase 外部ストレージ + cljs Cloudflare Worker API

- Status: proposed (2026-06-28)
- 文脈: murakumo(kotoba WASM lattice 操作 CLI)/ manimani(triage アプリ)を **OSS と
  して公開**しつつ、**cloud に繋ぐと kotobase.net をストレージに**して多デバイス・耐久化
  したい。外部公開の **API は Worker(cljs)** で。

## 課題

OSS アプリは standalone(ローカルストレージ)で動く必要がある一方、cloud 接続時は
kotobase.net(kotoba PDS)へ永続化したい。アプリコードを二重化せず、**ストレージだけ差し
替え**たい。さらに kotoba 自身が外部 object storage を背負える設計にしたい。

## 決定

### 1. IStore ポート(kotobase-clj)— 永続化の単一 seam

zero-dep 全 .cljc の `kotobase.store/IStore`(`put/get/list` の doc 空間 + `append/read
(since)` の単調 :seq ストリーム)。

- **:local**(`kotobase.local/LocalStore`)— atom の純粋実装。OSS standalone の backend
  かつ契約 oracle。JVM/cljs 同一。
- **:kotobase**(`kotobase.kotobase/KotobaseStore`)— 全 op を **host 注入の `xrpc`
  fn** へ転送 → kotobase.net XRPC → kotoba PDS。HTTP client を持たない(Worker は
  `fetch` を注入)。`MemStore ‖ DatomicStore` / num-clj `IBackend` と同型の注入。
- 契約テストで **`KotobaseStore ≡ LocalStore`**(忠実 transport 上で一致)を保証。

### 2. cloud-murakumo / cloud-manimani — cljs Cloudflare Worker の外部 API

- portable な `routes/handle :: (store, request) → response`(.cljc)を **JVM で
  LocalStore 相手にテスト**(緑)。Worker(`worker.cljs`)はその **async 翻訳**
  (CF runtime と kotobase fetch が async ゆえ各 op は Promise)。
- storage は注入:`xrpc.cljs` が kotobase.net への `fetch`(CACAO は Worker secret)を
  作り、`:kotobase` store でそのまま API を提供。OSS-local↔cloud でアプリロジック不変。
- shadow-cljs(`:esm`)→ ESM、`wrangler deploy`。

### 3. kotoba 外部ストレージ

kotobase.net は actor の鍵由来 IPNS graph(depth-1 self-mint、token 不要)を **外部
object storage(git-annex/B2、S3)に背負わせる**。cloud-* Worker はその前段の read/write
API。`langchain.kotoba-db` の db-api(`{:q :transact! :pull …}`)が XRPC 実体。

### 4. OSS 公開

再利用 clj ライブラリ群(format/protocol/CAE/共有)+ **kagi-clj**(PQC vault — コードは
Kerckhoffs 原則で公開可、鍵は `*identity.edn` gitignore)+ **murakumo** + **manimani** を
public。secret scan 済み(commit 済み鍵・creds 無し)。actor 群(kekkai/vehicle-design/
kenchi)・infra(残置)は private 継続。

## 帰結

- 3 リポジトリ新設(kotobase-clj / cloud-murakumo / cloud-manimani)。portable core は
  全テスト緑(kotobase-clj 4/20、各 Worker の routes 1/8・1/6)。Worker の cljs build/
  deploy(shadow-cljs + wrangler)は配線済み・要 CF アカウント。
- アプリは無改造で OSS-standalone(:local)↔ cloud(:kotobase)を切替。
- sync IStore vs async Worker の境界を明記(num-clj WgslBackend と同様):routes は sync
  参照、Worker は async 翻訳。
- west 登録・GitHub remote 作成・public 公開は CLAUDE.md 方針通り(API single-entry pin、
  pin==HEAD 検証)。

## 却下案

- **アプリに kotobase を直書き**: standalone を壊し二重化。IStore 注入で一本化。
- **Worker に独自ストレージ(CF KV/D1)**: kotoba PDS の主権/IPNS graph と二重管理。
  kotobase.net を単一の真実源にする。
- **sync な cljs WgslBackend 風 Worker**: WebGPU 同様 readback が async。Worker は
  async route で素直に翻訳する。
