# run 2026-09-18 (2) — kotobase-stab: stasis /signup 200 3 tick / x402 unexplained 3 unchanged

## 計測 (生値、捏造なし)

- cron プロンプトの `kotobase_lead_loop.cljs` → ENOENT (3 tick 連続、同一不整合)。
  正本 `.cljk` を使用: `nbb kotobase_lead_loop.cljk funnel-pulse` → EXIT=0:
  `SCANNED 1 / VISITORS 13331 / SIGNUPS 42 / CHECKOUTS 1 /
  DELTA {:visitors 7, :signups 0, :checkouts 0} / UNCHANGED false /
  EXTERNAL-FUNNEL-CHANGE false / SCORE unchanged / RESULT recorded`。
  ※ 前 run (2026-09-18 tick 1) の記載 VISITORS 13321 は当 tick の生値と一致せず
  (API は 13331 を返す)。本 run は当時のファイルを未検証のため訂正せず、
  連続性の注記として残す。
- `GET https://kotobase.net/api/funnel` → 200 (0.18s):
  - funnel: visitors 13331 (organic 11734 / freebuff 23 / openai-ads 4)、
    signups 42 (organic 32 / other 4 / openai-ads 2 / freebuff 1)、checkouts 1。
  - micro: docs_view 4 / github_click 2 / cli_copy 2 (前 tick と同値)。
  - x402: submissions 4 / rejections 4 / rejections-classified 1
    (malformed-header 1) / **rejections-unexplained 3 (変化なし)** /
    settlement-rate 0 / settlements 0 / attempt-rate 0.0870 /
    unpriced-plane-reads 4518 / challenges 46 / unmetered-twin-reads 1170
    (前 tick 1163 → +7、visitors delta と同時刻に動いた)。
- 前 run からの変化: visitors +7、twin-reads +7 のみ。signups / checkouts /
  x402 未分類 3 件は変化なし。

## 安定化チェック (follow 済み、実測)

- `GET /` → 301 → `https://graph.kotoba.cloud/` → **200** (0.50s) ✅
- `GET /signup` → 301 → 同 graph.kotoba.cloud → **200** (0.43s) ✅
  (300 連続超 /signup 200 維持 3 tick 目)
- `GET /ipld/v1` → 302 → `https://ipfs.kotobase.net/ipfs/v1` → **400**
  = route 存在の正常値 ✅ (0.26s)
- `GET /ipld/` (bare) → 302 → `https://ipfs.kotobase.net/ipfs/` → **400**
  (SOUL.md 期待 404 とは連続不一致 → baseline 更新待ち、UNCONFIRMED)
- 原因推定 1 行 (UNCONFIRMED): 経路は前 tick から変化なし、転送先
  (kotoba.cloud / ipfs.kotobase.net) は安定稼働しており異常なし。

## SCORE → SELECT (WIP=1)

- SCORECARD.md は 2026-08-14 観測分のまま変更なし。全候補行 blocked
  (counsel gate / no contact path / red gate)。
- **Selected action (前 run から WIP 継続)**: 「x402 rejections 未分類 3 件の
  解消 — owner へ draft 報告」。根拠数字: submissions 4 / rejections 4 /
  classified 1 / unexplained 3 / settlements 0 / settlement-rate 0。
  unpriced-plane-reads 4518 に対し課金試行 4 全敗・3/4 原因不明 —
  一次ボトルネックは rejection 観測性で、代替候補より先。
- **Bounded experiment (本 run で実行済み)**: gleif x402 listing の生存確認 —
  `GET https://x402.nexus/gateway/gleif/v1/lei/01ERPZV3DOLNXY2MLB90`
  (X-PAYMENT なし) → **402** 0.60s、body は `scheme: transaction` /
  `maxAmountRequired: 1000` / payTo family Safe を広告 (run 0036 後の期待値と
  一致)。listing は live・レールは稼働中だが、外部 buyer の支払いは依然 0
  (settlements 0)。送信なし / deploy なし / spend なし。

## Owner への draft 報告 (送信はしない)

宛先: junkawasaki (owner)。
(1) x402: 直近 4 rejection のうち 3 件が `rejections-unexplained` のまま
  前進なし (2 tick 連続同値)。settlement-rate 0 の原因が malformed-header 1 件
  以外見えない状態が続く。rejection reason の 4/4 分類ログ出力を推奨。
(2) cron プロンプト側の `kotobase_lead_loop.cljs` → `.cljk` 修正 (3 tick
  ENOENT 継続)。
(3) SOUL.md の bare `/ipld/` 期待値 404 → 400 (ipfs.kotobase.net 実測) 更新判断。

## Decision → HOLD (spend 0 / outbound send 0 / deploy 0)

- canvas-ledger.edn は未触 (single-writer rule 遵守)。
- 次の検証: funnel API の `rejections-unexplained` が 3 → 減るか /
  visitors delta / /signup 200 4 tick 目 / gleif 402 広告に
  scheme 退行 (transaction → exact) がないかの再確認。
