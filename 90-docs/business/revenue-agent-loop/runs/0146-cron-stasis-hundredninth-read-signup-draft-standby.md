# Run 0146 - cron stability + hundred-and-ninth consecutive signup read (2026-09-08 JST)

Role: kotobase-stab stabilization loop, 1 iteration. canvas-ledger.edn NOT touched
by this run (single-writer routine, per SOUL rules).

Env note: terminal stdout channel is empty this session (same degradation as runs
0121-0145). Recovery pattern followed: signals captured via curl -o <file> and
nbb output-to-file, read back through read_file (separate healthy channel). Both
live network channels reached; this is NOT a not-measured run. nbb funnel-pulse
completed with exit 0 and wrote a proper record to the metrics EDN (read back via
tail of metrics/net-kotobase-funnel-pulses.edn).

## Measured (live, this run)

/api/funnel (curl, HTTP 200, JSON body, ~17:47 JST):
- funnel: visitors 10427 / signups 31 / checkouts 0
- by-source visitors: openai-ads 3, organic 8847 (organic+ads=8850 < 10427; ~1577
  visitors unassigned, same gap shape as 0144/0145's ~1577-1578 — no cause asserted)
- by-source signups: organic 27, other 1
- x402: challenges 40, submissions 4, rejections 4 (classified 1: malformed-header;
  unexplained 3), settlements 0, settlement-rate 0, attempt-rate 0.1

nbb funnel-pulse → exit 0, RESULT unchanged, recorded to metrics:
- visitors 10427 / signups 31 / checkouts 0
- delta vs immediate prior pulse: {visitors 0, signups 0, checkouts 0}; SCANNED 1,
  UNCHANGED true, EXTERNAL-FUNNEL-CHANGE false, SCORE unchanged

Funnel authority double-confirmed via two independent live reads (curl + nbb), both
agreeing: visitors 10427, signups 31, checkouts 0.

Cross-run vs 0145 (10388): visitors +39 (10388 → 10427), organic +39 (8808 →
8847), openai-ads flat 3, signups still 31 checkouts 0. Organic reach only; no
paid acquisition drift.

## Stability check (all healthy, no 5xx)

| Endpoint | Result | Evidence |
|---|---|---|
| /api/funnel | 200 | JSON body, curl + nbb |
| / | 200 | homepage (0.081s) |
| /signup | 200 | signup HTML (0.128s — recovered; 0145's 2.0s did not sustain) |
| /ipld/v1 | 400 (expected) | route alive, params required (baseline 2026-09-03); first probe timed out at 30s, retry returned 400 (0.10s) — transient |
| /ipld/ | 404 (expected) | bare path, no params (baseline 2026-09-03) |

No endpoint down. All four documented route expectations hold on final read.

## Score → Select (SCORECARD; WIP=1)

Selected (UNCHANGED from runs 0038-0145): counsel written-advice return on the
net-kotobase legal packet — sole remaining gate on the 75pt row (6399/6310 paid
pilot) and transitively the 65/62/61/51/48pt red rows.

Reason: SCORECARD ranking unchanged (measured 2026-08-14). visitors +39, all organic
— no paid shift, signups still 31, checkouts 0 — changes no row's score. 109th
consecutive read with signups pinned at 31 while reach rises = conversion/offer
blocker at /signup, not reach. No external evidence → score not raised. Counsel
reply human-dependent; sending out of this bot's no-deploy boundary. WIP=1.

Selected-action score: 58/100 (unchanged; 0 checkout, 0 payment, 0 settlement,
x402 submissions 4 / settlements 0 / unexplained 3).

## Bounded experiment (draft only — NOT executed, sent, or deployed)

Carried standby proposal at runs/0045-cron-signup-activation-draft.edn (present).

What (unchanged): /signup activation variant — add the price anchor ("Secure Managed
19,800 JPY/mo", only live-priced SKU per run 0033) plus one primary CTA near signup.
Zero change to pricing/checkout/legal; commercial go stays no-go.

Trigger held: signups still 31 (109th read). Expected signal: signups > 31 and/or
checkout-start > 0 post-variant. Do not induce purchase.

Status: standby. No live worker change by this bot. Deploy = owner decision, not
sent, not deployed. recipient: owner (kotobase.net operator).

## Decision

- Hold. No spend / self-purchase / paid acquisition / no outbound send / no deploy.
- Revenue = 0 measured (31 signups NOT revenue; 0 checkout, 0 settlement).
- Score unchanged 58/100. Canvas-ledger not touched.

## Next verification

1. Counsel reply (blocker since 0038) — unblocks 75/65/62/61/51/48 rows.
2. x402: movement from submissions 4 / settlements 0 / unexplained 3 = first demand signal.
3. /signup latency (0.128s, recovered from 0145's 2.0s) — flag if >1s recurs.
4. by-source gap (~1577 unassigned) — data-quality observe, no cause asserted.
5. openai-ads 3 of ~10427 = negligible paid acquisition, permanent hold.

canvas-ledger-touched?: false. score-raised?: false.

obvious-guard: Verified external revenue = 0. 31 signups NOT revenue. x402 submissions
4 / settlements 0 NOT revenue, cause unverified. Measured live via /api/funnel HTTP 200
+ nbb funnel-pulse exit 0 — not synthesized. Nothing sent, nothing deployed.