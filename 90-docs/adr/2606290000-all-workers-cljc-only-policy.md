# ADR-2606290000: worker は全て CLJC のみに — club-shinshi をリファレンス実装として残 worker を段階移行

**Status**: accepted
**Date**: 2026-06-29
**Scope**: `orgs/gftdcojp/club-shinshi`（リファレンス実装）+ gftdcojp 配下全 Cloudflare Worker

## Context

gftdcojp 配下の Cloudflare Worker は SvelteKit / Vite-TS / plain-TS / cljs-wasm が混在し、
同じ業務モデルが複数言語で重複する。`.cljc` 正本なら JVM(test/CLI) と browser/worker で
同一ロジックを走らせられ、UI は EDN IR に落とせば renderer(DOM/WebGL/WASM)を差し替え
できる。cloud-murakumo（assets-only SPA, cljc-ui-IR + cljs-dom-adapter）と app-aozora
appview（shadow-cljs :esm fetch worker, `#js {:fetch}`）に既に prod 実績がある。

オーナー指示（2026-06-29）: (1) shinshi.club（現 SvelteKit SPA-on-Workers, 44k req/7d）
を `magatama` 命名ではなく **club-shinshi** として CLJC で全面再構築（frontend 含む。
SvelteKit 廃止）。(2) **worker は全て CLJC のみに**（前進方針 + 段階移行）。

## Decision

1. **前進方針**: gftdcojp 配下の Worker は新規・修正ともに **CLJC のみ**。
   - `.cljc` 正本 + cljs-dom-adapter（UI）+ shadow-cljs `:esm`（fetch handler）。
   - SvelteKit / Vite-TS / plain-TS worker を新規に作らない。
2. **パターンカタログ**（使い分け）:
   - **A. assets-only SPA**（cloud-murakumo）— worker script 無し、`public/` を SPA 配信。UI 正本=cljc-ui-IR。
   - **B. :esm fetch worker**（app-aozora appview）— `out/worker.js`, `#js {:fetch}`。D1/R2/env は `^js` interop。
   - **A∪B. Worker + Assets**（club-shinshi）— `main` + `assets`、`not_found_handling:"none"` で worker が SPA fallback + SSR OG を握る。
3. **リファレンス実装**: `club-shinshi`（`shinshi.club`）。全面 CLJC 再構築を Phase 0→3 で進める。
4. **段階移行**: 残 36 worker は traffic+churn 順に移行。各々 ADR + parity diff + cutover + rollback。big-bang なし。
   - exemption: TS-only 第三 party SDK で cljs 等価が無いものは case-by-case ADR。default は interop で wrap し TS を primary に残さない。

## club-shinshi 構成

| ファイル | 役割 |
|---|---|
| `shinshi-cljc/deps.edn` | `.cljc` 正本を JVM/browser/worker で共用 |
| `shinshi-cljc/shadow-cljs.edn` | `:worker`(:esm) + `:app`(:browser) + `:worker-test`(:node-test) |
| `shinshi-cljc/wrangler.jsonc` | Worker+Assets, D1×2（既存 id 再利用）, vars, `workers_dev:true`（Phase 3 まで `shinshi.club` route 無効） |
| `src/club_shinshi/{worker,router,http,d1,telemetry,exoclick,mcp,hotel,...}.cljc` | BFF |
| `src/club_shinshi/ui/{core.cljs,dom.cljs,views.cljc,...}` | frontend cljc-ui-IR |

## Guardrails

- **honest-NULL telemetry**: 指標は基礎データ無ければ NULL（LLM 捏造禁止）。
- **secrets**: `wrangler secret` / Keychain SSoT。EDN には非機密参照先のみ。コミット禁止。
- **D1 継続性**: 既存 `database_id` を再利用（`d1 create` 禁止）。data migration ゼロ。
- **git**: shallow 既定・FF-only main・force-push 禁止・オーナー WIP 破棄禁止。
- **cutover**: live worker の切り替えは保守窓 + parity + 即 rollback（旧 worker は git 温存）。
- **Worker+Assets**: `not_found_handling:"none"` 必須（`single-page-application` だと SSR OG が worker に届かない）。
- **nodejs_compat**: `ui.*` を `:worker` build に混入させない（hls.js 等は browser-only）。

## Consequences

- 新規 Worker の substrate 選択が不要（CLJC 一本）。レビュー観点が `:language ".cljc"` に統一される。
- SvelteKit 資産は段階廃止。既存 TS worker は移行 ADR を要するまで並存（方針違反だが即刻削除しない）。
- club-shinshi が Worker+Assets の規範＝以後の新規 worker はこれを踏襯。

## Verification Notes

- Phase 0: scaffold + ADR + build/test 基盤（`npm install` → `clojure -M:test` → `release:worker` → `smoke` → `wrangler versions upload` preview）。
- Phase 1: BFF（8 endpoint + D1 ops + telemetry + ExoClick + MCP + hotel + SSR OG）を preview で parity 検証。
- Phase 2: frontend cljc-ui-IR（~28 route + components）。
- Phase 3: cutover（保守窓・別途確認 gate）→ ≥48h soak → 旧 `magatama-sh1n5h1x` decommission。