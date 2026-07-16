# ADR-2607086800: cloud-itonami-isic-7220 (research and experimental development on social sciences and humanities) deepened to `:implemented`

## Status

Accepted

## Related

- ADR-2607082200 (`cloud-itonami-isic-7210`, research — origin of the
  Research Integrity Governor / single-actuation shape)
- ADR-2607086600 (`cloud-itonami-isic-9512`, commrepair — origin of the
  governor-name-reuse precedent)
- ADR-2607086700 (`cloud-itonami-isic-9522`, applianceshop — second
  confirmation of the precedent)
- ADR-2607032000 (`cloud-itonami-isic-6511`, the original life-
  insurance reference implementation)
- the full `:adr/related` chain in the companion `.edn` file (every
  prior deepened vertical this fleet)

## Context

`cloud-itonami-isic-7220` ("Research and experimental development on
social sciences and humanities") was a `:blueprint`-tier stub in the
`kotoba-lang/industry` registry. Per this repository's standing
authorization to pick a new ISIC blueprint vertical and promote it to
`:implemented`, this build re-examined the fleet-wide governor-name-
collision survey's remaining four "blocked" candidates (7220, 8522,
8549, 9411) in light of the governor-name-reuse precedent
`commrepair`/9512's own ADR-0001 established and `applianceshop`/9522
confirmed a second time. All four remaining collisions were
re-examined for whether the underlying business archetype was
genuinely the same as its already-implemented sibling. `7220`
(social sciences/humanities R&D) vs. `research`/7210 (natural
sciences/engineering R&D) stood out as the strongest candidate: both
are the same research-integrity-oversight archetype, differing only in
subject domain, AND `7220`'s own characteristic activities (surveys,
interviews, focus groups, ethnographic fieldwork) create a genuinely
new, well-grounded regulatory concern entirely absent from `7210`'s
own natural-science/engineering concerns: human-subjects-research
ethics review (IRB/ethics-committee approval).

Grep-verifying every prior sibling's governor/registry/store
namespaces for `irb`, `human-subject`, `research-ethics`, `informed-
consent`, `ethics-review` and `ethics-committee` returned zero hits,
confirming this is a genuinely new concept for this fleet, and a
strong basis for a differentiated build rather than a mechanical
noun-swap.

## Decision

Build `socialresearch` (StudyOps-LLM ⊣ Research Integrity Governor)
following the exact governed-actor architecture established by
`cloud-itonami-isic-6511` and reused by every subsequent actor in this
fleet: `socialresearch.store` (Store protocol, MemStore + DatomicStore,
proven parity via `store-contract-test`), `socialresearch.registry`
(pure DRAFT-record construction, honest reuse of `replication-count-
insufficient?` from `research`/7210), `socialresearch.governor`
(independent HARD-check compliance layer, a new `human-subjects-
review-unconfirmed?` check, and a `high-stakes` actuation gate),
`socialresearch.phase` (0→3 rollout table), `socialresearch.
studyopsllm` (mock+llm Advisor pair), `socialresearch.operation`
(langgraph StateGraph, generic shape copied verbatim), and
`socialresearch.sim` (demo driver).

The shape is single-actuation (`#{:actuation/publish-findings-
report}`), mirroring `research`/7210's own shape exactly, since this
blueprint's own text names one distinct real-world act: publishing a
findings report.

`7220`'s own `:itonami.blueprint/governor` keyword,
`:research-integrity-governor`, is identical to `research`/7210's.
This is the THIRD confirmation of the fleet-wide governor-name-reuse
precedent, and the FIRST on a governor-name family other than
`:repair-shop-governor` — demonstrating the precedent generalizes
across independent governor-name collisions, not just within the
repair-shop cluster.

The one genuinely new HARD check —
`human-subjects-review-unconfirmed-violations` — is CONDITIONAL, unlike
most prior unconditional-evaluation-discipline instances: it activates
only when a study's own record declares `:involves-human-subjects?
true`. A study that does not involve human subjects (e.g. pure
archival/textual-corpus analysis) has no ethics-review requirement at
all — forcing one onto every study regardless of whether it actually
touches human participants would itself be a fabricated requirement,
the same failure mode `socialresearch.facts` refuses to commit for an
uncataloged jurisdiction. Grounded in real human-subjects-research-
ethics-review law:

- US: 45 C.F.R. Part 46 (the "Common Rule" — Protection of Human
  Subjects), enforced by the Office for Human Research Protections
  (OHRP), U.S. Department of Health and Human Services.
- UK: ESRC Framework for Research Ethics, Economic and Social Research
  Council (ESRC), UK Research and Innovation.
- Germany: DFG Leitlinie 13 (Ethikkommissionen), Deutsche
  Forschungsgemeinschaft.
- Japan: 人を対象とする生命科学・医学系研究に関する倫理指針, jointly
  administered by MEXT, MHLW and METI.

This is the 63rd distinct application of the unconditional-evaluation
screening discipline in this fleet (most recently
`applianceshop.governor/refrigerant-handling-certification-
unconfirmed-violations` at 62nd).

Two checks are honest, literal reuses of `research`/7210's own checks,
not claimed as new: `replication-count-insufficient?` (the 7th
MINIMUM-threshold sufficiency instance) and `data-reproducibility-
risk-unresolved?` (the 40th unconditional-evaluation grounding).
Quantitative social-science research (surveys redone across
independent samples, experimental/quasi-experimental designs) shares
the same replication and data-reproducibility concerns as natural-
science/engineering experiments, so reusing these checks unchanged is
the honest choice.

## Consequences

- `cloud-itonami-isic-7220` moves from `:blueprint` to `:implemented`
  in `kotoba-lang/industry`'s registry (fleet maturity: 78 → 79
  implemented, 7 → 6 blueprint).
- The governor-name-reuse precedent is now confirmed a third time, and
  for the first time on a governor-name family other than
  `:repair-shop-governor` — establishing it as a general fleet-wide
  pattern rather than a repair-shop-specific exception. Future builds
  against the remaining collision-blocked candidates (8522, 8549,
  9411) may reuse their own already-implemented sibling's governor
  name too, provided each brings its own genuinely differentiated
  check.
- 34 tests / 159 assertions pass in `socialresearch`; lint is clean;
  the demo (`clojure -M:dev:run`) walks one clean actuation lifecycle
  plus five HARD-hold scenarios end-to-end.
- `kotoba-lang/industry`'s own full test suite (7 tests / 126
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
(2607085700 through 2607086700).

## Alternatives considered

- **8522 (technical and vocational secondary education) vs.
  `secondary`/8521** was considered instead: a plausible distinguishing
  hook exists (workplace/apprenticeship safety, industry-recognized
  certification for vocational instructors), but 7220's human-subjects-
  research-ethics hook was judged stronger and more universally
  recognized (a single, well-documented, decades-old federal
  regulation in the US alone), so it was picked first; 8522 remains
  open for a future build.
- **9411 (business/employers membership organizations) vs.
  `association`/9412** was also considered (same membership-
  organization archetype, different member base), but the human-
  subjects-research angle for 7220 offered a cleaner, more
  legally-grounded differentiator than anything identified for 9411 in
  this pass; 9411 remains open for a future build.
- **An unconditional human-subjects-review check** (applying to every
  study regardless of subject matter). Rejected: not every social-
  science/humanities study involves human participants; forcing the
  check onto every study would fabricate a requirement, contradicting
  the honest-coverage discipline `socialresearch.facts` already
  commits to for jurisdictions.

## References

- `cloud-itonami-isic-7220/docs/adr/0001-architecture.md` (child-repo
  ADR, full 10-decision structure)
- `cloud-itonami-isic-7210/docs/adr/0001-architecture.md` (origin of
  the Research Integrity Governor / single-actuation shape)
- `cloud-itonami-isic-9512/docs/adr/0001-architecture.md` (origin of
  the governor-name-reuse precedent)
- 45 C.F.R. Part 46 (US Common Rule, OHRP)
- ESRC Framework for Research Ethics (UK)
- DFG Leitlinien zur Sicherung guter wissenschaftlicher Praxis,
  Leitlinie 13 (Germany)
- 人を対象とする生命科学・医学系研究に関する倫理指針 (Japan, MEXT/MHLW/METI)
