# run 0195 — kotobase-stab tick (checkouts 0→1 / /signup redirect 先が 200 に変化 / .cljs ENOENT 再確認)

**Status:** completed
**Started:** 2026-09-17
**Owner:** agent
**Mode:** cash-first

## 計測 (生値、捏造なし)

- `nbb kotobase_lead_loop.cljk funnel-pulse` (正本 .cljk) → **成功 EXIT=0**:
  `SCANNED 1 / VISITORS 13318 / SIGNUPS 42 / CHECKOUTS 1 / DELTA {:visitors 13318,:signups 42,:checkouts 1} / UNCHANGED false / EXTERNAL-FUNNEL-CHANGE true / SCORE unchanged / RESULT recorded`
  - DELTA=全量一致 → 前 pulse 側がゼロ (isolate-memory reset 後) と整合。delta 自体は script 記録通り。
- `curl https://kotobase.net/api/funnel` → **200, 0.656s**。
  visitors 13,318 (organic 11,721 / freebuff 23 / openai-ads 4) / signups 42 (organic 32 / ads 2 / freebuff 1 / other 4) / **checkouts 1 (organic)** / registrations 4 (other)。
  micro: docs_view 4 / github_click 2 / cli_copy 2。
  x402: challenges 46 / submissions 4 / **settlements 0** / settlement-rate 0 / rejections 4 (classified 1, **unexplained 3**) / attempt-rate 0.087 / unmetered-twin-ratio 25.24。

## 安定化チェック (本 tick 生値、-L follow 済み)

- `GET /` → 301 → `https://graph.kotoba.cloud/` → **200** ✅ (0.474s)
- `GET /signup` → 301 → `https://graph.kotoba.cloud/` → **200** ✅ (0.320s)
  ※ 09-17 前 tick (run 0194) では `kotoba.cloud/signup` → **404** だった。redirect 先が
  graph サブドメイン root に変わり結果 200。**ただし /signup 専 route が復活した証拠ではなく、
  root へ丸めているだけ** (意図は UNCONFIRMED)。入口としては「到達可能」に復活したことが重要。
- `GET /ipld/v1` → 302 → `https://ipfs.kotobase.net/ipfs/v1` → **400** ✅ (route 存在の正常値、SOUL.md 基準と一致、0.270s)

## SCORE → SELECT (WIP=1)

- Selected action (1 件): **checkouts 0→1 の変動検出 — run 0185 以来の未検証 checkout を
  「external verified payment 候補」として owner 確認キューに載せる**。
  根拠の数字: checkouts delta +1 (organic)。script 判定 EXTERNAL-FUNNEL-CHANGE true だが
  規約上「record only — self-purchase / internal / test でないことの検証なし」なので
  **verified revenue は依然 0**。
- 次点: /signup 入口の redirect 先 200 化 (上記)。入口 dead 6 tick (0190–0194) から状態変化。
  両方 owner 確認事項で、agent 側の実行手段なし → draft 止まり。
- Bounded experiment: draft 報告 (本 file + cron 報告)。宛先: owner (junkawasaki)。
  送信・デプロイ・実決済勧誘なし。

## Decision → HOLD + ESCALATE

- spend 0 / outbound send 0 / deploy 0 / canvas-ledger.edn 未触 (single-writer rule)。
- score-raised? false (checkout 変動は記録のみ、score 更新は external 検証後)。

## Next verification

1) checkouts=1 が external 実決済か (Stripe live / test、owner 自己購入でないこと)。
   検証できるまで売上 0 のまま。
2) /signup → graph.kotoba.cloud root 丸めが意図的か。専 route 復活 or 正しい signup
   ページへの redirect か owner 確認。
3) cron プロンプトの `kotobase_lead_loop.cljs` → `.cljk` 不整合は本 tick も再発 (ENOENT)。
   プロンプト側修正は owner 権限。
4) x402 unexplained rejections 3 (変化なし)。

## exaggeration-guard

- verified external revenue == 0。checkouts 1 は未検証。
- funnel 数値は isolate-memory 起因で lifetime とは限らない (script 本体の note 通り)。
- stdout バイト欠落障害は本 tick も継続 (file redirect で回避、原因 UNCONFIRMED)。
