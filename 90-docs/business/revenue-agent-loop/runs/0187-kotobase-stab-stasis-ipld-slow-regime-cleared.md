# Run 0187 — kotobase-stab loop — 2026-09-14 JST (cron tick)

## Measured (live)
- `nbb 90-docs/business/revenue-agent-loop/kotobase_lead_loop.cljk funnel-pulse`
  (EXIT=0; `.cljk` 正。SOUL の `.cljs` 表記は ENOENT — 0186 と同じ):
  SCANNED 1; VISITORS 13049; SIGNUPS 42; CHECKOUTS 1;
  DELTA {:visitors 124, :signups 0, :checkouts 0}; UNCHANGED false;
  EXTERNAL-FUNNEL-CHANGE false; SCORE unchanged; RESULT recorded.
- `curl /api/funnel` HTTP 200: visitors 13047; signups 42; checkouts 1;
  registrations 4 (all source "other"); by-source visitors organic 11445 +
  freebuff 23 + openai-ads 4 = 11472 of 13047 → unattributed gap 1575
  (0186: 1575 — flat, observe only). signups by-source: organic 32 +
  openai-ads 2 + freebuff 1 + other 4 = 39 of 42 (3 unexplained, unchanged).
  checkouts: organic 1 (unchanged; still UNVERIFIED since 0185).
  micro: docs_view 4 / github_click 2 / cli_copy 2.
- x402: challenges 40, submissions 4, rejections 4 (1 classified
  malformed-header, 3 unexplained), settlements 0, settlement-rate 0,
  attempt-rate 0.1, unmetered-twin-reads 1160 (0186 同値),
  unpriced-plane-reads 4518 (0186 同値)。demand signal 無し。
- checkout は 1 件のまま据え置き。新規 checkout 無し。
- 注意: curl -w の HTTP code / time が直接 stdout に出ない環境異常が本 tick
  前半にあり (foreground terminal が stdout を空で返す)、
  output-to-file + background 実行で全測定をやり直した。数値はその file から
  verbatim。SOUL の「応答なし = not-measured」記法に従い、最初の 2 試行は
  not-measured として棄却した。

## Stability check (HTTP; output-to-file 経由)
- `/` 200 — 生 HTML 取得 (kotobase home, DADS vendored CSS) — 正常
- `/signup` 200 — 生 HTML 取得 ("Start free — kotobase") — 正常
- `/api/funnel` 200 — 正常
- `/ipld/v1` 400 "invalid or corrupt CID block" = route 存在の正常値
- 5xx 無し。service up。

## Bounded experiment — /ipld/v1 5-sample probe
- sample1 400 0.039s; sample2 400 0.040s; sample3 400 0.294s;
  sample4 400 0.133s; sample5 400 0.035s; home-baseline 200 0.061s.
- **5/5 in-band (≤0.30s), 0 slow, 0 timeout。slow-regime は今 tick で解消。**
- Pattern ledger (run files から): 0183 5/5 clean; 0184 4 clean + 0.86s +
  30s-timeout; 0185 2 clean + 0.74/7.66/16.96s; 0186 3 clean + 0.83/23.33s;
  0187 5/5 clean。時間帯依存の intermittent pattern が最も整合
  (原因 UNCONFIRMED — owner incident-note は 0185 内に draft 済み、未送信)。

## Score->Select (WIP=1)
- Selected action: UNCHANGED — signups 0 delta / checkouts 0 delta / x402
  settlements 0 のため、0186 と同じ選択を維持する根拠が最も強い。
- Action score: 58/100 unchanged (0186 と同根拠)。唯一の gate は counsel
  written-advice return (SCORECARD observed 2026-08-14、それより新しい
  scorecard は無い)。
- Draft 0045 signup-activation は owner go/no-go 待ちで standby 継続。
  visitors +124 / signups +0 では trigger 変化なし。

## Decision -> HOLD
- spend 無し / self-purchase 無し / outbound send 無し / deploy 無し。
- canvas-ledger.edn は触っていない (single-writer rule)。

## Next verification
1) Owner: 0185 の checkout 1 件の検証 (live vs test mode vs self-purchase)。
2) Owner: counsel written reply (75/65/62/61/51/48pt rows の unblock)。
3) Owner: go/no-go on draft 0045 signup-activation。
4) x402: submissions/settlements の変化 = 最初の demand signal。
5) Next tick: 5-sample /ipld probe を時間帯刻みで継続。slow が再発したら
   時間帯分布を更新; 5/5 clean が続けば 0182-0186 の slow-regime は
   transient と結論して probe 頻度を下げる判断材料にする。
6) terminal frontend の stdout 不出力異常が再発するか観察 (本 tick は
   file リダイレクトで回避した; 原因 UNCONFIRMED)。

## exaggeration-guard
- verified external revenue == 0; checkout 1 件は未検証 event;
  42 signups は revenue ではない; x402 settlements 0。全数値は live 計測
  (nbb EXIT 0 + HTTP 200/400 生 body + curl レイテンシ verbatim)。捏造なし。
