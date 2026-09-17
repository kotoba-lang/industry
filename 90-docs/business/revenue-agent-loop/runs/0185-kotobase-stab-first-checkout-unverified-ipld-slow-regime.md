# Run 0185 - kotobase-stab loop - 2026-09-13 JST (cron tick, UTC ~05:04–05:10)

## Measured (live)
- kbb --backend sci kotobase_lead_loop.cljk funnel-pulse (workdir
  com-junkawasaki, EXIT=0, file-redirect workaround):
  SCANNED 1; VISITORS 12859; SIGNUPS 42; CHECKOUTS 1;
  DELTA {:visitors 665, :signups 3, :checkouts 0}; UNCHANGED false;
  EXTERNAL-FUNNEL-CHANGE false; SCORE unchanged; RESULT recorded.
  NOTE: SOUL/runbook path still says .cljs (ENOENTs); .cljk used, as run 0184
  already recommended.
- curl /api/funnel HTTP 200 (raw): visitors 12858; signups 42; checkouts 1;
  registrations 4 (all source "other"); x402 challenges 40, submissions 4,
  rejections 4 (1 classified malformed-header, 3 unexplained),
  settlements 0, settlement-rate 0, unmetered-twin-ratio 28.675,
  unmetered-twin-reads 1147, unpriced-plane-reads 4518, attempt-rate 0.1.
- by-source visitors: organic 11254 + freebuff 23 + openai-ads 4 = 11281 of
  12858; unattributed gap 1577 (runs 0183/0184: ~1572–1574 — flat, observe
  only). signups by-source: organic 32 + openai-ads 2 + freebuff 1 + other 4
  = 39 of 42; 3 unexplained (same 3 as runs 0180–0184). checkouts by-source:
  organic 1.
- FIRST CHECKOUT EVER OBSERVED in this funnel: checkouts 0→1, signups
  34→42 (+8). From this side it is one non-zero checkout event, source
  "organic". It is NOT verified revenue (this bot has no Stripe/payment
  readout; test-mode or owner self-purchase possibility NOT excluded).
- x402: identical counters to run 0184 (40/4/4, settle 0) — no demand signal.

## Stability check (HTTP; 05:04–05:06 UTC)
- `/` 200 @ 1.256s (230KB; vs 0.053s in run 0184 — single sample, observe only)
- `/signup` 200 @ 0.210s — expected
- `/api/funnel` 200 @ 0.567s — expected
- `/ipld/v1` 400 "invalid or corrupt CID block" = expected (route exists)
- `/ipld/` 404 = expected (bare /ipld/ normal; 2026-09-03 measured)
- No 5xx. Service up.

## Bounded experiment — /ipld/v1 latency probe, time-of-day tagged
(UTC start 05:06:34Z; 5 bare GETs, -m 30; signup baseline after)
- sample1 400 0.041s; sample2 400 0.735s; sample3 400 7.658s;
  sample4 400 0.041s; sample5 400 16.959s; signup-baseline 200 0.902s.
- Result: 2/5 in-band (≤0.05s), 3/5 slow (0.74 / 7.66 / 16.96 s), 0 timeouts.
  Continues the confirmed-intermittent pattern (runs 0182, 0184) with a new
  signature: multi-second stalls WITHOUT hard timeout, alternating with
  in-band samples. signup baseline 0.90s (vs 0.056s run 0184) — this tick the
  slowdown is NOT cleanly localized to /ipld (single-tick observation).
- Pattern ledger (from run files, not synthesized): 0182 4 clean + 2.3s +
  30s-timeout; 0183 5/5 clean; 0184 4 clean + 0.86s + 30s-timeout; 0185
  (this, 05:06 UTC) 2 clean + 0.74/7.66/16.96s, no timeout. Cause UNCONFIRMED.
- ESCALATION per run 0184 next-verification #1 (timeout/slow recurrence at a
  new hour): this run file IS the owner incident-note draft — timeline
  0179→0185, latencies verbatim, no cause claim. NOT SENT (no outbound send
  from this bot). Owner action: review runs/0179–0185 timeline; if desired,
  authorize a worker-side /ipld investigation.

## Score->Select (WIP=1)
- Selected action: UNCHANGED — counsel written-advice return on net-kotobase
  legal packet remains the sole gate on the 75pt row (cloud-itonami paid
  pilot to 5 existing external tenants) and transitively the 65/62/61/51/48
  red rows (SCORECARD observed 2026-08-14; no newer scorecard found).
- Action score: 58/100 unchanged. The checkout 0→1 does not raise it: one
  unverified checkout event is not revenue and not an external-conversion
  basis; SCORECARD still says verified external revenue = 0.
- WIP=1 held. Draft 0045 signup-activation remains surfaced for owner
  go/no-go; signups 34→42 is the largest single-tick delta so far (+8) but
  still not an owner trigger by itself — flagged to owner alongside the
  checkout event for verification.
- Not selected now (WIP=1): checkout-verification path (owner-side: confirm
  Stripe live/test mode and whether the checkout was owner self-purchase).

## Decision -> HOLD
- no spend; no self-purchase; no outbound send; no deploy; verified revenue
  still 0 as far as this bot can measure (1 checkout event, unverified;
  x402 settlements 0); canvas-ledger.edn NOT touched (single-writer rule).

## Next verification
1) Owner: verify the 1 checkout (live vs test mode vs self-purchase). Until
   then it stays "unverified event", never revenue.
2) Owner: incident note review — /ipld/v1 intermittent stalls now
   multi-second without timeouts (0179→0185 timeline in runs/).
3) Owner: counsel written reply (unblocks 75/65/62/61/51/48pt rows).
4) Owner: go/no-go on draft 0045 signup-activation (signups 42, +8 this tick).
5) x402: any change in submissions/settlements = first demand signal.
6) Next tick: repeat the 5-sample /ipld/v1 probe, time-of-day tagged; also
   re-sample `/` latency (this tick's 1.26s vs 0.05s may be noise — measure).
7) SOUL/runbook: funnel-pulse path is `.cljk` (not .cljs) — still unfixed.

## exaggeration-guard
- verified external revenue == 0; the 1 checkout is an UNVERIFIED event, not
  revenue; 42 signups NOT revenue; x402 settle 0 NOT revenue. All numbers
  read live (HTTP 200 funnel raw + kbb EXIT 0 + verbatim curl latency
  samples). Nothing sent; nothing deployed; nothing fabricated.
