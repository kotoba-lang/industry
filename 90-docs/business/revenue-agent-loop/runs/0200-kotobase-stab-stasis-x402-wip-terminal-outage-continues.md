# run 0200 — kotobase-stab: stasis 継続 (visitors +2)、x402 unexplained 3 件 WIP 継続

## 計測 (生値、捏造なし)

- cron プロンプトの `kotobase_lead_loop.cljs` → **ENOENT** (5 tick 連続、同一不整合)。
  正本は `kotobase_lead_loop.cljk`。
  `nbb 90-docs/business/revenue-agent-loop/kotobase_lead_loop.cljk funnel-pulse` → EXIT=0:
  `VISITORS 13333 / SIGNUPS 42 / CHECKOUTS 1 / DELTA {:visitors 2, :signups 0,
  :checkouts 0} / UNCHANGED false / EXTERNAL-FUNNEL-CHANGE false / RESULT recorded`。
  (注意: 本 tick も host terminal が stdout を返さない不調が継続 → /tmp への
  リダイレクト + read_file の代替手順で完遂。2026-09-16 記録の outage と同型。)
- `GET https://kotobase.net/api/funnel` → 200:
  - funnel: visitors 13333 (organic 11736 / freebuff 23 / openai-ads 4)、
    signups 42 (organic 32 / other 4 / openai-ads 2 / freebuff 1)、
    checkouts 1 (organic)。
  - registrations 4 (すべて source "other"、変化なし)。
  - micro: docs_view 4 / github_click 2 / cli_copy 2 (不変)。
  - x402: submissions 4 / rejections 4 / classified 1 (malformed-header 1) /
    **rejections-unexplained 3 (変化なし)** / settlement-rate 0 / settlements 0 /
    attempt-rate 0.0870 / unmetered-twin-reads 1175 (前 1174, +1) /
    unpriced-plane-reads 4518 (不変) / challenges 46 /
    unmetered-twin-ratio 25.54 (前 25.52)。
- 前 run (0199) からの変化: visitors +2 (organic +2)、twin-reads +1 のみ。
  signups / checkouts / registrations / 未分類 rejection 3 件 / settlements 0 は不変。
  owner 側の draft 処理状況は not measured。

## 安定化チェック (follow 済み)

- `GET /` → 301 → `graph.kotoba.cloud` → **200** ✅ (0.027s / -L 後 0.48s)
- `GET /signup` → 301 → `graph.kotoba.cloud` → **200** ✅
- `GET /ipld/v1?x=1` → 302 (cloudflare) → `https://ipfs.kotobase.net/ipfs/v1` →
  **400** = route 存在の正常値 ✅
- `GET /ipld/` (bare) → 302 → `https://ipfs.kotobase.net/ipfs/` → **400**。
  SOUL.md 期待 (404 正常) と 6 tick 連続不一致 → baseline 更新判断は owner 側に残置のまま。
- 原因推定 1 行 (UNCONFIRMED): 経路・転送先・着地コードは前 tick から不変で異常なし。

## SCORE → SELECT (WIP=1)

- SCORECARD.md は変更なし。全候補行 blocked (counsel gate / no contact path /
  red gate)。
- **Selected action は前 run から交代せず WIP 継続**:
  「x402 rejections の分類漏れ解消 — owner へ draft 報告」。
  根拠の数字: submissions 4 / rejections 4 / classified 1 / unexplained 3 /
  settlements 0 / settlement-rate 0 / unpriced-plane-reads 4518 /
  twin-reads 1175。
  4.5k read 流量に対し課金試行 4 件が全敗かつ 3/4 原因不明 — ボトルネックは
  流量でなく rejection 観測性。learn 5 / conf 5 を満たす。
- Bounded experiment (送信なし・deploy なし): 本 run file を draft とし、
  宛先 = owner への報告を cron 出力で提示して止まる。

## Owner への draft 報告 (送信はしない)

宛先: junkawasaki (owner)。
(1) x402: rejection 4 件のうち 3 件が `rejections-unexplained` のまま
  5 tick 不変。reason 分類ログ出力の修正を推奨 (settlements 0 継続中)。
(2) cron プロンプトの `kotobase_lead_loop.cljs` → `.cljk` 修正 (5 tick ENOENT 継続)。
(3) SOUL.md の bare `/ipld/` 期待値 404 → 400 更新の判断 (6 tick 不一致)。
(4) host terminal の stdout 空出力不調が本 tick も継続 (2026-09-16 記録と同型)。
  代替手順 (/tmp リダイレクト + read_file) で処理完遂、kotobase.net 側に影響なし。
