# Run 0153 - kotobase-stab stabilization loop, 2026-09-08 JST

Role: kotobase-stab loop, 1 iteration. canvas-ledger.edn NOT touched (single-writer
routine; explicit rule, respected).

Env note: terminal stdout channel empty this session (same degradation as 0121-0152).
Recovery pattern followed: signals via curl -o <file> and nbb output-to-file, read back
via read_file (healthy channel). Both live network channels reached -> this is a
MEASURED run, not not-measured.

## Measured (live, two independent readings agree in the same tick)

/api/funnel via curl (HTTP 200, 0.074s) -> funnel visitors 10648 / signups 31 / checkouts 0
nbb funnel-pulse -> exit 0, RESULT recorded, funnel visitors 10649 / signups 31 / checkouts 0

Funnel authority (curl + nbb pulse agree within +1 in the same tick; growth tail):

- visitors **10649** (up from 10626 at run 0152 -> +23 this cycle; all-organic)
- signups **31 — still pinned** (no movement across 0038-0155)
- checkouts **0**
- by-source visitors: openai-ads 3, organic 9063 (3+9063=9066 < 10649; ~1583 unassigned
  gap, same data-quality note as 0136-0152, no cause asserted)
- by-source signups: organic 27, other 1
- x402: challenges 40, submissions 4, rejections 4 (classified 1: malformed-header;
  unexplained 3), settlements 0, settlement-rate 0, attempt-rate 0.1
  (all unchanged since run 0060)

nbb funnel-pulse delta (vs immediate prior pulse): visitors +3, signups 0, checkouts 0;
UNCHANGED false (visitor +3), EXTERNAL-FUNNEL-CHANGE false, SCORE unchanged.

Visitor growth this cycle all organic (+23). Paid acquisition float 3 (openai-ads).
Signups pinned 31, checkouts 0. Organic reach continues without conversion = offer /
activation blocker at /signup, not reach (consistent with runs 0038-0152).

## Stability check

| Endpoint | Result | Evidence |
|---|---|---|
| /api/funnel | 200 | JSON body (0.07s), curl + nbb |
| / | 200 | homepage (0.16s) |
| /signup | 200 | signup HTML (0.06s) |
| /ipld/v1 | 400 (expected) | route alive, params required. First probe hung to 45s timeout (HTTP 000, one-off flake); two 20s-timeout retries returned 400 in 0.10s / 0.21s. No recurrence in retry. Same single-flake pattern as run 0152. Flagged observe-only. |
| /ipld/ | 404 (expected) | bare path, no params (baseline 2026-09-03) |

No confirmed outage. All four documented route expectations hold. The /ipld/v1
45s first-probe flake (HTTP 000) was re-verified healthy (400, 0.10-0.21s) in the same
tick; recorded but not treated as an outage (matches 0152's identical observe-only note).

## Score -> Select (SCORECARD.md; WIP=1)

Selected (UNCHANGED from runs 0038-0152): counsel written-advice return on the
net-kotobase legal packet -- sole remaining gate on the 75pt row (6399/6310 paid pilot)
and transitively the 65/62/61/51/48pt rows. Draft on disk
(`net-kotobase/counsel-followup-draft-20260903.md`, present, NOT SENT).

Reason: SCORECARD ranking unchanged (measured 2026-08-14). visitors +23 all-organic, no
paid shift, signups still 31, checkouts 0 -> changes no row's score. 115 consecutive
reads signups pinned while reach rises = /signup activation blocker, not reach. Final
counsel reply is human-dependent (owner/deploy boundary); this bot holds. WIP=1.

Selected-action score: 58/100 (unchanged; 0 checkout, 0 payment, 0 settlement, x402
submissions 4 / settlements 0 / unexplained 3).

## Bounded experiment (DRAFT only -- NOT executed, not sent, not deployed)

Carried standby proposal `runs/0045-cron-signup-activation-draft.edn` (present).

- What (unchanged): /signup activation variant -- one visible change: add the actual
  price anchor ("Secure Managed 19,800 JPY/mo", the only live-priced SKU per run 0033)
  plus one primary CTA near the signup form. Zero change to pricing/checkout/legal.
  Commercial go stays no-go.
- Trigger held: signups remain 31 while visitors continue to rise. Expected signal:
  signups counter above 31 and/or checkout-start > 0 in a post-variant window.
- Recipient (for owner review only, NOT sent): owner (kotobase.net operator).
- Deploy of this variant = owner decision. This bot does not send / deploy.

## Decision

- Hold. No spend / self-purchase / paid acquisition / no outbound send / no deploy.
- Revenue = 0 measured (31 signups NOT revenue; 0 checkout, 0 settlement).
- Score unchanged 58/100. canvas-ledger-touched? false.

## Next verification

1. Counsel reply (blocker since run 0038) -- unblocks 75/65/62/61/51/48 rows.
2. First demand signal: signups > 31 OR checkout-start > 0 (signups pinned all run).
3. /ipld/v1 flake -- second occurrence (0152, 0153). Still observe-only; if it
   recurs across a third run, flag as a routing check for owner (not a hard outage).
4. by-source assignment gap (~1583) -- data-quality observe, no cause asserted.
5. openai-ads 3 of ~10649 = negligible paid acquisition, permanent no. Monitor
   settlement-rate if it ever moves above 0 with classified rejections.

exaggeration-guard: Verified external revenue = 0. 31 signups NOT revenue. x402 submissions 4 /
settlements 0 NOT revenue (cause unverified). Measured live via curl HTTP 200 + nbb
funnel-pulse exit 0 -- not synthesized. Nothing sent, nothing deployed, nothing fabricated.