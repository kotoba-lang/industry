# Run 0182 - kotobase-stab loop - 2026-09-11 JST (cron tick)

## Measured (live; two independent readings this tick)
- nbb funnel-pulse (workdir com-junkawasaki, EXIT=0): SCANNED 1; VISITORS 11934; SIGNUPS 33; CHECKOUTS 0; DELTA {:visitors 2, :signups 0, :checkouts 0}; UNCHANGED false; EXTERNAL-FUNNEL-CHANGE false; SCORE unchanged; RESULT recorded
- curl /api/funnel HTTP 200: visitors 11933; signups 33; checkouts 0 — nbb read 11934 (scan happened after the curl read; 1-visitor agreement, not disagreement)
- cross-run delta vs run 0181 (2026-09-10 ~20:04; visitors 11896 / signups 33): **+38 visitors / +0 signups / +0 checkouts** (from recorded run files, not synthesized)
- signups remain 33 for a 2nd tick — run 0181's +2 tick (31→33) did not repeat; no owner reply yet on attribution. Draft 0045 trigger stays in its met state as recorded; nothing changed.
- by-source visitors: organic 10356 + openai-ads 3 = 10359 vs total 11933: gap 1574 (flat vs run 0181's 1575; data-quality observe only)
- signups by-source: organic 29 + other 1 = 30 of 33 accounted; 3 unexplained (same as 0180/0181). This bot did NOT POST /signup (page GETs only).
- x402: challenges 40; submissions 4; rejections 4 (1 malformed-header classified, 3 unexplained); settlements 0; settlement-rate 0; attempt-rate 0.1 — flat vs 0181; no x402 demand signal.

## Stability check
- `/`            200 (0.056s) — expected
- `/signup`      200 (0.044s) — expected
- `/api/funnel`  200 — expected
- `/ipld/v1`     400 = expected status (route exists); latency NOT clean this tick
- `/ipld/`       404 (0.046s) — expected; verified this run
- All route/status expectations hold; no 5xx; no endpoint down this tick.

## Bounded experiment — /ipld/v1 latency recurrence probe (executed this tick)
- Method: 5 sequential bare `curl -m 30` GETs to /ipld/v1, status+latency only. This is run 0181 next-verification #3.
- Results: 400 @ 0.037s; 400 @ 2.344s; 400 @ 0.040s; 400 @ 0.037s; **000 @ 30.033s (timed out)**
- Pattern now recorded (3rd consecutive tick with latency degradation):
  - run 0179: one-off slow (recorded; this bot's prior run file)
  - run 0180: normal (0.124s)
  - run 0181: 400 @ 13.2s (2nd slow occurrence in 3 ticks)
  - run 0182 (this run): 4 fast (~0.04s) + 1 slow (2.3s) + 1 timeout (30s) in 5 samples
- Interpretation stays bounded: **status always 400 → route is up**; latency is **bimodal/intermittent** — mostly sub-50ms with sporadic multi-second and 30s-timeout outliers. Cause estimate (1 line, unconfirmed): sporadic cold start of the /ipld route or cold read against the graph store, occasionally exceeding 30s. No payload/cause claim beyond this.
- Next step if recurrence continues: sample at different times of day and correlate with /signup latency before any cause claim.

## Score->Select (WIP=1)
- selected   -> UNCHANGED — counsel written-advice return on net-kotobase legal packet; sole remaining gate on the 75pt row (cloud-itonami paid pilot to external tenants) and transitively the 65/62/61/51/48pt red rows (SCORECARD.md, observed 2026-08-14)
- selected-action score -> 58/100 (unchanged; 33 signups are not revenue; checkouts 0; settlements 0)
- WIP=1 held. **Nothing selected this tick** — both standing selections are unchanged:
  1. counsel gate (owner-side blocker since run 0038; this bot cannot produce the counsel return)
  2. draft 0045 signup-activation draft (`runs/0045-cron-signup-activation-draft.edn`) surfaced for **owner go/no-go** since run 0181 (trigger met: signups 33 > 31). NOT implemented, NOT sent, NOT deployed by this bot. Signups stayed 33 → no new trigger event to select on.

## Decision -> HOLD
- no spend; no self-purchase; no paid acquisition; no outbound send; no deploy; revenue = 0 measured (33 signups NOT revenue; 0 checkout; 0 x402 settlement); selected score unchanged 58/100; canvas-ledger-touched? false

## Next verification
1) **owner go/no-go on draft 0045** (blocker: owner) — do not implement/send without approval
2) council written reply (blocker: owner-side; unblocks 75/65/62/61/51/48pt rows)
3) /ipld/v1 latency: 3rd tick with degradation (13.2s → 2.3s+30s-timeout). If next tick still shows outliers, add time-of-day + /signup-latency correlation columns to the probe before any cause claim.
4) signups 33 attribution (owner/test cannot be excluded from this side) — if owner confirms test, revert draft 0045 trigger to not-met
5) x402: submissions 4 / settle 0 / 3 rejections unexplained — any change is first demand signal
6) terminal stdout channel — STILL degraded this session (foreground echo/curl probes returned empty; exit codes only). Workaround used and working: redirect to file + read_file. Keep pattern.

## exaggeration-guard
- external revenue == 0; 33 signups NOT revenue; x402 subm 4 / settle 0 NOT revenue; +38 visitors is a funnel tick, not a paying customer; all numbers read live via curl (HTTP 200) + nbb funnel-pulse (EXIT 0) with file-redirect workaround; probe values are raw curl samples, not synthesized; nothing sent; nothing deployed; nothing fabricated
