# ADR-2607022400: gftd cljs Worker/Pages repo の build 再現性を必須にする

**Status**: accepted
**Date**: 2026-07-02
**Deciders**: Jun Kawasaki

## Context

BMC gate の emitter を各 product repo に deploy しようとした際
（ADR-2607022200 の follow-up）、**gftd の ClojureScript Cloudflare Worker/Pages
repo が clean checkout から再現可能に build できない**ことが横断的に判明した。
これが「私（や CI）が deploy を回せない」根本原因で、deploy のたびに開発者の
ローカル環境に暗黙依存していた。

実測した欠落パターン（3 repo で同型）:

| repo | 症状 | 修正 |
|---|---|---|
| **net-kotobase** | `clj-edge/package.json`/lockfile が git に一度も無い（shadow-cljs + `@ipld/dag-cbor` 等の JS 依存が開発者マシンのみ）。wrangler `main` の entry `edge-app.cljc` と DO `durable-objects.cljc` が未コミット。生成 `worker/js/*.js` も未コミット | #141（package.json+lockfile 復元、実 build 検証）/ #142（entry+DO を git 履歴から復元、`.cljc`→`.ts`、bundle commit、dry-run 検証） |
| **cloud-manimani** | `shadow-cljs.edn :deps {:aliases []}` が `thheller/shadow-cljs` を宣言せず classpath 解決失敗。`deps.edn` が `../kotobase-clj` local-root（monorepo sibling 前提） | #2（`:cljs` alias に shadow-cljs 追加、custom domain route、実 build+deploy 検証） |
| **network-isekai** | `public/js` が gitignore（shadow-cljs build 出力）。clean checkout の `public/` は build 済み JS を欠き、そのまま Pages deploy すると **live サイトを破壊**。`/feed/*.edn` が `_redirects` の SPA fallback に飲まれる | 未修正（本 ADR で follow-up 化。live サイトゆえ慎重に） |

## Decision

gftd の全 cljs Worker/Pages repo に対し、**clean checkout から `wrangler deploy`
（または Pages deploy）が再現可能である**ことを必須要件とする。各 repo は次を満たす:

1. **shadow-cljs 依存の明示宣言** — `deps.edn` の build alias（例 `:cljs`）に
   `thheller/shadow-cljs` を pin。`shadow-cljs.edn :deps {:aliases [:cljs]}` で参照。
   npm 依存は `package.json` + lockfile を **git に commit**（開発者マシン依存を排除）。
2. **sibling local-root の解消** — `{:local/root "../foo"}` は CI/clean で解決不能。
   git dep（`{:git/url … :git/sha …}` or `io.github.…` deps）へ移すか、build を
   monorepo コンテキストで回す CI を明示。
3. **wrangler entry の commit** — `main` が指すファイル（TS shim / esm 出力）を
   git に置く。生成 bundle を commit する方針なら `.gitignore` と実態を一致させる
   （net-kotobase は `worker/js/.gitignore` が「committed」と書くのに未コミットだった）。
4. **build 出力方針の統一** — 生成物を (a) commit する（deploy-time build 不要）か
   (b) CI で build するかを repo ごとに宣言。gitignore と CI/RUNBOOK を一致させる。
5. **再現性ゲート** — CI（`worker.yml` 等）が **clean checkout から build を実行**し、
   syntax-check だけでなく bundle 生成まで検証する（net-kotobase の CI は syntax-check
   のみで build しないため欠落が検出されなかった）。

## Consequences

- (+) deploy が開発者マシン非依存になり、CI / agent / 別担当者が `wrangler deploy`
  一発で回せる。毎朝の routine で emitter deploy まで自動化する道が開く。
- (+) 実証済み: 本方針で net-kotobase・cloud-manimani を実際に本番 deploy できた
  （それぞれ Version 94ca7947 / 2fad35f1）。
- (−) 各 repo に build fix PR が要る。exemplar は net-kotobase #141/#142・
  cloud-manimani #2。残 audit（下記）を follow-up PR で片付ける。
- (−) network-isekai は live Pages サイト（12k req/7d）で、build 修正 + `_redirects`
  の `/feed/*.edn` 除外 + full cljs+wasm build を伴うため、開発者 build 環境での
  慎重な deploy を推奨（blind redeploy はサイト破壊リスク）。

## Audit（build 再現性の要修正 repo）

| repo | 種別 | 状態 |
|---|---|---|
| net-kotobase | cljs Worker | ✅ 修正済（#141/#142）・deploy 済 |
| cloud-manimani | cljs Worker | ✅ 修正済（#2）・deploy 済（custom domain provisioning 中） |
| network-isekai | cljs+wasm Pages | ⚠️ 未修正（public/js gitignore・_redirects・live サイト） |
| app-aozora (40-engine appview) | cljs Worker | 要 audit（shadow build + sibling 依存の可能性、PDS write も要る） |
| ai-gftd-apex | cljs browser app | build 経路要 audit（deploy は `gftd deploy` 系の可能性） |
| club-shinshi | TS/SvelteKit | pnpm build、別系統（要 audit だが cljs 問題は該当せず） |

## References

- ADR-2607022200（per-product gate instruments、本 ADR の発端）
- net-kotobase #141 / #142、cloud-manimani #2（exemplar 修正）
- 各 repo の shadow-cljs.edn / deps.edn / wrangler.{toml,jsonc}
