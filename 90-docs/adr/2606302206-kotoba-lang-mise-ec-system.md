---
id: adr-2606302206-kotoba-lang-mise-ec-system
title: "ADR-2606302206: kotoba-lang/mise を共通 EC system として設計し、ai-gftd-fashion (fashion.gftd.ai) で公開する"
status: proposed
doc_type: adr
topic: kotoba-lang-ec-system
authoritative: true
last_verified: 2026-06-30
authoritative_for:
  - kotoba-lang 配下フロントエント系 repo が共有する EC system の repo 名と境界
  - catalog + pricing + cart + inventory + checkout + order + re-frame events + pure-hiccup EC components の層構成
  - 注入 port（IPaymentPort / IOrderStore / IDiscount / ITax / IShipping）と mock adapter の v1 範囲
  - dual-render 契約（SSR shitsuke.hiccup ‖ reagent cljs）と portable re-frame 7 関数 subset
related:
  - 90-docs/adr/2606301900-kotoba-lang-shitsuke-design-system.md
  - orgs/kotoba-lang/mise
  - orgs/gftdcojp/ai-gftd-fashion
  - orgs/kotoba-lang/shitsuke
  - orgs/kotoba-lang/slides
  - https://www.fashionsnap.com/article/2018-04-20/gifted-hatra/
supersedes: []
superseded_by: []
---

# ADR-2606302206: kotoba-lang/mise を共通 EC system として設計し、ai-gftd-fashion (fashion.gftd.ai) で公開する

**Status**: proposed — landed (2026-06-30), mise/ai-gftd-fashion tests + builds green / manifest 登録済み
**Date**: 2026-06-30
**Deciders**: Jun Kawasaki

## Context / 背景

`fashion.gftd.ai` にファッションブランド **gftd.** の EC サイトを立てる。シード記事は
fashionsnap 2018-04-20「感覚過敏の人に向けたファッションブランド『ギフテッド』始動、第1弾は
ハトラとコラボ」#パーカ（Image by: gftd.）。ブランド主体は gftd.、初コラブ HATRA×パーカを
シード商品とする。

gftdcojp/kotoba-lang 配下に既存の EC/cart/checkout/order/inventory ライブラリは無く（調査済）、
EC system は net-new。直前の `shitsuke`（design system）+ `slides`（dual-render re-frame 移行）
のパターンを踏襲し、EC system を共通ライブラリ `mise`（店）として kotoba-lang に置き、最初の
利用者 `ai-gftd-fashion`（gftdcojp, fashion.gftd.ai）がそれを消費する。

gftdcojp サイトは Cloudflare Workers（`wrangler.jsonc`, `*.gftd.ai`, D1/B2）＋ shadow-cljs
（`:worker :esm` + `:app :browser` reagent/re-frame）が支配的（`club-shinshi` shinshi-cljc appview
が手本）。本 v1 は **repo+manifest+ADR+scaffold+build green** まで（live deploy の DNS/D1/Worker
プロビジョニングはオーナー follow-up）。決済は **pluggable IPaymentPort + mock adapter**
（Stripe adapter は stub、実クレカ無し）。

## Decision / 決定

`mise`（kotoba-lang, portable `.cljc`, runtime dep は shitsuke のみ）と `ai-gftd-fashion`
（gftdcojp, CF Worker + browser SPA）の 2 repo を新規起こす。

### mise repo layout

```
orgs/kotoba-lang/mise/
  deps.edn ; :deps {shitsuke}, :test/:local/:cljs/:pages aliases
  src/mise/
    catalog.cljc    ; Product/SKU/Catalog model (EDN) + query fns
    pricing.cljc    ; Price math + totals; IDiscount/ITax/IShipping ports
    cart.cljc       ; Cart line items + invariants (pure)
    inventory.cljc  ; Stock levels + reserve/restock (pure)
    checkout.cljc   ; checkout state machine + IPaymentPort (mock adapter)
    order.cljc      ; Order record + status statechart + IOrderStore (mock)
    events.cljc     ; re-frame events/subs (portable 7-fn subset via shitsuke)
    views.cljc      ; 純 hiccup EC components on shitsuke.components
    ssr.clj         ; SSR parity via shitsuke.hiccup/->html
```

### ai-gftd-fashion repo layout

```
orgs/gftdcojp/ai-gftd-fashion/
  deps.edn ; mise + shitsuke + reagent/re-frame/shadow-cljs (alias)
  shadow-cljs.edn ; :worker {:target :esm} + :app {:target :browser}
  wrangler.jsonc ; fashion.gftd.ai custom domain (scaffold; provisioning = owner)
  src/gftd/fashion/{seed,worker,events,views,app,ssr}.cljc/cljs
```

### 契約（authoritative）

1. **dual-render**: 同じ `.cljc` 純 hiccup view を SSR（`shitsuke.hiccup/->html`）と reagent
   （cljs）の両方へ（shitsuke/slides と同契約）。
2. **portable re-frame subset**: `mise.events` は `shitsuke.re-frame.core` に 7 関数のみで登録。
   effect/cofx/interceptor/chaining 使わず。
3. **注入 port**: 決済 `IPaymentPort`（authorize/capture）、永続化 `IOrderStore`、割引 `IDiscount`、
   税 `ITax`、配送 `IShipping`。v1 は mock adapter のみ（Stripe/D1 adapter は follow-up）。
4. **純粋 state**: cart/checkout/order の状態遷移はすべて純関数（host app-db が所有）。
5. **shitsuke 再利用**: views は `shitsuke.components` + `shitsuke.style/class-name`（`mise__*`）
   の上に積む。`data-act` は名前空間付き act を保持（`shitsuke` act->str で `:cart/add` →
   `"cart/add"`、衝突回避）。
6. **seed**: `gftd.fashion.seed/gftd-catalog` = gftd. ブランドの GIFTED × HATRA パーカ（3 サイズ）
   + sibling tee。記事 URL を docstring/README で参照。
7. **worker**: `GET /api/catalog`（seed fixture, EDN on wire）, `POST /api/orders`（mock IOrderStore）,
   `GET /` shell。TS shell 無し（CLJS handler = `export default { fetch }`）。

### 最初の利用者

`ai-gftd-fashion`（gftdcojp, fashion.gftd.ai）。shadow-cljs `:worker :esm` + `:app :browser`
で storefront → cart → checkout → confirmation SPA を構築。deploy は `wrangler.jsonc` scaffold
＋ README 手順（実プロビジョニングは owner）。

## Consequences

- **正向**: EC の純粋データモデル + re-frame subset + 純 hiccup component が kotoba-lang で共有化。
  新規 EC サイト（gftd. 以外のブランド）は mise + shitsuke を require するだけで立ち上がる。
  決済/永続化は port 差替えで本番化可能。
- **負向**: v1 は mock adapter のみ（Stripe/D1 未統合）。live deploy は owner follow-up。
- **移行**: ai-gftd-fashion の実装は本 PR で同時登録。shitsuke pin は act->str 強化（`310f8fc`）
  に前進。mise/ai-gftd-fashion pin は各 repo main HEAD。

## Alternatives Considered

- **既存 EC SaaS / Stripe Checkout 直叩き**: 却下。gftd. ブランドの cljc/kotoba 体制（ポータブル・
  データ主権）に合わない。mise は port で Stripe を後付け可能。
- **サイト repo 内に EC を直書き**: 却下。複数ブランドで共有できず、shitsuke の design-system 共有
  と対にならない。kotoba-lang は関心事単位 repo 慣例。
- **real re-frame のみ（mini runtime 無し）**: 却下。JVM SSR / babashka / WASM で動かない。
  shitsuke の compat-namespace パターン（wasm-ui 由来）を採用。

## References

- `90-docs/adr/2606301900-kotoba-lang-shitsuke-design-system.md`（design system 前提）
- `orgs/kotoba-lang/mise/docs/adr/0001-mise-ec-system.md`（per-repo 設計 SSoT）
- `orgs/kotoba-lang/mise/docs/design.md`（層ごとの API）
- `orgs/kotoba-lang/slides/src/slides/web/events.cljc`（re-frame portable subset 実績）
- `orgs/gftdcojp/club-shinshi`（shinshi-cljc appview dual-build 手本）

Co-Authored-By: Claude Opus 4.8 (1M context)
