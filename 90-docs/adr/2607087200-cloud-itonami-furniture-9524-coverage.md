# ADR-2607087200: cloud-itonami-isic-9524 (repair of furniture and home furnishings) deepened to `:implemented`

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
- ADR-2607032000 (`cloud-itonami-isic-6511`, the original life-
  insurance reference implementation)
- the full `:adr/related` chain in the companion `.edn` file (every
  prior deepened vertical this fleet)

## Context

`cloud-itonami-isic-9524` ("Repair of furniture and home furnishings")
was a `:blueprint`-tier stub in the `kotoba-lang/industry` registry,
part of the repair-shop cluster reopened by `commrepair`/9512's own
governor-name-reuse precedent after the original governor-name-
collision survey declared the fleet exhausted. Its own
`:repair-shop-governor` keyword is identical to `repairshop`/9521's,
`commrepair`/9512's and `applianceshop`/9522's — this is the first
build to extend that specific family to a THIRD sibling since
`applianceshop`/9522 confirmed the precedent a second time.

Of the remaining repair-shop candidates (9523: footwear/leather goods;
9524: furniture/home furnishings; 9529: other personal/household
goods), 9524 stood out for having the strongest, most concrete
regulatory hook: furniture and home-furnishings repair routinely
includes reupholstery/filling-material replacement work, and several
major jurisdictions (California, the UK, Germany) require
reupholstered items to carry a confirmed flammability-compliance
status before returning the item to a customer — a well-documented,
distinct regulatory regime `repairshop`/9521's, `commrepair`/9512's
and `applianceshop`/9522's own catalogs do not model.

## Decision

Build `furniture` (RepairOps-LLM ⊣ Repair Shop Governor) following the
exact governed-actor architecture established by `cloud-itonami-isic-
6511` and reused by every subsequent actor in this fleet:
`furniture.store` (Store protocol, MemStore + DatomicStore, proven
parity via `store-contract-test`), `furniture.registry` (pure DRAFT-
record construction, honest reuse of `parts-cost-matches-claim?` from
`repairshop`/9521, `commrepair`/9512 and `applianceshop`/9522),
`furniture.governor` (independent HARD-check compliance layer, a new
`flammability-compliance-unconfirmed?` check, and a `high-stakes`
actuation gate), `furniture.phase` (0→3 rollout table),
`furniture.repairopsllm` (mock+llm Advisor pair), `furniture.operation`
(langgraph StateGraph, generic shape copied verbatim), and
`furniture.sim` (demo driver).

The shape is dual-actuation
(`#{:actuation/complete-repair :actuation/return-item}`), mirroring
`repairshop`/9521's, `commrepair`/9512's and `applianceshop`/9522's own
shape exactly, since this blueprint's own text names two distinct
real-world acts: completing a repair and returning the item to the
customer.

This is the SEVENTH confirmation of the fleet-wide governor-name-reuse
precedent, and the first time it applies within the original
`:repair-shop-governor` family for a third sibling.

The one genuinely new HARD check —
`flammability-compliance-unconfirmed-violations` — is the FOURTH
conditional variant of the unconditional-evaluation-discipline family
(after `socialresearch`/7220's, `bizassoc`/9411's and `training`/8549's
own, at 63rd, 64th and 66th): it activates only when a ticket's own
record declares `:involves-upholstery-work? true`. A repair that does
not touch filling/upholstery materials (e.g. a simple wood-joint
reglue) has no flammability-compliance requirement at all. Grounded in
real furniture-flammability-compliance law:

- US: California's Home Furnishings and Thermal Insulation Act (the
  "Law Label" regime, Technical Bulletin 117-2013 flammability
  standard), administered by the California Bureau of Household Goods
  and Services (BHGS).
- UK: The Furniture and Furnishings (Fire) (Safety) Regulations 1988
  (as amended), administered by the Office for Product Safety and
  Standards (OPSS) and local Trading Standards.
- Germany: DIN EN 1021-1/1021-2 ignition-source test standards for
  upholstered furniture, enforced under the Produktsicherheitsgesetz
  (ProdSG) by regional market-surveillance authorities.

Japan is honestly seeded WITHOUT a flammability-specific sub-citation
— it has no direct equivalent mandatory regime in this R0 catalog,
the same honesty discipline `bizassoc`/9411's own lobbying-
registration check established for a different jurisdiction gap.

This is the 67th distinct application of the unconditional-evaluation
screening discipline in this fleet (most recently `training.governor/
instructor-license-unconfirmed-violations` at 66th).

Two checks are honest, literal reuses, not claimed as new:
`parts-cost-matches-claim?` and `safety-test-not-passed` (from
`repairshop`/9521's, `commrepair`/9512's and `applianceshop`/9522's own
architecture).

## Consequences

- `cloud-itonami-isic-9524` moves from `:blueprint` to `:implemented`
  in `kotoba-lang/industry`'s registry (fleet maturity: 82 → 83
  implemented, 3 → 2 blueprint).
- The governor-name-reuse precedent is now confirmed a seventh time,
  and confirmed to generalize to a THIRD sibling within the original
  `:repair-shop-governor` family — reinforcing it as a durable,
  scalable fleet-wide pattern.
- 41 tests / 191 assertions pass in `furniture`; lint is clean; the
  demo (`clojure -M:dev:run`) walks one clean dual-actuation lifecycle
  plus five HARD-hold scenarios end-to-end.
- `kotoba-lang/industry`'s own full test suite (7 tests / 130
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
(2607085700 through 2607087100).

Remaining `:blueprint`-tier candidates after this promotion: 9523
(repair of footwear and leather goods) and 9529 (repair of other
personal and household goods), each still requiring its own genuinely
differentiated, well-grounded check.

## Alternatives considered

- **An unconditional flammability-compliance check.** Rejected: a
  simple wood-joint reglue or hardware replacement has no flammability-
  compliance concern at all — forcing the check onto every ticket
  would fabricate a requirement.
- **Fabricating a Japanese flammability-compliance citation** to keep
  4-of-4 jurisdiction coverage. Rejected: the same honesty discipline
  this fleet has applied consistently (most recently `bizassoc`/9411's
  own lobbying-registration gap for Japan) forbids inventing a
  regulatory regime that does not exist.
- **Declining the build and treating the repair-shop cluster as
  exhausted at two siblings.** Rejected: `commrepair`/9512's and
  `applianceshop`/9522's own ADR-0001s already established the
  precedent generalizes; extending it to a third sibling confirms
  this rather than treating two instances as a ceiling.

## References

- `cloud-itonami-isic-9524/docs/adr/0001-architecture.md` (child-repo
  ADR, full 10-decision structure)
- `cloud-itonami-isic-9521/docs/adr/0001-architecture.md` (origin of
  the dual-actuation Repair Shop Governor shape)
- `cloud-itonami-isic-9512/docs/adr/0001-architecture.md` (origin of
  the governor-name-reuse precedent)
- California Business and Professions Code, Home Furnishings and
  Thermal Insulation Act (Law Label regime, TB117-2013)
- The Furniture and Furnishings (Fire) (Safety) Regulations 1988 (UK)
- DIN EN 1021-1/1021-2 under Produktsicherheitsgesetz (Germany)
