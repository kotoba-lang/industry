# ADR-2607087000: cloud-itonami-isic-8522 (technical and vocational secondary education) deepened to `:implemented`

## Status

Accepted

## Related

- ADR-2607072915 (`cloud-itonami-isic-8510`, school — pre-primary/
  primary education, origin of the shared Curriculum Safeguarding
  Governor name)
- ADR-2607080400 (`cloud-itonami-isic-8521`, secondary — general
  secondary education, second sibling to share the name; origin of
  the dual-actuation grading/graduation entity/op shape this build's
  own architecture is modeled on)
- ADR-2607086600 through 2607086900 (commrepair/9512,
  applianceshop/9522, socialresearch/7220, bizassoc/9411 — the four
  prior confirmations of the governor-name-reuse precedent)
- ADR-2607032000 (`cloud-itonami-isic-6511`, the original life-
  insurance reference implementation)
- the full `:adr/related` chain in the companion `.edn` file (every
  prior deepened vertical this fleet)

## Context

`cloud-itonami-isic-8522` ("Technical and vocational secondary
education") was a `:blueprint`-tier stub in the `kotoba-lang/industry`
registry, previously flagged as blocked by the fleet-wide governor-
name-collision survey. Unlike every other collision this fleet has
resolved so far, this one is a THREE-WAY collision: 8522's own
`:curriculum-safeguarding-governor` keyword is identical to BOTH
`school`/8510's and `secondary`/8521's. Crucially, `secondary.
governor`'s own docstring (written when `secondary`/8521 was built)
already documented that this was the blueprint authors' own deliberate
template reuse across closely related general-education sub-domains —
the first time two verticals in this fleet shared an identical
governor+advisor name pair — not an accidental collision. Extending
that same reasoning to a third sibling is a natural continuation of
the governor-name-reuse precedent `commrepair`/9512's own ADR-0001
established and three subsequent builds confirmed.

8522's own blueprint text already carries a hint that it is not a
mechanical clone of `secondary`/8521: its README independently names
the advisor "VocEdOps-LLM," distinct from `school`/8510's and
`secondary`/8521's shared "SchoolOps-LLM" — a distinction present
before this build started.

## Decision

Build `vocational` (VocEdOps-LLM ⊣ Curriculum Safeguarding Governor)
following the exact governed-actor architecture established by
`cloud-itonami-isic-6511` and reused by every subsequent actor in this
fleet: `vocational.store` (Store protocol, MemStore + DatomicStore,
proven parity via `store-contract-test`), `vocational.registry` (pure
DRAFT-record construction, honest reuses of `attendance-hours-
insufficient?` and `graduation-requirements-unsatisfied?` from
`secondary`/8521), `vocational.governor` (independent HARD-check
compliance layer, a new `workplace-safety-training-unconfirmed?`
check, and a `high-stakes` actuation gate), `vocational.phase` (0→3
rollout table), `vocational.vocedopsllm` (mock+llm Advisor pair),
`vocational.operation` (langgraph StateGraph, generic shape copied
verbatim), and `vocational.sim` (demo driver).

The shape is dual-actuation
(`#{:actuation/finalize-certification :actuation/finalize-
graduation}`), mirroring `secondary`/8521's own shape exactly, since
this blueprint's own text names two distinct real-world acts:
"finalizing a certification or graduation decision." `:certification/
finalize` replaces `secondary`/8521's own `:grading/finalize`, since
this blueprint's own text uses "certification" rather than "grading" —
the entity/op shape must match the blueprint's own actual text, not
merely mirror the sibling's naming for its own sake.

This is the FIFTH confirmation of the fleet-wide governor-name-reuse
precedent, and the first time it applies within a family
(`school`/8510, `secondary`/8521, `vocational`/8522) that already had
two members before a new sibling joined.

The one genuinely new HARD check —
`workplace-safety-training-unconfirmed-violations` — is deliberately
UNCONDITIONAL, unlike the two most recent additions to this discipline
(`socialresearch`/7220's `human-subjects-review-unconfirmed?` and
`bizassoc`/9411's `lobbying-registration-unconfirmed?`, both
conditional). Technical and vocational secondary education is defined
by ISIC 8522 itself as involving hands-on shop/practicum work with
tools, machinery and industrial equipment — every student here is in
scope, unlike the genuinely varying ground truths those two conditional
checks depend on. Grounded in real workplace/workshop-safety-training
law:

- US: OSHA (29 C.F.R. Part 1910/1926), administered by the
  Occupational Safety and Health Administration, U.S. Department of
  Labor.
- UK: Health and Safety at Work etc. Act 1974, administered by the
  Health and Safety Executive (HSE).
- Germany: DGUV Vorschriften under Sozialgesetzbuch VII (SGB VII),
  administered by the Deutsche Gesetzliche Unfallversicherung
  (DGUV)/Berufsgenossenschaften.
- Japan: 労働安全衛生法 (Industrial Safety and Health Act),
  administered by the Ministry of Health, Labour and Welfare.

This is the 65th distinct application of the unconditional-evaluation
screening discipline in this fleet (most recently `bizassoc.governor/
lobbying-registration-unconfirmed-violations` at 64th).

Two checks are honest, literal reuses, not claimed as new:
`attendance-hours-insufficient?` (the 2nd non-temporal MINIMUM-
threshold-family instance) and `graduation-requirements-unsatisfied?`
(the 3rd set-containment/subset-family instance), both from
`secondary`/8521's own architecture. `academic-integrity-flag-
unresolved?` is likewise an honest reuse of `secondary`/8521's own
23rd unconditional-evaluation grounding.

## Consequences

- `cloud-itonami-isic-8522` moves from `:blueprint` to `:implemented`
  in `kotoba-lang/industry`'s registry (fleet maturity: 80 → 81
  implemented, 5 → 4 blueprint).
- The governor-name-reuse precedent is now confirmed a fifth time,
  and demonstrates it generalizes even to a governor-name family that
  already had two members before a new sibling joined — reinforcing
  it as a durable fleet-wide pattern.
- 41 tests / 208 assertions pass in `vocational`; lint is clean; the
  demo (`clojure -M:dev:run`) walks one clean dual-actuation lifecycle
  plus six HARD-hold scenarios end-to-end.
- `kotoba-lang/industry`'s own full test suite (7 tests / 128
  assertions) was re-run clean before committing the promotion. Its
  own test suite's "still-blueprint" example entry was swapped from
  `"8522"` to `"8549"` in three places (`maturity-tier`,
  `maturity-roadmap-reports-next-step`,
  `execution-plan-reports-ui-export-readiness`), since 8522 is no
  longer blueprint-tier after this promotion.
- `manifest/west.yml`'s `industry` pin was advanced via the GitHub API
  single-entry-commit path and verified canonical via
  `nbb scripts/gen-west-manifest.cljs --entry industry`.

## Scope note

`:fleet-maturity-before`/`:fleet-maturity-after` in the companion
`.edn` reflect the actual observed sum of the three maturity tiers in
`registry.edn` at build time (630 total), not
`docs/cloud-itonami.md`'s separately-tracked "Total entries: 643"
figure — the same clarification made in every prior ADR this fleet
(2607085700 through 2607086900).

## Alternatives considered

- **A CONDITIONAL workplace-safety-training check** (applying only to
  students in specific trade tracks). Rejected: ISIC 8522 itself
  defines this entire vertical as involving hands-on shop/practicum
  work — there is no honest per-student exception to carve out here,
  unlike the two most recent conditional-variant checks elsewhere in
  this fleet.
- **Renaming `:certification/finalize` to `:grading/finalize`** to
  keep exact parity with `secondary`/8521's own op naming. Rejected:
  this blueprint's own published text consistently uses
  "certification," not "grading" — the op name must match the
  blueprint's own actual text.
- **Declining the build because this is the fleet's first three-way
  governor-name collision.** Rejected: `secondary.governor`'s own
  docstring already established that `school`/8510 and `secondary`/
  8521 sharing a name was the blueprint authors' own deliberate
  template reuse — extending that reasoning to a third sibling is the
  same precedent this fleet has already applied four times elsewhere.

## References

- `cloud-itonami-isic-8522/docs/adr/0001-architecture.md` (child-repo
  ADR, full 10-decision structure)
- `cloud-itonami-isic-8521/docs/adr/0001-architecture.md` (origin of
  the dual-actuation grading/graduation entity/op shape)
- `cloud-itonami-isic-9512/docs/adr/0001-architecture.md` (origin of
  the governor-name-reuse precedent)
- 29 C.F.R. Part 1910/1926 (US OSHA)
- Health and Safety at Work etc. Act 1974 (UK, HSE)
- Sozialgesetzbuch VII / DGUV Vorschriften (Germany)
- 労働安全衛生法 (Japan)
