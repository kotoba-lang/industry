# run 2026-09-19 — kotobase-stab: x402 unexplained 3 件のまま (WIP 継続)、visitors +21 tick

## 計測 (生値、捏造なし)

- cron プロンプトの `kotobase_lead_loop.cljs` → **ENOENT** (3 tick 連続)。
  正本は `kotobase_lead_loop.cljk`。`nbb ... .cljk funnel-pulse` → EXIT=0:
  `VISITORS 13342 / SIGNUPS 42 / CHECKOUTS 1 / DELTA {:visitors 9, :signups 0,
  :checkouts 0} / UNCHANGED false / RESULT recorded`。
  (注: DELTA の visitors 9 はスクリプト内部の前回記録との差。前 run file 記載の
  13321 との差は +21。signups 42 / checkouts 1 は 3 tick 連続据え置き。)
- `GET https://kotobase.net/api/funnel` → 200:
  - funnel: visitors 13342 (organic 11745 / freebuff 23 / openai-ads 4)、
    signups 42 (organic 32 / other 4 / openai-ads 2 / freebuff 1)、
    checkouts 1 (organic)。
  - registrations 4 (すべて source "other")。
  - micro: docs_view 4 / github_click 2 / cli_copy 2 — 4 tick 連続同値 (増えず)。
  - x402: submissions 4 / rejections 4 / rejections-classified 1
    (malformed-header 1) / **rejections-unexplained 3** / settlements 0 /
    settlement-rate 0 / attempt-rate 0.0870 / unmetered-twin-reads 1176
    (前 1163, +13) / unpriced-plane-reads 4518 (据え置き) / challenges 46 /
    unmetered-twin-ratio 25.57 (前 25.28)。
- 変化点: visitors +21、twin-reads +13 のみ。x402 の未分類 3 件は
  **5 tick 連続で変化なし** — 前 run の owner 報告 draft は owner 側で
  未処理のまま (owner 側事実は未測定 — not measured)。

## 安定化チェック (follow 済み)

- `GET /` → 301 → `graph.kotoba.cloud` → **200** (0.07s) ✅
- `GET /signup` → 301 → **200** (0.03s) ✅ (解消後 3 tick 連続 200)
- `GET /ipld/v1` → 302 → `ipfs.kotobase.net/ipfs/v1` → **400** = route 存在の
  正常値 ✅
- `GET /ipld/` (bare) → 302 → **400**。SOUL.md 期待値 (404) との不一致は
  4 tick 連続 → baseline は 400 側に寄せて観察継続 (UNCONFIRMED)。
- 原因推定 1 行 (UNCONFIRMED): すべての転送先 (graph.kotoba.cloud /
  ipfs.kotobase.net) が 200/400 応答し、origin 障害の兆候なし。

## SCORE → SELECT (WIP=1)

- SCORECARD.md は 2026-08-14 観測分のまま変更なし。全候補行 blocked
  (counsel gate / no contact path / red gate)。
- **Selected action: WIP 継続** — 「x402 rejections の分類漏れ解消」。
  根拠の数字: rejections 4 / classified 1 / **unexplained 3 (5 tick 変化なし)** /
  settlements 0 / settlement-rate 0 / attempt-rate 0.0870。対して本 tick の
  消费側変化は visitors +21 / twin-reads +13 のみで、signups・checkouts とも
  0 増。rejection 観測性がゼロ→計測可能になる以外に動くレバーが
  funnel 側にも micro 側にも現れていない。
- 変化なし: WIP が解決されていないため交代しない。ただし同一 draft の
  再送は情報量ゼロなので、本 run は前 run (2026-09-18) の draft を参照させる
  形とし、新規 draft 文面は書かない。
- Bounded experiment (送信なし・deploy なし): なし。観測 tick として記録のみ。

## Decision → HOLD (spend 0 / outbound send 0 / deploy 0)

- canvas-ledger.edn は未触 (single-writer rule 遵守)。
- cron プロンプト側の `.cljs` → `.cljk` 修正は 3 tick 連続未反映 (owner 待ち)。
- 次の検証: `rejections-unexplained` が 3 → 0/分類済み化したか、
  unpriced-plane-reads が 4518 から動くか、/signup 200 の 4 tick 目。
