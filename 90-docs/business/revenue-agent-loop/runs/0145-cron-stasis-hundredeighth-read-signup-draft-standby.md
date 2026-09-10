# Run 0145 - cron stability + hundred-and-eighth consecutive signup read (2026-09-08 JST)

Role: kotobase-stab stabilization loop, 1 iteration. canvas-ledger.edn NOT touched
by this run (single-writer routine, per SOUL rules).

Env note: terminal stdout channel is empty this session (same degradation as runs
0121-0144). Recovery pattern followed: signals captured via curl `-o <file>` and nbb
output-to-file, read back through read_file (separate healthy channel). Both live
network channels reached; this is NOT a not-measured run. `nbb funnel-pulse`
completed with exit 0 and wrote a proper record to the metrics EDN.

## Measured (live, this run)

`/api/funnel` (curl, HTTP 200, JSON body, ~17:07 JST):
- funnel: visitors **10388** / signups **31** / checkouts **0**
- by-source visitors: openai-ads 3, organic 8808 (organic+ads=8811 < 10388; ~1577
  visitors unassigned, same gap shape as 0144's ~1578 — no cause asserted)
- by-source signups: organic 27, other 1
- x402: challenges 40, submissions 4, rejections 4 (classified 1: malformed-header;
  unexplained 3), settlements 0, settlement-rate 0, attempt-rate 0.1

`nbb funnel-pulse` → exit 0, RESULT recorded:
- visitors **10388** / signups **31** / checkouts **0**
- delta vs immediate prior pulse: {visitors 1, signups 0, checkouts 0}; SCANNED 1,
  UNCHANGED false, EXTERNAL-FUNNEL-CHANGE false, SCORE unchanged

Funnel authority double-confirmed via two independent live reads (curl + nbb), both
agreeing: visitors 10388, signups 31, checkouts 0.

Cross-run vs 0144 (10388): visitors **+58** (10330 → 10388), organic +59 (8749 →
8808), openai-ads flat 3, signups **still 31**, checkouts 0. Organic reach only; no
paid acquisition drift.

## Stability check (all healthy, no 5xx)

| Endpoint | Result | Evidence |
|---|---|---|
| /api/funnel | 200 | JSON body read via curl + nbb |
| / | 200 | homepage (0.56s) |
| /signup | 200 | signup HTML (2.0s — slower than prior runs' ~0.1s but healthy) |
| /ipld/v1 | 400 (expected) | route alive, params required (baseline 2026-09-03) |
| /ipld/ | 404 (expected) | bare path, no params (baseline 2026-09-03) |

No endpoint down. All four documented route expectations hold. /signup returned 200
but at 2.0s this run vs 0.1s in 0144/0051 — noted as latency observation, not a 5xx;
flag if it sustains.

## Score → Select (SCORECARD.md; WIP=1)

Selected (UNCHANGED from runs 0038-0144): **counsel written-advice return on the
net-kotobase legal packet** — the sole remaining gate on the 75pt row (6399/6310
paid pilot to external tenants) and transitively the 65/62/61/51/48pt red rows.

Reason: SCORECARD ranking unchanged (measured 2026-08-14). Cross-run delta vs 0144:
visitors +58 (10330 → 10388), all organic — no paid shift, signups **still 31**,
checkouts 0 — changes no row's score. **108th consecutive read with signups pinned
at 31 while reach keeps rising** = the conversion/offer blocker is at /signup, not
reach. No new external evidence → score not raised. Counsel reply is human-dependent;
sending it is outside this bot's no-deploy boundary. WIP=1.

Selected-action score: **58/100** (unchanged; 0 checkout, 0 payment, 0 settlement,
x402 submissions 4 / settlements 0 / unexplained rejections 3). Scorecard top row 75
and selection 58 both unchanged across runs 0038-0145.

## Bounded experiment (draft only — NOT executed, sent, or deployed)

Carried standby proposal, on disk at
`runs/0045-cron-signup-activation-draft.edn` (confirmed present).

What (unchanged): /signup activation variant — one visible change: add the actual
price anchor ("Secure Managed ¥19,800/mo", the only live-priced SKU per run 0033)
plus one primary CTA near the signup form. Zero change to pricing/checkout/legal
text; commercial go stays no-go.

Trigger held: signups still 31 while visitors rose for the 108th consecutive read.

Expected signal: signups counter above 31 and/or checkout-start > 0 in a post-variant
window. Do not induce a purchase.

Status: **standby**. No live worker change by this bot (no-deploy boundary). Deploy is
an owner decision — not sent, not deployed.

recipient: owner (kotobase.net operator). Internal draft only; deploy = owner call
outside this bot's boundary. sent?: false. deployed?: false.

## Decision

- Hold. No spend / self-purchase / paid acquisition / no outbound send / no deploy.
- Revenue = 0 measured (verified external revenue; 31 signups are NOT revenue, 0
  checkout, 0 settlement).
- Score unchanged 58/100. Canvas-ledger not touched.

## Next verification

1. Counsel reply (unchanged blocker since 0038) — would unblock 75/65/62/61/51/48 rows.
2. signup conversion drift — owner-side UA/referrer split. openai-ads still 3 of
   ~10388 = negligible paid acquisition, permanent-hold. No paid-acquisition gate met.
3. x402: any movement from submissions 4 / settlements 0 / unexplained 3 = first
   demand signal (unchanged across 0134-0145+).
4. by-source visitor gap (~1577 of 10388 unassigned between organic+ads and total) —
   data-quality observation; flag if it persists, do not assert a cause.
5. /signup latency (2.0s this run) — re-measure; flag if it recurs beyond ~1s.

canvas-ledger-touched?: false. score-raised?: false.

obvious-guard:
Verified external revenue = 0 (0 checkout, 0 settlement). 31 signups are NOT revenue.
x402 submissions 4 / settlements 0 NOT revenue, cause unverified. All values read live
via /api/funnel HTTP 200 (JSON, curl) AND nbb funnel-pulse exit 0 (recorded to metrics)
— two independent channels; terminal stdout empty but files/read_file channel healthy —
real, not synthesized. Nothing sent, nothing deployed.