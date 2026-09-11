# Run 0181 - kotobase-stab loop - 2026-09-10 JST (~20:04 tick; 11:04 UTC)

## Measured (live; two independent readings this tick)
- nbb funnel-pulse (workdir com-junkawasaki, EXIT=0): SCANNED 1; VISITORS 11896; SIGNUPS 33; CHECKOUTS 0; DELTA {:visitors 0, :signups 0, :checkouts 0}; UNCHANGED true; EXTERNAL-FUNNEL-CHANGE false; SCORE unchanged; RESULT unchanged
- curl /api/funnel HTTP 200: visitors 11896; signups 33; checkouts 0 — readings agree exactly
- cross-run delta vs run 0180 (2026-09-10 ~02:01; visitors 11759 / signups 31): **+137 visitors / +2 signups / +0 checkouts**
- NOTE on DELTA=0: pulse DELTA/UNCHANGED is vs the script's own last-scan state. The 19:21 broken tick (runs/2026-09-10-stab.md) ran nbb with exit 0 but lost stdout — its scan likely absorbed the delta into state. Cross-run comparison above is from recorded run files, not synthesized.
- **SIGNUPS 33 > 31 — first signups movement since 2026-09-03 (run 0045; was pinned 31 from visitors 4870 through run 0180 at visitors 11759).** Trigger condition from run 0180 next-verification #2 ("signups > 31") is **MET**.
- by-source visitors: organic 10318 + openai-ads 3 = 10321 vs total 11896: gap 1575 (flat vs run 0180's 1575; data-quality observe only)
- signups by-source: organic 29 (was 27, +2) + other 1 = 30 of 33 accounted; 3 unexplained (same count as 0180). This bot did NOT POST /signup (page GETs only) — attribution limited to API by-source fields; cannot rule out owner/test signups from here.
- x402 (all flat vs run 0180): challenges 40; submissions 4; rejections 4 (1 malformed-header classified, 3 unexplained); settlements 0; settlement-rate 0; attempt-rate 0.1; unmetered-twin-ratio 0; unpriced-plane-reads 0 — no x402 demand signal.

## Stability check
- `/`            200 (0.070s) — expected
- `/signup`      200 (0.498s) — expected; slower than run 0180's 0.063s, observe only
- `/api/funnel`  200
- `/ipld/v1`     400 (13.2s) = expected status, **but slow — 2nd slow occurrence in 3 ticks** (run 0179 one-off, 0180 normal 0.124s). Route exists; not down. Cause estimate (1 line, unconfirmed): cold start of the /ipld route or cold read against the graph store — needs recurrence check, not an incident claim.
- `/ipld/`       404 (expected; verified this run)
- All five route expectations hold; no 5xx; no endpoint down this tick.

## Score->Select (WIP=1)
- selected   -> UNCHANGED — counsel written-advice return on net-kotobase legal packet; sole remaining gate on the 75pt row (cloud-itonami paid pilot to external tenants) and transitively the 65/62/61/51/48pt red rows (SCORECARD.md, observed 2026-08-14)
- selected-action score -> 58/100 (unchanged; 33 signups are not revenue; checkouts 0; settlements 0)
- NEW this tick -> **standby draft 0045 trigger condition MET** (signups 33 > 31): `runs/0045-cron-signup-activation-draft.edn` (/signup activation variant: price anchor "Secure Managed ¥19,800/mo" — only live-priced SKU per run 0033 — plus one primary CTA near signup form) is surfaced for **owner go/no-go**. NOT implemented, NOT sent, NOT deployed by this bot. WIP=1 held; the counsel-gate selection is unchanged.

## Bounded experiment (executed this tick)
- verification executed: re-measured funnel via two independent paths (nbb pulse EXIT=0 + curl HTTP 200) after the 19:21 stdout-degraded tick; readings agree exactly; signups 31→33 confirmed from recorded-run baseline — the run-0180 draft's trigger check is now resolved to MET.
- outbound: none. draft 0045 stays DRAFT / NOT SENT. recipient for review: kotobase.net operator (owner); deploy/activation = owner decision.

## Decision -> HOLD
- no spend; no self-purchase; no paid acquisition; no outbound send; no deploy; revenue = 0 measured (33 signups NOT revenue; 0 checkout; 0 x402 settlement); selected score unchanged 58/100; canvas-ledger-touched? false

## Next verification
1) **owner go/no-go on draft 0045** — trigger now met; do not implement/send without approval
2) council written reply (blocker since run 0038; unblocks 75/65/62/61/51/48pt rows)
3) /ipld/v1 slow-response recurrence (13.2s this tick; 2nd in 3 ticks) — if it repeats next tick, record pattern (times-of-day, payload) before any cause claim
4) attribution of +2 signups (organic per API by-source; owner/test signups cannot be excluded from this side) — if owner confirms test, revert trigger to not-met
5) x402: submissions 4 / settle 0 / 3 rejections unexplained — any change is first demand signal
6) terminal stdout channel — STILL degraded (foreground `/bin/echo` probe returned empty again this tick). Workaround used and working: redirect to profile-dir file + read_file. Keep pattern.

## exaggeration-guard
- external revenue == 0; 33 signups NOT revenue; x402 subm 4 / settle 0 NOT revenue; +2 signups is a funnel tick, not a paying customer; all numbers read live via curl (HTTP 200) + nbb funnel-pulse (EXIT 0) with file-redirect workaround; not synthesized; nothing sent; nothing deployed; nothing fabricated
