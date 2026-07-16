# ADR-2607087100: cloud-itonami-isic-8549 (other education n.e.c.) deepened to `:implemented`

## Status

Accepted

## Related

- ADR-2607084400 (`cloud-itonami-isic-8542`, cultural — cultural
  education, origin of the shared Instruction Integrity Governor name
  and the either/or-naming single-actuation precedent)
- ADR-2607086600 through 2607087000 (commrepair/9512,
  applianceshop/9522, socialresearch/7220, bizassoc/9411,
  vocational/8522 — the five prior confirmations of the governor-
  name-reuse precedent)
- ADR-2607032000 (`cloud-itonami-isic-6511`, the original life-
  insurance reference implementation)
- the full `:adr/related` chain in the companion `.edn` file (every
  prior deepened vertical this fleet)

## Context

`cloud-itonami-isic-8549` ("Other education n.e.c.") was a
`:blueprint`-tier stub in the `kotoba-lang/industry` registry,
previously flagged as blocked by the fleet-wide governor-name-
collision survey. Its own `:instruction-integrity-governor` keyword is
identical to `cultural`/8542's (cultural education). Per the governor-
name-reuse precedent `commrepair`/9512's own ADR-0001 established —
confirmed five times since across three distinct governor-name
families — sharing a governor name is acceptable when the underlying
business archetype is genuinely the same, provided the reuse is
documented and the new build brings its own genuinely differentiated,
well-grounded check.

This blueprint's own named example activities (driving schools, exam-
preparation courses, corporate training) are genuinely heterogeneous.
"Driving schools" stood out as the strongest hook: unlike any prior
check in this fleet, a driving student's actual safety depends on
whether the INSTRUCTOR who signs off on their competency is
themselves a licensed driving instructor — a fact about the assessor,
not the student. Every prior instance of this fleet's unconditional-
evaluation-screening discipline verifies a ground-truth fact about the
subject being assessed (a study, a position, a ticket, a student); this
is the first to verify a fact about the professional performing the
assessment.

## Decision

Build `training` (EdOps-LLM ⊣ Instruction Integrity Governor)
following the exact governed-actor architecture established by
`cloud-itonami-isic-6511` and reused by every subsequent actor in this
fleet: `training.store` (Store protocol, MemStore + DatomicStore,
proven parity via `store-contract-test`), `training.registry` (pure
DRAFT-record construction, honest reuse of `practice-hours-
insufficient?` from `cultural`/8542), `training.governor` (independent
HARD-check compliance layer, a new `instructor-license-unconfirmed?`
check, and a `high-stakes` actuation gate), `training.phase` (0→3
rollout table), `training.edopsllm` (mock+llm Advisor pair),
`training.operation` (langgraph StateGraph, generic shape copied
verbatim), and `training.sim` (demo driver).

This blueprint's own text phrases the one real-world act as
"finalizing a certification OR completion record" — an "or,"
signaling ONE decision with two possible outcome kinds, not two
separate sequential milestones (unlike `secondary`/8521's and
`vocational`/8522's own genuinely dual grading-THEN-graduation shape,
where the text uses "and"). Following `sports`/8541's and `cultural`/
8542's own precedent for this exact phrasing pattern, the shape is
single-actuation (`#{:actuation/finalize-completion}`).

This is the SIXTH confirmation of the fleet-wide governor-name-reuse
precedent, and the THIRD distinct governor-name family.

The one genuinely new HARD check —
`instructor-license-unconfirmed-violations` — is the THIRD conditional
variant of the unconditional-evaluation-discipline family (after
`socialresearch`/7220's `human-subjects-review-unconfirmed?` and
`bizassoc`/9411's `lobbying-registration-unconfirmed?`): it activates
only when a student's own record declares `:instructor-license-
required? true` (driving-instruction programs, in this R0 seed —
exam-prep and corporate training do not require a licensed
instructor's sign-off). Grounded in real driving-instructor-licensing
law:

- Japan: 道路交通法 (Road Traffic Act) — 指定自動車教習所指導員技能検定
  (Designated Driving School instructor competency examination),
  administered by Prefectural Public Safety Commissions under the
  National Police Agency.
- US: State Department of Motor Vehicles (DMV) driving-instructor
  licensing requirements under state vehicle codes.
- UK: Driver and Vehicle Standards Agency (DVSA) — Approved Driving
  Instructor (ADI) register, under the Road Traffic Act 1988, Part V.
- Germany: Fahrlehrergesetz (Driving Instructor Act) — Fahrlehrerlaubnis
  (driving instructor license), administered by regional
  Fahrerlaubnisbehörden.

This is the 66th distinct application of the unconditional-evaluation
screening discipline in this fleet (most recently `vocational.
governor/workplace-safety-training-unconfirmed-violations` at 65th),
and structurally novel: the FIRST check in this discipline to verify a
fact about the ASSESSOR rather than the subject being assessed.

One check is an honest, literal reuse, not claimed as new:
`practice-hours-insufficient?` (the 9th MINIMUM-threshold-family
instance, from `cultural`/8542's own architecture).

## Consequences

- `cloud-itonami-isic-8549` moves from `:blueprint` to `:implemented`
  in `kotoba-lang/industry`'s registry (fleet maturity: 81 → 82
  implemented, 4 → 3 blueprint).
- The governor-name-reuse precedent is now confirmed a sixth time,
  establishing a third distinct governor-name family.
- A structurally novel category of unconditional-evaluation check is
  established (assessor-credential verification), which future builds
  can draw on for professional-licensing-adjacent concerns.
- 32 tests / 141 assertions pass in `training`; lint is clean; the
  demo (`clojure -M:dev:run`) walks one clean single-actuation
  lifecycle plus three HARD-hold scenarios and one conditional-noop
  scenario end-to-end.
- `kotoba-lang/industry`'s own full test suite (7 tests / 129
  assertions) was re-run clean before committing the promotion. Its
  own test suite's "still-blueprint" example entry was swapped from
  `"8549"` to `"9523"` in three places (`maturity-tier`,
  `maturity-roadmap-reports-next-step`,
  `execution-plan-reports-ui-export-readiness`), since 8549 is no
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
(2607085700 through 2607087000).

Remaining `:blueprint`-tier candidates after this promotion: 9523,
9524, 9529 — the repair-shop cluster, reopened per `commrepair`/9512's
own governor-name-reuse precedent, each still requiring its own
genuinely differentiated, well-grounded check.

## Alternatives considered

- **An unconditional instructor-license check** (applying to every
  student regardless of program type). Rejected: exam-preparation and
  corporate-training programs do not require a licensed instructor's
  sign-off — forcing the check onto every student would fabricate a
  requirement that does not exist for those program types.
- **Reusing `cultural.governor/child-performer-work-permit-
  unresolved-violations` directly.** Rejected: a child-performer work
  permit and a driving-instructor license are legally and conceptually
  unrelated concerns for genuinely different program types — a
  mechanical copy would not be grounded in this vertical's actual
  regulatory reality.
- **A dual-actuation shape** mirroring `secondary`/8521's and
  `vocational`/8522's own grading-THEN-graduation shape. Rejected:
  this blueprint's own text uses "or," not "and" — following `sports`/
  8541's and `cultural`/8542's own precedent for this exact phrasing.

## References

- `cloud-itonami-isic-8549/docs/adr/0001-architecture.md` (child-repo
  ADR, full 10-decision structure)
- `cloud-itonami-isic-8542/docs/adr/0001-architecture.md` (origin of
  the shared Instruction Integrity Governor name and either/or-naming
  precedent)
- `cloud-itonami-isic-9512/docs/adr/0001-architecture.md` (origin of
  the governor-name-reuse precedent)
- 道路交通法 (Japan, Road Traffic Act)
- State DMV driving-instructor licensing requirements (US)
- Road Traffic Act 1988, Part V (UK, DVSA ADI register)
- Fahrlehrergesetz (Germany)
