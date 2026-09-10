# Run 0166 - kotobase-stab loop - 2026-09-09 JST
# role: 1 iteration; canvas-ledger.edn NOT touched (single-writer routine; respected.

## Measured (live; two independent readings same tick
- nbb funnel-pulse: SCANNED 1; VISITORS 11057; SIGNUPS  #####31; CHECKOUTS  #####0; DELTA {visitors 1, signups 0, checkouts 0};; UNCHANGED false;; EXTERNAL-FUNNEL-CHANGE false;; SCORE unchanged;; RESULT recorded (EXIT=0
- curl /api/funnel HTTP  200 (0.176s;; visitors  11057;; signups  31;; checkouts  0
- both readings agree on the live absolute value (11057) in the same tick
- delta vs run  0165 (visitors  11014 → 11057:  **+43**, organic-only:(organic  9434 →  9477 = +43;; openai-ads  stayed  3)); signups  31 still pinned;; checkouts   0
- x402: challenges   40;; submissions   4;; rejections   4 (1 malformed-header;; 3 unexplained);; settlements   0;; settlement-rate   0;; attempt-rate    #####0.1 — stasis since run  0060
- by-source visitors: organic   9477 + openai-ads   3 =  9480 vs total  11057: gap  1577 (data-quality observe;; measured difference;; no cause assigned)); same gap as prior run
- signups by-source: organic   27 + other  1

## Stability check;; all five routes hold;; no outage
- `/`   200 (0.411s; slightly slower than run  0165's 0.153s but well within healthy window;; no 2s spike recurrence
- `/signup`  200 (0.349s;; run  0165 was 0.072s — same-axis observe;; healthy
- `/api/funnel`  200 (0.176s
- `/ipld/v1`  400 (expected — route alive;; params required;; baseline  2026-09-03)) — clean single probe this run;; no first-probe 20s spike observed
- `/ipld/`  404 (expected;; verified this run
- No endpoint down;; no 5xx;; all four documented route expectations hold

## Score→Select (WIP=1
- selected →  UNCHANGED — council written-advice return on net-kotobase legal packet;; sole remaining gate on the  75pt row (cloud-itonami  6399/6310 paid pilot to external tenants)and transitively the  65/62/61/51/48pt red rows
- draft on disk →  `runs/0045-cron-signup-activation-draft.edn` (present;; DRAFT;; NOT SENT;; carried since run  0153);; state unchanged
- reason →  SCORECARD ranking unchanged(measured  2026-08-14);; movement this window +43 organic-only visitors;; signups still  31;; checkouts   0;; x402 settlements  0 →  no row score change;; council reply is human-dependent;; sending out of scope;; WIP=1 held
- selected-action score →   58/100 (unchanged

## Bounded experiment((DRAFT only;; NOT executed/sent/deployed
- carried standby →  `runs/0045-cron-signup-activation-draft.edn` (present;; /signup activation variant:add price anchor(Secure Managed ¥19,800/mo;; only live-priced SKU per run  0033,+ one primary CTA near signup form;; zero change to pricing/checkout/legal by this bot
- trigger held →  signups remain  31 while visitors grow(+43 this run;; expected signal signups  31 or checkout-start  0 in post-variant window;; NOT fired(signups still  31;; checkout   0 this read
- recipient((for owner review only;; NOT sent)→  kotobase.net operator;; deploy = owner decision(outside no-deploy boundary

## Decision → HOLD
- no spend;; no self-purchase;; no paid acquisition;; no outbound send;; no deploy;; revenue =  0 measured (31 signups NOT revenue;; 0 checkout;; 0 settlement;; score unchanged  58/100;; canvas-ledger-touched? false

## Next verification
1) council written reply(blocker since run  0038;; unblocks  75/65/62/61/51/48pt rows
2)) demand signal signups  31 or checkout-start  0 ((triggers standby draft  0045 for owner go/no-go;; do not implement without approval
3)) x402: submissions still  4;; settle  0;; 3 rejections unexplained — any change is first demand signal;; if unexplained stays  3 more runs,, flag growing observability gap
4)) by-source visitor gap  ~1577((organic+ads vs total)) — observe only
5)) endpoint latency — / and /signup read  0.41s/0.35s this run(vs 0.07-0.15s prior;; healthy range;; no recurrence of run  0145's 2.0s res;; watch if either regresses past ~1s consistently

## exaggeration-guard
external revenue ==  0;; 31 signups NOT revenue;; x402 subm  4/settle  0 NOT revenue(cause unverified);; all deltas read live via curl + nbb HTTP  200 readings (0.176s / server pulse EXIT  0;; not synthesized;; nothing sent;; nothing deployed;; nothing fabricated