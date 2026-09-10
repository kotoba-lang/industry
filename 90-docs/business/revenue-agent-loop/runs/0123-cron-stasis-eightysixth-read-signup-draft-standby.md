# Run 0123 - cron stability + eighty-sixth consecutive signup read (2026-09-08 JST)

Role: kotobase-stab stabilization loop, 1 iteration. canvas-ledger.edn NOT touched.
Measured live 2026-09-08 05:32 JST (nbb funnel-pulse, recorded to pulse ledger;
/api/funnel HTTP 200 same window). Env note: bare terminal stdout is empty again
this session (same degradation tracked in runs 0122/0121/0119/0118/0037); commands
execute with exit 0 and signals were captured via file-redirect (curl/nbb wrote to
files, read back). /api/funnel returned HTTP 200 so live network confirmed - NOT a
not-measured run.

## Measured

| Signal | Value |
|---|---|
| funnel-pulse (nbb kotobase_lead_loop.cljs) | EXIT=0. VISITORS=9692 SIGNUPS=31 CHECKOUTS=0. DELTA {:visitors 1 :signups 0 :checkouts 0}. UNCHANGED=false EXTERNAL-FUNNEL-CHANGE=false SCORE=unchanged RESULT=recorded. Pulse ledger appended (as-of 2026-09-08T05:32:01+09:00). |
| /api/funnel (curl, 05:32:01) | HTTP 200. visitors 9692 / signups 31 / checkouts 0. by-source visitors organic 8110 / openai-ads 3, signups organic 27 / other 1, x402 challenges 38 / submissions 4 / settlement-rate 0 / settlements 0 / unexplained 3. |

This run: visitors 9692, signups pinned at 31, checkouts 0. Visitor drift vs run 0122
(curl window 9678) = +14 - signups still 31, checkouts still 0.

**86th consecutive read with signups static at 31 while reach rises** = sustained
CONVERSION/OFFER blocker at /signup, not reach. by-source sums 8110 organic + 3
openai-ads = 8113 of 9692 (1,579 visitors not attributed by source); paid
acquisition remains negligible either way.

### Stability checks (all healthy)

| Endpoint | Status | Note |
|---|---|---|
| / | 200 | healthy, 0.194s |
| /signup | 200 | healthy, 0.146s |
| /ipld/v1 | 400 | expected = route exists, params required (baseline 2026-09-03) |

all-healthy? true

### WIP (carried, unchanged)

- next-form: WIP=beekle (https://beekle.jp/contact). beekle-status :blocked (Turnstile,
  human Chrome required). DO-NOT-SEND=true. RESULT=beekle-unsent (carried).
- WIP count = 1 (unsatisfied).

## Score -> Select

- **Selected:** UNCHANGED: counsel written-advice return on the net-kotobase legal packet -
  the sole remaining gate on the 75pt row (cloud-itonami clusters paid pilot to 5 external
  tenants) and transitively the 65/62/61/51/48 rows. Draft on disk
  (90-docs/business/net-kotobase/counsel-followup-draft-20260903.md, confirmed present 2026-09-08,
  1193 bytes), NOT SENT.
- **Reason:** SCORECARD ranking unchanged - top rows blocked, all counsel-gated per run 0021
  gate rewrite. No counsel return observed through run 0122. This = 86th consecutive read with
  signups pinned at 31 while visitors keep rising (9692; +14 vs run 0122) - a
  CONVERSION/OFFER blocker at /signup, not a reach problem. SCORECARD instructs re-scoring the
  75pt row when counsel returns; nothing returned, selection holds.
- Selected-action score: **58/100** (unchanged prior; no new external evidence: 0 checkout,
  0 payment, 0 settlement). Mode still cash-first (60/40), external revenue = 0.

## Experiment (standby-carry, NOT sent/deployed)

- carried-from: run 0045 (no owner decision observed through run 0123).
- status: standby.
- trigger: signups static at 31 across eighty-six consecutive reads while visitors keep
  rising (to 9692). Conversion, not reach, is the gap; the blocker is the /signup offer.
- what: /signup activation variant: add the actual price anchor (Secure Managed JPY 19,800/mo
  - only live-priced SKU per run 0033) plus one primary CTA next to the signup form. Zero
  change to pricing, checkout, or legal text (commercial no-go intact).
- expected-signal: signups counter above 31, and/or checkout-start > 0, in a post-variant
  window.
- draft: signup-activation-variant proposal carried from run 0045.
- recipient: owner (kotobase.net operator). Internal draft only - no write/send/deploy by bot.
- deploy-owner: owner decision required - live worker change, outside this bot's no-deploy
  boundary.
- sent?: false. deployed?: false.

## Next verification

1. Counsel reply (unchanged blocker); would re-open the 75/65/62/61/51/48 rows.
2. Visitor/signup conversion drift - owner-side UA/referrer split (API only exposes
   organic/openai-ads; openai-ads still 3 of 9692 = negligible paid acquisition).
3. x402: any movement from submissions 4 / settlements 0 / unexplained 3 = first demand
   signal.

canvas-ledger-touched?: false. score-raised?: false.

exaggeration-guard:
Verified external revenue = 0 (Stripe active-subscriptions 0 prior metric, not re-read this
run). 31 signups are NOT revenue/demand. 4 x402 submissions / 0 settlements is NOT revenue.
Visitors 9692 (pulse and curl window agree) is traffic, not conversion; cause unverified, 1,579
of 9,692 visitors unattributed by source. All deltas read from live funnel-pulse (exit 0, pulse
ledger append confirmed) + /api/funnel HTTP 200 same window. Nothing sent, nothing deployed,
nothing fabricated. All stability checks healthy (200/200/400 expected). Env: file-redirect
capture - signals are real, not synthesized.