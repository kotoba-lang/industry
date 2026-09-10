# Run 0150 - kotobase-stab stabilization loop, 2026-09-08 JST (19:32)

Role: kotobase-stab loop, 1 iteration. canvas-ledger.edn NOT touched (single-writer
routine; explicit rule, respected).

Env note: terminal stdout channel empty this session (same degradation as 0121-0149).
Recovery pattern followed: signals via curl -o <file> and nbb output-to-file, read back
via read_file (healthy channel). Both live network channels reached -> this is a
MEASURED run, not not-measured.

## Measured (live, two readings agree in the same tick)

/api/funnel via curl (HTTP 200) -> funnel visitors 10541 / signups 31 / checkouts 0
nbb funnel-pulse -> exit 0, RESULT recorded, funnel visitors 10541 / signups 31 /
checkouts 0

Funnel authority (curl + nbb pulse agree; numeric tail at 19:31-19:32 in
`metrics/net-kotobase-funnel-pulses.edn`):

- visitors 10541 (up from 10510 at run 0149 -> +31 this cycle; pulses 10509/10510 -> 10541)
- signups **31 for the Nth consecutive run** (pinned; no movement)
- checkouts **0**
- by-source visitors: openai-ads 3, organic 8960 (3+8960=8963 < 10541; ~1577 unassigned
  gap, same data-quality note as 0136-0149, no cause asserted)
- by-source signups: organic 27, other 1
- x402: challenges 40, submissions 4, rejections 4 (classified 1: malformed-header;
  unexplained 3), settlements 0, settlement-rate 0, attempt-rate 0.1

nbb funnel-pulse delta (vs immediate prior pulse): visitors +2, signups 0, checkouts 0;
UNCHANGED false (visitor +2), EXTERNAL-FUNNEL-CHANGE false, SCORE unchanged.

Visitor growth this cycle all organic (+31). Paid acquisition float 3 (openai-ads).
Signups pinned 31, checkouts 0. Organic reach continues without conversion = offer /
activation blocker at /signup, not reach (consistent with runs 0038-0149).

## Stability check (all healthy, no 5xx)

| Endpoint | Result | Evidence |
|---|---|---|
| /api/funnel | 200 | JSON body (~0.27s), curl + nbb |
| / | 200 | homepage (0.12s) |
| /signup | 200 | signup HTML (0.16s) |
| /ipld/v1 | 400 (expected) | route alive, params required (baseline 2026-09-03) |
| /ipld/ | 404 (expected) | bare path, no params (baseline 2026-09-03) |

No endpoint down. All four documented route expectations hold.

## Score -> Select (SCORECARD; WIP=1)

Selected (UNCHANGED from runs 0038-0149): counsel written-advice return on the
net-kotobase legal packet -- sole remaining gate on the 75pt row (6399/6310 paid pilot)
and transitively the 65/62/61/51/48pt red rows. Draft on disk
(`net-kotobase/counsel-followup-draft-20260903.md`, present, NOT SENT).

Reason: SCORECARD ranking unchanged (measured 2026-08-14). visitors +31 all-organic, no
paid shift, signups still 31, checkouts 0 -> changes no row's score. 113+ consecutive
reads signups pinned while reach rises = /signup activation blocker, not reach. Final
counsel reply is human-dependent (owner/deploy boundary); this bot holds. WIP=1.

Selected-action score: 58/100 (unchanged; 0 checkout, 0 payment, 0 settlement, x402
submissions 4 / settlements 0 / unexplained 3).

## Bounded experiment (DRAFT only -- NOT executed, not sent, not deployed)

Carried standby proposal `runs/0045-cron-signup-activation-draft.edn` (present).

- What (unchanged): /signup activation variant -- price anchor ("Secure Managed 19,800
  JPY/mo", only live-priced SKU, run 0033) + one primary CTA near signup. Zero change to
  pricing/checkout/legal. Commercial go stays no-go.
- Trigger held: signups still 31. Expected signal: signups > 31 and/or checkout-start > 0.
- Recipient (for owner review only, NOT sent): owner (kotobase.net operator).
- Deploy of this variant = owner decision. This bot does not send / deploy / warm.

## Decision

- Hold. No spend / self-purchase / paid acquisition / no outbound send / no bound-deploy.
- Revenue = 0 measured (31 signups NOT revenue; 0 checkout, 0 settlement).
- Score unchanged 58/100. canvas-ledger-touched? false.

## Next verification

1. Counsel reply (blocker since run 0038) -- unblocks 75/66/65/61/51/48 rows.
2. First demand signal: signup > 31 OR checkout-start > 0 (signups pinned this entire run).
3. /signup latency if any spike (0.16s here; run 0145 had 2.0s, no recurrence).
4. by-source assignment gap (~1577) -- data-quality observe, no cause asserted.
5. openai-ads 3 of ~10541 = negligible paid acquisition, permanent hold. Monitor
   settlement-rate if it ever moves above 0 with classified rejections.

exaggeration-guard: Verified external revenue = 0. 31 signups NOT revenue. x402 submissions 4 /
settlements 0 NOT revenue (cause unverified). Measured live via curl HTTP 200 + nbb
funnel-pulse exit 0 -- not synthesized. Nothing sent, nothing deployed.