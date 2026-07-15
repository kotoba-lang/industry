# ADR-2607154312: cloud-itonami-isic-4312 (site preparation) site-preparation-project-operations-coordination actor

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607155000 (`cloud-itonami-isic-4311` demolition
coordination-only actor, primary structural template), ADR-2607150700
(`cloud-itonami-isic-4211` construction robotics-premise reference),
ADR-2607011000 (actor pattern & ISIC section coverage), ADR-2607142800 /
ADR-2607152000 (robotics-premise "policy, not control" convention this
ADR deliberately narrows further, the same way ADR-2607155000 did)

## Context

`cloud-itonami/cloud-itonami-isic-4312` did not exist prior to this ADR
(verified via `gh api repos/cloud-itonami/cloud-itonami-isic-4312` 404
before starting -- no failed prior attempt to recover from). ISIC Rev.5
class 4312 is "Site preparation" -- confirmed against the authoritative
`kotoba-lang/industry` registry (`{:id "4312" :name "Site preparation"
...}`, `:maturity :spec`) before scaffolding, avoiding the code-mislabel
failure mode earlier ADRs in this batch (e.g. ADR-2607154000, isic-0145)
had to guard against. Site preparation covers excavation, earth-moving,
land clearing, and test drilling/boring/core sampling -- a safety-critical
domain distinct from its sibling 4311 (Demolition): the primary risks are
excavation collapse and buried-utility strike, not structural collapse or
hazmat exposure.

`cloud-itonami-isic-4311` (Demolition) was read in full as the primary
structural template -- it is itself a deliberately-narrowed,
coordination-only sibling of `cloud-itonami-isic-4211` (Community
Building Construction, the fleet's robotics-premise reference), and its
module shape (`facts`/`governor`/`notify`/`operation`/`phase`/`registry`/
`sim`/`store`, plus an `advisor` module for the sealed LLM/proposal node)
transfers directly to the site-preparation domain. Like 4311, this task
explicitly required a NARROWER design than 4211: site preparation is
construction/heavy-equipment-adjacent, so per this workspace's standing
rule against writing new Rust or robot-control code,
`cloud-itonami-isic-4312` was scoped as a **coordination-only** actor
from the start -- it deliberately has NO `robotics.cljc`/`simphysics.cljc`
analog (no simulated robot mission, no `kotoba-lang/robotics`/
`kotoba-lang/physics-2d` dependency) and NO module that commits a
real-world actuation effect. Every proposal this actor's advisor can
produce carries `:effect :propose`, unconditionally, and the governor
HARD-holds any proposal that doesn't -- the same permanent structural
invariant ADR-2607155000 established for 4311.

Built by reading `cloud-itonami-isic-4311` in full, adapting its module
shape to the site-preparation domain (renaming `demolition.*` ->
`site-prep.*`, `hazmat-survey` -> `utility-locate`, `demolition-
notification` -> `excavation-notification`, `:schedule-demolition-
operation` -> `:schedule-site-operation`), researching REAL regulatory
citations for JPN/USA/DEU via web search before writing (never
fabricated), and following the same stricter verification protocol
(capable model, mandatory `clojure -M:test` re-run before and after
every push, fresh-clone post-merge re-verification) as the preceding
batch of ISIC-coverage actors in this rollout.

## Scope exclusions (hard, permanent, governor-enforced -- not just prose)

- Excavation/earth-moving-equipment control (dispatching or commanding
  equipment) -- outside this actor's authority entirely; no op in the
  closed allowlist can express it, and the governor's
  `forbidden-action-class` HARD check independently rejects any proposal
  whose `:value` carries an `:equipment-control?`/`:direct-actuation?`
  marker, un-overridable by any human approval.
- Geotechnical/site-readiness sign-off authority -- the licensed
  engineer / site supervisor's exclusively. `:schedule-site-operation`
  proposes a phase-window schedule, NEVER a geotechnical/site-readiness
  sign-off; the same `forbidden-action-class` check rejects any proposal
  carrying a `:finalizes-geotechnical-signoff?` marker.
- Real-world actuation of any kind -- every committed record carries
  `:effect :propose` only (`site-prep.governor`'s `effect-not-propose`
  HARD check, defense-in-depth against a compromised/malfunctioning
  advisor).

## Decision

Implement a complete site-preparation-project-operations-coordination
actor (`cloud-itonami-isic-4312`), structured after
`cloud-itonami-isic-4311` module-for-module, narrowed to coordination-only
authority as described above:

1. **`site-prep.governor`** (Site Prep Governor) -- EIGHT HARD checks,
   all un-overridable by human approval: unknown op (outside the closed
   4-op allowlist), `:effect` not `:propose`, forbidden action class
   (equipment-control/direct-actuation/geotechnical-signoff-finalization
   markers), site not independently verified/registered (`:schedule-
   site-operation`/`:flag-safety-concern`/`:order-supplies` require the
   site's own recorded `:site-verified?` ground-truth field), legal-basis
   missing (uncovered jurisdiction), utility-locate survey incomplete,
   notification-lead-time insufficient (`:quantitative` jurisdictions
   only -- independently recomputed from the site's own recorded
   `:notification-lead-days-actual`, the same "ground truth, not
   self-report" discipline `construction.governor`'s weather-threshold
   recheck established), unresolved safety concern on file. High-stakes
   escalation: `:flag-safety-concern` and `:schedule-site-operation`
   ALWAYS escalate, unconditionally, at every phase; `:order-supplies`
   escalates above a cost threshold (default $5,000 USD) or below the
   confidence floor (0.6).

2. **`site-prep.facts`** -- per-jurisdiction utility-locate/excavation-
   notification legal-basis catalog (JPN/USA/DEU), citing REAL official
   sources verified via web search before writing (never fabricated):
   Japan's 労働安全衛生規則（昭和47年労働省令第32号）第355条 (pre-
   excavation ground/buried-object investigation duty) and 騒音規制法
   第14条 (7-calendar-day advance notification for specified construction
   work, including backhoe ≥80kW / bulldozer ≥40kW); the USA's OSHA 29
   CFR 1926.651(b) (excavation underground-installations standard) and
   the national 811 one-call system, honestly labeled a widely-adopted
   state-law convention (≥2 business days, e.g. Texas/New York) rather
   than a single federal statute with one fixed day count (unlike 4311's
   federal NESHAP 10-working-day rule); the EU/Germany's DIN 4124:2012-01
   (excavation/trench slope & shoring design standard) plus the general
   duty-of-care utility-operator query (Verkehrssicherungspflicht),
   honestly `:qualitative` -- no fixed EU-wide numeric lead-time, unlike
   Japan's 7 days or the USA's 2 days. `notification-lead-insufficient?`
   is the same three-valued (true/false/:qualitative/nil) ground-truth
   recheck family `construction.facts/weather-threshold-exceeded?` and
   `demolition.facts/notification-lead-insufficient?` established.

3. **`site-prep.registry`** -- pure-function record construction for
   site-record-log / schedule-proposal / safety-concern-flag /
   supply-order-proposal drafts (jurisdiction-scoped sequence numbering,
   e.g. `JPN-SCH-000000`), plus `render-safety-concern-notice` (the
   actual human-readable notice document, citing legal basis inline and
   explicitly disclaiming geotechnical/site-readiness sign-off authority
   in its own `## Status` section).

4. **`site-prep.store`** -- `Store` protocol, dual `MemStore`/
   `DatomicStore` (langchain.db) backend, contract-tested for parity.
   `DatomicStore` uses `langchain-store.core` (ADR-2607141600) for the
   EDN-blob codec / `:db.unique/identity` schema / seq-keyed event-log
   read-append pattern via a data-driven entity field-spec, instead of
   hand-rolling `enc`/`dec*`. UNLIKE every sibling actor's store, none of
   the four coordination-artifact histories is a one-time double-
   actuation-guarded event -- every op may recur any number of times for
   the same site, because this actor never actually dispatches/
   authorizes/files/hands over anything.

5. **`site-prep.notify`** -- mail+phone (Resend+Twilio, JVM-only real
   transports behind `#?(:clj ...)`) `Notifier` protocol, structurally
   identical to `demolition.notify` but fires `dispatch-safety-concern-
   notice!` to a site's `:safety-contacts` (licensed engineer/site
   supervisor/geotechnical authority) roster ONLY after a human has
   approved committing a `:flag-safety-concern` proposal (never on an
   auto-commit path, since that op is never auto-eligible at any phase).

6. **`site-prep.advisor`** -- the sealed LLM/proposal node (`Advisor`
   protocol, deterministic `mock-advisor` default + `llm-advisor` real-
   inference seam), producing all four ops' proposals, every one
   carrying `:effect :propose`.

7. **`site-prep.phase`** -- 0→3 rollout gate. `:log-site-record` and
   `:order-supplies` are the ONLY ops ever in a phase's `:auto` set (at
   phase 3); `:schedule-site-operation`/`:flag-safety-concern` are
   permanently absent from every phase's `:auto` set, belt-and-suspenders
   with the governor's `high-stakes` set.

8. **`site-prep.operation`** -- langgraph-clj StateGraph actor (real
   `interrupt-before #{:request-approval}` human-in-the-loop, checkpoint-
   based resume), structurally identical to `demolition.operation`.

9. **`site-prep.sim`** -- demo driver (`clojure -M:dev:run`) walking
   eight seeded sites through the full coordination episode and all six
   distinct HARD-hold paths (uncovered jurisdiction, unverified site,
   incomplete utility-locate survey, insufficient notification lead
   time, unresolved safety concern, op outside the closed allowlist)
   plus a cross-jurisdiction (USA) and a qualitative (DEU/EU) schedule
   walkthrough.

10. **Operations supported** (closed allowlist, all `:effect :propose`):
    `:log-site-record` (excavation-progress/soil-test/utility-locate data
    logging), `:schedule-site-operation` (excavation/earth-moving/
    clearing scheduling proposal, ALWAYS escalates), `:flag-safety-
    concern` (excavation-collapse/buried-utility-strike/contamination
    concern, ALWAYS escalates), `:order-supplies` (equipment/materials
    procurement proposal, escalates above cost threshold or on low
    confidence).

11. **Tests** -- 68 tests / 253 assertions, all green (facts, governor-
    contract, notify, phase, registry, store-contract suites).

12. **Documentation** -- README.md (documents the coordination-only
    scope prominently, explicitly notes 4211's robotics-premise framing
    does NOT apply verbatim, the same disclosure ADR-2607155000
    established for 4311), GOVERNANCE.md, CODE_OF_CONDUCT.md,
    CONTRIBUTING.md, SECURITY.md (all domain-adapted for site-preparation
    coordination). `blueprint.edn` (`:isic-rev5 "4312"`, `:robotics
    false` -- honestly disclosed, since this actor holds no robotics-
    simulation code). AGPL-3.0-or-later LICENSE copied verbatim from
    4311.

## Consequences

(+) Site preparation (ISIC 4312) site-preparation-project-operations-
coordination is now genuinely implemented and fully tested.

(+) Scope boundaries (no excavation/earth-moving-equipment control, no
geotechnical/site-readiness sign-off authority, no real-world actuation
of any kind) are hardcoded in governor checks (`forbidden-action-class`,
`effect-not-propose`), not just asserted in prose -- and covered by
dedicated tests exercising the governor directly with hypothetically-
compromised-advisor proposals the deterministic mock advisor itself
never produces.

(+) Safety-critical escalation (`:flag-safety-concern` ALWAYS human,
`:schedule-site-operation` ALWAYS human, at every phase, unconditionally)
is a core design invariant enforced at TWO independent layers
(`site-prep.phase`'s `:auto` set and `site-prep.governor`'s
`high-stakes` set), not an add-on.

(+) Per-jurisdiction legal-basis catalog cites REAL, web-search-verified
official sources (労働安全衛生規則第355条, 騒音規制法第14条, OSHA 29 CFR
1926.651(b), the national 811 one-call system, DIN 4124:2012-01) -- the
USA citation is honestly labeled a state-law convention (not a single
federal statute), and the EU/Germany entry is honestly `:qualitative` (no
fabricated numeric lead-time).

(+) `DatomicStore` uses the current `langchain-store.core` convention
(ADR-2607141600) instead of hand-rolling the EDN-blob codec.

(+) Portable `.cljc` implementation with no JVM-only constructs in `src`
(real Resend/Twilio transports in `site-prep.notify` are the same
`#?(:clj ...)`-guarded pattern `demolition.notify` established); `.clj`
test files run on the JVM only, per this fleet's verification protocol.

(-) Real Datomic/kotoba-server deployment (vs. the tested in-memory
`MemStore` default) is a follow-up, matching every sibling actor's own
current deployment status.

(-) `site-prep.facts`'s catalog covers only JPN/USA/DEU (3 of ~194
jurisdictions) -- an honestly-reported starting catalog, not a survey,
matching `demolition.facts`'s own disclosed scope.

## Verification

- `cloud-itonami-isic-4312`: `clojure -M:test` → "Ran 68 tests containing
  253 assertions. 0 failures, 0 errors." `clojure -M:lint` → 0 errors, 1
  warning (`clojure.string` required-but-unused-under-cljs-analysis in
  `site-prep.notify`, confirmed to be the SAME pre-existing warning
  `demolition.notify` in `cloud-itonami-isic-4311` itself carries -- not
  a regression). `clojure -M:dev:run` → demo runs end-to-end through
  every op and all HARD-hold paths, safety-concern notice document
  renders with real legal citations, no exceptions.
- Pushed to `main` as a single scaffold commit, `cc871a696e933af9d324da478e63fa6ffe9a9006`
  (no rename/correction needed -- ISIC 4312 was confirmed correct
  against the `kotoba-lang/industry` registry, `:id "4312"` = `:name
  "Site preparation"`, before scaffolding).
