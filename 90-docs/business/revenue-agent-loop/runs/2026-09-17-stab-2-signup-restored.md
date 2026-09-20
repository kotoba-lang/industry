# run 2026-09-17 (tick 2) — kotobase-stab: /signup 404 解消を確認、x402 rejections 未分類 3 件へ注目

## 計測 (生値、捏造なし)

- cron プロンプトの `kotobase_lead_loop.cljs` → **ENOENT** (実測)。正本は
  `kotobase_lead_loop.cljk` (前 run と同じ不整合、未修正)。
- `nbb kotobase_lead_loop.cljk funnel-pulse` → 成功 (EXIT=0):
  `SCANNED 1 / VISITORS 13319 / SIGNUPS 42 / CHECKOUTS 1 / DELTA {:visitors 1,:signups 0,:checkouts 0} / UNCHANGED false / RESULT recorded`
  → 前 tick (visitors 0, isolate-memory) から **durable 値に置き換わっている**。
  13319/42/1 が真の lifetime かは API 本文のみでは確定できない (UNCONFIRMED)。
- `GET https://kotobase.net/api/funnel` → 200:
  visitors 13319 (organic 11722 / freebuff 23 / openai-ads 4) / signups 42
  (organic 32 / other 4 / openai-ads 2 / freebuff 1) / checkouts 1 (organic) /
  registrations 4 (すべて source "other") /
  micro: docs_view 4, github_click 2, cli_copy 2 /
  x402: submissions 4, rejections 4 (**rejections-unexplained 3**,
  classified 1 = malformed-header), settlement-rate 0, settlements 0,
  attempt-rate 0.0869, unmetered-twin-reads 1161, unpriced-plane-reads 4518,
  challenges 46, unmetered-twin-ratio 25.24。
- この API は前 tick 観測の `persistence.kind "isolate-memory"` 注記を**今回含まない**
  (durable store に移った可能性、UNCONFIRMED — 前回値 0 からの非連続ジャンプと整合)。

## 安定化チェック (follow 済み)

- `GET /` → 301 → `graph.kotoba.cloud` → **200** ✅
- `GET /signup` → 301 → `graph.kotoba.cloud/signup` → **200** ✅
  (**7 tick 連続だった 404 が解消**。body は "Graph & Ontology database — Kotoba
  Cloud" 正常レンダ、noindex、freebuff タグ含む。signup form 自体の DOM は
  今 tick 未検証 — curl 出力は head 切りまでで確認)
- `GET /ipld/v1` → 302 → `ipfs.kotobase.net/ipfs/v1` → **400**
  ("ipfs origin id must be a CIDv1 base32 DNS label") = route 存在の正常値 ✅
- `GET /ipld/` (bare) → 302 → 400 ("missing target")。SOUL.md の 2026-09-03 実測
  (404 正常) から引き続き **400 に変化中** (前 run も同報告)。bare route の期待値
  404 は ipfs.kotobase.net 側の挙動と不一致 — baseline 要更新 (UNCONFIRMED)。
- 原因推定 1 行 (UNCONFIRMED): Cloudflare 転送先 (kotoba.cloud / ipfs.kotobase.net)
  側が修正され、全経路が実在 route に到達するようになった。

## SCORE → SELECT (WIP=1)

- SCORECARD.md は 2026-08-14 観測分のまま (更新なし)。全候補行 blocked
  (counsel gate / no contact path / red gate)。
- **前 run の selected action「/signup 404 の owner 報告」は達成と判定**
  (200 を実測)。交代。
- Selected action (1 件): **x402 rejections の分類漏れ解消 — 3/4 が
  unexplained、settlement-rate 0** を API 観測 + owner へ draft 報告。
  根拠の数字: submissions 4 / rejections 4 / classified 1 / unexplained 3 /
  settlements 0 / attempt-rate 0.087。読み手 4518 unpriced-plane-reads に対し
  課金試行 4 は全敗 — 収益レールの 1 次ボトルネックは流量でなく rejection 分類。
- Bounded experiment (外向け送信なし、deploy なし):
  draft 報告 = 本 run file + cron 報告。宛先: owner (junkawasaki)。
  内容: (1) x402 の rejection reason を 4/4 すべて分類ログに出す
  (現状 3 件 unexplained)。settlement-rate 0 の原因が malformed-header 1 件
  以外に見えない。(2) funnel API が isolate-memory 注記を外したことの確認と、
  13319/42/1 が durable かの明示。(3) `kotobase_lead_loop.cljs` → `.cljk`
  (cron プロンプト側)。

## Decision → HOLD (spend 0 / outbound send 0 / deploy 0)

- canvas-ledger.edn は未触 (single-writer rule)。

## Next verification

1) `/signup` の DOM に signup form / CTA が実在するか head 以上で検証。
2) `api/funnel` を複数 tick 観測し isolate-memory 注記の消失が恒久か確認
   (durable 化の実証)。
3) x402 rejection 3 件の reason が次 tick までに分類されるか再実測。
4) terminal stdout 異常は本 tick も継続 (全コマンド stdout 0 バイト、
   file redirect で回避)。run 0187 以降の既知事象。

## exaggeration-guard

- verified external revenue == 0 (SCORECARD 時点から変化なし)。
- 13319/42/1 は API 実測値だが lifetime 性は UNCONFIRMED。
- /signup 200 は本 tick の生実測。form 有無は未検証。
