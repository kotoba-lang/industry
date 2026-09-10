# Run 0133 - cron stability + ninety-sixth consecutive signup read (2026-09-08 JST)

Role: kotobase-stab stabilization loop, 1 iteration. canvas-ledger.edn NOT touched.
Measured live 2026-09-08 10:31 JST (nbb funnel-pulse + /api/funnel HTTP 200 same window).

Env note: bare terminal stdout empty again this session (same degradation as runs
0121-0132); commands exit 0 and files written by those commands were read back via
read_file, so all signals below are real captured values, not synthesized.
/api/funnel returned HTTP 200 body, so live network confirmed - NOT a not-measured run.

## Measured
Signal funnel-pulse (nbb): EXIT=0. VISITORS=10022 SIGNUPS=31 CHECKOUTS=0.
DELTA {:visitors 2 :signups 0 :checkouts 0} UNCHANGED=false
EXTERNAL-FUNNEL-CHANGE=false SCORE=unchanged RESULT=recorded.

/api/funnel (curl): HTTP 200 (0.059s). visitors 10021 / signups 31 / checkouts 0.
by-source visitors organic 8439, openai-ads 3. signups organic 27, other 1.
checkouts {}. x402 challenges 38, submissions 4, settlement-rate 0,
settlements 0, rejections 4 (1 classified malformed-header, 3 unexplained),
attempt-rate 0.105.

Cross-run vs 0132 (9988/9984): visitors +33..34, signups 31 static, checkouts 0.
organic 8402 -> 8439. openai-ads flat 3. x402 numbers identical to 0132
(challenges 38 / submissions 4 / settlements 0 / unexplained 3).
**96th consecutive read: signups pinned at 31 while reach keeps rising (~10,022) =
CONVERSION/OFFER blocker at /signup, not reach. Paid acquisition still negligible
(openai-ads 3 of ~10,021).**

## Stability
| Endpoint | Status | Note |
|---|---|---|
| /api/funnel | 200 | body-fetch confirmed, 0.059s |
| / | 200 | 0.071s |
| /signup | 200 | 0.048s |
| /ipld/v1 | 400 | expected = route exists, params required. 0.057s |
| /ipld/ | 404 | expected (baseline 2026-09-03) |

all-healthy? true. No 5xx. All latencies sub-100ms this window; the elevated
/ipld/v1 paths from prior runs (25.9s / 2.283s / 0.222s) not observed now.

## Score - Select
Selected (unchanged): counsel written-advice return on the net-kotobase legal packet -
the sole remaining gate on the 75pt SCORECARD row (cloud paid pilot to external tenants)
and transitively the lower-ranked rows. NOT SENT. Draft on disk
(90-docs/business/net-kotobase/counsel-followup-draft-20260903.md, confirmed present this
run) plus carried standby draft runs/0045-cron-signup-activation-draft.edn (confirmed
present, 3673 bytes).

Reason: SCORECARD ranking (Measured 2026-08-14) unchanged - all top rows counsel-gated
per run 0021 gate rewrite. No counsel return observed through run 0133. 96th read with
signups static (31) while reach rises (~10,022) = offer/conversion blocker, consistent
with the carried activation draft being the lever. Selection holds.

Selected-action score: 58/100 (unchanged; 0 checkout, 0 payment, 0 settlement, x402
submissions 4 / settlements 0 / unexplained rejections 3). Mode cash-first 60/40.
Verified external revenue = 0.

## Bounded experiment (standby only, NOT sent/deployed)
Carried from run 0046/0045. Not executed by bot (no-deploy boundary). Draft on disk
(runs/0045-cron-signup-activation-draft.edn, confirmed present this run).
What (unchanged): /signup experience activation variant - present the actual price
anchor (Secure Managed JPY 19,800/mo, only live-priced SKU per run 0033) plus one
primary CTA next to the signup form. Zero change to pricing/checkout/legal text
(commercial go stays no-go).
Expected signal: signups counter moves above 31 and/or checkout-start > 0 in a
post-variant window. Do not induce a purchase. status standby.
recipient: owner (kotobase.net operator). internal draft only; deploy = owner decision
outside this bot's no-deploy boundary.
sent?: false. deployed?: false.

## Decision
- Hold. No spend / self-purchase / paid acquisition. Revenue = 0 measured (96th).
- Next verified: external non-owner payment before any acquisition spend.
- Score unchanged 58/100. Nothing sent, nothing deployed, no fabricated numbers.

## Next verification
1. Counsel reply (unchanged blocker) - would re-open 75/65/62/61/51/48 rows.
2. signup conversion drift - owner-side UA/referrer split (openai-ads still 3 of ~10,021
   = negligible paid acquisition).
3. x402: any movement from submissions 4 / settlements 0 / unexplained 3 = 1st demand
   signal (identical to 0132 this window - no movement).
4. /ipld/v1 latency - sub-100ms this run; watch for return of run 0125's 25.9s slow path.

canvas-ledger-touched?: false. score-raised?: false (no new external evidence).

obvious-guard:
verified external revenue = 0 (0 checkout, 0 settlement). 31 signups are NOT revenue.
x402 submissions 4 / settlements 0 NOT revenue cause unverified. Reasonable values all
read live via funnel-pulse (exit 0) + /api/funnel HTTP 200 same window; nbb/curl output
was captured to files and read back - real, not synthesized.