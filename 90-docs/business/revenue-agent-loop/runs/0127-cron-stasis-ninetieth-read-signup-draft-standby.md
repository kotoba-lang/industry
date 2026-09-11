# Run 0127 - cron stability + ninetieth consecutive signup read (2026-09-08 JST)

Role: kotobase-stab stabilization loop, 1 iteration. canvas-ledger.edn NOT touched.
Measured live 2026-09-08 07:30 JST (nbb funnel-pulse recorded to pulse ledger;
/api/funnel HTTP 200 in the same window). Env note: bare terminal stdout empty again this
session (same degradation as runs 0125/0126/0124/0123/0122/0121/0119/0118/0037); commands
execute with exit 0 and signals captured via file-redirect (curl/nbb wrote to files, read
back). /api/funnel returned HTTP 200 so live network confirmed - NOT a not-measured run.

## Measured

| Signal | Value |
|---|---|
| funnel-pulse (kbb --backend sci kotobase_lead_loop.cljk, 07:30) | EXIT=0. VISITORS=9821 SIGNUPS=31 CHECKOUTS=0. DELTA {:visitors 1 :signups 0 :checkouts 0}. UNCHANGED=false (visitors moved +1) EXTERNAL-FUNNEL-CHANGE=false SCORE=unchanged RESULT=recorded. |
| /api/funnel (curl, same window) | HTTP 200, 0.060s. visitors 9821 / signups 31 / checkouts 0. by-source visitors organic 8237 / openai-ads 3, signups organic 27 / other 1. x402 challenges 38 / submissions 4 / settlement-rate 0 / settlements 0 / rejections 4 (1 classified malformed-header, 3 unexplained) / attempt-rate 0.105. |

Window drift: nbb 9822 vs curl 9821 (1 apart within window). vs run 0126 pulse (9773):
visitors ~+48; signups still 31, checkouts still 0. by-source organic grew 8188 -> 8237.
openai-ads flat at 3. by-source sum 8237 + 3 = 8240 of ~9821 (1,581 visitors unattributed).

**90th consecutive read with signups static at 31 while reach keeps rising** = sustained
CONVERSION/OFFER blocker at /signup, not reach. Paid acquisition remains negligible either.

## Stability checks (all healthy)

| Endpoint | Status | Note |
|---|---|---|
| /api/funnel | 200 | 0.060s body-fetch confirmed |
| / | 200 | 0.087s |
| /signup | 200 | 0.110s |
| /ipld/v1 | 400 | expected = route exists, params required. Latency 0.358s this run (run 0125's 25.9s was transient; 0126 0.17s - normal) |
| /ipld/ | 404 | expected (baseline 2026-09-03) |

all-healthy? true

### WIP (carried, unchanged)

- next-form: WIP=beekle (https://beekle.jp/contact). beekle-status :blocked (Turnstile,
  human Chrome required). DO-NOT-SEND=true. RESULT=beekle-unsent (carried).
- WIP count = 1 (unsatisfied).

## Score -> Select

- **Selected:** UNCHANGED: counsel written-advice return on the net-kotobase legal packet -
  the sole remaining gate on the 75pt row (cloud-itonami paid pilot to 5 external tenants)
  and transitively the 65/62/61/51/48 rows. Draft on disk
  (90-docs/business/net-kotobase/counsel-followup-draft-20260903.md, confirmed present this
  run), NOT SENT.
- **Reason:** SCORECARD ranking unchanged - all top rows counsel-gated per run 0021 gate
  rewrite. No counsel return observed through run 0127. #90 consecutive read with signups
  pinned at 31 while visitors keep rising (9821; +48 vs run 0126 pulse 9773) - a
  CONVERSION/OFFER blocker at /signup, not a reach problem. SCORECARD instructs re-scoring
  the 75pt row when counsel returns; nothing returned, selection holds.
- Selected-action score: **58/100** (unchanged prior; no new external evidence: 0 checkout,
  0 payment, 0 settlement; x402 static at 4/0). Mode cash-first (60/40) external revenue = 0.

## Experiment (standby-carry, NOT sent/deployed)

- carried-from: run 0045 (no owner decision observed through run 0127).
- status: standby.
- trigger: signups static at 31 across ninety consecutive reads while visitors keep rising
  (9821). Conversion, not reach, is the gap; the blocker is the /signup offer.
- what: /signup activation variant - add the actual price anchor (Secure Managed
  JPY 19,800/mo, only live-priced SKU per run 0033) plus one primary CTA next to the signup
  form. Zero change to pricing, checkout, or legal text (commercial no-go intact).
- expected-signal: signups counter above 31, and/or checkout-start > 0, in a post-variant
  window.
- draft: signup-activation-variant proposal carried from run 0045
  (runs/0045-cron-signup-activation-draft.edn, confirmed present this run).
- recipient: owner (kotobase.net operator). Internal draft only - no write/send/deploy by bot.
- deploy-owner: owner decision required - live worker change, outside this bot's no-deploy
  boundary.
- sent?: false. deployed?: false.

## Next verification

1. Counsel reply (unchanged blocker); would re-open the 75/65/62/61/51/48 rows.
2. signup conversion drift - owner-side UA/referrer split (openai-ads still 3 of 9821 =
   negligible paid acquisition).
3. x402: any movement from submissions 4 / settlements 0 / unexplained 3 = 1st demand signal.
4. /ipld/v1 stability - healthy at 0.358s this run (no return of run 0125's 25.9s slow path).

score-raised?: false. canvas-ledger-touched?: false. Selected-action score 58/100 unchanged.
Nothing sent, nothing deployed, no fabricated numbers.

obvious-guard:
verified external revenue = 0 (no checkout, no permanent settlement, x402 settlement 0).
31 signups are NOT revenue/demand. 4 x402 submissions / 0 settlements is NOT revenue.
Visitors 9821 (pulse 9822) window - traffic only, cause unverified, ~1,581 of 9821
unattributed by source. All deltas read live from funnel-pulse (exit 0) + /api/funnel HTTP 200
same window. Signals were file-redirect - real, not synthesized.