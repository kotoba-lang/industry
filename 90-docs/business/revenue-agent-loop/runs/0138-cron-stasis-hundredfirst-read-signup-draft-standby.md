# Run 0138 - cron stability + hundred-first consecutive signup read (2026-09-08 JST)

Role: kotobase-stab stabilization loop, 1 iteration. canvas-ledger.edn NOT touched
by this run (single-writer routine, per SOUL rules).

Env note: the terminal stdout channel was empty again this session (same degradation
as runs 0121-0137) - `echo` and every probe returned exit 0 with no stdout captured.
Per the established recovery pattern, signals were captured via curl `-o <file>` and
read back through read_file (a separate, healthy channel). nbb `funnel-pulse` also
returned EXIT=0 with stdout swallowed; funnel authority this run comes from
`/api/funnel`, which returned HTTP 200 with a JSON body. Live network reached; this is
NOT a not-measured run.

## Measured (live, this run)

`/api/funnel` → HTTP 200, JSON body:
- funnel: visitors **10178** / signups **31** / checkouts **0**
- by-source visitors: openai-ads 3, organic 8599 (note: organic+ads=8602 < 10178,
  ~1576 visitors unassigned in the by-source breakdown; no cause asserted)
- by-source signups: organic 27, other 1
- x402: challenges 38, submissions 4, rejections 4 (classified 1: malformed-header;
  unexplained 3), settlements 0, settlement-rate 0, attempt-rate 0.105

nbb funnel-pulse exit=0, stdout not retrievable this session (infra, not product).

## Stability check (all healthy, no 5xx)

| Endpoint | Result | Evidence |
|---|---|---|
| /api/funnel | 200 | JSON body read (above) |
| / | 200 | full homepage HTML, title "Kotobase — trusted graph state for AI-generated software" |
| /signup | 200 | full HTML, title "Start free — kotobase" |
| /ipld/v1 | 400 (expected) | body "invalid or corrupt CID block" = route alive, params required (baseline 2026-09-03) |
| /ipld/ | 404 (expected) | "Page not found — kotobase" |

No endpoint down. All four documented route expectations hold.

## Score → Select (SCORECARD.md; WIP=1)

Selected (UNCHANGED from runs 0038-0137): **counsel written-advice return on the
net-kotobase legal packet** - the sole remaining gate on the 75pt row (cloud paid pilot
to external tenants, 6399/6310) and transitively the 65/62/61/51/48pt red rows.

Reason: SCORECARD ranking (measured 2026-08-14) unchanged. Cross-run delta vs 0137:
visitors +38 (10140 → 10178), organic +36, openai-ads flat 3, signups **still 31**,
checkouts 0 - organic reach only, changes no row's score. **101st consecutive read with
signups pinned at 31 while reach keeps rising** = conversion/offer blocker at /signup,
consistent with the carried activation draft being the lever. No new external evidence
→ score not raised. Counsel reply is human-dependent; sending it is outside this bot's
no-deploy boundary. WIP=1.

Selected-action score: **58/100** (unchanged; 0 checkout, 0 payment, 0 settlement,
x402 submissions 4 / settlements 0 / unexplained rejections 3).

## Bounded experiment (draft only — NOT executed, sent, or deployed)

Carried standby proposal, on disk at
`runs/0045-cron-signup-activation-draft.edn` (confirmed present, 3673 bytes this run).

What (unchanged): /signup activation variant - one visible change: add the actual price
anchor ("Secure Managed ¥19,800/mo", the only live-priced SKU per run 0033) plus one
primary CTA near the signup form. Zero change to pricing/checkout/legal text; commercial
go stays no-go.

Trigger held: signups still 31 while visitors rose for the 101st consecutive read.

Expected signal: signups counter above 31 and/or checkout-start > 0 in a post-variant
window. Do not induce a purchase.

Status: **standby**. No live worker change by this bot (no-deploy boundary). Deploy is an
owner decision — not sent, not deployed.

recipient: owner (kotobase.net operator). Internal draft only; deploy = owner call
outside this bot's boundary. sent?: false. deployed?: false.

## Decision

- Hold. No spend / self-purchase / paid acquisition / no outbound send / no deploy.
- Revenue = 0 measured (verified external revenue; 31 signups are NOT revenue, 0
  checkout, 0 settlement).
- Score unchanged 58/100. Canvas-ledger not touched.

## Next verification

1. Counsel reply (unchanged blocker since 0038) - would unblock 75/65/62/61/51/48 rows.
2. signup conversion drift - owner-side UA/referrer split. openai-ads still 3 of ~10178 =
   negligible paid acquisition, permanent-hold. No paid-acquisition gate met.
3. x402: any movement from submissions 4 / settlements 0 / unexplained 3 = first demand
   signal (unchanged across 0134-0137+).
4. by-source visitor gap (~1576 of 10178 unassigned between organic+ads and total) -
   data-quality observation; flag if it persists, do not assert a cause.

canvas-ledger-touched?: false. score-raised?: false.

obvious-guard:
Verified external revenue = 0 (0 checkout, 0 settlement). 31 signups are NOT revenue.
x402 submissions 4 / settlements 0 NOT revenue, cause unverified. All values read live
via /api/funnel HTTP 200 (JSON) + file-confirmed HTML bodies; terminal stdout empty but
files/read_file channel healthy - real, not synthesized. Nothing sent, nothing deployed.
