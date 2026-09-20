# run 2026-09-18 — kotobase-stab: WIP 継続 (x402 unexplained rejections)、/signup 200 維持 2 tick

## 計測 (生値、捏造なし)

- cron プロンプトの `kotobase_lead_loop.cljs` → **ENOENT** (2 tick 連続、同一不整合)。
  正本は `kotobase_lead_loop.cljk`。`nbb ... .cljk funnel-pulse` → EXIT=0:
  `VISITORS 13321 / SIGNUPS 42 / CHECKOUTS 1 / DELTA {:visitors 3, :signups 0,
  :checkouts 0} / UNCHANGED false / RESULT recorded`
  (direnv `.envrc` blocked の警告は出たがスクリプト自体は成功)。
- `GET https://kotobase.net/api/funnel` → 200:
  - funnel: visitors 13321 (organic 11724 / freebuff 23 / openai-ads 4)、
    signups 42 (organic 32 / other 4 / openai-ads 2 / freebuff 1)、
    checkouts 1 (organic)。
  - registrations 4 (すべて source "other")。
  - micro: docs_view 4 / github_click 2 / cli_copy 2 (前 tick と同値、増えず)。
  - x402: submissions 4 / rejections 4 / **rejections-classified 1
    (malformed-header 1) / rejections-unexplained 3** / settlement-rate 0 /
    settlements 0 / attempt-rate 0.0870 / unmetered-twin-reads 1163 (前 1161,
    +2) / unpriced-plane-reads 4518 / challenges 46 / unmetered-twin-ratio
    25.28 (前 25.24)。
- 前 run からの変化は visitors +2、twin-reads +2 のみ。x402 の未分類 3 件は
  **変化なし** (前 run の draft 報告はまだ owner 側で未処理と推定されるが、
  owner 側事実は未測定 — not measured)。

## 安定化チェック (follow 済み)

- `GET /` → 301 → `graph.kotoba.cloud` → **200** (0.45s) ✅
- `GET /signup` → 301 → **200** (0.26s) ✅ (解消後 2 tick 連続 200)
- `GET /ipld/v1` → 302 → `ipfs.kotobase.net/ipfs/v1` → **400** = route 存在の
  正常値 ✅
- `GET /ipld/` (bare) → 302 → **400**。SOUL.md の期待値 (404 正常) とは 3 tick
  連続で不一致 → baseline 更新が必要 (UNCONFIRMED のまま、次回も観察)。
- 原因推定 1 行 (UNCONFIRMED): 経路は前 tick から変化なし、転送先
  (kotoba.cloud / ipfs.kotobase.net) が安定稼働している。

## SCORE → SELECT (WIP=1)

- SCORECARD.md は 2026-08-14 観測分のまま変更なし。全候補行 blocked
  (counsel gate / no contact path / red gate)。
- **Selected action は前 run (2026-09-17 tick 2) から交代せず WIP 継続**:
  「x402 rejections の分類漏れ解消 — owner へ draft 報告」。
  根拠の数字: submissions 4 / rejections 4 / classified 1 / unexplained 3 /
  settlements 0 / settlement-rate 0。読み手 4518 (unpriced-plane-reads) に対し
  課金試行 4 が全敗で、しかも 3/4 の原因が観測不能 — レールの 1 次ボトルネックは
  流量でなく rejection 観測性。代替候補 (funnel CVR 改善等) より
  「ゼロ→計測可能」への変化が先で、score 上も learn 5 / conf 5 を満たす。
- Bounded experiment (送信なし・deploy なし): 本 run file を draft とし、
  宛先 = owner (junkawasaki) への報告を cron 出力で提示して止まる。

## Owner への draft 報告 (送信はしない)

宛先: junkawasaki (owner)。
(1) x402: 直近 4 rejection のうち 3 件が `rejections-unexplained`。
  rejection reason を 4/4 分類ログに出す修正を推奨。settlement-rate 0 の
  原因が malformed-header 1 件以外見えない状態が続いている。
(2) `kotobase_lead_loop.cljs` → `.cljk` に cron プロンプト側を修正 (2 tick
  ENOENT 継続)。
(3) SOUL.md の bare `/ipld/` 期待値を 404 → 400 (ipfs.kotobase.net 実測) に
  更新するかの判断。

## Decision → HOLD (spend 0 / outbound send 0 / deploy 0)

- canvas-ledger.edn は未触 (single-writer rule 遵守)。
- 次の検証: funnel API で `rejections-unexplained` が 3 → 0/分類済み化したか、
  および visitors/signup/checkouts の delta。/signup 200 の 3 tick 目。
