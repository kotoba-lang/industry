# ADR-2607087300: cloud-itonami-isic-9529 (repair of other personal and household goods) deepened to `:implemented`

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
- ADR-2607032000 (`cloud-itonami-isic-6511`, the original life-
  insurance reference implementation)
- the full `:adr/related` chain in the companion `.edn` file (every
  prior deepened vertical this fleet)

## Context

`cloud-itonami-isic-9529` ("Repair of other personal and household
goods", e.g. watches, jewelry, bicycles) was a `:blueprint`-tier stub
in the `kotoba-lang/industry` registry, part of the repair-shop
cluster reopened by `commrepair`/9512's own governor-name-reuse
precedent after the original governor-name-collision survey declared
the fleet exhausted. Its own `:repair-shop-governor` keyword is
identical to `repairshop`/9521's, `commrepair`/9512's, `applianceshop`/
9522's and `furniture`/9524's — this is the first build to extend that
specific family to a FOURTH sibling.

Of the remaining repair-shop candidates (9523: footwear/leather goods;
9529: other personal/household goods), 9529 stood out for having the
strongest, most concrete regulatory hook grounded in its own named
example activities: watch/jewelry/bicycle repair routinely includes
jewelry repair/resizing/soldering work, and several major
jurisdictions (UK, US, Germany) require precious-metal (gold/silver/
platinum) articles above a weight threshold to carry an authentic
hallmark/quality mark, with a mandatory statutory hallmarking/assay-
office regime independent of general consumer-product-safety law —
the UK's Hallmarking Act 1973 (administered by four statutory UK Assay
Offices tracing back centuries) is one of the oldest, most well-
documented consumer-protection-through-metal-integrity regimes in the
world. None of `repairshop`/9521's, `commrepair`/9512's,
`applianceshop`/9522's nor `furniture`/9524's own catalogs model this
concern.

## Decision

Build `specialtyrepair` (RepairOps-LLM ⊣ Repair Shop Governor)
following the exact governed-actor architecture established by
`cloud-itonami-isic-6511` and reused by every subsequent actor in this
fleet: `specialtyrepair.store` (Store protocol, MemStore +
DatomicStore, proven parity via `store-contract-test`),
`specialtyrepair.registry` (pure DRAFT-record construction, honest
reuse of `parts-cost-matches-claim?` from `repairshop`/9521,
`commrepair`/9512, `applianceshop`/9522 and `furniture`/9524),
`specialtyrepair.governor` (independent HARD-check compliance layer, a
new `hallmark-integrity-unconfirmed?` check, and a `high-stakes`
actuation gate), `specialtyrepair.phase` (0→3 rollout table),
`specialtyrepair.repairopsllm` (mock+llm Advisor pair),
`specialtyrepair.operation` (langgraph StateGraph, generic shape
copied verbatim), and `specialtyrepair.sim` (demo driver).

The shape is dual-actuation
(`#{:actuation/complete-repair :actuation/return-item}`), mirroring
`repairshop`/9521's, `commrepair`/9512's, `applianceshop`/9522's and
`furniture`/9524's own shape exactly, since this blueprint's own text
names two distinct real-world acts: completing a repair and returning
the item to the customer.

This is the EIGHTH confirmation of the fleet-wide governor-name-reuse
precedent, and the second time it applies within the original
`:repair-shop-governor` family, now for a fourth sibling.

The one genuinely new HARD check —
`hallmark-integrity-unconfirmed-violations` — is the FIFTH conditional
variant of the unconditional-evaluation-discipline family (after
`socialresearch`/7220's, `bizassoc`/9411's, `training`/8549's and
`furniture`/9524's own, at 63rd, 64th, 66th and 67th): it activates
only when a ticket's own record declares `:involves-precious-metal-
work? true`. A bicycle-brake or watch-battery repair has no hallmark-
integrity concern at all. Unlike every prior check in this discipline
— which verifies either a fact about the subject being acted upon (a
study, a position, a ticket) or, as `training`/8549's own
`instructor-license-unconfirmed?` first established, a fact about the
assessor/professional performing an act — this check verifies a fact
about the MATERIAL/COMPOSITION integrity of the item itself, a third,
structurally distinct category this discipline has not exercised
before. Grounded in real precious-metal-hallmark-integrity law:

- UK: The Hallmarking Act 1973, administered by the British
  Hallmarking Council and the four statutory UK Assay Offices (London,
  Birmingham, Sheffield, Edinburgh).
- US: The National Stamping Act (15 U.S.C. §297) and the FTC Guides
  for the Jewelry, Precious Metals, and Pewter Industries
  (16 C.F.R. Part 23), enforced by the Federal Trade Commission.
- Germany: The Feingehaltsgesetz (Gesetz über den Feingehalt der
  Gold- und Silberwaren), enforced by regional Eichämter /
  Edelmetall-Kontrollstellen.

Japan is honestly seeded WITHOUT a hallmark-integrity sub-citation —
it has no mandatory statutory hallmarking/assay-office regime in this
R0 catalog (precious-metal marking in Japan is largely voluntary/
JIS-standard-based), the same honesty discipline `bizassoc`/9411's own
lobbying-registration check and `furniture`/9524's own flammability-
compliance check established for other jurisdiction gaps.

This is the 68th distinct application of the unconditional-evaluation
screening discipline in this fleet (most recently `furniture.governor/
flammability-compliance-unconfirmed-violations` at 67th).

Two checks are honest, literal reuses, not claimed as new:
`parts-cost-matches-claim?` and `safety-test-not-passed` (from
`repairshop`/9521's, `commrepair`/9512's, `applianceshop`/9522's and
`furniture`/9524's own architecture).

## Consequences

- `cloud-itonami-isic-9529` moves from `:blueprint` to `:implemented`
  in `kotoba-lang/industry`'s registry (fleet maturity: 83 → 84
  implemented, 2 → 1 blueprint).
- The governor-name-reuse precedent is now confirmed an eighth time,
  and confirmed to generalize to a FOURTH sibling within the original
  `:repair-shop-governor` family — reinforcing it as a durable,
  scalable fleet-wide pattern.
- 41 tests / 191 assertions pass in `specialtyrepair`; lint is clean;
  the demo (`clojure -M:dev:run`) walks one clean dual-actuation
  lifecycle plus five HARD-hold scenarios end-to-end.
- `kotoba-lang/industry`'s own full test suite (7 tests / 131
  assertions) was re-run clean before committing the promotion.
- `manifest/west.yml`'s `industry` pin was advanced via the GitHub API
  single-entry-commit path and verified canonical via
  `nbb scripts/gen-west-manifest.cljs --entry industry`.

## Scope note

`:fleet-maturity-before`/`:fleet-maturity-after` in the companion
`.edn` reflect the actual observed sum of the three maturity tiers in
`registry.edn` at build time (630 total), not
`docs/cloud-itonami.md`'s separately-tracked "Total entries: 643"
figure — the same clarification made in every prior ADR this fleet
(2607085700 through 2607087200).

Remaining `:blueprint`-tier candidate after this promotion: 9523
(repair of footwear and leather goods), still requiring its own
genuinely differentiated, well-grounded check.

## Alternatives considered

- **An unconditional hallmark-integrity check.** Rejected: a bicycle-
  brake or watch-battery repair has no hallmark-integrity concern at
  all — forcing the check onto every ticket would fabricate a
  requirement.
- **Fabricating a Japanese hallmark-integrity citation** to keep 4-of-4
  jurisdiction coverage. Rejected: the same honesty discipline this
  fleet has applied consistently (most recently `furniture`/9524's own
  flammability-compliance gap for Japan) forbids inventing a
  regulatory regime that does not exist.
- **Framing the new check as another assessor-credential check**,
  reusing `training`/8549's own structural category. Rejected: the
  hallmark-integrity concern is about the item's own material
  composition/marking, not about any professional's license or
  credential — a genuinely distinct third structural category.
- **Declining the build and treating the repair-shop cluster as
  exhausted at three siblings.** Rejected: `commrepair`/9512's,
  `applianceshop`/9522's and `furniture`/9524's own ADR-0001s already
  established the precedent generalizes; extending it to a fourth
  sibling confirms this rather than treating three instances as a
  ceiling.

## References

- `cloud-itonami-isic-9529/docs/adr/0001-architecture.md` (child-repo
  ADR, full 10-decision structure)
- `cloud-itonami-isic-9521/docs/adr/0001-architecture.md` (origin of
  the dual-actuation Repair Shop Governor shape)
- `cloud-itonami-isic-9512/docs/adr/0001-architecture.md` (origin of
  the governor-name-reuse precedent)
- Hallmarking Act 1973 (UK)
- National Stamping Act, 15 U.S.C. §297; FTC Guides for the Jewelry,
  Precious Metals, and Pewter Industries, 16 C.F.R. Part 23 (US)
- Feingehaltsgesetz (Gesetz über den Feingehalt der Gold- und
  Silberwaren) (Germany)
