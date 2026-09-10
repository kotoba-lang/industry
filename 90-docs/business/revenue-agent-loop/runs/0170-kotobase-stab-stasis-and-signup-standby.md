# Run 0170 - kotobase-stab loop - 2026-09-09 JST
# role: 1 iteration; canvas-ledger.edn NOT touched (single-writer routine; respected.)

## Measured (live; two independent readings agree this tick)
- nbb funnel-pulse (server pulse): SCANNED 1; VISITORS 11253; SIGNUPS 31; CHECKOUTS 0; DELTA {:visitors 1, :signups 0, :checkouts 0} (vs last pulse); UNCHANGED false (visitor +1); EXTERNAL-FUNNEL-CHANGE false; SCORE unchanged; RESULT recorded (EXIT=0)
- curl /api/funnel HTTP 200 (0.263s); visitors 11253; signups 31; checkouts 0
- both readings agree on the live absolute (11253) in the same tick
- delta vs run 0169 (05:32; visitors 11208/11207): **+45/+46**; organic 9628 -> 9675 = **+47**; openai-ads stayed 3; signups STILL pinned at 31; checkouts 0
- by-source visitors: organic 9675 + openai-ads 3 = 9678 vs total 11253: gap ~1575 (data-quality observe; measured; no cause assigned; -1 vs run 0169's 1576)
- signups by-source: organic 27 + other 1 (only 28 of 31 accounted; 3 unexplained — same since earlier runs, observe only)
- x402: challenges 40; submissions 4; rejections 4 (1 malformed-header, 3 unexplained); settlements 0; settlement-rate 0; attempt-rate 0.1 — stasis since run 0060

## Stability check; all five routes hold; no outage
- `/`          200 (0.129s)
- `/signup`    200 (0.055s)
- `/api/funnel` 200 (0.263s)
- `/ipld/v1`   400 (expected — route alive, params required; baseline 2026-09-03)
- `/ipld/`     404 (expected; verified this run)
- No endpoint down; no 5xx; all five documented route expectations hold; latency healthy (/signup read 0.055s vs run 0169's 0.17s; no 2.0s-spike recurrence of run 0145)

## Score->Select (WIP=1)
- selected   ->  UNCHANGED — council written-advice return on net-kotobase legal packet; sole remaining gate on the 75pt row (cloud-itonami 6399/6310 paid pilot to 5 external tenants) and transitively the 65/62/61/51/48pt red rows (SCORECARD, observed 2026-08-14)
- draft on disk -> `runs/0045-cron-signup-activation-draft.edn` (verified present this run; DRAFT; NOT SENT; carried since run 0153 / trigger since 0045)
- reason     ->  SCORECARD ranking unchanged (measured 2026-08-14). Movement this window +45/+46 total / +47 organic; openai-ads flat 3; signups STILL 31; checkouts 0; x402 settlements 0 -> no row score change. Council reply is human-dependent; sending out of scope; WIP=1 held.
- selected-action score -> 58/100 (unchanged; 31 signups are not revenue, this is the score-card held line-not a revenue credit)

## Bounded experiment (DRAFT only; NOT executed/sent/deployed)
- carried standby -> `runs/0045-cron-signup-activation-draft.edn` (present; /signup activation variant: price anchor "Secure Managed ¥19,800/mo" — the only live-priced SKU per run 0033 — plus one primary CTA near signup form; zero change to pricing/checkout/legal by this bot)
- trigger held   -> signups remain 31 while visitors grow (+45/+46 this run). Expected signal signups > 31 or checkout-start > 0 in post-variant window; NOT fired (signups still 31, checkout 0 this read)
- recipient (for owner review only; NOT sent) -> kotobase.net operator; deploy = owner decision (outside no-deploy boundary)

## Decision -> HOLD
- no spend; no self-purchase; no paid acquisition; no outbound send; no deploy; revenue = 0 measured (31 signups NOT revenue; 0 checkout; 0 settlement; x402 settle 0); score unchanged 58/100; canvas-ledger-touched? false
- NOTE: signups have been pinned at 31 from 2026-09-03 (run 0045, visitors 4870) through 2026-09-09 (run 0170, visitors 11253) while visitors roughly 2.3x'd — conversion gap (31/~11253 ≈ 0.28%) is the standing business bottleneck and is unchanged. The /signup activation draft is the one queued lever; it awaits owner go/no-go.

## Next verification
1) council written reply (blocker since run 0038; unblocks 75/65/62/61/51/48pt rows)
2) demand signal signups > 31 or checkout-start > 0 (triggers standby draft 0045 for owner go/no-go; do not implement without approval)
3) x402: submissions still 4; settle 0; 3 rejections unexplained — any change is first demand signal; if unexplained stays 3 more runs, flag growing observability gap
4) by-source visitor gap ~1575 (organic+ads vs total) and signups by-source 28-of-31 unexplained — observe only
5) endpoint latency — / and /signup read 0.129s/0.055s this run (vs run 0169's 0.08s/0.17s; healthy; no recurrence of run 0145's 2.0s; watch if either regresses past ~1s consistently)

## exaggeration-guard
- external revenue == 0; 31 signups NOT revenue; x402 subm 4 / settle 0 NOT revenue (cause unverified); all deltas read live via curl + nbb HTTP 200 readings (0.263s / server pulse EXIT 0); not synthesized; nothing sent; nothing deployed; nothing fabricated