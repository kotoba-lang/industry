# ADR-2607032000: cloud-itonami insurance (ISIC 65/66) + real-estate (ISIC 68) coverage push

**Status**: accepted
**Date**: 2026-07-03
**Deciders**: Jun Kawasaki

## Context

A coverage audit of `cloud-itonami` (ADR-2607011000's ISIC Rev.5 blueprint
fleet, tracked in `kotoba-lang/industry`'s registry) found:

- **Insurance** (division 65: life/non-life insurance, reinsurance, pension
  funding; division 66 group 662: risk evaluation, insurance agents/brokers,
  other insurance auxiliary): **0 of 7 relevant classes** had a published
  blueprint repo. All 7 sat at `:spec` (registry entry only), and their
  `:required-technologies` borrowed `:banking`'s capability id and
  `:operating-states` as an unedited placeholder (`:intake :onboard :transact
  :clear :settle :audit` — a banking lifecycle, not an insurance one).
- **Real estate** (division 68): `6810` (real-estate agency, own/leased
  property) already had a published blueprint repo (`cloud-itonami-L6810`,
  ADR-2607011000) but no deeper actor implementation — still `:blueprint`
  maturity, same tier as a freshly-scaffolded repo. `6820` (real estate on a
  fee or contract basis — brokerage/management for others) was `:spec` only.
- No `:insurance` capability existed in `kotoba-lang/technology`'s registry;
  the 7 insurance classes had nothing real to point `:required-technologies`
  at, which is why they borrowed `:banking`.

## Decision

### 1. New capability lib: `kotoba-lang/insurance`

A pure `.cljc` capability library (Apache-2.0), same shape as
`kotoba-lang/property`/`labor`/`retail`: policy / premium / claim /
underwriting-decision pure-data contracts + validation, a read-only
`ui.cljc` operator dashboard (no write surface), `export.cljc` (CSV/JSON),
tests. No real actuarial tables — this is a forkable scaffold an operator
fills with their own licensed actuarial data, not a source of actuarial
authority itself. Registered in `kotoba-lang/technology`'s registry as
`:insurance` (layer `:finance/insurance`).

### 2. Eight blueprint repos (`:spec` → `:blueprint`)

| ISIC | Name | Repo |
|---|---|---|
| 6511 | Life insurance | `cloud-itonami-6511` |
| 6512 | Non-life insurance | `cloud-itonami-6512` |
| 6520 | Reinsurance | `cloud-itonami-6520` |
| 6530 | Pension funding | `cloud-itonami-6530` |
| 6621 | Risk and damage evaluation | `cloud-itonami-6621` |
| 6622 | Activities of insurance agents and brokers | `cloud-itonami-6622` |
| 6629 | Other activities auxiliary to insurance and pension funding | `cloud-itonami-6629` |
| 6820 | Real estate activities on a fee or contract basis | `cloud-itonami-6820` |

Each publishes directly under the `cloud-itonami` org (public, AGPL-3.0-or-later),
per ADR-2607012100's standing note that new blueprint repos no longer need to
transit `gftdcojp` first. Each follows the existing blueprint-tier template
(`README.md` / `blueprint.edn` / `docs/business-model.md` /
`docs/operator-guide.md` / `GOVERNANCE.md` / `CONTRIBUTING.md` /
`SECURITY.md` / `CODE_OF_CONDUCT.md` / `LICENSE`), corrected per class (not
a copy-paste of an unrelated vertical's governance text — a defect found in
some earlier-batch repos, e.g. `cloud-itonami-K6419`'s `SECURITY.md` still
reads "This project handles HR and talent-management workflows").

The 7 insurance entries' `:required-technologies` swap the placeholder
`:banking` for the new `:insurance` capability, and `:operating-states`
change from the banking placeholder to an insurance lifecycle: `:intake
:underwrite :bind :endorse :claim :settle :audit` (loss-adjustment/brokerage
classes use the subset that applies to their actual activity).

### 3. `6810` deepened to `:implemented`

`cloud-itonami-L6810` (real-estate agency) is deepened from blueprint-only
to a real actor, same pattern as `cloud-itonami-M6910` (company
incorporation) and `cloud-itonami-6310` (HR/talent) — the 3rd
`:implemented` entry in the registry:

- **Realtor-LLM** (sealed advisor) proposes listing intake, buyer/tenant
  matching, offer drafting and closing-readiness checks; never itself
  executes a transaction.
- **RealtorGovernor** (independent) gates on spec-basis (per-jurisdiction
  disclosure/title requirements, citation-backed), fraud/sanctions hold,
  document-complete, confidence floor, and a hard **actuation gate**.
- **Actuation invariant**: title transfer and escrow/fund disbursement are
  never a member of any phase's `:auto` set and always escalate to human
  sign-off in the governor's high-stakes check — the same two-independent-
  layers shape as `formation.phase`/`formation.governor` in `M6910`.
- `realty.store` (MemStore + append-only audit ledger), `realty.registry`
  (listing/offer/closing draft records), `realty.facts` (per-jurisdiction
  disclosure/title requirement catalog with citations, honest coverage
  reporting — seed a handful of jurisdictions, not a global claim),
  `realty.phase` (0→3), `realty.operation` (langgraph-clj StateGraph).

### 4. Registry hygiene

`kotoba-lang/industry`'s registry entries for all 8 classes get their
`:repo` URL corrected from the stale `gftdcojp/cloud-itonami-*` placeholder
to the actual `cloud-itonami/cloud-itonami-*` URL now that the repo exists,
and `6810`'s `:maturity` flips from `:blueprint` to `:implemented`.

## Consequences

- (+) Insurance coverage: 0/7 blueprinted → 7/7 blueprinted (still 0
  `:implemented` — a blueprint repo is a scaffold, not a governed actor;
  see ADR-2607011000's three-tier maturity model).
- (+) Real estate coverage: 1/2 blueprinted (0 implemented) → 2/2
  blueprinted, 1/2 (`6810`) implemented.
- (+) `kotoba-lang/technology` gains a real `:insurance` capability instead
  of insurance classes silently borrowing `:banking`'s id.
- (+) Fleet-wide maturity counts move from 1 implemented / 25 blueprint /
  617 spec to 2 implemented / 33 blueprint / 608 spec (out of 643 total).
- (-) The other insurance-adjacent classes still at `:spec`
  (central banking 6411, holding companies 6420/642, financial leasing
  6491/6492, fund management 663/6630, financial market administration
  6611, securities brokerage 6612) are unchanged — out of this ADR's scope
  per the user's explicit scoping.
- Manifest registration: `kotoba-lang/insurance` is added to
  `manifest/repos.edn`/`west.yml` (a real lib, same as `property`/`labor`).
  The 8 `cloud-itonami-*` blueprint/actor repos stay unregistered
  (standalone), per the existing convention (ADR-2607011000/2607012100).

## Addendum 1 (2026-07-07, owner-directed): `cloud-itonami-isic-6512` deepened to `:implemented`

After the three-actor VC-fund system (`cloud-itonami-isic-6499`/`6430`/
`6630`, ADR-2607061700) reached its own documented gaps' saturation
point, the owner was asked where the recurring coverage-improvement loop
should look next and chose "pick a new ISIC blueprint vertical." `6512`
(non-life/property-casualty insurance) was selected as the natural next
candidate: its sibling `6511` (life insurance) was already `:implemented`
(the same insurance-batch this ADR published), giving a close
architectural reference to model against, and `6512` still sat at
`:blueprint` with zero code.

- `cloud-itonami-isic-6512` gains **Underwriter-LLM ⊣ Non-Life Insurance
  Governor** -- `casualty.*` namespaces, modeled closely on `6511`'s
  `underwriting.*`: the same Store/Registry/Governor/Phase/Advisor/
  Operation/Sim shape, the same langgraph-clj StateGraph (copied
  verbatim, since that shape is fully generic), the same "no fabricated
  international numbering standard" and "honest, non-exhaustive
  per-jurisdiction spec-basis catalog" discipline.
- **A genuinely new lifecycle beyond `6511`'s own scope**: property/
  casualty insurance is claims-driven (a policyholder files a claim
  citing a loss event; life insurance has no analog -- it pays out once,
  at maturity or death). `6512` adds `:claim/file` (HARD-gated on the
  referenced policy actually being bound) and `:claim/settle` (a SECOND
  actuation event alongside `:policy/bind`, independently checked
  against the policy's own coverage limit and double-settlement-
  protected) -- checks `6511` has no concept of at all.
- A real bug (an overly-broad op-guard on the ported `sanctions-
  violations` check, silently preventing a `:kyc/screen` proposal from
  ever being HARD-held on its own sanctions finding) was caught by
  running the demo and reading the actual audit-ledger output, not by
  trusting lint/compile success alone -- documented in `cloud-itonami-
  isic-6512`'s own ADR-0001.
- `kotoba-lang/industry`'s registry: `:maturity :blueprint` →
  `:implemented` for `"6512"`, fleet-wide maturity counts move from 7
  implemented / 90 blueprint / 546 spec to 8 implemented / 89 blueprint /
  546 spec (out of 643 total), `test/kotoba/industry_test.clj`'s
  `maturity-summary` assertion updated to match.
- `test/casualty/*` -- 34 tests / 172 assertions, lint-clean, demo
  (`clojure -M:dev:run`) runs end-to-end: one clean policy-bind lifecycle
  + one clean claim-settlement lifecycle (both escalate → approve →
  commit) plus six HARD-hold cases (sanctions hit, no spec-basis, claim
  against an unbound policy, claim exceeding the policy's coverage
  limit, a nonexistent claim, a double-settlement) that never reach a
  human at all. See `cloud-itonami-isic-6512`'s own ADR-0001 for the full
  design.

## Addendum 2 (2026-07-07, owner-directed): `cloud-itonami-isic-6621` deepened to `:implemented`

Continuing the SAME "pick a new ISIC blueprint vertical" direction that
produced Addendum 1's `6512`, `6621` (risk and damage evaluation --
independent loss adjusting) was selected next: it is another insurance-
adjacent class from THIS ADR's own original 8-repo batch, its blueprint
already named the exact actor shape to build (Adjuster-LLM ⊣ Loss
Adjustment Governor), and it offered a genuinely distinctive HARD check
neither `6511` nor `6512` has any concept of.

- `cloud-itonami-isic-6621` gains **Adjuster-LLM ⊣ Loss Adjustment
  Governor** -- `adjustment.*` namespaces, modeled closely on
  `6511`/`6512`'s Store/Registry/Governor/Phase/Advisor/Operation/Sim
  shape and the SAME generic langgraph-clj StateGraph.
- **The distinctive check**: independent loss adjustment's entire
  business premise is INDEPENDENCE -- the adjuster evaluating a matter
  must have no undisclosed conflict of interest with the party
  requesting the evaluation. `adjustment.governor`'s `conflict-
  violations` screens the ASSIGNED ADJUSTER against the matter, not a
  party against a sanctions blocklist (`6511`/`6512`'s KYC check) --
  same code shape, different and equally load-bearing semantics.
  `:valuation/finalize` is the ONE real actuation event (unlike `6512`'s
  two), matching `6511`'s original single-actuation-event scope.
- **A lesson applied, not just documented**: `6512`'s own ADR recorded a
  real bug (an overly-broad op-guard on its ported sanctions-violations
  check, caught by reading the demo's actual ledger output). `6621`'s
  `conflict-violations` was written from scratch with that lesson
  already in hand (the `hit-in-proposal?` branch evaluated
  UNCONDITIONALLY, not scoped to a specific op) -- the demo and full
  test suite passed on the FIRST run with no equivalent bug, a direct
  payoff of writing down what went wrong rather than only fixing it in
  place.
- `kotoba-lang/industry`'s registry: `:maturity :blueprint` →
  `:implemented` for `"6621"`, fleet-wide maturity counts move from 8
  implemented / 89 blueprint / 546 spec to 9 implemented / 88 blueprint /
  546 spec (out of 643 total), `test/kotoba/industry_test.clj`'s
  `maturity-summary` assertion updated to match.
- `test/adjustment/*` -- 24 tests / 106 assertions, lint-clean, demo
  (`clojure -M:dev:run`) runs end-to-end: one clean intake-through-
  finalization lifecycle (escalate → approve → commit) plus three
  HARD-hold cases (conflict of interest, no spec-basis, evidence
  incomplete) that never reach a human at all. See `cloud-itonami-isic-
  6621`'s own ADR-0001 for the full design.

## Addendum 3 (2026-07-07, owner-directed): `cloud-itonami-isic-6622` deepened to `:implemented`

Continuing the SAME "pick a new ISIC blueprint vertical" direction that
produced Addenda 1-2, `6622` (activities of insurance agents and
brokers) was selected next: the fourth and, for the time being, last
insurance-adjacent class from THIS ADR's own original 8-repo batch to
receive this treatment. Its blueprint already named the exact actor
shape to build (Broker-LLM ⊣ Insurance Intermediation Governor) and its
own Trust Controls already named an undisclosed-conflict-of-interest
check as central to the business.

- `cloud-itonami-isic-6622` gains **Broker-LLM ⊣ Insurance
  Intermediation Governor** -- `intermediation.*` namespaces, modeled
  closely on `6511`/`6512`/`6621`'s Store/Registry/Governor/Phase/
  Advisor/Operation/Sim shape and the SAME generic langgraph-clj
  StateGraph. TWO actuation events (`:placement/bind`, `:commission/
  book`), matching `6512`'s dual-actuation shape rather than
  `6511`'s/`6621`'s single-actuation one.
- **A genuinely new check, not borrowed from any sibling**:
  `insufficient-quotes-violations` verifies the broker actually compared
  at least two insurers' quotes before binding -- a best-interest/
  shopping-duty check none of `6511`/`6512`/`6621` have any analog to
  (their "document-complete"/"evidence-incomplete" checks verify
  paperwork completeness, not that genuine options were compared).
- **The conflict-of-interest check reused `6621`'s already-corrected
  shape a SECOND time** (unconditional `hit-in-proposal?`, on-file check
  scoped to the real acts) -- reinforcing that this is now a load-bearing
  convention for this actor family, not a one-off fix.
- **A DIFFERENT real bug, caught the same way**: `placement-not-bound-
  violations` initially checked `:status :bound` directly, which broke
  on the double-booking demo scenario once a successful commission
  booking legitimately advances status to `:commission-booked`
  (re-triggering the "not bound" check alongside the correct "double-
  booking" one). Fixed by checking `:placement-number` (set once at
  binding, never cleared) instead. Caught by running the demo and
  reading the actual ledger output -- the SAME verification discipline
  `6512`'s and `6621`'s own ADRs established, applied to a genuinely NEW
  kind of bug (a status-lifecycle assumption, not an op-scoping guard),
  showing the discipline generalizes rather than only catching the one
  specific mistake it was first written down for.
- `kotoba-lang/industry`'s registry: `:maturity :blueprint` →
  `:implemented` for `"6622"`, fleet-wide maturity counts move from 9
  implemented / 88 blueprint / 546 spec to 10 implemented / 87 blueprint /
  546 spec (out of 643 total), `test/kotoba/industry_test.clj`'s
  `maturity-summary` assertion updated to match.
- `test/intermediation/*` -- 33 tests / 169 assertions, lint-clean, demo
  (`clojure -M:dev:run`) runs end-to-end: one clean placement-bind
  lifecycle + one clean commission-booking lifecycle (both escalate →
  approve → commit) plus six HARD-hold cases (conflict of interest, no
  spec-basis, insufficient quotes, placement not bound, commission rate
  exceeding cap, double-booking) that never reach a human at all. See
  `cloud-itonami-isic-6622`'s own ADR-0001 for the full design.

## Addendum 4 (2026-07-07, owner-directed): `cloud-itonami-isic-6629` deepened to `:implemented`

Continuing the SAME "pick a new ISIC blueprint vertical" direction that
produced Addenda 1-3, `6629` (other activities auxiliary to insurance
and pension funding) was selected next -- the fifth insurance-adjacent
class from THIS ADR's own original 8-repo batch to receive this
treatment, and the one bundling two genuinely distinct activities
(outsourced claims administration, and marine general-average
adjustment) under a single governed workflow.

- `cloud-itonami-isic-6629` gains **Claims-LLM ⊣ Insurance Auxiliary
  Governor** -- `auxiliary.*` namespaces, modeled closely on
  `6511`/`6512`/`6621`/`6622`'s Store/Registry/Governor/Phase/Advisor/
  Operation/Sim shape and the SAME generic langgraph-clj StateGraph. A
  single actuation op (`:recommendation/finalize`, matching `6511`'s/
  `6621`'s single-actuation shape) serves BOTH bundled activities,
  dispatching its HARD checks by the case's own `:case-type` rather than
  duplicating StateGraph wiring per activity.
- **A genuinely different KIND of check, not another party screen**:
  `apportionment-mismatch-violations` independently recomputes a
  general-average apportionment (pro-rata by value-at-risk share) and
  compares it against the case's claimed figures -- reusing the
  CROSS-REPO "never trust a claimed number, independently re-derive it"
  discipline `cloud-itonami-isic-6499`/`6430` established, applied here
  WITHIN one repo. Unlike `6511`/`6512`'s sanctions checks and `6621`'s/
  `6622`'s conflict-of-interest checks (all party-screening), this is
  pure arithmetic verification -- the actor deliberately has NO party/
  conflict-of-interest concept at all, since none applies to its core
  failure mode.
- `kotoba-lang/industry`'s registry: `:maturity :blueprint` →
  `:implemented` for `"6629"`, fleet-wide maturity counts move from 10
  implemented / 87 blueprint / 546 spec to 11 implemented / 86 blueprint /
  546 spec (out of 643 total), `test/kotoba/industry_test.clj`'s
  `maturity-summary` assertion updated to match.
- `test/auxiliary/*` -- 30 tests / 125 assertions, lint-clean, demo
  (`clojure -M:dev:run`) runs end-to-end: one clean average-adjustment
  finalization lifecycle (escalate → approve → commit) plus three
  HARD-hold cases (no spec-basis, evidence incomplete, apportionment
  mismatch) that never reach a human at all -- all correct on the FIRST
  demo run, since this check is new territory rather than a ported
  pattern with a known failure mode already lurking in it (unlike
  `6512`'s and `6622`'s own ADRs, which each record a real bug caught
  during demo verification). See `cloud-itonami-isic-6629`'s own
  ADR-0001 for the full design.

Of the 7 insurance classes (division 65/662) this ADR originally
published, 5 are now `:implemented` (`6511` life insurance, implemented
before this addendum sequence began; `6512` non-life, `6621` loss
adjustment, `6622` intermediation and `6629` insurance-auxiliary
services, implemented by Addenda 1-4). TWO remain at `:blueprint` from
the original 8-repo batch -- `6520` (reinsurance) and `6530` (pension
funding) -- plus `6820` (real estate on a fee/contract basis, a separate
division entirely). All three are candidates for a future addendum, not
silently forgotten.

## Addendum 5 (2026-07-07, owner-directed): `cloud-itonami-isic-6520` deepened to `:implemented`

Continuing the SAME "pick a new ISIC blueprint vertical" direction that
produced Addenda 1-4, `6520` (reinsurance) was selected next -- the
sixth insurance-adjacent class from THIS ADR's own original 8-repo
batch to receive this treatment.

- `cloud-itonami-isic-6520` gains **Treaty-LLM ⊣ Reinsurance Governor**
  -- `reinsurance.*` namespaces, modeled closely on `6511`/`6512`/
  `6621`/`6622`/`6629`'s Store/Registry/Governor/Phase/Advisor/
  Operation/Sim shape and the SAME generic langgraph-clj StateGraph.
  Two entities (`treaties`, `recoveries`) mirror `6512`'s policy/claim
  lifecycle shape almost exactly: `:recovery/file` (auto-eligible once
  the treaty is bound, no capital risk) → `:recovery/pay` (always
  human-gated actuation), the same shape as `:claim/file` → `:claim/
  settle`. Dual actuation (`:treaty/bind`, `:recovery/pay`), matching
  `6512`'s/`6622`'s dual-actuation shape, not `6511`'s/`6621`'s/`6629`'s
  single-actuation one.
- **A genuinely different KIND of check, reusing `6629`'s arithmetic-
  verification pattern on a NEW formula**: `recovery-calculation-
  mismatch-violations` independently recomputes a claims-recovery
  amount via the treaty's OWN quota-share (percentage-of-loss, capped
  at an aggregate limit) or excess-of-loss (retention/layer) formula
  and compares it against the recovery request's claimed figure --
  reusing the "never trust a claimed number, independently re-derive
  it" discipline `cloud-itonami-isic-6629`'s `apportionment-mismatch-
  violations` established for general-average apportionment, applied
  here to a different well-known formula (reinsurance treaty math).
  Like `6629`, this actor deliberately has NO party/conflict-of-
  interest concept at all, since none applies to its core failure mode
  -- a ceding insurer is a named contractual counterparty, not a
  screened individual.
- **A lesson from `6622` applied by REASONING, not by copying a fix
  defensively**: `6622`'s ADR records a real bug where checking
  `:status :bound` directly broke because a placement's status
  legitimately advances PAST `:bound`. `6520`'s `treaty-not-bound-
  violations` checks `:status :bound` directly too -- but SAFELY, since
  a treaty's status never advances past `:bound` (recovery filing/
  payment only ever change the recovery's own status). This reasoning
  is written into the check's own docstring and `cloud-itonami-isic-
  6520`'s own ADR-0001, rather than defensively copying `6622`'s fix
  where it isn't needed.
- `kotoba-lang/industry`'s registry: `:maturity :blueprint` →
  `:implemented` for `"6520"`, fleet-wide maturity counts move from 11
  implemented / 86 blueprint / 546 spec to 12 implemented / 85 blueprint /
  546 spec (out of 643 total), `test/kotoba/industry_test.clj`'s
  `maturity-summary` assertion updated to match.
- `test/reinsurance/*` -- 36 tests / 171 assertions, lint-clean, demo
  (`clojure -M:dev:run`) runs end-to-end: two clean lifecycles (a
  quota-share treaty bind + recovery-payment cycle, and a separate
  excess-of-loss treaty bind) plus five HARD-hold cases (no spec-basis,
  recovery filed against an unbound treaty, a recovery-calculation
  mismatch, a nonexistent recovery, a double payment) that never reach
  a human at all -- all correct on the FIRST demo run, since this
  build's status-lifecycle risk was reasoned through up front rather
  than discovered by a failing demo (unlike `6512`'s and `6622`'s own
  ADRs, which each record a real bug caught during demo verification).
  See `cloud-itonami-isic-6520`'s own ADR-0001 for the full design.

Of the 7 insurance classes (division 65/662) this ADR originally
published, 6 are now `:implemented` (`6511` life insurance, implemented
before this addendum sequence began; `6512` non-life, `6621` loss
adjustment, `6622` intermediation, `6629` insurance-auxiliary services
and `6520` reinsurance, implemented by Addenda 1-5). ONE remains at
`:blueprint` from the original 8-repo batch -- `6530` (pension funding)
-- plus `6820` (real estate on a fee/contract basis, a separate
division entirely). Both are candidates for a future addendum, not
silently forgotten.

## Addendum 6 (2026-07-07, owner-directed): `cloud-itonami-isic-6530` deepened to `:implemented`

Continuing the SAME "pick a new ISIC blueprint vertical" direction that
produced Addenda 1-5, `6530` (pension funding) was selected next -- the
seventh and LAST insurance class from THIS ADR's own original 8-repo
batch to receive this treatment.

- `cloud-itonami-isic-6530` gains **Pension-LLM ⊣ Pension Governor** --
  `pension.*` namespaces, modeled closely on `6511`/`6512`/`6621`/
  `6622`/`6629`/`6520`'s Store/Registry/Governor/Phase/Advisor/
  Operation/Sim shape and the SAME generic langgraph-clj StateGraph.
  Unlike every insurance-adjacent sibling, the MEMBER entity folds the
  role a separate policyholder/party record plays elsewhere -- there is
  no distinct insured-property or counterparty to track apart from the
  member themselves.
- **A CAP check blending two established patterns**:
  `disbursement-exceeds-entitlement-violations` independently recomputes
  a member's maximum entitlement (a lump-sum-remaining-balance formula,
  or a period-certain annuity-installment division) and refuses if a
  requested amount EXCEEDS it -- the "never trust a claimed number,
  independently re-derive it" discipline `6629`'s/`6520`'s checks
  established, but expressed as an upper-bound cap (like `casualty.
  governor/claim-exceeds-coverage-violations`) rather than an exact-
  match. The member's own running `:disbursed-to-date` balance advances
  after every lump-sum payment commits, verified directly in the demo:
  a second disbursement against the same member correctly holds against
  the REMAINING entitlement, not the original balance.
- **A genuinely NEW kind of actuation, not another one-time bind/pay**:
  `:payout/continue` authorizes a benefit payout stream to CONTINUE past
  a periodic proof-of-life check -- explicitly RECURRING, with
  deliberately NO double-guard (unlike every prior sibling's one-time
  second-actuation event, each protected by a double-payment/double-
  booking check). Each proof-of-life cycle is its own independent
  authorization event, by domain design.
- **A real bug caught during demo verification -- a NEW one, arising
  from a check combination no sibling had tried**: `spec-basis-
  violations` (scoped to `:jurisdiction/assess` + `:disbursement/pay`,
  mirroring `casualty.governor`'s `:policy/bind` scoping) spuriously
  co-fired with `disbursement-missing-violations` on a nonexistent
  disbursement, since a not-found disbursement's proposal naturally has
  empty `:cites` -- muddying the audit trail with a rule that didn't
  describe what actually went wrong (the disposition was always
  correctly `:hold` either way -- an audit-trail-accuracy bug, not a
  governance failure). Fixed by guarding the `:disbursement/pay` branch
  on the disbursement actually existing first. Unlike `6512`'s and
  `6622`'s bugs (each a single check misfiring alone), this is the
  FIRST bug in this fleet caused by bundling MORE HARD checks onto ONE
  actuation op than any predecessor did.
- `kotoba-lang/industry`'s registry: `:maturity :blueprint` →
  `:implemented` for `"6530"`, fleet-wide maturity counts move from 12
  implemented / 85 blueprint / 546 spec to 13 implemented / 84 blueprint /
  546 spec (out of 643 total), `test/kotoba/industry_test.clj`'s
  `maturity-summary` assertion updated to match.
- `test/pension/*` -- 40 tests / 195 assertions, lint-clean, demo
  (`clojure -M:dev:run`) runs end-to-end: two clean lifecycles (a
  disbursement-payment cycle, and a proof-of-life-screened payout-
  continuation cycle) plus seven HARD-hold cases (no spec-basis, an
  unvested member's disbursement filing, an entitlement-exceeding
  disbursement, a failed proof-of-life check, a continuation attempt
  for a never-in-payout member, a nonexistent disbursement, a double
  payment) that never reach a human at all -- all correct on the
  SECOND demo run, after the spec-basis/missing-entity overlap above
  was found and fixed. See `cloud-itonami-isic-6530`'s own ADR-0001 for
  the full design.

Of the 7 insurance classes (division 65/662) this ADR originally
published, ALL SEVEN are now `:implemented` (`6511` life insurance,
implemented before this addendum sequence began; `6512` non-life,
`6621` loss adjustment, `6622` intermediation, `6629` insurance-
auxiliary services, `6520` reinsurance and `6530` pension funding,
implemented by Addenda 1-6). This closes out every insurance class this
ADR originally published at `:blueprint`. ONE candidate remains from
the original 8-repo batch outside insurance proper -- `6820` (real
estate on a fee/contract basis, ISIC division 68, a separate division
entirely) -- a candidate for a future addendum, not silently forgotten.

## Addendum 7 (2026-07-07, owner-directed): `cloud-itonami-isic-6820` deepened to `:implemented` -- closes the original 8-repo batch

Continuing the SAME "pick a new ISIC blueprint vertical" direction that
produced Addenda 1-6, `6820` (real estate on a fee or contract basis)
was selected next -- the LAST remaining candidate from THIS ADR's
original 8-repo batch, and the FIRST actor in this whole fleet outside
the insurance division (65/66).

- `cloud-itonami-isic-6820` gains **Realty-Fee-LLM ⊣ Real-Estate
  Fee-Services Governor** -- `realty.*` namespaces, modeled closely on
  `6511`/`6512`/`6621`/`6622`/`6629`/`6520`/`6530`'s Store/Registry/
  Governor/Phase/Advisor/Operation/Sim shape and the SAME generic
  langgraph-clj StateGraph. Two lifecycle shapes coexist: `:fee/file` →
  `:fee/pay` (a TWO-step lifecycle mirroring `casualty`'s/`pension`'s
  claim/disbursement shape) and `:contract/execute` (a ONE-step
  lifecycle acting directly on a property's inline pending contract,
  mirroring `reinsurance`'s `:treaty/bind` shape) -- both in one actor.
- **Two DIFFERENT check shapes in ONE governor, a new combination**:
  `fee-calculation-mismatch-violations` reuses the EXACT-MATCH
  independent-recompute pattern (`6629`'s/`6520`'s discipline, applied
  to a fourth domain-specific formula: fixed-percentage-of-rent), while
  `contract-exceeds-authorization-violations` reuses the STATIC-CAP
  pattern (`casualty.governor/claim-exceeds-coverage-violations`'s
  shape, a cap against a stored constant). No sibling combines a
  computed exact-match AND a static cap in the SAME governor; `6530`
  blended them into ONE check instead of keeping two separate ones.
- **A new guard mechanism**: a pending contract lives INLINE on the
  property record (no separate `contracts` collection, like `6629`'s/
  `6530`'s "no unused collection" judgment), and executing it CLEARS
  the field -- so a repeat execution attempt naturally falls into the
  SAME `contract-missing` check a never-had-one property would also
  trigger. One accurate check for two occurrences of the same
  underlying fact, not a separate double-execution guard.
- **`6530`'s lesson applied proactively, before it could recur**:
  `spec-basis-violations` is scoped to an op (`:fee/pay`) that ALSO
  carries a "missing entity" check (`fee-missing-violations`) -- the
  exact shape that caused `6530`'s audit-trail-accuracy bug. This
  build's `spec-basis-violations` guards on the fee actually existing
  FIRST, from the very first draft, and passed the full demo clean on
  the first attempt for this specific concern.
- **Two real bugs WERE still caught, of two NEW kinds for this
  fleet**: (a) during BUILD, not demo -- the Datomic `property->tx`
  initially wrote `:pending-contract` unconditionally, which would
  have silently clobbered an existing pending contract on any partial
  upsert never mentioning the key; caught by re-reading the diff,
  fixed via a `contains?` guard matching `MemStore`'s `merge`
  semantics. (b) during demo verification -- the sim's own intake step
  copied `casualty.sim`'s `:status :ready` patch verbatim, but unlike
  `casualty` (policies start at `:intake`, later bound), this actor's
  properties start already `:under-management` with no separate
  property-binding actuation -- the patch clobbered the seed status,
  and the governor CORRECTLY held every subsequent fee scenario; the
  bug was in the demo's storytelling, not the governance logic. Fixed
  by patching a harmless already-true field instead.
- `kotoba-lang/industry`'s registry: `:maturity :blueprint` →
  `:implemented` for `"6820"`, fleet-wide maturity counts move from 13
  implemented / 84 blueprint / 546 spec to 14 implemented / 83 blueprint /
  546 spec (out of 643 total), `test/kotoba/industry_test.clj`'s
  `maturity-summary` assertion updated to match.
- `test/realty/*` -- 39 tests / 187 assertions, lint-clean, demo
  (`clojure -M:dev:run`) runs end-to-end: two clean lifecycles (a fee-
  payment cycle, and a contract-execution cycle) plus seven HARD-hold
  cases (no spec-basis, a fee filed for a property not under
  management, a fee-calculation mismatch, a contract exceeding
  authorization, a contract-execution attempt with no pending
  contract, a nonexistent fee, a double payment) that never reach a
  human at all -- all correct after the two build-time/demo-time fixes
  above. See `cloud-itonami-isic-6820`'s own ADR-0001 for the full
  design.

This closes the ENTIRE original 8-repo batch from this ADR: all 7
insurance classes (`6511`/`6512`/`6621`/`6622`/`6629`/`6520`/`6530`)
plus `6820` (real estate) are now `:implemented`. No candidates remain
from this ADR's original scope -- future "pick a new ISIC blueprint
vertical" work moves to a DIFFERENT ISIC class outside this ADR's
original batch entirely.

## References

- ADR-2607011000 (cloud-itonami robotics premise + ISIC 21/21 section
  coverage; three-tier maturity model; blueprint-repo template)
- ADR-2607012100 (cloud-itonami org split; new blueprints publish directly
  under `cloud-itonami`)
- ADR-2607031500 (`cloud-itonami-M6910`; Registrar-LLM ⊣ RegistrarGovernor
  pattern this ADR's `6810` deepening mirrors)
- `kotoba-lang/industry/docs/cloud-itonami.md` (maturity tier definitions)
- `kotoba-lang/property` (capability-lib template `kotoba-lang/insurance`
  follows)
