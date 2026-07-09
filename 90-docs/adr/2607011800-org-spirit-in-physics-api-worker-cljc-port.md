# ADR-2607011800: org-spirit-in-physics `apps/api-worker` を CLJC (`:esm` fetch worker) へ移植

**Status**: proposed
**Date**: 2026-07-01
**Scope**: `orgs/com-junkawasaki/org-spirit-in-physics/apps/api-worker`

## Context

`org-spirit-in-physics`（west 管理下の別リポジトリ、pnpm workspace）は TypeScript/SvelteKit
ベースの "Spirit-in-Physics Visualizer"。全体（`apps/api-worker` + `apps/web` +
`apps/researcher` + `pkg/*`、合計 ~15,000 行）を `.cljc` へ移行したいというオーナー要望を受け、
まず最小・自己完結な `apps/api-worker`（Hono BFF, ~2,300行, D1+R2のみ・DO/KV無し）を先行移植する。
`apps/web`/`apps/researcher` の Svelte UI（~13,000行）移植は別フェーズとする。

`90-docs/adr/2606290000-all-workers-cljc-only-policy.md`（gftdcojp org スコープ）が
shadow-cljs `:esm` fetch worker（Pattern B, 参照実装: app-aozora）を既に定義しており、
本 ADR はこのパターンを `com-junkawasaki` へ初適用するもの。同ポリシーの移行ガードレール
自体が「各 worker は個別 ADR + parity diff + cutover + rollback、big-bang 禁止」と明記して
おり、既存 ADR の scope 欄を書き換えるのではなく本 ADR を新規に起票する。

事前調査で判明した重要事実:
- api-worker の LangGraph (`@langchain/langgraph` StateGraph) 利用は実質「線形チェーン +
  手書き D1 監査ログ」のみ。LLM 呼び出し無し、LangGraph 自身の checkpointer も未使用、
  分岐/interrupt も無い。
- 組織内に D1 interop・Kysely 相当のクエリビルダ・WebAuthn サーバ側検証の CLJC/CLJS 前例は
  一切無い（Pattern B 参照実装の app-aozora にも無い）。ゼロから設計する。
- app-aozora（Pattern B 参照実装）は `deps.edn` を持たず npm+shadow-cljs のみ。JVM テスト層は無い。
- 既存 TS worker には自動テストが一切無く、移植先の parity 基準にできる既存テストが無い。

## Decision

### D1 アクセス

生 SQL 文字列 + `^js` interop（`.prepare/.bind/.all/.first/.run`）を手書きする。汎用クエリ
ビルダは作らない。Kysely 相当の前例が組織に無く、クエリ数は約15〜20個で汎用DSLは過剰設計。

### WebAuthn

`@simplewebauthn/server` を shadow-cljs npm interop でラップする。COSE/CBOR/attestation
検証を CLJS で再実装しない。ADR-2606290000 の exemption 条項（TS-only SDK に cljs 等価が
無い場合は interop wrap がデフォルト）に合致し、認証のセキュリティクリティカルな検証ロジックを
手で再実装するリスクを避ける。

### セッション Cookie (HMAC)

`js/crypto.subtle` interop で直接 `.cljc` に移植する。Web Crypto は Workers ランタイムで
標準提供され、ライブラリ不要でロジックも単純。

### LangGraph (`StateGraph`)

廃止し、プレーンな `.cljc` 関数合成（`->` パイプライン）に置き換える。`graph_runs`/
`graph_checkpoints`/`graph_node_events` への書き込みは既存と同じ形で維持する（TS 側も元々
LangGraph の checkpointer は使わず手動で書いていたため、DB 上の挙動は不変）。現状のグラフは
分岐もLLM呼び出しも無い線形チェーンで、グラフエンジンを持ち込む理由が無い。langgraph-clj が
shadow-cljs `:esm` ターゲットで動く保証も無く、リスクを増やすだけ。

### `deps.edn` / JVM テスト層

作らない。app-aozora の Pattern B 前例（npm + shadow-cljs のみ）に揃える。この worker は
langgraph-clj StateGraph actor ではなくステートレス edge worker であり、CLAUDE.md の
Actors 規約はここには非該当。

### D1 migrations

既存 SQL をそのまま流用し無変更とする。schema 自体は言語非依存。既存 `database_id` を
再利用しゼロデータ移行というガードレール（ADR-2606290000）にも合致する。

### デプロイ名/ルート

新 worker は別名・別ルートで parity 検証し、本番切替（cutover）は別途確認の上で実施する。
ADR-2606290000 のガードレール「cutover は保守窓+parity+即rollback」に従う。

## Consequences

- api-worker の言語substrateが CLJC に統一され、レビュー観点が `:language ".cljc"` に揃う。
- D1 interop・WebAuthn interop wrap・LangGraph代替パイプラインという3つの新規パターンが
  組織に前例として残り、以後の同種移植（`app-aozora` 系以外の worker）で再利用できる。
- 既存 TS worker（`apps/api-worker`）は cutover + soak 期間経過まで並存する。
- `apps/web`/`apps/researcher` の Svelte UI 移植は別 ADR で扱う（本 ADR はスコープ外）。

## Related

- `90-docs/adr/2606290000-all-workers-cljc-only-policy.md`（Pattern B の定義元。本ADRは
  その `com-junkawasaki` 初適用）

## Out-of-scope

- `apps/web` / `apps/researcher`（~13,000行の Svelte UI）の移植
- 本番デプロイ・DNS ルート切替（`spirit-in-physics.com/api/*` 等）
- `manifest/west.yml` / `repos.edn` への新規登録や pin 変更
- 既存 `apps/api-worker`（TS版）の削除

## Verification Notes

- Phase 0: スキャフォールド（`shadow-cljs.edn`/`package.json`/`wrangler.jsonc`/migrations流用）
- Phase 1: データ層（`schema.cljc`/`db.cljc`/`stimulus_words.cljc`）+ 認証層（session/webauthn）
- Phase 2: ルート移植（health/participants/sessions/storage → timeline/assessments）+
  `router.cljc`/`worker.cljc` 結線
- Phase 3: テスト（`cljs.test` via `:node-test`）+ ビルド + `smoke.mjs`
- Phase 4: parity 検証（`wrangler dev` 新旧並走での応答比較 + WebAuthn 手動確認）
- Phase 5: push + PR（merge・本番 deploy・DNS切替・west登録は別途確認）
