# Run 0147 - cron stability + hundred-and-tenth consecutive signup read (2026-09-08 JST)

Role: kotobase-stab stabilization loop, 1 iteration. canvas-ledger.edn NOT touched
by this run (single-writer routine, per SOUL rules).

Env note: terminal stdout channel is empty this session (same degradation as runs
0121-0146). Recovery pattern followed: signals captured via curl -o <file> and
nbb output-to-file, read back through read_file (separate healthy channel). Both
live network channels reached; this is NOT a not-measured run. nbb funnel-pulse
completed exit 0 and returned RESULT `recorded`.

## Measured (live, this run)

/api/funnel (curl, HTTP 200, JSON body):
- funnel: visitors 10445 / signups 31 / checkouts 0
- by-source visitors: openai-ads 3, organic 8865 (3+8865=8868 < 10445; ~1577
  unassigned, same gap shape as 0144-0146 -- no cause asserted)
- by-source signups: organic 27, other 1
- x402: challenges 40, submissions 4, rejections 4 (classified 1: malformed-header;
  unexplained 3), settlements 0, settlement-rate 0, attempt-rate 0.1

nbb funnel-pulse -> exit 0, RESULT recorded:
- visitors 10445 / signups 31 / checkouts 0
- delta vs immediate prior pulse: {visitors +1, signups 0, checkouts 0}; SCANNED 1,
  UNCHANGED false (visitor +1), EXTERNAL-FUNNEL-CHANGE false, SCORE unchanged

Funnel authority double-confirmed via two independent live reads (curl + nbb), both
agreeing: visitors 10445, signups 31, checkouts 0.

Cross-run vs 0146 (10427): visitors +18 (10427 -> 10445), organic +18 (8847 -> 8865),
openai-ads flat 3, signups still 31 checkouts 0. Organic reach only; no paid
acquisition drift.

## Stability check (all healthy, no 5xx)

| Endpoint | Result | Evidence |
|---|---|---|
| /api/funnel | 200 | JSON body, curl + nbb |
| / | 200 | homepage (0.196s) |
| /signup | 200 | signup HTML (0.119s -- holds; no 2.0s latency recurrence from 0145) |
| /ipld/v1 | 400 (expected) | route alive, 28B text/plain, params required (baseline 2026-09-03) |
| /ipld/ | 404 (expected) | bare path, no params (baseline 2026-09-03) |

No endpoint down. All four documented route expectations hold.

## Score -> Select (SCORECARD; WIP=1)

Selected (UNCHANGED from runs 0038-0146): counsel written-advice return on the
net-kotobase legal packet -- sole remaining gate on the 75pt row (6399/6310 paid
pilot) and transitively the 65/62/61/51/48pt red rows.

Reason: SCORECARD ranking unchanged (measured 2026-08-14). visitors +18, all organic
-- no paid shift, signups still 31, checkouts 0 -- changes no row's score. 110th
consecutive read with signups pinned at 31 while reach rises = conversion/offer
blocker at /signup, not reach. No external evidence -> score not raised. Counsel
reply human-dependent; sending out of this bot's no-deploy boundary. WIP=1.

Selected-action score: 58/100 (unchanged; 0 checkout, 0 payment, 0 settlement,
x402 submissions 4 / settlements 0 / unexplained 3).

## Bounded experiment (draft only -- NOT executed, sent, or deployed)

Carried standby proposal at runs/0045-cron-signup-activation-draft.edn (present).

What (unchanged): /signup activation variant -- add the price anchor ("Secure Managed
19,800 JPY/mo", only live-priced SKU per run 0033) plus one primary CTA near signup.
Zero change to pricing/checkout/legal; commercial go stays no-go.

Trigger held: signups still 31 (110th read). Expected signal: signups > 31 and/or
checkout-start > 0 post-variant. Do not induce purchase.

Status: standby. No live worker change by this bot. Deploy = owner decision, not sent,
not deployed. recipient: owner (kotobase.net operator).

## Decision

- Hold. No spend / self-purchase / paid acquisition / no outbound send / no deploy.
- Revenue = 0 measured (31 signups NOT revenue; 0 checkout, 0 settlement).
- Score unchanged 58/100. Canvas-ledger not touched.

## Next verification

1. Counsel reply (blocker since 0038) -- unblocks 75/65/62/61/51/48 rows.
2. First demand signal after this: signup > 31 or checkout-start > 0 (signups pinned
   at 31 for 110 reads).
3. Monitor /signup latency if > 1s recurs (0.119s here).
4. by-source unassigned gap (~1577) -- data-quality observe, no cause asserted.
5. openai-ads 3 of ~10445 = negligible paid acquisition, permanent hold.

canvas-ledger-touched?: false. score-raised?: false.

obvious-guard: Verified external revenue = 0. 31 signups NOT revenue. x402 submissions
4 / settlements 0 NOT revenue, cause unverified. Measured live via /api/funnel HTTP 200
+ nbb funnel-pulse exit 0 -- not synthesized. Nothing sent, nothing deployed.