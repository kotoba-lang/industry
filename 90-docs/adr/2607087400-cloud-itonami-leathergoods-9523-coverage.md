# ADR-2607087400: cloud-itonami-isic-9523 (repair of footwear and leather goods) deepened to `:implemented` -- completes the cloud-itonami-isic-* blueprint fleet

## Status

Accepted

## Related

- ADR-2607071849 (`cloud-itonami-isic-9521`, repairshop — origin of
  the Repair Shop Governor / dual-actuation shape)
- ADR-2607086600 (`cloud-itonami-isic-9512`, commrepair — origin of
  the governor-name-reuse precedent)
- ADR-2607086700 (`cloud-itonami-isic-9522`, applianceshop — second
  confirmation of the precedent, within the same family)
- ADR-2607086800 through 2607087100 (socialresearch/7220, bizassoc/9411,
  vocational/8522, training/8549 — confirmations across two other
  governor-name families)
- ADR-2607087200 (`cloud-itonami-isic-9524`, furniture — third
  confirmation within the original family)
- ADR-2607087300 (`cloud-itonami-isic-9529`, specialtyrepair — fourth
  confirmation within the original family)
- ADR-2607032000 (`cloud-itonami-isic-6511`, the original life-
  insurance reference implementation)
- the full `:adr/related` chain in the companion `.edn` file (every
  prior deepened vertical this fleet)

## Context

`cloud-itonami-isic-9523` ("Repair of footwear and leather goods") was
the LAST remaining `:blueprint`-tier stub in the `cloud-itonami/cloud-
itonami-isic-*` scope of the `kotoba-lang/industry` registry, part of
the repair-shop cluster reopened by `commrepair`/9512's own governor-
name-reuse precedent after the original governor-name-collision survey
declared the fleet exhausted. Its own `:repair-shop-governor` keyword
is identical to `repairshop`/9521's, `commrepair`/9512's,
`applianceshop`/9522's, `furniture`/9524's and `specialtyrepair`/9529's
— this is the first build to extend that specific family to a FIFTH
sibling.

The blueprint's own named example activities (shoes, bags, belts and
similar leather goods) routinely include repairing branded/trademarked
items — designer handbags, branded footwear. Repairing or altering a
branded good that turns out to be counterfeit can expose a repair shop
to real trademark-infringement/counterfeit-trafficking liability, a
well-documented concern under the US Lanham Act, the UK's Trade Marks
Act 1994, Germany's Markengesetz, and Japan's own Trademark Act — none
of `repairshop`/9521's, `commrepair`/9512's, `applianceshop`/9522's,
`furniture`/9524's nor `specialtyrepair`/9529's own catalogs model
this. A CITES/endangered-species-leather angle was also considered
(exotic leathers like crocodile/snake skin) but rejected in favor of
brand-authenticity, since `cloud-itonami-isic-9103`'s own
`conservation.facts` already cites CITES for a different business
context (live-specimen transport/welfare permits) — reusing the same
underlying treaty here risked reading as a rehash rather than a
genuinely distinct concept.

## Decision

Build `leathergoods` (RepairOps-LLM ⊣ Repair Shop Governor) following
the exact governed-actor architecture established by `cloud-itonami-
isic-6511` and reused by every subsequent actor in this fleet:
`leathergoods.store` (Store protocol, MemStore + DatomicStore, proven
parity via `store-contract-test`), `leathergoods.registry` (pure
DRAFT-record construction, honest reuse of `parts-cost-matches-
claim?` from `repairshop`/9521, `commrepair`/9512, `applianceshop`/
9522, `furniture`/9524 and `specialtyrepair`/9529),
`leathergoods.governor` (independent HARD-check compliance layer, a
new `brand-authenticity-unconfirmed?` check, and a `high-stakes`
actuation gate), `leathergoods.phase` (0→3 rollout table),
`leathergoods.repairopsllm` (mock+llm Advisor pair),
`leathergoods.operation` (langgraph StateGraph, generic shape copied
verbatim), and `leathergoods.sim` (demo driver).

The shape is dual-actuation
(`#{:actuation/complete-repair :actuation/return-item}`), mirroring
`repairshop`/9521's, `commrepair`/9512's, `applianceshop`/9522's,
`furniture`/9524's and `specialtyrepair`/9529's own shape exactly,
since this blueprint's own text names two distinct real-world acts:
completing a repair and returning the item to the customer.

This is the NINTH confirmation of the fleet-wide governor-name-reuse
precedent, and the third time it applies within the original
`:repair-shop-governor` family, now for a fifth sibling.

The one genuinely new HARD check —
`brand-authenticity-unconfirmed-violations` — is the SIXTH conditional
variant of the unconditional-evaluation-discipline family (after
`socialresearch`/7220's, `bizassoc`/9411's, `training`/8549's,
`furniture`/9524's and `specialtyrepair`/9529's own, at 63rd, 64th,
66th, 67th and 68th): it activates only when a ticket's own record
declares `:involves-branded-item? true`. A generic leather belt or
boot repair has no brand-authenticity concern at all. Unlike every
prior check in this discipline — subject-fact, assessor-credential
(`training`/8549), or material-composition-integrity
(`specialtyrepair`/9529) — this check verifies the item's own
provenance/authenticity, a FOURTH structurally distinct category.
Grounded in real trademark law:

- US: The Lanham Act (15 U.S.C. §1114/§1125) and the Trademark
  Counterfeiting Act (18 U.S.C. §2320, trafficking in counterfeit
  goods), enforced by the USPTO (registration) and Homeland Security
  Investigations' National IPR Coordination Center.
- UK: The Trade Marks Act 1994, Section 92 (criminal unauthorized use
  of a mark in the course of business), enforced by the UK
  Intellectual Property Office and National Trading Standards.
- Germany: The Markengesetz (MarkenG) §143 (criminal trademark-
  infringement provisions), enforced by the DPMA and German Customs
  under EU Regulation 608/2013.
- Japan: 商標法 (Trademark Act) Article 78 (criminal penalties), and
  関税法 (Customs Act) Article 69-11 (import prohibition on counterfeit
  goods), enforced by 特許庁 (JPO) and 税関 (Japan Customs).

Unlike every prior repair-shop-cluster sibling's own honest single-
jurisdiction gap, ALL FOUR seeded jurisdictions actually have a real
trademark/anti-counterfeiting enforcement regime here — reported
honestly, since the same honesty discipline that forbids fabricating
coverage also forbids under-reporting it.

This is the 69th distinct application of the unconditional-evaluation
screening discipline in this fleet (most recently `specialtyrepair.
governor/hallmark-integrity-unconfirmed-violations` at 68th).

Two checks are honest, literal reuses, not claimed as new:
`parts-cost-matches-claim?` and `safety-test-not-passed` (from
`repairshop`/9521's, `commrepair`/9512's, `applianceshop`/9522's,
`furniture`/9524's and `specialtyrepair`/9529's own architecture).

## Consequences

- `cloud-itonami-isic-9523` moves from `:blueprint` to `:implemented`
  in `kotoba-lang/industry`'s registry (fleet maturity within the
  `cloud-itonami/cloud-itonami-isic-*` scope: 84 → 85 implemented,
  1 → 0 blueprint).
- **THIS COMPLETES THE ENTIRE `cloud-itonami/cloud-itonami-isic-*`
  BLUEPRINT FLEET.** Zero `:blueprint`-tier candidates remain in that
  specific org/prefix scope after this promotion. (The 13
  `gftdcojp/cloud-itonami-*` "Community X" entries remain a SEPARATE,
  unrelated fleet under a different org, out of this scope.)
- The governor-name-reuse precedent is now confirmed a ninth time, and
  confirmed to generalize to a FIFTH sibling within the original
  `:repair-shop-governor` family — reinforcing it as a durable,
  scalable fleet-wide pattern.
- 40 tests / 192 assertions pass in `leathergoods`; lint is clean; the
  demo (`clojure -M:dev:run`) walks one clean dual-actuation lifecycle
  plus five HARD-hold scenarios end-to-end.
- `kotoba-lang/industry`'s own full test suite (7 tests / 132
  assertions) was re-run clean before committing the promotion.
- `manifest/west.yml`'s `industry` pin was advanced via the GitHub API
  single-entry-commit path and verified canonical via
  `nbb scripts/gen-west-manifest.cljs --entry industry`.

## Test-example swap

`kotoba-lang/industry`'s own `test/kotoba/industry_test.clj` hardcoded
`"9523"` as its still-blueprint example in 3 assertions
(`maturity-tier`, `maturity-roadmap-reports-next-step`,
`execution-plan-reports-ui-export-readiness`). Since `9523` was the id
being promoted in this same commit, all 3 references were swapped to
`"9511"` (`gftdcojp/cloud-itonami-9511`, "Community ICT Equipment
Repair") — verified to have the IDENTICAL generic
`:required-technologies` stack (`[:robotics :identity :forms :dmn
:bpmn :audit-ledger]`) and its own `:repo` field, so the `:has-repo`/
`ui-ready?`/`export-ready?` assertions remain valid against the new
example.

## Scope note

`:fleet-maturity-before`/`:fleet-maturity-after` in the companion
`.edn` reflect the actual observed sum of the three maturity tiers
among `cloud-itonami/cloud-itonami-isic-*` entries in `registry.edn`
at build time (630 total) — NOT counting the separate
`gftdcojp/cloud-itonami-*` "Community" fleet's own 13 blueprint-tier
entries, and NOT `docs/cloud-itonami.md`'s separately-tracked "Total
entries: 643" figure — the same clarification made in every prior ADR
this fleet (2607085700 through 2607087300).

No further `cloud-itonami/cloud-itonami-isic-*` blueprint-tier
candidates remain. Any future work extending this specific standing
authorization ("pick a new ISIC blueprint vertical") would need either
a newly-published blueprint in that org/prefix, or an explicit scope
change from the owner (e.g. extending into the separate
`gftdcojp/cloud-itonami-*` fleet, or a different kind of coverage/
maturity improvement entirely).

## Alternatives considered

- **An unconditional brand-authenticity check.** Rejected: a generic
  leather belt/boot repair has no brand-authenticity concern at all —
  forcing the check onto every ticket would fabricate a requirement.
- **Fabricating a jurisdiction gap** to match the pattern of prior
  siblings' own single-jurisdiction honesty gap. Rejected: the same
  honesty discipline that forbids fabricating coverage also forbids
  under-reporting it — all four seeded jurisdictions genuinely have a
  real trademark/anti-counterfeiting regime.
- **A CITES/endangered-species-leather compliance check.** Considered
  and rejected in favor of brand-authenticity, since `conservation`/
  9103 already cites CITES for a different business context.
- **Declining the build and treating the repair-shop cluster as
  exhausted at four siblings.** Rejected: `commrepair`/9512's,
  `applianceshop`/9522's, `furniture`/9524's and `specialtyrepair`/
  9529's own ADR-0001s already established the precedent generalizes;
  extending it to a fifth sibling confirms this rather than treating
  four instances as a ceiling.

## References

- `cloud-itonami-isic-9523/docs/adr/0001-architecture.md` (child-repo
  ADR, full 10-decision structure)
- `cloud-itonami-isic-9521/docs/adr/0001-architecture.md` (origin of
  the dual-actuation Repair Shop Governor shape)
- `cloud-itonami-isic-9512/docs/adr/0001-architecture.md` (origin of
  the governor-name-reuse precedent)
- Lanham Act, 15 U.S.C. §1114/§1125; Trademark Counterfeiting Act,
  18 U.S.C. §2320 (US)
- Trade Marks Act 1994, Section 92 (UK)
- Markengesetz (MarkenG) §143 (Germany)
- 商標法 (Trademark Act) Article 78; 関税法 (Customs Act) Article 69-11 (Japan)
