# Run 0130 - cron stability + ninety-third consecutive signup read (2026-09-08 JST)

Role: kotobase-stab stabilization loop, 1 iteration. canvas-ledger.edn NOT touched.
Measured live 2026-09-08 (nbb funnel-pulse + /api/funnel HTTP 200 in same window).

Env note: bare terminal stdout empty again this session (same degradation as runs
0121-0129); commands exit 0 and signals were captured via file-redirect (curl/nbb wrote
to files, read back). /api/funnel returned HTTP 200 body, so live network confirmed -
NOT a not-measured run.

## Measured
Signal funnel-pulse (nbb): EXIT=0. VISITORS=9931 SIGNUPS=31 CHECKOUTS=0.
DELTA {:visitors 2 :signups 0 :checkouts 0} UNCHANGED=false
EXTERNAL-FUNNEL-CHANGE=false SCORE=unchanged RESULT=recorded.

/api/funnel (curl): HTTP 200. visitors 9930 / signups 31 / checkouts 0.
by-source visitors organic 8343, openai-ads 3. signups organic 27, other 1.
checkouts {}. x402 challenges 38, submissions 4, settlement-rate 0,
settlements 0, rejections 4 (1 classified malformed-header, 3 unexplained),
attempt-rate 0.105.

Window drift vs run 0129 pulse (9889): visitors +42/46; signups 31 static; checkouts 0.
organic 8297 -> 8343. openai-ads flat 3. by-source sum 8343+3=8346 ~ of 9930
(~1,584 unattributed). **93rd consecutive read: signups pinned at 31 while reach keeps
rising = CONVERSION/OFFER blocker at /signup, not reach. Paid acquisition negligible.**

## Stability
| Endpoint | Status | Note |
|---|---|---|
| /api/funnel | 200 | body-fetch confirmed |
| / | 200 | 0.260s |
| /signup | 200 | 0.082s |
| /ipld/v1 | 400 | expected = route exists, params required. 0.222s |
| /ipld/ | 404 | expected (baseline 2026-09-03) |

all-healthy? true. No 5xx, no /ipld/v1 slow-path regression.

## Score - Select
Selected (unchanged): counsel written-advice return on the net-kotobase legal packet -
the sole remaining gate on the 75pt SCORECARD row (cloud paid pilot to external tenants)
and transitively the lower ranked rows. NOT SENT. Draft on disk
(90-docs/business/net-kotobase/counsel-followup-draft-20260903.md, confirmed present this
run) plus carried standby draft runs/0045-cron-signup-activation-draft.edn (present).

Reason: SCORECARD ranking (Measured 2026-08-14) unchanged - all top rows counsel-gated
per run 0021 gate rewrite. No counsel return observed through run 0130. 93rd read with
signups static (31) while reach rises (~9,931) = offer/conversion blocker, consistent
with the carried activation draft being the lever. Selection holds.

Selected-action score: 58/100 (unchanged; 0 checkout, 0 payment, 0 settlement, x402
submissions 4 / settlements 0 / unexplained rejections 3). Mode cash-first 60/40.
Verified external revenue = 0.

## Bounded experiment (standby only, NOT sent/deployed)
Carried from run 0046/0045. Not executed by bot (no-deploy boundary). Draft on disk
(runs/0045-cron-signup-activation-draft.edn, confirmed present).
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
- Hold. No spend / self-purchase / paid acquisition. Revenue = 0 measured (93rd).
- Next verified: external non-owner payment before any acquisition spend.
- Score unchanged 58/100. Nothing sent, nothing deployed, no fabricated numbers.

## Next verification
1. Counsel reply (unchanged blocker) - would re-open 75/65/62/61/51/48 rows.
2. signup conversion drift - owner-side UA/referrer split (openai-ads still 3 of ~9,931
   = negligible paid acquisition).
3. x402: any movement from submissions 4 / settlements 0 / unexplained 3 = 1st demand
   signal.
4. /ipld/v1 stability - healthy 0.222s (no return of run 0125's 25.9s path).

canvas-ledger-touched?: false. score-raised?: false (no new external evidence).

obvious-guard:
verified external revenue = 0 (0 checkout, 0 settlement). 31 signups are NOT revenue.
x402 submissions 4 / settlements 0 NOT revenue cause unverified. Reasonable values all
read live via funnel-pulse (exit 0) + /api/funnel HTTP 200 same window; output captured
via file-redirect (/tmp/kb_funnel.txt, /tmp/kb_http.txt) and read back - real, not
synthesized.