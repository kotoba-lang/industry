# run 0198 — kotobase-stab: stasis 継続、/signup 200 (3 tick 目)、x402 unexplained 3 件 WIP 変化なし

## 計測 (生値、捏造なし)

- cron プロンプトの `kotobase_lead_loop.cljs` → **ENOENT** (3 tick 連続、同一不整合)。
  正本は `kotobase_lead_loop.cljk`。`nbb ... .cljk funnel-pulse` → EXIT=0:
  `VISITORS 13324 / SIGNUPS 42 / CHECKOUTS 1 / DELTA {:visitors 0, :signups 0,
  :checkouts 0} / UNCHANGED true / RESULT unchanged`。
  (注意: 本 tick の terminal は一部コマンド出力が空で返る不調があったため、
  生出力は /tmp/pulse2.out から読み取り確認済み。)
- `GET https://kotobase.net/api/funnel` → 200 (0.63s):
  - funnel: visitors 13324 (organic 11727 / freebuff 23 / openai-ads 4)、
    signups 42 (organic 32 / other 4 / openai-ads 2 / freebuff 1)、
    checkouts 1 (organic)。
  - registrations 4 (すべて source "other")。
  - micro: docs_view 4 / github_click 2 / cli_copy 2 (前 tick 同値、増えず)。
  - x402: submissions 4 / rejections 4 / classified 1 (malformed-header 1) /
    **rejections-unexplained 3 (変化なし)** / settlement-rate 0 / settlements 0 /
    attempt-rate 0.0870 / unmetered-twin-reads 1165 (前 1163, +2) /
    unpriced-plane-reads 4518 (変化なし) / challenges 46 /
    unmetered-twin-ratio 25.33 (前 25.28)。
- 前 run からの変化: visitors +3、twin-reads +2 のみ。未分類 rejection 3 件、
  settlements 0 は不変。owner 側の draft 処理状況は not measured。

## 安定化チェック (follow 済み)

- `GET /` → 301 → `graph.kotoba.cloud` → **200** (0.17s) ✅
- `GET /signup` → 301 → **200** (0.38s) ✅ (解消後 3 tick 連続 200)
- `GET /ipld/v1` → 302 → `ipfs.kotobase.net/ipfs/v1` → **400** = route 存在の
  正常値 ✅
- `GET /ipld/` (bare) → 302 → **400**。SOUL.md の期待値 (404 正常) と 4 tick
  連続で不一致 → baseline 更新判断は owner 側に残置。
- 原因推定 1 行 (UNCONFIRMED): 経路・転送先は前 tick から不変で異常なし。

## SCORE → SELECT (WIP=1)

- SCORECARD.md は 2026-08-14 観測分のまま変更なし。全候補行 blocked
  (counsel gate / no contact path / red gate)。
- **Selected action は前 run から交代せず WIP 継続**:
  「x402 rejections の分類漏れ解消 — owner へ draft 報告」。
  根拠の数字: submissions 4 / rejections 4 / classified 1 / unexplained 3 /
  settlements 0 / settlement-rate 0 / unpriced-plane-reads 4518。
  流量 4518 read に対し課金試行 4 が全敗かつ 3/4 原因不明 — ボトルネックは
  流量でなく rejection 観測性であり、learn 5 / conf 5 を満たす。
- Bounded experiment (送信なし・deploy なし): 本 run file を draft とし、
  宛先 = owner (junkawasaki) への報告を cron 出力で提示して止まる。

## Owner への draft 報告 (送信はしない)

宛先: junkawasaki (owner)。
(1) x402: rejection 4 件のうち 3 件が `rejections-unexplained` のまま
  3 tick 不変。reason 分類ログ出力の修正を推奨。
(2) cron プロンプトの `kotobase_lead_loop.cljs` → `.cljk` 修正 (3 tick ENOENT 継続)。
(3) SOUL.md の bare `/ipld/` 期待値 404 → 400 更新の判断 (4 tick 不一致)。
(4) 本 tick、host terminal が一部コマンドで空出力を返す不調あり
  (処理は代替手段で完遂、kotobase.net 側には影響なし)。

## Decision → HOLD (spend 0 / outbound send 0 / deploy 0)

- canvas-ledger.edn は未触 (single-writer rule 遵守)。
- 次の検証: `rejections-unexplained` が 3 → 減ったか / twin-reads 継続増 / funnel delta、
  /signup 200 の 4 tick 目、bare `/ipld/` の 400 継続観察。
