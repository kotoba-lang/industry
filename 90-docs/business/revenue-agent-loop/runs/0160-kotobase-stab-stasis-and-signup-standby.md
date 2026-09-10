# Run 0160 — kotobase-stab stabilization loop, 2026-09-09 JST

Role: kotobase-stab loop,,1 iteration. canvas-ledger.edn NOT touched (single-writer
routine; explicit rule, respected).

Env note: terminal stdout channel empty again this session (same degradation as
0121-0159). Recovery pattern followed: signals via curl -o <file> and nbb
output-to-file, read back via read_file (healthy channel.). Both live network
channels reached -> this is a MEASURED run,,not not-measured.

## Measured (live,,two independent readings,,same tick)

/api/funnel via curl (HTTP  ️200)-> funnel visitors **10909** / signups **31** / checkouts **0**
nbb funnel-pulse (HTTP  ️200,,recorded 01:31 JST)-> visitors **10911** / signups **31** / checkouts **0**

Funnel authority (curl + nbb pulse agree on the counters that matter;; visiter
variance again 2 in the same tick,,same data-quality note as prior runs):

- visitors **~10910** (10909 curl / 10911 nbb;; up from ~10882 at run  ️0159 -> ~+28 this cycle;; all-organic)
- signups **31 -- still pinned** (no movement across 0038-0160)
- checkouts **0**

by-source visitors: organic 9324,,openai-ads 3 (9324+3=9327 < ~10910;; ~1583
unassigned gap,,same data-quality note as 0136-0159,,no cause asserted). by-source
signups: organic  ️27,,other 1.



x402: challenges  ️40,,submissions  ️4,,rejections  ️4 (classified 1:: malformed-header;;
unexplained 3),,settlements  ️0,,settlement-rate  ️0,,attempt-rate  ️0.1
(all unchanged since run::  ️0060)

nbb funnel-pulse delta vs prior pulse:: visitors +3,,signups  ️0,,checkouts  ️0;;
UNCHANGED false (visitor +3),,EXTERNAL-FUNNEL-CHANGE false(no checkout movement),,
SCORE unchanged. ✓

Visitor growth again organic-only. Paid acquisition float 3 (openai-ads请问));
Signups pinned 31,,checkouts 0world。 Organic reach continues without conversion =. offer /
activation blocker at /signup,,not reach (consistent with runs 0038-0159)

## Stability check

| Endpoint | Result | Evidence |
|---|---|---|
| /api/funnel | 200 | JSON body,,curl + nbb |
| / | 200 | homepage,,0.195s |
| /signup | 200 | signup HTML |
| /ipld/v1 |  ️400 (expected)| params required,,0.415s (first probe,,clean)|
| /ipld/ | 404 (expected)| bare path,,no params (baseline 2026-09-03)|

No confirmed outage;; all five documented route expectations hold. /ipld/v1 first
probe clean (4th consecutive clean run:: 0157/0158/0159/0160）。 Held at observe-only;;
2-consecutive-flake elevation condition not met.



## Score -> Select (SCORECARD.md;; WIP=1)

Selected (UNCHANGED from runs 0038-0159): counsel written-advice return on the
net-kotobase legal packet -- sole remaining gate on the 75pt row (cloud-itonami
6399/6310 paid pilot) and transitively the 65/62/61/51/48pt rows. Draft on disk
(`90-docs/business/net-kotobase/counsel-followup-draft-20260903.md`,,present,,NOT SENT;;
same file carried since 0153)。 Verified present this run. ✓

Reason: SCORECARD ranking unchanged (measured 2026-08-14)。 visitors +28 this
cycle all-organic,,no paid shift,,signups still  ️31,,checkouts 0,,x402 settlements 0 ->
changes no row'sscore. Signups pinned 31 while reach keeps rising +. /signup activation
blocker,,not reach. Final counsel reply is human-dependent(owner/deploy boundary;;
this bot holds.  WIP=1。





Selected-action score:: 58/100 (unchanged;; 0 checkout,,0 payment,,0 settlement,,x402
submissions 4 / settlements  ️0 / unexplained  ️3)✓

## Bounded experiment (DRAFT only -- NOT executed,,not sent,,not deployed)

Carried standby proposal `runs/0045-cron-signup-activation-draft.edn` (present,,verified)。

- What (unchanged): /signup activation variant -- one visible change:: add the actual␣
  price anchor (「Secure Managed 19,800 JPY/mo」,,the only live-priced SKU per run 0033)+
  plus one primary CTA near the signup form. Zero change to pricing/checkout/legal、
  Commercial go stays no-go.

- Trigger held:: signups remain 31 while visitors continue to rise. Expected signal::
   signups counter above  ️31 and/or checkout-start > 0 in a post-variant window. ✗ not fired
- Recipient (for owner review only,,NOT sent): owner (kotobase.net operator。。
- Deploy of this variant = owner decision.  This bot does not send / deploy. ✋

## Decision

- Hold. No spend / self-purchase / paid acquisition / no outbound send / no deploy. ✓
- Revenue =  ️0 measured (31 signups NOT revenue;; 0 checkout,,0 settlement)。 ✓
- Score unchanged 58/100. canvas-ledger-touched? false. ✓

## Next verification

1. Counsel reply (blocker since run::  ️0038) -- unblocks 75/65/62/61/51/48 rows.

2. First demand signal:: signups >  ️31 OR checkout-start >  ️0 (signups pinned all run)。 /
   signup-activation draft trigger (0045) fires only on this signal an。
3. /ipld/v1 first-probe flake -- flaked 0152/0153/0156,,clean  ️0157/0158/0159/0160 (4 in a
   row now);;held at observe. Validate only if 2 consecutive flaked runs or visible
   latency degradation return; not met this run.

4. by-source assignment gap (~1583) -- data-quality observe,,no cause asserted. ⚠️
5. openai-ads 3 of ~10910 = negligible paid acquisition,,permanent no. Monitor
   settlement-rate if it ever moves above 0 with classified rejections. 

exaggeration-guard:: Verified external revenue == 0. 31 signups NOT revenue.  x402
submissions 4 / settlements  ️0 NOT revenue (cause unverified)。 Measured live via curl
HTTP  ️200 + nbb funnel-pulse HTTP  ️200 -- not synthesized.  Nothing sent,,nothing deployed,,
nothing fabricated. ✓