# run 0191 — kotobase-stab tick 2026-09-15 (JST)

## 計測 (全生計測、捏造なし)
- `nbb kotobase_lead_loop.cljk funnel-pulse` → EXIT 0
  (`SOUL.md` の `.cljs` は ENOENT、正しくは `.cljk` — run 0184/0189/0190 と同様)
  `SCANNED 1 / VISITORS 0 / SIGNUPS 0 / CHECKOUTS 0 /
   DELTA {:visitors 0, :signups 0, :checkouts 0} / UNCHANGED true /
   EXTERNAL-FUNNEL-CHANGE false / SCORE unchanged / RESULT unchanged`
- `curl -sL https://kotobase.net/api/funnel` → 200 JSON verbatim 要点:
  visitors 0 / signups 0 / signup_completed 0 / registrations 0 /
  by-source 全空 / seeded false /
  persistence.kind = "isolate-memory" (durable false — counter は isolate
  再生で reset、lifetime totals ではない旨明記) / openai_ads HOLD。
  ※ apex 301 経由で最終 URL は https://kotoba.cloud/api/funnel。
  本 tick 中の新規 visitor は 0 (not-measured 以上は言えない)。

## 安定化チェック (HTTP headers verbatim)
- `GET /` (no follow) → **301**, `location: https://kotoba.cloud/docs/graph/`
  (run 0190 と同値 — 変化なし)。follow → 200 (~110KB, kotoba.cloud docs)。
- `GET /signup` (no follow) → **301** → `https://kotoba.cloud/signup`;
  follow → **404** ("Not found — Kotoba Cloud" page 実測)。
  **signup funnel 入口は引き続き実質 dead (run 0190 から未修復)**。
- `GET /ipld/v1` → **302** → `https://ipfs.kotobase.net/ipfs/v1` → follow 400
  (= route 存在の正常値。run 0190 と同じ転送経路)。
- `GET /ipld/` → 302 → ipfs.kotobase.net (bare 404 期待値は redirect に覆われ
  本 tick も未直測)。
- 原因推定 1 行 (UNCONFIRMED): apex → kotoba.cloud への redirect 移行が
  run 0190 のまま続き、/signup の転送先がまだ kotoba.cloud 側に存在しない。

## SCORE → SELECT (WIP=1)
- SCORECARD は 2026-08-14 観測分のまま (それより新しい scorecard は無い)。
- Selected action (1 件): **run 0190 と同一 — 「/signup → kotoba.cloud/signup →
  404 の funnel 入口断線」を owner への最優先報告に据える (2 tick 連続)**。
  根拠の数字: signups 0 + delta 0 (入口が 404 の間、signup 導線は物理的に閉じたまま)、
  checkouts 0、verified external revenue 0。
  58/100 の signup-activation draft (0045) は入口復旧まで無意味。
- Bounded experiment: 外向け送信はしない (SOUL.md 原則)。本 run file と
  報告文で止まる。redirect rule 変更 / deploy はしない (原因特定前の本番操作は
  危険、run 0189/0190 と同じ判断)。

## Decision → HOLD + ESCALATE (report, 2 tick 連続)
- spend 無し / self-purchase 無し / outbound send 無し / deploy 無し。
- canvas-ledger.edn は触っていない (single-writer rule)。

## Next verification
1) **Owner: apex → kotoba.cloud redirect の意図確認 + kotoba.cloud/signup 404 の
   埋め戻し** (2 tick 連続の最優先項目のまま)。確認経路: Cloudflare
   Redirect Rules / `wrangler deployments list` (repo 外設定のため
   repo からは検証不能)。
2) 入口復旧後: / 200 (自ドメイン), /signup 200, /ipld/v1 400 の再実測。
3) counter reset 後の funnel が非ゼロに戻るか (visitors/signups の tick delta)。
4) 繰り越し: checkout 1 件の検証 / counsel written reply / draft 0045 go-no-go。
5) 環境注意: 本 tick も foreground terminal が stdout を返さない異常
   (0187 以降継続)。全出力を file redirect で取得して回避。原因 UNCONFIRMED。

## exaggeration-guard
- verified external revenue == 0。counter は isolate-memory で lifetime ではない。
- redirect 変化は実測 (headers verbatim) だが意図/原因は UNCONFIRMED。
- signups 0 は「新規 0」であって「過去実績が消えた」という意味ではない。
