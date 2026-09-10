# Run 0164 - kotobase-stab loop - 2026-09-09 JST
# role: 1 iteration; canvas-ledger.edn NOT touched (single-writer routine; respected.

## Measured (live; two independent readings same tick
- nbb funnel-pulse: SCANNED 1;; VISITORS 10978;; SIGNUPS 31;; CHECKOUTS 0;; DELTA {visitors 1, signups 0, checkouts 0};; UNCHANGED false;; EXTERNAL-FUNNEL-CHANGE false;; SCORE unchanged;; RESULT recorded (EXIT=0
- curl /api/funnel HTTP 200 (first-probe 0.18s;; visitors 10978;; signups  31;; checkouts  0
- both readings agree on the live absolute value (10978) in the same tick
- delta vs run 0163 (visitors 10954 → 10978: **+24**, organic-only;; signups 31 still pinned;; checkouts  0
- x402,: challenges  40;; submissions  4;; rejections  4 (1 malformed-header;; 3 unexplained),, settlements  0;; settlement-rate  0;; attempt-rate  concluding0.1 — stasis since run 0060
- by-source visitors,: organic 9398 + openai-ads 3 =  9401 vs total 10978: gap  1577 (data-quality observe;; measured difference;; no cause assignedathon
- signups by-source,: organic 27 + other  1

## Stability check;; all five routes hold;; no outage
- `/`  200 (0.124s
- `/signup`  200 (0.047s
- `/api/funnel`  200
- `/ipld/v1`  400 (expected — route alive;; params required;; baseline 2026-09-03)。 **Note:** first-probe this run returned HTTP 000-worthy 20.9s before the 400 (a first-probe cold-start flake;; same pattern as runs 0152/0153/0156/0158;), but two immediate re-probes both clean 400 (0.037s / 0.106s)。 Preceded by a 7-clean streak (0157-0163;;;not 2 consecutive flaked runs here (0163 clean → 0164 first-probe spike;; isolated;; single spike,subsequent probes clean) → kept at observe-only per elevation rule (2 consecutive OR persistently-degrading latency;; neither reached
- `/ipld/`  404 (expected;; verified this run

## Score→Select (WIP=1
- selected → UNCHANGED — counsel written-advice return on net-kotobase legal packet;; sole remaining gate on the 75pt row (cloud-itonami 6399/6310 paid pilot to external tenants)and transitively the  65/62/61/51/48pt red rows
- draft on disk → `90-docs/business/net-kotobase/counsel-followup-draft-20260903.md` (present;; DRAFT;; NOT SENT;; carried since run 0153);; state unchanged
- reason → SCORECARD ranking unchanged (measured 2026-08-14);; movement this window  +24 organic-only visitors;; signups still  31;; checkouts  0;; x402 settlements  0 →  no row score change;; counsel reply is human-dependent;; sending out of scope;; WIP=1 held
- selected-action score →  58/100 (unchanged

## Bounded experiment (DRAFT only;; NOT executed/sent/deployed
- carried standby → `runs/0045-cron-signup-activation-draft.edn` (present;; /signup activation variant:add price anchor (Secure Managed ¥19,800/mo;; only live-priced SKU per run 0033),+ one primary CTA near signup form;; zero change to pricing/checkout/legal by this bot
- trigger held → signups remain 31 while visitors grow (+24 this run);; expected signal signups  31 or checkout-start  0 in post-variant window;; NOT fired (signups still  31;; checkout  0 this read
- recipient (for owner review only;; NOT sent) → kotobase.net operator;; deploy = owner decision (outside no-deploy boundary

## Decision → HOLD
- no spend;; no self-purchase;; no paid acquisition;; no outbound send;; no deploy;; revenue =  elev0 measured (31 signups NOT revenue;; 0 checkout;; losing0 settlement;; score unchanged  58/100;; canvas-ledger-touched? false

## Next verification
1) counsel written reply (blocker since run 0038;; unblocks  75/65/62/61/51/48pt rows
2) demand signal signups  31 or checkout-start  0 (triggers standby draft 0045 for owner go/no-go;; do not implement without approval
3)) x402,: submissions still   4;; settle  0;;  3 rejections unexplained — any change is first demand signal;; if unexplained stays  3 more runs,, flag growing observability gap
4) by-source visitor gap ~1577 (organic+ads vs total)) — observe only
5)) /ipld/v1 first-probe flake — observed runs  0152/0153/0156/0158/0164(first-probe 20.9s spike;,then clean re-probes 0.037/0.106s;; preceded by  0157-0163 clean streak)。 Single isolated spike;; NOT two-in-a-row → stays observe-only;; elevate to a routing check only if 2 consecutive flaked reads (or visibly degrading latency across probes;; not yet

## exaggeration-guard
external revenue ==  0;;   31 signups NOT revenue;; x402 subm 4/settle  0 NOT revenue (cause unverified);; all deltas read live via curl + nbb HTTP  200 readings (0.18s / server pulse EXIT 0;; not synthesized;; nothing sent;; nothing deployed;; nothing fabricated