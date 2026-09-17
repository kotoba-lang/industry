# run 0193 — kotobase-stab tick 2026-09-15 (4 tick 連続 /signup 404)

## 計測 (全生計測、捏造なし)
- `nbb kotobase_lead_loop.cljk funnel-pulse` → EXIT 0
  `SCANNED 1 / VISITORS 0 / SIGNUPS 0 / CHECKOUTS 0 /
   DELTA {:visitors -12859, :signups -42, :checkouts -1} / UNCHANGED false /
   EXTERNAL-FUNNEL-CHANGE false / SCORE unchanged / RESULT recorded`
  - 負 delta は isolate-memory counter が isolate 再生で reset したことによる
    (persistence.kind "isolate-memory", durable false — lifetime totals ではない)。
    外向きの funnel 変化はない (EXTERNAL-FUNNEL-CHANGE false)。
- `curl https://kotobase.net/api/funnel` (301 follow → 200):
  visitors 0 / signups 0 / signup_completed 0 / registrations 0 / by-source 全空 /
  seeded false / openai_ads HOLD / persistence.kind "isolate-memory"。

## 安定化チェック (本 tick 生値, headers verbatim 取得)
- `GET /` → **301** → `location: https://kotoba.cloud/docs/graph/`
  (Cloudflare edge 発, cf-ray NRT)。follow → 200。
- `GET /signup` → **301** → `https://kotoba.cloud/signup` → **404**
  (cf-cache-status HIT, content-length 87961)。**4 tick 連続で funnel 入口 dead**。
  CSP に `freebuff.com` が出現 (転送先 Kotoba Cloud Pages 側の属性)。
- `GET /ipld/v1` → **302** → `https://ipfs.kotobase.net/ipfs/v1` → **400**
  = route 存在の正常値。
- `GET /ipld/` (bare) → 本 tick 未再測 (run 0192 の 302→400 実測を引き継ぎ)。
- 原因推定 1 行 (UNCONFIRMED): apex → kotoba.cloud への edge redirect rule が
  継続中で、転送先に /signup が無いため funnel 入口が 404 のまま。

## SCORE → SELECT (WIP=1)
- SCORECARD は 2026-08-14 観測分のまま (より新しい scorecard 無し)。
- Selected action (1 件): **run 0190–0192 と同一 — 「/signup → 404 の funnel
  入口断線」の owner 報告継続 (4 tick 連続)**。root cause 未解決のため交代しない。
  根拠の数字: signups 0 / checkouts 0 / verified external revenue 0。
  入口が 404 の間、signup 導線は物理的に閉じており draft 0045 (58/100) は無意味。
- Bounded experiment: 外向け送信なし (SOUL.md 原則)。本 run file と報告文で止まる。
  redirect rule 変更 / deploy はしない (edge 設定は repo 外、原因特定前の本番操作は
  run 0189–0192 と同じく回避)。

## Decision → HOLD + ESCALATE (4 tick 連続)
- spend 0 / self-purchase 0 / outbound send 0 / deploy 0。
- canvas-ledger.edn は触っていない (single-writer rule)。

## Next verification
1) **Owner: apex → kotoba.cloud redirect の意図確認 + /signup の埋め戻し**
   (最優先、4 tick 連続)。確認経路: Cloudflare Redirect Rules /
   `wrangler deployments list` (repo 外設定のため repo からは検証不能)。
2) 入口復旧後の再実測: / 200 (自ドメイン), /signup 200, /ipld/v1 400。
3) funnel counter が非ゼロに戻るか (isolate reset 後の tick delta — 本 tick の
   負 delta は reset 副作用であり減少実績ではない)。
4) 繰り越し: checkout 1 件の検証 / counsel written reply / draft 0045 go-no-go。
5) 環境注意: foreground terminal が stdout を返さない異常は本 tick も継続
   (run 0187 以降)。全出力を file redirect で取得して回避。原因 UNCONFIRMED。
   また本 tick で `kotobase_lead_loop.cljs` が実在しないことを確認 — 正本は
   `kotobase_lead_loop.cljk` (.cljk)。cron 側プロンプトの拡張子が古い。

## exaggeration-guard
- verified external revenue == 0。counter は isolate-memory で lifetime ではない。
- /signup 404 は 4 tick 連続の生実測。redirect の意図/原因は UNCONFIRMED。
- 負 delta は counter reset の副作用であって利用者消失の測定値ではない。
