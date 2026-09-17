# run 2026-09-17 — kotobase-stab tick (signup 404 連続、/ipld/ 400 へ変化、.cljs ENOENT 再確認)

## 計測 (生値、捏造なし)

- `nbb 90-docs/business/revenue-agent-loop/kotobase_lead_loop.cljs funnel-pulse`
  → **ENOENT** (実測、cron プロンプトの拡張子不整合)。正本は `kotobase_lead_loop.cljk`。
- `nbb kotobase_lead_loop.cljk funnel-pulse` → **成功** (EXIT=0):
  `SCANNED 1 / VISITORS 0 / SIGNUPS 0 / CHECKOUTS 0 / DELTA {:visitors 0,:signups 0,:checkouts 0} / UNCHANGED true / RESULT unchanged`
- `curl https://kotobase.net/api/funnel` (301 follow → 200 kotoba.cloud):
  visitors 0 / signups 0 / signup_completed 0 / registrations 0 / by-source 全空 /
  **persistence.kind "isolate-memory", durable false** — API 応答本文 verbatim で
  "Counters live in isolate memory and reset when the isolate recycles. Do not
  treat these as lifetime totals." / seeded false / openai_ads HOLD。
  直近の lifetime 系履歴 (metrics/net-kotobase-funnel-pulses.kotoba): 2026-09-05 時点
  visitors 6,524 / signups 31 / checkouts 0 (delta 累積、生実測)。

## 安定化チェック (本 tick 生値、301/302 は follow 済み)

- `GET /` → 301 → `https://kotoba.cloud/docs/graph/` → **200** ✅
- `GET /signup` → 301 → `https://kotoba.cloud/signup` → **404** ❌
  (**6 tick 連続で funnel 入口 dead**。404 ページは "Not found — Kotoba Cloud" 正常レンダ)
- `GET /ipld/v1` → 302 → `https://ipfs.kotobase.net/ipfs/v1` → **400**
  ("ipfs origin id must be a CIDv1 base32 DNS label") = route 存在の正常値 ✅
- `GET /ipld/` (bare) → 302 → `https://ipfs.kotobase.net/ipfs/` → **400**
  ("missing target")。SOUL.md の 2026-09-03 実測 (404 正常) から **400 に変化**。
  旧 apex route が ipfs.kotobase.net へ移行した結果と推定 (UNCONFIRMED)。
- 原因推定 1 行 (UNCONFIRMED): Cloudflare edge が apex 全経路を kotoba.cloud /
  ipfs.kotobase.net へ恒久 redirect しており、転送先に /signup が存在しない。
- 追加実測: kotoba.cloud 上で /signup 404 / /register 404 / /pricing 404 /
  /login 302 / / 200。

## SCORE → SELECT (WIP=1)

- SCORECARD.md は 2026-08-14 観測分のまま (それより新しい score 無し)。
- Selected action (1 件、run 0190–0194 から継続): **「/signup → 404 の funnel 入口
  断線」の owner 報告継続 (6 tick 連続)**。root cause 未解決のため交代しない。
  根拠の数字: signups 0 / signup_completed 0 / checkouts 0 / verified external
  revenue 0。入口が 404 の間、signup 導線は物理的に閉じている。
- Bounded experiment (外向け送信なし): draft 報告書 = 本 run file + cron 報告。
  宛先: owner (junkawasaki)。内容: (1) Cloudflare Redirect Rules で /signup →
  kotoba.cloud/signup を指す転送先を実在 route に直すか、転送先側に /signup を
  生やす。(2) funnel カウンタが isolate-memory のまま — durable store (KV/D1)
  を付けるまで lifetime 数値は消失する。(3) cron プロンプトの `kotobase_lead_loop.cljs`
  → `.cljk` 修正。deploy は実行しない。

## Decision → HOLD + ESCALATE

- spend 0 / outbound send 0 / deploy 0 / canvas-ledger.edn 未触 (single-writer rule)。

## Next verification

1) Owner による redirect 修正確認後: /signup 200 を再実測。
2) `kotobase.cloud` 転送先に signup equivalent (/login 302 先?) の有無を分解。
3) terminal stdout 異常: 本 tick も stdout 0 バイトで file redirect で回避
   (run 0187 以降継続、原因 UNCONFIRMED)。

## exaggeration-guard

- verified external revenue == 0。0/0/0 は isolate-memory reset 後の値で
  lifetime ではない (両方を併記)。
- /signup 404 は 6 tick 連続の生実測。redirect の意図は UNCONFIRMED。
