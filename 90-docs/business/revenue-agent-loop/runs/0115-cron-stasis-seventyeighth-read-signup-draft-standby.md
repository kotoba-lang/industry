# Run 0115 - cron stability + seventy-eighth consecutive signup read (2026-09-08 JST)

Role: kotobase-stab stabilization loop, 1 iteration. canvas-ledger.edn NOT touched.
Measured live 2026-09-08 ~01:35 JST (funnel-pulse + /api/funnel same window).
Env note: bare terminal stdout was empty this session (commands executed, exit 0) —
signals captured via file-redirect (curl/nbb wrote to files, read back). /api/funnel
returned HTTP 200 so live network confirmed; NOT a not-measured run.

## Measured

| Signal | Value |
|---|---|
| funnel-pulse (nbb kotobase_lead_loop.cljs) | EXIT=0. VISITORS=9558 SIGNUPS=31 CHECKOUTS=0. DELTA {:visitors 1 :signups 0 :checkouts 0}. UNCHANGED=false EXTERNAL-FUNNEL-CHANGE=false SCORE=unchanged RESULT=recorded. |
| /api/funnel | HTTP 200 (0.48s). {:visitors 9559 :signups 31 :checkouts 0 :by-source {:visitors {:openai-ads 3 :organic 7980} :signups {:organic 27 :other 1}} :x402 {:challenges 38 :submissions 4 :rejections 4 :settlements 0 :settlement-rate 0 :rejection-reasons {:malformed-header 1} :rejections-classified 1 :rejections-unexplained 3 :unmetered-twin-ratio 0 :unmetered-twin-reads 0 :unpriced-plane-reads 0 :attempt-rate 0.10526315789473684}} |

Note: pulse (9558) and /api/funnel (9559) differ by 1 — snapshot timing within the same
window (pulse ran first). Not contradictory.

### Stability checks (all healthy)

| Endpoint | Status | Note |
|---|---|---|
| / | 200 | healthy, 0.107s |
| /signup | 200 | healthy, 0.161s |
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
  (90-docs/business/net-kotobase/counsel-followup-draft-20260903.md), NOT SENT.
- **Reason:** SCORECARD ranking unchanged — top rows blocked, all counsel-gated per run 0021
  gate rewrite. No counsel return observed since run 0038. This = 78th consecutive read with
  signups pinned at 31 while visitors keep rising (9558/9559; ~+24 vs run 0114's 9534/9535 at
  00:45) — a CONVERSION/OFFER blocker at /signup, not a reach problem. SCORECARD instructs
  re-scoring the 75pt row when counsel returns; nothing returned, selection holds.
- Selected-action score: **58/100** (unchanged prior; no new external evidence: 0 checkout,
  0 payment, 0 settlement).

## Experiment (standby-carry, NOT sent/deployed)

- carried-from: run 0045 (no owner decision observed through run 0115).
- status: standby.
- trigger: signups static at 31 across seventy-eight consecutive reads while visitors keep
  rising (to 9558/9559). Conversion, not reach, is the gap; blocker is the /signup offer.
  Same trigger as runs 0045-0114.
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
   organic/openai-ads; openai-ads still 3 of 9559 = negligible paid acquisition).
3. x402: any movement from submissions 4 / settlements 0 / unexplained 3 = first demand
   signal.

canvas-ledger-touched?: false. score-raised?: false.

exaggeration-guard:
Verified external revenue = 0 (Stripe active-subscriptions 0 prior metric, not re-read this
run). 31 signups are NOT revenue/demand. 4 x402 submissions / 0 settlements is NOT revenue.
Visitors 9558/9559 (+~24 vs run 0114's 9534/9535) is traffic, not conversion; cause unverified
(no source split beyond organic/openai-ads). All deltas read from live counters (nbb
funnel-pulse + /api/funnel, both exit 0/200 same window). Nothing sent, nothing deployed,
nothing fabricated. All stability checks healthy (200/200/400/404 expected). Env: file-redirect
capture — signals are real, not synthesized.