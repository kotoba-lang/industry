# Run 0184 - kotobase-stab loop - 2026-09-11 JST (cron tick, ~05:07 UTC start)

## Measured (live)
- kbb --backend sci kotobase_lead_loop.cljk funnel-pulse (workdir com-junkawasaki, EXIT=0):
  SCANNED 1; VISITORS 12105; SIGNUPS 34; CHECKOUTS 0;
  DELTA {:visitors 109, :signups 1, :checkouts 0}; UNCHANGED false;
  EXTERNAL-FUNNEL-CHANGE false; SCORE unchanged; RESULT recorded.
  NOTE: script was renamed .cljs -> .cljk on disk (Sep 11 12:02 JST); the
  .cljs path in the runbook ENOENTs. Future ticks should call the .cljk path.
- curl /api/funnel HTTP 200 (raw): visitors 12103; signups 34; checkouts 0.
  nbb read (12105) is +2 visitors vs raw curl — concurrent traffic, not a
  contradiction; both reads show signups 34, checkouts 0.
- by-source visitors: organic 10522 + openai-ads 3 + freebuff 4 = 10529 of
  12103: unattributed gap 1574 (flat vs run 0183's 1572; data-quality
  observe only). NEW source key "freebuff" (4 visitors) — first appearance
  in this bot's runs; no signup attributed to it.
- signups by-source: organic 30 + other 1 = 31 of 34 accounted; 3
  unexplained (same 3 as runs 0180–0183). This bot did NOT POST /signup.
  SIGNUPS 33 → 34 (+1). Still no owner confirmation of the earlier 31→33
  move; attribution remains unverified from this side.
- x402: challenges 40; submissions 4; rejections 4 (1 malformed-header
  classified, 3 unexplained); settlements 0; settlement-rate 0;
  unmetered-twin-ratio 0; attempt-rate 0.1 — identical to run 0183; no x402
  demand signal.

## Stability check (HTTP status; measured 05:07–05:08 UTC)
- `/`            200 @ 0.053s — expected
- `/signup`      200 @ 0.060s (initial check) and 200 @ 0.056s (probe) — expected
- `/api/funnel`  200 — expected
- `/ipld/v1`     400 = expected (route exists)
- `/ipld/`       404 @ 0.046s — expected (verified this tick)
- No 5xx on any endpoint.

## Bounded experiment — /ipld/v1 latency probe, time-of-day tagged (per run 0183 next-verification #3)
- UTC start 05:07:47Z (new hour vs run 0183's 23:04 UTC). /signup baseline:
  200 @ 0.056s. Then 5 bare GETs to /ipld/v1:
  - 400 @ 0.038s; 400 @ 0.038s; 400 @ 0.040s; 400 @ 0.859s; **HTTP 000 @
    30.01s (curl timeout hit at -m 30)**.
- Result: FIRST RECURRENCE CAPTURED with a timeout — 4/5 samples in-band
  (≤0.86s, one mild 0.86s), 1/5 hard timeout ≥30s. This upgrades the
  pattern from "one clean hour" (run 0183) back to confirmed intermittent.
- Pattern ledger (from run files, not synthesized): 0179 one-off slow;
  0180 clean; 0181 13.2s slow; 0182 4 clean + 2.3s + 30s-timeout;
  0183 5/5 clean (23:04 UTC); **0184 (05:07 UTC): 4 clean + 0.86s + 30s-timeout.**
- Interpretation stays bounded: intermittent, hour-of-day independent so far
  (outliers at both 23h and 05h UTC). Sample #4 slowed to 0.86s immediately
  before #5 timed out — consistent with a stall onset, but cause remains
  UNCONFIRMED (no payload captured; prior "possible cold start / cold graph
  read" hypothesis unchanged and unverified).
- /signup latency stayed ~0.06s while /ipld/v1 stalled → stall looks
  localized to the /ipld path, not a global edge slowdown (single-tick
  observation, not a conclusion).

## Score->Select (WIP=1)
- selected -> UNCHANGED — counsel written-advice return on net-kotobase legal
  packet; sole remaining gate on the 75pt row (cloud-itonami paid pilot to
  external tenants) and transitively the 65/62/61/51/48pt red rows
  (SCORECARD.md, observed 2026-08-14; no newer scorecard present).
- selected-action score -> 58/100 unchanged (34 signups not revenue;
  checkouts 0; x402 settlements 0).
- WIP=1 held. Nothing newly selected: (1) counsel gate — owner-side blocker,
  unchanged; (2) draft 0045 signup-activation draft remains surfaced for
  owner go/no-go; signups 33→34 is a slow trickle, not a new trigger event.
- New observation worth a future action, not selected now (WIP=1): /ipld/v1
  30s timeout recurrence is a reliability risk on the only metered read
  plane; if it repeats next tick, a one-page incident note for the owner
  (timeline: runs 0179–0184) would be the bounded artifact. No outbound send.

## Decision -> HOLD
- no spend; no self-purchase; no outbound send; no deploy; revenue = 0
  measured (34 signups NOT revenue; checkouts 0; x402 settlements 0);
  canvas-ledger.edn NOT touched (single-writer rule respected).

## Next verification
1) /ipld/v1 latency: recurrence CONFIRMED again this tick (30s timeout +
   0.86s precursor). Next tick: repeat time-of-day-tagged 5-sample probe;
   if timeout recurs at a different hour, escalate to a one-page owner
   incident note (timeline 0179→0184, latencies verbatim, no cause claim).
2) owner go/no-go on draft 0045 (blocker: owner) — do not implement/send
   without approval.
3) counsel written reply (owner-side; unblocks 75/65/62/61/51/48pt rows).
4) signups: +1 this tick (33→34); the 31→33 attribution still unconfirmed
   by owner; continue observing by-source deltas.
5) x402: submissions 4 / settle 0 / 3 rejections unexplained — any change is
   first demand signal.
6) runbook fix needed: funnel-pulse script path is now
   `kotobase_lead_loop.cljk` (renamed from .cljs); update the SOUL/runbook
   reference to avoid future ENOENT not-measured ticks.
7) terminal stdout channel still degraded (empty stdout, exit codes only);
   file-redirect workaround used and working again this tick.

## exaggeration-guard
- external revenue == 0; 34 signups NOT revenue; x402 settle 0 NOT revenue;
  +1 signup tick is not a paying customer; all numbers read live (HTTP 200
  funnel raw + nbb EXIT 0 + verbatim curl latency samples); nothing sent;
  nothing deployed; nothing fabricated.
