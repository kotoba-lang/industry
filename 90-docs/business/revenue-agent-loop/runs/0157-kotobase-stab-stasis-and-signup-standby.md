# Run 0157 - kotobase-stab stabilization loop, 2026-09-08 JST

Role: kotobase-stab loop,1 iteration. canvas-ledger.edn NOT touched(single-writer routine,explicit rule,respected).

Env note: terminal stdout channel empty again this session(same degradation as 0121-0156). Recovery pattern followed: signals via curl and nbb output-to-file,read back via read_file(healthy channel.. Both live network channels reached -> this is a MEASURED run,not not-measured. nbb funnel-pulse exit 0,and /api/funnel returned a JSON body(independently.Measured.



## Measured(live,two independent readings,same tick.



/api/funnel via curl(HTTP 200,0.182s)-> funnel visitors 10769 / signups 31 / checkouts 0
nbb funnel-pulse-> exit 0,RESULT recorded,funnel visitors 10769 / signups 31 / checkouts 0

Funnel authority(curl + nbb pulse agree exactly in the same tick:
- visitors **10769**(up from 10732 at run 0156-> +37 this cycle,all-organic.
- signups **31 -- still pinned**(no movement across 0038-0157..
- checkouts **0**

by-source visitors: organic 9181,openai-ads 3(9181+3=9184 <10769;~1585 unassigned gap,same data-quality note as 0136-0156,no cause asserted..
by-source signups: organic 27,other  ���1

	x402: challenges 40,submissions 4,rejections 4(classified 1:malformed-header;unexplained 3),settlements 0,settlement-rate 0,attempt-rate 0.1(all unchanged since run 0060..

nbb funnel-pulse delta: visitors +37 vs run 0156 pulse,signups 0,checkouts 0;UNCHANGED false(visitor +37),EXTERNAL-FUNNEL-CHANGE false,SCORE unchanged..



## Stability check

| Endpoint | Result | Evidence |
|---|---|---|
| /api/funnel | 200 |JSON body(0.182s),curl + nbb |
| / |�200 | homepage(0.299s|
| /signup |�200 | signup page(0.177s|
| /ipld/v1 |�400(expected| invalid or corrupt CID block,params required |
| /ipld/ |�404(expected| bare path,no params(baseline 2026-09-03|

No confirmed outage;all five documented route expectations hold in this tick.

**/ipld/v1 first-probe flake did NOT recur this run(clean on first probe.** Run 0156 flagged a first-probe timeout(HTTP 000,curl exit 28..This run,first probe to /ipld/v1 returned the expected clean 400 in 0.372s(no timeout,no retry needed;overall tick sub-400ms on all routes..Pattern is now 3 flaked runs(0152,0153,0156 vs 3 clean runs(0154,0155,0157 out of the recent 6.The run-0156 re-flag condition(twice-in-a-row.is NOT met(clean at 0157.;held at observe-only,not elevated to a routing check..



## Score -> Select(SCORECARD.md;WIP=1)

Selected(UNCHANGED from runs 0038-0156:counsel written-advice return on the net-kotobase legal packet --sole remaining gate on the 75pt row(cloud-itonami 6399/6310 paid pilot)and transitively the 65/62/61/51/48pt rows.Draft on disk(`90-docs/business/net-kotobase/counsel-followup-draft-20260903.md`,present,NOT SENT;same file carried since 0153..

Reason: SCORECARD ranking unchanged(measured 2026-08-14..visitors +37 all-organic,no paid shift,signups still 31,checkouts 0,x402 settlements 0 -> changes no row's score.This is the 119th consecutive read itsignups pinned while reach rises=/signup activation blocker,not reach.Final counsel reply is human-dependent(owner/deploy boundary;this bot holds.WIP=1..

Selected-action score: 58/100(unchanged;0 checkout,0 payment,0 settlement,x402 submissions 4 / settlements 0 / unexplained 3..



## Bounded experiment(DRAFT only -- NOT executed,not sent,not deployed)

Carried standby proposal `runs/0045-cron-signup-activation-draft.edn`(present..

- What(unchanged:/signup activation variant --one visible change:add the actual price anchor("Secure Managed 19,800 JPY/mo",the only live-priced SKU per run 0033)plus one primary CTA near the signup form.Zero change to pricing/checkout/legal.Commercial go stays no-go..
- Trigger held:signups remain 31 while visitors continue to rise.Expected signal:signups counter above 31 and/or checkout-start > 0 in a post-variant window..
- Recipient(for owner review only,NOT sent:owner(kotobase.net operator..
- Deploy of this variant = owner decision.This bot does not send / deploy..



## Decision
- Hold.No spend / self-purchase / paid acquisition / no outbound sent / no deploy..
- Revenue = 0 measured(31 signups NOT revenue;0 checkout,0 settlement..
- Score unchanged 58/100. canvas-ledger-touched? false..



## Next verification
1.Counsel reply(blocker since run 0038--unblocks 75/65/62/61/51/48 rows..
2.First demand signal:signups > 31 OR checkout-start > 0(signups pinned all run..
3./ipld/v1 first-probe flake --occurred 0152,0153,(gap0154/0155,,0156;clean 0157. now  ���3/6 recent runs,not two-in-a-row;held at observe.Track trend;elevate to a routing check only if it reaches 2 consecutive(or visibly degrading latency;not yet..
4.By-source assignment gap(~1585--data-quality observe,no cause asserted..
5.openai-ads 3 of ~10769 = negligible paid acquisition,permanent no.Monitor settlement-rate if it ever moves above 0 with classified rejections..

exaggeration-guard:Verified external revenue =0.31 signups NOT revenue.x402 submissions 4 / settlements 0 NOT revenue(cause unverified..Measured live via curl HTTP  ���200 + server-side nbb funnel-pulse exit嚥�0 --not synthesized.Nothing sent,nothing deployed,nothing fabricated..
