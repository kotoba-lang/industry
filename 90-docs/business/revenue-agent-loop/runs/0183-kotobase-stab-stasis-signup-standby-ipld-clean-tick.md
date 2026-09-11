# Run 0183 - kotobase-stab loop - 2026-09-11 JST (cron tick, ~23:04 UTC start)

## Measured (live)
- nbb funnel-pulse (workdir com-junkawasaki, EXIT=0): SCANNED 1; VISITORS 11996; SIGNUPS 33; CHECKOUTS 0; DELTA {:visitors 1, :signups 0, :checkouts 0}; UNCHANGED false; EXTERNAL-FUNNEL-CHANGE false; SCORE unchanged; RESULT recorded
- curl /api/funnel HTTP 200: visitors 11996; signups 33; checkouts 0 — agrees with nbb read.
- by-source visitors: organic 10421 + openai-ads 3 = 10424 of 11996: gap 1572 (flat vs run 0182's 1574; data-quality observe only)
- signups by-source: organic 29 + other 1 = 30 of 33 accounted; 3 unexplained (same as 0180–0182). This bot did NOT POST /signup.
- x402: challenges 40; submissions 4; rejections 4 (1 malformed-header classified, 3 unexplained); settlements 0; settlement-rate 0; unmetered-twin-ratio 0; attempt-rate 0.1 — flat vs 0181/0182; no x402 demand signal.
- 4th consecutive tick with signups = 33. No new trigger event; attribution of the 31→33 move still unconfirmed by owner.

## Stability check (HTTP status; latencies in the latency probe section)
- `/`            200 — expected
- `/signup`      200 — expected
- `/api/funnel`  200 — expected
- `/ipld/v1`     400 = expected (route exists)
- `/ipld/`       404 — expected (verified this tick)
- No 5xx; no endpoint down this tick.

## Bounded experiment — /ipld/v1 latency probe with time-of-day + /signup-latency columns (executed per run 0182 next-verification #3)
- UTC start 23:04:35Z. /signup: 200 @ 0.324s. Then 5 bare GETs to /ipld/v1:
  - 400 @ 0.039s; 400 @ 0.033s; 400 @ 0.039s; 400 @ 0.036s; 400 @ 0.036s
- Result: 5/5 clean sub-50ms samples this tick. No outlier this hour.
- Pattern ledger (from run files, not synthesized): 0179 one-off slow; 0180 clean; 0181 13.2s; 0182 4 clean + 2.3s + 30s-timeout; **0183 (this run, 23:04 UTC): 5/5 clean.**
- Interpretation stays bounded: outliers are intermittent, not continuous. One clean hour does not refute recurrence — accumulation continues before any cause claim. No payload/cause claim beyond prior "possible cold start / cold graph read" (unconfirmed).

## Score->Select (WIP=1)
- selected -> UNCHANGED — counsel written-advice return on net-kotobase legal packet; sole remaining gate on the 75pt row (cloud-itonami paid pilot to external tenants) and transitively the 65/62/61/51/48pt red rows (SCORECARD.md, observed 2026-08-14)
- selected-action score -> 58/100 unchanged (33 signups not revenue; checkouts 0; x402 settlements 0)
- WIP=1 held. Nothing newly selected: (1) counsel gate — owner-side blocker, unchanged; (2) draft 0045 signup-activation draft remains surfaced for owner go/no-go since 0181; signups stayed 33 → no new trigger event.

## Decision -> HOLD
- no spend; no self-purchase; no outbound send; no deploy; revenue = 0 measured (33 signups NOT revenue; checkouts 0; x402 settlements 0); canvas-ledger-touched? false

## Next verification
1) owner go/no-go on draft 0045 (blocker: owner) — do not implement/send without approval
2) counsel written reply (owner-side; unblocks 75/65/62/61/51/48pt rows)
3) /ipld/v1 latency: continue time-of-day-tagged samples; 2+ clean consecutive ticks at different hours → downgrade the pattern note to "resolved/intermittent-only"; any new outlier → correlate with /signup latency (this tick's baseline: 0.324s)
4) signups 33 attribution (owner/test cannot be excluded from this side)
5) x402: submissions 4 / settle 0 / 3 rejections unexplained — any change is first demand signal
6) terminal stdout channel still degraded (empty stdout, exit codes only); file-redirect workaround used and working again this tick

## exaggeration-guard
- external revenue == 0; 33 signups NOT revenue; x402 settle 0 NOT revenue; +1 visitor tick is not a paying customer; all numbers read live (HTTP 200 funnel + nbb EXIT 0 + raw curl samples); nothing sent; nothing deployed; nothing fabricated
