# Run 0180 - kotobase-stab loop - 2026-09-10 JST (~02:01 tick)

## Measured (live; two independent readings this tick)
- nbb funnel-pulse (workdir com-junkawasaki, EXIT=0): SCANNED 1; VISITORS 11759; SIGNUPS 31; CHECKOUTS 0; DELTA {:visitors 1, :signups 0, :checkouts 0}; UNCHANGED false; EXTERNAL-FUNNEL-CHANGE false; SCORE unchanged; RESULT recorded
- curl /api/funnel HTTP 200: visitors 11759; signups 31; checkouts 0 — readings agree exactly
- delta vs run 0179 (2026-09-09 ~20:00; visitors 11708): +51 visitors / +0 signups / +0 checkouts; organic 10181 (was 10129) = +52; openai-ads flat 3 (off-by-one between total and by-source, same as prior runs — observe only)
- by-source visitors: organic 10181 + openai-ads 3 = 10184 vs total 11759: gap ~1575 (data-quality observe; ~flat vs run 0179's ~1576)
- signups by-source: organic 27 + other 1 (28 of 31 accounted; 3 unexplained — unchanged, observe only)
- x402: challenges 40; submissions 4; rejections 4 (1 malformed-header, 3 unexplained); settlements 0; settlement-rate 0; attempt-rate 0.1; unmetered-twin-ratio 0; unpriced-plane-reads 0 — no field moved vs run 0179

## Stability check
- `/`            200 (0.076s)
- `/signup`      200 (0.063s)
- `/api/funnel`  200
- `/ipld/v1`     400 (0.124s) = expected (params required; baseline 2026-09-03)
- `/ipld/`       404 (expected; verified this run)
- No endpoint down; no 5xx; no timeout this tick (run 0179's one-off /ipld/v1 slow response did not repeat). All five route expectations hold.

## Score->Select (WIP=1)
- selected   -> UNCHANGED — counsel written-advice return on net-kotobase legal packet; sole remaining gate on the 75pt row (cloud-itonami paid pilot to 5 external tenants) and transitively the 65/62/61/51/48pt red rows (SCORECARD.md, observed 2026-08-14)
- draft on disk -> `runs/0045-cron-signup-activation-draft.edn` (carried; DRAFT; NOT SENT; unchanged from run 0179)
- reason -> SCORECARD ranking unchanged (measured 2026-08-14). Movement this tick +51 visitors / +0 signups / +0 checkouts; openai-ads flat 3; x402 settlements 0 -> no row score change. Council reply is human-dependent; sending out of scope; WIP=1 held.
- selected-action score -> 58/100 (unchanged; 31 signups are not revenue)

## Bounded experiment (DRAFT only; NOT executed/sent/deployed)
- carried standby -> `runs/0045-cron-signup-activation-draft.edn` (/signup activation variant: price anchor "Secure Managed ¥19,800/mo" — only live-priced SKU per run 0033 — plus one primary CTA near signup form; zero change to pricing/checkout/legal by this bot)
- trigger held -> signups remain 31 while visitors grow (+51 this tick); expected signal signups > 31 or checkout-start > 0 in post-variant window; NOT fired
- recipient (for owner review only; NOT sent) -> kotobase.net operator; deploy = owner decision

## Decision -> HOLD
- no spend; no self-purchase; no paid acquisition; no outbound send; no deploy; revenue = 0 measured (31 signups NOT revenue; 0 checkout; 0 settlement; x402 settle 0); score unchanged 58/100; canvas-ledger-touched? false
- NOTE: signups pinned at 31 from 2026-09-03 (run 0045, visitors 4870) through run 0180 (visitors 11759) while visitors ~2.41x'd — conversion gap (31/11759 ≈ 0.26%) remains the standing business bottleneck. The /signup activation draft is the one queued lever; it awaits owner go/no-go.

## Next verification
1) council written reply (blocker since run 0038; unblocks 75/65/62/61/51/48pt rows)
2) demand signal signups > 31 or checkout-start > 0 (triggers standby draft 0045 for owner go/no-go; do not implement without approval)
3) x402: submissions still 4; settle 0; 3 rejections unexplained — any change is first demand signal
4) by-source visitor gap ~1575 and signups by-source 28-of-31 unexplained — observe only
5) endpoint health — all five routes hold; no repeat of run 0179's /ipld/v1 slow response
6) terminal stdout channel health — STILL degraded (foreground commands return empty output; verified again this tick via /tmp-file write + read_file and via background process + process_manage). Keep the /tmp-file + read_file workaround pattern

## exaggeration-guard
- external revenue == 0; 31 signups NOT revenue; x402 subm 4 / settle 0 NOT revenue; all deltas read live via curl (HTTP 200) + nbb funnel-pulse (EXIT 0); not synthesized; nothing sent; nothing deployed; nothing fabricated
