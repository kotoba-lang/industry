# run 0194 — kotobase-stab tick 2026-09-15 (5 tick 連続 /signup 404)

## 計測 (生計測、捏造なし)
- `nbb kotobase_lead_loop.cljk funnel-pulse`: **未実行** — cron プロンプトの拡張子
  `.cljs` のファイルは実在しない (ENOENT 実測、run 0193 で確認済みの既知不整合)。
  正本は `kotobase_lead_loop.cljk`。本 tick は cron 指定文字列のままの実行で ENOENT
  を確認しただけで pulse 経由の delta は not-measured。
- `curl https://kotobase.net/api/funnel` (301 follow → 200):
  visitors 0 / signups 0 / signup_completed 0 / registrations 0 / by-source 全空 /
  seeded false / openai_ads HOLD / persistence.kind "isolate-memory" (durable false,
  lifetime totals ではない — API 応答本文 verbatim)。

## 安定化チェック (本 tick 生値)
- `GET /` → **301** (Cloudflare edge) → follow → **200**。
- `GET /signup` → **301** → follow → **404**。**5 tick 連続で funnel 入口 dead**。
- `GET /ipld/v1` → **302** → follow → **400** = route 存在の正常値。
- `GET /ipld/` (bare) → **302** → follow → **400** (404 期待に対し 400。
  bare route が 2026-09-03 実測の 404 から変化している可能性 — UNCONFIRMED、
  redirect 先の 400 が edge 由来か origin 由来かは本 tick 未分解)。
- 原因推定 1 行 (UNCONFIRMED): apex → kotoba.cloud への edge redirect rule が
  継続中で、転送先に /signup が無いため funnel 入口が 404 のまま。

## SCORE → SELECT (WIP=1)
- SCORECARD.md は 2026-08-14 観測分のまま (より新しい score 無し)。
- Selected action (1 件): run 0190–0193 と同一 — **「/signup → 404 の funnel
  入口断線」の owner 報告継続 (5 tick 連続)**。root cause 未解決のため交代しない。
  根拠の数字: signups 0 / signup_completed 0 / verified external revenue 0。
  入口が 404 の間、signup 導線は物理的に閉じており draft 0045 (58/100) は無意味。
- Bounded experiment: 外向け送信なし (SOUL.md 原則)。本 run file と報告文で止まる。
  redirect rule 変更 / deploy はしない (edge 設定は repo 外、原因特定前の本番操作回避)。

## Decision → HOLD + ESCALATE (5 tick 連続)
- spend 0 / self-purchase 0 / outbound send 0 / deploy 0。
- canvas-ledger.edn は触っていない (single-writer rule)。

## Next verification
1) **Owner: apex → kotoba.cloud redirect の意図確認 + /signup の埋め戻し**
   (最優先、5 tick 連続)。Cloudflare Redirect Rules / `wrangler deployments list`。
2) `/ipld/` bare が 404 でなく 400 を返すようになった点の分解 (302 先の origin 値)。
3) cron プロンプトのスクリプト名修正: `kotobase_lead_loop.cljs` → `.cljk`
   (本 tick も ENOENT。これで pulse 経由の delta が毎 tick not-measured になっている)。
4) 入口復旧後の再実測: / 200 (自ドメイン), /signup 200, /ipld/v1 400。
5) 環境注意: foreground terminal が stdout を返さない異常は本 tick も継続
   (run 0187 以降)。全出力を file redirect で取得して回避。原因 UNCONFIRMED。

## exaggeration-guard
- verified external revenue == 0。counter は isolate-memory で lifetime ではない。
- /signup 404 は 5 tick 連続の生実測。redirect の意図/原因は UNCONFIRMED。
- funnel-pulse delta は本 tick not-measured (スクリプト名不整合による ENOENT)。
