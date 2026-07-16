# ADR-2607155000: cloud-itonami-isic-4311 (demolition of buildings and other structures) demolition-project-operations-coordination actor

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607150700 (`cloud-itonami-isic-4211` construction
robotics-premise reference, primary structural template), ADR-2607011000
(actor pattern & ISIC section coverage), ADR-2607142800 /
ADR-2607152000 (robotics-premise "policy, not control" convention this
ADR deliberately narrows further)

## Context

`cloud-itonami/cloud-itonami-isic-4311` did not exist prior to this ADR
(verified via `gh repo view` before starting -- no failed prior attempt
to recover from). ISIC Rev.5 class 4311 is "Demolition" -- a
safety-critical domain: structural-collapse risk, hazardous materials
(asbestos, lead).

`cloud-itonami-isic-4211` (Community Building Construction) was read in
full as the primary structural template -- it establishes this fleet's
"robotics premise" (every cloud-itonami vertical assumes a robot performs
the physical-domain work, gated by an independent governor) and its
module shape (`facts`/`governor`/`notify`/`operation`/`phase`/
`registry`/`sim`/`store`, plus an advisor module and, for 4211
specifically, `robotics`/`simphysics` modules simulating a physical robot
pre-placement-verification mission with a real `physics-2d` press-
collision simulation).

This task assignment explicitly required a NARROWER design than 4211:
demolition is construction/heavy-equipment-adjacent, so per this
workspace's standing rule against writing new Rust or robot-control code,
`cloud-itonami-isic-4311` was scoped as a **coordination-only** actor
from the start -- it deliberately has NO `robotics.cljc`/`simphysics.cljc`
analog (no simulated robot mission, no `kotoba-lang/robotics`/
`kotoba-lang/physics-2d` dependency) and NO module that commits a
real-world actuation effect. Every proposal this actor's advisor can
produce carries `:effect :propose`, unconditionally, and the governor
HARD-holds any proposal that doesn't -- a permanent structural invariant
distinguishing this actor from every prior `cloud-itonami-isic-*` actor
in this fleet (including 4211), whose actuation ops DO commit real
effects (mail dispatch, robot placement, structure handover, legal report
filing).

Built by reading `cloud-itonami-isic-4211` in full, adapting its module
shape (facts/governor/notify/operation/phase/registry/sim/store, plus a
new `demolition.advisor` module for the sealed LLM/proposal node) to the
demolition domain, and following the same stricter verification protocol
(capable model, mandatory `clojure -M:test` re-run before and after every
push, fresh-clone post-merge re-verification) as the preceding batch of
ISIC-coverage actors in this rollout.

## Scope exclusions (hard, permanent, governor-enforced -- not just prose)

- Heavy-equipment control (dispatching or commanding equipment) --
  outside this actor's authority entirely; no op in the closed
  allowlist can express it, and the governor's `forbidden-action-class`
  HARD check independently rejects any proposal whose `:value` carries
  an `:equipment-control?`/`:direct-actuation?` marker, un-overridable
  by any human approval.
- Structural-engineering-decision / demolition-plan-finalization
  authority -- the licensed engineer / site supervisor's exclusively.
  `:schedule-demolition-operation` proposes a phase-window schedule,
  NEVER a finalized engineering plan; the same `forbidden-action-class`
  check rejects any proposal carrying a `:finalizes-engineering-plan?`
  marker.
- Real-world actuation of any kind -- every committed record carries
  `:effect :propose` only (`demolition.governor`'s `effect-not-propose`
  HARD check, defense-in-depth against a compromised/malfunctioning
  advisor).

## Decision

Implement a complete demolition-project-operations-coordination actor
(`cloud-itonami-isic-4311`), structured after `cloud-itonami-isic-4211`
module-for-module where applicable, narrowed to coordination-only
authority as described above:

1. **`demolition.governor`** (Demolition Governor) -- EIGHT HARD checks,
   all un-overridable by human approval: unknown op (outside the closed
   4-op allowlist), `:effect` not `:propose`, forbidden action class
   (equipment-control/direct-actuation/engineering-plan-finalization
   markers), site not independently verified/registered (`:schedule-
   demolition-operation`/`:flag-safety-concern`/`:order-supplies`
   require the site's own recorded `:site-verified?` ground-truth
   field), legal-basis missing (uncovered jurisdiction), hazmat-survey
   incomplete, notification-lead-time insufficient (`:quantitative`
   jurisdictions only -- independently recomputed from the site's own
   recorded `:notification-lead-days-actual`, the same "ground truth,
   not self-report" discipline `construction.governor`'s weather-
   threshold recheck established), unresolved safety concern on file.
   High-stakes escalation: `:flag-safety-concern` and `:schedule-
   demolition-operation` ALWAYS escalate, unconditionally, at every
   phase; `:order-supplies` escalates above a cost threshold (default
   $5,000 USD) or below the confidence floor (0.6).

2. **`demolition.facts`** -- per-jurisdiction hazmat-survey/demolition-
   notification legal-basis catalog (JPN/USA/DEU), citing REAL official
   sources verified via web search before writing (never fabricated):
   Japan's 石綿障害予防規則（平成17年厚生労働省令第21号）第3条 (asbestos
   pre-work survey duty) and 建設リサイクル法第10条 (7-calendar-day
   advance notification, buildings ≥80㎡); the USA's OSHA 29 CFR
   1926.1101 (asbestos construction standard) and 40 CFR 61.145 (NESHAP
   Subpart M, 10-working-day advance notification); the EU/Germany's
   Directive 2009/148/EC Art.11 (plan-of-work duty, honestly
   `:qualitative` -- no fixed EU-wide numeric lead-time, unlike Japan's
   7 days or the USA's 10 days) transposed via Gefahrstoffverordnung/
   TRGS 519, plus Landesbauordnung demolition-notification/permit
   citations. `notification-lead-insufficient?` is the same three-valued
   (true/false/:qualitative/nil) ground-truth recheck family
   `construction.facts/weather-threshold-exceeded?` established.

3. **`demolition.registry`** -- pure-function record construction for
   site-record-log / schedule-proposal / safety-concern-flag /
   supply-order-proposal drafts (jurisdiction-scoped sequence numbering,
   e.g. `JPN-SCH-000000`), plus `render-safety-concern-notice` (the
   actual human-readable notice document, citing legal basis inline and
   explicitly disclaiming plan-finalization authority in its own
   `## Status` section).

4. **`demolition.store`** -- `Store` protocol, dual `MemStore`/
   `DatomicStore` (langchain.db) backend, contract-tested for parity.
   `DatomicStore` uses `langchain-store.core` (ADR-2607141600) for the
   EDN-blob codec / `:db.unique/identity` schema / seq-keyed event-log
   read-append pattern via a data-driven entity field-spec, instead of
   hand-rolling `enc`/`dec*` -- this is a NEW store in this fleet, so it
   follows the current convention rather than the hand-rolled pattern
   earlier actors (including `construction.store`) predate. UNLIKE every
   sibling actor's store, none of the four coordination-artifact
   histories is a one-time double-actuation-guarded event -- every op
   may recur any number of times for the same site, because this actor
   never actually dispatches/authorizes/files/hands over anything.

5. **`demolition.notify`** -- mail+phone (Resend+Twilio, JVM-only real
   transports behind `#?(:clj ...)`) `Notifier` protocol, structurally
   identical to `construction.notify` but fires `dispatch-safety-
   concern-notice!` to a site's `:safety-contacts` (licensed engineer/
   site supervisor/hazmat authority) roster ONLY after a human has
   approved committing a `:flag-safety-concern` proposal (never on an
   auto-commit path, since that op is never auto-eligible at any phase).

6. **`demolition.advisor`** -- the sealed LLM/proposal node (`Advisor`
   protocol, deterministic `mock-advisor` default + `llm-advisor` real-
   inference seam), producing all four ops' proposals, every one
   carrying `:effect :propose`.

7. **`demolition.phase`** -- 0→3 rollout gate. `:log-site-record` and
   `:order-supplies` are the ONLY ops ever in a phase's `:auto` set (at
   phase 3); `:schedule-demolition-operation`/`:flag-safety-concern` are
   permanently absent from every phase's `:auto` set, belt-and-suspenders
   with the governor's `high-stakes` set.

8. **`demolition.operation`** -- langgraph-clj StateGraph actor (real
   `interrupt-before #{:request-approval}` human-in-the-loop, checkpoint-
   based resume), structurally identical to `construction.operation`.

9. **`demolition.sim`** -- demo driver (`clojure -M:dev:run`) walking
   eight seeded sites through the full coordination episode and all eight
   HARD-hold paths plus a cross-jurisdiction (USA) and a qualitative
   (DEU/EU) schedule walkthrough.

10. **Operations supported** (closed allowlist, all `:effect :propose`):
    `:log-site-record` (site survey/hazmat-assessment/progress data
    logging), `:schedule-demolition-operation` (demolition-phase
    scheduling proposal, ALWAYS escalates), `:flag-safety-concern`
    (structural-instability/hazmat concern, ALWAYS escalates), `:order-
    supplies` (equipment/disposal-service procurement proposal,
    escalates above cost threshold or on low confidence).

11. **Tests** -- 68 tests / 252 assertions, all green (facts, governor-
    contract, notify, phase, registry, store-contract suites).

12. **Documentation** -- README.md (documents the coordination-only
    scope prominently, explicitly notes 4211's robotics-premise framing
    does NOT apply verbatim), GOVERNANCE.md, CODE_OF_CONDUCT.md,
    CONTRIBUTING.md, SECURITY.md (all domain-adapted for demolition
    coordination, not copy-pasted from an unrelated HR/talent-domain
    template -- 4211's own SECURITY.md/CODE_OF_CONDUCT.md were found to
    carry leftover HR-domain language from a shared template and were
    NOT propagated here). blueprint.edn (`:isic-rev5 "4311"`,
    `:robotics false` -- honestly disclosed, since this actor holds no
    robotics-simulation code, unlike 4211). AGPL-3.0-or-later LICENSE
    copied verbatim from 4211.

## Consequences

(+) Demolition (ISIC 4311) demolition-project-operations-coordination is
now genuinely implemented and fully tested.

(+) Scope boundaries (no heavy-equipment control, no structural-
engineering-decision/demolition-plan-finalization authority, no
real-world actuation of any kind) are hardcoded in governor checks
(`forbidden-action-class`, `effect-not-propose`), not just asserted in
prose -- and covered by dedicated tests exercising the governor directly
with hypothetically-compromised-advisor proposals the deterministic mock
advisor itself never produces.

(+) Safety-critical escalation (`:flag-safety-concern` ALWAYS human,
`:schedule-demolition-operation` ALWAYS human, at every phase,
unconditionally) is a core design invariant enforced at TWO independent
layers (`demolition.phase`'s `:auto` set and `demolition.governor`'s
`high-stakes` set), not an add-on.

(+) Per-jurisdiction legal-basis catalog cites REAL, web-search-verified
official sources (石綿障害予防規則, 建設リサイクル法, OSHA 29 CFR
1926.1101, 40 CFR 61.145 NESHAP, EU Directive 2009/148/EC) -- the EU/
Germany entry is honestly `:qualitative` (no fabricated numeric lead-time
to match Japan's 7 days / the USA's 10 days).

(+) `DatomicStore` uses the current `langchain-store.core` convention
(ADR-2607141600) instead of hand-rolling the EDN-blob codec, since this
is a newly-created store with no legacy pattern to preserve.

(+) Portable `.cljc` implementation with no JVM-only constructs in `src`
(real Resend/Twilio transports in `demolition.notify` are the same
`#?(:clj ...)`-guarded pattern `construction.notify` established); `.clj`
test files run on the JVM only, per this fleet's verification protocol.

(-) Real Datomic/kotoba-server deployment (vs. the tested in-memory
`MemStore` default) is a follow-up, matching every sibling actor's own
current deployment status.

(-) `demolition.facts`'s catalog covers only JPN/USA/DEU (3 of ~194
jurisdictions) -- an honestly-reported starting catalog, not a survey,
matching `construction.facts`'s own disclosed scope.

## Verification

- `cloud-itonami-isic-4311`: `clojure -M:test` → "Ran 68 tests containing
  252 assertions. 0 failures, 0 errors." `clojure -M:lint` → 0 errors, 1
  warning (`clojure.string` required-but-unused-under-cljs-analysis in
  `demolition.notify`, confirmed to be the SAME pre-existing warning
  `construction.notify` in `cloud-itonami-isic-4211` itself carries --
  not a regression). `clojure -M:dev:run` → demo runs end-to-end through
  every op and all eight HARD-hold paths, safety-concern notice document
  renders with real legal citations, no exceptions.
- Pushed to `main` as a single scaffold commit, `0b609abb11ff61fcc0ec6058fde8c7b2b9139dce`
  (no rename/correction needed -- ISIC 4311 was confirmed correct
  against the `kotoba-lang/industry` registry, `:id "4311"` = `:name
  "Demolition"`, before scaffolding).
