# Run 0128 - cron stability + ninety-first consecutive signup read (2026-09-08 JST)

Role: kotobase-stab stabilization loop, 1 iteration. canvas-ledger.edn NOT touched.
Measured live 2026-09-08 (nbb funnel-pulse + /api/funnel HTTP 200 in same window).

## Measured
Signal funnel-pulse (nbb): EXIT=0. VISITORS=9856 SIGNUPS=31 CHECKOUTS=0.
DELTA {:visitors 5 :signups 0 :checkouts 0} UNCHANGED=false
EXTERNAL-FUNNEL-CHANGE=false SCORE=unchanged RESULT=recorded.

/api/funnel (curl): HTTP 200 0.053s. visitors 9856 / signups 31 / checkouts 0.
by-source visitors organic 8270, openai-ads 3. signups organic 27, other 1.
x402 challenges 38, submissions 4, settlement 0, rejections 4 (1 malformed-header,
3 unexplained), attempt-rate 0.105.

Window drift vs run 0127 pulse (9821): visitors +35; signups 31 static; checkouts 0.
organic 8237 -> 8270. openai-ads flat 3. by-source sum 8270+3=8273 of ~9856.
91st consecutive read: signups pinned at 31 while reach rises = conversion/offer
blocker at /signup, not reach. Paid acquisition negligible.

## Stability
/api/funnel 200. / 200 0.134s. /signup 200 0.048s.
/ipld/v1 400 (expected, params required) 0.067s. /ipld/ 404 (expected baseline 09-03).
all healthy, no 5xx.

## Score - Select
Selected (unchanged): counsel written-advice return on the net-kotobase packet - the
sole remaining gate on the 75pt SCORECARD row (cloud paid pilot to external tenants)
and transitively the lower ranked rows. NOT SENT.
Reason: scorecard ranking unchanged; top rows counsel-gated per run 0021 gate rewrite.
No counsel return observed. 91st read signups 31 static while reach 9856 rises.
Selected-action score 58/100 (unchanged; 0 checkout 0 payment 0 settlement, x402 4/0).
Mode cash-first 60/40; verified external revenue 0.

## Bounded experiment (standby only, NOT sent/deployed)
Carried from run 0045. Not executed by bot (no-deploy boundary). Draft on disk in
runs/0045. Expected signal: signups move above 31 and/or checkout-start gt 0 in a
post-variant window. Do not induce a purchase. status standby.

## Decision
- Hold. Do not spend / self-purchase / paid acquisition. Revenue = 0 measured.
- Next verified: external non-owner payment before any acquisition spend.
Score unchanged 58/100. Nothing sent, nothing deployed.
obvious-guard: revenue = 0 measured; 31 signups NOT revenue; x402 4/0 NOT revenue;
visitors 9856 window traffic only. All values read live from funnel-pulse + /api/funnel.