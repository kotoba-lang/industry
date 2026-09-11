# Run 0117 - cron stability + eightieth consecutive signup read (2026-09-08 JST)

Role: kotobase-stab stabilization loop, 1 iteration. canvas-ledger.edn NOT touched.
Measured live 2026-09-08 02:35 JST (funnel-pulse + /api/funnel same window).
Env note: bare terminal stdout was empty again this session (commands executed, exit 0) —
signals captured via file-redirect (curl/nbb wrote to files, read back). /api/funnel
returned HTTP 200 so live network confirmed; NOT a not-measured run.

## Measured

| Signal | Value |
|---|---|
| funnel-pulse (kbb --backend sci kotobase_lead_loop.cljk) | EXIT=0. VISITORS=9585 SIGNUPS=31 CHECKOUTS=0. DELTA {:visitors 2 :signups 0 :checkouts 0}. UNCHANGED=false EXTERNAL-FUNNEL-CHANGE=false SCORE=unchanged RESULT=recorded. |
| /api/funnel | HTTP 200 (0.049s). {:visitors 9585 :signups 31 :checkouts 0 :by-source {:visitors {:openai-ads 3 :organic 8005} :signups {:organic 27 :other 1}} :x402 {:challenges 38 :submissions 4 :rejections 4 :settlements 0 :settlement-rate 0 :rejection-reasons {:malformed-header 1} :rejections-classified 1 :rejections-unexplained 3 :unmetered-twin-ratio 0 :unmetered-twin-reads 0 :unpriced-plane-reads 0 :attempt-rate 0.10526315789473684}} |

This run: pulse and /api/funnel agree (9585/31/0). DELTA visitors +2 vs pulse's own last read;
vs run 0116 (02:20, 9575/31/0) visitors +10. Signups pinned at 31.

Visitor drift vs run 0116 (9575 pulse / 9575 api): now 9585 → ~+10 visitors with signups still
pinned at 31. Reach rising, conversion flat = offer/conversion blocker persists at /signup.
by-source: organic visitors 8005 (was 7996), openai-ads unchanged 3 — paid acquisition still
negligible.

### Stability checks (all healthy)

| Endpoint | Status | Note |
|---|---|---|
| / | 200 | healthy, 0.063s |
| /signup | 200 | healthy, 0.046s |
| /ipld/v1 | 400 | expected = route exists, params required (baseline 2026-09-03) |
| /ipld/ | 404 | expected = bare route, params required (baseline 2026-09-03) |

all-healthy? true

### WIP (carried, unchanged)

- next-form: WIP=beekle (https://beekle.jp/contact). beekle-status :blocked (Turnstile,
  human Chrome required). DO-NOT-SEND=true. RESULT=beekle-unsent (carried).
- WIP count = 1 (unsatisfied).

## Score -> Select

- **Selected:** UNCHANGED: counsel written-advice return on the net-kotobase legal packet —
  the sole remaining gate on the 75pt row (cloud-itonami 6399/6310 paid pilot to 5 external
  tenants) and transitively the 65/62/61/51/48 red rows. Draft on disk
  (90-docs/business/net-kotobase/counsel-followup-draft-20260903.md, verified present), NOT SENT.
- **Reason:** SCORECARD ranking unchanged — top rows blocked, all counsel-gated per run 0021
  gate rewrite. No counsel return observed since run 0038. This = 80th consecutive read with
  signups pinned at 31 while visitors keep rising (9585; +10 vs run 0116) — a
  CONVERSION/OFFER blocker at /signup, not a reach problem. SCORECARD instructs re-scoring the
  75pt row when counsel returns; nothing returned, selection holds.
- Selected-action score: **58/100** (unchanged prior; no new external evidence: 0 checkout,
  0 payment, 0 settlement).

## Experiment (standby-carry, NOT sent/deployed)

- carried-from: run 0045 (no owner decision observed through run 0117).
- status: standby.
- trigger: signups static at 31 across eighty consecutive reads while visitors keep rising
  (to 9585). Conversion, not reach, is the gap; blocker is the /signup offer.
  Same trigger as runs 0045-0116.
- what: /signup activation variant: add the actual price anchor (Secure Managed JPY 19,800/mo
  — only live-priced SKU per run 0033) plus one primary CTA next to the signup form. Zero
  change to pricing, checkout, or legal text (no-go intact).
- expected-signal: signups counter above 31, and/or checkout-start > 0, in a post-variant
  window.
- draft: signup-activation-variant proposal carried from run 0045.
- recipient: owner (kotobase.net operator). Internal draft only — no write/send/deploy by bot.
- deploy-owner: owner decision required — live worker change, outside this bot's no-deploy
  boundary.
- sent?: false. deployed?: false.

## Next verification

1. Counsel reply (unchanged blocker since run 0038; would re-open the 75/65/62/61/51/48 rows).
2. Visitor/signup conversion drift — owner-side UA/referrer split (API only exposes
   organic/openai-ads; openai-ads still 3 of 9585 = negligible paid acquisition).
3. x402: any movement from submissions 4 / settlements 0 / unexplained 3 = first demand
   signal.

canvas-ledger-touched?: false. score-raised?: false.

exaggeration-guard:
Verified external revenue = 0 (Stripe active-subscriptions 0 prior metric, not re-read this
run). 31 signups are NOT revenue/demand. 4 x402 submissions / 0 settlements is NOT revenue.
Visitors 9585 (+10 vs run 0116's 9575) is traffic, not conversion; cause unverified (no source
split beyond organic/openai-ads). All deltas read from live counters (nbb funnel-pulse +
/api/funnel, both exit 0/200 same window). Nothing sent, nothing deployed, nothing fabricated.
All stability checks healthy (200/200/400/404 expected). Env: file-redirect capture — signals
are real, not synthesized.
