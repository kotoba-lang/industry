# run 0196 — kotobase-stab tick (stasis / kotobase.net 全 path→graph root 丸めを実測 / checkouts delta 0)

**Status:** completed
**Started:** 2026-09-18 (JST)
**Owner:** agent
**Mode:** cash-first

## 計測 (生値、捏造なし)

- `nbb kotobase_lead_loop.cljk funnel-pulse` → **EXIT=0**:
  `SCANNED 1 / VISITORS 13324 / SIGNUPS 42 / CHECKOUTS 1 / DELTA {:visitors 3, :signups 0, :checkouts 0} / UNCHANGED false / EXTERNAL-FUNNEL-CHANGE false / SCORE unchanged`
  - 注意: script delta visitors +3 vs `/api/funnel` 実測差 13,324−13,318=**+6**。script 側 delta の分母基準は未確認。捏造しないため両方の値を残す。
- `curl https://kotobase.net/api/funnel` → **200**:
  visitors 13,324 (organic 11,727 / freebuff 23 / openai-ads 4) / signups 42 (organic 32 / ads 2 / freebuff 1 / other 4) / **checkouts 1 (organic, 未検証のまま)** / registrations 4 (other)。
  micro: docs_view 4 / github_click 2 / cli_copy 2。
  x402: challenges 46 / submissions 4 / **settlements 0** / settlement-rate 0 / rejections 4 (classified 1 / **unexplained 3**) / attempt-rate 0.087 / unmetered-twin-ratio **25.30** (run 0195: 25.24) / unpriced-plane-reads 4,518 / unmetered-twin-reads 1,164。

## 安定化チェック (本 tick 生値)

- `GET /` → 301 → `https://graph.kotoba.cloud/` → **200** (0.044s 初段)
- `GET /signup` → 301 → `https://graph.kotoba.cloud/` (path 切捨て) → **200**
  - **graph.kotoba.cloud/signup 自体は 404 を実測** (run 0190–0194 の 404 事象と整合)。signup 専 route は今日も存在しない。
- `GET /ipld/v1` → 302 → `https://ipfs.kotobase.net/ipfs/v1` → **400** ✅ (route 存在の正常値、SOUL.md 基準と一致)
- `GET /ipld/` (bare) → 302 → `https://ipfs.kotobase.net/ipfs/` → **400** (404 基準と異なるが bare route の挙動変化は run 0195 から既に 302 化、route 存在は確認)

## 本 tick の新規測定 (bounded experiment)

**graph.kotoba.cloud / kotobase.net の surface 消失を実測:**

| URL | 結果 |
|---|---|
| `graph.kotoba.cloud/` | 200 |
| `graph.kotoba.cloud/register` | **404** |
| `graph.kotoba.cloud/pricing` | **404** |
| `graph.kotoba.cloud/legal/terms/` | **404** |
| `kotobase.net/pricing` | 301 → graph root (final 200, path 切捨て) |
| `kotobase.net/legal/terms/` | 301 → graph root (final 200, path 切捨て) |

→ SCORECARD 記載の live surface (`/pricing`, `/legal/terms/`, `/legal/privacy/`, `/legal/dpa`) は
**現在どこからも到達不能**。301 が全 path を root に丸めているため、
URL を保持した redirect ですらない。signup 入口は「root 200 に見えるが
実質 dead」。原因推定 1 行: apex に全域 301 を張る redirect rule が入った
(或いは origin が graph.kotoba.cloud 単独 root に差し替えられた) が、
意図か事故かは owner 確認事項 (UNCONFIRMED)。

## SCORE → SELECT (WIP=1)

- Selected action (1 件): **signup/pricing/legal surface の全域 root 丸めを
  owner への draft 報告として記録** (本 file + cron 報告)。
  根拠の数字: signups 42→42 (delta 0)、入口 `/signup` 実質 dead 7 tick 目
  (0190–0194 は 404、0195–本 tick は root 丸め)、`/register` `/pricing`
  `/legal/terms/` 全 404 実測。acquisition bottleneck に対する入口修復は
  owner 権限のため agent 側実行手段なし → draft 止まり。
- 次点: checkouts 1 の external 検証 (run 0195 から引継ぎ、delta 0 で変化なし)。
- Bounded experiment: 上表 6 URL の route 存在確認 (実施済み)。
- 外向け送信 0 / デプロイ 0 / spend 0。

## Decision → HOLD + ESCALATE

- canvas-ledger.edn 未触 (single-writer rule)。
- score-raised? false (外部検証なし、conversion 実績変化なし)。

## Next verification

1) kotobase.net の全域 root 301 が意図的 migration か事故か (owner 確認)。
   `/signup` `/pricing` `/legal/*` の path 保持 redirect 復旧が最优先。
2) checkouts 1 が external 実決済か (検証できるまで売上 0)。
3) cron プロンプトの `.cljs` → 正本 `.cljk` 不整合、本 tick も再発 (ENOENT を本 tick 冒頭で実測、`.cljk` に差し替えて成功)。
4) x402 unexplained rejections 3 (変化なし)。

## exaggeration-guard

- verified external revenue == 0。checkouts 1 は未検証。
- terminal stdout 欠落障害は本 tick も継続 (全コマンドを file redirect で回避、原因 UNCONFIRMED)。
