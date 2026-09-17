# Run 0186 — kotobase-stab loop — 2026-09-13 JST (cron tick, UTC ~11:05–11:15)

## Measured (live)
- `nbb 90-docs/business/revenue-agent-loop/kotobase_lead_loop.cljk funnel-pulse`
  (EXIT=0; SOUL の `.cljs` は ENOENT — `.cljk` が正。runs 0184/0185 同様):
  SCANNED 1; VISITORS 12925; SIGNUPS 42; CHECKOUTS 1;
  DELTA {:visitors 66, :signups 0, :checkouts 0}; UNCHANGED false;
  EXTERNAL-FUNNEL-CHANGE false; SCORE unchanged; RESULT recorded.
- `curl /api/funnel` HTTP 200: visitors 12925; signups 42; checkouts 1;
  registrations 4 (all source "other"); by-source visitors organic 11323 +
  freebuff 23 + openai-ads 4 = 11350 of 12925 → unattributed gap 1575
  (runs 0184/0185: 1577 / 1577 — flat, observe only). signups by-source:
  organic 32 + openai-ads 2 + freebuff 1 + other 4 = 39 of 42 (3 unexplained,
  unchanged since 0180). checkouts: organic 1 (unchanged; still UNVERIFIED).
- x402: challenges 40, submissions 4, rejections 4 (1 classified
  malformed-header, 3 unexplained), settlements 0, settlement-rate 0,
  attempt-rate 0.1, unmetered-twin-reads 1160, unpriced-plane-reads 4518.
  与 0185 実質同値 (twin-reads 1147→1160, unpriced 4518→4518) — demand signal 無し。
- **checkout は 0185 の 0→1 のまま据え置き。新規 checkout 無し。**

## Stability check (HTTP; 11:13–11:14 UTC)
- `/` 200 @ 0.171s — 正常
- `/signup` 200 @ 0.080s — 正常
- `/api/funnel` 200 — 正常
- `/ipld/v1` 400 = route 存在の正常値
- `/ipld/` 404 = bare は正常 (2026-09-03 実測)
- 5xx 無し。service up。

## Bounded experiment — /ipld/v1 5-sample probe (UTC start 11:14:12Z)
- sample1 400 0.364s; sample2 400 23.331s; sample3 400 0.829s;
  sample4 400 0.043s; sample5 400 0.454s; signup-baseline 200 0.068s.
- 3/5 in-band (≤0.45s), 2/5 slow (0.83 / 23.33s), 0 timeouts.
  slow-regime 継続 (0182, 0184, 0185 と同型; 今 tick は 23.3s が最大)。
  signup baseline 0.068s → 今 tick は /ipld に局在した遅さ。原因 UNCONFIRMED。
- Pattern ledger (run files から): 0182 4 clean + 2.3s + 30s-timeout;
  0183 5/5 clean; 0184 4 clean + 0.86s + 30s-timeout; 0185 2 clean +
  0.74/7.66/16.96s; 0186 3 clean + 0.83/23.33s. 時間帯刻みで継続観測中。
- owner incident-note draft は run 0185 内に既に書かれている (未送信のまま)。
  本 tick でタイムラインに 0186 の 1 行を追加しただけ。送信はしない。

## Score->Select (WIP=1)
- Selected action: UNCHANGED — signups 0 delta / checkouts 0 delta / x402
  settlements 0 のため、0185 と同じ選択を維持する根拠が最も強い。
  唯一の gate は counsel written-advice return (75pt row の gate;
  SCORECARD observed 2026-08-14, より新しい scorecard は見つかっていない)。
- Action score: 58/100 unchanged (0185 と同根拠。verified external revenue
  は本 bot の測定範囲で 0 のまま; checkout 1 件は未検証 event)。
- Draft 0045 signup-activation は owner go/no-go 待ちで standby 継続。
  visitors +66 / signups +0 のため新たな trigger にはならない。

## Decision -> HOLD
- spend 無し / self-purchase 無し / outbound send 無し / deploy 無し。
- canvas-ledger.edn は触っていない (single-writer rule)。

## Next verification
1) Owner: 0185 の checkout 1 件の検証 (live vs test mode vs self-purchase)
   — これが済むまで checkout は revenue として数えない。
2) Owner: counsel written reply (75/65/62/61/51/48pt rows の unblock)。
3) Owner: go/no-go on draft 0045 signup-activation。
4) x402: submissions/settlements の変化 = 最初の demand signal。
5) Next tick: 5-sample /ipld/v1 probe を時間帯刻みで継続 (slow-regime の
   時間帯分布を蓄積)。`/` レイテンシも 1 sample 足す。
6) SOUL/runbook: funnel-pulse path は `.cljk` (`.cljs` は ENOENT) — 未修正。

## exaggeration-guard
- verified external revenue == 0; checkout 1 件は未検証 event;
  42 signups は revenue ではない; x402 settlements 0。全数値は live 計測
  (HTTP 200 raw + nbb EXIT 0 + curl レイテンシ verbatim)。捏造なし。
